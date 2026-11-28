package dev.jun.gyeot.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Small fixes to showcase pieces the user keeps on a home page (준: "턴테이블 음악 잡음을 좀 더 자연스럽게 하고 잡음을 줄여줘").
 * The two turntables synthesize their record noise loud and bright: a loud hiss, twenty sharp pops a second and a short loop
 * the ear hears go round. Here the hiss is a third as loud, the pops fewer and softer, the noise passed through a warm lowpass,
 * and the walnut deck's loop four times longer (the scratch still ticks once a turn).
 *
 * The walnut deck (057) also gets 들퍼레's add-on (준: "하방 스크롤 안하고 곡목록 보기 및 선택가능하게해줘", "내 폴더에 있는 음악들도
 * 재생가능하게 해줘", the three.js deck): a few hooks that let the add-on read the piece's own state, and its scripts before
 * </body>. Its fixes live in resources (piece-057.json), so tests/browser/vinyl.test.js applies the very same ones to the live
 * piece. Each fix is exact text that must be found once; if the piece was changed and one is not, the piece is left as it is
 * (null), with the noise fixes too. Pure Java.
 */
public final class PieceTune {
    private PieceTune() {}
    /** One fix: exact text and what it becomes; why says what it is for (diagnostics, the fixes file). */
    public record Fix(String why, String find, String put) {}
    /** A tuned page, or which fix (counted from 1) no longer matched and why; html is null then. */
    public record Tuned(String html, int failed, String why) {}

    private static final Map<String, List<Fix>> FIXES = fixes();

    private static Map<String, List<Fix>> fixes() {
        Map<String, List<Fix>> all = new HashMap<>();
        // Opus 5.5 · 057 Hi-Fi Walnut — Vinyl Listening Room: the noise, the hooks and the add-on.
        JSONObject walnut = resource("piece-057.json");
        if (walnut != null) all.put(walnut.optString("path"), read(walnut));
        // Sonnet 5.5 · 036 Rainy Sunday Records — a lo-fi turntable
        all.put("/Sonnet-5.5-100-HTML-Files/036-lofi-turntable.html", List.of(
            new Fix("noise: quieter hiss and fewer, softer pops", "cd[i]=(Math.random()*2-1)*.012;if(Math.random()<.0006){var a=(Math.random()<.5?-1:1)*(.15+Math.random()*.7)",
                "cd[i]=(Math.random()*2-1)*.0045;if(Math.random()<.00022){var a=(Math.random()<.5?-1:1)*(.05+Math.pow(Math.random(),2)*.3)"),
            new Fix("noise: through a warm lowpass", "crS.connect(crHp);crHp.connect(crG);",
                "var crLp=ctx.createBiquadFilter();crLp.type='lowpass';crLp.frequency.value=4800;crLp.Q.value=.5;crS.connect(crHp);crHp.connect(crLp);crLp.connect(crG);"),
            new Fix("noise: the surface quieter", "crG.gain.setTargetAtTime(.5,t,.15);", "crG.gain.setTargetAtTime(.34,t,.15);")));
        return Map.copyOf(all);
    }
    /** A fixes file's list ({"path", "fixes": [{why, find, put}]}). */
    public static List<Fix> read(JSONObject file) {
        List<Fix> out = new ArrayList<>();
        JSONArray list = file == null ? null : file.optJSONArray("fixes");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f != null && !f.optString("find").isEmpty()) out.add(new Fix(f.optString("why"), f.optString("find"), f.optString("put")));
        }
        return List.copyOf(out);
    }
    private static JSONObject resource(String name) {
        try (InputStream in = PieceTune.class.getResourceAsStream(name)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(); in.transferTo(out);
            return new JSONObject(out.toString(StandardCharsets.UTF_8));
        } catch (Exception unreadable) { return null; }
    }

    /** Whether this address (host and path) is a piece with fixes. */
    public static boolean knows(String host, String path) {
        return HtmlShowcase.HOST.equalsIgnoreCase(host == null ? "" : host) && FIXES.containsKey(path == null ? "" : path);
    }
    /** A piece's fixes, in order (none for a piece without). */
    public static List<Fix> fixes(String path) { return FIXES.getOrDefault(path == null ? "" : path, List.of()); }
    /** The piece's page with its fixes, or null when it has none or one no longer matches (then the page is used unchanged). */
    public static String tune(String path, String html) { return apply(path, html).html(); }
    /** The same, saying which fix failed when one did. */
    public static Tuned apply(String path, String html) {
        List<Fix> fixes = FIXES.get(path == null ? "" : path);
        if (fixes == null || html == null) return new Tuned(null, 0, "고칠 작품이 아니에요");
        return apply(fixes, html);
    }
    /** Fixes applied in order; each find must occur exactly once in the page as the fixes before it left it. */
    public static Tuned apply(List<Fix> fixes, String html) {
        String out = html;
        for (int i = 0; i < fixes.size(); i++) {
            Fix f = fixes.get(i);
            int at = out.indexOf(f.find());
            if (at < 0) return new Tuned(null, i + 1, "찾지 못함: " + f.why());
            if (out.indexOf(f.find(), at + 1) >= 0) return new Tuned(null, i + 1, "두 군데 이상: " + f.why());
            out = out.substring(0, at) + f.put() + out.substring(at + f.find().length());
        }
        return new Tuned(out, 0, "");
    }
}
