package com.alma.mvp;

import java.text.Normalizer;
import java.util.Locale;

final class NavigationCommand {
    static String parseDestination(String message) {
        if (message == null) return null;

        String n = Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}", "")
         .replaceAll("\\s+", " ")
         .trim();

        String[] prefixes = new String[] {
                "llevame a ",
                "llevame hasta ",
                "navega a ",
                "navega hasta ",
                "como llego a ",
                "como llegar a ",
                "guiame a ",
                "guiame hasta ",
                "ir a "
        };

        for (String prefix : prefixes) {
            if (n.startsWith(prefix)) {
                String destination = n.substring(prefix.length()).trim();
                return destination.isEmpty() ? null : destination;
            }
        }

        return null;
    }

    private NavigationCommand() {}
}
