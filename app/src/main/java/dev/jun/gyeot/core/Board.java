package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 판서: what is on one board. The board is WIDTH units wide on every screen and grows downward; everything on it is an
 * item in the order it was put there: a freehand stroke, a piece of text, a line (with or without an arrowhead), a
 * rectangle, an oval, or a picture (a photo of a problem, a page of a document) that lies under everything else so it
 * can be written on. Colors are names (the screen turns "ink" into black or white with the theme). An item the AI
 * wrote in a lesson carries that lesson's id ("ls") and its step ("st"), so the lesson can be given again.
 */
public final class Board {
    private Board() {}
    public static final int WIDTH = 1000;
    /** The page's side margin, and how far one step of indent moves text. */
    public static final int MARGIN = 48, INDENT = 48;
    public static final String STROKE = "stroke", TEXT = "text", LINE = "line", RECT = "rect", OVAL = "oval", IMAGE = "image";
    /** A picture's file name inside the boards folder: the board's id, a number, and its kind. */
    public static final java.util.regex.Pattern IMAGE_NAME = java.util.regex.Pattern.compile("[a-z0-9]{6,16}-[0-9]{1,4}\\.(jpg|png)");
    /** Who put an item on the board. */
    public static final String ME = "me", AI = "ai";
    public static final String INK = "ink";
    public static final String[] COLORS = {INK, "red", "blue", "green", "orange", "purple"};
    /** Text sizes in board units: a phone shows WIDTH units across, so NORMAL reads like body text. */
    public static final float TITLE = 68, BIG = 56, NORMAL = 46, SMALL = 36;
    /** The thickest and thinnest line, the longest text of one item, the most points of one stroke and the most items. */
    public static final float THIN = 2, THICK = 60;
    public static final int TEXT_LIMIT = 2000, POINTS = 4000, ITEMS = 6000;
    /** The board never grows past this. */
    public static final float DEEPEST = 200_000;

    /** How wide and tall a text is when drawn: the screen's own measure, so core can lay text out and find it again. */
    public interface Measure {
        /** {width, height} of text at size (bold or not), wrapped to maxWidth. */
        float[] size(String text, float size, boolean bold, float maxWidth);
        /** As above for text in the handwriting letters (hand) or the print ones; a measure with one kind of letters has one answer. */
        default float[] size(String text, float size, boolean bold, float maxWidth, boolean hand) { return size(text, size, bold, maxWidth); }
    }
    /** A measure for places without a screen: every letter is 0.6 of the size wide (0.95 for wide scripts), lines 1.4 apart. */
    public static final Measure ROUGH = (text, size, bold, maxWidth) -> {
        float widest = 0; int lines = 0;
        for (String line : (text == null ? "" : text).split("\n", -1)) {
            float width = 0;
            for (int i = 0; i < line.length(); i++) width += line.charAt(i) > 0x2E7F ? size * .95f : size * .6f;
            int wraps = maxWidth > 0 ? Math.max(1, (int) Math.ceil(width / maxWidth)) : 1;
            lines += wraps; widest = Math.max(widest, wraps > 1 ? maxWidth : width);
        }
        return new float[]{widest, Math.max(1, lines) * size * 1.4f};
    };

    public static String color(String name) {
        String clean = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (clean.equals("black") || clean.equals("white") || clean.equals("default")) return INK;
        for (String color : COLORS) if (color.equals(clean)) return color;
        return INK;
    }
    private static float clamp(double value, float least, float most) { return Double.isNaN(value) || Double.isInfinite(value) ? least : (float) Math.max(least, Math.min(most, value)); }
    private static float x(double value) { return clamp(value, -WIDTH, 2 * WIDTH); }
    private static float y(double value) { return clamp(value, -WIDTH, DEEPEST); }
    private static String who(String by) { return AI.equals(by) ? AI : ME; }

