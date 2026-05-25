package com.ebook.reader.crypto;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PackageCryptoService {

    public void verifyManifestSignature(ZipFile zipFile, PublicKey publicKey) throws Exception {
        byte[] manifest = readZipEntry(zipFile, "manifest.bin");
        byte[] signature = readZipEntry(zipFile, zipFile.getEntry("manifest.sig") != null ? "manifest.sig" : "license.sig");
        verifySignature(publicKey, manifest, signature, "Manifest signature invalid");
    }

    public void verifyLicenseSignature(ZipFile zipFile, PublicKey publicKey) throws Exception {
        byte[] license = readZipEntry(zipFile, "license.json");
        byte[] signature = readZipEntry(zipFile, "license.sig");
        verifySignature(publicKey, license, signature, "License signature invalid");
    }

    private void verifySignature(PublicKey publicKey, byte[] data, byte[] signature, String error) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        if (!verifier.verify(signature)) {
            throw new SecurityException(error);
        }
    }

    public JsonObject loadManifest(ZipFile zipFile) throws IOException {
        byte[] manifest = readZipEntry(zipFile, "manifest.bin");
        return JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    public byte[] decryptPageBlob(byte[] encryptedBlob, byte[] pageKey) throws Exception {
        byte[] nonce = new byte[12];
        System.arraycopy(encryptedBlob, 0, nonce, 0, 12);
        byte[] ciphertext = new byte[encryptedBlob.length - 12];
        System.arraycopy(encryptedBlob, 12, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        SecretKeySpec secretKey = new SecretKeySpec(pageKey, "AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        return cipher.doFinal(ciphertext);
    }

    public byte[] derivePageKey(byte[] masterKey, int pageNumber) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(masterKey);
        digest.update(String.valueOf(pageNumber).getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    public byte[] unwrapContentKey(String wrappedContentKeyB64, String deviceHash) throws Exception {
        byte[] blob = Base64.getDecoder().decode(wrappedContentKeyB64);
        byte[] nonce = new byte[12];
        System.arraycopy(blob, 0, nonce, 0, 12);
        byte[] ciphertext = new byte[blob.length - 12];
        System.arraycopy(blob, 12, ciphertext, 0, ciphertext.length);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(("ebook-reader-device-wrap-v1|" + deviceHash).getBytes(StandardCharsets.UTF_8));
        SecretKeySpec secretKey = new SecretKeySpec(digest.digest(), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(ciphertext);
    }

    public byte[] unwrapContentKeyWithPrivateKey(String wrappedContentKeyB64, PrivateKey privateKey) throws Exception {
        byte[] ciphertext = Base64.getDecoder().decode(wrappedContentKeyB64);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(ciphertext);
    }

    public void verifyEntryHashes(ZipFile zipFile, JsonObject manifest) throws Exception {
        if (!manifest.has("files") || !manifest.get("files").isJsonArray()) {
            return;
        }
        for (var item : manifest.getAsJsonArray("files")) {
            JsonObject f = item.getAsJsonObject();
            String path = f.get("path").getAsString();
            String expected = f.get("sha256").getAsString();
            byte[] data = readZipEntry(zipFile, path);
            String actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
            if (!expected.equals(actual)) {
                throw new SecurityException("Package entry hash mismatch: " + path);
            }
        }
    }

    public static PublicKey loadPublicKeyPem(String pem) throws Exception {
        if (pem == null || pem.isBlank() || pem.contains("REPLACE_WITH_BACKEND_SIGNING_PUBLIC_KEY")) {
            throw new IllegalArgumentException("public_key.pem is not configured with a valid backend signing public key");
        }
        String normalized = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim();
        byte[] keyBytes = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    public static String readResourceText(String resourcePath) throws IOException {
        try (InputStream in = PackageCryptoService.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static byte[] readZipEntry(ZipFile zipFile, String entryName) throws IOException {
        ZipEntry e = zipFile.getEntry(entryName);
        if (e == null) {
            throw new IOException("Missing zip entry: " + entryName);
        }
        try (InputStream in = zipFile.getInputStream(e)) {
            return in.readAllBytes();
        }
    }
}
