package com.ebook.reader.crypto;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocalBookReader {
    private static final Pattern WORD_PATTERN = Pattern.compile("\\S+");
    private final PackageCryptoService crypto = new PackageCryptoService();
    private final Path packagePath;
    private final byte[] legacyMasterKey;
    private final String deviceHash;
    private final PrivateKey devicePrivateKey;

    public LocalBookReader(Path packagePath, byte[] legacyMasterKey, String deviceHash, PrivateKey devicePrivateKey) {
        this.packagePath = packagePath;
        this.legacyMasterKey = legacyMasterKey;
        this.deviceHash = deviceHash;
        this.devicePrivateKey = devicePrivateKey;
    }

    public int totalPages() throws Exception {
        try (ZipFile zf = new ZipFile(packagePath.toFile())) {
            JsonObject manifest = crypto.loadManifest(zf);
            return manifest.get("total_pages").getAsInt();
        }
    }

    public void verify(PackageCryptoService cryptoService, String publicKeyPem) throws Exception {
        try (ZipFile zf = new ZipFile(packagePath.toFile())) {
            var publicKey = PackageCryptoService.loadPublicKeyPem(publicKeyPem);
            cryptoService.verifyManifestSignature(zf, publicKey);
            JsonObject manifest = cryptoService.loadManifest(zf);
            cryptoService.verifyEntryHashes(zf, manifest);
            if (manifestVersion(manifest) >= 2) {
                cryptoService.verifyLicenseSignature(zf, publicKey);
                resolveContentKey(zf, manifest);
            }
        }
    }

    public Image renderPageImage(int page) throws Exception {
        try (ZipFile zf = new ZipFile(packagePath.toFile())) {
            String path = "pages/p" + page + ".img";
            byte[] blob = PackageCryptoService.readZipEntry(zf, path);
            byte[] key = crypto.derivePageKey(resolveContentKey(zf, crypto.loadManifest(zf)), page);
            byte[] raw = crypto.decryptPageBlob(blob, key);
            return new Image(new ByteArrayInputStream(raw));
        }
    }

    public String readPageText(int page) throws Exception {
        try (ZipFile zf = new ZipFile(packagePath.toFile())) {
            String path = "text/p" + page + ".txt";
            byte[] blob = PackageCryptoService.readZipEntry(zf, path);
            byte[] key = crypto.derivePageKey(resolveContentKey(zf, crypto.loadManifest(zf)), page);
            byte[] raw = crypto.decryptPageBlob(blob, key);
            return new String(raw, StandardCharsets.UTF_8);
        }
    }

    private int manifestVersion(JsonObject manifest) {
        if (manifest.has("package_format_version")) return manifest.get("package_format_version").getAsInt();
        if (manifest.has("version")) return manifest.get("version").getAsInt();
        return 1;
    }

    private byte[] resolveContentKey(ZipFile zf, JsonObject manifest) throws Exception {
        if (manifestVersion(manifest) < 2) {
            if (legacyMasterKey == null || legacyMasterKey.length == 0) {
                throw new SecurityException("Legacy package requires drm.masterKeyB64");
            }
            return legacyMasterKey;
        }
        JsonObject license = JsonParser.parseString(
            new String(PackageCryptoService.readZipEntry(zf, "license.json"), StandardCharsets.UTF_8)
        ).getAsJsonObject();
        if (!license.has("device_hash") || !license.get("device_hash").getAsString().equals(deviceHash)) {
            throw new SecurityException("License device mismatch");
        }
        if (!license.has("ebook_id") || license.get("ebook_id").getAsInt() != manifest.get("ebook_id").getAsInt()) {
            throw new SecurityException("License ebook mismatch");
        }
        String alg = license.has("key_wrap_alg") ? license.get("key_wrap_alg").getAsString() : "";
        if (alg.startsWith("RSA-OAEP")) {
            if (devicePrivateKey == null) {
                throw new SecurityException("Device private key missing");
            }
            return crypto.unwrapContentKeyWithPrivateKey(license.get("wrapped_content_key").getAsString(), devicePrivateKey);
        }
        return crypto.unwrapContentKey(license.get("wrapped_content_key").getAsString(), deviceHash);
    }

    public PageTextMap readPageTextMap(int page) throws Exception {
        String raw = readPageText(page);
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        double width = root.has("width") ? root.get("width").getAsDouble() : 0.0;
        double height = root.has("height") ? root.get("height").getAsDouble() : 0.0;
        List<TextSpanBox> spans = new ArrayList<>();
        JsonArray blocks = root.has("blocks") ? root.getAsJsonArray("blocks") : new JsonArray();
        blocks.forEach(b -> {
            JsonObject bo = b.getAsJsonObject();
            if (!bo.has("lines")) return;
            bo.getAsJsonArray("lines").forEach(l -> {
                JsonObject lo = l.getAsJsonObject();
                if (!lo.has("spans")) return;
                lo.getAsJsonArray("spans").forEach(s -> {
                    JsonObject so = s.getAsJsonObject();
                    String text = so.has("text") ? so.get("text").getAsString() : "";
                    if (text.isBlank() || !so.has("bbox")) return;
                    JsonArray bb = so.getAsJsonArray("bbox");
                    if (bb.size() < 4) return;
                    double x0 = bb.get(0).getAsDouble();
                    double y0 = bb.get(1).getAsDouble();
                    double x1 = bb.get(2).getAsDouble();
                    double y1 = bb.get(3).getAsDouble();
                    double w = x1 - x0;
                    double h = y1 - y0;
                    if (w <= 0 || h <= 0) return;
                    List<TextSpanBox> words = splitSpanToWords(text, x0, y0, w, h);
                    if (!words.isEmpty()) {
                        spans.addAll(words);
                    } else {
                        spans.add(new TextSpanBox(text, x0, y0, w, h));
                    }
                });
            });
        });
        return new PageTextMap(width, height, spans);
    }

    private List<TextSpanBox> splitSpanToWords(String text, double x, double y, double w, double h) {
        List<TextSpanBox> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        int totalChars = text.length();
        if (totalChars <= 0) return out;
        double charW = w / totalChars;
        Matcher m = WORD_PATTERN.matcher(text);
        while (m.find()) {
            int a = m.start();
            int b = m.end();
            if (b <= a) continue;
            String word = text.substring(a, b).trim();
            if (word.isEmpty()) continue;
            double wx = x + (a * charW);
            double ww = Math.max(1.0, (b - a) * charW);
            out.add(new TextSpanBox(word, wx, y, ww, h));
        }
        return out;
    }

    public int findFirstPageContaining(String query) throws Exception {
        String q = query.toLowerCase();
        int total = totalPages();
        for (int i = 1; i <= total; i++) {
            String txt = readPageText(i).toLowerCase();
            if (txt.contains(q)) {
                return i;
            }
        }
        return -1;
    }

    public record TextSpanBox(String text, double x, double y, double w, double h) {}
    public record PageTextMap(double width, double height, List<TextSpanBox> spans) {}
}
