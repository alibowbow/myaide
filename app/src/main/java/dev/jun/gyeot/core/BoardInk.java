package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.put;

/** Notebook marks made by either the tutor or the pen shelf. Each is one editable board item. */
public final class BoardInk {
    private BoardInk() {}
    public static final String PEN = "pen", PENCIL = "pencil", BRUSH = "brush", MARKER = "marker";
    public static final String DOUBLE = "double", DOTTED = "dotted", WAVY = "wavy", BRACKET = "bracket", CHECK = "check";

    public static boolean freehand(String tool) {
        return PEN.equals(tool) || PENCIL.equals(tool) || BRUSH.equals(tool) || MARKER.equals(tool);
    }
    public static JSONObject stroke(String tool, String color, float width, float[] points, JSONArray pressure) {
        return stroke(tool, color, width, points, pressure, Board.ME);
    }
    static JSONObject stroke(String tool, String color, float width, float[] points, JSONArray pressure, String by) {
        float actual = switch (tool) {
            case PENCIL -> width * .78f;
            case BRUSH -> width * 1.85f;
            case MARKER -> width * 4.6f;
            default -> width;
        };
        JSONObject item = Board.stroke(color, actual, MARKER.equals(tool), points, by);
        if (!PEN.equals(tool)) put(item, "nib", tool);
        if (BRUSH.equals(tool) && pressure != null) put(item, "pressure", pressure);
        return item;
    }
    static void restore(JSONObject item, JSONObject saved) {
        String nib = saved.optString("nib");
        if (Board.STROKE.equals(item.optString("t")) && freehand(nib) && !PEN.equals(nib)) {
            put(item, "nib", nib);
            JSONArray p = saved.optJSONArray("pressure");
            int points = item.optJSONArray("p").length() / 2;
            if (BRUSH.equals(nib) && p != null && p.length() == points) {
                JSONArray clean = new JSONArray();
                for (int i = 0; i < points; i++) clean.put(Math.max(0, Math.min(100, p.optInt(i, 60))));
                put(item, "pressure", clean);
            }
        }
        String rule = saved.optString("rule");
        if (Board.LINE.equals(item.optString("t")) && (DOUBLE.equals(rule) || DOTTED.equals(rule))) put(item, "rule", rule);
        String mark = saved.optString("mark");
        if (Board.STROKE.equals(item.optString("t")) && (BRACKET.equals(mark) || CHECK.equals(mark))) put(item, "mark", mark);
    }
    public static JSONObject mark(String kind, float x1, float y1, float x2, float y2, String color, float width, String by) {
        if (BRACKET.equals(kind) || CHECK.equals(kind)) {
            float[] pts = BRACKET.equals(kind)
                    ? new float[]{x2,y1, x1,y1, x1,y2, x2,y2}
                    : new float[]{x1,y1+(y2-y1)*.52f, x1+(x2-x1)*.35f,y2, x2,y1};
            JSONObject stroke = Board.stroke(color, width, false, pts, by);
            put(stroke, "sharp", true);
            put(stroke, "mark", kind);
            return stroke;
        }
        JSONObject line = Board.shape(Board.LINE, x1, y1, x2, y2, color, width, "arrow".equals(kind), by);
        if (DOUBLE.equals(kind) || DOTTED.equals(kind)) put(line, "rule", kind);
        if (WAVY.equals(kind)) Board.wavy(line);
        return line;
    }
    /** Offset of the companion rule in page coordinates, also used by hit testing and export bounds. */
    public static float[] doubleOffset(float x1, float y1, float x2, float y2, float width) {
        float length = Math.max(.01f, (float) Math.hypot(x2-x1, y2-y1));
        float gap = Math.max(4, width * 2.25f) / length;
        return new float[]{-(y2-y1)*gap, (x2-x1)*gap};
    }
}