    /** A picture file shown at this place and size. */
    public static JSONObject image(String name, float left, float top, float width, float height, String by) {
        if (name == null || !IMAGE_NAME.matcher(name).matches()) throw new IllegalArgumentException("그림 파일 이름이 올바르지 않아요.");
        return obj("t", IMAGE, "id", name, "x", round(x(left)), "y", round(y(top)), "w", round(clamp(width, 20, 2 * WIDTH)), "h", round(clamp(height, 20, 20_000)), "by", who(by));
    }
    /** Copies what marks an item as part of a lesson, and its dashes, from a stored item. */
    private static JSONObject tagged(JSONObject made, JSONObject from) {
        String lesson = from.optString("ls");
        if (lesson.matches("[a-z0-9]{1,12}")) { Jsons.put(made, "ls", lesson); Jsons.put(made, "st", Math.max(0, Math.min(999, from.optInt("st")))); }
        if (from.optBoolean("d") && !TEXT.equals(made.optString("t")) && !IMAGE.equals(made.optString("t"))) Jsons.put(made, "d", true);
        if (from.optBoolean("sharp") && STROKE.equals(made.optString("t"))) Jsons.put(made, "sharp", true);
        if (from.optBoolean("hand") && TEXT.equals(made.optString("t"))) Jsons.put(made, "hand", true);
        return made;
    }
    /** Marks an item as written in a lesson's step. */
    public static JSONObject inLesson(JSONObject item, String lesson, int step) { Jsons.put(item, "ls", lesson); Jsons.put(item, "st", step); return item; }
    public static JSONObject dashed(JSONObject item) { if (item != null) Jsons.put(item, "d", true); return item; }
    /** Marks a text as written in the handwriting letters. */
    public static JSONObject handwritten(JSONObject item, boolean hand) { if (item != null && hand && TEXT.equals(item.optString("t"))) Jsons.put(item, "hand", true); return item; }

    public static JSONObject stroke(String color, float width, boolean highlight, float[] points, String by) {
        JSONArray flat = arr(); int count = points == null ? 0 : Math.min(points.length / 2, POINTS);
        for (int i = 0; i < count; i++) { put(flat, round(x(points[2 * i]))); put(flat, round(y(points[2 * i + 1]))); }
        return obj("t", STROKE, "c", color(color), "w", round(clamp(width, THIN, THICK)), "hl", highlight, "p", flat, "by", who(by));
    }
    public static JSONObject text(float left, float top, String text, float size, String color, boolean bold, float maxWidth, String by) {
        String clean = text == null ? "" : text.replace("\r", "").strip();
        if (clean.length() > TEXT_LIMIT) clean = clean.substring(0, TEXT_LIMIT);
        return obj("t", TEXT, "x", round(x(left)), "y", round(y(top)), "text", clean, "s", round(clamp(size, 16, 160)), "c", color(color), "b", bold,
            "mw", round(clamp(maxWidth, 80, WIDTH)), "by", who(by));
    }
    /** type is LINE, RECT or OVAL; arrow gives a LINE an arrowhead at its second point. */
    public static JSONObject shape(String type, float x1, float y1, float x2, float y2, String color, float width, boolean arrow, String by) {
        String kind = Set.of(LINE, RECT, OVAL).contains(type) ? type : LINE;
        return obj("t", kind, "x1", round(x(x1)), "y1", round(y(y1)), "x2", round(x(x2)), "y2", round(y(y2)), "c", color(color), "w", round(clamp(width, THIN, THICK)),
            "arrow", LINE.equals(kind) && arrow, "by", who(by));
    }
    private static double round(float value) { return Math.round(value * 10) / 10.0; }
    private static void put(JSONArray list, double value) { try { list.put(value); } catch (Exception impossible) { /* finite by clamp */ } }

