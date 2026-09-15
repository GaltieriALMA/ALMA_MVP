package com.alma.mvp;

import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AlmaApiClient {
    public String chat(String userId, String sessionId, String message) throws Exception {
        URL url = new URL(ApiConfig.BASE_URL + "/chat");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        JSONObject payload = new JSONObject();
        payload.put("user_id", userId);
        payload.put("session_id", sessionId);
        payload.put("message", message);

        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        } finally {
            connection.disconnect();
        }

        if (status < 200 || status >= 300) {
            throw new IOException("ALMA API HTTP " + status);
        }

        return new JSONObject(body.toString()).getString("text");
    }
}
