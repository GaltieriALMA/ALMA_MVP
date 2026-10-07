package com.alma.mvp.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class AlmaAlarmScheduler {

    public static final String EXTRA_ALARM_ID = "alma_alarm_id";
    public static final String EXTRA_REMINDER_TEXT = "alma_reminder_text";

    private static final String PREFS = "alma_alarm_scheduler";
    private static final String NEXT_ID = "next_alarm_id";

    private AlmaAlarmScheduler() {}

    public static boolean schedule(
            Context context,
            long triggerAtMillis
    ) {
        return schedule(context, triggerAtMillis, "");
    }

    public static boolean schedule(
            Context context,
            long triggerAtMillis,
            String reminderText
    ) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        int alarmId = nextAlarmId(context);

        Intent intent = new Intent(context, AlmaAlarmReceiver.class);
        intent.putExtra(EXTRA_ALARM_ID, alarmId);
        intent.putExtra(
                EXTRA_REMINDER_TEXT,
                reminderText == null ? "" : reminderText.trim()
        );

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !alarmManager.canScheduleExactAlarms()) {

            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
            );

            return false;
        }

        alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
        );

        return true;
    }

    private static synchronized int nextAlarmId(Context context) {
        int current = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(NEXT_ID, 7000);

        int next = current >= 2_000_000_000
                ? 7000
                : current + 1;

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(NEXT_ID, next)
                .apply();

        return next;
    }
}
