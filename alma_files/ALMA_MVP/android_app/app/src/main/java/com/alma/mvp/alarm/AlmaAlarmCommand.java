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

        text = text.replace("cinco minutos mas", "5 minutos mas");

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
                || text.contains("despertador");

        if (!alarmIntent) return null;

        Matcher halfHour = Pattern.compile(
                "(?:dentro\\s+de|en)\\s+media\\s+hora"
        ).matcher(text);

        if (halfHour.find()) {
            return relativeTarget(30, reminderText);
        }

        Matcher relativeMinutes = Pattern.compile(
                "(?:para\\s+)?(?:dentro\\s+de|en)\\s+"
                + "(\\d{1,3}|un|una|uno)\\s+minutos?"
        ).matcher(text);

        if (relativeMinutes.find()) {
            int minutes = parseCount(relativeMinutes.group(1));

            if (minutes < 1 || minutes > 1440) {
                return null;
            }

            return relativeTarget(minutes, reminderText);
        }

        Matcher relativeHours = Pattern.compile(
                "(?:para\\s+)?(?:dentro\\s+de|en)\\s+"
                + "(\\d{1,2}|un|una|uno)\\s+horas?"
        ).matcher(text);

        if (relativeHours.find()) {
            int hours = parseCount(relativeHours.group(1));

            if (hours < 1 || hours > 48) {
                return null;
            }

            return relativeTarget(hours * 60, reminderText);
        }

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

        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null;
        }

        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();

        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        if (text.contains("manana")) {
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

    private static int parseCount(String value) {
        if (value == null) return -1;

        String v = value.trim().toLowerCase(Locale.ROOT);

        if ("un".equals(v) || "una".equals(v) || "uno".equals(v)) {
            return 1;
        }

        try {
            return Integer.parseInt(v);
        } catch (Exception ignored) {
            return -1;
        }
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

        return matcher.group(1)
                .trim()
                .replaceAll("[.!]+$", "")
                .trim();
    }
}
