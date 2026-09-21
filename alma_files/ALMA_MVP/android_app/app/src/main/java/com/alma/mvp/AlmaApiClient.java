package com.alma.mvp;

import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AlmaApiClient {
    public String chat(String userId, String sessionId, String message, String accessToken) throws Exception {
        URL url = new URL(ApiConfig.BASE_URL + "/chat");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(90000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IOException("ALMA access token missing");
        }
        connection.setRequestProperty("X-ALMA-API-Key", accessToken);

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
            throw new IOException("ALMA API HTTP " + status + ": " + body);
        }

        return new JSONObject(body.toString()).getString("text");
    }
        public byte[] tts(String text, String accessToken) throws Exception {
                    URL url = new URL(ApiConfig.BASE_URL + "/tts");
                            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                                    connection.setRequestMethod("POST");
                                            connection.setConnectTimeout(90000);
                                                    connection.setReadTimeout(120000);
                                                            connection.setDoOutput(true);
                                                                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                                                                            connection.setRequestProperty("Accept", "audio/mpeg");

                                                                                    if (accessToken == null || accessToken.trim().isEmpty()) {
                                                                                                throw new IOException("ALMA access token missing");
                                                                                                        }

                                                                                                                connection.setRequestProperty("X-ALMA-API-Key", accessToken);

                                                                                                                        JSONObject payload = new JSONObject();
                                                                                                                                payload.put("text", text);

                                                                                                                                        try (OutputStream out = connection.getOutputStream()) {
                                                                                                                                                    out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                                                                                                                                                            }

                                                                                                                                                                    int status = connection.getResponseCode();
                                                                                                                                                                            InputStream stream = status >= 200 && status < 300
                                                                                                                                                                                            ? connection.getInputStream()
                                                                                                                                                                                                            : connection.getErrorStream();

                                                                                                                                                                                                                    ByteArrayOutputStream audio = new ByteArrayOutputStream();
                                                                                                                                                                                                                            byte[] buffer = new byte[4096];
                                                                                                                                                                                                                                    int read;

                                                                                                                                                                                                                                            try {
                                                                                                                                                                                                                                                        while ((read = stream.read(buffer)) != -1) {
                                                                                                                                                                                                                                                                        audio.write(buffer, 0, read);
                                                                                                                                                                                                                                                                                    }
                                                                                                                                                                                                                                                                                            } finally {
                                                                                                                                                                                                                                                                                                        stream.close();
                                                                                                                                                                                                                                                                                                                    connection.disconnect();
                                                                                                                                                                                                                                                                                                                            }

                                                                                                                                                                                                                                                                                                                                    if (status < 200 || status >= 300) {
                                                                                                                                                                                                                                                                                                                                                throw new IOException("ALMA TTS HTTP " + status);
                                                                                                                                                                                                                                                                                                                                                        }

                                                                                                                                                                                                                                                                                                                                                                return audio.toByteArray();
                                                                                                                                                                                                                                                                                                                                                                    }
        
}
