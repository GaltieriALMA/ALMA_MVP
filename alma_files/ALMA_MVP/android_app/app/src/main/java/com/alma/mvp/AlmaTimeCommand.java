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
        ).replaceAll("\\p{M}", "").replaceAll("[^a-z0-9 ]", " ")
         .replaceAll("\\s+", " ").trim();
        ZonedDateTime now = ZonedDateTime.now();

        if (text.contains("que hora es") ||
            text.contains("la hora") ||
            text.contains("hora exacta") ||
            text.contains("que horas son") ||
            text.contains("hora tenemos") ||
            text.contains("decime la hora") ||
            text.contains("hora actual")) {
            return "Son las " + now.format(
                DateTimeFormatter.ofPattern("HH:mm", es));
        }

        if (text.contains("en que ano estamos") ||
            text.contains("que ano es") ||
            text.contains("ano actual")) {
            return "Estamos en el año " + now.getYear();
        }

        if (text.contains("en que mes") || text.contains("que mes es") ||
            text.contains("mes actual") || text.contains("cual es el mes")) {
            return "Estamos en " + now.format(DateTimeFormatter.ofPattern("MMMM 'de' yyyy", es));
        }

        if (text.contains("que dia es") ||
            text.contains("que fecha es") ||
            text.contains("fecha de hoy") ||
            (text.contains("fecha") && text.contains("hoy")) ||
              text.contains("fecha completa") ||
              text.contains("dia de hoy") ||
              text.contains("en que fecha estamos") ||
            text.contains("que dia mes y ano") ||
            text.contains("dia mes y ano") ||
            (text.contains("en que fecha") && text.contains("ano"))) {
            return "Hoy es " + now.format(
                DateTimeFormatter.ofPattern(
                    "EEEE d 'de' MMMM 'de' yyyy", es));
        }
        return null;
    }
}
