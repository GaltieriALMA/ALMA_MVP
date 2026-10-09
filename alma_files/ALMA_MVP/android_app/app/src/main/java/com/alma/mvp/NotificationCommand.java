package com.alma.mvp;

import java.text.Normalizer;
import java.util.Locale;

final class NotificationCommand {

    static boolean matches(String message) {
        if (message == null) return false;

        String n = Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "")
         .replaceAll("[?¿!¡]", "")
         .replaceAll("\\s+", " ")
         .trim();

        return n.equals("que notificaciones tengo")
                || n.equals("que notificacion tengo")
                || n.equals("lee mis notificaciones")
                || n.equals("leeme mis notificaciones")
                || n.equals("lee las notificaciones")
                || n.equals("leeme las notificaciones")
                || n.equals("ultima notificacion")
                || n.equals("lee la ultima notificacion")
                || n.equals("leeme la ultima notificacion");
    }

    static int requestedLimit(String message) {
        if (message == null) return 5;

        String n = message.toLowerCase(Locale.ROOT);

        if (n.contains("última")
                || n.contains("ultima")) {
            return 1;
        }

        return 5;
    }

    private NotificationCommand() {}
}
