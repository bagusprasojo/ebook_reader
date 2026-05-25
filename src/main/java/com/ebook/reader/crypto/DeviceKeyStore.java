package com.ebook.reader.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class DeviceKeyStore {
    private final Path privateKeyPath;
    private final Path publicKeyPath;

    public DeviceKeyStore(Path root) {
        this.privateKeyPath = root.resolve("device_private_key.pem");
        this.publicKeyPath = root.resolve("device_public_key.pem");
    }

    public void ensureKeyPair() throws Exception {
        if (Files.exists(privateKeyPath) && Files.exists(publicKeyPath)) {
            return;
        }
        Files.createDirectories(privateKeyPath.getParent());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair kp = generator.generateKeyPair();
        Files.writeString(privateKeyPath, toPem("PRIVATE KEY", kp.getPrivate().getEncoded()), StandardCharsets.UTF_8);
        Files.writeString(publicKeyPath, toPem("PUBLIC KEY", kp.getPublic().getEncoded()), StandardCharsets.UTF_8);
    }

    public String publicKeyPem() throws Exception {
        ensureKeyPair();
        return Files.readString(publicKeyPath, StandardCharsets.UTF_8);
    }

    public PrivateKey privateKey() throws Exception {
        ensureKeyPair();
        String pem = Files.readString(privateKeyPath, StandardCharsets.UTF_8)
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim();
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private String toPem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }
}
