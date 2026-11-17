package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * path.ts: the restricted SVG path grammar (M L H V C S Q T A Z, absolute and relative), its
 * normalized serialization, flattening into polygons and nonzero fill tests. Also the cubic
 * conversion behind {@link BotAvatar#commands}.
 */
final class BotAvatarPath {
    private BotAvatarPath() {}

    static final int MAX_CHARS = 6000, MAX_SEGMENTS = 400, MAX_SUBPATHS = 16;
    static final double COORD_MIN = -20, COORD_MAX = 120;

    /** A path the grammar or its limits reject; the message is the TypeScript engine's. */
    static final class PathError extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        PathError(String message) { super(message); }
    }

    /** One absolute segment: c is M L C Q A Z; x1 y1 (C, Q), x2 y2 (C), rx ry rot large sweep (A), x y (all but Z). */
    static final class Seg {
        final char c;
        double x1, y1, x2, y2, rx, ry, rot, x, y;
        int large, sweep;
        Seg(char c) { this.c = c; }
        static Seg point(char c, double x, double y) { Seg s = new Seg(c); s.x = x; s.y = y; return s; }
    }

    private static final String SPACE = "\\t\\n\\u000b\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";
    private static final Pattern BAD = Pattern.compile("[^MmLlHhVvCcSsQqTtAaZz0-9.,eE+\\-" + SPACE + "]");
    private static final Pattern TOKEN = Pattern.compile("([MmLlHhVvCcSsQqTtAaZz])|([+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)|([," + SPACE + "]+)");

    private static int argCount(char up) {
        switch (up) {
            case 'M': case 'L': case 'T': return 2;
            case 'H': case 'V': return 1;
            case 'C': return 6;
            case 'S': case 'Q': return 4;
            case 'A': return 7;
            default: return 0;
        }
    }

    /** Tokens: a Character for a command, a Double for a number. */
    private static List<Object> tokenize(String d) {
        Matcher bad = BAD.matcher(d);
        // One UTF-16 unit, as a JS regex without the u flag matches it (half of a surrogate pair).
        if (bad.find()) throw new PathError("unsupported character " + BotAvatarJs.quote(String.valueOf(d.charAt(bad.start()))) + " (allowed commands: M L H V C S Q T A Z)");
        List<Object> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(d);
        int i = 0;
        while (i < d.length()) {
            m.region(i, d.length());
            if (!m.lookingAt()) throw new PathError("cannot parse path near " + BotAvatarJs.quote(d.substring(i, Math.min(d.length(), i + 12))));
            if (m.group(1) != null) tokens.add(m.group(1).charAt(0));
            else if (m.group(2) != null) tokens.add(Double.parseDouble(m.group(2)));
            i = m.end();
        }
        return tokens;
    }

    /** parsePath: absolute M/L/C/Q/A/Z segments, with the engine's limits and implicit closes. */
    static List<Seg> parse(String d) { return parse(d, true); }

    /**
     * With {@code strict} (the engine's parsePath) the size and coordinate limits apply and open
     * subpaths are closed; without it (painting) any size is accepted and open subpaths stay open.
     */
    static List<Seg> parse(String d, boolean strict) {
        if (d == null || BotAvatarJs.trim(d).isEmpty()) throw new PathError("path is empty");
        if (strict && d.length() > MAX_CHARS) throw new PathError("path longer than " + MAX_CHARS + " characters");
        List<Object> tokens = tokenize(d);
        List<Seg> out = new ArrayList<>();
        int i = 0;
        double cx = 0, cy = 0, sx = 0, sy = 0;
        double[] prevC = null, prevQ = null;
        char cmd = 0;
        int subpaths = 0;
        boolean open = false;
        while (i < tokens.size()) {
            Object t = tokens.get(i);
            if (t instanceof Character) {
                cmd = (Character) t;
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    if (!open) throw new PathError("Z without an open subpath");
                    out.add(new Seg('Z'));
                    cx = sx; cy = sy; prevC = prevQ = null; open = false;
                    continue;
                }
            } else if (cmd == 0) {
                throw new PathError("path must start with M");
            } else if (cmd == 'Z' || cmd == 'z') {
                throw new PathError("numbers after Z");
            }
            char up = Character.toUpperCase(cmd);
            boolean rel = cmd != up;
            if (out.isEmpty() && up != 'M') throw new PathError("path must start with M");
            int n = argCount(up);
            double[] args = new double[n];
            for (int k = 0; k < n; k++) {
                Object v = i + k < tokens.size() ? tokens.get(i + k) : null;
                if (!(v instanceof Double)) throw new PathError("command " + cmd + " expects " + n + " numbers");
                double value = (Double) v;
                if (Double.isNaN(value) || Double.isInfinite(value)) throw new PathError("non-finite number");
                args[k] = value;
            }
            i += n;
            double ox = rel ? cx : 0, oy = rel ? cy : 0;
            double[] nextC = null, nextQ = null;
            if (!strict && !open && up != 'M' && !out.isEmpty()) {
                // After Z a drawing command starts a new subpath at the closed one's start.
                out.add(Seg.point('M', cx, cy));
                open = true;
            }
            switch (up) {
                case 'M': {
                    if (open && strict) out.add(new Seg('Z'));
                    cx = args[0] + ox; cy = args[1] + oy; sx = cx; sy = cy;
                    out.add(Seg.point('M', cx, cy));
                    subpaths++; open = true;
                    cmd = rel ? 'l' : 'L';
                    break;
                }
                case 'L': cx = args[0] + ox; cy = args[1] + oy; out.add(Seg.point('L', cx, cy)); break;
                case 'H': cx = args[0] + ox; out.add(Seg.point('L', cx, cy)); break;
                case 'V': cy = args[0] + oy; out.add(Seg.point('L', cx, cy)); break;
                case 'C': {
                    Seg s = new Seg('C');
                    s.x1 = args[0] + ox; s.y1 = args[1] + oy; s.x2 = args[2] + ox; s.y2 = args[3] + oy; s.x = args[4] + ox; s.y = args[5] + oy;
                    out.add(s); nextC = new double[] {s.x2, s.y2}; cx = s.x; cy = s.y; break;
                }
                case 'S': {
                    Seg s = new Seg('C');
                    s.x1 = prevC != null ? 2 * cx - prevC[0] : cx; s.y1 = prevC != null ? 2 * cy - prevC[1] : cy;
                    s.x2 = args[0] + ox; s.y2 = args[1] + oy; s.x = args[2] + ox; s.y = args[3] + oy;
                    out.add(s); nextC = new double[] {s.x2, s.y2}; cx = s.x; cy = s.y; break;
                }
                case 'Q': {
                    Seg s = new Seg('Q');
                    s.x1 = args[0] + ox; s.y1 = args[1] + oy; s.x = args[2] + ox; s.y = args[3] + oy;
                    out.add(s); nextQ = new double[] {s.x1, s.y1}; cx = s.x; cy = s.y; break;
                }
                case 'T': {
                    Seg s = new Seg('Q');
                    s.x1 = prevQ != null ? 2 * cx - prevQ[0] : cx; s.y1 = prevQ != null ? 2 * cy - prevQ[1] : cy;
                    s.x = args[0] + ox; s.y = args[1] + oy;
                    out.add(s); nextQ = new double[] {s.x1, s.y1}; cx = s.x; cy = s.y; break;
                }
                case 'A': {
                    double rx = args[0], ry = args[1], rot = args[2], la = args[3], sw = args[4];
                    if (rx < 0 || ry < 0) throw new PathError("arc radii must be non-negative");
                    if ((la != 0 && la != 1) || (sw != 0 && sw != 1)) throw new PathError("arc flags must be 0 or 1, written as separate numbers");
                    cx = args[5] + ox; cy = args[6] + oy;
                    Seg s = new Seg('A');
                    s.rx = rx; s.ry = ry; s.rot = rot; s.large = (int) la; s.sweep = (int) sw; s.x = cx; s.y = cy;
                    out.add(s);
                    break;
                }
                default:
                    throw new PathError("unsupported command " + cmd);
            }
            prevC = nextC; prevQ = nextQ;
            if (strict && out.size() > MAX_SEGMENTS) throw new PathError("more than " + MAX_SEGMENTS + " segments");
        }
        if (open && strict) out.add(new Seg('Z'));
        if (strict && subpaths > MAX_SUBPATHS) throw new PathError("more than " + MAX_SUBPATHS + " subpaths");
        if (strict) {
            // In each segment's own key order (x1 y1 x2 y2 x y; rx ry x y for arcs), as Object.entries visits them.
            for (Seg s : out) {
                double[] values;
                switch (s.c) {
                    case 'C': values = new double[] {s.x1, s.y1, s.x2, s.y2, s.x, s.y}; break;
                    case 'Q': values = new double[] {s.x1, s.y1, s.x, s.y}; break;
                    case 'A': values = new double[] {s.rx, s.ry, s.x, s.y}; break;
                    case 'Z': values = new double[0]; break;
                    default: values = new double[] {s.x, s.y};
                }
                for (double v : values) {
                    if (v < COORD_MIN || v > COORD_MAX) throw new PathError("coordinate " + BotAvatarJs.num(v) + " far outside the 0..100 viewBox");
                }
            }
        }
        return out;
    }

    private static String fmt(double n) { return BotAvatarJs.fixed(n, 2); }

    /** serializePath: compact absolute path data built only from the parsed numbers. */
    static String serialize(List<Seg> segs) {
        StringBuilder out = new StringBuilder();
        for (Seg s : segs) {
            switch (s.c) {
                case 'M': out.append('M').append(fmt(s.x)).append(' ').append(fmt(s.y)); break;
                case 'L': out.append('L').append(fmt(s.x)).append(' ').append(fmt(s.y)); break;
                case 'C': out.append('C').append(fmt(s.x1)).append(' ').append(fmt(s.y1)).append(' ').append(fmt(s.x2)).append(' ').append(fmt(s.y2))
                    .append(' ').append(fmt(s.x)).append(' ').append(fmt(s.y)); break;
                case 'Q': out.append('Q').append(fmt(s.x1)).append(' ').append(fmt(s.y1)).append(' ').append(fmt(s.x)).append(' ').append(fmt(s.y)); break;
                case 'A': out.append('A').append(fmt(s.rx)).append(' ').append(fmt(s.ry)).append(' ').append(fmt(s.rot)).append(' ').append(s.large)
                    .append(' ').append(s.sweep).append(' ').append(fmt(s.x)).append(' ').append(fmt(s.y)); break;
                default: out.append('Z');
            }
        }
        return out.toString();
    }

    /** Endpoint arc parameters converted to center form (SVG implementation notes, F.6.5); null when the arc is a line or empty. */
    private static double[] arcCenter(double x0, double y0, Seg s) {
        double rx = s.rx, ry = s.ry, x = s.x, y = s.y;
        if (rx == 0 || ry == 0 || (x0 == x && y0 == y)) return null;
        double phi = (s.rot * Math.PI) / 180;
        double cos = StrictMath.cos(phi), sin = StrictMath.sin(phi);
        double dx = (x0 - x) / 2, dy = (y0 - y) / 2;
        double x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        rx = Math.abs(rx); ry = Math.abs(ry);
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) { double k = Math.sqrt(lambda); rx *= k; ry *= k; }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = Math.sqrt(Math.max(0, num / den));
        if (s.large == s.sweep) coef = -coef;
        double cxp = (coef * rx * y1p) / ry, cyp = (-coef * ry * x1p) / rx;
        double cx = cos * cxp - sin * cyp + (x0 + x) / 2;
        double cy = sin * cxp + cos * cyp + (y0 + y) / 2;
        double t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (s.sweep == 0 && dt > 0) dt -= 2 * Math.PI;
        if (s.sweep != 0 && dt < 0) dt += 2 * Math.PI;
        return new double[] {cx, cy, rx, ry, cos, sin, t1, dt};
    }

    private static double ang(double ux, double uy, double vx, double vy) { return StrictMath.atan2(ux * vy - uy * vx, ux * vx + uy * vy); }

    private static void sampleArc(double x0, double y0, Seg s, List<double[]> out) {
        double[] a = arcCenter(x0, y0, s);
        if (a == null) { out.add(new double[] {s.x, s.y}); return; }
        double cx = a[0], cy = a[1], rx = a[2], ry = a[3], cos = a[4], sin = a[5], t1 = a[6], dt = a[7];
        int steps = (int) Math.max(4, Math.ceil((Math.abs(dt) / (Math.PI / 2)) * 6));
        for (int k = 1; k <= steps; k++) {
            double t = t1 + (dt * k) / steps;
            double ex = rx * StrictMath.cos(t), ey = ry * StrictMath.sin(t);
            out.add(new double[] {cos * ex - sin * ey + cx, sin * ex + cos * ey + cy});
        }
    }

    /** flattenPath: one closed polygon per subpath (points as {x, y}), polygons under 3 points dropped. */
    static double[][][] flatten(List<Seg> segs) {
        List<double[][]> polys = new ArrayList<>();
        List<double[]> cur = new ArrayList<>();
        double px = 0, py = 0;
        for (Seg s : segs) {
            switch (s.c) {
                case 'M':
                    if (!cur.isEmpty()) polys.add(cur.toArray(new double[0][]));
                    cur = new ArrayList<>();
                    cur.add(new double[] {s.x, s.y}); px = s.x; py = s.y; break;
                case 'L': cur.add(new double[] {s.x, s.y}); px = s.x; py = s.y; break;
                case 'C': {
                    int n = 12;
                    for (int k = 1; k <= n; k++) {
                        double t = (double) k / n, u = 1 - t;
                        cur.add(new double[] {
                            u * u * u * px + 3 * u * u * t * s.x1 + 3 * u * t * t * s.x2 + t * t * t * s.x,
                            u * u * u * py + 3 * u * u * t * s.y1 + 3 * u * t * t * s.y2 + t * t * t * s.y,
                        });
                    }
                    px = s.x; py = s.y; break;
                }
                case 'Q': {
                    int n = 8;
                    for (int k = 1; k <= n; k++) {
                        double t = (double) k / n, u = 1 - t;
                        cur.add(new double[] {u * u * px + 2 * u * t * s.x1 + t * t * s.x, u * u * py + 2 * u * t * s.y1 + t * t * s.y});
                    }
                    px = s.x; py = s.y; break;
                }
                case 'A': sampleArc(px, py, s, cur); px = s.x; py = s.y; break;
                default:
                    if (!cur.isEmpty()) { polys.add(cur.toArray(new double[0][])); px = cur.get(0)[0]; py = cur.get(0)[1]; }
                    cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty()) polys.add(cur.toArray(new double[0][]));
        List<double[][]> kept = new ArrayList<>();
        for (double[][] p : polys) if (p.length >= 3) kept.add(p);
        return kept.toArray(new double[0][][]);
    }

    /** Nonzero winding number of a point against all subpaths. */
    static int winding(double[][][] polys, double x, double y) {
        int w = 0;
        for (double[][] poly : polys) {
            for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
                double xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
                if (yj <= y) {
                    if (yi > y && (xi - xj) * (y - yj) - (x - xj) * (yi - yj) > 0) w++;
                } else if (yi <= y && (xi - xj) * (y - yj) - (x - xj) * (yi - yj) < 0) w--;
            }
        }
        return w;
    }

    static boolean isInside(double[][][] polys, double x, double y) { return winding(polys, x, y) != 0; }

    /**
     * insideRow: nonzero inside flags for points on one row (xs ascending) from one pass over the
     * edges. Upward and downward crossings are counted separately, which gives the same winding
     * number at every sample as the engine's single sorted list.
     */
    static void insideRow(double[][][] polys, double y, double[] xs, boolean[] out) {
        int nu = 0, nd = 0;
        double[] up = new double[16], down = new double[16];
        for (double[][] poly : polys) {
            for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
                double xj = poly[j][0], yj = poly[j][1], xi = poly[i][0], yi = poly[i][1];
                if (yj <= y && yi > y) {
                    if (nu == up.length) up = Arrays.copyOf(up, nu * 2);
                    up[nu++] = xj + ((y - yj) * (xi - xj)) / (yi - yj);
                } else if (yi <= y && yj > y) {
                    if (nd == down.length) down = Arrays.copyOf(down, nd * 2);
                    down[nd++] = xj + ((y - yj) * (xi - xj)) / (yi - yj);
                }
            }
        }
        Arrays.sort(up, 0, nu);
        Arrays.sort(down, 0, nd);
        int a = 0, b = 0;
        for (int k = 0; k < xs.length; k++) {
            while (a < nu && up[a] < xs[k]) a++;
            while (b < nd && down[b] < xs[k]) b++;
            out[k] = a - b != 0;
        }
    }

    /** {minX, minY, maxX, maxY}; ±Infinity when empty. */
    static double[] bounds(double[][][] polys) {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double[][] p : polys) for (double[] q : p) {
            if (q[0] < b[0]) b[0] = q[0];
            if (q[1] < b[1]) b[1] = q[1];
            if (q[0] > b[2]) b[2] = q[0];
            if (q[1] > b[3]) b[3] = q[1];
        }
        return b;
    }

    /** Filled area on a 1-unit sample grid (nonzero rule). */
    static int filledArea(double[][][] polys, double[] b) {
        int n = 0;
        List<Double> list = new ArrayList<>();
        for (double x = Math.floor(b[0]) + 0.5; x < b[2]; x += 1) list.add(x);
        double[] xs = new double[list.size()];
        for (int i = 0; i < xs.length; i++) xs[i] = list.get(i);
        boolean[] row = new boolean[xs.length];
        for (double y = Math.floor(b[1]) + 0.5; y < b[3]; y += 1) {
            insideRow(polys, y, xs, row);
            for (boolean v : row) if (v) n++;
        }
        return n;
    }

    /** One segment list per subpath, each starting at its M. */
    static List<List<Seg>> splitSubpaths(List<Seg> segs) {
        List<List<Seg>> out = new ArrayList<>();
        for (Seg s : segs) {
            if (s.c == 'M') out.add(new ArrayList<>());
            if (!out.isEmpty()) out.get(out.size() - 1).add(s);
        }
        return out;
    }

    // -----------------------------------------------------------------------------------------
    // Painting commands

    /** BotAvatar.commands: M, L, C and Z only; quads raised to cubics, arcs split into cubics of at most 90°. */
    static List<double[]> commands(String d) {
        List<Seg> segs = parse(d, false);
        List<double[]> out = new ArrayList<>(segs.size() + 8);
        double px = 0, py = 0, sx = 0, sy = 0;
        for (Seg s : segs) {
            switch (s.c) {
                case 'M': out.add(new double[] {'M', s.x, s.y}); px = sx = s.x; py = sy = s.y; break;
                case 'L': out.add(new double[] {'L', s.x, s.y}); px = s.x; py = s.y; break;
                case 'C': out.add(new double[] {'C', s.x1, s.y1, s.x2, s.y2, s.x, s.y}); px = s.x; py = s.y; break;
                case 'Q':
                    out.add(new double[] {'C', px + 2.0 / 3 * (s.x1 - px), py + 2.0 / 3 * (s.y1 - py), s.x + 2.0 / 3 * (s.x1 - s.x), s.y + 2.0 / 3 * (s.y1 - s.y), s.x, s.y});
                    px = s.x; py = s.y; break;
                case 'A': arcCubics(px, py, s, out); px = s.x; py = s.y; break;
                default: out.add(new double[] {'Z'}); px = sx; py = sy;
            }
        }
        return out;
    }

    private static void arcCubics(double x0, double y0, Seg s, List<double[]> out) {
        if (x0 == s.x && y0 == s.y) return; // an arc to its own start draws nothing
        double[] a = arcCenter(x0, y0, s);
        if (a == null) { out.add(new double[] {'L', s.x, s.y}); return; }
        double cx = a[0], cy = a[1], rx = a[2], ry = a[3], cos = a[4], sin = a[5], t1 = a[6], dt = a[7];
        int n = Math.max(1, (int) Math.ceil(Math.abs(dt) / (Math.PI / 2) - 1e-9));
        double step = dt / n, k = 4.0 / 3 * Math.tan(step / 4);
        double ax = x0, ay = y0;
        for (int i = 0; i < n; i++) {
            double ta = t1 + step * i, tb = i == n - 1 ? t1 + dt : t1 + step * (i + 1);
            double ca = Math.cos(ta), sa = Math.sin(ta), cb = Math.cos(tb), sb = Math.sin(tb);
            // Tangents d/dt of (rx cos t, ry sin t), rotated into place.
            double dax = -rx * sa, day = ry * ca, dbx = -rx * sb, dby = ry * cb;
            double bx = i == n - 1 ? s.x : cos * rx * cb - sin * ry * sb + cx, by = i == n - 1 ? s.y : sin * rx * cb + cos * ry * sb + cy;
            out.add(new double[] {'C',
                ax + k * (cos * dax - sin * day), ay + k * (sin * dax + cos * day),
                bx - k * (cos * dbx - sin * dby), by - k * (sin * dbx + cos * dby),
                bx, by});
            ax = bx; ay = by;
        }
    }
}
