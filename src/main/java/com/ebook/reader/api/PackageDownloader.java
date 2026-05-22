package com.ebook.reader.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import okhttp3.Response;

public class PackageDownloader {
    private final BackendApiClient api;

    public PackageDownloader(BackendApiClient api) {
        this.api = api;
    }

    public Path download(String accessToken, int ebookId, int deviceId, Path targetFile) throws IOException {
        Files.createDirectories(targetFile.getParent());
        long existing = Files.exists(targetFile) ? Files.size(targetFile) : 0;

        try (Response resp = api.executeDownload(accessToken, ebookId, deviceId, existing)) {
            if (!(resp.code() == 200 || resp.code() == 206)) {
                throw new IOException("Download failed: " + resp.code());
            }
            if (resp.body() == null) {
                throw new IOException("Empty download body");
            }

            try (InputStream in = resp.body().byteStream();
                 RandomAccessFile raf = new RandomAccessFile(targetFile.toFile(), "rw")) {
                if (resp.code() == 206) {
                    raf.seek(existing);
                } else {
                    raf.setLength(0);
                    raf.seek(0);
                }

                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    raf.write(buf, 0, n);
                }
            }
        }

        return targetFile;
    }
}
