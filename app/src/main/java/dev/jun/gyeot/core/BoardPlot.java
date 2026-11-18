package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;
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
        return function(text, Map.of(), null);
    }

    public static Function function(String text, Map<String, Double> values) {
        return function(text, values, null);
    }

    /** Variables in first-use order, parsed by the same grammar as the plotted expression. */
    public static List<String> parameters(String text) {
        Map<String, Double> names = new LinkedHashMap<>();
        function(text, Map.of(), names);
        return new ArrayList<>(names.keySet());
    }

    private static Function function(String text, Map<String, Double> values, Map<String, Double> names) {
        return function(text,values,names,null);
    }
    static java.util.function.DoubleBinaryOperator implicit(String expression) {
        double[] ordinate={0};
        Function curve=function(expression,Map.of(),null,ordinate);
        return (x,y)->{ordinate[0]=y;return curve.at(x);};
    }
    private static Function function(String text, Map<String,Double> values, Map<String,Double> names,double[] ordinate) {
        String clean = text == null ? "" : text.strip();
        // "y = …" and "f(x) = …" say what the function is called, not what it is.
        clean = clean.replaceFirst("^(?i)\\s*(y|[a-z]\\s*\\(\\s*x\\s*\\))\\s*=\\s*", "");
        clean = clean.replace("×", "*").replace("·", "*").replace("÷", "/").replace("−", "-").replace("–", "-").replace("²", "^2").replace("³", "^3").replace("π", "pi").replace("√", "sqrt")
            .replace("½", "(1/2)").replace("⅓", "(1/3)").replace("¼", "(1/4)").replace("¾", "(3/4)").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (clean.isEmpty() || clean.length() > 200) throw new IllegalArgumentException("함수식을 읽지 못했어요.");
        Parser parser = new Parser(clean, values, names,ordinate); Node root = parser.expression();
        if (parser.at < clean.length()) throw new IllegalArgumentException("함수식을 읽지 못했어요: " + text);
        return root::value;
    }
    private interface Node { double value(double x); }
    private static final class Parser {
        final String s; final Map<String, Double> values, names; int at; int depth;
        final double[] ordinate;
        Parser(String s, Map<String, Double> values, Map<String, Double> names,double[] ordinate) {this.ordinate=ordinate; this.s = s; this.values = values; this.names = names; }
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
                if(c=='y' && ordinate!=null)return x->ordinate[0];
                if (c == 'e') return x -> Math.E;
                if (c >= 'a' && c <= 'z') {
                    String key = String.valueOf(c);
                    if (names != null) { names.putIfAbsent(key, 0.0); return x -> 0; }
                    Double value = values.get(key);
                    if (value != null && Double.isFinite(value)) return x -> value;
                }
                throw new IllegalArgumentException("x로 된 식만 그릴 수 있어요: " + c);
            }
            throw new IllegalArgumentException("함수식을 읽지 못했어요.");
        }
    }

    /**
     * How much of the page's width a computed visual takes at a text scale: all of it at a phone's scale, less where the
     * writing is small (a wide screen), so a graph is the size of the writing around it and not of the screen.
     */
    public static float compact(float scale) { return Math.max(.58f, Math.min(1, scale / .85f)); }

    // ── A graph ─────────────────────────────────────────────────────────────────────────────────────────

    /** A round step for about `wanted` ticks across a span: 1, 2 or 5 times a power of ten. */
    public static double step(double span, int wanted) {
        if (!(span > 0) || !Double.isFinite(span)) return 1;
        double rough = span / Math.max(1, wanted), power = Math.pow(10, Math.floor(Math.log10(rough))), unit = rough / power;
        double tick = (unit < 1.5 ? 1 : unit < 3.5 ? 2 : unit < 7.5 ? 5 : 10) * power;
        return tick > 0 && Double.isFinite(tick) ? tick : span;
    }
    public static String number(double value) {
        if (!Double.isFinite(value)) return "—";
        if (value == 0) return "0";
        BigDecimal decimal = BigDecimal.valueOf(value).round(new MathContext(12)).stripTrailingZeros();
        double magnitude = Math.abs(value);
        return (magnitude >= 1e7 || magnitude < 1e-3 ? decimal.toString() : decimal.toPlainString()).replace('-', '−');
    }
    private static void ticks(double low, double high, double step, DoubleConsumer emit) {
        if (!(low < high) || !(step > 0) || !Double.isFinite(step)) return;
        double first = Math.ceil(low / step - 1e-9) * step;
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 32; i++) {
            double at = first + i * step;
            if (!Double.isFinite(at) || at <= previous || at > high + step * 1e-8) break;
            emit.accept(Math.abs(at) < step * 1e-8 ? 0 : at);
            previous = at;
        }
    }
    private static double[] range(JSONArray pair, double low, double high) {
        double a = pair == null ? low : pair.optDouble(0, low), b = pair == null ? high : pair.optDouble(1, high);
        if (!Double.isFinite(a) || !Double.isFinite(b)) return new double[]{low, high};
        double from = Math.max(-1e6, Math.min(a, b)), to = Math.min(1e6, Math.max(a, b));
        return from < to && to - from >= 1e-300 ? new double[]{from, to} : new double[]{low, high};
    }
    private static final String[] COLORS = {"blue", "pink", "green", "orange", "purple"};
    /** The room a graph's legend takes above its plot: a row for each function that has a name. */
    public static float legendHeight(JSONObject spec, float scale) {
        JSONArray list = spec.optJSONArray("functions"); int named = 0;
        for (int i = 0; list != null && i < list.length() && i < FUNCTIONS; i++) { JSONObject one = list.optJSONObject(i); if (one != null && !one.optString("label").isBlank()) named++; }
        return named * Math.max(18, Board.SMALL * .8f * scale) * 1.5f;
    }
    /** One function of a graph: what it is, where it is drawn (a piece of a piecewise function has ends), its color and name. */
    private record Curve(Function function, double from, double to, double start, double end, boolean fromOpen, boolean toOpen, String color, String label) {
        double value(double x) { try { return function.at(x); } catch (RuntimeException undefined) { return Double.NaN; } }
    }

    /**
     * The graph an operation describes, in a box as wide as the page and `height` tall whose top is at `top`; `scale`
     * sizes its numbers and names. The operation: {"x":[from,to], "y":[from,to] (found from the functions when left
     * out), "grid":true, "functions":[{"expr","color","label","from","to","fromOpen","toOpen"}], "shade":[{"expr",
     * "from","to","color"}], "points":[{"at":[x,y],"label","open"}], "vlines":[x…], "hlines":[y…]}. A function with
     * "from" or "to" is drawn only there, with a dot at that end (hollow when the end is open): the pieces of a piecewise
     * function. "shade" tints the region between a curve and the x axis. A function that cannot be read is left out;
     * with nothing to draw the axes still are.
     */
    static boolean hasPoints(JSONObject spec) { return pointRange(spec.optJSONArray("points"), 0) != null; }
    private static double[] pointRange(JSONArray points, int axis) {
        double min=Double.POSITIVE_INFINITY, max=Double.NEGATIVE_INFINITY;
        for(int i=0;points!=null && i<Math.min(256,points.length());i++) {
            JSONObject point=points.optJSONObject(i); JSONArray at=point==null?points.optJSONArray(i):point.optJSONArray("at");
            if(at==null || !Double.isFinite(at.optDouble(0)) || !Double.isFinite(at.optDouble(1)))continue;
            double v=at.optDouble(axis); min=Math.min(min,v); max=Math.max(max,v);
        }
        if(min>max)return null;
        double pad=Math.max(1,(max-min)*.1); return new double[]{min-pad,max+pad};
    }

    private static void expandRange(double[] bounds, double span) {
        double extra = (span - (bounds[1] - bounds[0])) / 2;
        if (extra > 0) { bounds[0] -= extra; bounds[1] += extra; }
    }

    public static List<JSONObject> graph(JSONObject spec, float top, float height, float scale) {
        return graph(spec,top,height,scale,false);
    }
    static List<JSONObject> graph(JSONObject spec,float top,float height,float scale,boolean relation) {
        List<JSONObject> out = new ArrayList<>();
        double[] dataX = pointRange(spec.optJSONArray("points"),0);
        double[] xs = range(spec.optJSONArray("x"), dataX==null?-5:Math.min(-5,dataX[0]), dataX==null?5:Math.max(5,dataX[1]));
        Map<String, Double> values = BoardWidget.values(spec);
        JSONArray list = spec.optJSONArray("functions"); List<Curve> curves = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; list != null && i < list.length() && i < FUNCTIONS; i++) {
            JSONObject one = list.optJSONObject(i); String expr = one == null ? list.optString(i) : one.optString("expr");
            try {
                Function function = function(expr, values); JSONObject d = one == null ? Jsons.obj() : one;
                double from = d.optDouble("from", Double.NaN), to = d.optDouble("to", Double.NaN);
                curves.add(new Curve(function, Double.isFinite(from) ? Math.max(xs[0], from) : xs[0], Double.isFinite(to) ? Math.min(xs[1], to) : xs[1], from, to, d.optBoolean("fromOpen"), d.optBoolean("toOpen"),
                    d.optString("color").isBlank() ? COLORS[curves.size() % COLORS.length] : d.optString("color"), d.optString("label").strip()));
            } catch (IllegalArgumentException unreadable) { errors.add(expr); }
        }
        if (curves.isEmpty() && dataX==null && !relation) return List.of(Board.text(Board.MARGIN, top, "표시할 수 있는 함수식이 없어요. 그래프를 눌러 식을 고쳐 주세요.", Board.SMALL * scale, "red", false, Board.WIDTH - 2 * Board.MARGIN, Board.AI));
        double[] dataY = pointRange(spec.optJSONArray("points"),1);
        double[] fitted = spec.optJSONArray("y") != null ? range(spec.optJSONArray("y"), -5, 5) : curves.isEmpty() ? (dataY==null?new double[]{-5,5}:dataY) : fit(curves, xs);
        double[] ys=spec.optJSONArray("y")==null && dataY!=null ? new double[]{Math.min(fitted[0],dataY[0]),Math.max(fitted[1],dataY[1])}:fitted;
        float size=Math.max(18,Board.SMALL*.8f*scale);
        float roomWidth=(Board.WIDTH-150)*compact(scale);
        float roomTop=top+30+size*.9f+(curves.stream().filter(curve->!curve.label().isBlank()).count()+(errors.isEmpty()?0:1))*size*1.5f;
        float roomHeight=Math.max(8,top+height-40-roomTop);
        double ratio=(xs[1]-xs[0])/(ys[1]-ys[0]);
        boolean squareUnits = relation || spec.optBoolean("equalUnits", true);
        // Ordinary Cartesian graphs use the full drawing area with one geometric scale. Keep both requested
        // ranges in view and pad the shorter one. Relations and discrete fields have an exact domain.
        if (squareUnits && !relation && !curves.isEmpty()) {
            double unitsPerPixel = Math.max((xs[1] - xs[0]) / roomWidth, (ys[1] - ys[0]) / roomHeight);
            double wider = unitsPerPixel * roomWidth, taller = unitsPerPixel * roomHeight;
            if (Double.isFinite(wider) && Double.isFinite(taller) && wider <= 2e6 && taller <= 2e6) {
                expandRange(xs, wider);
                expandRange(ys, taller);
                ratio = (xs[1] - xs[0]) / (ys[1] - ys[0]);
                curves.replaceAll(c -> new Curve(c.function(), Double.isFinite(c.start()) ? Math.max(xs[0], c.start()) : xs[0],
                    Double.isFinite(c.end()) ? Math.min(xs[1], c.end()) : xs[1],
                    c.start(), c.end(), c.fromOpen(), c.toOpen(), c.color(), c.label()));
            }
        }
        float plotWidth=squareUnits?(float)Math.min(roomWidth,roomHeight*ratio):roomWidth;
        float plotHeight=squareUnits?(float)Math.min(roomHeight,roomWidth/ratio):roomHeight;
        float left=90+(roomWidth-plotWidth)/2,right=left+plotWidth,bottom=roomTop+plotHeight;
        // The functions' names are a legend above the plot, each in its curve's color, so no name lies across a curve.
        float legendY = top; boolean named = false;
        for (Curve curve : curves) {
            if (curve.label().isEmpty()) continue;
            // One name to a row: whatever letters it is written in, no name runs into the next.
            if (named) legendY += size * 1.5f;
            out.add(Board.text(90, legendY, "— " + curve.label(), size, curve.color(), false, roomWidth, Board.AI)); named = true;
        }
        if (!errors.isEmpty()) {
            if (named) legendY += size * 1.5f;
            out.add(Board.text(90, legendY, "일부 함수식을 그리지 못했어요. 그래프에서 식을 확인해 주세요.", size, "red", false, roomWidth, Board.AI));
            named = true;
        }
        // Room above the plot for the y axis's arrow and its name, so they never reach into the line above.
        float upper = (named ? legendY + size * 1.5f : top) + 30 + size * .9f;
        double xSpan = xs[1] - xs[0], ySpan = ys[1] - ys[0];
        java.util.function.DoubleUnaryOperator px = x -> left + (x - xs[0]) / xSpan * (right - left), py = y -> bottom - (y - ys[0]) / ySpan * (bottom - upper);
        // As many ticks as their numbers have room for: fewer on a small plot, so no number sits on the next.
        double xStep = step(xSpan, Math.max(2, Math.min(8, Math.round((right - left) / (size * 3.6f))))), yStep = step(ySpan, Math.max(2, Math.min(8, Math.round((bottom - upper) / (size * 1.9f)))));
        // A pale grid at the ticks, under everything else.
        if (spec.optBoolean("grid", true)) {
            ticks(xs[0], xs[1], xStep, x -> out.add(Board.faint(Board.shape(Board.LINE, (float) px.applyAsDouble(x), bottom, (float) px.applyAsDouble(x), upper, Board.INK, 1.5f, false, Board.AI))));
            ticks(ys[0], ys[1], yStep, y -> out.add(Board.faint(Board.shape(Board.LINE, left, (float) py.applyAsDouble(y), right, (float) py.applyAsDouble(y), Board.INK, 1.5f, false, Board.AI))));
        }
        // Regions between a curve and the x axis, tinted.
        JSONArray shades = spec.optJSONArray("shade");
        for (int i = 0; shades != null && i < shades.length() && i < 4; i++) {
            JSONObject shade = shades.optJSONObject(i); if (shade == null) continue;
            Function function; try { function = function(shade.optString("expr"), values); } catch (IllegalArgumentException unreadable) { continue; }
            double from = Math.max(xs[0], shade.optDouble("from", xs[0])), to = Math.min(xs[1], shade.optDouble("to", xs[1])); if (!(from < to)) continue;
            float base = (float) py.applyAsDouble(Math.max(ys[0], Math.min(ys[1], 0)));
            String color = shade.optString("color").isBlank() ? "orange" : shade.optString("color");
            for (List<Float> piece : pieces(function, from, to, ys, px, py, true)) {
                List<Float> edge = new ArrayList<>(); edge.add(piece.get(0)); edge.add(base); edge.addAll(piece);
                edge.add(piece.get(piece.size() - 2)); edge.add(base); edge.add(edge.get(0)); edge.add(base);
                out.add(region(edge, color, 1.5f));
            }
        }
        // Axes where zero is, at the box's edge when zero is out of view.
        float axisY = (float) py.applyAsDouble(Math.max(ys[0], Math.min(ys[1], 0))), axisX = (float) px.applyAsDouble(Math.max(xs[0], Math.min(xs[1], 0)));
        out.add(Board.shape(Board.LINE, left - 20, axisY, right + 30, axisY, Board.INK, 2.4f, true, Board.AI));
        out.add(Board.shape(Board.LINE, axisX, bottom + 20, axisX, upper - 24, Board.INK, 2.4f, true, Board.AI));
        out.add(Board.text(right + 34, axisY - size * .7f, "x", size, Board.INK, false, 80, Board.AI));
        out.add(Board.text(axisX + 10, upper - 24 - size * .9f, "y", size, Board.INK, false, 80, Board.AI));
        ticks(xs[0], xs[1], xStep, x -> {
            if (x == 0) return;
            float at = (float) px.applyAsDouble(x); out.add(Board.shape(Board.LINE, at, axisY - 7, at, axisY + 7, Board.INK, 2, false, Board.AI));
            String label = number(x); out.add(Board.text(at - label.length() * size * .28f, axisY + 10, label, size, Board.INK, false, 200, Board.AI));
        });
        ticks(ys[0], ys[1], yStep, y -> {
            if (y == 0) return;
            float at = (float) py.applyAsDouble(y); out.add(Board.shape(Board.LINE, axisX - 7, at, axisX + 7, at, Board.INK, 2, false, Board.AI));
            String label = number(y); out.add(Board.text(axisX - 14 - label.length() * size * .58f, at - size * .7f, label, size, Board.INK, false, 200, Board.AI));
        });
        if (xs[0] <= 0 && xs[1] >= 0 && ys[0] <= 0 && ys[1] >= 0) out.add(Board.text(axisX - size * .9f, axisY + 8, "O", size, Board.INK, false, 80, Board.AI));
        JSONArray vertical = spec.optJSONArray("vlines"), horizontal = spec.optJSONArray("hlines");
        for (int i = 0; vertical != null && i < vertical.length() && i < 6; i++) { double x = vertical.optDouble(i, Double.NaN); if (x >= xs[0] && x <= xs[1]) out.add(Board.dashed(Board.shape(Board.LINE, (float) px.applyAsDouble(x), bottom, (float) px.applyAsDouble(x), upper, "orange", 3, false, Board.AI))); }
        for (int i = 0; horizontal != null && i < horizontal.length() && i < 6; i++) { double y = horizontal.optDouble(i, Double.NaN); if (y >= ys[0] && y <= ys[1]) out.add(Board.dashed(Board.shape(Board.LINE, left, (float) py.applyAsDouble(y), right, (float) py.applyAsDouble(y), "orange", 3, false, Board.AI))); }
        if(relation)BoardCurves.add(out,spec,xs,ys,px,py);
        for (Curve curve : curves) {
            if (!(curve.from() < curve.to())) continue;
            for (List<Float> piece : pieces(curve.function(), curve.from(), curve.to(), ys, px, py, false)) out.add(stroke(piece, curve.color()));
        }
        // The ends of a piece: a filled dot where the end belongs to it, a hollow one where it does not.
        for (Curve curve : curves) {
            if (curve.from() > curve.to()) continue;
            if (Double.isFinite(curve.start())) end(out, curve, curve.start(), curve.fromOpen(), xs, ys, px, py);
            if (Double.isFinite(curve.end())) end(out, curve, curve.end(), curve.toOpen(), xs, ys, px, py);
        }
        JSONArray points = spec.optJSONArray("points");
        for (int i = 0; points != null && i < points.length() && i < 256; i++) {
            JSONObject point = points.optJSONObject(i); JSONArray at = point == null ? points.optJSONArray(i) : point.optJSONArray("at"); if (at == null) continue;
            if (point == null) point = Jsons.obj();
            double x = at.optDouble(0, Double.NaN), y = at.optDouble(1, Double.NaN); if (!(x >= xs[0] && x <= xs[1] && y >= ys[0] && y <= ys[1])) continue;
            float cx = (float) px.applyAsDouble(x), cy = (float) py.applyAsDouble(y); String color = point.optString("color").isBlank() ? "red" : point.optString("color");
            out.add(point.optBoolean("open") ? Board.shape(Board.OVAL, cx - 9, cy - 9, cx + 9, cy + 9, color, 4, false, Board.AI) : Board.stroke(color, points.length()>40?8:15, false, new float[]{cx, cy}, Board.AI));
            String label = point.optString("label").strip(); if (label.isEmpty()) continue;
            // The name goes to the corner of the point with the least under it: no curve, no axis, no tick's number.
            float wide = label.length() * size * .56f, tall = size * 1.3f, best = Float.MAX_VALUE, bx = cx + 14, by = cy - tall - 8;
            float[][] corners = {{cx + 14, cy - tall - 8}, {cx - 14 - wide, cy - tall - 8}, {cx + 14, cy + 10}, {cx - 14 - wide, cy + 10}};
            for (float[] corner : corners) {
                float l = corner[0], t = corner[1], r = l + wide, b = t + tall, cost = 0;
                if (l < left || r > right + 40 || t < upper - tall || b > bottom + 10) cost += 6;
                if (b > axisY - 6 && t < axisY + 12 + tall) cost += 4;
                if (r > axisX - size * 2.4f && l < axisX + 8) cost += 4;
                for (Curve curve : curves) for (int k = 0; k <= 10; k++) {
                    double x0 = xs[0] + (l + (r - l) * k / 10f - left) / (right - left) * xSpan; if (x0 < curve.from() || x0 > curve.to()) continue;
                    double y0 = curve.value(x0); if (Double.isNaN(y0) || Double.isInfinite(y0)) continue;
                    float at0 = (float) py.applyAsDouble(y0); if (at0 > t - 6 && at0 < b + 6) cost += 1;
                }
                if (cost < best) { best = cost; bx = l; by = t; }
            }
            out.add(Board.text(bx, by, label, size, color, false, 360, Board.AI));
        }
        return out;
    }
    private static void end(List<JSONObject> out, Curve curve, double x, boolean open, double[] xs, double[] ys, java.util.function.DoubleUnaryOperator px, java.util.function.DoubleUnaryOperator py) {
        double y = curve.value(x); if (Double.isNaN(y) || Double.isInfinite(y) || x < xs[0] || x > xs[1] || y < ys[0] || y > ys[1]) return;
        float cx = (float) px.applyAsDouble(x), cy = (float) py.applyAsDouble(y);
        out.add(open ? Board.shape(Board.OVAL, cx - 9, cy - 9, cx + 9, cy + 9, curve.color(), 4, false, Board.AI) : Board.stroke(curve.color(), 18, false, new float[]{cx, cy}, Board.AI));
    }

    private static List<List<Float>> pieces(Function function, double from, double to, double[] ys,
                                            java.util.function.DoubleUnaryOperator px, java.util.function.DoubleUnaryOperator py,
                                            boolean shade) {
        Samples sampled = new Samples(ys, px, py, shade);
        double ax = from, ay = function.at(from);
        for (int i = 1; i <= SAMPLES; i++) {
            double bx = i == SAMPLES ? to : from + (to - from) * i / SAMPLES;
            if (bx <= ax) continue;
            double by = function.at(bx);
            sampled.segment(function, ax, ay, bx, by, 5);
            ax = bx; ay = by;
        }
        sampled.breakLine();
        return sampled.pieces;
    }

    /** Subdivides curved intervals and leaves a gap when a jump persists at the finest sample. */
    private static final class Samples {
        final double low, high;
        final java.util.function.DoubleUnaryOperator px, py;
        final boolean shade;
        final List<List<Float>> pieces = new ArrayList<>();
        List<Float> piece = new ArrayList<>();

        Samples(double[] ys, java.util.function.DoubleUnaryOperator px, java.util.function.DoubleUnaryOperator py, boolean shade) {
            low = ys[0]; high = ys[1]; this.px = px; this.py = py; this.shade = shade;
        }

        void segment(Function function, double ax, double ay, double bx, double by, int depth) {
            double mx = ax + (bx - ax) / 2, my = function.at(mx);
            if (!shade && (ay < low && by < low && my < low || ay > high && by > high && my > high)) {
                breakLine(); return;
            }
            if (Double.isFinite(ay) && Double.isFinite(by) && Double.isFinite(my)) {
                double amplitude = Math.max(Math.abs(ay), Math.max(Math.abs(by), Math.abs(my)));
                double tolerance = Math.max(Math.ulp(amplitude) * 16,
                    Math.max(Math.abs(by - ay) * .2, Math.min((high - low) * .001, amplitude * .025)));
                double mid = ay / 2 + by / 2;
                if ((shade || inside(ay) && inside(by) && inside(my)) && Math.abs(my - mid) <= tolerance) {
                    point(ax, ay); point(bx, by); return;
                }
            } else if (!Double.isFinite(ay) && !Double.isFinite(by) && !Double.isFinite(my)) {
                breakLine(); return;
            }
            if (depth == 0 || mx == ax || mx == bx) { breakLine(); return; }
            segment(function, ax, ay, mx, my, depth - 1);
            segment(function, mx, my, bx, by, depth - 1);
        }

        private boolean inside(double value) { return value >= low && value <= high; }
        private void point(double x, double y) {
            float a = (float) px.applyAsDouble(x), b = (float) py.applyAsDouble(Math.max(low, Math.min(high, y)));
            int n = piece.size();
            if (n >= 2 && piece.get(n - 2) == a && piece.get(n - 1) == b) return;
            if (n >= 2 * (Board.POINTS - 4)) {
                float lastX = piece.get(n - 2), lastY = piece.get(n - 1);
                breakLine(); piece.add(lastX); piece.add(lastY);
            }
            piece.add(a); piece.add(b);
        }
        void breakLine() { if (piece.size() >= 4) pieces.add(piece); piece = new ArrayList<>(); }
    }

    private static JSONObject stroke(List<Float> points, String color) {
        float[] flat = new float[points.size()]; for (int i = 0; i < flat.length; i++) flat[i] = points.get(i);
        JSONObject made = Board.stroke(color, 4.5f, false, flat, Board.AI); Jsons.put(made, "sharp", true); return made;
    }
    /** A closed outline with straight sides, tinted inside. */
    private static JSONObject region(List<Float> outline, String color, float pen) {
        float[] flat = new float[outline.size()]; for (int i = 0; i < flat.length; i++) flat[i] = outline.get(i);
        JSONObject made = Board.filled(Board.stroke(color, pen, false, flat, Board.AI)); Jsons.put(made, "sharp", true); return made;
    }
    /** A y range that shows the functions over the x range, without letting one steep end flatten everything else. */
    private static double[] fit(List<Curve> curves, double[] xs) {
        List<Double> values = new ArrayList<>();
        for (Curve curve : curves) if (curve.from() <= curve.to()) for (int i = 0; i <= SAMPLES; i++) {
            double y = curve.value(curve.from() + (curve.to() - curve.from()) * i / SAMPLES);
            if (Double.isFinite(y)) values.add(Math.max(-1e300, Math.min(1e300, y)));
        }
        if (values.isEmpty()) return new double[]{-5, 5};
        values.sort(Double::compare);
        double low = values.get((int) (values.size() * .04)), high = values.get((int) Math.min(values.size() - 1, values.size() * .96)), span = Math.max(high - low, 1e-6);
        low = Math.min(low - span * .12, 0); high = Math.max(high + span * .12, 0);
        if (high - low < 1e-3) { low -= 1; high += 1; }
        return new double[]{low, high};
    }

    // ── A table ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A table from rows of cells (text), starting at `top`: its ruled lines and each cell's text centred. Columns are as
     * wide as their widest cell needs and the table as wide as the page allows. The first row is written bold when
     * header is true.
     */
    public static List<JSONObject> table(JSONArray rows, boolean header, float top, float size, Board.Measure measure) {
        return tableLayout(rows, header, top, size, measure).items();
    }
    public static Made tableLayout(JSONArray rows, boolean header, float top, float size, Board.Measure measure) {
        List<JSONObject> out = new ArrayList<>(); Board.Measure ruler = measure == null ? Board.ROUGH : measure;
        int rowCount = rows == null ? 0 : Math.min(ROWS, rows.length()), columns = 0;
        for (int r = 0; r < rowCount; r++) { JSONArray row = rows.optJSONArray(r); if (row != null) columns = Math.max(columns, Math.min(COLUMNS, row.length())); }
        if (rowCount == 0 || columns == 0) return new Made(out, 0);
        float[] widths = new float[columns];
        float pad = size * .42f, room = Board.WIDTH - 2 * Board.MARGIN, total = 0;
        float minimum = Math.min(room / columns, Math.max(92, size * 2.5f));
        for (int c = 0; c < columns; c++) {
            float widest = minimum;
            for (int r = 0; r < rowCount; r++) {
                JSONArray row = rows.optJSONArray(r); String cell = row == null ? "" : row.optString(c);
                widest = Math.max(widest, ruler.size(cell, size, header && r == 0, room)[0] + 2 * pad);
            }
            widths[c] = widest; total += widest;
        }
        if (total > room) {
            float factor = (room - columns * minimum) / (total - columns * minimum);
            for (int c = 0; c < columns; c++) widths[c] = minimum + (widths[c] - minimum) * factor;
            total = room;
        }
        float left = Board.MARGIN;
        float[] edges = new float[rowCount + 1]; edges[0] = top;
        float[][][] boxes = new float[rowCount][columns][];
        for (int r = 0; r < rowCount; r++) {
            JSONArray row = rows.optJSONArray(r);
            float rowHeight = size * 1.9f;
            for (int c = 0; c < columns; c++) {
                String cell = row == null ? "" : row.optString(c).strip();
                float inner = Math.max(Board.MIN_TEXT_WIDTH, widths[c] - 2 * pad);
                float[] box = ruler.size(cell, size, header && r == 0, inner);
                boxes[r][c] = box;
                rowHeight = Math.max(rowHeight, box[1] + 2 * pad);
            }
            edges[r + 1] = edges[r] + rowHeight;
        }
        float bottom = edges[rowCount];
        // Ruled like a table in a tidy notebook: a tinted band under the header, pale rules between rows and columns,
        // and one thin rounded frame around it all.
        float frame = Math.max(1.5f, Math.min(3, size / 15)), corner = Math.min(16, size * .57f);
        if (header) out.add(Board.rounded(Board.bare(Board.filled(Board.shape(Board.RECT, left + frame, top + frame, left + total - frame, edges[1], "blue", 1, false, Board.AI))), Math.max(0, corner - frame)));
        for (int r = 1; r < rowCount; r++) out.add(Board.faint(Board.shape(Board.LINE, left + 4, edges[r], left + total - 4, edges[r], Board.INK, Math.max(1.6f, frame * .85f), false, Board.AI)));
        float x = left;
        for (int c = 0; c < columns; c++) { if (c > 0) out.add(Board.faint(Board.shape(Board.LINE, x, top + 4, x, bottom - 4, Board.INK, Math.max(1.2f, frame * .7f), false, Board.AI))); x += widths[c]; }
        out.add(Board.rounded(Board.shape(Board.RECT, left, top, left + total, bottom, Board.INK, frame, false, Board.AI), corner));
        for (int r = 0; r < rowCount; r++) {
            JSONArray row = rows.optJSONArray(r); x = left;
            for (int c = 0; c < columns; c++) {
                String cell = row == null ? "" : row.optString(c).strip(); boolean bold = header && r == 0;
                // A cell's text keeps a little off the ruled lines even when it has to wrap.
                if (!cell.isEmpty()) {
                    float inner = Math.max(Board.MIN_TEXT_WIDTH, widths[c] - 2 * pad);
                    float[] box = boxes[r][c];
                    out.add(Board.text(x + pad + Math.max(0, (inner - box[0]) / 2), edges[r] + Math.max(0, (edges[r + 1] - edges[r] - box[1]) / 2), cell, size, Board.INK, bold, inner, Board.AI));
                }
                x += widths[c];
            }
        }
        return new Made(out, bottom - top);
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
        float left = 90, right = left + (Board.WIDTH - 180) * compact(scale), y = top + 90, size = Math.max(18, Board.SMALL * .8f * scale);
        java.util.function.DoubleUnaryOperator px = x -> left + (x - xs[0]) / span * (right - left);
        out.add(Board.shape(Board.LINE, left - 40, y, right + 40, y, Board.INK, 3, true, Board.AI));
        double step = step(span, 10);
        ticks(xs[0], xs[1], step, x -> {
            float at = (float) px.applyAsDouble(x); String label = number(x);
            out.add(Board.shape(Board.LINE, at, y - 9, at, y + 9, Board.INK, 2, false, Board.AI));
            out.add(Board.text(at - label.length() * size * .28f, y + 14, label, size, Board.INK, false, 200, Board.AI));
        });
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

    // ── Charts and diagrams ─────────────────────────────────────────────────────────────────────────────

    /** Something drawn in the flow of the page: its items and how tall it is. */
    public record Made(List<JSONObject> items, float height) {}
    public static final int BARS = 10, SLICES = 8, BOXES = 10, EVENTS = 14, BRANCHES = 6, LEAVES = 6;
    private static Board.Measure ruler(Board.Measure measure) { return measure == null ? Board.ROUGH : measure; }
    private static String color(JSONObject spec, int index) { return spec != null && !spec.optString("color").isBlank() ? spec.optString("color") : COLORS[index % COLORS.length]; }
    /** A text centred in a box (at most as wide as the box), and the height it takes. */
    private static float centred(List<JSONObject> out, String text, float left, float top, float width, float size, String color, boolean bold, Board.Measure measure) {
        float[] box = measure.size(text, size, bold, width);
        out.add(Board.text(left + Math.max(0, (width - box[0]) / 2), top, text, size, color, bold, width, Board.AI));
        return box[1];
    }

    /**
     * A chart of numbers from {"kind":"bar|line|pie","title","labels":[…],"values":[…],"unit","color"}: bars or a line
     * over a numbered axis with a pale grid, each value written at its bar or point; or a pie whose slices are tinted and
     * named with their share. Values that are not numbers are left out with their labels.
     */
    public static Made chart(JSONObject spec, float top, float scale, Board.Measure measure) {
        List<JSONObject> out = new ArrayList<>(); Board.Measure ruler = ruler(measure);
        JSONArray labels = spec.optJSONArray("labels"), numbers = spec.optJSONArray("values"); List<String> names = new ArrayList<>(); List<Double> values = new ArrayList<>();
        String kind = spec.optString("kind").strip().toLowerCase(Locale.ROOT); int most = kind.equals("pie") ? SLICES : BARS;
        for (int i = 0; numbers != null && i < numbers.length() && values.size() < most; i++) {
            double value = numbers.optDouble(i, Double.NaN);
            if (!Double.isFinite(value) || Math.abs(value) > 1e150 || kind.equals("pie") && value < 0)
                return new Made(List.of(Board.text(Board.MARGIN, top, "차트의 숫자를 확인해 주세요.", Board.SMALL * scale, "red", false, 880, Board.AI)), 65);
            values.add(value); names.add(labels == null ? "" : labels.optString(i).strip());
        }
        if (values.isEmpty()) return new Made(out, 0);
        float size = Math.max(18, Board.SMALL * .85f * scale), y = top; String unit = spec.optString("unit").strip(), title = spec.optString("title").strip();
        if (!title.isEmpty()) y += centred(out, title, Board.MARGIN, y, Board.WIDTH - 2 * Board.MARGIN, size * 1.1f, Board.INK, true, ruler) + size * .5f;
        if (kind.equals("pie")) {
            double total = 0; for (double value : values) total += Math.max(0, value);
            if (!(total > 0)) return new Made(new ArrayList<>(), 0);
            float radius = 170 * compact(scale), cx = Board.MARGIN + radius + 30, cy = y + radius + 10; double angle = -Math.PI / 2; float legendY = y + 20;
            for (int i = 0; i < values.size(); i++) {
                double share = Math.max(0, values.get(i)) / total; if (share <= 0) continue;
                // The slice: its centre, then its arc in small steps.
                List<Float> outline = new ArrayList<>(); outline.add(cx); outline.add(cy); int steps = Math.max(2, (int) Math.ceil(share * 72));
                for (int k = 0; k <= steps; k++) { double at = angle + share * 2 * Math.PI * k / steps; outline.add((float) (cx + radius * Math.cos(at))); outline.add((float) (cy + radius * Math.sin(at))); }
                outline.add(cx); outline.add(cy); out.add(region(outline, COLORS[i % COLORS.length], 2)); angle += share * 2 * Math.PI;
                float lx = cx + radius + 70; out.add(Board.rounded(Board.filled(Board.shape(Board.RECT, lx, legendY + size * .2f, lx + size * .8f, legendY + size, COLORS[i % COLORS.length], 2, false, Board.AI)), 40));
                String line = (names.get(i).isEmpty() ? "" : names.get(i) + "  ") + number(Math.round(share * 1000) / 10.0) + "%";
                legendY += Math.max(size * 1.5f, ruler.size(line, size, false, Board.WIDTH - Board.MARGIN - lx - size * 1.3f)[1] + size * .3f);
                out.add(Board.text(lx + size * 1.3f, legendY - Math.max(size * 1.5f, ruler.size(line, size, false, Board.WIDTH - Board.MARGIN - lx - size * 1.3f)[1] + size * .3f), line, size, Board.INK, false, Board.WIDTH - Board.MARGIN - lx - size * 1.3f, Board.AI));
            }
            return new Made(out, Math.max(cy + radius + 10, legendY) - top);
        }
        double low = 0, high = 0; for (double value : values) { low = Math.min(low, value); high = Math.max(high, value); }
        if (high == low) high = low + 1;
        double tick = step(high - low, 5); high = Math.ceil(high / tick) * tick; low = Math.floor(low / tick) * tick;
        float left = 120, right = left + (Board.WIDTH - Board.MARGIN - 120) * compact(scale), plotTop = y + size * 1.6f, plotBottom = plotTop + 300 * compact(scale), slot = (right - left) / values.size();
        double floor = low, ceiling = high;
        java.util.function.DoubleUnaryOperator py = v -> plotBottom - (v - floor) / (ceiling - floor) * (plotBottom - plotTop);
        ticks(floor, ceiling, tick, v -> {
            float at = (float) py.applyAsDouble(v); out.add(Board.faint(Board.shape(Board.LINE, left, at, right, at, Board.INK, 1.5f, false, Board.AI)));
            String label = number(v) + (unit.isEmpty() || v != ceiling ? "" : " " + unit); out.add(Board.text(Math.max(0, left - 14 - ruler.size(label, size * .9f, false, 300)[0]), at - size * .65f, label, size * .9f, Board.INK, false, 300, Board.AI));
        });
        float zero = (float) py.applyAsDouble(0); out.add(Board.shape(Board.LINE, left, zero, right, zero, Board.INK, 2.2f, false, Board.AI));
        float tallest = 0; List<Float> line = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            float centre = left + slot * (i + .5f), at = (float) py.applyAsDouble(values.get(i)); String shade = spec.optString("color").isBlank() ? COLORS[i % COLORS.length] : spec.optString("color"), value = number(values.get(i));
            if (kind.equals("line")) { line.add(centre); line.add(at); }
            else out.add(Board.rounded(Board.filled(Board.shape(Board.RECT, centre - slot * .28f, Math.min(at, zero), centre + slot * .28f, Math.max(at, zero), shade, 2, false, Board.AI)), 10));
            float width = ruler.size(value, size * .9f, false, slot)[0];
            out.add(Board.text(centre - width / 2, values.get(i) >= 0 ? at - size * 1.35f : at + 6, value, size * .9f, kind.equals("line") ? color(spec, 0) : shade, false, Math.max(60, slot), Board.AI));
            if (!names.get(i).isEmpty()) tallest = Math.max(tallest, centred(out, names.get(i), centre - slot * .48f, plotBottom + 10, slot * .96f, size * .9f, Board.INK, false, ruler));
        }
        if (kind.equals("line") && line.size() >= 4) {
            JSONObject path = stroke(line, color(spec, 0)); Jsons.put(path, "sharp", true); out.add(path);
            for (int i = 0; i + 1 < line.size(); i += 2) out.add(Board.stroke(color(spec, 0), 16, false, new float[]{line.get(i), line.get(i + 1)}, Board.AI));
        }
        return new Made(out, plotBottom + 10 + tallest + size * .4f - top);
    }

    /**
     * Steps in order, each in a tinted box with an arrow to the next: {"steps":["…", {"text":"…","color":"red"}],
     * "direction":"down|right"}. Across the page when asked and there are few enough short steps; down it otherwise.
     */
    public static Made flow(JSONObject spec, float top, float scale, Board.Measure measure) {
        List<JSONObject> out = new ArrayList<>(); Board.Measure ruler = ruler(measure); JSONArray steps = spec.optJSONArray("steps");
        List<String> texts = new ArrayList<>(), colors = new ArrayList<>();
        for (int i = 0; steps != null && i < steps.length() && texts.size() < BOXES; i++) { JSONObject one = steps.optJSONObject(i); String text = (one == null ? steps.optString(i) : one.optString("text")).strip(); if (text.isEmpty()) continue; texts.add(text); colors.add(one == null || one.optString("color").isBlank() ? "blue" : one.optString("color")); }
        if (texts.isEmpty()) return new Made(out, 0);
        float size = Math.max(18, Board.SMALL * scale), padX = size * .7f, padY = size * .45f, room = Board.WIDTH - 2 * Board.MARGIN, gap = Math.max(37, size * 1.5f);
        boolean across = spec.optString("direction").toLowerCase(Locale.ROOT).startsWith("r") && texts.size() <= 4;
        if (across) {
            float share = (room - gap * (texts.size() - 1)) / texts.size();
            for (String text : texts) if (ruler.size(text, size, false, share - 2 * padX)[1] > size * 2.8f) { across = false; break; }
        }
        if (across) {
            // Boxes as wide as what they say, all alike: on a wide page they do not stretch from edge to edge.
            float share = (room - gap * (texts.size() - 1)) / texts.size(), widest = 0, tallest = 0; float[] heights = new float[texts.size()];
            for (String text : texts) widest = Math.max(widest, ruler.size(text, size, false, share - 2 * padX)[0]);
            float each = Math.min(share, Math.max(size * 4, widest + 2 * padX));
            for (int i = 0; i < texts.size(); i++) { heights[i] = ruler.size(texts.get(i), size, false, each - 2 * padX)[1]; tallest = Math.max(tallest, heights[i]); }
            for (int i = 0; i < texts.size(); i++) {
                float left = Board.MARGIN + i * (each + gap);
                out.add(Board.rounded(Board.filled(Board.shape(Board.RECT, left, top, left + each, top + tallest + 2 * padY, colors.get(i), 2, false, Board.AI)), Math.min(24, (tallest + 2 * padY) / 2)));
                centred(out, texts.get(i), left + padX, top + padY + (tallest - heights[i]) / 2, each - 2 * padX, size, Board.INK, false, ruler);
                if (i + 1 < texts.size()) out.add(Board.shape(Board.LINE, left + each + 6, top + padY + tallest / 2, left + each + gap - 6, top + padY + tallest / 2, Board.INK, 2.4f, true, Board.AI));
            }
            return new Made(out, tallest + 2 * padY);
        }
        float widest = 0; for (String text : texts) widest = Math.max(widest, ruler.size(text, size, false, room - 2 * padX)[0]);
        float width = Math.min(room, Math.max(size * 8, widest + 2 * padX)), left = Board.MARGIN, y = top;
        for (int i = 0; i < texts.size(); i++) {
            float height = ruler.size(texts.get(i), size, false, width - 2 * padX)[1];
            out.add(Board.rounded(Board.filled(Board.shape(Board.RECT, left, y, left + width, y + height + 2 * padY, colors.get(i), 2, false, Board.AI)), Math.min(24, (height + 2 * padY) / 2)));
            centred(out, texts.get(i), left + padX, y + padY, width - 2 * padX, size, Board.INK, false, ruler);
            y += height + 2 * padY;
            if (i + 1 < texts.size()) { out.add(Board.shape(Board.LINE, left + width / 2, y + 5, left + width / 2, y + gap - 5, Board.INK, 2.4f, true, Board.AI)); y += gap; }
        }
        return new Made(out, y - top);
    }

    /** Events in order down a line, each with when it was and what happened: {"events":[{"when":"1392","what":"…"}]}. */
    public static Made timeline(JSONObject spec, float top, float scale, Board.Measure measure) {
        List<JSONObject> out = new ArrayList<>(); Board.Measure ruler = ruler(measure); JSONArray events = spec.optJSONArray("events");
        float size = Math.max(18, Board.SMALL * scale), whenWidth = 0; List<String[]> rows = new ArrayList<>();
        for (int i = 0; events != null && i < events.length() && rows.size() < EVENTS; i++) { JSONObject event = events.optJSONObject(i); if (event == null) continue; String when = event.optString("when").strip(), what = event.optString("what").strip(); if (when.isEmpty() && what.isEmpty()) continue; rows.add(new String[]{when, what, event.optString("color")}); whenWidth = Math.max(whenWidth, ruler.size(when, size, true, 280)[0]); }
        if (rows.isEmpty()) return new Made(out, 0);
        whenWidth = Math.min(280, whenWidth); float lineX = Board.MARGIN + whenWidth + size, textX = lineX + size, room = Board.WIDTH - Board.MARGIN - textX, y = top + size * .3f; int rail = out.size();
        for (String[] row : rows) {
            String color = row[2].isBlank() ? "blue" : row[2];
            float[] when = ruler.size(row[0], size, true, 280); float height = Math.max(when[1], row[1].isEmpty() ? 0 : ruler.size(row[1], size, false, room)[1]);
            if (!row[0].isEmpty()) out.add(Board.text(lineX - size - when[0], y, row[0], size, color, true, 280, Board.AI));
            out.add(Board.stroke(color, 16, false, new float[]{lineX, y + size * .7f}, Board.AI));
            if (!row[1].isEmpty()) out.add(Board.text(textX, y, row[1], size, Board.INK, false, room, Board.AI));
            y += height + size * .7f;
        }
        out.add(rail, Board.faint(Board.shape(Board.LINE, lineX, top, lineX, y - size * .4f, Board.INK, 4, false, Board.AI)));
        return new Made(out, y - top);
    }

    /**
     * One idea and what it branches into: {"root":"…","children":[{"text":"…","children":["…"]}]}. The root's box on top,
     * its branches in rows of up to three under it joined by lines, and each branch's own points listed under it.
     */
    public static Made tree(JSONObject spec, float top, float scale, Board.Measure measure) {
        List<JSONObject> out = new ArrayList<>(); Board.Measure ruler = ruler(measure); String root = spec.optString("root").strip(); JSONArray children = spec.optJSONArray("children");
        float size = Math.max(18, Board.SMALL * scale), padX = size * .7f, padY = size * .45f, room = Board.WIDTH - 2 * Board.MARGIN, y = top;
        if (root.isEmpty()) return new Made(out, 0);
        float rootWidth = Math.min(room, Math.max(size * 7, ruler.size(root, size * 1.05f, true, room - 2 * padX)[0] + 2 * padX)), rootLeft = Board.MARGIN + (room - rootWidth) / 2, rootHeight = ruler.size(root, size * 1.05f, true, rootWidth - 2 * padX)[1];
        out.add(Board.rounded(Board.filled(Board.shape(Board.RECT, rootLeft, y, rootLeft + rootWidth, y + rootHeight + 2 * padY, "purple", 2.4f, false, Board.AI)), 20));
        centred(out, root, rootLeft + padX, y + padY, rootWidth - 2 * padX, size * 1.05f, Board.INK, true, ruler);
        y += rootHeight + 2 * padY; float trunk = Board.WIDTH / 2f;
        List<JSONObject> branches = new ArrayList<>();
        for (int i = 0; children != null && i < children.length() && branches.size() < BRANCHES; i++) { JSONObject child = children.optJSONObject(i); if (child == null && !children.optString(i).isBlank()) child = Jsons.obj("text", children.optString(i)); if (child != null && !child.optString("text").isBlank()) branches.add(child); }
        for (int start = 0; start < branches.size(); start += 3) {
            int count = Math.min(3, branches.size() - start); float gap = size, each = (room - gap * (count - 1)) / count, bus = y + size * .9f, rowTop = bus + size * .9f, tallest = 0;
            out.add(Board.shape(Board.LINE, trunk, y, trunk, bus, Board.INK, 2, false, Board.AI));
            float first = Board.MARGIN + each / 2, last = Board.MARGIN + (count - 1) * (each + gap) + each / 2;
            out.add(Board.shape(Board.LINE, Math.min(first, trunk), bus, Math.max(last, trunk), bus, Board.INK, 2, false, Board.AI));
            for (int k = 0; k < count; k++) {
                JSONObject child = branches.get(start + k); String text = child.optString("text").strip(), color = color(child, start + k);
                float left = Board.MARGIN + k * (each + gap), height = ruler.size(text, size, false, each - 2 * padX)[1], bottom = rowTop + height + 2 * padY;
                out.add(Board.shape(Board.LINE, left + each / 2, bus, left + each / 2, rowTop, Board.INK, 2, false, Board.AI));
                out.add(Board.rounded(Board.filled(Board.shape(Board.RECT, left, rowTop, left + each, bottom, color, 2, false, Board.AI)), 16));
                centred(out, text, left + padX, rowTop + padY, each - 2 * padX, size, Board.INK, false, ruler);
                JSONArray leaves = child.optJSONArray("children"); float leafY = bottom + size * .35f;
                for (int n = 0; leaves != null && n < leaves.length() && n < LEAVES; n++) { String leaf = leaves.optString(n).strip(); if (leaf.isEmpty()) continue; String line = "· " + leaf; out.add(Board.text(left + 6, leafY, line, size * .88f, Board.INK, false, each - 6, Board.AI)); leafY += ruler.size(line, size * .88f, false, each - 6)[1] + size * .15f; }
                tallest = Math.max(tallest, leafY - rowTop);
            }
            y = rowTop + tallest + size * .2f; trunk = Board.WIDTH / 2f;
            // A further row hangs from the same trunk: it runs down the page's middle between the boxes of this row.
            if (start + 3 < branches.size()) out.add(Board.faint(Board.shape(Board.LINE, trunk, bus, trunk, y, Board.INK, 2, false, Board.AI)));
        }
        return new Made(out, y - top);
    }
}
