package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A lesson the AI gives on the board: steps, each with what is said and what is written or drawn while it is said. The
 * model answers with JSON in the form instructions() describes; parse() reads it leniently, and a Flow turns each step
 * into Board items under what is already on the board, so writing never lands on top of other writing.
 */
public final class BoardScript {
    private BoardScript() {}
    public static final int STEPS = 40, DRAWS = 12, SAY = 700, FIGURE_SHAPES = 40;
    /** The gap under a written line and around a figure, in board units. */
    public static final float GAP = 14, FIGURE_GAP = 24;

    public record Step(String say, List<JSONObject> draw) {}
    public record Lesson(String title, List<Step> steps) {}

    /** What the model is told about its board and the form of its answer. */
    public static String instructions(String language) {
        return "You are a teacher at a whiteboard inside a phone app. You teach by writing and drawing on the board step by step while you talk, the way a good tutor solves a problem at the board: "
            + "one idea per step, the reasoning shown line by line, the key result marked. The learner sees the board fill in as each step is spoken aloud.\n\n"
            + "Answer with ONE JSON object and nothing else (no markdown fence, no commentary):\n"
            + "{\"title\": \"short title of the lesson\", \"steps\": [{\"say\": \"what you say aloud for this step\", \"draw\": [ ...operations... ]}]}\n\n"
            + "Operations for \"draw\" (each step has 1 to 6 of them; the board places them top to bottom under what is already there, so you never give positions for writing):\n"
            + "- {\"op\":\"write\",\"text\":\"...\",\"size\":\"title|big|normal|small\",\"color\":\"black|red|blue|green|orange|purple\",\"indent\":0}  one line or short paragraph of writing. "
            + "Keep a line under about 22 characters of Korean or 40 of Latin so it fits a phone; break longer content into several write operations.\n"
            + "- {\"op\":\"figure\",\"height\":400,\"shapes\":[ ... ]}  a drawing in its own box 1000 units wide and `height` units tall (200 to 1200), origin at its top left. Shapes: "
            + "{\"kind\":\"line\",\"from\":[x,y],\"to\":[x,y]}, {\"kind\":\"arrow\",\"from\":[x,y],\"to\":[x,y]}, {\"kind\":\"rect\",\"from\":[x,y],\"to\":[x,y]}, {\"kind\":\"oval\",\"from\":[x,y],\"to\":[x,y]} (the oval fills that box), "
            + "{\"kind\":\"path\",\"points\":[[x,y],[x,y],...]} for a curve or a graph, {\"kind\":\"label\",\"at\":[x,y],\"text\":\"...\",\"size\":\"small\"}. Every shape may have \"color\". "
            + "Use figures for graphs, axes, geometry, diagrams, timelines and tables; label their parts.\n"
            + "- {\"op\":\"mark\",\"text\":\"part of a line you wrote earlier\",\"kind\":\"underline|box|highlight\",\"color\":\"red\"}  emphasizes that earlier line.\n"
            + "- {\"op\":\"space\",\"height\":40}  extra room before the next thing.\n\n"
            + "Rules:\n"
            + "- Write mathematics in plain text with Unicode: x², √(x+1), ½, ×, ÷, ≤, ≠, π, ∫, Σ, →, ∴. No LaTeX, no markdown, no HTML.\n"
            + "- \"say\" is spoken by a voice: natural sentences in " + (language == null || language.isBlank() ? "Korean" : language) + ", no symbols read out awkwardly, no list markers, at most three sentences per step. "
            + "Say what you are doing and why; do not just read the board aloud.\n"
            + "- The board carries the content that should stay (givens, each line of working, the answer); the voice carries the explanation.\n"
            + "- Use color with purpose: black for working, blue for what is given or defined, red for the answer or a warning, green for a check.\n"
            + "- Solve problems completely and correctly: state what is asked, show every step of the working, and end with the answer marked. When a file or picture holds several problems, solve them in order, "
            + "each starting with its number as a \"big\" line.\n"
            + "- For a lesson on a topic or a document, go from the big picture to the details: the outline first, then each part with an example, then a short recap of what to remember.\n"
            + "- Between 3 and " + STEPS + " steps. The board already shows what earlier lessons wrote (given to you as text); continue under it and do not repeat it unless asked.";
    }

