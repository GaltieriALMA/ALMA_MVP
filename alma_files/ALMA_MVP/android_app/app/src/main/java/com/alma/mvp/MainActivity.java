package com.alma.mvp;

import android.Manifest;
import android.content.Intent;
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
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private final AlmaApiClient api = new AlmaApiClient();
    private final String userId = "android_local_user";
    private final String sessionId = UUID.randomUUID().toString();

    private TextView chatText;
    private EditText messageInput;
    private Button sendButton;
    private Button voiceButton;
    private TextToSpeech tts;
    private SecureTokenStore tokenStore;

    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private MediaPlayer currentPlayer;

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

        chatText = findViewById(R.id.chatText);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        voiceButton = findViewById(R.id.voiceButton);

        voiceButton.setOnClickListener(v -> startVoiceRecognition());

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
if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
    startHandsFreeService();
} else {
    requestPermissions(
            new String[]{Manifest.permission.RECORD_AUDIO},
            AUDIO_PERMISSION_REQUEST_CODE
    );
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
            if (!normalized.matches(".*\\balma\\b.*")) {
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

        new Thread(() -> {
            try {
                String reply = api.chat(userId, sessionId, message, token);
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
