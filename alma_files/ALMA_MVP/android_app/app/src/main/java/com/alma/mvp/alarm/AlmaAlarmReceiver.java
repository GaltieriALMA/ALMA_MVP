package com.alma.mvp.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AlmaAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent service = new Intent(context, AlmaAlarmService.class);

        service.putExtra(
                AlmaAlarmScheduler.EXTRA_ALARM_ID,
                intent.getIntExtra(
                        AlmaAlarmScheduler.EXTRA_ALARM_ID,
                        -1
                )
        );

        service.putExtra(
                AlmaAlarmScheduler.EXTRA_REMINDER_TEXT,
                intent.getStringExtra(
                        AlmaAlarmScheduler.EXTRA_REMINDER_TEXT
                )
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
