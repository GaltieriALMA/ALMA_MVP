package com.alma.mvp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.provider.MediaStore;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public class WakeWordService extends Service
        implements VoskWakeWord.Listener {

    private static final String CHANNEL_ID =
            "alma_hands_free_silent_v3";
    private static final int NOTIFICATION_ID = 41;
    private static final long CONVERSATION_IDLE_MS = 10000L;

    private static final Pattern END_PHRASE = Pattern.compile(
            "^(chau|chao|adios|listo|gracias|terminamos|"
                    + "eso es todo|nada mas|hasta luego)$"
    );

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final AlmaApiClient api = new AlmaApiClient();
    private final String userId = "android_local_user";
    private final String sessionId = UUID.randomUUID().toString();

    private SecureTokenStore tokenStore;
    private MediaPlayer currentPlayer;
    private File currentAudioFile;
    private VoskWakeWord voskWakeWord;

    private boolean speaking = false;
    private boolean conversationActive = false;
    private boolean destroyed = false;
    private ComposeCommand.Draft pendingComposeDraft = null;
    private CalendarCommand.Draft pendingCalendarDraft = null;
    private ContactCallCommand.Resolution pendingCall = null;
    private String pendingCallMethod = null;

    private volatile byte[] wakeAckAudio;
    private static final long WAKE_SECURITY_COOLDOWN_MS = 4000L;
    private long lastWakeAcceptedAt = 0L;

    @Override
    public void onCreate() {
        super.onCreate();

        tokenStore = new SecureTokenStore(this);
        voskWakeWord = new VoskWakeWord(this, this);

        createNotificationChannel();

        startForeground(
                NOTIFICATION_ID,
                buildNotification("Esperando que digas \"ALMA\"")
        );

        preloadWakeAcknowledgement();
        voskWakeWord.startWake();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        return START_STICKY;
    }

    private String normalize(String text) {
        if (text == null) return "";

        return Normalizer.normalize(
                        text,
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String removeWakeWord(String text) {
        if (text == null) return "";

        return text.replaceFirst(
                "(?i)^\\s*(?:hola\\s+)?alma\\b[\\s,:-]*",
                ""
        ).trim();
    }

    private boolean isEndPhrase(String text) {
        return END_PHRASE.matcher(normalize(text)).matches();
    }

    private void activateConversation(String recognizedText) {
        if (destroyed || conversationActive || speaking) {
            return;
        }

        conversationActive = true;
        updateNotification("ALMA te escuchó");

        String remainder = removeWakeWord(recognizedText);

        if (!remainder.isEmpty()) {
            sendToAlma(remainder);
            return;
        }

        playWakeAcknowledgement();
    }

    private void playWakeAcknowledgement() {
        byte[] cached = wakeAckAudio;

        if (cached != null && cached.length > 0) {
            playAudio(
                    cached,
                    this::resumeConversationListening
            );
            return;
        }

        speakAlmaText(
                "Sí, te escucho.",
                this::resumeConversationListening
        );
    }

    private void preloadWakeAcknowledgement() {
        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.postDelayed(this::preloadWakeAcknowledgement, 1500);
                return;
            }

            try {
                wakeAckAudio =
                        api.tts("Sí, te escucho.", token);
            } catch (Exception ignored) {
            }
        }, "ALMA-Wake-Ack-Preload").start();
    }

    private void resumeConversationListening() {
        if (destroyed || voskWakeWord == null) return;

        speaking = false;
        conversationActive = true;
        updateNotification("Conversación activa");

        voskWakeWord.startConversation(
                CONVERSATION_IDLE_MS
        );
    }

    private void resetToWakeMode() {
        if (destroyed || voskWakeWord == null) return;

        speaking = false;
        conversationActive = false;
        updateNotification("Esperando que digas \"ALMA\"");

        voskWakeWord.startWake();
    }


    private boolean handlePendingComposeConfirmation(String message) {
        if (pendingComposeDraft == null) {
            return false;
        }

        String n = normalize(message);

        boolean confirmed =
                n.equals("si")
                || n.equals("si envia")
                || n.equals("si envialo")
                || n.equals("envia")
                || n.equals("envialo")
                || n.equals("confirmo");

        boolean cancelled =
                n.equals("no")
                || n.equals("cancela")
                || n.equals("cancelalo")
                || n.equals("espera")
                || n.equals("dejalo");

        if (cancelled) {
            pendingComposeDraft = null;

            speakAlmaText(
                    "Cancelado. No envié nada.",
                    this::resetToWakeMode
            );

            return true;
        }

        if (!confirmed) {
            speakAlmaText(
                    "No te entendí. Decí sí, enviá, o cancelá.",
                    this::resumeConversationListening
            );

            return true;
        }

        ComposeCommand.Draft draft = pendingComposeDraft;
        pendingComposeDraft = null;

        speakAlmaText(
                "Confirmado. Abro el mensaje listo para enviar.",
                () -> openComposeDraft(draft)
        );

        return true;
    }

    private boolean handleMeetingModeCommand(String message) {
        String n = normalize(message);

        boolean enable =
                n.equals("modo reunion")
                || n.equals("activa modo reunion")
                || n.equals("activar modo reunion")
                || n.equals("inicia modo reunion")
                || n.equals("inicia reunion")
                || n.equals("empeza reunion");

        if (!enable) {
            return false;
        }

        getSharedPreferences(
                "alma_runtime",
                android.content.Context.MODE_PRIVATE
        ).edit()
         .putBoolean("meeting_mode", true)
         .apply();

        speakAlmaText(
                "Modo reunión activado. Dejo de escuchar hasta que lo desactives.",
                this::stopSelf
        );

        return true;
    }

    private boolean handleComposeRequest(String message) {
        ComposeCommand.Draft draft =
                ComposeCommand.parse(message);

        if (draft == null) {
            return false;
        }

        pendingComposeDraft = draft;

        String label =
                "whatsapp".equals(draft.type)
                        ? "WhatsApp"
                        : "correo";

        speakAlmaText(
                "Preparé el " + label
                        + " que dice: "
                        + draft.text
                        + ". ¿Confirmás? Decí sí, enviá, o cancelá.",
                this::resumeConversationListening
        );

        return true;
    }

    private void openComposeDraft(ComposeCommand.Draft draft) {
        if (draft == null) {
            resetToWakeMode();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, draft.text);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            if ("whatsapp".equals(draft.type)) {
                intent.setPackage("com.whatsapp");

                try {
                    startActivity(intent);
                } catch (Exception first) {
                    intent.setPackage("com.whatsapp.w4b");
                    startActivity(intent);
                }

            } else {
                intent.setPackage("com.google.android.gm");
                startActivity(intent);
            }

            speaking = false;
            stopSelf();

        } catch (Exception e) {
            intent.setPackage(null);

            try {
                Intent chooser =
                        Intent.createChooser(
                                intent,
                                "Enviar mensaje"
                        );

                chooser.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                );

                startActivity(chooser);
                speaking = false;
                stopSelf();

            } catch (Exception ignored) {
                speaking = false;
                resetToWakeMode();
            }
        }
    }

    private void clearPendingCall() {
        pendingCall = null;
        pendingCallMethod = null;
    }

    private boolean handlePendingCallFlow(String message) {
        if (pendingCall == null) return false;

        String n = normalize(message);

        boolean cancelled =
                n.equals("no")
                || n.equals("cancela")
                || n.equals("cancelalo")
                || n.equals("espera")
                || n.equals("dejalo");

        if (cancelled) {
            clearPendingCall();

            speakAlmaText(
                    "Cancelado. No hice la llamada.",
                    this::resetToWakeMode
            );

            return true;
        }

        if (pendingCallMethod == null) {
            String method =
                    ContactCallCommand.parseMethod(message);

            if (method == null) {
                speakAlmaText(
                        "Decime WhatsApp o línea.",
                        this::resumeConversationListening
                );
                return true;
            }

            pendingCallMethod = method;
            askCallConfirmation();
            return true;
        }

        boolean confirmed =
                n.equals("si")
                || n.equals("si llama")
                || n.equals("si llamalo")
                || n.equals("llama")
                || n.equals("llamalo")
                || n.equals("confirmo")
                || n.equals("confirmado");

        if (!confirmed) {
            speakAlmaText(
                    "No te entendí. Decí sí, llamá, o cancelá.",
                    this::resumeConversationListening
            );
            return true;
        }

        ContactCallCommand.Resolution call = pendingCall;
        String method = pendingCallMethod;

        clearPendingCall();

        if (ContactCallCommand.METHOD_LINE.equals(method)) {
            if (checkSelfPermission(
                    android.Manifest.permission.CALL_PHONE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                speakAlmaText(
                        "Falta el permiso de llamadas. Abrí ALMA y tocá PERMISOS.",
                        this::resetToWakeMode
                );
                return true;
            }

            speakAlmaText(
                    "Confirmado. Llamando a "
                            + call.displayName
                            + " por línea.",
                    () -> openLineCall(call)
            );

        } else {
            speakAlmaText(
                    "Confirmado. Abro WhatsApp en "
                            + call.displayName
                            + ".",
                    () -> openWhatsAppContact(call)
            );
        }

        return true;
    }

    private boolean handleCallRequest(String message) {
        ContactCallCommand.Draft draft =
                ContactCallCommand.parse(message);

        if (draft == null) return false;

        if (checkSelfPermission(
                android.Manifest.permission.READ_CONTACTS
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {

            speakAlmaText(
                    "Necesito permiso de contactos. Abrí ALMA y tocá PERMISOS.",
                    this::resetToWakeMode
            );
            return true;
        }

        ContactCallCommand.Resolution resolution =
                ContactCallCommand.resolve(
                        this,
                        draft.requestedName
                );

        if (resolution.ambiguous) {
            speakAlmaText(
                    "Encontré más de un contacto o número parecido. Decime el nombre completo.",
                    this::resumeConversationListening
            );
            return true;
        }

        if (!resolution.found()) {
            speakAlmaText(
                    "No encontré ese contacto.",
                    this::resumeConversationListening
            );
            return true;
        }

        pendingCall = resolution;
        pendingCallMethod = draft.preferredMethod;

        if (pendingCallMethod == null) {
            speakAlmaText(
                    "Encontré a "
                            + resolution.displayName
                            + ". ¿Querés llamar por WhatsApp o por línea?",
                    this::resumeConversationListening
            );
        } else {
            askCallConfirmation();
        }

        return true;
    }

    private void askCallConfirmation() {
        if (pendingCall == null || pendingCallMethod == null) {
            clearPendingCall();
            resetToWakeMode();
            return;
        }

        String label =
                ContactCallCommand.METHOD_WHATSAPP.equals(
                        pendingCallMethod
                )
                        ? "WhatsApp"
                        : "línea";

        speakAlmaText(
                pendingCall.displayName
                        + " por "
                        + label
                        + ". ¿Confirmás? Decí sí, llamá, o cancelá.",
                this::resumeConversationListening
        );
    }

    private void openLineCall(
            ContactCallCommand.Resolution call
    ) {
        Intent intent = new Intent(
                Intent.ACTION_CALL,
                android.net.Uri.parse(
                        "tel:" + android.net.Uri.encode(
                                call.phoneNumber
                        )
                )
        );

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(intent);
            speaking = false;
            stopSelf();
        } catch (Exception e) {
            speaking = false;
            resetToWakeMode();
        }
    }

    private void openWhatsAppContact(
            ContactCallCommand.Resolution call
    ) {
        Intent intent = new Intent(
                Intent.ACTION_SENDTO,
                android.net.Uri.parse(
                        "smsto:" + android.net.Uri.encode(
                                call.phoneNumber
                        )
                )
        );

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage("com.whatsapp");

        try {
            startActivity(intent);
            speaking = false;
            stopSelf();

        } catch (Exception first) {
            intent.setPackage("com.whatsapp.w4b");

            try {
                startActivity(intent);
                speaking = false;
                stopSelf();

            } catch (Exception second) {
                speaking = false;
                resetToWakeMode();
            }
        }
    }

    private boolean handlePendingCalendarConfirmation(String message) {
        if (pendingCalendarDraft == null) {
            return false;
        }

        String n = normalize(message);

        boolean confirmed =
                n.equals("si")
                || n.equals("si confirma")
                || n.equals("confirmo")
                || n.equals("confirmalo")
                || n.equals("agenda")
                || n.equals("agendalo");

        boolean cancelled =
                n.equals("no")
                || n.equals("cancela")
                || n.equals("cancelalo")
                || n.equals("espera")
                || n.equals("dejalo");

        if (cancelled) {
            pendingCalendarDraft = null;

            speakAlmaText(
                    "Cancelado. No agendé nada.",
                    this::resetToWakeMode
            );

            return true;
        }

        if (!confirmed) {
            speakAlmaText(
                    "No te entendí. Decí sí, confirmá, o cancelá.",
                    this::resumeConversationListening
            );

            return true;
        }

        CalendarCommand.Draft draft = pendingCalendarDraft;
        pendingCalendarDraft = null;

        speakAlmaText(
                "Confirmado. Abro el evento preparado.",
                () -> openCalendarDraft(draft)
        );

        return true;
    }

    private boolean handleCalendarRequest(String message) {
        CalendarCommand.Draft draft =
                CalendarCommand.parse(message);

        if (draft == null) {
            return false;
        }

        pendingCalendarDraft = draft;

        String when = new java.text.SimpleDateFormat(
                "EEEE d 'de' MMMM 'a las' HH:mm",
                new Locale("es", "AR")
        ).format(new java.util.Date(draft.startMillis));

        speakAlmaText(
                "Preparé " + draft.title
                        + ", " + when
                        + ". ¿Confirmás? Decí sí, confirmá, o cancelá.",
                this::resumeConversationListening
        );

        return true;
    }

    private void openCalendarDraft(CalendarCommand.Draft draft) {
        if (draft == null) {
            resetToWakeMode();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setData(
                android.provider.CalendarContract.Events.CONTENT_URI
        );
        intent.putExtra(
                android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                draft.startMillis
        );
        intent.putExtra(
                android.provider.CalendarContract.EXTRA_EVENT_END_TIME,
                draft.endMillis
        );
        intent.putExtra(
                android.provider.CalendarContract.Events.TITLE,
                draft.title
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(intent);
            speaking = false;
            stopSelf();
        } catch (Exception e) {
            speaking = false;
            resetToWakeMode();
        }
    }

    private boolean handleNavigationCommand(String message) {
        String destination =
                NavigationCommand.parseDestination(message);

        if (destination == null) {
            return false;
        }

        String encoded =
                android.net.Uri.encode(destination);

        Intent maps = new Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse(
                        "google.navigation:q=" + encoded
                )
        );

        maps.setPackage(
                "com.google.android.apps.maps"
        );
        maps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(maps);
            speaking = false;
            stopSelf();
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse(
                                "geo:0,0?q=" + encoded
                        )
                );

                fallback.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                );

                startActivity(fallback);
                speaking = false;
                stopSelf();

            } catch (Exception ignored) {
                speaking = false;
                resetToWakeMode();
            }
        }

        return true;
    }

    private boolean handleAppLaunchCommand(String message) {
        AppLaunchCommand.Target target = AppLaunchCommand.parse(message);

        if (target == null) {
            return false;
        }

        for (String packageName : target.packages) {
            Intent launch =
                    getPackageManager().getLaunchIntentForPackage(packageName);

            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                updateNotification("Abriendo " + target.label);

                try {
                    startActivity(launch);
                    speaking = false;
                    stopSelf();
                } catch (Exception e) {
                    speaking = false;
                    resetToWakeMode();
                }

                return true;
            }
        }

        updateNotification(target.label + " no está instalado");
        speaking = false;
        resetToWakeMode();
        return true;
    }

    private boolean handleVideoCommand(String message) {
        String normalized = normalize(message);

        boolean wantsVideo =
                normalized.contains("video")
                || normalized.contains("youtube")
                || ((normalized.startsWith("pone ")
                        || normalized.startsWith("poneme ")
                        || normalized.startsWith("reproduci ")
                        || normalized.startsWith("reproduce "))
                    && normalized.contains("altavoz"));

        if (!wantsVideo) {
            return false;
        }

        String query = normalized
                .replaceFirst("^(busca|buscame|mostra|mostrame|pone|poneme|reproduci|reproduce)\\s+", "")
                .replaceFirst("^un\\s+", "")
                .replaceFirst("^video\\s+(de|del)?\\s*", "")
                .replaceAll("\\b(en\\s+)?youtube\\b", "")
                .replaceAll("\\b(en\\s+el\\s+|por\\s+el\\s+)?altavoz\\b", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (query.isEmpty()) {
            return false;
        }

        speaking = true;
        updateNotification("Buscando en YouTube: " + query);

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    updateNotification("Abrí ALMA para configurar la clave");
                    resetToWakeMode();
                });
                return;
            }

            try {
                org.json.JSONObject result = api.searchYouTube(query, token);
                String videoId = result.getString("video_id");
                String title = result.optString("title", query);

                handler.post(() -> {
                    try {
                        Intent intent = new Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.youtube.com/watch?v=" + videoId)
                        );
                        intent.setPackage("com.google.android.youtube");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent fallback = new Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.youtube.com/watch?v=" + videoId)
                        );
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fallback);
                    }

                    speaking = false;
                    stopSelf();
                });

            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;
                    updateNotification("No pude buscar el video");
                    resetToWakeMode();
                });
            }
        }, "ALMA-YouTube").start();

        return true;
    }

    private void sendToAlma(String message) {
        if (message == null || message.trim().isEmpty()) {
            resetToWakeMode();
            return;
        }

        if (handleMeetingModeCommand(message)) {
            return;
        }

        if (handlePendingCallFlow(message)) {
            return;
        }

        if (handlePendingComposeConfirmation(message)) {
            return;
        }

        if (handlePendingCalendarConfirmation(message)) {
            return;
        }

        if (handleComposeRequest(message)) {
            return;
        }

        if (handleCalendarRequest(message)) {
            return;
        }

        if (handleCallRequest(message)) {
            return;
        }

        if (handleNavigationCommand(message)) {
            return;
        }

        if (handleAppLaunchCommand(message)) {
            return;
        }

        if (handleVideoCommand(message)) {
            return;
        }

        speaking = true;
        updateNotification("ALMA está pensando");

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    conversationActive = false;
                    updateNotification(
                            "Abrí ALMA para configurar la clave"
                    );

                    handler.postDelayed(
                            this::resetToWakeMode,
                            1200
                    );
                });
                return;
            }

            try {
                String reply = api.chat(
                        userId,
                        sessionId,
                        message,
                        token
                );

                byte[] audio = api.tts(reply, token);

                handler.post(() ->
                        playAudio(
                                audio,
                                this::resumeConversationListening
                        )
                );

            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;
                    updateNotification(
                            "Error de conexión. ALMA sigue activa"
                    );
                    resumeConversationListening();
                });
            }
        }, "ALMA-Chat").start();
    }

    private void speakAlmaText(
            String text,
            Runnable afterPlayback
    ) {
        speaking = true;

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    conversationActive = false;
                    updateNotification(
                            "Abrí ALMA para configurar la clave"
                    );
                    handler.postDelayed(
                            this::resetToWakeMode,
                            1200
                    );
                });
                return;
            }

            try {
                byte[] audio = api.tts(text, token);

                handler.post(() ->
                        playAudio(audio, afterPlayback)
                );

            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;

                    if (afterPlayback != null) {
                        afterPlayback.run();
                    }
                });
            }
        }, "ALMA-TTS").start();
    }

    private void playAudio(
            byte[] audio,
            Runnable afterPlayback
    ) {
        try {
            speaking = true;

            if (voskWakeWord != null) {
                voskWakeWord.stopListening();
            }

            File file = File.createTempFile(
                    "alma_handsfree_",
                    ".mp3",
                    getCacheDir()
            );
            currentAudioFile = file;

            try (FileOutputStream out =
                         new FileOutputStream(file)) {
                out.write(audio);
            }

            if (currentPlayer != null) {
                try {
                    currentPlayer.release();
                } catch (Exception ignored) {
                }
            }

            currentPlayer = new MediaPlayer();
            currentPlayer.setDataSource(
                    file.getAbsolutePath()
            );

            currentPlayer.setOnCompletionListener(mp -> {
                try {
                    mp.release();
                } catch (Exception ignored) {
                }

                currentPlayer = null;
                currentAudioFile = null;
                file.delete();
                speaking = false;

                if (afterPlayback != null) {
                    afterPlayback.run();
                }
            });

            currentPlayer.setOnErrorListener(
                    (mp, what, extra) -> {
                        try {
                            mp.release();
                        } catch (Exception ignored) {
                        }

                        currentPlayer = null;
                        file.delete();
                        speaking = false;

                        if (afterPlayback != null) {
                            afterPlayback.run();
                        }

                        return true;
                    }
            );

            currentPlayer.prepare();
            currentPlayer.start();

            updateNotification("ALMA está hablando");


        } catch (Exception e) {
            speaking = false;

            if (afterPlayback != null) {
                afterPlayback.run();
            }
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager == null) return;

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "ALMA manos libres",
                        NotificationManager.IMPORTANCE_LOW
                );

        channel.setDescription(
                "ALMA escucha la palabra de activación "
                        + "mientras el modo manos libres está activo."
        );
        channel.setSound(null, null);
        channel.enableVibration(false);

        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        Intent openApp =
                new Intent(this, MainActivity.class);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        openApp,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        return new Notification.Builder(
                this,
                CHANNEL_ID
        )
                .setSmallIcon(
                        android.R.drawable.ic_btn_speak_now
                )
                .setContentTitle("ALMA · Manos libres")
                .setContentText(status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager != null) {
            manager.notify(
                    NOTIFICATION_ID,
                    buildNotification(status)
            );
        }
    }

    @Override
    public void onWakeWord(String recognizedText) {
        if (destroyed || speaking) return;

        activateConversation(recognizedText);
    }

    @Override
    public void onInterrupt(String recognizedText) {
        if (destroyed || !speaking) return;

        MediaPlayer player = currentPlayer;
        currentPlayer = null;

        if (player != null) {
            try {
                player.setOnCompletionListener(null);
                player.setOnErrorListener(null);
                player.stop();
            } catch (Exception ignored) {
            }

            try {
                player.release();
            } catch (Exception ignored) {
            }
        }

        File file = currentAudioFile;
        currentAudioFile = null;

        if (file != null) {
            try {
                file.delete();
            } catch (Exception ignored) {
            }
        }

        speaking = false;
        conversationActive = true;
        updateNotification("Te escucho");

        if (voskWakeWord != null) {
            voskWakeWord.startConversation(
                    CONVERSATION_IDLE_MS
            );
        }
    }

    @Override
    public void onConversationAudio(byte[] pcm16, String localText) {
        if (destroyed || speaking) return;


        speaking = true;
        updateNotification("Entendiendo tu voz");

        new Thread(() -> {
            String token = tokenStore.load();

            if (token == null || token.trim().isEmpty()) {
                handler.post(() -> {
                    speaking = false;
                    resetToWakeMode();
                });
                return;
            }

            try {
                String transcript = api.transcribe(pcm16, token);

                handler.post(() -> {
                    speaking = false;

                    String text = transcript == null
                            ? ""
                            : transcript.trim();

                    if (text.isEmpty()) {
                        resumeConversationListening();
                        return;
                    }

                    if (isEndPhrase(text)) {
                        resetToWakeMode();
                        return;
                    }

                    sendToAlma(text);
                });

            } catch (Exception e) {
                handler.post(() -> {
                    speaking = false;

                    String fallback = localText == null
                            ? ""
                            : localText.trim();

                    if (!fallback.isEmpty()) {
                        if (isEndPhrase(fallback)) {
                            resetToWakeMode();
                        } else {
                            sendToAlma(fallback);
                        }
                    } else {
                        updateNotification("Seguimos escuchando");
                        resumeConversationListening();
                    }
                });
            }
        }, "ALMA-Transcribe").start();
    }

    @Override
    public void onConversationTimeout() {
        if (destroyed) return;

        resetToWakeMode();
    }

    @Override
    public void onError(Exception error) {
        if (destroyed) return;

        if (speaking) {
            updateNotification("ALMA está hablando");
            return;
        }

        updateNotification(
                "Reiniciando reconocimiento"
        );

        if (voskWakeWord != null) {
            voskWakeWord.stopListening();
        }

        handler.postDelayed(
                this::resetToWakeMode,
                800
        );
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);

        if (voskWakeWord != null) {
            voskWakeWord.destroy();
            voskWakeWord = null;
        }

        if (currentPlayer != null) {
            try {
                currentPlayer.release();
            } catch (Exception ignored) {
            }
            currentPlayer = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
