package com.alma.mvp;

import android.Manifest;
import android.content.Intent;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.graphics.BitmapFactory;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.Base64;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.alma.mvp.tv.TvDirectBridge;
import com.alma.mvp.alarm.AlmaAlarmCommand;
import com.alma.mvp.alarm.AlmaAlarmScheduler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private final AlmaApiClient api = new AlmaApiClient();
    private final String userId = "android_local_user";
    private String sessionId;

    private TextView chatText;
    private EditText messageInput;
    private Button sendButton;
    private Button voiceButton;
    private Button cameraButton;
    private TextToSpeech tts;
    private SecureTokenStore tokenStore;

    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private MediaPlayer currentPlayer;
    private Uri cameraImageUri;

    private boolean handsFreeMode = false;
    private boolean conversationActive = false;
    private boolean audioPlaying = false;
    private boolean waitingForResponse = false;
    private boolean manualVoiceActive = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private static final int VOICE_REQUEST_CODE = 1001;
    private static final int AUDIO_PERMISSION_REQUEST_CODE = 2001;
    private static final long CONVERSATION_SILENCE_MS = 15000L;

    private final Runnable conversationTimeoutRunnable = () -> {
        conversationActive = false;
        startWakeWordListening();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tokenStore = new SecureTokenStore(this);

        sessionId = getSharedPreferences("alma_chat", MODE_PRIVATE)
                .getString("session_id", "");

        if (sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            getSharedPreferences("alma_chat", MODE_PRIVATE)
                    .edit()
                    .putString("session_id", sessionId)
                    .apply();
        }

        chatText = findViewById(R.id.chatText);

        String savedChat = getSharedPreferences("alma_chat", MODE_PRIVATE)
                .getString("history", "");

        if (!savedChat.isEmpty()) {
            chatText.setText(savedChat);
        }

        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        voiceButton = findViewById(R.id.voiceButton);
cameraButton = findViewById(R.id.cameraButton);
        voiceButton.setOnClickListener(v -> startVoiceRecognition());
cameraButton.setOnClickListener(v -> openCamera());
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "AR"));
                tts.setSpeechRate(0.88f);
                tts.setPitch(1.00f);
            }
        });

        sendButton.setOnClickListener(v -> {
            if (tokenStore.load() == null) {
                requestAccessKey();
            } else {
                sendMessage();
            }
        });
    }
 private void openCamera() {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, 2002);
        return;
    }

    Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
    ContentValues values = new ContentValues();
    values.put(MediaStore.Images.Media.DISPLAY_NAME, "ALMA_" + System.currentTimeMillis() + ".jpg");
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    cameraImageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    if (cameraImageUri != null) {
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
        cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    if (cameraIntent.resolveActivity(getPackageManager()) != null) {
        startActivityForResult(cameraIntent, 3001);
    } else {
        append("ALMA: No encontré una aplicación de cámara disponible.");
    }
 }
    @Override
