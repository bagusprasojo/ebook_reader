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
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PackageCryptoService {

    public void verifyManifestSignature(ZipFile zipFile, PublicKey publicKey) throws Exception {
        byte[] manifest = readZipEntry(zipFile, "manifest.bin");
        byte[] signature = readZipEntry(zipFile, "license.sig");

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(manifest);
        if (!verifier.verify(signature)) {
            throw new SecurityException("Manifest signature invalid");
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
