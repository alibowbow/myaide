package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Drawings the board computes instead of sketching: the graph of functions on real axes, a table, a number line. Each
 * is made of ordinary Board items (lines, strokes, text) in a box as wide as the page starting at a given height, so it
 * is stored, erased and replayed like anything else. Also the small calculator that reads a function such as
 * "x^2 - 5x + 6" or "2sin(x) + √(x+1)".
 */
public final class BoardPlot {
    private BoardPlot() {}
    public static final int SAMPLES = 240, FUNCTIONS = 5, POINTS = 12, ROWS = 12, COLUMNS = 8;

    // ── Functions of x ──────────────────────────────────────────────────────────────────────────────────

    /** A function of x read from text. */
    public interface Function { double at(double x); }

    /** Reads a function of x; says so when the text is not one. */
    public static Function function(String text) {
        String clean = text == null ? "" : text.strip();
        // "y = …" and "f(x) = …" say what the function is called, not what it is.
        clean = clean.replaceFirst("^(?i)\\s*(y|[a-z]\\s*\\(\\s*x\\s*\\))\\s*=\\s*", "");
        clean = clean.replace("×", "*").replace("·", "*").replace("÷", "/").replace("−", "-").replace("–", "-").replace("²", "^2").replace("³", "^3").replace("π", "pi").replace("√", "sqrt")
            .replace("½", "(1/2)").replace("⅓", "(1/3)").replace("¼", "(1/4)").replace("¾", "(3/4)").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (clean.isEmpty() || clean.length() > 200) throw new IllegalArgumentException("함수식을 읽지 못했어요.");
        Parser parser = new Parser(clean); Node root = parser.expression();
        if (parser.at < clean.length()) throw new IllegalArgumentException("함수식을 읽지 못했어요: " + text);
        return root::value;
    }
    private interface Node { double value(double x); }
    private static final class Parser {
        final String s; int at; int depth;
        Parser(String s) { this.s = s; }
        private boolean eat(char c) { if (at < s.length() && s.charAt(at) == c) { at++; return true; } return false; }
        Node expression() {
            if (++depth > 60) throw new IllegalArgumentException("함수식이 너무 복잡해요.");
            Node left = term();
            while (at < s.length()) {
                if (eat('+')) { Node a = left, b = term(); left = x -> a.value(x) + b.value(x); }
                else if (eat('-')) { Node a = left, b = term(); left = x -> a.value(x) - b.value(x); }
                else break;
            }
            depth--; return left;
        }
        Node term() {
            Node left = unary();
            while (at < s.length()) {
                char c = s.charAt(at);
                if (eat('*')) { Node a = left, b = unary(); left = x -> a.value(x) * b.value(x); }
                else if (eat('/')) { Node a = left, b = unary(); left = x -> a.value(x) / b.value(x); }
                // 2x, 3(x+1), x sin(x): written side by side is multiplied.
                else if (Character.isLetterOrDigit(c) || c == '(' || c == '.') { Node a = left, b = power(); left = x -> a.value(x) * b.value(x); }
                else break;
            }
            return left;
        }
        Node unary() { if (eat('-')) { Node a = unary(); return x -> -a.value(x); } eat('+'); return power(); }
        Node power() {
            Node base = primary();
            if (eat('^')) { Node a = base, b = unary(); return x -> Math.pow(a.value(x), b.value(x)); }
            return base;
        }
        Node primary() {
            if (at >= s.length()) throw new IllegalArgumentException("함수식이 중간에 끊겼어요.");
            char c = s.charAt(at);
            if (eat('(')) { Node inner = expression(); if (!eat(')')) throw new IllegalArgumentException("괄호가 닫히지 않았어요."); return inner; }
            if (eat('|')) { Node inner = expression(); if (!eat('|')) throw new IllegalArgumentException("절댓값 기호가 닫히지 않았어요."); return x -> Math.abs(inner.value(x)); }
            if (Character.isDigit(c) || c == '.') {
                int from = at; while (at < s.length() && (Character.isDigit(s.charAt(at)) || s.charAt(at) == '.')) at++;
                double number; try { number = Double.parseDouble(s.substring(from, at)); } catch (NumberFormatException bad) { throw new IllegalArgumentException("숫자를 읽지 못했어요."); }
                return x -> number;
            }
            if (Character.isLetter(c)) {
                int from = at; while (at < s.length() && Character.isLetter(s.charAt(at))) at++;
                String name = s.substring(from, at);
                // The longest function name the letters start with; what is left of them is read on (xsin, pix).
                for (String known : new String[]{"sqrt", "asin", "acos", "atan", "sinh", "cosh", "tanh", "floor", "ceil", "sign", "sin", "cos", "tan", "abs", "exp", "log", "ln"}) {
                    if (!name.startsWith(known)) continue;
                    at = from + known.length(); Node inside;
                    if (eat('(')) { inside = expression(); if (!eat(')')) throw new IllegalArgumentException("괄호가 닫히지 않았어요."); } else inside = power();
                    Node in = inside;
                    return switch (known) {
                        case "sqrt" -> x -> Math.sqrt(in.value(x)); case "asin" -> x -> Math.asin(in.value(x)); case "acos" -> x -> Math.acos(in.value(x)); case "atan" -> x -> Math.atan(in.value(x));
                        case "sinh" -> x -> Math.sinh(in.value(x)); case "cosh" -> x -> Math.cosh(in.value(x)); case "tanh" -> x -> Math.tanh(in.value(x));
                        case "floor" -> x -> Math.floor(in.value(x)); case "ceil" -> x -> Math.ceil(in.value(x)); case "sign" -> x -> Math.signum(in.value(x));
                        case "sin" -> x -> Math.sin(in.value(x)); case "cos" -> x -> Math.cos(in.value(x)); case "tan" -> x -> Math.tan(in.value(x));
                        case "abs" -> x -> Math.abs(in.value(x)); case "exp" -> x -> Math.exp(in.value(x)); case "log" -> x -> Math.log10(in.value(x)); default -> x -> Math.log(in.value(x));
                    };
                }
                if (name.startsWith("pi")) { at = from + 2; return x -> Math.PI; }
                at = from + 1;
                if (c == 'x') return x -> x;
                if (c == 'e') return x -> Math.E;
                throw new IllegalArgumentException("x로 된 식만 그릴 수 있어요: " + c);
            }
            throw new IllegalArgumentException("함수식을 읽지 못했어요.");
        }
    }

