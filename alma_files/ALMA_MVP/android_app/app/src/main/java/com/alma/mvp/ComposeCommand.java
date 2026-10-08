package com.alma.mvp;

import java.text.Normalizer;
import java.util.Locale;

final class ComposeCommand {

    static final class Draft {
        final String type;
        final String text;

        Draft(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    static Draft parse(String message) {
        if (message == null) return null;

        String n = Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "")
         .replaceAll("\\s+", " ")
         .trim();

        String type = null;

        if (n.contains("whatsapp") || n.contains("watsap")) {
            type = "whatsapp";
        } else if (n.contains("correo")
                || n.contains("mail")
                || n.contains("email")) {
            type = "email";
        }

        if (type == null) return null;

        boolean command =
                n.startsWith("prepara ")
                || n.startsWith("preparame ")
                || n.startsWith("escribi ")
                || n.startsWith("redacta ");

        if (!command) return null;

        String marker;

        if (n.contains(" que diga ")) {
            marker = " que diga ";
        } else if (n.contains(" diciendo ")) {
            marker = " diciendo ";
        } else {
            return null;
        }

        int pos = n.indexOf(marker);

        if (pos < 0) return null;

        String text =
                n.substring(pos + marker.length()).trim();

        if (text.isEmpty()) return null;

        return new Draft(type, text);
    }

    private ComposeCommand() {}
}
