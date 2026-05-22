package com.ebook.reader.config;

public record AppConfig(String baseUrl, String downloadPath, String masterKeyB64) {
    public String downloadUrl() {
        return baseUrl + downloadPath;
    }
}
