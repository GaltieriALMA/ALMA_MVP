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

        Parsed(int hour, int minute, long triggerAtMillis) {
            this.hour = hour;
            this.minute = minute;
            this.triggerAtMillis = triggerAtMillis;
        }
    }

    public static Parsed parse(String message) {
        if (message == null) return null;

        String text = Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "");

        text = text.replace("cinco minutos mas", "5 minutos mas");

        Matcher snooze = Pattern.compile(
                "(?:otros?\\s+)?(\\d{1,3})\\s+minutos?\\s+mas"
        ).matcher(text);

        if (snooze.find()) {
            int minutes = Integer.parseInt(snooze.group(1));

            if (minutes < 1 || minutes > 180) {
                return null;
            }

            Calendar target = Calendar.getInstance();
            target.add(Calendar.MINUTE, minutes);
            target.set(Calendar.SECOND, 0);
            target.set(Calendar.MILLISECOND, 0);

            return new Parsed(
                    target.get(Calendar.HOUR_OF_DAY),
                    target.get(Calendar.MINUTE),
                    target.getTimeInMillis()
            );
        }

        boolean alarmIntent =
                (text.contains("despertame") || text.contains("despiertame"))
                || text.contains("levantame")
                || text.contains("alarma")
                || text.contains("despertador");

        if (!alarmIntent) return null;

        int hour = -1;
        int minute = 0;

        Matcher half = Pattern.compile(
                "a\\s+las\\s+(\\d{1,2})\\s+y\\s+media"
        ).matcher(text);

        if (half.find()) {
            hour = Integer.parseInt(half.group(1));
            minute = 30;
        } else {
            Matcher normal = Pattern.compile(
                    "a\\s+las\\s+(\\d{1,2})(?::(\\d{2}))?"
            ).matcher(text);

            if (!normal.find()) return null;

            hour = Integer.parseInt(normal.group(1));

            if (normal.group(2) != null) {
                minute = Integer.parseInt(normal.group(2));
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

        if (text.contains("manana")) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        } else if (target.getTimeInMillis() <= now.getTimeInMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }

        return new Parsed(
                hour,
                minute,
                target.getTimeInMillis()
        );
    }
}
