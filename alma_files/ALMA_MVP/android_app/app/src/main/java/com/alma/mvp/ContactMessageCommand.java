package com.alma.mvp;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ContactMessageCommand {

    static final String METHOD_WHATSAPP = "whatsapp";
    static final String METHOD_SMS = "sms";

    static final class Draft {
        final String requestedName;
        final String text;
        final String preferredMethod;

        Draft(
                String requestedName,
                String text,
                String preferredMethod
        ) {
            this.requestedName = requestedName;
            this.text = text;
            this.preferredMethod = preferredMethod;
        }
    }

    static Draft parse(String message) {
        if (message == null) return null;

        String n = normalize(message);

        String marker;

        if (n.contains(" que diga ")) {
            marker = " que diga ";
        } else if (n.contains(" diciendo ")) {
            marker = " diciendo ";
        } else {
            return null;
        }

        int pos = n.indexOf(marker);

        String command = n.substring(0, pos).trim();
        String text = n.substring(pos + marker.length()).trim();

        if (text.isEmpty()) return null;

        String method = null;
        String name = null;

        Matcher whatsapp = Pattern.compile(
                "^(?:manda|mandale|envia|enviale)\\s+(?:un\\s+)?(?:whatsapp|watsap)\\s+a\\s+(.+)$"
        ).matcher(command);

        Matcher sms = Pattern.compile(
                "^(?:manda|mandale|envia|enviale)\\s+(?:un\\s+)?(?:sms|mensaje\\s+de\\s+texto)\\s+a\\s+(.+)$"
        ).matcher(command);

        Matcher generic = Pattern.compile(
                "^(?:manda|mandale|envia|enviale)\\s+(?:un\\s+)?mensaje\\s+a\\s+(.+)$"
        ).matcher(command);

        Matcher direct = Pattern.compile(
                "^(?:manda|mandale|envia|enviale)\\s+a\\s+(.+)$"
        ).matcher(command);

        if (whatsapp.find()) {
            method = METHOD_WHATSAPP;
            name = whatsapp.group(1);
        } else if (sms.find()) {
            method = METHOD_SMS;
            name = sms.group(1);
        } else if (generic.find()) {
            name = generic.group(1);
        } else if (direct.find()) {
            name = direct.group(1);
        }

        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        return new Draft(
                name.trim(),
                text,
                method
        );
    }

    static String parseMethod(String message) {
        String n = normalize(message);

        if (n.contains("whatsapp") || n.contains("watsap")) {
            return METHOD_WHATSAPP;
        }

        if (n.equals("sms")
                || n.equals("mensaje")
                || n.equals("mensaje de texto")
                || n.equals("texto")) {
            return METHOD_SMS;
        }

        return null;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(
                value.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "")
         .replaceAll("\\s+", " ")
         .trim();
    }

    private ContactMessageCommand() {}
}
