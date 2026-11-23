package com.alma.mvp.alarm;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Opciones optativas de una alarma. Sin peticiones adicionales, solo saludo. */
public final class AlmaWakeOptions {
    public final boolean weather;
    public final boolean radio;

    private AlmaWakeOptions(boolean weather, boolean radio) {
        this.weather = weather;
        this.radio = radio;
    }

    public static AlmaWakeOptions parse(String spokenCommand) {
        if (spokenCommand == null) return new AlmaWakeOptions(false, false);
        String text = Normalizer.normalize(
                spokenCommand.toLowerCase(Locale.forLanguageTag("es-AR")),
                Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();

        // No confundir "dentro de un tiempo" con un pedido de pronóstico.
        boolean weather = Pattern.compile(
                "\\b(?:clima|pronostico|meteorologico|meteorologia|"
                + "como (?:va a estar|estara|esta) el tiempo|"
                + "(?:el|del|sobre el|sobre) tiempo)\\b")
                .matcher(text).find();

        // Radio 10 AM 710 es la emisora elegida. "la radio" usa esa preferencia.
        boolean radio = Pattern.compile("\\b(?:radio|emisora|710)\\b")
                .matcher(text).find();

        // Decir explícitamente que no quiere la opción debe prevalecer.
        if (Pattern.compile("\\b(?:sin|no quiero|no pongas|no prendas) (?:la |el )?radio\\b")
                .matcher(text).find()) radio = false;
        if (Pattern.compile("\\b(?:sin|no quiero|no me digas) (?:el |del )?(?:clima|pronostico|tiempo)\\b")
                .matcher(text).find()) weather = false;
        return new AlmaWakeOptions(weather, radio);
    }
}