    /** A stored list as items this class made: anything else is left out, numbers are brought into range. */
    public static JSONArray clean(JSONArray stored) {
        JSONArray out = arr();
        for (int i = 0; stored != null && i < stored.length() && out.length() < ITEMS; i++) {
            JSONObject item = stored.optJSONObject(i); if (item == null) continue;
            String type = item.optString("t"), by = item.optString("by");
            if (STROKE.equals(type)) {
                JSONArray p = item.optJSONArray("p"); if (p == null || p.length() < 2) continue;
                float[] points = new float[Math.min(p.length() / 2, POINTS) * 2];
                for (int k = 0; k < points.length; k++) points[k] = (float) p.optDouble(k, 0);
                out.put(tagged(stroke(item.optString("c"), (float) item.optDouble("w", 6), item.optBoolean("hl"), points, by), item));
            } else if (TEXT.equals(type)) {
                if (item.optString("text").isBlank()) continue;
                out.put(tagged(text((float) item.optDouble("x", MARGIN), (float) item.optDouble("y", 0), item.optString("text"), (float) item.optDouble("s", NORMAL), item.optString("c"), item.optBoolean("b"),
                    (float) item.optDouble("mw", WIDTH - 2 * MARGIN), by), item));
            } else if (Set.of(LINE, RECT, OVAL).contains(type)) {
                out.put(tagged(shape(type, (float) item.optDouble("x1", 0), (float) item.optDouble("y1", 0), (float) item.optDouble("x2", 0), (float) item.optDouble("y2", 0), item.optString("c"),
                    (float) item.optDouble("w", 6), item.optBoolean("arrow"), by), item));
            } else if (IMAGE.equals(type)) {
                if (!IMAGE_NAME.matcher(item.optString("id")).matches()) continue;
                out.put(tagged(image(item.optString("id"), (float) item.optDouble("x", MARGIN), (float) item.optDouble("y", 0), (float) item.optDouble("w", WIDTH - 2 * MARGIN), (float) item.optDouble("h", 400), by), item));
            }
        }
        return out;
    }

