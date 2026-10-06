package com.alma.mvp.alarm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;

import androidx.core.app.NotificationCompat;

import com.alma.mvp.R;

import java.util.Locale;

public class AlmaAlarmService extends Service {

    private static final String CHANNEL_ID = "alma_alarm_channel";
    private static final int NOTIFICATION_ID = 7001;

    private TextToSpeech tts;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentTitle("ALMA")
                        .setContentText("Despertador inteligente activo")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setOngoing(true);

        startForeground(NOTIFICATION_ID, notification.build());

        PowerManager powerManager =
                (PowerManager) getSystemService(POWER_SERVICE);

        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ALMA:AlarmWakeLock"
        );

        wakeLock.acquire(60_000L);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "AR"));
                tts.setSpeechRate(0.88f);
                tts.setPitch(1.00f);

                tts.speak(
                        "Buen día Alejandro. Es hora de levantarse.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "ALMA_ALARM"
                );
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Despertador ALMA",
                            NotificationManager.IMPORTANCE_HIGH
                    );

            channel.setDescription(
                    "Avisos del despertador inteligente de ALMA"
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
