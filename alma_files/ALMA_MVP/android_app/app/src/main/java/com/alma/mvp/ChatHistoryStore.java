package com.alma.mvp;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared history store for the Activity and foreground Service. */
public final class ChatHistoryStore {
    private static final Object LOCK = new Object();
    private static final int MAX_HISTORY = 50000;
    private static final int MAX_ENTRY = 12000;
    private ChatHistoryStore() {}

    public static String sessionId(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = context.getSharedPreferences("alma_chat", Context.MODE_PRIVATE);
            String id = prefs.getString("session_id", "");
            if (id == null || id.isEmpty()) {
                id = UUID.randomUUID().toString();
                prefs.edit().putString("session_id", id).commit();
            }
            return id;
        }
    }

    public static String load(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = context.getSharedPreferences("alma_chat", Context.MODE_PRIVATE);
            String raw = prefs.getString("history", "");
            String clean = trim(sanitize(raw == null ? "" : raw.replace("\\n", "\n")));
            if (!clean.equals(raw)) {
                SharedPreferences.Editor edit = prefs.edit();
                if (!prefs.contains("history_pre_0816_diag_backup"))
                    edit.putString("history_pre_0816_diag_backup", raw);
                edit.putString("history", clean).commit();
            }
            return clean;
        }
    }

    public static void append(Context context, String entry) {
        if (entry == null || entry.trim().isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences prefs = context.getSharedPreferences("alma_chat", Context.MODE_PRIVATE);
            String previous = prefs.getString("history", "");
            if (previous != null && previous.contains("[DIAG]")
                    && !prefs.contains("history_pre_0816_diag_backup")) {
                prefs.edit().putString("history_pre_0816_diag_backup", previous).commit();
            }
            String clean = sanitize(previous == null ? "" : previous.replace("\\n", "\n"));
            String next = clean.isEmpty() ? sanitize(entry) : clean + "\n\n" + sanitize(entry);
            prefs.edit().putString("history", trim(next)).commit();
        }
    }

    private static String sanitize(String input) {
        // Limpieza no destructiva de conversaciones: solo descarta entradas
        // internas [DIAG], nunca mensajes del usuario ni respuestas de ALMA.
        StringBuilder visible = new StringBuilder();
        for (String section : input.split("\n\n", -1)) {
            if (section.trim().startsWith("[DIAG]")) continue;
            if (visible.length() > 0) visible.append("\n\n");
            visible.append(section);
        }
        String result = visible.toString().replaceAll("__IMAGE__:[A-Za-z0-9+/=]+", "[Foto]");
        result = result.replaceAll("(?<![A-Za-z0-9+/=])[A-Za-z0-9+/=]{201,}(?![A-Za-z0-9+/=])", "[Contenido codificado omitido]");
        if (result.length() <= MAX_ENTRY) return result;
        // Preserve separate messages when importing old history; truncate individual entries only.
        String[] entries = result.split("\n\n", -1);
        StringBuilder out = new StringBuilder();
        for (String e : entries) {
            if (out.length() > 0) out.append("\n\n");
            out.append(e.length() > MAX_ENTRY ? e.substring(0, MAX_ENTRY) + " [recortado]" : e);
        }
        return out.toString();
    }

    private static String trim(String value) {
        if (value.length() <= MAX_HISTORY) return value;
        // Never start inside an entry: discard oldest complete entries.
        while (value.length() > MAX_HISTORY) {
            int cut = value.indexOf("\n\n");
            if (cut < 0) return value.substring(Math.max(0, value.length() - MAX_HISTORY));
            value = value.substring(cut + 2);
        }
        return value;
    }
}
