package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * volume.ts: the implied volume of a flat silhouette. Every filled subpath becomes a part (inflated
 * slab, or a solid of revolution about a vertical or a shared horizontal axis) cut once into a
 * stack of z cross-sections (facing the viewer) and a stack of x cross-sections (facing sideways).
 */
final class BotAvatarVolume {
    private BotAvatarVolume() {}

    static final double MAX_PART_DEPTH = 30;
    static final double EYE_BOOST = 1.45;
    static final double EYE_BOOST_PAD = 3;
    static final double FACE_ROOM_TARGET = 4.5;
    private static final double OUTLINE_SLOPE = 4;
    private static final double MAX_X_SLICES = 40;
    private static final double THIN = 4, THIN_REACH = 5, THIN_MARGIN = 2, FINE_Z = 0.6;

    /** One cross-section: 'z' (plane z = at, path in x y) or 'x' (plane x = at, path in u = -z, y). box = {x, y, w, h}. */
    static final class Slice {
        final char axis; final double at; final String d; final List<double[][]> loops; final boolean evenOdd; final double[] box; final double inset;
        Slice(char axis, double at, String d, List<double[][]> loops, boolean evenOdd, double[] box, double inset) {
            this.axis = axis; this.at = at; this.d = d; this.loops = loops; this.evenOdd = evenOdd; this.box = box; this.inset = inset;
        }
        Slice at(double z) { return new Slice(axis, z, d, loops, evenOdd, box, inset); }
    }

    /** Front surface height of a part's kind: (x, y, inward distance) → z. */
    private interface Height { double at(double x, double y, double sd); }

    static final class Part {
        String kind, d;
        double[][][] polys;
        double[] bounds, center;
        double thickness, depth, rxy, rz;
        Double axis;
        List<Slice> zSlices, xSlices;
        Height heightOf;

        /** Front surface height at a point, 0 outside the part. */
        double height(double x, double y) {
            return BotAvatarPath.isInside(polys, x, y) ? heightOf.at(x, y, kind.equals("inflate") ? edgeDistance(polys, x, y) : 0) : 0;
        }
    }

    static final class Volume {
        List<Part> parts;
        double[] bounds;
        int eyePart;
        double[] eyeZ;
        double eyeScale;
        /** Per eye: host normals {x, y, z} under its left edge, center and right edge. */
        double[][][] eyeNormals;
    }

