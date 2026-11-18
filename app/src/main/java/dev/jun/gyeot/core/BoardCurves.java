package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/** Numerical contours over the reals and curves traced by a parameter. */
final class BoardCurves {
    private BoardCurves() {}

    static void add(
            List<JSONObject> out,
            JSONObject spec,
            double[] xs,
            double[] ys,
            DoubleUnaryOperator px,
            DoubleUnaryOperator py) {
        int first = out.size();
        String mode = spec.optString("mode", "implicit");
        if (mode.equals("implicit")) contour(out, spec.optString("expr"), xs, ys, px, py);
        else if (mode.equals("parametric") || mode.equals("polar"))
            trace(out, spec, mode.equals("polar"), xs, ys, px, py);
        else throw new IllegalArgumentException("그래프 유형을 확인하세요.");
        if (out.size() == first)
            throw new IllegalArgumentException("현재 축 범위에 표시할 곡선이 없어요. 식과 범위를 확인하세요.");
    }

    private record Sample(double x, double y, double z) {}

    private static void contour(
            List<JSONObject> out,
            String expr,
            double[] xs,
            double[] ys,
            DoubleUnaryOperator px,
            DoubleUnaryOperator py) {
        String[] parts = expr.split("=", -1);
        if (parts.length > 2 || parts[0].isBlank())
            throw new IllegalArgumentException("음함수 방정식을 입력하세요.");
        String f = parts.length == 1 ? parts[0] : "(" + parts[0] + ")-(" + parts[1] + ")";
        // Interpolate edge crossings on a triangular grid. Undefined vertices are holes.
        DoubleBinaryOperator function = BoardPlot.implicit(f);
        int n = 60;
        Sample[] previous = null;
        for (int row = 0; row <= n; row++) {
            double y = ys[0] + (ys[1] - ys[0]) * row / n;
            Sample[] line = new Sample[n + 1];
            for (int i = 0; i <= n; i++) {
                double x = xs[0] + (xs[1] - xs[0]) * i / n;
                line[i] = new Sample(x, y, function.applyAsDouble(x, y));
                if (row == 0 || i == 0) continue;
                triangle(out, previous[i - 1], previous[i], line[i], px, py, function);
                triangle(out, previous[i - 1], line[i], line[i - 1], px, py, function);
            }
            previous = line;
        }
    }

    private static void triangle(
            List<JSONObject> out,
            Sample a,
            Sample b,
            Sample c,
            DoubleUnaryOperator px,
            DoubleUnaryOperator py,
            DoubleBinaryOperator function) {
        if (!Double.isFinite(a.z) || !Double.isFinite(b.z) || !Double.isFinite(c.z)) return;
        boolean ap = a.z >= 0, bp = b.z >= 0, cp = c.z >= 0;
        if (ap == bp && bp == cp) return;
        Sample odd = ap == bp ? c : bp == cp ? a : b,
                one = odd == a ? b : a,
                two = odd == c ? b : c;
        double k = root(odd, one, function), l = root(odd, two, function);
        if (!Double.isFinite(k + l)) return;
        float x1 = (float) px.applyAsDouble(odd.x + (one.x - odd.x) * k),
                y1 = (float) py.applyAsDouble(odd.y + (one.y - odd.y) * k);
        float x2 = (float) px.applyAsDouble(odd.x + (two.x - odd.x) * l),
                y2 = (float) py.applyAsDouble(odd.y + (two.y - odd.y) * l);
        if (Math.abs(x1 - x2) + Math.abs(y1 - y2) > .02)
            out.add(Board.shape(Board.LINE, x1, y1, x2, y2, "blue", 4, false, Board.AI));
    }

    /** A sign flip at a pole has no zero. Accept a crossing only when the residual converges. */
    private static double root(Sample a, Sample b, DoubleBinaryOperator f) {
        if (a.z == 0) return 0;
        if (b.z == 0) return 1;
        double lo = 0, hi = 1, best = Double.NaN, residual = Math.min(Math.abs(a.z), Math.abs(b.z));
        double initial = residual;
        double secant = a.z / (a.z - b.z);
        for (int i = 0; i < 15; i++) {
            double t = i == 0 && secant > 0 && secant < 1 ? secant : (lo + hi) * .5;
            double z = f.applyAsDouble(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            if (!Double.isFinite(z)) return Double.NaN;
            if (z == 0) return t;
            if (Math.abs(z) < residual) {
                residual = Math.abs(z);
                best = t;
            }
            if ((z >= 0) == (a.z >= 0)) lo = t;
            else hi = t;
        }
        return residual < initial * .35 ? best : Double.NaN;
    }

    private static BoardPlot.Function param(String expr) {
        return BoardPlot.function(
                expr.replace("θ", "x").replaceAll("(?i)(?<![a-z])(?:theta|t)(?![a-z])", "x"));
    }

    private static void trace(
            List<JSONObject> out,
            JSONObject spec,
            boolean polar,
            double[] xs,
            double[] ys,
            DoubleUnaryOperator px,
            DoubleUnaryOperator py) {
        BoardPlot.Function u = param(spec.optString(polar ? "expr" : "xt")),
                v = polar ? null : param(spec.optString("yt"));
        JSONArray t = spec.optJSONArray("t");
        double from = t == null ? 0 : t.optDouble(0), to = t == null ? 2 * Math.PI : t.optDouble(1);
        if (!(from < to) || !Double.isFinite(to - from))
            throw new IllegalArgumentException("매개변수 범위를 확인하세요.");
        List<Float> path = new ArrayList<>();
        double prevX = Double.NaN, prevY = Double.NaN;
        for (int i = 0; i <= 480; i++) {
            double angle = from + (to - from) * i / 480,
                    r = u.at(angle),
                    x = polar ? r * Math.cos(angle) : r,
                    y = polar ? r * Math.sin(angle) : v.at(angle);
            if (!(x >= xs[0] && x <= xs[1] && y >= ys[0] && y <= ys[1])) {
                flush(out, path);
                prevX = Double.NaN;
                continue;
            }
            if (Math.abs(prevX - x) > (xs[1] - xs[0]) * .15
                    || Math.abs(prevY - y) > (ys[1] - ys[0]) * .15) flush(out, path);
            path.add((float) px.applyAsDouble(x));
            path.add((float) py.applyAsDouble(y));
            prevX = x;
            prevY = y;
        }
        flush(out, path);
    }

    private static void flush(List<JSONObject> out, List<Float> path) {
        if (path.size() >= 4) {
            float[] data = new float[path.size()];
            for (int i = 0; i < data.length; i++) data[i] = path.get(i);
            out.add(Board.stroke("blue", 4, false, data, Board.AI));
        }
        path.clear();
    }
}
