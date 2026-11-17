package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * seams.ts: small accidental steps (SEAM_MIN up to SEAM_MAX units) in the body's outer silhouette
 * where parts meet, scanned every 0.1 units along each side. Advisory, for strict checks only.
 */
final class BotAvatarSeams {
    private BotAvatarSeams() {}

    static final double SEAM_MIN = 0.3, SEAM_MAX = 2.5;
    private static final double STEP = 0.1, SUDDEN = 2.5, CONTINUES = 0.6, END_SKIP = 1;

    /** Filled intervals of the line y = s under the nonzero rule, as {start, end} pairs. */
    private static List<double[]> spans(double[][][] polys, double s) {
        List<double[]> xs = new ArrayList<>();
        for (double[][] poly : polys) {
            for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
                double ax = poly[j][0], ay = poly[j][1], bx = poly[i][0], by = poly[i][1];
                if ((ay <= s && by > s) || (by <= s && ay > s)) xs.add(new double[] {ax + ((s - ay) * (bx - ax)) / (by - ay), by > ay ? 1 : -1});
            }
        }
        xs.sort((m, n) -> BotAvatarJs.cmp(m[0] - n[0]));
        List<double[]> out = new ArrayList<>();
        int w = 0;
        double start = 0;
        for (double[] x : xs) {
            int was = w;
            w += (int) x[1];
            if (was == 0 && w != 0) start = x[0];
            else if (was != 0 && w == 0) out.add(new double[] {start, x[0]});
        }
        return out;
    }

    private static double[][] profile(double[][][] polys, double lo, double hi, boolean low) {
        List<double[]> rows = new ArrayList<>();
        for (double s = lo + STEP / 2; s < hi; s += STEP) {
            List<double[]> sp = spans(polys, s);
            rows.add(new double[] {s, sp.isEmpty() ? Double.NaN : low ? -sp.get(0)[0] : sp.get(sp.size() - 1)[1]});
        }
        return rows.toArray(new double[0][]);
    }

    private static List<double[]> stepsIn(double[][] p) {
        List<double[]> out = new ArrayList<>();
        int k = 0, len = p.length;
        while (k < len) {
            if (Double.isNaN(p[k][1])) { k++; continue; }
            int a = k;
            while (k < len && !Double.isNaN(p[k][1])) k++;
            int b = k - 1;
            int i = a;
            while (i < b) {
                if (Math.abs(d(p, i)) <= SUDDEN * STEP) { i++; continue; }
                double sign = Math.signum(d(p, i));
                int lo = i, hi = i;
                while (lo > a && Math.signum(d(p, lo - 1)) == sign && Math.abs(d(p, lo - 1)) > CONTINUES * STEP) lo--;
                while (hi + 1 < b && Math.signum(d(p, hi + 1)) == sign && Math.abs(d(p, hi + 1)) > CONTINUES * STEP) hi++;
                double size = p[hi + 1][1] - p[lo][1];
                boolean touchesEnd = (lo - a) * STEP < END_SKIP || (b - hi - 1) * STEP < END_SKIP;
                if (!touchesEnd && Math.abs(size) >= SEAM_MIN && Math.abs(size) < SEAM_MAX) out.add(new double[] {p[i][0], BotAvatarJs.round(size * 100) / 100});
                i = hi + 1;
            }
        }
        return out;
    }

    private static double d(double[][] p, int i) { return p[i + 1][1] - p[i][1]; }

    /** Seams as {side index (0 left, 1 right, 2 top, 3 bottom), at, size}. */
    static List<double[]> silhouetteSeams(double[][][] polys) {
        double[] b = BotAvatarPath.bounds(polys);
        double[][][] cols = new double[polys.length][][];
        for (int i = 0; i < polys.length; i++) {
            cols[i] = new double[polys[i].length][];
            for (int k = 0; k < polys[i].length; k++) cols[i][k] = new double[] {polys[i][k][1], polys[i][k][0]};
        }
        List<double[]> seams = new ArrayList<>();
        double[][][] profiles = {profile(polys, b[1], b[3], true), profile(polys, b[1], b[3], false), profile(cols, b[0], b[2], true), profile(cols, b[0], b[2], false)};
        for (int side = 0; side < 4; side++) for (double[] s : stepsIn(profiles[side])) seams.add(new double[] {side, s[0], s[1]});
        return seams;
    }
}