    private static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double len = dx * dx + dy * dy;
        double t = len > 0 ? ((px - ax) * dx + (py - ay) * dy) / len : 0;
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        double ex = ax + t * dx - px, ey = ay + t * dy - py;
        return Math.sqrt(ex * ex + ey * ey);
    }

    static double edgeDistance(double[][][] polys, double x, double y) {
        double best = Double.POSITIVE_INFINITY;
        for (double[][] poly : polys) {
            for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
                double d = segDist(x, y, poly[j][0], poly[j][1], poly[i][0], poly[i][1]);
                if (d < best) best = d;
            }
        }
        return best;
    }

    static double surfaceZ(double depth, double rxy, double rz, double dist) {
        double half = depth / 2;
        if (rxy <= 0 || dist >= rxy) return half;
        double k = 1 - Math.max(0, dist) / rxy;
        return half - rz + Math.sqrt(Math.max(0, 1 - k * k)) * rz;
    }

    static double partDepth(double thickness, double[] b, double volume) {
        double lng = Math.max(b[2] - b[0], b[3] - b[1]);
        double raw = 0.8 * Math.max(thickness, Math.min(0.4 * lng, 2.4 * thickness));
        return Math.min(MAX_PART_DEPTH, Math.max(3, raw)) * volume;
    }

    private static double[] crossings(double[][][] polys, boolean rows, double s) {
        double[] out = new double[8];
        int n = 0;
        int a = rows ? 1 : 0, b = rows ? 0 : 1;
        for (double[][] poly : polys) {
            for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
                double[] p = poly[j], q = poly[i];
                if ((p[a] <= s && q[a] > s) || (q[a] <= s && p[a] > s)) {
                    if (n == out.length) out = Arrays.copyOf(out, n * 2);
                    out[n++] = p[b] + ((s - p[a]) * (q[b] - p[b])) / (q[a] - p[a]);
                }
            }
        }
        out = Arrays.copyOf(out, n);
        Arrays.sort(out);
        return out;
    }

    /** Half-widths about `center` along rows (or columns), or null when not a solid of revolution. */
    private static double[] revolveProfile(double[][][] polys, boolean rows, double lo, double hi, double center) {
        double step = 0.2;
        int n = (int) Math.max(2, Math.ceil((hi - lo) / step) + 1);
        double[] r = new double[n];
        double span = 0;
        List<Double> mids = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            double s = Math.min(hi - 0.01, Math.max(lo + 0.01, lo + k * step));
            double[] xs = crossings(polys, rows, s);
            if (xs.length == 0) continue;
            if (xs.length != 2) return null;
            r[k] = (xs[1] - xs[0]) / 2;
            span = Math.max(span, xs[1] - xs[0]);
            mids.add((xs[0] + xs[1]) / 2);
        }
        double tol = Math.max(0.8, 0.04 * span);
        if (mids.isEmpty()) return null;
        for (double m : mids) if (Math.abs(m - center) > tol) return null;
        return r; // start = lo, step = 0.2
    }

    private static double radiusAt(double[] r, double start, double s) {
        double t = (s - start) / 0.2;
        if (t < 0 || t > r.length - 1) return 0;
        int k = (int) Math.min(r.length - 2, Math.floor(t));
        return r[k] + (r[k + 1] - r[k]) * (t - k);
    }

    /** Horizontal axis of revolution shared by every part, or null. */
    static Double revolveAxis(BotAvatarValidate.Body body) {
        double[] b = body.bounds;
        List<BotAvatarValidate.Subpath> outlines = new ArrayList<>();
        for (BotAvatarValidate.Subpath s : body.subpaths) if (!s.hole) outlines.add(s);
        if (outlines.size() < 2 || b[2] - b[0] < 1.1 * (b[3] - b[1])) return null;
        double axis = (b[1] + b[3]) / 2;
        for (BotAvatarValidate.Subpath o : outlines) {
            double[] ob = BotAvatarPath.bounds(o.polys);
            if (Math.abs((ob[1] + ob[3]) / 2 - axis) > 1.5) return null;
        }
        int filled = 0, off = 0;
        List<Double> list = new ArrayList<>();
        for (double x = b[0] + 0.5; x < b[2]; x += 1) list.add(x);
        double[] xs = new double[list.size()];
        for (int i = 0; i < xs.length; i++) xs[i] = list.get(i);
        boolean[] top = new boolean[xs.length], bottom = new boolean[xs.length];
        for (double y = b[1] + 0.5; y < axis; y += 1) {
            BotAvatarPath.insideRow(body.polys, y, xs, top);
            BotAvatarPath.insideRow(body.polys, 2 * axis - y, xs, bottom);
            for (int k = 0; k < xs.length; k++) {
                if (top[k] || bottom[k]) filled++;
                if (top[k] != bottom[k]) off++;
            }
        }
        return filled > 0 && (double) off / filled < 0.02 ? axis : null;
    }

    /** Sampled signed distance (positive inside) and height over a part's bounds. */
    private static final class Grid {
        double x0, y0, step;
        int nx, ny;
        double[] sd, h;
    }

    /** Edges bucketed on a coarse grid, for nearest-edge queries that visit nearby buckets only. */
    private static final class EdgeIndex {
        private static final double CELL = 3;
        private final int[][] buckets;
        private final int[] sizes;
        private double[] e = new double[256];
        private int edges;
        private final double ox, oy;
        private final int span;

        EdgeIndex(double[][][] polys, double[] b) {
            ox = b[0] - 4; oy = b[1] - 4;
            span = (int) (Math.ceil((Math.max(b[2] - b[0], b[3] - b[1]) + 8) / CELL) + 1);
            buckets = new int[span * span][];
            sizes = new int[span * span];
            for (double[][] poly : polys) {
                for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
                    int k = edges;
                    if (edges + 4 > e.length) e = Arrays.copyOf(e, e.length * 2);
                    e[edges++] = poly[j][0]; e[edges++] = poly[j][1]; e[edges++] = poly[i][0]; e[edges++] = poly[i][1];
                    double x0 = Math.min(poly[j][0], poly[i][0]), x1 = Math.max(poly[j][0], poly[i][0]);
                    double y0 = Math.min(poly[j][1], poly[i][1]), y1 = Math.max(poly[j][1], poly[i][1]);
                    for (int cy = cy(y0); cy <= cy(y1); cy++) {
                        for (int cx = cx(x0); cx <= cx(x1); cx++) {
                            int key = cy * span + cx;
                            if (buckets[key] == null) buckets[key] = new int[4];
                            else if (sizes[key] == buckets[key].length) buckets[key] = Arrays.copyOf(buckets[key], sizes[key] * 2);
                            buckets[key][sizes[key]++] = k;
                        }
                    }
                }
            }
        }

        private int cx(double x) { return (int) Math.max(0, Math.min(span - 1, Math.floor((x - ox) / CELL))); }
        private int cy(double y) { return (int) Math.max(0, Math.min(span - 1, Math.floor((y - oy) / CELL))); }

        double distance(double x, double y) {
            int bx = cx(x), by = cy(y);
            double best = Double.POSITIVE_INFINITY;
            for (int r = 0; r < span; r++) {
                for (int cy = by - r; cy <= by + r; cy++) {
                    if (cy < 0 || cy >= span) continue;
                    boolean edgeRow = cy == by - r || cy == by + r;
                    for (int cx = bx - r; cx <= bx + r; cx += edgeRow ? 1 : (2 * r != 0 ? 2 * r : 1)) {
                        if (cx < 0 || cx >= span) continue;
                        int key = cy * span + cx;
                        int[] list = buckets[key];
                        if (list == null) continue;
                        for (int q = 0, size = sizes[key]; q < size; q++) {
                            int k = list[q];
                            double d = segDist(x, y, e[k], e[k + 1], e[k + 2], e[k + 3]);
                            if (d < best) best = d;
                        }
                    }
                }
                if (best <= r * CELL) break;
            }
            return best;
        }
    }

    private static Grid sampleGrid(double[][][] polys, double[] b, double step, Height height) {
        double pad = 1;
        Grid g = new Grid();
        g.x0 = b[0] - pad; g.y0 = b[1] - pad; g.step = step;
        g.nx = (int) (Math.ceil((b[2] - b[0] + 2 * pad) / step) + 1);
        g.ny = (int) (Math.ceil((b[3] - b[1] + 2 * pad) / step) + 1);
        g.sd = new double[g.nx * g.ny];
        g.h = new double[g.nx * g.ny];
        EdgeIndex index = new EdgeIndex(polys, b);
        double[] xs = new double[g.nx];
        for (int i = 0; i < g.nx; i++) xs[i] = g.x0 + i * step;
        boolean[] inside = new boolean[g.nx];
        for (int j = 0; j < g.ny; j++) {
            double y = g.y0 + j * step;
            BotAvatarPath.insideRow(polys, y, xs, inside);
            for (int i = 0; i < g.nx; i++) {
                double d = index.distance(xs[i], y);
                g.sd[j * g.nx + i] = inside[i] ? d : -d;
                g.h[j * g.nx + i] = inside[i] ? height.at(xs[i], y, d) : 0;
            }
        }
        return g;
    }

    private static double[] loopBox(List<double[][]> loops) {
        double[] b = BotAvatarPath.bounds(loops.toArray(new double[0][][]));
        return new double[] {b[0], b[1], b[2] - b[0], b[3] - b[1]};
    }

    /** A rectangle of grid samples (inclusive) and, for thin boxes, the highest surface over the thin samples. */
    private static final class CellBox {
        int i0, i1, j0, j1;
        double top;
        CellBox(int i0, int i1, int j0, int j1, double top) { this.i0 = i0; this.i1 = i1; this.j0 = j0; this.j1 = j1; this.top = top; }
    }

    private static List<double[][]> levelLoops(Grid g, double level, CellBox box) {
        int i0 = box != null ? Math.max(0, box.i0 - 1) : 0, i1 = box != null ? Math.min(g.nx - 1, box.i1 + 1) : g.nx - 1;
        int j0 = box != null ? Math.max(0, box.j0 - 1) : 0, j1 = box != null ? Math.min(g.ny - 1, box.j1 + 1) : g.ny - 1;
        int nx = i1 - i0 + 1, ny = j1 - j0 + 1;
        double[] values = new double[nx * ny];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                int k = (j + j0) * g.nx + i + i0;
                double v = Math.min(g.h[k] - level, OUTLINE_SLOPE * g.sd[k]);
                if (box != null) {
                    v = Math.min(v, OUTLINE_SLOPE * g.step * Math.min(Math.min(i + i0 - box.i0, box.i1 - i - i0), Math.min(j + j0 - box.j0, box.j1 - j - j0)));
                }
                values[j * nx + i] = v;
            }
        }
        List<double[][]> loops = BotAvatarContour.contourLoops(values, nx, ny, g.x0 + i0 * g.step, g.y0 + j0 * g.step, g.step, 0);
        List<double[][]> out = new ArrayList<>(loops.size());
        for (double[][] l : loops) {
            double[][] s = BotAvatarContour.simplifyLoop(l, 0.04);
            if (s.length >= 3) out.add(s);
        }
        return out;
    }

    private static List<CellBox> thinBoxes(Grid g) {
        int nx = g.nx, ny = g.ny;
        int reach = (int) Math.ceil(THIN_REACH / g.step);
        double[] rows = new double[nx * ny], widest = new double[nx * ny];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                double m = 0;
                for (int k = Math.max(0, i - reach), e = Math.min(nx - 1, i + reach); k <= e; k++) m = Math.max(m, g.sd[j * nx + k]);
                rows[j * nx + i] = m;
            }
        }
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                double m = 0;
                for (int k = Math.max(0, j - reach), e = Math.min(ny - 1, j + reach); k <= e; k++) m = Math.max(m, rows[k * nx + i]);
                widest[j * nx + i] = m;
            }
        }
        boolean[] seen = new boolean[nx * ny];
        int grow = (int) Math.ceil(THIN_MARGIN / g.step);
        List<CellBox> boxes = new ArrayList<>();
        int[] stack = new int[64];
        for (int start = 0; start < nx * ny; start++) {
            if (seen[start] || !(g.sd[start] > 0 && widest[start] < THIN)) continue;
            int bi0 = nx, bi1 = -1, bj0 = ny, bj1 = -1;
            double top = 0;
            int cells = 0, sp = 0;
            stack[sp++] = start;
            seen[start] = true;
            while (sp > 0) {
                int k = stack[--sp];
                int i = k % nx, j = (k - i) / nx;
                cells++;
                top = Math.max(top, g.h[k]);
                bi0 = Math.min(bi0, i); bi1 = Math.max(bi1, i);
                bj0 = Math.min(bj0, j); bj1 = Math.max(bj1, j);
                int[] next = {i > 0 ? k - 1 : -1, i < nx - 1 ? k + 1 : -1, j > 0 ? k - nx : -1, j < ny - 1 ? k + nx : -1};
                for (int n : next) {
                    if (n >= 0 && !seen[n] && g.sd[n] > 0 && widest[n] < THIN) {
                        seen[n] = true;
                        if (sp == stack.length) stack = Arrays.copyOf(stack, sp * 2);
                        stack[sp++] = n;
                    }
                }
            }
            if (cells * g.step * g.step < 2) continue;
            boxes.add(new CellBox(Math.max(0, bi0 - grow), Math.min(nx - 1, bi1 + grow), Math.max(0, bj0 - grow), Math.min(ny - 1, bj1 + grow), top));
        }
        for (int a = 0; a < boxes.size(); a++) {
            for (int b = a + 1; b < boxes.size(); b++) {
                CellBox p = boxes.get(a), q = boxes.get(b);
                if (p.i0 > q.i1 || q.i0 > p.i1 || p.j0 > q.j1 || q.j0 > p.j1) continue;
                boxes.set(a, new CellBox(Math.min(p.i0, q.i0), Math.max(p.i1, q.i1), Math.min(p.j0, q.j0), Math.max(p.j1, q.j1), Math.max(p.top, q.top)));
                boxes.remove(b);
                b = a;
            }
        }
        return boxes;
    }

    private interface EdgeHeight { double at(double x, double y); }

    private static List<double[][]> columnLoops(Grid g, int c0, int c1, EdgeHeight edgeHeight) {
        List<double[][]> loops = new ArrayList<>();
        double[] sd = new double[g.ny], h = new double[g.ny];
        Arrays.fill(sd, Double.NEGATIVE_INFINITY);
        for (int j = 0; j < g.ny; j++) {
            for (int c = c0; c <= c1; c++) {
                int k = j * g.nx + c;
                if (g.sd[k] > sd[j]) sd[j] = g.sd[k];
                if (g.sd[k] > 0 && g.h[k] > h[j]) h[j] = g.h[k];
            }
        }
        int j = 0;
        while (j < g.ny) {
            if (sd[j] <= 0) { j++; continue; }
            int j0 = j;
            while (j < g.ny && sd[j] > 0) j++;
            int j1 = j - 1;
            double yTop = j0 > 0 ? edge(g, sd, j0, j0 - 1) : yAt(g, j0);
            double yBot = j1 < g.ny - 1 ? edge(g, sd, j1, j1 + 1) : yAt(g, j1);
            List<double[]> right = new ArrayList<>();
            right.add(new double[] {j0 > 0 ? rim(g, h, c0, c1, edgeHeight, yTop, j0) : h[j0], yTop});
            for (int k = j0; k <= j1; k++) right.add(new double[] {h[k], yAt(g, k)});
            right.add(new double[] {j1 < g.ny - 1 ? rim(g, h, c0, c1, edgeHeight, yBot, j1) : h[j1], yBot});
            double max = Double.NEGATIVE_INFINITY;
            for (double[] p : right) max = Math.max(max, p[0]);
            if (max < 0.05) continue;
            double[][] loop = new double[right.size() * 2][];
            for (int k = 0; k < right.size(); k++) {
                loop[k] = right.get(k);
                double[] p = right.get(right.size() - 1 - k);
                loop[right.size() + k] = new double[] {-p[0], p[1]};
            }
            loops.add(BotAvatarContour.simplifyLoop(loop, 0.04));
        }
        return loops;
    }

    private static double yAt(Grid g, int jj) { return g.y0 + jj * g.step; }

    private static double edge(Grid g, double[] sd, int inside, int outside) {
        return yAt(g, inside) + ((yAt(g, outside) - yAt(g, inside)) * sd[inside]) / (sd[inside] - sd[outside]);
    }

    private static double rim(Grid g, double[] h, int c0, int c1, EdgeHeight edgeHeight, double y, int j) {
        double top = 0;
        for (int c = c0; c <= c1; c++) top = Math.max(top, edgeHeight.at(g.x0 + c * g.step, y));
        return Math.min(h[j], top);
    }

    private static List<Double> levels(double flat, double round, int steps) {
        List<Double> out = new ArrayList<>();
        for (int k = 1; k < steps; k++) out.add(flat + round * StrictMath.sin(((double) k / steps) * (Math.PI / 2)));
        return out;
    }

    /** buildVolume: `volume` scales every depth (0 = flat). */
    static Volume build(BotAvatar.Definition def, double volume, BotAvatarValidate.Body body) {
        List<BotAvatarValidate.Subpath> outlines = new ArrayList<>(), holes = new ArrayList<>();
        for (BotAvatarValidate.Subpath s : body.subpaths) (s.hole ? holes : outlines).add(s);
        double v = Double.isNaN(volume) || Double.isInfinite(volume) ? 1 : Math.max(0, volume);
        Double xAxis = revolveAxis(body);

        List<Part> parts = new ArrayList<>();
        for (BotAvatarValidate.Subpath o : outlines) {
            List<BotAvatarValidate.Subpath> cuts = new ArrayList<>();
            for (BotAvatarValidate.Subpath h : holes) if (h.probe != null && BotAvatarPath.isInside(o.polys, h.probe[0], h.probe[1])) cuts.add(h);
            List<double[][]> all = new ArrayList<>(Arrays.asList(o.polys));
            for (BotAvatarValidate.Subpath h : cuts) all.addAll(Arrays.asList(h.polys));
            double[][][] polys = all.toArray(new double[0][][]);
            double[] b = BotAvatarPath.bounds(o.polys);
            double[] center = {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2};
            double minDim = Math.min(b[2] - b[0], b[3] - b[1]);
            double step = Math.max(0.25, Math.min(0.45, minDim / 24));

            String kind = "inflate";
            double[] profile = null;
            double profileStart = 0;
            if (xAxis != null) {
                profile = revolveProfile(polys, false, b[0], b[2], xAxis);
                profileStart = b[0];
                if (profile != null) kind = "revolve-x";
            } else if (outlines.size() > 1 && cuts.isEmpty()) {
                profile = revolveProfile(polys, true, b[1], b[3], center[0]);
                profileStart = b[1];
                if (profile != null) kind = "revolve-y";
            }

            double thickness, depth, rxy = 0, rz = 0;
            Height heightOf;
            if (kind.equals("inflate") || profile == null) {
                Grid coarse = sampleGrid(polys, b, Math.max(step, 0.6), (x, y, sd) -> 0);
                double m = 0;
                for (double d : coarse.sd) m = Math.max(m, d);
                thickness = 2 * m;
                depth = partDepth(thickness, b, v);
                rxy = Math.min(thickness / 2, depth / 2) * 0.9;
                rz = (depth / 2) * 0.85;
                double sDepth = depth, sRxy = rxy, sRz = rz;
                heightOf = (x, y, sd) -> surfaceZ(sDepth, sRxy, sRz, sd);
            } else {
                double[] p = profile;
                double start = profileStart;
                double axis = kind.equals("revolve-x") ? xAxis : center[0];
                double radius = Double.NEGATIVE_INFINITY;
                for (double r : p) radius = Math.max(radius, r);
                thickness = 2 * radius;
                depth = 2 * radius * v;
                if (kind.equals("revolve-x")) {
                    heightOf = (x, y, sd) -> { double r = radiusAt(p, start, x), dy = y - axis; return v * Math.sqrt(Math.max(0, r * r - dy * dy)); };
                } else {
                    heightOf = (x, y, sd) -> { double r = radiusAt(p, start, y), dx = x - axis; return v * Math.sqrt(Math.max(0, r * r - dx * dx)); };
                }
            }

            Part part = new Part();
            part.kind = kind; part.polys = polys; part.bounds = b; part.center = center; part.thickness = thickness; part.depth = depth;
            part.rxy = rxy; part.rz = rz; part.heightOf = heightOf;
            StringBuilder coreD = new StringBuilder(o.d);
            for (BotAvatarValidate.Subpath h : cuts) coreD.append(h.d);
            part.d = coreD.toString();
            Slice core = new Slice('z', 0, part.d, Arrays.asList(polys), false, new double[] {b[0], b[1], b[2] - b[0], b[3] - b[1]}, 0);
            List<Slice> zSlices = new ArrayList<>();
            zSlices.add(core);
            List<Slice> xSlices = new ArrayList<>();
            double half = depth / 2;
            if (half > 0.05) {
                Grid g = sampleGrid(polys, b, step, heightOf);
                int steps = kind.equals("inflate")
                    ? (int) Math.max(4, Math.min(14, BotAvatarJs.round(rz / 0.8)))
                    : (int) Math.max(5, Math.min(18, BotAvatarJs.round(half / 1.1)));
                List<Double> ls = new ArrayList<>();
                if (kind.equals("inflate")) { ls.add(half - rz); ls.addAll(levels(half - rz, rz, steps)); }
                else ls.addAll(levels(0, half, steps));
                List<Double> used = new ArrayList<>();
                used.add(0.0);
                for (double level : ls) if (level >= 0.02 && addLevel(g, polys, level, null, zSlices)) used.add(level);
                List<CellBox> thin = kind.equals("inflate") ? thinBoxes(g) : List.of();
                for (CellBox box : thin) {
                    for (int k = 1; k < used.size() && used.get(k - 1) < box.top; k++) {
                        double a = used.get(k - 1), gap = used.get(k) - a;
                        int m = (int) Math.ceil(gap / FINE_Z - 1e-9);
                        for (int q = 1; q < m && a + (gap * q) / m < box.top; q++) addLevel(g, polys, a + (gap * q) / m, box, zSlices);
                    }
                }
                zSlices.sort((m, n) -> BotAvatarJs.cmp(m.at - n.at));

                double width = b[2] - b[0];
                int every = (int) Math.max(1, BotAvatarJs.round(Math.max(1.1, width / MAX_X_SLICES) / g.step));
                EdgeHeight edgeHeight = (x, y) -> heightOf.at(x, y, 0);
                int before = (int) Math.floor((every - 1) / 2.0);
                for (int i = 1; i < g.nx - 1; i += every) {
                    List<double[][]> loops = columnLoops(g, Math.max(0, i - before), Math.min(g.nx - 1, i - before + every - 1), edgeHeight);
                    if (!loops.isEmpty()) xSlices.add(new Slice('x', g.x0 + i * g.step, BotAvatarContour.loopsToPath(loops), loops, true, loopBox(loops), 0));
                }
            }
            part.zSlices = zSlices;
            part.xSlices = xSlices;
            part.axis = kind.equals("revolve-x") ? xAxis : kind.equals("revolve-y") ? Double.valueOf(center[0]) : null;
            parts.add(part);
        }

        Volume vol = new Volume();
        vol.parts = parts;
        vol.bounds = body.bounds;
        vol.eyeScale = boostedEyeScale(body, def.eyes());
        double[][] centers = BotAvatarValidate.eyeCenters(def.eyes());
        int eyePart = 0;
        double bestZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            double z0 = part.height(centers[0][0], centers[0][1]), z1 = part.height(centers[1][0], centers[1][1]);
            if (z0 <= 0 || z1 <= 0) continue;
            double z = Math.min(z0, z1);
            if (z > bestZ) { bestZ = z; eyePart = i; }
        }
        vol.eyePart = eyePart;
        Part host = parts.isEmpty() ? null : parts.get(eyePart);
        double s = vol.eyeScale;
        double w = (BotAvatarValidate.EYE_W * s) / 2 + BotAvatarValidate.GAZE_X * s, hh = (BotAvatarValidate.EYE_H * s) / 2 + BotAvatarValidate.GAZE_Y * s;
        double[] fs = {-1, -0.5, 0, 0.5, 1};
        vol.eyeZ = new double[2];
        for (int e = 0; e < 2; e++) {
            double top = 0;
            for (double fx : fs) for (double fy : fs) top = Math.max(top, host != null ? host.height(centers[e][0] + fx * w, centers[e][1] + fy * hh) : 0);
            vol.eyeZ[e] = top;
        }
        double halfW = (BotAvatarValidate.EYE_W * s) / 2;
        vol.eyeNormals = new double[2][][];
        for (int e = 0; e < 2; e++) {
            double x = centers[e][0], y = centers[e][1];
            vol.eyeNormals[e] = new double[][] {normal(host, x - halfW, y), normal(host, x, y), normal(host, x + halfW, y)};
        }
        return vol;
    }

    private static boolean addLevel(Grid g, double[][][] polys, double level, CellBox box, List<Slice> zSlices) {
        List<double[][]> loops = levelLoops(g, level, box);
        if (loops.isEmpty()) return false;
        double inset = Double.POSITIVE_INFINITY;
        int k = 0;
        for (double[][] loop : loops) {
            for (double[] p : loop) {
                if (k++ % 3 == 0) inset = Math.min(inset, edgeDistance(polys, p[0], p[1]));
            }
        }
        Slice slice = new Slice('z', level, BotAvatarContour.loopsToPath(loops), loops, true, loopBox(loops), inset);
        zSlices.add(slice);
        zSlices.add(slice.at(-level));
        return true;
    }

    private static double[] normal(Part host, double x, double y) {
        if (host == null) return new double[] {0, 0, 1};
        double e = 0.75;
        double gx = (host.height(x + e, y) - host.height(x - e, y)) / (2 * e);
        double gy = (host.height(x, y + e) - host.height(x, y - e)) / (2 * e);
        double n = BotAvatarJs.hypot(gx, gy, 1);
        return new double[] {-gx / n, -gy / n, 1 / n};
    }

    /** Largest eye scale up to EYE_BOOST times the definition's whose drawn eyes keep EYE_BOOST_PAD from the outline. */
    static double boostedEyeScale(BotAvatarValidate.Body body, BotAvatar.Eyes eyes) {
        for (double k : new double[] {EYE_BOOST, 1.38, 1.3, 1.22, 1.15, 1.08}) {
            BotAvatar.Eyes e = new BotAvatar.Eyes(eyes.x(), eyes.y(), eyes.spacing(), eyes.scale() * k);
            if (BotAvatarValidate.drawnEyesFit(body.polys, e, 0, 0, EYE_BOOST_PAD) == null) return eyes.scale() * k;
        }
        return eyes.scale();
    }

    /** faceRoom: {boost, clearance, gaze left, right, up, down, ok (1 or 0)}. */
    static double[] faceRoom(BotAvatar.Definition def, BotAvatarValidate.Body body) {
        double scale = boostedEyeScale(body, def.eyes());
        BotAvatar.Eyes eyes = new BotAvatar.Eyes(def.eyes().x(), def.eyes().y(), def.eyes().spacing(), scale);
        double clearance = BotAvatarValidate.eyeClearance(body.polys, eyes);
        double[] gaze = BotAvatarValidate.gazeRange(body.polys, eyes);
        double boost = BotAvatarJs.round((scale / def.eyes().scale()) * 100) / 100;
        boolean ok = boost >= EYE_BOOST - 1e-9 && clearance >= FACE_ROOM_TARGET && Math.min(Math.min(gaze[0], gaze[1]), Math.min(gaze[2], gaze[3])) == 1;
        return new double[] {boost, clearance, gaze[0], gaze[1], gaze[2], gaze[3], ok ? 1 : 0};
    }
}
