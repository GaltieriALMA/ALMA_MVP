package com.alma.mvp;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
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
    private final String sessionId = UUID.randomUUID().toString();

    private TextView chatText;
    private EditText messageInput;
    private Button sendButton;
    private Button voiceButton;
    private Button cameraButton;
    private ImageView almaImage;
    private AnimatorSet avatarSpeakingAnimator;
    private boolean speakingReceiverRegistered = false;

    private static final String ACTION_SPEAKING_STATE =
            "com.alma.mvp.SPEAKING_STATE";

    private final BroadcastReceiver speakingReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getBooleanExtra("speaking", false)) {
                        startAvatarSpeakingAnimation();
                    } else {
                        stopAvatarSpeakingAnimation();
                    }
                }
            };
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

        chatText = findViewById(R.id.chatText);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        voiceButton = findViewById(R.id.voiceButton);
cameraButton = findViewById(R.id.cameraButton);
        almaImage = findViewById(R.id.almaImage);
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

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter =
                new IntentFilter(ACTION_SPEAKING_STATE);

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                        speakingReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                registerReceiver(speakingReceiver, filter);
            }
            speakingReceiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStop() {
        if (speakingReceiverRegistered) {
            try {
                unregisterReceiver(speakingReceiver);
            } catch (Exception ignored) {
            }
            speakingReceiverRegistered = false;
        }

        stopAvatarSpeakingAnimation();
        super.onStop();
    }

    private void startAvatarSpeakingAnimation() {
        if (almaImage == null) return;

        if (avatarSpeakingAnimator != null
                && avatarSpeakingAnimator.isRunning()) {
            return;
        }

        almaImage.post(() -> {
            almaImage.setPivotX(almaImage.getWidth() / 2f);
            almaImage.setPivotY(almaImage.getHeight() * 0.88f);

            PropertyValuesHolder moveX =
                    PropertyValuesHolder.ofFloat(
                            "translationX",
                            0f, 7f, -5f, 6f, -3f, 0f
                    );

            PropertyValuesHolder moveY =
                    PropertyValuesHolder.ofFloat(
                            "translationY",
                            0f, -3f, 0f, -5f, -2f, 0f
                    );

            PropertyValuesHolder rotate =
                    PropertyValuesHolder.ofFloat(
                            "rotation",
                            0f, 1.4f, -1.1f, 0.9f, -0.6f, 0f
                    );

            PropertyValuesHolder scaleX =
                    PropertyValuesHolder.ofFloat(
                            "scaleX",
                            1.0f, 1.012f, 1.0f, 1.016f, 1.006f, 1.0f
                    );

            PropertyValuesHolder scaleY =
                    PropertyValuesHolder.ofFloat(
                            "scaleY",
                            1.0f, 1.018f, 1.004f, 1.014f, 1.006f, 1.0f
                    );

            ObjectAnimator bodyGesture =
                    ObjectAnimator.ofPropertyValuesHolder(
                            almaImage,
                            moveX,
                            moveY,
                            rotate,
                            scaleX,
                            scaleY
                    );

            bodyGesture.setDuration(2600L);
            bodyGesture.setRepeatCount(ValueAnimator.INFINITE);
            bodyGesture.setRepeatMode(ValueAnimator.RESTART);

            avatarSpeakingAnimator = new AnimatorSet();
            avatarSpeakingAnimator.play(bodyGesture);
            avatarSpeakingAnimator.start();
        });
    }

    private void stopAvatarSpeakingAnimation() {
        if (avatarSpeakingAnimator != null) {
            avatarSpeakingAnimator.cancel();
            avatarSpeakingAnimator = null;
        }

        if (almaImage != null) {
            almaImage.setScaleX(1.0f);
            almaImage.setScaleY(1.0f);
            almaImage.setTranslationY(0f);
        }
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

    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
        startHandsFreeService();
    } else {
        requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION_REQUEST_CODE
        );
    }
}
    private void startHandsFreeService() {
        Intent serviceIntent =
                new Intent(this, WakeWordService.class);

        try {
            stopService(serviceIntent);
        } catch (Exception ignored) {
        }

        handler.postDelayed(() -> {
            try {
                if (Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } catch (Exception ignored) {
            }
        }, 500L);
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

    private boolean handleYouTubeCommand(String message) {
        if (message == null) return false;

        String lower = message.toLowerCase(Locale.ROOT);

        if (!lower.contains("youtube")) {
            return false;
        }

        String query = message
                .replaceAll("(?i)\\balma\\b", "")
                .replaceAll("(?i)\\b(abr[ií]|abre|abrir|busc[aá]|busca|buscame|pon[eé]|pone|poneme|reproduc[ií]|reproduce|mostr[aá]|mostra|mostrame)\\b", "")
                .replaceAll("(?i)\\b(en\\s+)?youtube\\b", "")
                .replaceFirst("(?i)^\\s*(un\\s+)?video\\s+(de\\s+|del\\s+)?", "")
                .replaceAll("\\s+", " ")
                .trim();

        try {
            String url = query.isEmpty()
                    ? "https://www.youtube.com/"
                    : "https://www.youtube.com/results?search_query="
                        + Uri.encode(query);

            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
            );
            intent.setPackage("com.google.android.youtube");

            try {
                startActivity(intent);
            } catch (Exception first) {
                intent.setPackage(null);
                startActivity(intent);
            }

            append(
                    query.isEmpty()
                            ? "ALMA: Abriendo YouTube"
                            : "ALMA: Buscando en YouTube: " + query
            );

            return true;

        } catch (Exception e) {
            append("ALMA: No pude abrir YouTube.");
            return true;
        }
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) {
            startWakeWordListening();
            return;
        }

        if (handleYouTubeCommand(message)) {
            append("Vos: " + message);
            messageInput.setText("");
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
            startAvatarSpeakingAnimation();

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
                stopAvatarSpeakingAnimation();
                resumeHandsFreeAfterResponse();
            });

            currentPlayer.setOnErrorListener((mp, what, extra) -> {
                mp.release();
                currentPlayer = null;
                file.delete();

                audioPlaying = false;
                stopAvatarSpeakingAnimation();
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
            stopAvatarSpeakingAnimation();
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
        stopAvatarSpeakingAnimation();
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
