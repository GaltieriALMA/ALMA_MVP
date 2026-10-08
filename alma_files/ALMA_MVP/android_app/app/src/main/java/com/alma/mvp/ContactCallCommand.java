package com.alma.mvp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ContactCallCommand {

    static final String METHOD_WHATSAPP = "whatsapp";
    static final String METHOD_LINE = "line";

    static final class Draft {
        final String requestedName;
        final String preferredMethod;

        Draft(String requestedName, String preferredMethod) {
            this.requestedName = requestedName;
            this.preferredMethod = preferredMethod;
        }
    }

    static final class Resolution {
        final String displayName;
        final String phoneNumber;
        final boolean ambiguous;

        Resolution(String displayName, String phoneNumber, boolean ambiguous) {
            this.displayName = displayName;
            this.phoneNumber = phoneNumber;
            this.ambiguous = ambiguous;
        }

        boolean found() {
            return phoneNumber != null && !phoneNumber.trim().isEmpty();
        }
    }

    static Draft parse(String message) {
        String n = normalize(message);

        if (n.isEmpty() || n.contains("uber")) return null;

        Matcher matcher = Pattern.compile(
                "^(?:llama|llame|llamar)\\s+(.+)$"
        ).matcher(n);

        if (!matcher.find()) return null;

        String body = matcher.group(1).trim();
        String method = null;

        if (body.startsWith("por whatsapp a ")) {
            method = METHOD_WHATSAPP;
            body = body.substring("por whatsapp a ".length()).trim();
        } else if (body.startsWith("por watsap a ")) {
            method = METHOD_WHATSAPP;
            body = body.substring("por watsap a ".length()).trim();
        } else if (body.startsWith("por linea a ")) {
            method = METHOD_LINE;
            body = body.substring("por linea a ".length()).trim();
        } else if (body.startsWith("por numero de linea a ")) {
            method = METHOD_LINE;
            body = body.substring("por numero de linea a ".length()).trim();
        } else {
            body = body.replaceFirst("^a\\s+", "");

            if (body.endsWith(" por whatsapp")) {
                method = METHOD_WHATSAPP;
                body = body.substring(
                        0,
                        body.length() - " por whatsapp".length()
                ).trim();
            } else if (body.endsWith(" por watsap")) {
                method = METHOD_WHATSAPP;
                body = body.substring(
                        0,
                        body.length() - " por watsap".length()
                ).trim();
            } else if (body.endsWith(" por linea")) {
                method = METHOD_LINE;
                body = body.substring(
                        0,
                        body.length() - " por linea".length()
                ).trim();
            } else if (body.endsWith(" por numero de linea")) {
                method = METHOD_LINE;
                body = body.substring(
                        0,
                        body.length() - " por numero de linea".length()
                ).trim();
            }
        }

        if (body.isEmpty()) return null;

        return new Draft(body, method);
    }

    static String parseMethod(String message) {
        String n = normalize(message);

        if (n.contains("whatsapp") || n.contains("watsap")) {
            return METHOD_WHATSAPP;
        }

        if (n.equals("linea")
                || n.equals("por linea")
                || n.equals("telefono")
                || n.equals("por telefono")
                || n.equals("numero")
                || n.equals("numero de linea")
                || n.equals("llamada normal")) {
            return METHOD_LINE;
        }

        return null;
    }

    static Resolution resolve(Context context, String requestedName) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return new Resolution(null, null, false);
        }

        String wanted = normalize(requestedName);

        Map<String, Candidate> exact = new LinkedHashMap<>();
        Map<String, Candidate> partial = new LinkedHashMap<>();

        String[] projection = new String[] {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
        };

        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
        )) {
            if (cursor == null) {
                return new Resolution(null, null, false);
            }

            int nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            );
            int numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
            );
            int typeIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.TYPE
            );

            while (cursor.moveToNext()) {
                String display =
                        nameIndex >= 0 ? cursor.getString(nameIndex) : null;
                String number =
                        numberIndex >= 0 ? cursor.getString(numberIndex) : null;
                int type =
                        typeIndex >= 0 ? cursor.getInt(typeIndex) : 0;

                if (display == null || number == null) continue;

                String normalizedDisplay = normalize(display);

                boolean isExact = normalizedDisplay.equals(wanted);
                boolean isPartial = normalizedDisplay.contains(wanted);

                if (!isExact && !isPartial) continue;

                Map<String, Candidate> target =
                        isExact ? exact : partial;

                Candidate candidate = target.computeIfAbsent(
                        normalizedDisplay,
                        key -> new Candidate(display)
                );

                candidate.add(
                        number,
                        type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                );
            }
        }

        Map<String, Candidate> selected =
                !exact.isEmpty() ? exact : partial;

        if (selected.isEmpty()) {
            return new Resolution(null, null, false);
        }

        if (selected.size() != 1) {
            return new Resolution(null, null, true);
        }

        Candidate candidate =
                selected.values().iterator().next();

        String number = candidate.bestNumber();

        if (number == null) {
            return new Resolution(null, null, true);
        }

        return new Resolution(
                candidate.displayName,
                number,
                false
        );
    }

    private static final class Candidate {
        final String displayName;
        final LinkedHashSet<String> mobileNumbers = new LinkedHashSet<>();
        final LinkedHashSet<String> allNumbers = new LinkedHashSet<>();

        Candidate(String displayName) {
            this.displayName = displayName;
        }

        void add(String number, boolean mobile) {
            String clean = PhoneNumberUtils.normalizeNumber(number);

            if (clean == null || clean.trim().isEmpty()) return;

            allNumbers.add(clean);

            if (mobile) {
                mobileNumbers.add(clean);
            }
        }

        String bestNumber() {
            LinkedHashSet<String> preferred =
                    !mobileNumbers.isEmpty()
                            ? mobileNumbers
                            : allNumbers;

            if (preferred.size() != 1) {
                return null;
            }

            return preferred.iterator().next();
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";

        return Normalizer.normalize(
                value.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "")
         .replaceAll("\\s+", " ")
         .trim();
    }

    private ContactCallCommand() {}
}