protected void onResume() {
    super.onResume();
    android.content.SharedPreferences p=getSharedPreferences("alma_alarm_log",MODE_PRIVATE); if(p.getBoolean("spoken_history_unread",false)){String s=p.getString("spoken_history",""); if(s!=null&&!s.trim().isEmpty()) append("ALMA [alarmas]: "+s); p.edit().putString("spoken_history","").putBoolean("spoken_history_unread",false).apply();}

    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        startHandsFreeService();
    }
}
    private void startHandsFreeService() {
    Intent serviceIntent = new Intent(this, WakeWordService.class);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(serviceIntent);
    } else {
        startService(serviceIntent);
    }
 
 }   private void setupHandsFreeRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            append("ALMA: El reconocimiento de voz no está disponible.");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR");
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        configureHandsFreeListener();

        handsFreeMode = true;
        startWakeWordListening();
    }

    private void configureHandsFreeListener() {
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
                handler.removeCallbacks(conversationTimeoutRunnable);
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                if (!handsFreeMode || audioPlaying || waitingForResponse || manualVoiceActive) {
                    return;
                }

                handler.postDelayed(
                        () -> startWakeWordListening(),
                        700
                );
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (matches != null && !matches.isEmpty()) {
                    handleHandsFreeText(matches.get(0));
                } else {
                    startWakeWordListening();
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void startWakeWordListening() {
        if (!handsFreeMode
                || audioPlaying
                || waitingForResponse
                || manualVoiceActive
                || speechRecognizer == null
                || speechRecognizerIntent == null) {
            return;
        }

        try {
            speechRecognizer.startListening(speechRecognizerIntent);
        } catch (Exception e) {
            handler.postDelayed(
                    () -> startWakeWordListening(),
                    1000
            );
        }
    }

    private void handleHandsFreeText(String text) {
        if (text == null) {
            startWakeWordListening();
            return;
        }

        String heard = text.trim();
        String normalized = heard.toLowerCase(Locale.ROOT);

        if (!conversationActive) {
            if (!normalized.matches("^alma\\b.*")) {
                startWakeWordListening();
                return;
            }

            conversationActive = true;
            handler.removeCallbacks(conversationTimeoutRunnable);

            heard = heard.replaceFirst("(?i)\\balma\\b", "").trim();

            if (heard.isEmpty()) {
                acknowledgeWakeWord();
                return;
            }
        } else {
            handler.removeCallbacks(conversationTimeoutRunnable);
        }

        messageInput.setText(heard);
        sendMessage();
    }

    private void acknowledgeWakeWord() {
        append("ALMA: Te escucho.");

        String token = tokenStore.load();
        if (token == null) {
            handler.postDelayed(
                    conversationTimeoutRunnable,
                    CONVERSATION_SILENCE_MS
            );
            startWakeWordListening();
            return;
        }

        waitingForResponse = true;

        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
            }
        }

        new Thread(() -> {
            try {
                byte[] audio = api.tts("Te escucho.", token);

                runOnUiThread(() -> {
                    waitingForResponse = false;
                    playAudio(audio);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    waitingForResponse = false;
                    handler.removeCallbacks(conversationTimeoutRunnable);
                    handler.postDelayed(
                            conversationTimeoutRunnable,
                            CONVERSATION_SILENCE_MS
                    );
                    startWakeWordListening();
                });
            }
        }).start();
    }

    private void startVoiceRecognition() {
        manualVoiceActive = true;

        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
            }
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                new Locale("es", "AR").toLanguageTag()
        );
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Hablale a ALMA");
        startActivityForResult(intent, VOICE_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == VOICE_REQUEST_CODE) {
            manualVoiceActive = false;

            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results =
                        data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);

                if (results != null && !results.isEmpty()) {
                    messageInput.setText(results.get(0));
                    sendMessage();
                    return;
                }
            }

            startWakeWordListening();
        }
    if (requestCode == 3001 && resultCode == RESULT_OK) {
            try (InputStream in = getContentResolver().openInputStream(cameraImageUri)) {

                if (in != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 4; android.graphics.Bitmap photo = BitmapFactory.decodeStream(in, null, options);

                                                ByteArrayOutputStream out = new ByteArrayOutputStream(); photo.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out); messageInput.setText("__IMAGE__:" + Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)); sendMessage();
                                                    }
                                                    }
            catch (Exception e) { append("ALMA: No pude procesar la foto."); }
    }
    

    }
    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startHandsFreeService();
            } else {
                append("ALMA: Necesito permiso de micrófono para el modo manos libres.");
            }
        }
    }

    private void requestAccessKey() {
        final EditText input = new EditText(this);
        input.setHint("Clave de acceso ALMA");
        input.setInputType(
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        new AlertDialog.Builder(this)
                .setTitle("Activar ALMA")
                .setMessage("Ingresá la clave privada de acceso. Se guardará cifrada en este dispositivo.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String token = input.getText().toString().trim();

                    if (token.isEmpty()) {
                        append("ALMA: La clave no puede estar vacía.");
                        return;
                    }

                    try {
                        tokenStore.save(token);
                        append("ALMA: Acceso configurado.");
                    } catch (Exception e) {
                        append("ALMA: No pude guardar la clave de forma segura.");
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private String localTvAction(String message) {
        String n = java.text.Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "");

        if (!(n.contains("televisor") || n.contains("tele") || n.contains("tv"))) {
            return null;
        }

        if (n.contains("vincula") || n.contains("empareja") ||
                n.contains("conecta")) {
            return "pair";
        }

        if (n.contains("apaga") || n.contains("apagar")) {
            return "power_off";
        }

        if (n.contains("enciende") || n.contains("encende") ||
                n.contains("encender") || n.contains("prende") ||
                n.contains("prender")) {
            return "power_on";
        }

        if (n.contains("volumen") &&
                (n.contains("subi") || n.contains("sube") || n.contains("aumenta"))) {
            return "volume_up";
        }

        if (n.contains("volumen") &&
                (n.contains("baja") || n.contains("baje") || n.contains("disminui"))) {
            return "volume_down";
        }

        if (n.contains("silencio") || n.contains("silencia") || n.contains("mute")) {
            return "mute";
        }

        if (n.contains("inicio") || n.contains("home") || n.contains("pantalla principal")) {
            return "home";
        }

        if (n.contains("atras") || n.contains("volver") || n.contains("volve")) {
            return "back";
        }

        return null;
    }

    private boolean handleLocalTvCommand(String message, String token) {
        String action = localTvAction(message);
        if (action == null) {
            return false;
        }

        handler.removeCallbacks(conversationTimeoutRunnable);
        waitingForResponse = true;

        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
            }
        }

        append("Vos: " + message);
        messageInput.setText("");
        sendButton.setEnabled(false);

        TvDirectBridge.Callback callback = (ok, reply) ->
                runOnUiThread(() -> finishTvCommand(reply, token));

        if ("pair".equals(action)) {
            TvDirectBridge.pair(this, callback);
        } else {
            TvDirectBridge.send(this, action, callback);
        }

        return true;
    }

    private void finishTvCommand(String reply, String token) {
        new Thread(() -> {
            try {
                byte[] audio = api.tts(reply, token);

                runOnUiThread(() -> {
                    waitingForResponse = false;
                    sendButton.setEnabled(true);
                    append("ALMA: " + reply);
                    playAudio(audio);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    waitingForResponse = false;
                    sendButton.setEnabled(true);
                    append("ALMA: " + reply);
                    resumeHandsFreeAfterResponse();
                });
            }
        }).start();
    }


    private boolean handleLocalAlarmCommand(String message, String token) {
        AlmaAlarmCommand.Parsed parsed = AlmaAlarmCommand.parse(message);

        if (parsed == null) {
            return false;
        }

        boolean exact = AlmaAlarmScheduler.schedule(
                this,
                parsed.triggerAtMillis,
                parsed.reminderText
        );

        String reply = String.format(
                Locale.ROOT,
                "Listo. Te despierto a las %02d:%02d.",
                parsed.hour,
                parsed.minute
        );

        if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            reply += " Android todavía no me dio permiso para alarmas exactas.";
        }

        append("Vos: " + message);
        messageInput.setText("");
        sendButton.setEnabled(false);
        waitingForResponse = true;

        String finalReply = reply;

        new Thread(() -> {
            try {
                byte[] audio = api.tts(finalReply, token);

                runOnUiThread(() -> {
                    waitingForResponse = false;
                    sendButton.setEnabled(true);
                    append("ALMA: " + finalReply);
                    playAudio(audio);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    waitingForResponse = false;
                    sendButton.setEnabled(true);
                    append("ALMA: " + finalReply);
                    resumeHandsFreeAfterResponse();
                });
            }
        }).start();

        return true;
    }

    private boolean handleLocalYoutubeCommand(String message, String token) {
        String n = java.text.Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "");

        boolean wantsYoutube =
                n.contains("youtube")
                || n.contains("video")
                || ((n.startsWith("pone ")
                        || n.startsWith("poneme ")
                        || n.startsWith("reproduci ")
                        || n.startsWith("reproduce "))
                    && n.contains("altavoz"));

        if (!wantsYoutube) return false;

        String query = n
                .replaceFirst("^(busca|buscame|mostra|mostrame|pone|poneme|reproduci|reproduce)\\s+", "")
                .replaceFirst("^un\\s+", "")
                .replaceFirst("^video\\s+(de|del)?\\s*", "")
                .replaceAll("\\b(en\\s+)?youtube\\b", "")
                .replaceAll("\\b(en\\s+el\\s+|por\\s+el\\s+)?altavoz\\b", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (query.isEmpty()) return false;

        waitingForResponse = true;
        append("Vos: " + message);
        messageInput.setText("");
        sendButton.setEnabled(false);

        new Thread(() -> {
            try {
                org.json.JSONObject result = api.searchYouTube(query, token);
                String videoId = result.getString("video_id");
                String title = result.optString("title", query);

                runOnUiThread(() -> {
                    waitingForResponse = false;
                    sendButton.setEnabled(true);
                    append("ALMA: Reproduciendo en YouTube: " + title);

                    stopService(new Intent(MainActivity.this, WakeWordService.class));

                    Intent i = new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/watch?v=" + videoId)
                    );
                    i.setPackage("com.google.android.youtube");

                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        i.setPackage(null);
                        startActivity(i);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    waitingForResponse = false;
                    sendButton.setEnabled(true);
                    append("ALMA: No pude encontrar ese video en YouTube.");
                    resumeHandsFreeAfterResponse();
                });
            }
        }, "ALMA-YouTube").start();

        return true;
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) {
            startWakeWordListening();
            return;
        }

        String token = tokenStore.load();
        if (token == null) {
            requestAccessKey();
            startWakeWordListening();
            return;
        }

        if (handleLocalYoutubeCommand(message, token)) {
            return;
        }

        if (handleLocalTvCommand(message, token)) {
            return;
        }

        if (handleLocalAlarmCommand(message, token)) {
            return;
        }

        handler.removeCallbacks(conversationTimeoutRunnable);
        waitingForResponse = true;

        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
            }
        }

        append(message.startsWith("__IMAGE__:") ? "Vos: [Foto]" : "Vos: " + message);
        messageInput.setText("");
        sendButton.setEnabled(false);

        new Thread(() -> {
            try {
                String reply = message.startsWith("__IMAGE__:") ? api.chatWithImage(userId, sessionId, "Mirá esta imagen y contame qué ves", message.substring(10), "image/jpeg", token) : api.chat(userId, sessionId, message, token);
                if (openYoutubeReply(reply)) { runOnUiThread(() -> sendButton.setEnabled(true)); return; }
                byte[] audio = api.tts(reply, token);

                runOnUiThread(() -> {
                    waitingForResponse = false;
                    append("ALMA: " + reply);
                    playAudio(audio);
                });
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("401")) {
                    tokenStore.clear();

                    runOnUiThread(() -> {
                        waitingForResponse = false;
                        append("ALMA: La clave de acceso no es válida. Volvé a ingresarla.");
                        requestAccessKey();
                        resumeHandsFreeAfterResponse();
                    });
                } else {
                    runOnUiThread(() -> {
                        waitingForResponse = false;
                        append(
                                "ALMA ERROR: "
                                        + e.getClass().getSimpleName()
                                        + ": "
                                        + String.valueOf(e.getMessage())
                        );
                        resumeHandsFreeAfterResponse();
                    });
                }
            } finally {
                runOnUiThread(() -> sendButton.setEnabled(true));
            }
        }).start();
    }

    private boolean openYoutubeReply(String r){int p=r.indexOf("youtube.com/watch?v=");if(p<0)return false;int s=p+20,e=s;String ok="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";while(e<r.length()&&ok.indexOf(r.charAt(e))>=0)e++;String id=r.substring(s,e);if(id.isEmpty())return false;runOnUiThread(()->{waitingForResponse=false;append("ALMA: Abriendo video en YouTube");Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/watch?v="+id));i.setPackage("com.google.android.youtube");try{startActivity(i);}catch(Exception ex){i.setPackage(null);startActivity(i);}});return true;}

    private void playAudio(byte[] audio) {
        try {
            audioPlaying = true;

            if (speechRecognizer != null) {
                try {
                    speechRecognizer.cancel();
                } catch (Exception ignored) {
                }
            }

            File file = File.createTempFile(
                    "alma_voice_",
                    ".mp3",
                    getCacheDir()
            );

            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(audio);
            }

            currentPlayer = new MediaPlayer();
            currentPlayer.setDataSource(file.getAbsolutePath());

            currentPlayer.setOnCompletionListener(mp -> {
                mp.release();
                currentPlayer = null;
                file.delete();

                audioPlaying = false;
                resumeHandsFreeAfterResponse();
            });

            currentPlayer.setOnErrorListener((mp, what, extra) -> {
                mp.release();
                currentPlayer = null;
                file.delete();

                audioPlaying = false;
                resumeHandsFreeAfterResponse();
                return true;
            });

            currentPlayer.prepare();
            currentPlayer.setPlaybackParams(
                    currentPlayer
                            .getPlaybackParams()
                            .setSpeed(1.0f)
                            .setPitch(1.0f)
            );
            currentPlayer.start();
        } catch (Exception e) {
            audioPlaying = false;
            append("ALMA: No pude reproducir la voz.");
            resumeHandsFreeAfterResponse();
        }
    }

    private void resumeHandsFreeAfterResponse() {
        if (!handsFreeMode) {
            return;
        }

        startWakeWordListening();

        if (conversationActive) {
            handler.removeCallbacks(conversationTimeoutRunnable);
            handler.postDelayed(
                    conversationTimeoutRunnable,
                    CONVERSATION_SILENCE_MS
            );
        }
    }

    private void append(String line) {
        chatText.append("\n\n" + line);

        String history = chatText.getText().toString();

        if (history.length() > 50000) {
            history = history.substring(history.length() - 50000);
        }

        getSharedPreferences("alma_chat", MODE_PRIVATE)
                .edit()
                .putString("history", history)
                .apply();
    }

    @Override
    protected void onDestroy() {
        handsFreeMode = false;
        handler.removeCallbacksAndMessages(null);

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }

        if (currentPlayer != null) {
            try {
                currentPlayer.release();
            } catch (Exception ignored) {
            }
            currentPlayer = null;
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        super.onDestroy();
    }
}