    // ── A graph ─────────────────────────────────────────────────────────────────────────────────────────

    /** A round step for about `wanted` ticks across a span: 1, 2 or 5 times a power of ten. */
    public static double step(double span, int wanted) {
        if (!(span > 0)) return 1;
        double rough = span / Math.max(1, wanted), power = Math.pow(10, Math.floor(Math.log10(rough))), unit = rough / power;
        return (unit < 1.5 ? 1 : unit < 3.5 ? 2 : unit < 7.5 ? 5 : 10) * power;
    }
    public static String number(double value) {
        double rounded = Math.round(value * 1000) / 1000.0;
        if (rounded == Math.rint(rounded)) return (rounded < 0 ? "−" : "") + (long) Math.abs(rounded);
        String text = String.valueOf(Math.abs(rounded)); return (rounded < 0 ? "−" : "") + text;
    }
    private static double[] range(JSONArray pair, double low, double high) {
        double a = pair == null ? low : pair.optDouble(0, low), b = pair == null ? high : pair.optDouble(1, high);
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isInfinite(a) || Double.isInfinite(b) || a == b) return new double[]{low, high};
        return new double[]{Math.max(-1e6, Math.min(a, b)), Math.min(1e6, Math.max(a, b))};
    }

    /**
     * The graph an operation describes: {"x":[from,to], "y":[from,to] (found from the functions when left out),
     * "functions":[{"expr","color","label"}], "points":[{"at":[x,y],"label","open"}], "vlines":[x…], "hlines":[y…]} in a
     * box as wide as the page and `height` tall whose top is at `top`. `scale` sizes its numbers and labels. A function
     * that cannot be read is left out; with nothing to draw the axes still are.
     */
    public static List<JSONObject> graph(JSONObject spec, float top, float height, float scale) {
        List<JSONObject> out = new ArrayList<>();
        double[] xs = range(spec.optJSONArray("x"), -5, 5);
        JSONArray list = spec.optJSONArray("functions"); List<Function> functions = new ArrayList<>(); List<JSONObject> described = new ArrayList<>();
        for (int i = 0; list != null && i < list.length() && functions.size() < FUNCTIONS; i++) {
            JSONObject one = list.optJSONObject(i); String expr = one == null ? list.optString(i) : one.optString("expr");
            try { functions.add(function(expr)); described.add(one == null ? Jsons.obj("expr", expr) : one); } catch (IllegalArgumentException unreadable) { /* left out */ }
        }
        double[] ys = spec.optJSONArray("y") != null ? range(spec.optJSONArray("y"), -5, 5) : fit(functions, xs);
        float left = 90, right = Board.WIDTH - 60, bottom = top + height - 40, size = Math.max(20, Board.SMALL * .8f * scale);
        // The functions' names are a legend above the plot, each in its curve's color, so no name lies across a curve.
        String[] colors = {"blue", "red", "green", "purple", "orange"};
        float legendX = left, legendY = top; boolean named = false;
        for (int f = 0; f < functions.size(); f++) {
            String label = described.get(f).optString("label").strip(); if (label.isEmpty()) continue;
            String color = described.get(f).optString("color").isBlank() ? colors[f % colors.length] : described.get(f).optString("color");
            // One name to a row: whatever letters it is written in, no name runs into the next.
            if (named) legendY += size * 1.5f;
            out.add(Board.text(legendX, legendY, "— " + label, size, color, false, right - left, Board.AI)); named = true;
        }
        float upper = (named ? legendY + size * 1.5f : top) + 30;
        double xSpan = xs[1] - xs[0], ySpan = ys[1] - ys[0];
        java.util.function.DoubleUnaryOperator px = x -> left + (x - xs[0]) / xSpan * (right - left), py = y -> bottom - (y - ys[0]) / ySpan * (bottom - upper);
        // Axes where zero is, at the box's edge when zero is out of view.
        float axisY = (float) py.applyAsDouble(Math.max(ys[0], Math.min(ys[1], 0))), axisX = (float) px.applyAsDouble(Math.max(xs[0], Math.min(xs[1], 0)));
        out.add(Board.shape(Board.LINE, left - 20, axisY, right + 30, axisY, Board.INK, 3, true, Board.AI));
        out.add(Board.shape(Board.LINE, axisX, bottom + 20, axisX, upper - 24, Board.INK, 3, true, Board.AI));
        out.add(Board.text(right + 34, axisY - size * .7f, "x", size, Board.INK, false, 80, Board.AI));
        out.add(Board.text(axisX + 10, upper - 24 - size * .9f, "y", size, Board.INK, false, 80, Board.AI));
        double xStep = step(xSpan, 8), yStep = step(ySpan, 6);
        for (double x = Math.ceil(xs[0] / xStep) * xStep; x <= xs[1] + 1e-9; x += xStep) {
            if (Math.abs(x) < xStep / 1000) continue;
            float at = (float) px.applyAsDouble(x); out.add(Board.shape(Board.LINE, at, axisY - 8, at, axisY + 8, Board.INK, 2, false, Board.AI));
            String label = number(x); out.add(Board.text(at - label.length() * size * .28f, axisY + 12, label, size, Board.INK, false, 200, Board.AI));
        }
        for (double y = Math.ceil(ys[0] / yStep) * yStep; y <= ys[1] + 1e-9; y += yStep) {
            if (Math.abs(y) < yStep / 1000) continue;
            float at = (float) py.applyAsDouble(y); out.add(Board.shape(Board.LINE, axisX - 8, at, axisX + 8, at, Board.INK, 2, false, Board.AI));
            String label = number(y); out.add(Board.text(axisX - 16 - label.length() * size * .58f, at - size * .7f, label, size, Board.INK, false, 200, Board.AI));
        }
        if (xs[0] <= 0 && xs[1] >= 0 && ys[0] <= 0 && ys[1] >= 0) out.add(Board.text(axisX - size * .9f, axisY + 10, "O", size, Board.INK, false, 80, Board.AI));
        JSONArray vertical = spec.optJSONArray("vlines"), horizontal = spec.optJSONArray("hlines");
        for (int i = 0; vertical != null && i < vertical.length() && i < 6; i++) { double x = vertical.optDouble(i, Double.NaN); if (x >= xs[0] && x <= xs[1]) out.add(Board.dashed(Board.shape(Board.LINE, (float) px.applyAsDouble(x), bottom, (float) px.applyAsDouble(x), upper, "orange", 3, false, Board.AI))); }
        for (int i = 0; horizontal != null && i < horizontal.length() && i < 6; i++) { double y = horizontal.optDouble(i, Double.NaN); if (y >= ys[0] && y <= ys[1]) out.add(Board.dashed(Board.shape(Board.LINE, left, (float) py.applyAsDouble(y), right, (float) py.applyAsDouble(y), "orange", 3, false, Board.AI))); }
        for (int f = 0; f < functions.size(); f++) {
            String color = described.get(f).optString("color").isBlank() ? colors[f % colors.length] : described.get(f).optString("color");
            // The curve in pieces: it breaks where the function has no value or leaves the box.
            List<Float> piece = new ArrayList<>();
            for (int i = 0; i <= SAMPLES; i++) {
                double x = xs[0] + xSpan * i / SAMPLES, y; try { y = functions.get(f).at(x); } catch (RuntimeException undefined) { y = Double.NaN; }
                boolean inside = !Double.isNaN(y) && !Double.isInfinite(y) && y >= ys[0] && y <= ys[1];
                if (inside) { piece.add((float) px.applyAsDouble(x)); piece.add((float) py.applyAsDouble(y)); }
                if (!inside || i == SAMPLES) { if (piece.size() >= 4) out.add(stroke(piece, color)); piece = new ArrayList<>(); }
            }
        }
        JSONArray points = spec.optJSONArray("points");
        for (int i = 0; points != null && i < points.length() && i < POINTS; i++) {
            JSONObject point = points.optJSONObject(i); JSONArray at = point == null ? null : point.optJSONArray("at"); if (at == null) continue;
            double x = at.optDouble(0, Double.NaN), y = at.optDouble(1, Double.NaN); if (!(x >= xs[0] && x <= xs[1] && y >= ys[0] && y <= ys[1])) continue;
            float cx = (float) px.applyAsDouble(x), cy = (float) py.applyAsDouble(y); String color = point.optString("color").isBlank() ? "red" : point.optString("color");
            out.add(point.optBoolean("open") ? Board.shape(Board.OVAL, cx - 9, cy - 9, cx + 9, cy + 9, color, 4, false, Board.AI) : Board.stroke(color, 18, false, new float[]{cx, cy}, Board.AI));
            String label = point.optString("label").strip(); if (!label.isEmpty()) out.add(Board.text(cx + 16, cy + 8, label, size, color, false, 360, Board.AI));
        }
        return out;
    }
    private static JSONObject stroke(List<Float> points, String color) { float[] flat = new float[points.size()]; for (int i = 0; i < flat.length; i++) flat[i] = points.get(i); return Board.stroke(color, 5, false, flat, Board.AI); }
    /** A y range that shows the functions over the x range, without letting one steep end flatten everything else. */
    private static double[] fit(List<Function> functions, double[] xs) {
        List<Double> values = new ArrayList<>();
        for (Function function : functions) for (int i = 0; i <= SAMPLES; i++) { double y; try { y = function.at(xs[0] + (xs[1] - xs[0]) * i / SAMPLES); } catch (RuntimeException undefined) { continue; } if (!Double.isNaN(y) && !Double.isInfinite(y)) values.add(y); }
        if (values.isEmpty()) return new double[]{-5, 5};
        values.sort(Double::compare);
        double low = values.get((int) (values.size() * .04)), high = values.get((int) Math.min(values.size() - 1, values.size() * .96)), span = Math.max(high - low, 1e-6);
        low = Math.min(low - span * .12, 0); high = Math.max(high + span * .12, 0);
        if (high - low < 1e-3) { low -= 1; high += 1; }
        return new double[]{low, high};
    }

    // ── A table ─────────────────────────────────────────────────────────────────────────────────────────

    /** The height a table of this many rows takes at a text size. */
    public static float tableHeight(int rows, float size) { return Math.max(1, Math.min(ROWS, rows)) * size * 1.9f; }
    /**
     * A table from rows of cells (text), starting at `top`: its ruled lines and each cell's text centred. Columns are as
     * wide as their widest cell needs and the table as wide as the page allows. The first row is written bold when
     * header is true.
     */
    public static List<JSONObject> table(JSONArray rows, boolean header, float top, float size, Board.Measure measure) {
        List<JSONObject> out = new ArrayList<>(); Board.Measure ruler = measure == null ? Board.ROUGH : measure;
        int rowCount = rows == null ? 0 : Math.min(ROWS, rows.length()), columns = 0;
        for (int r = 0; r < rowCount; r++) { JSONArray row = rows.optJSONArray(r); if (row != null) columns = Math.max(columns, Math.min(COLUMNS, row.length())); }
        if (rowCount == 0 || columns == 0) return out;
        float[] widths = new float[columns]; float pad = size * .7f, room = Board.WIDTH - 2 * Board.MARGIN, total = 0;
        for (int c = 0; c < columns; c++) {
            float widest = size * 1.6f;
            for (int r = 0; r < rowCount; r++) { JSONArray row = rows.optJSONArray(r); String cell = row == null ? "" : row.optString(c); widest = Math.max(widest, ruler.size(cell, size, false, room)[0]); }
            widths[c] = widest + 2 * pad; total += widths[c];
        }
        if (total > room) { for (int c = 0; c < columns; c++) widths[c] *= room / total; total = room; }
        float left = Board.MARGIN, rowHeight = size * 1.9f, bottom = top + rowCount * rowHeight;
        for (int r = 0; r <= rowCount; r++) out.add(Board.shape(Board.LINE, left, top + r * rowHeight, left + total, top + r * rowHeight, Board.INK, r == 0 || r == rowCount || header && r == 1 ? 4 : 2, false, Board.AI));
        float x = left;
        for (int c = 0; c <= columns; c++) { out.add(Board.shape(Board.LINE, x, top, x, bottom, Board.INK, c == 0 || c == columns ? 4 : 2, false, Board.AI)); if (c < columns) x += widths[c]; }
        for (int r = 0; r < rowCount; r++) {
            JSONArray row = rows.optJSONArray(r); x = left;
            for (int c = 0; c < columns; c++) {
                String cell = row == null ? "" : row.optString(c).strip(); boolean bold = header && r == 0;
                // A cell's text keeps a little off the ruled lines even when it has to wrap.
                if (!cell.isEmpty()) { float inner = Math.max(40, widths[c] - 8); float[] box = ruler.size(cell, size, bold, inner); out.add(Board.text(x + 4 + Math.max(0, (inner - box[0]) / 2), top + r * rowHeight + Math.max(2, (rowHeight - box[1]) / 2), cell, size, Board.INK, bold, inner, Board.AI)); }
                x += widths[c];
            }
        }
        return out;
    }

    // ── A number line ───────────────────────────────────────────────────────────────────────────────────

    public static final float LINE_HEIGHT = 170;
    /**
     * A number line from {"from","to","marks":[{"at","label","open"}],"ranges":[{"from","to","color"}]}: the line with
     * round ticks, each range drawn thick above it, each mark a filled or open dot with its label.
     */
    public static List<JSONObject> numberLine(JSONObject spec, float top, float scale) {
        List<JSONObject> out = new ArrayList<>();
        double[] xs = range(Jsons.arr(spec.optDouble("from", -5), spec.optDouble("to", 5)), -5, 5); double span = xs[1] - xs[0];
        float left = 90, right = Board.WIDTH - 90, y = top + 90, size = Math.max(20, Board.SMALL * .8f * scale);
        java.util.function.DoubleUnaryOperator px = x -> left + (x - xs[0]) / span * (right - left);
        out.add(Board.shape(Board.LINE, left - 40, y, right + 40, y, Board.INK, 3, true, Board.AI));
        double step = step(span, 10);
        for (double x = Math.ceil(xs[0] / step) * step; x <= xs[1] + 1e-9; x += step) {
            float at = (float) px.applyAsDouble(x); String label = number(x);
            out.add(Board.shape(Board.LINE, at, y - 9, at, y + 9, Board.INK, 2, false, Board.AI));
            out.add(Board.text(at - label.length() * size * .28f, y + 14, label, size, Board.INK, false, 200, Board.AI));
        }
        JSONArray ranges = spec.optJSONArray("ranges");
        for (int i = 0; ranges != null && i < ranges.length() && i < 4; i++) {
            JSONObject range = ranges.optJSONObject(i); if (range == null) continue;
            // An end left out runs off that side of the line.
            double from = Math.max(xs[0] - span * .04, range.optDouble("from", xs[0] - span)), to = Math.min(xs[1] + span * .04, range.optDouble("to", xs[1] + span)); if (!(from < to)) continue;
            out.add(Board.shape(Board.LINE, (float) px.applyAsDouble(from), y - 26 - i * 16, (float) px.applyAsDouble(to), y - 26 - i * 16, range.optString("color").isBlank() ? "red" : range.optString("color"), 7, false, Board.AI));
        }
        JSONArray marks = spec.optJSONArray("marks");
        for (int i = 0; marks != null && i < marks.length() && i < POINTS; i++) {
            JSONObject mark = marks.optJSONObject(i); if (mark == null) continue;
            double at = mark.optDouble("at", Double.NaN); if (!(at >= xs[0] && at <= xs[1])) continue;
            float cx = (float) px.applyAsDouble(at); String color = mark.optString("color").isBlank() ? "red" : mark.optString("color");
            out.add(mark.optBoolean("open") ? Board.shape(Board.OVAL, cx - 10, y - 10, cx + 10, y + 10, color, 4, false, Board.AI) : Board.stroke(color, 20, false, new float[]{cx, y}, Board.AI));
            String label = mark.optString("label").strip(); if (!label.isEmpty()) out.add(Board.text(cx - label.length() * size * .28f, y - 44 - size, label, size, color, false, 300, Board.AI));
        }
        return out;
    }
}
