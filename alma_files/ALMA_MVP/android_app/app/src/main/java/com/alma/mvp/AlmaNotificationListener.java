package com.alma.mvp;

import android.app.Notification;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

public class AlmaNotificationListener
        extends NotificationListenerService {

    private static final String PREFS =
            "alma_notifications";

    private static final String KEY =
            "recent";

    private static final int MAX_ITEMS = 12;

    @Override
    public void onNotificationPosted(
            StatusBarNotification sbn
    ) {
        if (sbn == null
                || sbn.getPackageName() == null
                || sbn.getPackageName().equals(getPackageName())) {
            return;
        }

        Notification notification =
                sbn.getNotification();

        if (notification == null) {
            return;
        }

        CharSequence titleValue =
                notification.extras.getCharSequence(
                        Notification.EXTRA_TITLE
                );

        CharSequence textValue =
                notification.extras.getCharSequence(
                        Notification.EXTRA_TEXT
                );

        String title =
                clean(titleValue == null
                        ? ""
                        : titleValue.toString());

        String text =
                clean(textValue == null
                        ? ""
                        : textValue.toString());

        if (title.isEmpty() && text.isEmpty()) {
            return;
        }

        String app = appLabel(
                this,
                sbn.getPackageName()
        );

        save(
                this,
                app,
                title,
                text,
                System.currentTimeMillis()
        );
    }

    static boolean hasAccess(Context context) {
        return androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.getPackageName());
    }

    static String readSummary(
            Context context,
            int limit
    ) {
        JSONArray data = load(context);

        if (data.length() == 0) {
            return "No tengo notificaciones recientes para leerte.";
        }

        int count = Math.min(
                Math.max(limit, 1),
                data.length()
        );

        StringBuilder result = new StringBuilder();

        if (count == 1) {
            result.append("Tu última notificación es: ");
        } else {
            result.append(
                    "Tenés "
                            + count
                            + " notificaciones recientes. "
            );
        }

        for (int i = 0; i < count; i++) {
            JSONObject item = data.optJSONObject(i);

            if (item == null) continue;

            String app =
                    item.optString("app", "Aplicación");

            String title =
                    item.optString("title", "");

            String text =
                    item.optString("text", "");

            result.append(i + 1)
                    .append(", ")
                    .append(app);

            if (!title.isEmpty()) {
                result.append(", ")
                        .append(title);
            }

            if (!text.isEmpty()) {
                result.append(": ")
                        .append(text);
            }

            if (i < count - 1) {
                result.append(". ");
            }
        }

        return result.toString().trim();
    }

    private static synchronized void save(
            Context context,
            String app,
            String title,
            String text,
            long time
    ) {
        try {
            JSONArray old = load(context);

            if (old.length() > 0) {
                JSONObject first =
                        old.optJSONObject(0);

                if (first != null
                        && app.equals(first.optString("app"))
                        && title.equals(first.optString("title"))
                        && text.equals(first.optString("text"))) {
                    return;
                }
            }

            JSONArray fresh = new JSONArray();

            JSONObject item = new JSONObject();
            item.put("app", app);
            item.put("title", title);
            item.put("text", text);
            item.put("time", time);

            fresh.put(item);

            for (int i = 0;
                 i < old.length()
                         && fresh.length() < MAX_ITEMS;
                 i++) {

                JSONObject previous =
                        old.optJSONObject(i);

                if (previous != null) {
                    fresh.put(previous);
                }
            }

            context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
            ).edit()
             .putString(KEY, fresh.toString())
             .apply();

        } catch (Exception ignored) {
        }
    }

    private static JSONArray load(
            Context context
    ) {
        String raw =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                ).getString(KEY, "[]");

        try {
            return new JSONArray(raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static String appLabel(
            Context context,
            String packageName
    ) {
        try {
            android.content.pm.ApplicationInfo info =
                    context.getPackageManager()
                            .getApplicationInfo(
                                    packageName,
                                    0
                            );

            CharSequence label =
                    context.getPackageManager()
                            .getApplicationLabel(info);

            return label == null
                    ? packageName
                    : label.toString();

        } catch (Exception ignored) {
            return packageName;
        }
    }

    private static String clean(
            String value
    ) {
        if (value == null) return "";

        String clean = value
                .replaceAll("\\s+", " ")
                .trim();

        if (clean.length() > 220) {
            clean = clean.substring(0, 220);
        }

        return clean;
    }
}
