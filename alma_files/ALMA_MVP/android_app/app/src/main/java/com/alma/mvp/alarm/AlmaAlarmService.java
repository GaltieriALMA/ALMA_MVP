package com.alma.mvp.alarm;

import android.app.PendingIntent;
import android.media.AudioAttributes;
import android.net.Uri;
import java.util.Calendar;
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
    private static final String ACTION_STOP_RADIO = "com.alma.mvp.alarm.STOP_RADIO";
    // Transmisión real de Radio 10, no voz sintetizada ni reproducción de un video.
    // Fuente publicada en directorios de emisoras: requiere prueba en A72.
    private static final String RADIO_10_STREAM =
            "https://radio10.stweb.tv/radio10/live/playlist.m3u8";
    private MediaPlayer radioPlayer;

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
                        .setContentText("Alarma o recordatorio activo")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setOngoing(true)
                        .addAction(android.R.drawable.ic_media_pause,
                                "Detener radio",
                                PendingIntent.getService(this, 991,
                                        new Intent(this, AlmaAlarmService.class)
                                                .setAction(ACTION_STOP_RADIO),
                                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

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
        if (intent != null && ACTION_STOP_RADIO.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String reminderText = intent == null ? "" :
                (intent.getStringExtra(AlmaAlarmScheduler.EXTRA_REMINDER_TEXT) == null
                ? "" : intent.getStringExtra(AlmaAlarmScheduler.EXTRA_REMINDER_TEXT).trim());
        final boolean weather = reminderText.isEmpty() && intent != null
                && intent.getBooleanExtra("alma_wake_weather", false);
        final boolean radio = reminderText.isEmpty() && intent != null
                && intent.getBooleanExtra("alma_wake_radio", false);
        getSharedPreferences("alma_alarm_voice", MODE_PRIVATE).edit()
                .putLong("active_until", System.currentTimeMillis() + 120000L).apply();

        // Siempre despierta PRIMERO. La red del pronóstico no puede retrasar el saludo.
        Runnable afterAll = () -> {
            if (radio) startRadio10(startId);
            else stopSelf(startId);
        };
        String greeting;
        if (!reminderText.isEmpty()) {
            greeting = "Alejandro, te recuerdo: " + reminderText + ".";
        } else {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            String hello = hour >= 4 && hour < 12 ? "Buen día" :
                    (hour < 20 && hour >= 12 ? "Buenas tardes" : "Buenas noches");
            greeting = hello + ", Alejandro. Es hora de despertarse.";
        }
        saveSpokenHistory(greeting);
        if (weather) {
            speakWithAlmaVoice(greeting, () -> new Thread(() -> {
                String report = AlmaWakeWeather.todaySummary();
                saveSpokenHistory(report);
                speakWithAlmaVoice(report, afterAll);
            }, "ALMA-Weather").start());
        } else {
            speakWithAlmaVoice(greeting, afterAll);
        }
        return START_NOT_STICKY;
    }

    private void saveSpokenHistory(String text) {
        android.content.SharedPreferences prefs =
                getSharedPreferences("alma_alarm_log", MODE_PRIVATE);
        String history = prefs.getString("spoken_history", "");
        prefs.edit().putString("spoken_history",
                history.isEmpty() ? text : history + "\n" + text)
                .putBoolean("spoken_history_unread", true).commit();
    }

    private void speakWithAlmaVoice(String text, Runnable afterVoice) {
        new Thread(() -> {
            try {
                String token = new SecureTokenStore(this).load();

                if (token == null || token.trim().isEmpty()) {
                    fallbackAndroidVoice(text, afterVoice);
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
                    afterVoice.run();
                });

                player.setOnErrorListener((mp, what, extra) -> {
                    mp.release();
                    file.delete();
                    fallbackAndroidVoice(text, afterVoice);
                    return true;
                });

                player.prepare();
                player.start();

            } catch (Exception e) {
                fallbackAndroidVoice(text, afterVoice);
            }
        }, "ALMA-Alarm-Voice").start();
    }



    private void fallbackAndroidVoice(String text, Runnable afterVoice) {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                afterVoice.run();
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
                            afterVoice.run();
                        }

                        @Override
                        public void onError(String utteranceId) {
                            afterVoice.run();
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


    /** El MediaPlayer queda dentro del servicio foreground durante la radio. */
    private void startRadio10(int startId) {
        try {
            if (radioPlayer != null) {
                radioPlayer.release();
                radioPlayer = null;
            }
            MediaPlayer player = new MediaPlayer();
            radioPlayer = player;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(this, Uri.parse(RADIO_10_STREAM));
            player.setOnPreparedListener(mp -> {
                mp.start();
                NotificationManager notifications =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationCompat.Builder notification =
                        new NotificationCompat.Builder(this, CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.ic_media_play)
                                .setContentTitle("ALMA — Radio 10 AM 710")
                                .setContentText("Radio en vivo. Tocá Detener radio para apagarla.")
                                .setOngoing(true)
                                .addAction(android.R.drawable.ic_media_pause,
                                        "Detener radio",
                                        PendingIntent.getService(this, 991,
                                                new Intent(this, AlmaAlarmService.class)
                                                        .setAction(ACTION_STOP_RADIO),
                                                PendingIntent.FLAG_UPDATE_CURRENT
                                                        | PendingIntent.FLAG_IMMUTABLE));
                notifications.notify(NOTIFICATION_ID, notification.build());
            });
            player.setOnErrorListener((mp, what, extra) -> {
                stopSelf();
                return true;
            });
            player.setOnCompletionListener(mp -> stopSelf());
            player.prepareAsync();
        } catch (Exception ignored) {
            stopSelf(startId);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Alarmas y recordatorios ALMA",
                            NotificationManager.IMPORTANCE_HIGH
                    );

            channel.setDescription(
                    "Avisos de alarmas y recordatorios de ALMA"
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (radioPlayer != null) {
            radioPlayer.release();
            radioPlayer = null;
        }
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
