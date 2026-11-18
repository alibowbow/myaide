package dev.jun.gyeot.core;

import static dev.jun.gyeot.core.Jsons.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The affine points of a short Weierstrass equation over a small prime field, for a legible
 * classroom plot.
 */
public final class BoardField {
    private BoardField() {}

    private static final Pattern EQUATION =
            Pattern.compile(
                    "y\\^2[≡=]x\\^3(?:([+-])(\\d*)\\*?x)?(?:([+-])(\\d+))?\\(?mod(\\d+)\\)?",
                    Pattern.CASE_INSENSITIVE);

    static JSONObject recognize(String text) {
        var match =
                EQUATION.matcher(
                        text.replaceAll("\\s+", "")
                                .replace("²", "^2")
                                .replace("³", "^3")
                                .replace("−", "-")
                                .replace("×", "*"));
        if (!match.find()) return null;
        try {
            int p = Integer.parseInt(match.group(5));
            int a =
                    match.group(1) == null
                            ? 0
                            : Integer.parseInt(
                                    match.group(1)
                                            + (match.group(2).isEmpty() ? "1" : match.group(2)));
            int b = match.group(3) == null ? 0 : Integer.parseInt(match.group(3) + match.group(4));
            JSONObject result = obj("p", p, "a", a, "b", b);
            validate(result);
            return result;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static final Pattern COORDINATE =
            Pattern.compile("[（(]\\s*(\\d{1,2})\\s*[,，]\\s*(\\d{1,2})\\s*[)）]");

    static void labelMentionedPoints(JSONObject spec, String text) {
        JSONArray selected = spec.optJSONArray("points");
        if (selected != null && selected.length() > 0) return;
        int p = spec.optInt("p", 17),
                a = Math.floorMod(spec.optInt("a", 0), p),
                b = Math.floorMod(spec.optInt("b", 7), p);
        var seen = new java.util.HashSet<Integer>();
        var matches = COORDINATE.matcher(text);
        JSONArray labels = arr();
        while (labels.length() < 8 && matches.find()) {
            int x = Integer.parseInt(matches.group(1)), y = Integer.parseInt(matches.group(2));
            if (x < p && y < p && (y * y) % p == (x * x * x + a * x + b) % p && seen.add(x * p + y))
                labels.put(obj("at", arr(x, y), "label", "(" + x + ", " + y + ")"));
        }
        if (labels.length() > 0) put(spec, "points", labels);
    }

    private static int integer(JSONObject spec, String key, int fallback) {
        double n = spec.optDouble(key, fallback);
        if (!Double.isFinite(n) || n != Math.rint(n) || Math.abs(n) > 10000)
            throw new IllegalArgumentException("계수는 절댓값 10000 이하의 정수로 입력하세요.");
        return (int) n;
    }

    static void validate(JSONObject spec) {
        int p = integer(spec, "p", 17);
        if (p < 5 || p > 97) throw new IllegalArgumentException("표시할 소수 p는 5~97에서 골라 주세요.");
        for (int n = 2; n * n <= p; n++)
            if (p % n == 0) throw new IllegalArgumentException("p에는 소수를 입력하세요.");
        int a = Math.floorMod(integer(spec, "a", 0), p),
                b = Math.floorMod(integer(spec, "b", 7), p);
        if ((4 * a * a * a + 27 * b * b) % p == 0)
            throw new IllegalArgumentException("이 계수에서는 곡선이 특이해요. a 또는 b를 바꿔 주세요.");
    }

    static JSONArray points(JSONObject spec) {
        validate(spec);
        int p = spec.optInt("p", 17),
                a = Math.floorMod(spec.optInt("a", 0), p),
                b = Math.floorMod(spec.optInt("b", 7), p);
        JSONArray points = arr();
        for (int x = 0; x < p; x++)
            for (int y = 0; y < p; y++)
                if (y * y % p == (x * x * x + a * x + b) % p)
                    points.put(obj("at", arr(x, y), "color", "blue"));
        return points;
    }

    static BoardPlot.Made draw(JSONObject spec, float scale, Board.Measure measure) {
        JSONArray dots = points(spec), highlighted = spec.optJSONArray("points");
        int p = spec.optInt("p", 17), a = spec.optInt("a", 0), b = spec.optInt("b", 7);
        for (int i = 0; highlighted != null && i < highlighted.length(); i++) {
            JSONObject mark = highlighted.optJSONObject(i);
            JSONArray at = mark == null ? null : mark.optJSONArray("at");
            if (at == null) continue;
            for (int j = 0; j < dots.length(); j++) {
                JSONObject dot = dots.optJSONObject(j);
                JSONArray xy = dot.optJSONArray("at");
                if (at.optDouble(0) == xy.optInt(0) && at.optDouble(1) == xy.optInt(1)) {
                    put(dot, "color", "red");
                    put(
                            dot,
                            "label",
                            mark.optString(
                                    "label", "(" + xy.optInt(0) + ", " + xy.optInt(1) + ")"));
                    break;
                }
            }
        }
        String equation =
                "y² ≡ x³"
                        + (a == 0
                                ? ""
                                : (a < 0 ? " − " : " + ")
                                        + (Math.abs(a) == 1 ? "" : Math.abs(a))
                                        + "x")
                        + (b == 0 ? "" : (b < 0 ? " − " : " + ") + Math.abs(b))
                        + "  (mod "
                        + p
                        + ")";
        float size = Math.max(24, Board.SMALL * scale);
        JSONObject heading =
                Board.text(Board.MARGIN, 0, equation, size, "ink", true, 900, Board.AI);
        float top = Board.bounds(heading, measure)[3] + 8, height = 535 * BoardPlot.compact(scale);
        JSONObject plot = obj("x", arr(-1, p), "y", arr(-1, p), "points", dots);
        List<JSONObject> items = new ArrayList<>();
        items.add(heading);
        put(plot, "equalUnits", true);
        items.addAll(BoardPlot.graph(plot, top, height, scale));
        JSONObject key =
                Board.text(
                        Board.MARGIN,
                        top + height + 8,
                        "유한체의 좌표 " + dots.length() + "개 · 무한원점은 별도",
                        size,
                        "ink",
                        false,
                        900,
                        Board.AI);
        items.add(key);
        return new BoardPlot.Made(items, Board.bounds(key, measure)[3] + 8);
    }

    /**
     * Old point-only graphs were stored with an error label's height. Restore and reflow only
     * affected widgets.
     */
    public static JSONArray restore(JSONArray items, Board.Measure measure) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject old = items.optJSONObject(i);
            if (old == null
                    || !Board.WIDGET.equals(old.optString("t"))
                    || !BoardWidget.GRAPH.equals(old.optString("kind"))
                    || old.optDouble("h") > 150) continue;
            JSONObject spec = BoardWidget.spec(old), finite = recognize(spec.toString());
            StringBuilder context = new StringBuilder();
            if (!old.optString("ls").isEmpty())
                for (int j = i - 1; j >= Math.max(0, i - 14); j--) {
                    JSONObject before = items.optJSONObject(j);
                    if (before == null || !old.optString("ls").equals(before.optString("ls")))
                        break;
                    if (Board.TEXT.equals(before.optString("t"))) {
                        String text = before.optString("text");
                        context.append(text).append('\n');
                        if (finite == null) finite = recognize(text);
                    }
                }
            if (finite == null && !BoardPlot.hasPoints(spec)) continue;
            if (finite != null && spec.has("points"))
                put(finite, "points", spec.optJSONArray("points"));
            if (finite != null) labelMentionedPoints(finite, context.toString());
            JSONObject made =
                    BoardWidget.make(
                            finite == null ? BoardWidget.GRAPH : BoardWidget.FIELD,
                            finite == null ? spec : finite,
                            (float) old.optDouble("y"),
                            (float) old.optDouble("scale", .85),
                            old.optString("by"),
                            measure);
            JSONArray next = Board.editedWidget(items, old, made, measure);
            if (Board.bottom(next, measure) <= Board.DEEPEST) items = next;
        }
        return items;
    }
}
