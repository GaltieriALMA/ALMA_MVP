package com.alma.mvp.alarm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.core.app.NotificationCompat;

import com.alma.mvp.AlmaApiClient;
import com.alma.mvp.SecureTokenStore;

import com.alma.mvp.R;

import java.io.File;
import java.io.FileOutputStream;
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

    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String reminderText = "";

        if (intent != null) {
            String received = intent.getStringExtra(
                    AlmaAlarmScheduler.EXTRA_REMINDER_TEXT
            );

            if (received != null) {
                reminderText = received.trim();
            }
        }

        String spokenText = reminderText.isEmpty()
                ? "Alejandro, sonó tu alarma."
                : "Alejandro, te recuerdo: " + reminderText + ".";

        speakWithAlmaVoice(spokenText, startId);

        return START_NOT_STICKY;
    }



    private void speakWithAlmaVoice(String text, int startId) {
        new Thread(() -> {
            try {
                String token = new SecureTokenStore(this).load();

                if (token == null || token.trim().isEmpty()) {
                    fallbackAndroidVoice(text, startId);
                    return;
                }

                byte[] audio = new AlmaApiClient().tts(text, token);

                File file = File.createTempFile(
                        "alma_alarm_",
                        ".mp3",
                        getCacheDir()
                );

                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(audio);
                }

                MediaPlayer player = new MediaPlayer();
                player.setDataSource(file.getAbsolutePath());

                player.setOnCompletionListener(mp -> {
                    mp.release();
                    file.delete();
                    stopSelf(startId);
                });

                player.setOnErrorListener((mp, what, extra) -> {
                    mp.release();
                    file.delete();
                    fallbackAndroidVoice(text, startId);
                    return true;
                });

                player.prepare();
                player.start();

            } catch (Exception e) {
                fallbackAndroidVoice(text, startId);
            }
        }, "ALMA-Alarm-Voice").start();
    }



    private void fallbackAndroidVoice(String text, int startId) {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                stopSelf(startId);
                return;
            }

            tts.setLanguage(new Locale("es", "AR"));
            tts.setSpeechRate(0.88f);
            tts.setPitch(1.00f);

            tts.setOnUtteranceProgressListener(
                    new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {}

                        @Override
                        public void onDone(String utteranceId) {
                            stopSelf(startId);
                        }

                        @Override
                        public void onError(String utteranceId) {
                            stopSelf(startId);
                        }
                    }
            );

            tts.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "ALMA_ALARM_FALLBACK"
            );
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
