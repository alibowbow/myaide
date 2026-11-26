package dev.jun.gyeot.core;

import java.util.List;
import java.util.Map;

/**
 * Small fixes to showcase pieces the user keeps on a home page (준: "턴테이블 음악 잡음을 좀 더 자연스럽게 하고 잡음을 줄여줘").
 * The two turntables synthesize their record noise loud and bright: a loud hiss, twenty sharp pops a second and a short loop
 * the ear hears go round. Here the hiss is a third as loud, the pops fewer and softer, the noise passed through a warm lowpass,
 * and the walnut deck's loop four times longer (the scratch still ticks once a turn). Each fix is exact text that must be found
 * once; if the piece was changed and one is not, the piece is left as it is (null). Pure Java.
 */
public final class PieceTune {
    private PieceTune() {}
    private record Fix(String find, String put) {}

    private static final Map<String, List<Fix>> FIXES = Map.of(
        // Opus 5.5 · 057 Hi-Fi Walnut — Vinyl Listening Room
        "/Claude-Opus-5.5-100-HTML-Files/057-vinyl-listening-room.html", List.of(
            new Fix("const cLen = Math.floor(sr * 3.6), cb", "const cLen = Math.floor(sr * 14.4), cb"),
            new Fix("d[i] = (w - lp) * 0.0065;", "d[i] = (w - lp) * 0.0022;"),
            new Fix("const pops = Math.floor(3.6 * 20);", "const pops = Math.floor(14.4 * 5);"),
            new Fix("amp = 0.015 + Math.pow(Math.random(), 3) * 0.3,", "amp = 0.008 + Math.pow(Math.random(), 4) * 0.09,"),
            new Fix("for (const t0 of [0.42, 2.22])", "for (const t0 of [0.42, 2.22, 4.02, 5.82, 7.62, 9.42, 11.22, 13.02])"),
            new Fix("d[pos + j] += 0.2 * Math.exp(-j / 26)", "d[pos + j] += 0.05 * Math.exp(-j / 30)"),
            new Fix("+ 0.05 * Math.sin(j / sr * 2 * Math.PI * 85)", "+ 0.02 * Math.sin(j / sr * 2 * Math.PI * 85)"),
            new Fix("cs.connect(chp); chp.connect(crackleGain);",
                "const clp = actx.createBiquadFilter(); clp.type = 'lowpass'; clp.frequency.value = 4600; clp.Q.value = 0.5; cs.connect(chp); chp.connect(clp); clp.connect(crackleGain);"),
            new Fix("on ? 0.55 * A.crackle : 0", "on ? 0.36 * A.crackle : 0")),
        // Sonnet 5.5 · 036 Rainy Sunday Records — a lo-fi turntable
        "/Sonnet-5.5-100-HTML-Files/036-lofi-turntable.html", List.of(
            new Fix("cd[i]=(Math.random()*2-1)*.012;if(Math.random()<.0006){var a=(Math.random()<.5?-1:1)*(.15+Math.random()*.7)",
                "cd[i]=(Math.random()*2-1)*.0045;if(Math.random()<.00022){var a=(Math.random()<.5?-1:1)*(.05+Math.pow(Math.random(),2)*.3)"),
            new Fix("crS.connect(crHp);crHp.connect(crG);",
                "var crLp=ctx.createBiquadFilter();crLp.type='lowpass';crLp.frequency.value=4800;crLp.Q.value=.5;crS.connect(crHp);crHp.connect(crLp);crLp.connect(crG);"),
            new Fix("crG.gain.setTargetAtTime(.5,t,.15);", "crG.gain.setTargetAtTime(.34,t,.15);")));

    /** Whether this address (host and path) is a piece with fixes. */
    public static boolean knows(String host, String path) {
        return HtmlShowcase.HOST.equalsIgnoreCase(host == null ? "" : host) && FIXES.containsKey(path == null ? "" : path);
    }
    /** The piece's page with its fixes, or null when it has none or one no longer matches (then the page is used unchanged). */
    public static String tune(String path, String html) {
        List<Fix> fixes = FIXES.get(path == null ? "" : path);
        if (fixes == null || html == null) return null;
        String out = html;
        for (Fix f : fixes) {
            int at = out.indexOf(f.find());
            if (at < 0 || out.indexOf(f.find(), at + 1) >= 0) return null;
            out = out.substring(0, at) + f.put() + out.substring(at + f.find().length());
        }
        return out;
    }
}