    /** The message that goes with the user's request: the request, and the words already on the board. */
    public static String request(String ask, String boardWords, boolean attached) {
        String words = ask == null ? "" : ask.strip();
        StringBuilder text = new StringBuilder(words.isEmpty() ? (attached ? "첨부한 자료를 판서로 풀어서 설명해 줘." : "") : words);
        if (attached && !words.isEmpty()) text.append("\n\n(첨부한 자료를 보고 판서해 주세요.)");
        if (boardWords != null && !boardWords.isBlank()) text.append("\n\n<board_so_far>\n").append(boardWords.strip()).append("\n</board_so_far>");
        return text.toString();
    }

    /** The lesson in a model's answer. Says why when there is none to read. */
    public static Lesson parse(String answer) {
        String text = answer == null ? "" : answer.strip();
        int from = text.indexOf('{'), to = text.lastIndexOf('}');
        if (from < 0 || to <= from) throw new IllegalArgumentException("AI가 판서 내용을 주지 않았어요. 다시 요청해 주세요.");
        JSONObject root;
        try { root = new JSONObject(text.substring(from, to + 1)); } catch (Exception broken) { throw new IllegalArgumentException("AI가 준 판서 내용을 읽지 못했어요. 다시 요청해 주세요."); }
        JSONArray list = root.optJSONArray("steps"); List<Step> steps = new ArrayList<>();
        for (int i = 0; list != null && i < list.length() && steps.size() < STEPS; i++) {
            JSONObject step = list.optJSONObject(i); if (step == null) continue;
            String say = step.optString("say").replaceAll("\\s+", " ").strip(); if (say.length() > SAY) say = say.substring(0, SAY);
            JSONArray draw = step.optJSONArray("draw"); List<JSONObject> ops = new ArrayList<>();
            for (int k = 0; draw != null && k < draw.length() && ops.size() < DRAWS; k++) { JSONObject op = draw.optJSONObject(k); if (op != null && !op.optString("op").isBlank()) ops.add(op); }
            if (!say.isEmpty() || !ops.isEmpty()) steps.add(new Step(say, ops));
        }
        if (steps.isEmpty()) throw new IllegalArgumentException("AI가 준 판서에 내용이 없어요. 다시 요청해 주세요.");
        String title = root.optString("title").replaceAll("\\s+", " ").strip();
        return new Lesson(title.length() > 60 ? title.substring(0, 60) : title, steps);
    }

    public static float size(String name) {
        return switch (name == null ? "" : name.strip().toLowerCase(Locale.ROOT)) { case "title" -> Board.TITLE; case "big", "large" -> Board.BIG; case "small" -> Board.SMALL; default -> Board.NORMAL; };
    }
    private static float number(JSONArray pair, int index) { return pair == null ? 0 : (float) pair.optDouble(index, 0); }

    /** Where the next writing goes, and where this lesson's earlier lines are (for marks). One Flow per lesson. */
    public static final class Flow {
        private float y; private final Board.Measure measure;
        private final List<JSONObject> written = new ArrayList<>();
        /** Starts under everything on the board (a little lower when the board is not empty). */
        public Flow(JSONArray items, Board.Measure measure) {
            this.measure = measure == null ? Board.ROUGH : measure;
            float bottom = Board.bottom(items, this.measure);
            y = bottom <= 0 ? Board.MARGIN : bottom + 3 * GAP;
        }
        public float y() { return y; }

