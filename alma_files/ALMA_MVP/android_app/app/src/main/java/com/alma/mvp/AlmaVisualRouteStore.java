package com.alma.mvp;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Solo guarda descripciones y su orden. No almacena fotos ni posiciones GPS. */
public final class AlmaVisualRouteStore {
    private static final String PREFS = "alma_route_visual_0815";
    private AlmaVisualRouteStore() {}

    public static void start(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String started = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("es", "AR"))
                .format(new Date());
        prefs.edit().putString("started", started).putString("notes", "[]").apply();
    }

    public static void add(Context context, String description) {
        if (description == null || description.trim().isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray notes = new JSONArray(prefs.getString("notes", "[]"));
            if (notes.length() >= 8) return;
            String value = description.trim().replace('\n', ' ');
            if (value.length() > 380) value = value.substring(0, 380);
            JSONObject item = new JSONObject();
            item.put("hora", new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()));
            item.put("referencia", value);
            notes.put(item);
            prefs.edit().putString("notes", notes.toString()).apply();
        } catch (Exception ignored) {
            // Un registro ilegible no debe cerrar la aplicación ni tocar los otros módulos.
        }
    }

    public static int count(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONArray(prefs.getString("notes", "[]")).length();
        } catch (Exception ignored) { return 0; }
    }

    public static String summary(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String started = prefs.getString("started", "");
        try {
            JSONArray notes = new JSONArray(prefs.getString("notes", "[]"));
            if (notes.length() == 0) return "Todavía no registré referencias visuales de un recorrido.";
            StringBuilder result = new StringBuilder("Recorrido del ").append(started)
                    .append(". Referencias observadas en orden: ");
            for (int i = 0; i < notes.length(); i++) {
                JSONObject item = notes.getJSONObject(i);
                if (i > 0) result.append("; ");
                result.append(i + 1).append(", ").append(item.optString("referencia"));
            }
            result.append(". Estas referencias no confirman una ubicación ni un giro exacto.");
            return result.toString();
        } catch (Exception ignored) { return "No pude recuperar la memoria visual del recorrido."; }
    }
}
