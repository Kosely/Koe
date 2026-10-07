package dev.koegoated.swiftguild.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Locale;

public final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {}

    public static Component c(String s) {
        return LEGACY.deserialize(s == null ? "" : s);
    }

    /** Composant sans italique (noms/lores d'items). */
    public static Component item(String s) {
        return c(s).decoration(TextDecoration.ITALIC, false);
    }

    public static String num(long n) {
        String s = Long.toString(Math.abs(n));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + b;
    }

    public static String kdr(double d) {
        return String.format(Locale.US, "%.2f", d);
    }

    public static String hours(long seconds) {
        return num(seconds / 3600) + "h";
    }

    public static String time(long s) {
        long h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, x) : String.format("%02d:%02d", m, x);
    }
}