        /** The Board items of one step, in the order they are drawn. Operations the board does not know are left out. */
        public List<JSONObject> place(Step step) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject op : step.draw()) {
                if (y > Board.DEEPEST - 2000) break;
                switch (op.optString("op").strip().toLowerCase(Locale.ROOT)) {
                    case "write", "text" -> {
                        String text = op.optString("text").strip(); if (text.isEmpty()) continue;
                        float size = size(op.optString("size")); int indent = Math.max(0, Math.min(4, op.optInt("indent", 0)));
                        float left = Board.MARGIN + indent * Board.INDENT, room = Board.WIDTH - Board.MARGIN - left;
                        JSONObject item = Board.text(left, y, text, size, op.optString("color"), "title".equalsIgnoreCase(op.optString("size").strip()), room, Board.AI);
                        y = Board.bounds(item, measure)[3] + GAP; written.add(item); out.add(item);
                    }
                    case "figure", "draw" -> {
                        float height = Math.max(120, Math.min(1200, (float) op.optDouble("height", 400))), top = y + FIGURE_GAP; JSONArray shapes = op.optJSONArray("shapes");
                        for (int i = 0; shapes != null && i < shapes.length() && i < FIGURE_SHAPES; i++) { JSONObject item = shape(shapes.optJSONObject(i), top, height); if (item != null) out.add(item); }
                        y = top + height + FIGURE_GAP;
                    }
                    case "mark" -> { JSONObject item = mark(op); if (item != null) out.add(item); }
                    case "space" -> y += Math.max(0, Math.min(400, (float) op.optDouble("height", 40)));
                    default -> { }
                }
            }
            return out;
        }
        private JSONObject shape(JSONObject shape, float top, float height) {
            if (shape == null) return null;
            String kind = shape.optString("kind").strip().toLowerCase(Locale.ROOT), color = shape.optString("color");
            JSONArray from = shape.optJSONArray("from"), to = shape.optJSONArray("to");
            float x1 = inside(number(from, 0), Board.WIDTH), y1 = top + inside(number(from, 1), height), x2 = inside(number(to, 0), Board.WIDTH), y2 = top + inside(number(to, 1), height);
            switch (kind) {
                case "line", "arrow" -> { return from == null || to == null ? null : Board.shape(Board.LINE, x1, y1, x2, y2, color, 5, kind.equals("arrow"), Board.AI); }
                case "rect", "box" -> { return from == null || to == null ? null : Board.shape(Board.RECT, x1, y1, x2, y2, color, 5, false, Board.AI); }
                case "oval", "circle", "ellipse" -> { return from == null || to == null ? null : Board.shape(Board.OVAL, x1, y1, x2, y2, color, 5, false, Board.AI); }
                case "path", "curve" -> {
                    JSONArray points = shape.optJSONArray("points"); if (points == null || points.length() < 2) return null;
                    int count = Math.min(points.length(), 600); float[] flat = new float[count * 2];
                    for (int i = 0; i < count; i++) { JSONArray point = points.optJSONArray(i); flat[2 * i] = inside(number(point, 0), Board.WIDTH); flat[2 * i + 1] = top + inside(number(point, 1), height); }
                    return Board.stroke(color, 5, false, flat, Board.AI);
                }
                case "label", "text" -> {
                    String text = shape.optString("text").strip(); JSONArray at = shape.optJSONArray("at"); if (text.isEmpty() || at == null) return null;
                    float left = inside(number(at, 0), Board.WIDTH), size = shape.optString("size").isBlank() ? Board.SMALL : size(shape.optString("size"));
                    return Board.text(left, top + inside(number(at, 1), height), text, size, color, false, Math.max(120, Board.WIDTH - left), Board.AI);
                }
                default -> { return null; }
            }
        }
        private static float inside(float value, float most) { return Float.isNaN(value) ? 0 : Math.max(0, Math.min(most, value)); }
        /** The most recent line of this lesson that holds the words, underlined, boxed or highlighted. */
        private JSONObject mark(JSONObject op) {
            String words = op.optString("text").strip(), kind = op.optString("kind").strip().toLowerCase(Locale.ROOT); if (words.isEmpty()) return null;
            for (int i = written.size() - 1; i >= 0; i--) {
                JSONObject line = written.get(i); if (!line.optString("text").contains(words)) continue;
                float[] b = Board.bounds(line, measure); String color = op.optString("color").isBlank() ? "red" : op.optString("color");
                if (kind.equals("box")) return Board.shape(Board.RECT, b[0] - 10, b[1] - 4, b[2] + 10, b[3] + 2, color, 4, false, Board.AI);
                if (kind.equals("highlight")) return Board.stroke(op.optString("color").isBlank() ? "orange" : color, Math.min(Board.THICK, (b[3] - b[1]) * .8f), true, new float[]{b[0], (b[1] + b[3]) / 2, b[2], (b[1] + b[3]) / 2}, Board.AI);
                return Board.shape(Board.LINE, b[0], b[3], b[2], b[3], color, 4, false, Board.AI);
            }
            return null;
        }
    }

    /** How long a step's drawing takes to appear, in milliseconds: writing at a hand's pace, shapes a little each. */
    public static long drawMillis(List<JSONObject> items) {
        long total = 0;
        for (JSONObject item : items) total += itemMillis(item);
        return Math.max(300, Math.min(20_000, total));
    }
    public static long itemMillis(JSONObject item) {
        if (Board.TEXT.equals(item.optString("t"))) return Math.max(250, Math.min(6_000, item.optString("text").length() * 55L));
        if (Board.STROKE.equals(item.optString("t"))) { JSONArray p = item.optJSONArray("p"); return Math.max(350, Math.min(2_500, (p == null ? 0 : p.length() / 2) * 25L)); }
        return 450;
    }
    /** How long a step is held when no voice reads it: time to read what was said. */
    public static long readMillis(String say) { return say == null || say.isBlank() ? 400 : Math.max(1_200, Math.min(12_000, say.length() * 75L)); }
}
