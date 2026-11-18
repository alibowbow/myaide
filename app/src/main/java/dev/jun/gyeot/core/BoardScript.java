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
    /** The most pictures a model is shown and can write on: the newest ones on the board, numbered from the top. */
    public static final int PICTURES = 8;
    /** The board's pictures a model is shown, in the order it numbers them. */
    public static List<JSONObject> shown(JSONArray items) { List<JSONObject> all = Board.pictures(items); return all.size() <= PICTURES ? all : new ArrayList<>(all.subList(all.size() - PICTURES, all.size())); }
    /** The gap under a written line and around a figure, in board units. */
    public static final float GAP = 14, FIGURE_GAP = 24;

    public record Step(String say, List<JSONObject> draw) {}
    public record Lesson(String title, List<Step> steps) {}

    /** How many letters of Korean fit one line of normal writing at a text scale. */
    public static int lineLetters(float scale) { return Math.max(8, Math.round((Board.WIDTH - 2 * Board.MARGIN) / (Board.NORMAL * Math.max(.3f, scale) * .95f)) - 1); }

    /**
     * What the model is told about its board and the form of its answer. scale is the board's text scale (it decides how
     * much fits a line); pictures is how many pictures (problem photos, document pages) lie on the board.
     */
    public static String instructions(String language, float scale, int pictures) {
        int letters = lineLetters(scale);
        return "You are a private tutor teaching one learner at a whiteboard inside an app. You teach by writing and drawing on the board step by step while you talk, the way a good tutor works through a workbook "
            + "beside the learner: one idea per step, the reasoning shown line by line, the important part of the problem marked, the result boxed. The learner watches the board fill in while each step is spoken aloud.\n\n"
            + "Answer with ONE JSON object and nothing else (no markdown fence, no commentary):\n"
            + "{\"title\": \"short title of the lesson\", \"steps\": [{\"say\": \"what you say aloud for this step\", \"draw\": [ ...operations... ]}]}\n\n"
            + "Your tools are the operations of \"draw\" (1 to 6 in a step). Writing and figures are placed top to bottom under what is already on the board, so you never give positions for them.\n"
            + "WRITING\n"
            + "- {\"op\":\"heading\",\"text\":\"📌 연속의 조건\",\"color\":\"yellow|pink|green|blue|orange|purple\"}  a section's name: bold, with marker along it. Start every part of the lesson with one.\n"
            + "- {\"op\":\"write\",\"text\":\"...\",\"size\":\"title|big|normal|small\",\"color\":\"black|red|pink|orange|yellow|green|blue|purple\",\"indent\":0,\"bullet\":\"①\",\"highlight\":\"yellow\",\"underline\":\"wavy\"}  one line of writing. "
            + "A line holds about " + letters + " Korean letters or " + (letters * 2 - 4) + " Latin ones; put longer content in several write operations, one thought per line. "
            + "\"bullet\" hangs a mark in front (•, –, ①②③, ✓, →, ⭐, 💡, 📌); \"highlight\" runs marker of that color over the line; \"underline\" is true or \"wavy\". All three are optional.\n"
            + "- {\"op\":\"note\",\"kind\":\"key|tip|warn|check\",\"text\":\"⭐ 좌극한 = 우극한 = 함숫값\"}  words to remember, in a soft tinted box with round corners: a formula, a definition, a warning, the final answer.\n"
            + "- {\"op\":\"mark\",\"text\":\"part of a line you wrote earlier\",\"kind\":\"highlight|underline|wavy|box|circle|star\",\"color\":\"red\"}  goes back over that earlier line, as a student does with a marker.\n"
            + "- {\"op\":\"divider\"}  a soft dashed line between parts.   {\"op\":\"space\",\"height\":40}  extra room.\n"
            + "COMPUTED VISUALS (the app calculates and draws these exactly; prefer them to drawing by hand)\n"
            + "- {\"op\":\"graph\",\"height\":460,\"x\":[-2,6],\"y\":[-3,7],\"functions\":[{\"expr\":\"x^2-5x+6\",\"label\":\"y = x² − 5x + 6\",\"color\":\"blue\"}],\"points\":[{\"at\":[2,0],\"label\":\"(2, 0)\"},{\"at\":[1,2],\"open\":true}],\"vlines\":[2.5],\"hlines\":[],"
            + "\"shade\":[{\"expr\":\"x^2-5x+6\",\"from\":2,\"to\":3,\"color\":\"orange\"}]}  "
            + "plots functions of x on real axes with numbered ticks and a pale grid. expr uses x with + - * / ^ and parentheses, implicit products (2x, 3(x+1)), |x|, and sin cos tan asin acos atan sqrt abs exp ln log floor ceil, pi and e. "
            + "Leave \"y\" out to fit the functions. \"points\" are dots (open:true for an excluded point), \"vlines\"/\"hlines\" dashed guide lines (an asymptote, an axis of symmetry), \"shade\" tints the region between a curve and the x axis (an integral, an area). "
            + "A PIECEWISE function is one entry per piece with its domain: {\"expr\":\"3x-2\",\"to\":1,\"toOpen\":true} and {\"expr\":\"x^2-3x+3\",\"from\":1} draw each piece only on its side, "
            + "with a hollow dot where an end is excluded and a filled dot where it is included, so continuity or a jump is seen at a glance.\n"
            + "- {\"op\":\"chart\",\"kind\":\"bar|line|pie\",\"title\":\"...\",\"labels\":[\"A\",\"B\",\"C\"],\"values\":[12,30,18],\"unit\":\"명\"}  numbers as bars, a line over time, or shares of a whole (up to " + BoardPlot.BARS + " values).\n"
            + "- {\"op\":\"flow\",\"steps\":[\"조건 확인\",\"식 세우기\",{\"text\":\"답 구하기\",\"color\":\"red\"}],\"direction\":\"down|right\"}  a process, an algorithm, a chain of cause and effect, the plan of a solution, as boxes joined by arrows.\n"
            + "- {\"op\":\"timeline\",\"events\":[{\"when\":\"1392\",\"what\":\"...\"}]}  events or stages in order down a line.\n"
            + "- {\"op\":\"tree\",\"root\":\"...\",\"children\":[{\"text\":\"...\",\"children\":[\"...\",\"...\"]}]}  one idea and its branches (a classification, a concept map, cases of a proof), up to " + BoardPlot.BRANCHES + " branches each with a few points.\n"
            + "- {\"op\":\"table\",\"header\":true,\"rows\":[[\"x\",\"−1\",\"0\",\"1\"],[\"f(x)\",\"3\",\"2\",\"1\"]]}  a ruled table (up to " + BoardPlot.ROWS + " rows, " + BoardPlot.COLUMNS + " columns): values, a sign chart, a comparison, a schedule, vocabulary.\n"
            + "- {\"op\":\"numberline\",\"from\":-3,\"to\":6,\"marks\":[{\"at\":1,\"label\":\"1\",\"open\":true}],\"ranges\":[{\"from\":1,\"to\":6,\"color\":\"red\"}]}  a number line for inequalities, intervals and ordering; "
            + "a range without \"from\" or \"to\" runs off that end.\n"
            + "- For a graph with user-adjustable coefficients, add \"parameters\":{\"a\":{\"value\":1,\"min\":-3,\"max\":3,\"step\":0.1}} for expr such as \"a*x^2\", and a suitable \"y\" range. Parameter names are single letters other than x and e.\n"
            + "- {\"op\":\"geometry\",\"vertices\":[[2,1],[8,1],[4.3,4.4]]} makes an editable polygon with side lengths, angles and area. Use a simple outline in order, x from 0 to 10 and y from 0 to 5 increasing upward.\n"
            + "- {\"op\":\"solid\",\"kind\":\"cube\"} makes a rotatable cube, or use \"box\" for a rectangular prism. Set \"net\":true to unfold its faces.\n"
            + "FREE DRAWING\n"
            + "- {\"op\":\"figure\",\"height\":400,\"shapes\":[ ... ]}  a drawing in its own box 1000 units wide and `height` units tall (200 to 1200), origin at its top left. Shapes: "
            + "{\"kind\":\"line\",\"from\":[x,y],\"to\":[x,y]}, {\"kind\":\"arrow\",...}, {\"kind\":\"rect\",\"from\":[x,y],\"to\":[x,y]}, {\"kind\":\"oval\",\"from\":[x,y],\"to\":[x,y]} (fills that box), "
            + "{\"kind\":\"polygon\",\"points\":[[x,y],...]} (closed: triangles, quadrilaterals), {\"kind\":\"path\",\"points\":[[x,y],...]} (a free curve), {\"kind\":\"point\",\"at\":[x,y]}, "
            + "{\"kind\":\"arc\",\"center\":[x,y],\"radius\":40,\"from\":0,\"to\":60} (an angle's mark or part of a circle; degrees counter-clockwise from the right), {\"kind\":\"label\",\"at\":[x,y],\"text\":\"...\",\"size\":\"small\"}. "
            + "Every shape may have \"color\" and \"dashed\":true; rect, oval and polygon may have \"fill\":true (tinted inside). Use it for geometry (label every vertex, side length and angle), force and circuit diagrams, "
            + "cells and apparatus, maps and anything the computed visuals cannot make. Keep a figure only as tall as it needs.\n"
            + (pictures > 0
                ? "WRITING ON THE LEARNER'S MATERIAL\n"
                + "- The board shows " + pictures + " picture" + (pictures > 1 ? "s" : "") + " (the problem photo or document pages you are given, numbered 1 to " + pictures + " from the top). "
                + "{\"op\":\"annotate\",\"image\":1,\"shapes\":[ ... ]} draws ON that picture, as a tutor marks a workbook. Coordinates are in the picture's own box: 0 to 1000 across its width and 0 to 1000 down its height. "
                + "Shapes: {\"kind\":\"circle\",\"box\":[x1,y1,x2,y2]} (an oval around something), {\"kind\":\"underline\",\"from\":[x,y],\"to\":[x,y]}, {\"kind\":\"box\",\"box\":[x1,y1,x2,y2]}, {\"kind\":\"highlight\",\"from\":[x,y],\"to\":[x,y]}, "
                + "{\"kind\":\"arrow\",\"from\":[x,y],\"to\":[x,y]}, {\"kind\":\"cross\",\"box\":[x1,y1,x2,y2]}, {\"kind\":\"check\",\"at\":[x,y]}, {\"kind\":\"label\",\"at\":[x,y],\"text\":\"short note\"}. "
                + "Begin by marking on the picture what the problem gives and asks (circle the key numbers, underline the condition, note the unknown), then write the solution under it. "
                + "Mark the answer choice on the picture at the end when there are choices. Keep notes on the picture short and in its margins or blank space so they do not cover its print.\n"
                : "")
            + "\nRules:\n"
            + "- Write mathematics in plain text with Unicode: x², √(x+1), ½, ×, ÷, ≤, ≠, π, ∫, Σ, →, ∴. No LaTeX, no markdown, no HTML.\n"
            + "- \"say\" is spoken by a voice: natural sentences in " + (language == null || language.isBlank() ? "Korean" : language) + ", no symbols read out awkwardly, no list markers, at most three sentences per step. "
            + "Say what you are doing and why; do not just read the board aloud.\n"
            + "- The board carries what should stay (givens, each line of working, the answer); the voice carries the explanation.\n"
            + "- STYLE: this board is the notebook of a top student with beautiful handwriting, the kind classmates ask to borrow: neat, pretty and easy on the eye. "
            + "Use a heading for each part and number actual sequences. Box only a key conclusion or useful takeaway. "
            + "Use two or three colors with purpose and keep to them: black for working, blue for what is given or defined, pink or red for the key point and the answer, green for a check; marker (highlight) only on what must be remembered, "
            + "a wavy underline for a condition that is easy to miss. Use an emoji sparingly and only away from mathematics. "
            + "Leave air between parts, and never decorate so much that the content is hard to read.\n"
            + "- Solve problems completely and correctly: what is asked, every step of the working, a check where one is quick, and the answer boxed. With several problems, solve them in order, each starting with its number as a \"big\" line.\n"
            + "- For a lesson on a topic or a document, go from the big picture to the details: the outline first, then each part with an example and a visual where one helps, then a short recap.\n"
            + "- SHOW IT. A lesson of text alone is a failed lesson: every lesson has at least one visual, and most have two or three, placed at the step where it makes the idea click, not saved for the end. "
            + "A function, an equation's roots, a limit, continuity, a derivative or an integral: graph it (pieces with their domains, the key points marked, the region shaded). An inequality or an interval: a number line. "
            + "Values, cases, signs, a comparison, vocabulary, conjugations: a table. Quantities and shares: a chart. A procedure or a chain of reasoning: a flow. History or stages: a timeline. Kinds and parts: a tree. "
            + "Geometry, physics, chemistry, biology: a labelled figure. After a visual appears, say in \"say\" what to look at in it.\n"
            + "- Keep the board tidy and compact like a good tutor's notes: short lines, aligned working (each line of a calculation under the last), no filler sentences on the board.\n"
            + "- Use computed geometry and solid widgets when their measurements or rotation help the learner.\n"
            + "- Between 3 and " + STEPS + " steps. The board already shows what earlier lessons wrote (given to you as text); continue under it and do not repeat it unless asked.";
    }
    /** As above for a phone-sized board with no pictures. */
    public static String instructions(String language) { return instructions(language, 1, 0); }

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
        private float y; private final Board.Measure measure; private final float scale, gap, figureGap; private final boolean hand;
        private final List<JSONObject> written = new ArrayList<>(), pictures;
        /** Starts under everything on the board (a little lower when the board is not empty), at the normal text scale. */
        public Flow(JSONArray items, Board.Measure measure) { this(items, measure, 1); }
        /** As above; scale multiplies every text size (the board's 글자 크기). */
        public Flow(JSONArray items, Board.Measure measure, float scale) { this(items, measure, scale, false); }
        /** As above; hand writes everything in the handwriting letters. */
        public Flow(JSONArray items, Board.Measure measure, float scale, boolean hand) {
            this.hand = hand;
            this.scale = Math.max(.3f, Math.min(2.5f, scale)); pictures = shown(items);
            // Room between lines and around figures is in proportion to the writing: small letters sit close together.
            float room = Math.max(.45f, Math.min(1.4f, this.scale)); gap = GAP * room; figureGap = FIGURE_GAP * room;
            this.measure = measure == null ? Board.ROUGH : measure;
            float bottom = Board.bottom(items, this.measure);
            y = bottom <= 0 ? Board.MARGIN : bottom + 3 * gap;
        }
        public float y() { return y; }
        public void reflow(JSONArray items) {
            y=Board.bottom(items,measure)+3*gap;
            written.clear(); pictures.clear(); pictures.addAll(shown(items));
            for(int i=0;i<items.length();i++) { JSONObject item=items.optJSONObject(i); if(item!=null && Board.TEXT.equals(item.optString("t"))) written.add(item); }
        }

        /** The Board items of one step, in the order they are drawn. Operations the board does not know are left out. */
        public List<JSONObject> place(Step step) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject op : step.draw()) {
                if (y > Board.DEEPEST - 2000) break;
                switch (op.optString("op").strip().toLowerCase(Locale.ROOT)) {
                    case "write", "text" -> {
                        String text = op.optString("text").strip(); if (text.isEmpty()) continue;
                        float size = size(op.optString("size")) * scale; int indent = Math.max(0, Math.min(4, op.optInt("indent", 0)));
                        float left = Board.MARGIN + indent * Board.INDENT * Math.max(.6f, Math.min(1, scale / .85f));
                        // A bullet hangs in front of its line: the line's own words start after it, on every row.
                        String bullet = op.optString("bullet").strip(); if (bullet.codePointCount(0, bullet.length()) > 3) bullet = "";
                        if (!bullet.isEmpty()) { JSONObject mark = Board.handwritten(Board.text(left, y, bullet, size, op.optString("bulletColor").isBlank() ? op.optString("color") : op.optString("bulletColor"), false, 200, Board.AI), hand); out.add(mark); left = Board.bounds(mark, measure)[2] + size * .35f; }
                        float room = Board.WIDTH - Board.MARGIN - left;
                        JSONObject item = Board.text(left, y, text, size, op.optString("color"), "title".equalsIgnoreCase(op.optString("size").strip()), room, Board.AI);
                        float[] b = Board.bounds(item, measure); written.add(item); out.add(item);
                        // Marker over the words, or a line under them, drawn after they are written as a student goes back over a note.
                        String marker = op.optString("highlight").strip();
                        if (!marker.isEmpty() && !marker.equals("false")) for (float[] row : spans(item, 0, item.optString("text").length())) out.add(band(row, marker.equals("true") ? "yellow" : marker, .68f));
                        String under = op.opt("underline") == null ? "" : String.valueOf(op.opt("underline")).strip().toLowerCase(Locale.ROOT);
                        if (under.equals("true") || under.equals("wavy")) for (float[] row : spans(item, 0, item.optString("text").length())) {
                            JSONObject line = Board.shape(Board.LINE, row[0], row[3] + 2, row[2], row[3] + 2, op.optString("underlineColor").isBlank() ? "red" : op.optString("underlineColor"), Math.max(2, size / 16), false, Board.AI);
                            out.add(under.equals("wavy") ? Board.wavy(line) : line);
                        }
                        y = b[3] + gap + (under.isEmpty() || under.equals("false") ? 0 : size * .12f);
                    }
                    case "heading", "title" -> {
                        // A section's name as it stands in a pretty notebook: bold, with marker along its lower half.
                        String text = op.optString("text").strip(); if (text.isEmpty()) continue;
                        float size = Board.BIG * scale; if (!out.isEmpty() || !written.isEmpty()) y += gap * 1.2f;
                        JSONObject item = Board.handwritten(Board.text(Board.MARGIN, y, text, size, op.optString("ink"), true, Board.WIDTH - 2 * Board.MARGIN, Board.AI), hand);
                        float[] b = Board.bounds(item, measure); written.add(item); out.add(item);
                        for (float[] row : spans(item, 0, item.optString("text").length())) {
                            float height = row[3] - row[1], baseline = row[1] + height * .75f;
                            out.add(Board.stroke(op.optString("color").isBlank() ? "blue" : op.optString("color"), Math.min(17, height * .40f), true, new float[]{row[0] - 4, baseline, row[2] + 6, baseline}, Board.AI));
                        }
                        y = b[3] + gap * 1.3f;
                    }
                    case "note", "callout", "box" -> {
                        // Words to remember in a soft tinted box with round corners.
                        String text = op.optString("text").strip(); if (text.isEmpty()) continue;
                        String kind = op.optString("kind").strip().toLowerCase(Locale.ROOT), color = op.optString("color").isBlank() ? (kind.equals("warn") ? "red" : kind.equals("tip") ? "green" : kind.equals("check") ? "blue" : "orange") : op.optString("color");
                        float size = Board.NORMAL * scale * .95f, pad = size * .55f, top = y + gap * .6f, room = Board.WIDTH - 2 * Board.MARGIN - 2 * pad;
                        JSONObject item = Board.text(Board.MARGIN + pad, top + pad * .7f, text, size, op.optString("ink"), false, room, Board.AI);
                        float[] b = Board.bounds(item, measure);
                        out.add(Board.rounded(Board.filled(Board.shape(Board.RECT, Board.MARGIN, top, Math.min(Board.WIDTH - Board.MARGIN, b[2] + pad), b[3] + pad * .7f, color, Math.max(1.5f, size / 22), false, Board.AI)), Math.min(22, size * .5f)));
                        written.add(item); out.add(item); y = b[3] + pad * .7f + gap * 1.2f;
                    }
                    case "divider", "line" -> { float at = y + gap * .8f; out.add(Board.dashed(Board.faint(Board.shape(Board.LINE, Board.MARGIN, at, Board.WIDTH - Board.MARGIN, at, Board.INK, 2.5f, false, Board.AI)))); y = at + gap * 1.4f; }
                    case "figure", "draw" -> figure(out, op);
                    case "mark" -> out.addAll(mark(op));
                    case "annotate" -> {
                        List<JSONObject> marks = annotate(op);
                        if (marks.isEmpty()) missing(out, "자료에 표시할 위치를 찾지 못했어요."); else out.addAll(marks);
                    }
                    case "space" -> y += Math.max(0, Math.min(400, (float) op.optDouble("height", 40))) * Math.max(.45f, Math.min(1.4f, scale));
                    case "graph", "plot" -> widget(out, BoardWidget.GRAPH, op);
                    case "table" -> widget(out, BoardWidget.TABLE, op);
                    case "numberline", "number_line" -> widget(out, BoardWidget.NUMBER_LINE, op);
                    case "chart" -> widget(out, BoardWidget.CHART, op);
                    case "flow", "steps" -> widget(out, BoardWidget.FLOW, op);
                    case "timeline" -> widget(out, BoardWidget.TIMELINE, op);
                    case "tree", "mindmap" -> widget(out, BoardWidget.TREE, op);
                    case "geometry" -> widget(out, BoardWidget.GEOMETRY, op);
                    case "solid" -> widget(out, BoardWidget.SOLID, op);
                    case "relation","map","diagram","chemistry","reading","finitefield" -> widget(out, op.optString("op"), op);
                    case "update" -> {} // applied to existing material by the teacher before placement
                    default -> missing(out, "이 단계의 그리기 도구를 사용할 수 없어요.");
                }
            }
            return out;
        }
        private void widget(List<JSONObject> out, String kind, JSONObject op) {
            JSONObject item = BoardWidget.make(kind, op, y + figureGap, scale, Board.AI, measure);
            if (item == null) { missing(out, BoardWidget.name(kind) + "에 그릴 내용이 없어요."); return; }
            if (op.has("_wid")) Jsons.put(item,"wid",op.optString("_wid"));
            out.add(item); y = Board.bounds(item, measure)[3] + figureGap;
        }
        private void missing(List<JSONObject> out, String text) {
            JSONObject item = Board.text(Board.MARGIN, y, text, Board.SMALL * scale, "red", false, Board.WIDTH - 2 * Board.MARGIN, Board.AI);
            out.add(item); y = Board.bounds(item, measure)[3] + gap;
        }
        private void figure(List<JSONObject> out, JSONObject op) {
            float height = Math.max(120, Math.min(1200, (float) op.optDouble("height", 400)));
            JSONArray shapes = op.optJSONArray("shapes");
            List<JSONObject> made = new ArrayList<>();
            float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
            for (int i = 0; shapes != null && i < Math.min(shapes.length(), FIGURE_SHAPES); i++) {
                JSONObject item = shape(shapes.optJSONObject(i), 0, height);
                if (item == null) continue;
                float[] bounds = Board.bounds(item, measure);
                top = Math.min(top, bounds[1]); bottom = Math.max(bottom, bounds[3]); made.add(item);
            }
            if (made.isEmpty()) { missing(out, "도식에 그릴 내용이 없어요."); return; }
            float offset = y + figureGap - top;
            for (JSONObject item : made) Board.shift(item, offset);
            out.addAll(made);
            y += bottom - top + 2 * figureGap;
        }
        private List<float[]> spans(JSONObject item, int start, int end) {
            float left = (float) item.optDouble("x"), top = (float) item.optDouble("y");
            List<float[]> result = new ArrayList<>();
            for (float[] row : measure.spans(item.optString("text"), (float) item.optDouble("s"), item.optBoolean("b"),
                (float) item.optDouble("mw"), item.optBoolean("hand"), start, end)) {
                result.add(new float[]{left + row[0], top + row[1], left + row[2], top + row[3]});
            }
            return result;
        }
        /** A stroke of marker across a text's box, at part of its height. */
        private static JSONObject band(float[] b, String color, float share) {
            float height = b[3] - b[1], middle = (b[1] + b[3]) / 2;
            return Board.stroke(color, Math.min(Board.THICK, height * share), true, new float[]{b[0] - 3, middle, b[2] + 3, middle}, Board.AI);
        }
        JSONObject shape(JSONObject shape, float top, float height) {
            if (shape == null) return null;
            String kind = shape.optString("kind").strip().toLowerCase(Locale.ROOT), color = shape.optString("color");
            JSONArray from = shape.optJSONArray("from"), to = shape.optJSONArray("to");
            float x1 = inside(number(from, 0), Board.WIDTH), y1 = top + inside(number(from, 1), height), x2 = inside(number(to, 0), Board.WIDTH), y2 = top + inside(number(to, 1), height);
            JSONObject made = shaped(shape, kind, color, from, to, x1, y1, x2, y2, top, height);
            if (made != null && shape.optBoolean("fill")) Board.filled(made);
            return made != null && shape.optBoolean("dashed") ? Board.dashed(made) : made;
        }
        private JSONObject shaped(JSONObject shape, String kind, String color, JSONArray from, JSONArray to, float x1, float y1, float x2, float y2, float top, float height) {
            switch (kind) {
                case "line", "arrow" -> { return from == null || to == null ? null : Board.shape(Board.LINE, x1, y1, x2, y2, color, 5, kind.equals("arrow"), Board.AI); }
                case "rect", "box" -> { return from == null || to == null ? null : Board.shape(Board.RECT, x1, y1, x2, y2, color, 5, false, Board.AI); }
                case "oval", "circle", "ellipse" -> { return from == null || to == null ? null : Board.shape(Board.OVAL, x1, y1, x2, y2, color, 5, false, Board.AI); }
                case "path", "curve", "polygon" -> {
                    JSONArray points = shape.optJSONArray("points"); if (points == null || points.length() < 2) return null;
                    boolean closed = kind.equals("polygon") && points.length() >= 3; int count = Math.min(points.length(), 600); float[] flat = new float[(count + (closed ? 1 : 0)) * 2];
                    for (int i = 0; i < count; i++) { JSONArray point = points.optJSONArray(i); flat[2 * i] = inside(number(point, 0), Board.WIDTH); flat[2 * i + 1] = top + inside(number(point, 1), height); }
                    if (closed) { flat[2 * count] = flat[0]; flat[2 * count + 1] = flat[1]; }
                    JSONObject made = Board.stroke(color, 5, false, flat, Board.AI);
                    // A polygon's corners stay corners: it is drawn with straight sides.
                    if (kind.equals("polygon")) Jsons.put(made, "sharp", true);
                    return made;
                }
                case "label", "text" -> {
                    String text = shape.optString("text").strip(); JSONArray at = shape.optJSONArray("at"); if (text.isEmpty() || at == null) return null;
                    float left = Math.max(Board.MARGIN, Math.min(Board.WIDTH - Board.MARGIN - 100, inside(number(at, 0), Board.WIDTH)));
                    float size = (shape.optString("size").isBlank() ? Board.SMALL : size(shape.optString("size"))) * scale;
                    return Board.text(left, top + inside(number(at, 1), height), text, size, color, false, Board.WIDTH - Board.MARGIN - left, Board.AI);
                }
                case "point", "dot" -> {
                    JSONArray at = shape.optJSONArray("at"); if (at == null) return null;
                    return Board.stroke(color.isBlank() ? "red" : color, 16, false, new float[]{inside(number(at, 0), Board.WIDTH), top + inside(number(at, 1), height)}, Board.AI);
                }
                case "arc", "angle" -> {
                    // Part of a circle: an angle's mark, a sector's rim, a turn. Degrees run counter-clockwise from the right, as in mathematics.
                    JSONArray centre = shape.optJSONArray("center"); if (centre == null) return null;
                    float cx = inside(number(centre, 0), Board.WIDTH), cy = top + inside(number(centre, 1), height), radius = Math.max(8, Math.min(600, (float) shape.optDouble("radius", 40)));
                    double start = Math.toRadians(shape.optDouble("from", 0)), end = Math.toRadians(shape.optDouble("to", 90)); int steps = Math.max(4, (int) Math.min(120, Math.abs(end - start) / Math.toRadians(4)));
                    float[] flat = new float[(steps + 1) * 2];
                    for (int i = 0; i <= steps; i++) { double a = start + (end - start) * i / steps; flat[2 * i] = cx + radius * (float) Math.cos(a); flat[2 * i + 1] = cy - radius * (float) Math.sin(a); }
                    return Board.stroke(color, 4, false, flat, Board.AI);
                }
                default -> { return null; }
            }
        }
        private static float inside(float value, float most) { return Float.isNaN(value) ? 0 : Math.max(0, Math.min(most, value)); }
        /**
         * Marks on one of the board's pictures. A shape's coordinates run 0 to 1000 across the picture and 0 to 1000
         * down it, whatever its size on the board; a picture number the board does not have draws nothing.
         */
        private List<JSONObject> annotate(JSONObject op) {
            List<JSONObject> out = new ArrayList<>(); int number = op.optInt("image", 1); JSONArray shapes = op.optJSONArray("shapes");
            if (number < 1 || number > pictures.size() || shapes == null) return out;
            float[] b = Board.bounds(pictures.get(number - 1), measure); float w = b[2] - b[0], h = b[3] - b[1], pen = Math.max(3, Math.min(6, w / 200));
            for (int i = 0; i < shapes.length() && i < FIGURE_SHAPES; i++) {
                JSONObject shape = shapes.optJSONObject(i); if (shape == null) continue;
                String kind = shape.optString("kind").strip().toLowerCase(Locale.ROOT), color = shape.optString("color").isBlank() ? "red" : shape.optString("color");
                JSONArray box = shape.optJSONArray("box"), from = shape.optJSONArray("from"), to = shape.optJSONArray("to"), at = shape.optJSONArray("at");
                float x1 = b[0] + inside(number(box != null ? box : from, 0), 1000) / 1000 * w, y1 = b[1] + inside(number(box != null ? box : from, 1), 1000) / 1000 * h;
                float x2 = b[0] + inside(number(box != null ? box : to, box != null ? 2 : 0), 1000) / 1000 * w, y2 = b[1] + inside(number(box != null ? box : to, box != null ? 3 : 1), 1000) / 1000 * h;
                switch (kind) {
                    case "circle", "oval" -> { if (box != null) out.add(Board.shape(Board.OVAL, x1 - 6, y1 - 6, x2 + 6, y2 + 6, color, pen, false, Board.AI)); }
                    case "box", "rect" -> { if (box != null) out.add(Board.shape(Board.RECT, x1 - 4, y1 - 4, x2 + 4, y2 + 4, color, pen, false, Board.AI)); }
                    case "underline", "line" -> { if (from != null && to != null) out.add(Board.shape(Board.LINE, x1, y1, x2, y2, color, pen, false, Board.AI)); }
                    case "arrow" -> { if (from != null && to != null) out.add(Board.shape(Board.LINE, x1, y1, x2, y2, color, pen, true, Board.AI)); }
                    case "highlight" -> { if (from != null && to != null) out.add(Board.stroke(shape.optString("color").isBlank() ? "orange" : color, Math.max(16, Math.min(Board.THICK, h / 28)), true, new float[]{x1, y1, x2, y2}, Board.AI)); }
                    case "cross" -> { if (box != null) { out.add(Board.shape(Board.LINE, x1, y1, x2, y2, color, pen, false, Board.AI)); out.add(Board.shape(Board.LINE, x1, y2, x2, y1, color, pen, false, Board.AI)); } }
                    case "check" -> { if (at != null) { float cx = b[0] + inside(number(at, 0), 1000) / 1000 * w, cy = b[1] + inside(number(at, 1), 1000) / 1000 * h, r = Math.max(14, w / 40);
                        out.add(Board.stroke(color, pen, false, new float[]{cx - r, cy, cx - r * .3f, cy + r * .7f, cx + r, cy - r * .8f}, Board.AI)); Jsons.put(out.get(out.size() - 1), "sharp", true); } }
                    case "label", "text", "note" -> {
                        String text = shape.optString("text").strip(); if (text.isEmpty() || at == null) continue;
                        float left = b[0] + inside(number(at, 0), 1000) / 1000 * w;
                        out.add(Board.text(left, b[1] + inside(number(at, 1), 1000) / 1000 * h, text, Board.SMALL * scale, color, false, Math.max(160, Board.WIDTH - Board.MARGIN / 2f - left), Board.AI));
                    }
                    default -> { }
                }
            }
            for (JSONObject item : out) Jsons.put(item, "on", pictures.get(number - 1).optString("id"));
            return out;
        }
        /** Marks only the matching phrase, one line at a time when the phrase wraps. */
        private List<JSONObject> mark(JSONObject op) {
            String words = op.optString("text").strip(), kind = op.optString("kind").strip().toLowerCase(Locale.ROOT); if (words.isEmpty()) return List.of();
            for (int i = written.size() - 1; i >= 0; i--) {
                JSONObject line = written.get(i);
                int match = line.optString("text").lastIndexOf(words);
                if (match < 0) continue;
                List<JSONObject> out = new ArrayList<>();
                String color = op.optString("color").isBlank() ? "red" : op.optString("color");
                for (float[] b : spans(line, match, match + words.length())) {
                    float pad = Math.max(4, (b[3] - b[1]) * .16f), pen = Math.max(2, Math.min(4, (b[3] - b[1]) / 14));
                    out.add(switch (kind) {
                        case "box" -> Board.rounded(Board.shape(Board.RECT, b[0] - pad, b[1] - pad * .5f, b[2] + pad, b[3] + pad * .4f, color, pen, false, Board.AI), 14);
                        case "highlight" -> band(b, op.optString("color").isBlank() ? "yellow" : color, .62f);
                        case "circle" -> Board.shape(Board.OVAL, b[0] - pad * 2, b[1] - pad * .7f, b[2] + pad * 2, b[3] + pad * .7f, color, pen, false, Board.AI);
                        case "star" -> Board.text(Math.min(Board.WIDTH - Board.MARGIN, b[2] + pad), b[1], "★", (float) line.optDouble("s", Board.NORMAL) * .9f, color, false, 80, Board.AI);
                        default -> {
                            JSONObject under = Board.shape(Board.LINE, b[0], b[3] + 2, b[2], b[3] + 2, color, pen, false, Board.AI);
                            yield kind.equals("wavy") ? Board.wavy(under) : under;
                        }
                    });
                }
                return out;
            }
            return List.of();
        }
    }

    /** How long a step's drawing takes to appear, in milliseconds: writing at a hand's pace, shapes a little each. */
    public static long drawMillis(List<JSONObject> items) {
        long total = 0;
        for (JSONObject item : items) total += itemMillis(item);
        return Math.max(300, Math.min(20_000, total));
    }
    public static long itemMillis(JSONObject item) {
        if (Board.WIDGET.equals(item.optString("t"))) return 850;
        if (Board.TEXT.equals(item.optString("t"))) return Math.max(250, Math.min(6_000, item.optString("text").length() * 55L));
        if (Board.STROKE.equals(item.optString("t"))) { JSONArray p = item.optJSONArray("p"); return Math.max(350, Math.min(2_500, (p == null ? 0 : p.length() / 2) * 25L)); }
        return 450;
    }
    /** How long a step is held when no voice reads it: time to read what was said. */
    public static long readMillis(String say) { return say == null || say.isBlank() ? 400 : Math.max(1_200, Math.min(12_000, say.length() * 75L)); }
}
