package com.alma.mvp;

import java.text.Normalizer;
import java.util.Locale;

final class AppLaunchCommand {
    static final class Target {
        final String label;
        final String[] packages;

        Target(String label, String... packages) {
            this.label = label;
            this.packages = packages;
        }
    }

    static Target parse(String message) {
        if (message == null) return null;

        String n = Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "")
         .replaceAll("\\s+", " ")
         .trim();

        if (!n.matches("^(abri|abre|abrime|abreme|abrir)\\s+.+$")) {
            return null;
        }

        String target = n
                .replaceFirst("^(abri|abre|abrime|abreme|abrir)\\s+", "")
                .replaceFirst("^(la\\s+app\\s+de\\s+|la\\s+aplicacion\\s+de\\s+|el\\s+|la\\s+)", "")
                .trim();

        switch (target) {
            case "whatsapp":
            case "watsap":
            case "wsp":
                return new Target("WhatsApp",
                        "com.whatsapp",
                        "com.whatsapp.w4b");

            case "instagram":
            case "insta":
                return new Target("Instagram",
                        "com.instagram.android");

            case "gmail":
            case "correo":
                return new Target("Gmail",
                        "com.google.android.gm");

            case "maps":
            case "google maps":
            case "mapas":
                return new Target("Google Maps",
                        "com.google.android.apps.maps");

            case "uber":
                return new Target("Uber",
                        "com.ubercab");

            case "linkedin":
                return new Target("LinkedIn",
                        "com.linkedin.android");

            case "youtube":
                return new Target("YouTube",
                        "com.google.android.youtube");

            default:
                return null;
        }
    }

    private AppLaunchCommand() {}
}
