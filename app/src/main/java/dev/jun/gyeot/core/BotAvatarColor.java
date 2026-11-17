package dev.jun.gyeot.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** color.ts: #rgb / #rrggbb parsing and the small color helpers ({r, g, b} channels 0..255). */
final class BotAvatarColor {
    private BotAvatarColor() {}

    private static final Pattern HEX = Pattern.compile("#([0-9a-f]{3}|[0-9a-f]{6})", Pattern.CASE_INSENSITIVE);
    static final double[] DARK_INK = {20, 22, 34}, LIGHT_INK = {250, 250, 255};

    static double[] parseHex(String input) {
        if (input == null) return null;
        Matcher m = HEX.matcher(BotAvatarJs.trim(input));
        if (!m.matches()) return null;
        String h = m.group(1);
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        return new double[] {Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16), Integer.parseInt(h.substring(4, 6), 16)};
    }

    static String toHex(double[] c) {
        StringBuilder out = new StringBuilder("#");
        for (double v : c) {
            String s = Integer.toHexString((int) BotAvatarJs.round(Math.min(255, Math.max(0, v))));
            if (s.length() < 2) out.append('0');
            out.append(s);
        }
        return out.toString();
    }

    static double[] mix(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /** Darken toward a deeper, slightly saturated version of the color rather than gray-black. */
    static double[] shade(double[] c, double t) {
        double max = Math.max(c[0], Math.max(c[1], c[2]));
        double[] deep = {c[0] * 0.35 - (max - c[0]) * 0.1, c[1] * 0.35 - (max - c[1]) * 0.1, c[2] * 0.35 - (max - c[2]) * 0.1};
        return mix(c, deep, t);
    }

    static double[] tint(double[] c, double t) { return mix(c, new double[] {255, 255, 255}, t); }

    /** WCAG relative luminance. */
    static double luminance(double[] c) {
        return 0.2126 * channel(c[0]) + 0.7152 * channel(c[1]) + 0.0722 * channel(c[2]);
    }

    private static double channel(double v) {
        double s = v / 255;
        return s <= 0.03928 ? s / 12.92 : StrictMath.pow((s + 0.055) / 1.055, 2.4);
    }

    static double contrast(double[] a, double[] b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** The eye ink with the best contrast against the body color. */
    static double[] autoEyeColor(double[] body) { return contrast(body, DARK_INK) >= contrast(body, LIGHT_INK) ? DARK_INK : LIGHT_INK; }
}
