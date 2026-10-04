package com.alma.mvp;

import java.net.HttpURLConnection;
import java.net.URL;

public class SmartHomeLocalClient {

    private static final String BASE_URL = "http://127.0.0.1:8765";

    public boolean send(String action) throws Exception {
        URL url = new URL(BASE_URL + "/" + action);

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);

        int status = connection.getResponseCode();
        connection.disconnect();

        return status >= 200 && status < 300;
    }
}
