package com.ebook.reader.api;

import com.ebook.reader.config.AppConfig;
import com.ebook.reader.model.Book;
import com.ebook.reader.model.DeviceInfo;
import com.ebook.reader.model.SessionTokens;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BackendApiClient {
    private final OkHttpClient http = new OkHttpClient();
    private final Gson gson = new Gson();
    private final AppConfig config;

    public BackendApiClient(AppConfig config) {
        this.config = config;
    }

    public SessionTokens login(String email, String password) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("email", email);
        payload.addProperty("password", password);

        Request req = new Request.Builder()
            .url(config.baseUrl() + "/api/auth/login")
            .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Login failed: " + resp.code());
            }
            JsonObject obj = JsonParser.parseString(resp.body().string()).getAsJsonObject();
            return new SessionTokens(obj.get("access").getAsString(), obj.get("refresh").getAsString());
        }
    }

    public List<Book> listBooks(String accessToken) throws IOException {
        Request req = new Request.Builder()
            .url(config.baseUrl() + "/api/books")
            .get()
            .header("Authorization", "Bearer " + accessToken)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("List books failed: " + resp.code());
            }
            JsonArray arr = JsonParser.parseString(resp.body().string()).getAsJsonArray();
            List<Book> out = new ArrayList<>();
            arr.forEach(item -> {
                JsonObject o = item.getAsJsonObject();
                out.add(new Book(
                    o.get("id").getAsInt(),
                    o.get("title").getAsString(),
                    o.has("author") && !o.get("author").isJsonNull() ? o.get("author").getAsString() : "",
                    o.get("total_pages").getAsInt(),
                    o.get("status").getAsString()
                ));
            });
            return out;
        }
    }

    public List<DeviceInfo> listDevices(String accessToken) throws IOException {
        Request req = new Request.Builder()
            .url(config.baseUrl() + "/api/device/")
            .get()
            .header("Authorization", "Bearer " + accessToken)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("List devices failed: " + resp.code());
            }
            JsonArray arr = JsonParser.parseString(resp.body().string()).getAsJsonArray();
            List<DeviceInfo> out = new ArrayList<>();
            arr.forEach(item -> {
                JsonObject o = item.getAsJsonObject();
                out.add(new DeviceInfo(
                    o.get("id").getAsInt(),
                    o.get("device_hash").getAsString(),
                    o.get("device_name").getAsString(),
                    o.get("os_version").getAsString(),
                    o.get("app_version").getAsString()
                ));
            });
            return out;
        }
    }

    public DeviceInfo registerDevice(String accessToken, String deviceHash, String deviceName, String osVersion, String appVersion) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("device_hash", deviceHash);
        payload.addProperty("device_name", deviceName);
        payload.addProperty("os_version", osVersion);
        payload.addProperty("app_version", appVersion);

        Request req = new Request.Builder()
            .url(config.baseUrl() + "/api/device/register")
            .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
            .header("Authorization", "Bearer " + accessToken)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() && resp.code() != 201) {
                throw new IOException("Device register failed: " + resp.code());
            }
            JsonObject o = JsonParser.parseString(resp.body().string()).getAsJsonObject();
            return new DeviceInfo(
                o.get("id").getAsInt(),
                o.get("device_hash").getAsString(),
                o.get("device_name").getAsString(),
                o.get("os_version").getAsString(),
                o.get("app_version").getAsString()
            );
        }
    }

    public boolean verifyLicense(String accessToken, int ebookId, int deviceId) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("ebook_id", ebookId);
        payload.addProperty("device_id", deviceId);

        Request req = new Request.Builder()
            .url(config.baseUrl() + "/api/license/verify")
            .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
            .header("Authorization", "Bearer " + accessToken)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            return resp.isSuccessful();
        }
    }

    public Response executeDownload(String accessToken, int ebookId, int deviceId, Long rangeStart) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("ebook_id", ebookId);
        payload.addProperty("device_id", deviceId);

        Request.Builder rb = new Request.Builder()
            .url(config.downloadUrl())
            .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
            .header("Authorization", "Bearer " + accessToken);

        if (rangeStart != null && rangeStart > 0) {
            rb.header("Range", "bytes=" + rangeStart + "-");
        }
        return http.newCall(rb.build()).execute();
    }
}
