package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** contour.ts: marching-squares iso-contours assembled into closed loops, Ramer-Douglas-Peucker, compact loop paths. */
final class BotAvatarContour {
    private BotAvatarContour() {}

    /** Closed loops where the row-major field crosses `level` (inside = value ≥ level). */
    static List<double[][]> contourLoops(double[] values, int nx, int ny, double x0, double y0, double step, double level) {
        int keys = 2 * nx * ny;
        // Up to four neighbors per edge key, in link order; keys in the order they were first linked.
        int[] links = new int[keys * 4], count = new int[keys];
        int[] order = new int[64];
        int ordered = 0;
        for (int j = 0; j < ny - 1; j++) {
            for (int i = 0; i < nx - 1; i++) {
                double atl = values[j * nx + i] - level, atr = values[j * nx + i + 1] - level;
                double abr = values[(j + 1) * nx + i + 1] - level, abl = values[(j + 1) * nx + i] - level;
                boolean tl = atl >= 0, tr = atr >= 0, br = abr >= 0, bl = abl >= 0;
                int code = (tl ? 8 : 0) | (tr ? 4 : 0) | (br ? 2 : 0) | (bl ? 1 : 0);
                if (code == 0 || code == 15) continue;
                int top = 2 * (j * nx + i), bottom = 2 * ((j + 1) * nx + i);
                int left = 2 * (j * nx + i) + 1, right = 2 * (j * nx + i + 1) + 1;
                int p1, q1, p2 = -1, q2 = -1;
                if (code == 10 || code == 5) {
                    boolean center = (atl + atr + abr + abl) / 4 >= 0;
                    if ((code == 10) == center) { p1 = top; q1 = right; p2 = bottom; q2 = left; }
                    else { p1 = left; q1 = top; p2 = right; q2 = bottom; }
                } else {
                    int[] crossed = new int[2];
                    int c = 0;
                    if (tl != tr) crossed[c++] = top;
                    if (tr != br) crossed[c++] = right;
                    if (br != bl && c < 2) crossed[c++] = bottom;
                    if (bl != tl && c < 2) crossed[c++] = left;
                    p1 = crossed[0]; q1 = crossed[1];
                }
                for (int pair = 0; pair < 2; pair++) {
                    int p = pair == 0 ? p1 : p2, q = pair == 0 ? q1 : q2;
                    if (p < 0) break;
                    for (int key : new int[] {p, q}) {
                        if (count[key] == 0) {
                            if (ordered == order.length) order = Arrays.copyOf(order, ordered * 2);
                            order[ordered++] = key;
                        }
                    }
                    links[p * 4 + count[p]++] = q;
                    links[q * 4 + count[q]++] = p;
                }
            }
        }

        List<double[][]> loops = new ArrayList<>();
        boolean[] seen = new boolean[keys];
        for (int o = 0; o < ordered; o++) {
            int start = order[o];
            if (seen[start]) continue;
            List<double[]> loop = new ArrayList<>();
            int prev = -1, cur = start;
            while (!seen[cur]) {
                seen[cur] = true;
                loop.add(point(values, nx, x0, y0, step, level, cur));
                int next = -1;
                for (int c = 0; c < count[cur]; c++) {
                    int nb = links[cur * 4 + c];
                    if (nb != prev && !seen[nb]) { next = nb; break; }
                }
                if (next < 0) break;
                prev = cur; cur = next;
            }
            if (loop.size() >= 3) loops.add(loop.toArray(new double[0][]));
        }
        return loops;
    }

    private static double[] point(double[] values, int nx, double x0, double y0, double step, double level, int key) {
        int idx = key >> 1, i = idx % nx, j = (idx - i) / nx;
        double a = values[j * nx + i] - level;
        if ((key & 1) != 0) {
            double b = values[(j + 1) * nx + i] - level, t = a / (a - b);
            return new double[] {x0 + i * step, y0 + (j + t) * step};
        }
        double b = values[j * nx + i + 1] - level, t = a / (a - b);
        return new double[] {x0 + (i + t) * step, y0 + j * step};
    }

    private static double perpendicular(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double len = BotAvatarJs.hypot(dx, dy);
        if (len == 0) return BotAvatarJs.hypot(p[0] - a[0], p[1] - a[1]);
        return Math.abs(dx * (a[1] - p[1]) - dy * (a[0] - p[0])) / len;
    }

    /** Ramer-Douglas-Peucker on a closed loop. */
    static double[][] simplifyLoop(double[][] loop, double tolerance) {
        if (loop.length <= 8) return loop;
        int far = 0;
        double best = -1;
        for (int k = 1; k < loop.length; k++) {
            double d = BotAvatarJs.hypot(loop[k][0] - loop[0][0], loop[k][1] - loop[0][1]);
            if (d > best) { best = d; far = k; }
        }
        boolean[] keep = new boolean[loop.length];
        keep[0] = keep[far] = true;
        int[] stack = new int[16];
        int top = 0;
        stack[top++] = 0; stack[top++] = far; stack[top++] = far; stack[top++] = loop.length;
        while (top > 0) {
            int e = stack[--top], s = stack[--top];
            double[] end = loop[e % loop.length];
            int idx = -1;
            double max = tolerance;
            for (int k = s + 1; k < e; k++) {
                double d = perpendicular(loop[k], loop[s], end);
                if (d > max) { max = d; idx = k; }
            }
            if (idx >= 0) {
                keep[idx] = true;
                if (top + 4 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
                stack[top++] = s; stack[top++] = idx; stack[top++] = idx; stack[top++] = e;
            }
        }
        int kept = 0;
        for (boolean k : keep) if (k) kept++;
        double[][] out = new double[kept][];
        for (int k = 0, o = 0; k < loop.length; k++) if (keep[k]) out[o++] = loop[k];
        return out;
    }

    private static String n1(double v) { return BotAvatarJs.fixed(v, 1); }

    /** Compact closed path from loops (draw with fill-rule evenodd). */
    static String loopsToPath(List<double[][]> loops) {
        StringBuilder out = new StringBuilder();
        for (double[][] loop : loops) {
            out.append('M').append(n1(loop[0][0])).append(' ').append(n1(loop[0][1])).append('L');
            for (int k = 1; k < loop.length; k++) {
                if (k > 1) out.append(' ');
                out.append(n1(loop[k][0])).append(' ').append(n1(loop[k][1]));
            }
            out.append('Z');
        }
        return out.toString();
    }
}