    /** {left, top, right, bottom} of one item. */
    public static float[] bounds(JSONObject item, Measure measure) {
        String type = item.optString("t");
        if (IMAGE.equals(type)) { float left = (float) item.optDouble("x"), top = (float) item.optDouble("y"); return new float[]{left, top, left + (float) item.optDouble("w"), top + (float) item.optDouble("h")}; }
        if (TEXT.equals(type)) {
            float left = (float) item.optDouble("x"), top = (float) item.optDouble("y");
            float[] size = (measure == null ? ROUGH : measure).size(item.optString("text"), (float) item.optDouble("s", NORMAL), item.optBoolean("b"), (float) item.optDouble("mw", WIDTH), item.optBoolean("hand"));
            return new float[]{left, top, left + size[0], top + size[1]};
        }
        if (STROKE.equals(type)) {
            JSONArray p = item.optJSONArray("p"); float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
            for (int i = 0; p != null && i + 1 < p.length(); i += 2) { float px = (float) p.optDouble(i), py = (float) p.optDouble(i + 1); l = Math.min(l, px); r = Math.max(r, px); t = Math.min(t, py); b = Math.max(b, py); }
            float half = (float) item.optDouble("w", 6) / 2;
            return l > r ? new float[]{0, 0, 0, 0} : new float[]{l - half, t - half, r + half, b + half};
        }
        float x1 = (float) item.optDouble("x1"), y1 = (float) item.optDouble("y1"), x2 = (float) item.optDouble("x2"), y2 = (float) item.optDouble("y2"), half = (float) item.optDouble("w", 6) / 2;
        return new float[]{Math.min(x1, x2) - half, Math.min(y1, y2) - half, Math.max(x1, x2) + half, Math.max(y1, y2) + half};
    }
    /** The lowest point anything reaches; 0 for an empty board. */
    public static float bottom(JSONArray items, Measure measure) {
        float lowest = 0;
        for (int i = 0; items != null && i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item != null) lowest = Math.max(lowest, bounds(item, measure)[3]); }
        return lowest;
    }

    private static float distance(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
        float t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / length));
        float cx = ax + t * dx - px, cy = ay + t * dy - py;
        return (float) Math.sqrt(cx * cx + cy * cy);
    }
    /** How far a point is from an item's ink: 0 inside a text's box, the distance to the nearest part of a line otherwise. */
    public static float distance(JSONObject item, float px, float py, Measure measure) {
        String type = item.optString("t");
        if (TEXT.equals(type) || IMAGE.equals(type)) {
            float[] b = bounds(item, measure);
            float dx = Math.max(0, Math.max(b[0] - px, px - b[2])), dy = Math.max(0, Math.max(b[1] - py, py - b[3]));
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
        if (STROKE.equals(type)) {
            JSONArray p = item.optJSONArray("p"); float nearest = Float.MAX_VALUE;
            if (p == null || p.length() < 2) return nearest;
            if (p.length() < 4) return distance(px, py, (float) p.optDouble(0), (float) p.optDouble(1), (float) p.optDouble(0), (float) p.optDouble(1));
            for (int i = 0; i + 3 < p.length(); i += 2) nearest = Math.min(nearest, distance(px, py, (float) p.optDouble(i), (float) p.optDouble(i + 1), (float) p.optDouble(i + 2), (float) p.optDouble(i + 3)));
            return nearest;
        }
        float x1 = (float) item.optDouble("x1"), y1 = (float) item.optDouble("y1"), x2 = (float) item.optDouble("x2"), y2 = (float) item.optDouble("y2");
        if (LINE.equals(type)) return distance(px, py, x1, y1, x2, y2);
        if (RECT.equals(type)) return Math.min(Math.min(distance(px, py, x1, y1, x2, y1), distance(px, py, x2, y1, x2, y2)), Math.min(distance(px, py, x2, y2, x1, y2), distance(px, py, x1, y2, x1, y1)));
        float cx = (x1 + x2) / 2, cy = (y1 + y2) / 2, rx = Math.max(1, Math.abs(x2 - x1) / 2), ry = Math.max(1, Math.abs(y2 - y1) / 2);
        // An oval's outline: how far the point is from the rim along its own direction from the centre.
        float nx = (px - cx) / rx, ny = (py - cy) / ry, length = (float) Math.sqrt(nx * nx + ny * ny);
        return Math.abs(length - 1) * Math.min(rx, ry);
    }
    /** The topmost item whose ink is within reach of the point, or -1. Pictures are left out: ink on them is found first. */
    public static int hit(JSONArray items, float px, float py, float reach, Measure measure) { return hit(items, px, py, reach, measure, false); }
    /** As above; with pictures, a picture is found when nothing written is. */
    public static int hit(JSONArray items, float px, float py, float reach, Measure measure, boolean pictures) {
        int ink = hitKind(items, px, py, reach, measure, false);
        return ink >= 0 || !pictures ? ink : hitKind(items, px, py, 0, measure, true);
    }
    private static int hitKind(JSONArray items, float px, float py, float reach, Measure measure, boolean pictures) {
        for (int i = items == null ? -1 : items.length() - 1; i >= 0; i--) {
            JSONObject item = items.optJSONObject(i); if (item == null || IMAGE.equals(item.optString("t")) != pictures) continue;
            float slack = reach + (float) item.optDouble("w", 0) / 2;
            float[] b = bounds(item, measure);
            if (px < b[0] - slack || px > b[2] + slack || py < b[1] - slack || py > b[3] + slack) continue;
            if (distance(item, px, py, measure) <= slack) return i;
        }
        return -1;
    }

    /** The board's words from top to bottom, for a model that cannot see it; at most limit characters, the newest kept. */
    public static String words(JSONArray items, int limit) {
        java.util.List<JSONObject> texts = new java.util.ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item != null && TEXT.equals(item.optString("t")) && !item.optString("text").isBlank()) texts.add(item); }
        texts.sort((a, b) -> { int byRow = Double.compare(a.optDouble("y"), b.optDouble("y")); return byRow != 0 ? byRow : Double.compare(a.optDouble("x"), b.optDouble("x")); });
        StringBuilder out = new StringBuilder();
        for (JSONObject text : texts) out.append(out.length() == 0 ? "" : "\n").append(text.optString("text"));
        return out.length() <= limit ? out.toString() : "…" + out.substring(out.length() - Math.max(0, limit - 1));
    }
    /** "자료 1 · 글 12 · 그림 30" for a list of boards; "빈 판" when there is nothing. */
    public static String summary(JSONArray items) {
        int texts = 0, drawn = 0, pictures = 0;
        for (int i = 0; items != null && i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item == null) continue; if (TEXT.equals(item.optString("t"))) texts++; else if (IMAGE.equals(item.optString("t"))) pictures++; else drawn++; }
        if (texts == 0 && drawn == 0 && pictures == 0) return "빈 판";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (pictures > 0) parts.add("자료 " + pictures); if (texts > 0) parts.add("글 " + texts); if (drawn > 0) parts.add("그림 " + drawn);
        return String.join(" · ", parts);
    }
    /** The pictures on the board from top to bottom: what a model is shown as picture 1, 2, … */
    public static java.util.List<JSONObject> pictures(JSONArray items) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item != null && IMAGE.equals(item.optString("t"))) out.add(item); }
        out.sort((a, b) -> Double.compare(a.optDouble("y"), b.optDouble("y")));
        return out;
    }
}
