package com.alma.mvp.alarm;

import java.text.Normalizer;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AlmaAlarmCommand {

    private AlmaAlarmCommand() {}

    public static final class Parsed {
        public final int hour;
        public final int minute;
        public final long triggerAtMillis;
        public final String reminderText;

        Parsed(
                int hour,
                int minute,
                long triggerAtMillis,
                String reminderText
        ) {
            this.hour = hour;
            this.minute = minute;
            this.triggerAtMillis = triggerAtMillis;
            this.reminderText = reminderText == null ? "" : reminderText.trim();
        }
    }

    public static Parsed parse(String message) {
        if (message == null) return null;

        String original = message.trim();

        String text = Normalizer.normalize(
                original.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "");

        // Las transcripciones Android pueden escribir cantidades en palabras.
        // Sólo normalizamos cantidades junto a unidades o a expresiones horarias.
        text = text.replaceAll("[¿?¡!]", " ")
                .replaceAll("\\s+", " ").trim();
        text = text.replaceAll("\\bun par de minutos?\\b", "2 minutos")
                .replaceAll("\\bminutitos?\\b", "minutos");
        text = normalizeSpokenDurations(text);
        text = normalizeSpokenClock(text);

        // Negaciones y solicitudes de cancelar nunca deben CREAR una alarma.
        if (Pattern.compile("\\b(?:no\\s+(?:pongas|programes|quiero|actives|me\\s+despiertes)"
                + "|cancela|cancelar|borra|borraremos|elimina|desactiva)\\b")
                .matcher(text).find()) {
            return null;
        }

        String reminderText = extractReminderText(original);

        Matcher snooze = Pattern.compile(
                "(?:otros?\\s+)?(\\d{1,3})\\s+minutos?\\s+mas"
        ).matcher(text);

        if (snooze.find()) {
            int minutes = Integer.parseInt(snooze.group(1));

            if (minutes < 1 || minutes > 180) {
                return null;
            }

            return relativeTarget(minutes, reminderText);
        }

        boolean alarmIntent =
                text.contains("alarma")
                || text.contains("despertame")
                || text.contains("despiertame")
                || text.contains("levantame")
                || text.contains("despertador")
                || text.contains("temporizador");

        boolean reminderIntent =
                text.contains("recordame")
                || text.contains("recordarme")
                || text.contains("recuerdame")
                || text.contains("haceme acordar")
                || text.contains("acordame")
                || text.contains("avisame");

        if (!alarmIntent && !reminderIntent) return null;

        Matcher halfHour = Pattern.compile(
                "(?:dentro\\s+de|en)\\s+media\\s+hora"
        ).matcher(text);

        if (halfHour.find()) {
            return relativeTarget(30, reminderText);
        }

        Matcher relativeMinutes = Pattern.compile(
                "(?:para\\s+)?(?:dentro\\s+de|de\\s+aca\\s+a|de\\s+aqui\\s+a|en)\\s+"
                + "(\\d{1,4})\\s+minutos?"
        ).matcher(text);

        if (relativeMinutes.find()) {
            int minutes = parseCount(relativeMinutes.group(1));

            if (minutes < 1 || minutes > 1440) {
                return null;
            }
            // "2 minutos y medio" no debe programarse como 2 exactos.
            if (text.substring(relativeMinutes.end()).trim().matches("^y\\s+(?:(?:medio|media|cuarto|pico|tres\\s+cuartos)\\b|(?:\\d{1,3}|[a-z]+)\\s+segundos?\\b).*")) {
                return null;
            }
            return relativeTarget(minutes, reminderText);
        }

        Matcher relativeHours = Pattern.compile(
                "(?:para\\s+)?(?:dentro\\s+de|de\\s+aca\\s+a|de\\s+aqui\\s+a|en)\\s+"
                + "(\\d{1,2})\\s+horas?"
                + "(?:\\s+y\\s+(media|cuarto|tres\\s+cuartos|\\d{1,3}\\s+minutos?))?"
        ).matcher(text);

        if (relativeHours.find()) {
            int hours = parseCount(relativeHours.group(1));
            if (hours < 1 || hours > 48) return null;

            int extraMinutes = 0;
            String extra = relativeHours.group(2);
            if (extra != null) {
                if (extra.equals("media")) extraMinutes = 30;
                else if (extra.equals("cuarto")) extraMinutes = 15;
                else if (extra.equals("tres cuartos")) extraMinutes = 45;
                else {
                    String count = extra.replaceAll("\\s+minutos?$", "");
                    extraMinutes = parseCount(count);
                    if (extraMinutes < 0 || extraMinutes > 59) return null;
                }
            }
            if (text.substring(relativeHours.end()).trim().matches("^y\\s+(?:(?:medio|media|cuarto|pico|tres\\s+cuartos)\\b|(?:\\d{1,3}|[a-z]+)\\s+segundos?\\b).*")) {
                return null;
            }
            return relativeTarget(hours * 60 + extraMinutes, reminderText);
        }

        // No adivinar la hora si la expresión todavía es imprecisa.
        if (Pattern.compile("\\ba\\s+las?\\s+\\d{1,2}\\s+menos\\b")
                .matcher(text).find()) return null;

        // "A las ocho y pico" no expresa una hora concreta.
        if (Pattern.compile("\\ba\\s+las?\\s+\\d{1,2}\\s+y\\s+pico\\b")
                .matcher(text).find()) return null;

        int hour = -1;
        int minute = 0;
        String meridiem = "";

        Matcher half = Pattern.compile(
                "a\\s+las?\\s+(\\d{1,2})\\s+y\\s+media\\s*(am|pm)?"
        ).matcher(text);

        if (half.find()) {
            hour = Integer.parseInt(half.group(1));
            minute = 30;
            meridiem = safeGroup(half, 2);
        } else {
            Matcher hourAndMinutes = Pattern.compile(
                    "a\\s+las?\\s+(\\d{1,2})\\s+y\\s+(\\d{1,2})\\s*(am|pm)?"
            ).matcher(text);

            if (hourAndMinutes.find()) {
                hour = Integer.parseInt(hourAndMinutes.group(1));
                minute = Integer.parseInt(hourAndMinutes.group(2));
                meridiem = safeGroup(hourAndMinutes, 3);
            } else {
                Matcher normal = Pattern.compile(
                        "a\\s+las?\\s+(\\d{1,2})"
                        + "(?:(?::|,|\\.)\\s*(\\d{1,2}))?"
                        + "\\s*(am|pm)?"
                ).matcher(text);

                if (!normal.find()) return null;

                hour = Integer.parseInt(normal.group(1));

                if (normal.group(2) != null) {
                    minute = Integer.parseInt(normal.group(2));
                }

                meridiem = safeGroup(normal, 3);
            }
        }

        hour = applyMeridiem(hour, meridiem);
        if (meridiem.isEmpty() && hour >= 0 && hour <= 12) {
            if (text.contains("de la tarde") || text.contains("por la tarde")
                    || text.contains("de la noche") || text.contains("por la noche")) {
                hour = applyMeridiem(hour, "pm");
            } else if (text.contains("de la manana") || text.contains("por la manana")
                    || text.contains("de la madrugada") || text.contains("por la madrugada")) {
                hour = applyMeridiem(hour, "am");
            }
        }

        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null;
        }

        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();

        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        // "a las 8 de la mañana" NO significa necesariamente mañana.
        String withoutDayParts = text.replaceAll(
                "\\b(?:de|por)\\s+la\\s+(?:manana|tarde|noche|madrugada)\\b", "");
        if (withoutDayParts.contains("pasado manana")) {
            target.add(Calendar.DAY_OF_YEAR, 2);
        } else if (Pattern.compile("\\bmanana\\b").matcher(withoutDayParts).find()) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        } else if (target.getTimeInMillis() <= now.getTimeInMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }

        return new Parsed(
                hour,
                minute,
                target.getTimeInMillis(),
                reminderText
        );
    }

    private static Parsed relativeTarget(
            int minutes,
            String reminderText
    ) {
        Calendar target = Calendar.getInstance();
        target.add(Calendar.MINUTE, minutes);

        return new Parsed(
                target.get(Calendar.HOUR_OF_DAY),
                target.get(Calendar.MINUTE),
                target.getTimeInMillis(),
                reminderText
        );
    }

    // Del 1 al 99: "dos", "veinticinco", "treinta y cinco", etc.
    private static final String NUMBER_WORD =
            "(?:un|una|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|"
            + "once|doce|trece|catorce|quince|dieciseis|diecisiete|dieciocho|"
            + "diecinueve|veinte|veintiuno|veintiuna|veintidos|veintitres|"
            + "veinticuatro|veinticinco|veintiseis|veintisiete|veintiocho|"
            + "veintinueve|treinta|cuarenta|cincuenta|sesenta|setenta|ochenta|"
            + "noventa|y)";

    private static final Pattern SPOKEN_DURATION = Pattern.compile(
            "\\b(" + NUMBER_WORD + "(?:\\s+" + NUMBER_WORD + "){0,2})"
            + "\\s+(minutos?|horas?)\\b");
    private static final Pattern SPOKEN_CLOCK_HOUR = Pattern.compile(
            "\\ba\\s+las?\\s+(" + NUMBER_WORD + ")\\b");
    private static final Pattern SPOKEN_CLOCK_MINUTE = Pattern.compile(
            "(\\ba\\s+las?\\s+\\d{1,2}\\s+y\\s+)"
            + "(" + NUMBER_WORD + "(?:\\s+" + NUMBER_WORD + "){0,2})\\b");

    private static String normalizeSpokenDurations(String text) {
        Matcher matcher = SPOKEN_DURATION.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String spoken = matcher.group(1);
            int count = parseCount(spoken);
            boolean conjunction = false;
            if (count < 0 && spoken.startsWith("y ")) {
                count = parseCount(spoken.substring(2));
                conjunction = count > 0;
            }
            String replacement = count > 0
                    ? (conjunction ? "y " : "") + count + " " + matcher.group(2)
                    : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String normalizeSpokenClock(String text) {
        Matcher hour = SPOKEN_CLOCK_HOUR.matcher(text);
        StringBuffer out = new StringBuffer();
        while (hour.find()) {
            int number = parseCount(hour.group(1));
            String replacement = number >= 1 && number <= 23
                    ? "a las " + number
                    : hour.group();
            hour.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        hour.appendTail(out);
        text = out.toString();

        // "a las ocho y cuarto" / "a las ocho menos cuarto".
        text = text.replaceAll("(\\ba\\s+las?\\s+\\d{1,2}\\s+y\\s+)cuarto\\b", "$1" + "15");
        Matcher lessQuarter = Pattern.compile(
                "\\ba\\s+las?\\s+(\\d{1,2})\\s+menos\\s+cuarto\\b")
                .matcher(text);
        out = new StringBuffer();
        while (lessQuarter.find()) {
            int h = Integer.parseInt(lessQuarter.group(1));
            String replacement = h >= 1 && h <= 12
                    ? "a las " + (h == 1 ? 12 : h - 1) + ":45"
                    : lessQuarter.group();
            lessQuarter.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        lessQuarter.appendTail(out);
        text = out.toString();

        Matcher minute = SPOKEN_CLOCK_MINUTE.matcher(text);
        out = new StringBuffer();
        while (minute.find()) {
            int number = parseCount(minute.group(2));
            String replacement = number >= 0 && number <= 59
                    ? minute.group(1) + number
                    : minute.group();
            minute.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        minute.appendTail(out);
        return out.toString();
    }

    private static int parseCount(String value) {
        if (value == null) return -1;
        String v = value.trim().toLowerCase(Locale.ROOT);
        try { return Integer.parseInt(v); }
        catch (NumberFormatException ignored) { }

        String[] units = { "un", "una", "uno", "dos", "tres", "cuatro",
                "cinco", "seis", "siete", "ocho", "nueve" };
        for (int i = 0; i < units.length; i++) {
            if (v.equals(units[i])) return i <= 2 ? 1 : i - 1;
        }
        // Tres variantes de uno; los demás valores continúan normalmente.
        String[] teens = { "diez", "once", "doce", "trece", "catorce",
                "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve" };
        for (int i = 0; i < teens.length; i++) if (v.equals(teens[i])) return 10 + i;

        if (v.equals("veinte")) return 20;
        if (v.equals("veintiuna")) return 21;
        String[] twenties = { "veintiuno", "veintidos", "veintitres",
                "veinticuatro", "veinticinco", "veintiseis", "veintisiete",
                "veintiocho", "veintinueve" };
        for (int i = 0; i < twenties.length; i++) {
            if (v.equals(twenties[i])) return 21 + i;
        }
        String[] tens = { "treinta", "cuarenta", "cincuenta", "sesenta",
                "setenta", "ochenta", "noventa" };
        for (int i = 0; i < tens.length; i++) {
            int base = 30 + i * 10;
            if (v.equals(tens[i])) return base;
            String prefix = tens[i] + " y ";
            if (v.startsWith(prefix)) {
                int n = parseCount(v.substring(prefix.length()));
                if (n >= 1 && n <= 9) return base + n;
            }
        }
        return -1;
    }

    private static int applyMeridiem(
            int hour,
            String meridiem
    ) {
        if (meridiem == null || meridiem.isEmpty()) {
            return hour;
        }

        if (hour < 1 || hour > 12) {
            return -1;
        }

        if ("am".equals(meridiem)) {
            return hour == 12 ? 0 : hour;
        }

        if ("pm".equals(meridiem)) {
            return hour == 12 ? 12 : hour + 12;
        }

        return hour;
    }

    private static String safeGroup(
            Matcher matcher,
            int group
    ) {
        String value = matcher.group(group);
        return value == null ? "" : value.trim();
    }

    private static String extractReminderText(String original) {
        Matcher matcher = Pattern.compile(
                "(?iu)(?:\\bpara\\s+|\\by\\s+)?"
                + "(?:recordame|recordáme|recordarme|recuerdame|recuérdame)"
                + "\\s+(?:de\\s+)?(.+)$"
        ).matcher(original);

        if (!matcher.find()) {
            return "";
        }

        String reminder = matcher.group(1).trim();
        // La radio y el pronóstico son acciones independientes, no parte del texto.
        // Ej.: "recordame oler las tiras y poné Radio 10 y decime el clima".
        reminder = reminder.replaceAll(
                "(?iu)\\s+y\\s+(?:pon[eé]|prend[eé]|encend[eé]|poneme|prendeme)\\s+(?:la\\s+)?radio(?:\\s*10)?\\b.*$", "");
        reminder = reminder.replaceAll(
                "(?iu)\\s+y\\s+(?:decime|contame|informame|dame)\\s+(?:c[oó]mo\\s+(?:est[aá]|va\\s+a\\s+estar)\\s+)?(?:el\\s+)?(?:clima|pron[oó]stico|tiempo)\\b.*$", "");
        return reminder.replaceAll("[.!]+$", "").trim();
    }
}
