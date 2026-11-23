package com.alma.mvp.alarm;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Pronóstico consultado AL SONAR la alarma; nunca reutiliza datos viejos. */
public final class AlmaWakeWeather {
    private AlmaWakeWeather() {}

    private static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast"
            + "?latitude=-34.34602&longitude=-58.79442"
            + "&current=temperature_2m,weather_code"
            + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            + "&timezone=America%2FArgentina%2FBuenos_Aires&forecast_days=1";

    public static String todaySummary() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(FORECAST_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("Weather HTTP " + status);
            StringBuilder response = new StringBuilder();
            try (InputStream stream = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                    if (response.length() > 500_000) throw new IllegalStateException("Weather response too large");
                }
            }
            JSONObject body = new JSONObject(response.toString());
            JSONObject current = body.getJSONObject("current");
            JSONObject daily = body.getJSONObject("daily");
            int currentTemp = (int) Math.round(current.getDouble("temperature_2m"));
            int maxTemp = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
            int minTemp = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
            int code = current.getInt("weather_code");
            int rain = daily.getJSONArray("precipitation_probability_max").getInt(0);
            String description = descriptionFor(code);
            return String.format(Locale.forLanguageTag("es-AR"),
                    "En Belén de Escobar hay %d grados y %s. "
                    + "Para hoy se esperan una mínima de %d y una máxima de %d grados. "
                    + "La probabilidad máxima de precipitación es del %d por ciento.",
                    currentTemp, description, minTemp, maxTemp, rain);
        } catch (Exception ignored) {
            return "No pude consultar el tiempo de Belén de Escobar ahora.";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String descriptionFor(int code) {
        if (code == 0) return "cielo despejado";
        if (code == 1 || code == 2) return "cielo parcialmente nublado";
        if (code == 3) return "cielo nublado";
        if (code == 45 || code == 48) return "niebla";
        if (code >= 51 && code <= 57) return "llovizna";
        if (code >= 61 && code <= 67) return "lluvia";
        if (code >= 71 && code <= 77) return "nieve";
        if (code >= 80 && code <= 82) return "chaparrones";
        if (code >= 85 && code <= 86) return "chaparrones de nieve";
        if (code >= 95) return "tormentas";
        return "condiciones meteorológicas variables";
    }
}
