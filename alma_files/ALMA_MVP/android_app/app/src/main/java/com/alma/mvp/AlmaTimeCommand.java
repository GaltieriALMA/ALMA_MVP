package com.alma.mvp;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.Locale;

public final class AlmaTimeCommand {
    private AlmaTimeCommand() {}

    public static String respond(String message) {
        if (message == null) return null;
        Locale es = Locale.forLanguageTag("es-AR");
        String text = Normalizer.normalize(
            message.toLowerCase(es), Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "");
        ZonedDateTime now = ZonedDateTime.now();

        if (text.contains("que hora es") ||
            text.contains("decime la hora") ||
            text.contains("hora actual")) {
            return "Son las " + now.format(
                DateTimeFormatter.ofPattern("HH:mm", es));
        }

        if (text.contains("que dia es") ||
            text.contains("que fecha es") ||
            text.contains("fecha de hoy")) {
            return "Hoy es " + now.format(
                DateTimeFormatter.ofPattern(
                    "EEEE d 'de' MMMM 'de' yyyy", es));
        }
        return null;
    }
}
