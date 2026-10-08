package com.alma.mvp;

import java.text.Normalizer;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CalendarCommand {

    static final class Draft {
        final String title;
        final long startMillis;
        final long endMillis;

        Draft(String title, long startMillis, long endMillis) {
            this.title = title;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }
    }

    static Draft parse(String message) {
        if (message == null) return null;

        String original = message.trim();

        String n = Normalizer.normalize(
                original.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "")
         .replaceAll("\\s+", " ")
         .trim();

        boolean command =
                n.startsWith("agenda ")
                || n.startsWith("agendame ")
                || n.startsWith("anota ")
                || n.startsWith("anotame ")
                || n.startsWith("programa ")
                || n.startsWith("programame ")
                || n.startsWith("crea evento ")
                || n.startsWith("crea un evento ");

        if (!command) return null;

        int hour = -1;
        int minute = 0;
        String meridiem = "";

        Matcher half = Pattern.compile(
                "a\\s+las?\\s+(\\d{1,2})\\s+y\\s+media\\s*(am|pm)?"
        ).matcher(n);

        if (half.find()) {
            hour = Integer.parseInt(half.group(1));
            minute = 30;
            meridiem = half.group(2) == null ? "" : half.group(2);
        } else {
            Matcher normal = Pattern.compile(
                    "a\\s+las?\\s+(\\d{1,2})"
                    + "(?:(?::|\\.|,)\\s*(\\d{1,2}))?"
                    + "\\s*(am|pm)?"
            ).matcher(n);

            if (!normal.find()) return null;

            hour = Integer.parseInt(normal.group(1));

            if (normal.group(2) != null) {
                minute = Integer.parseInt(normal.group(2));
            }

            meridiem =
                    normal.group(3) == null
                            ? ""
                            : normal.group(3);
        }

        if (!meridiem.isEmpty()) {
            if (hour < 1 || hour > 12) return null;

            if ("am".equals(meridiem)) {
                if (hour == 12) hour = 0;
            } else if ("pm".equals(meridiem)) {
                if (hour != 12) hour += 12;
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

        if (n.contains("pasado manana")) {
            target.add(Calendar.DAY_OF_YEAR, 2);
        } else if (n.contains("manana")) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        } else if (!n.contains("hoy")
                && target.getTimeInMillis() <= now.getTimeInMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }

        String title = original
                .replaceFirst(
                        "(?iu)^\\s*(?:agendá|agenda|agendame|agéndame|anotá|anota|anotame|anótame|programá|programa|programame|programáme|creá un evento|crea un evento|creá evento|crea evento)\\s+",
                        ""
                )
                .replaceAll(
                        "(?iu)\\b(?:pasado\\s+mañana|pasado\\s+manana|mañana|manana|hoy)\\b",
                        ""
                )
                .replaceAll(
                        "(?iu)\\ba\\s+las?\\s+\\d{1,2}(?:\\s+y\\s+media|(?:(?::|\\.|,)\\s*\\d{1,2}))?\\s*(?:am|pm)?",
                        ""
                )
                .replaceAll("\\s+", " ")
                .trim();

        title = title
                .replaceFirst("(?iu)^para\\s+", "")
                .replaceAll("^[,;:\\-]+|[,;:\\-]+$", "")
                .trim();

        if (title.isEmpty()) {
            title = "Evento con ALMA";
        }

        long start = target.getTimeInMillis();
        long end = start + 60L * 60L * 1000L;

        return new Draft(title, start, end);
    }

    private CalendarCommand() {}
}
