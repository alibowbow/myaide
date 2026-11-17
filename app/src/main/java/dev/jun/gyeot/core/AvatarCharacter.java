package dev.jun.gyeot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A living avatar from a picture the user made or uploaded (the profile picture): the character's silhouette as one
 * BotAvatar outline in the 0..100 box, a place for the eyes, and where the picture itself sits in that box so the painter
 * can lay it over the front of the volume. Pure Java on ARGB pixels, so it runs (and is tested) without Android.
 *
 * The subject is the picture's opaque pixels when it has transparency, else everything the background colour, flooded in
 * from the edges, does not reach. Holes are filled (the engine would cut them through the body), the largest piece is
 * kept, and its outline is traced and simplified to at most MAX_POINTS straight segments.
 */
public final class AvatarCharacter {
    /** The box the outline is fitted into (the contract keeps geometry within 8..92). */
    public static final double FIT_MIN = 10, FIT_SIZE = 80;
    public static final int MAX_POINTS = 180, MAX_SIDE = 1024;
    private AvatarCharacter() {}

    /**
     * path: the outline (one clockwise subpath, M/L/Z); eyes: {x, y, spacing, scale}; texture: where the whole picture lies in
     * the box (x, y, width, height); shade: the colour of the character's sides; coverage: subject share of the picture.
     */
    public record Result(String path, double eyeX, double eyeY, double eyeSpacing, double eyeScale, double textureX, double textureY,
            double textureWidth, double textureHeight, int shade, double coverage, boolean transparent) {}

    /** The subject mask, true where the character is. Throws IllegalArgumentException with a Korean reason. */
    public static boolean[] mask(int[] argb, int width, int height) {
        check(argb, width, height);
        int n = width * height, transparentBorder = 0, border = 0, transparent = 0;
        for (int i = 0; i < n; i++) if ((argb[i] >>> 24) < 128) transparent++;
        for (int i : borderIndexes(width, height)) { border++; if ((argb[i] >>> 24) < 128) transparentBorder++; }
        boolean[] subject = new boolean[n];
        if (transparent >= n * 0.03 && transparentBorder >= border * 0.5) {
            for (int i = 0; i < n; i++) subject[i] = (argb[i] >>> 24) >= 128;
        } else {
            int reference = background(argb, width, height);
            double tolerance = tolerance(argb, width, height, reference);
            boolean[] back = new boolean[n]; ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int i : borderIndexes(width, height)) if (!back[i] && distance(argb[i], reference) <= tolerance) { back[i] = true; queue.add(i); }
            while (!queue.isEmpty()) {
                int i = queue.poll(), x = i % width, y = i / width;
                for (int k = 0; k < 4; k++) {
                    int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    int j = ny * width + nx;
                    if (!back[j] && distance(argb[j], reference) <= tolerance) { back[j] = true; queue.add(j); }
                }
            }
            for (int i = 0; i < n; i++) subject[i] = !back[i];
        }
        keepLargest(subject, width, height);
        fillHoles(subject, width, height);
        close(subject, width, height, Math.max(1, Math.min(width, height) / 170));
        keepLargest(subject, width, height);
        fillHoles(subject, width, height);
        int count = 0; for (boolean b : subject) if (b) count++;
        double coverage = count / (double) n;
        if (coverage < 0.04) throw new IllegalArgumentException("그림에서 캐릭터를 찾지 못했어요. 캐릭터가 크게 보이고 배경이 단순한 그림(또는 배경이 투명한 PNG)을 써 주세요.");
        if (coverage > 0.93) throw new IllegalArgumentException("배경과 캐릭터를 구분하지 못했어요. 배경이 단색이거나 투명한 그림을 써 주세요.");
        return subject;
    }

    /** The whole conversion. Throws IllegalArgumentException with a Korean reason when the picture cannot become an avatar. */
    public static Result trace(int[] argb, int width, int height) {
        boolean[] subject = mask(argb, width, height);
        boolean transparent = false; int clear = 0; for (int c : argb) if ((c >>> 24) < 128) clear++;
        transparent = clear >= argb.length * 0.03;
        List<double[]> outline = outline(subject, width, height);
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (double[] p : outline) { x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]); x1 = Math.max(x1, p[0]); y1 = Math.max(y1, p[1]); }
        double bw = Math.max(1, x1 - x0), bh = Math.max(1, y1 - y0), s = FIT_SIZE / Math.max(bw, bh);
        double ox = FIT_MIN + (FIT_SIZE - bw * s) / 2 - x0 * s, oy = FIT_MIN + (FIT_SIZE - bh * s) / 2 - y0 * s;
        if (Math.min(bw, bh) * s < 18) throw new IllegalArgumentException("캐릭터가 너무 가늘어서 눈을 넣을 자리가 없어요. 더 통통한 모습의 그림을 써 주세요.");
        double epsilon = Math.max(0.6, Math.max(bw, bh) / 260.0);
        List<double[]> simple = simplify(outline, epsilon);
        while (simple.size() > MAX_POINTS) { epsilon *= 1.3; simple = simplify(outline, epsilon); }
        if (signedArea(simple) < 0) java.util.Collections.reverse(simple); // clockwise on screen (y down) is positive
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < simple.size(); i++) {
            double[] p = simple.get(i);
            path.append(i == 0 ? "M" : "L").append(num(ox + p[0] * s)).append(' ').append(num(oy + p[1] * s));
        }
        path.append('Z');
        if (Math.abs(signedArea(simple)) * s * s < 700) throw new IllegalArgumentException("캐릭터가 너무 작게 잡혔어요. 캐릭터가 크게 보이는 그림을 써 주세요.");
        double[] eyes = eyes(subject, width, height, s, ox, oy, x0, y0, x1, y1);
        return new Result(path.toString(), eyes[0], eyes[1], eyes[2], eyes[3], ox, oy, width * s, height * s,
            shade(argb, subject, width, height), countTrue(subject) / (double) subject.length, transparent);
    }

    /** A BotAvatar definition JSON for a traced picture. */
    public static org.json.JSONObject definition(Result r, String name) {
        String label = name == null || name.isBlank() ? "My Character" : name.strip();
        if (label.codePointCount(0, label.length()) > 40) label = label.substring(0, label.offsetByCodePoints(0, 40));
        return Jsons.obj("version", 1, "id", "my-character", "name", label, "body", Jsons.obj("path", r.path()),
            "eyes", Jsons.obj("x", round(r.eyeX()), "y", round(r.eyeY()), "spacing", round(r.eyeSpacing()), "scale", round(r.eyeScale())));
    }

    // ── Mask helpers ────────────────────────────────────────────────────────

    private static void check(int[] argb, int width, int height) {
        if (argb == null || width < 16 || height < 16 || width > MAX_SIDE || height > MAX_SIDE || argb.length != width * height)
            throw new IllegalArgumentException("그림 크기를 확인하지 못했어요.");
    }
    private static int[] borderIndexes(int w, int h) {
        int[] out = new int[2 * w + 2 * (h - 2)]; int k = 0;
        for (int x = 0; x < w; x++) { out[k++] = x; out[k++] = (h - 1) * w + x; }
        for (int y = 1; y < h - 1; y++) { out[k++] = y * w; out[k++] = y * w + w - 1; }
        return out;
    }
    /** The commonest border colour (4 bits a channel), averaged over the border pixels in that bucket. */
    static int background(int[] argb, int w, int h) {
        int[] counts = new int[4096]; long[] r = new long[4096], g = new long[4096], b = new long[4096];
        for (int i : borderIndexes(w, h)) {
            int c = argb[i], q = ((c >> 20) & 0xf) << 8 | ((c >> 12) & 0xf) << 4 | ((c >> 4) & 0xf);
            counts[q]++; r[q] += (c >> 16) & 255; g[q] += (c >> 8) & 255; b[q] += c & 255;
        }
        int best = 0; for (int q = 1; q < 4096; q++) if (counts[q] > counts[best]) best = q;
        int n = Math.max(1, counts[best]);
        return 0xff000000 | (int) (r[best] / n) << 16 | (int) (g[best] / n) << 8 | (int) (b[best] / n);
    }
    /** How far a background pixel may be from the reference: wider for a noisy or graded border. */
    static double tolerance(int[] argb, int w, int h, int reference) {
        double sum = 0; int n = 0;
        for (int i : borderIndexes(w, h)) { double d = distance(argb[i], reference); if (d < 90) { sum += d; n++; } }
        double mean = n == 0 ? 0 : sum / n;
        return Math.max(30, Math.min(70, 26 + mean * 2.2));
    }
    static double distance(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return Math.sqrt(dr * dr * 0.3 + dg * dg * 0.59 + db * db * 0.11) * 1.7;
    }
    private static int countTrue(boolean[] mask) { int n = 0; for (boolean b : mask) if (b) n++; return n; }
    /** Keeps only the largest 4-connected piece. */
    static void keepLargest(boolean[] mask, int w, int h) {
        int[] label = new int[mask.length]; int next = 0, best = 0, bestSize = 0; ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s = 0; s < mask.length; s++) {
            if (!mask[s] || label[s] != 0) continue;
            next++; int size = 0; label[s] = next; queue.add(s);
            while (!queue.isEmpty()) {
                int i = queue.poll(); size++; int x = i % w, y = i / w;
                if (x > 0 && mask[i - 1] && label[i - 1] == 0) { label[i - 1] = next; queue.add(i - 1); }
                if (x < w - 1 && mask[i + 1] && label[i + 1] == 0) { label[i + 1] = next; queue.add(i + 1); }
                if (y > 0 && mask[i - w] && label[i - w] == 0) { label[i - w] = next; queue.add(i - w); }
                if (y < h - 1 && mask[i + w] && label[i + w] == 0) { label[i + w] = next; queue.add(i + w); }
            }
            if (size > bestSize) { bestSize = size; best = next; }
        }
        for (int i = 0; i < mask.length; i++) mask[i] = label[i] == best && best != 0;
    }
    /** Background that the border cannot reach becomes subject (the engine would cut holes through the body). */
    static void fillHoles(boolean[] mask, int w, int h) {
        boolean[] outside = new boolean[mask.length]; ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i : borderIndexes(w, h)) if (!mask[i] && !outside[i]) { outside[i] = true; queue.add(i); }
        while (!queue.isEmpty()) {
            int i = queue.poll(), x = i % w, y = i / w;
            if (x > 0 && !mask[i - 1] && !outside[i - 1]) { outside[i - 1] = true; queue.add(i - 1); }
            if (x < w - 1 && !mask[i + 1] && !outside[i + 1]) { outside[i + 1] = true; queue.add(i + 1); }
            if (y > 0 && !mask[i - w] && !outside[i - w]) { outside[i - w] = true; queue.add(i - w); }
            if (y < h - 1 && !mask[i + w] && !outside[i + w]) { outside[i + w] = true; queue.add(i + w); }
        }
        for (int i = 0; i < mask.length; i++) if (!outside[i]) mask[i] = true;
    }
    /** Morphological closing (dilate then erode) by radius pixels: smooths the jagged edge of a flood fill. */
    static void close(boolean[] mask, int w, int h, int radius) {
        boolean[] grown = morph(mask, w, h, radius, true);
        boolean[] shrunk = morph(grown, w, h, radius, false);
        System.arraycopy(shrunk, 0, mask, 0, mask.length);
    }
    private static boolean[] morph(boolean[] mask, int w, int h, int r, boolean dilate) {
        boolean[] row = new boolean[mask.length], out = new boolean[mask.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean v = !dilate;
            for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++) { if (dilate ? mask[y * w + k] : !mask[y * w + k]) { v = dilate; break; } }
            row[y * w + x] = v;
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean v = !dilate;
            for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++) { if (dilate ? row[k * w + x] : !row[k * w + x]) { v = dilate; break; } }
            out[y * w + x] = v;
        }
        return out;
    }

    // ── Outline ─────────────────────────────────────────────────────────────

    /**
     * The outer boundary of the (single, hole-free) subject as pixel-corner points, traced along the edges between subject
     * and background pixels, so a one-pixel subject is a unit square. Collinear steps are merged.
     */
    static List<double[]> outline(boolean[] mask, int w, int h) {
        int start = -1; for (int i = 0; i < mask.length; i++) if (mask[i]) { start = i; break; }
        if (start < 0) throw new IllegalArgumentException("그림에서 캐릭터를 찾지 못했어요.");
        // Walk the boundary with the subject on the right (clockwise on screen), starting at the top edge of the first pixel.
        int sx = start % w, sy = start / w, x = sx, y = sy, dir = 0; // 0 right, 1 down, 2 left, 3 up
        List<double[]> points = new ArrayList<>();
        int guard = 4 * (w + 1) * (h + 1);
        do {
            points.add(new double[]{x, y});
            // At corner (x, y) heading dir: look at the two pixels ahead (left-ahead and right-ahead of the direction).
            int[] next = step(mask, w, h, x, y, dir);
            dir = next[0]; x += dx(dir); y += dy(dir);
            if (--guard < 0) throw new IllegalArgumentException("캐릭터 외곽선을 따라가지 못했어요.");
        } while (x != sx || y != sy); // the first pixel's top-left corner is on the outer boundary exactly once
        List<double[]> merged = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            double[] prev = points.get((i - 1 + points.size()) % points.size()), cur = points.get(i), nxt = points.get((i + 1) % points.size());
            boolean straight = (prev[0] == cur[0] && cur[0] == nxt[0]) || (prev[1] == cur[1] && cur[1] == nxt[1]);
            if (!straight) merged.add(cur);
        }
        return merged;
    }
    private static boolean on(boolean[] mask, int w, int h, int x, int y) { return x >= 0 && y >= 0 && x < w && y < h && mask[y * w + x]; }
    private static int dx(int dir) { return dir == 0 ? 1 : dir == 2 ? -1 : 0; }
    private static int dy(int dir) { return dir == 1 ? 1 : dir == 3 ? -1 : 0; }
    /** From corner (x, y) along dir with the subject on the right: turn right if possible, else straight, else left. */
    private static int[] step(boolean[] mask, int w, int h, int x, int y, int dir) {
        for (int turn : new int[]{1, 0, 3}) {
            int d = (dir + turn) % 4;
            // The edge from (x, y) along d has pixel "right" of it inside and pixel "left" of it outside.
            int rx, ry, lx, ly;
            switch (d) {
                case 0 -> { rx = x; ry = y; lx = x; ly = y - 1; }
                case 1 -> { rx = x - 1; ry = y; lx = x; ly = y; }
                case 2 -> { rx = x - 1; ry = y - 1; lx = x - 1; ly = y; }
                default -> { rx = x; ry = y - 1; lx = x - 1; ly = y - 1; }
            }
            if (on(mask, w, h, rx, ry) && !on(mask, w, h, lx, ly)) return new int[]{d};
        }
        return new int[]{(dir + 2) % 4};
    }
    /** Douglas–Peucker on a closed polygon. */
    static List<double[]> simplify(List<double[]> points, double epsilon) {
        int n = points.size(); if (n < 4) return new ArrayList<>(points);
        int far = 0; double best = -1; double[] a = points.get(0);
        for (int i = 1; i < n; i++) { double d = Math.hypot(points.get(i)[0] - a[0], points.get(i)[1] - a[1]); if (d > best) { best = d; far = i; } }
        boolean[] keep = new boolean[n]; keep[0] = true; keep[far] = true;
        mark(points, 0, far, epsilon, keep); mark(points, far, n, epsilon, keep);
        List<double[]> out = new ArrayList<>(); for (int i = 0; i < n; i++) if (keep[i]) out.add(points.get(i));
        return out;
    }
    private static void mark(List<double[]> points, int from, int to, double epsilon, boolean[] keep) {
        int n = points.size(); double[] a = points.get(from % n), b = points.get(to % n);
        int index = -1; double best = epsilon;
        for (int i = from + 1; i < to; i++) { double d = segment(points.get(i % n), a, b); if (d > best) { best = d; index = i; } }
        if (index < 0) return;
        keep[index % n] = true; mark(points, from, index, epsilon, keep); mark(points, index, to, epsilon, keep);
    }
    private static double segment(double[] p, double[] a, double[] b) {
        double vx = b[0] - a[0], vy = b[1] - a[1], len = vx * vx + vy * vy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / len));
        return Math.hypot(p[0] - a[0] - t * vx, p[1] - a[1] - t * vy);
    }
    /** Shoelace area; positive when the polygon runs clockwise on screen (y pointing down). */
    static double signedArea(List<double[]> p) {
        double sum = 0; for (int i = 0; i < p.size(); i++) { double[] a = p.get(i), b = p.get((i + 1) % p.size()); sum += a[0] * b[1] - b[0] * a[1]; }
        return sum / 2;
    }

    // ── Eyes and colour ─────────────────────────────────────────────────────

    /**
     * Eyes on the head, not the belly: at the largest scale (15 × scale apart) where both eyes keep the drawn eye's clearance
     * from the outline, the highest row that has room, then the roomiest spot just below it, as centred as possible.
     * Returns {x, y, spacing, scale} in box units.
     */
    static double[] eyes(boolean[] mask, int w, int h, double s, double ox, double oy, double x0, double y0, double x1, double y1) {
        double[] clearance = distances(mask, w, h); // pixels to the nearest background pixel
        double cx0 = (x0 + x1) / 2, bw = x1 - x0, bh = y1 - y0, stepY = Math.max(1, bh / 80), stepX = Math.max(1, bw / 80);
        double[] scales = {1.6, 1.4, 1.25, 1.1, 1.0, 0.9, 0.8, 0.7, 0.6};
        // The largest scale each row can hold (with a little spare room), top to bottom.
        java.util.List<double[]> rows = new java.util.ArrayList<>(); double upperBest = 0, anyBest = 0;
        for (double py = y0 + bh * 0.12; py <= y0 + bh * 0.7; py += stepY) {
            double best = 0;
            for (double scale : scales) if (centred(clearance, w, h, cx0, bw, stepX, 15 * scale / s, py, (8.2 * scale + 3) / s * 1.1) != null) { best = scale; break; }
            rows.add(new double[]{py, best}); anyBest = Math.max(anyBest, best);
            if (py <= y0 + bh * 0.45) upperBest = Math.max(upperBest, best);
        }
        double target = Math.max(0.6, 0.7 * (upperBest > 0 ? upperBest : anyBest));
        for (double[] row : rows) {
            if (row[1] < target) continue;
            // The first row with room enough: the head. Settle in its roomiest spot just below, at the largest scale it holds there.
            double scale = 0;
            for (double[] near : rows) if (near[0] >= row[0] && near[0] <= row[0] + bh * 0.12) scale = Math.max(scale, near[1]);
            double need = (8.2 * scale + 3) / s, spacing = 15 * scale / s, bestRoom = -1; double[] best = null;
            for (double[] near : rows) {
                if (near[0] < row[0] || near[0] > row[0] + bh * 0.12) continue;
                double[] spot = centred(clearance, w, h, cx0, bw, stepX, spacing, near[0], need);
                if (spot != null && Math.min(spot[2], need * 2) > bestRoom + 1e-9) { bestRoom = Math.min(spot[2], need * 2); best = spot; }
            }
            if (best != null) return new double[]{ox + best[0] * s, oy + best[1] * s, 15 * scale, scale};
        }
        // Last chance: the most room anywhere in the body at the smallest scale that still validates (eyes + 1.2 on the fill).
        double floor = (5.5 * 0.6 + 1.6) / s, spacing = 9 / s, bestRoom = -1; double[] best = null;
        for (double py = y0 + bh * 0.1; py <= y0 + bh * 0.9; py += stepY) for (double px = x0 + bw * 0.15; px <= x0 + bw * 0.85; px += stepX) {
            double room = Math.min(at(clearance, w, h, px - spacing / 2, py), at(clearance, w, h, px + spacing / 2, py));
            if (room >= floor && room > bestRoom) { bestRoom = room; best = new double[]{px, py}; }
        }
        if (best != null) return new double[]{ox + best[0] * s, oy + best[1] * s, 9, 0.6};
        throw new IllegalArgumentException("캐릭터 안에 눈을 놓을 넓은 자리가 없어요. 몸통이나 얼굴이 크게 보이는 그림을 써 주세요.");
    }
    /** The most centred eye pair on row py whose two eyes both have at least need pixels of room: {x, y, room}, or null. */
    private static double[] centred(double[] clearance, int w, int h, double cx, double bw, double stepX, double spacing, double py, double need) {
        for (double off = 0; off <= bw * 0.22; off += stepX) for (double sign : off == 0 ? new double[]{1} : new double[]{-1, 1}) {
            double px = cx + off * sign, room = Math.min(at(clearance, w, h, px - spacing / 2, py), at(clearance, w, h, px + spacing / 2, py));
            if (room >= need) return new double[]{px, py, room};
        }
        return null;
    }
    private static double at(double[] distance, int w, int h, double x, double y) {
        int ix = (int) Math.round(x), iy = (int) Math.round(y);
        if (ix < 0 || iy < 0 || ix >= w || iy >= h) return 0;
        return distance[iy * w + ix];
    }
    /** Chamfer (3-4) distance from each subject pixel to the nearest non-subject pixel or the picture edge, in pixels. */
    static double[] distances(boolean[] mask, int w, int h) {
        int big = 1 << 28; int[] d = new int[mask.length];
        for (int i = 0; i < mask.length; i++) d[i] = mask[i] ? big : 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x; if (d[i] == 0) continue;
            int v = d[i];
            v = Math.min(v, x > 0 ? d[i - 1] + 3 : 3); v = Math.min(v, y > 0 ? d[i - w] + 3 : 3);
            v = Math.min(v, x > 0 && y > 0 ? d[i - w - 1] + 4 : 4); v = Math.min(v, x < w - 1 && y > 0 ? d[i - w + 1] + 4 : 4);
            d[i] = v;
        }
        for (int y = h - 1; y >= 0; y--) for (int x = w - 1; x >= 0; x--) {
            int i = y * w + x; if (d[i] == 0) continue;
            int v = d[i];
            v = Math.min(v, x < w - 1 ? d[i + 1] + 3 : 3); v = Math.min(v, y < h - 1 ? d[i + w] + 3 : 3);
            v = Math.min(v, x < w - 1 && y < h - 1 ? d[i + w + 1] + 4 : 4); v = Math.min(v, x > 0 && y < h - 1 ? d[i + w - 1] + 4 : 4);
            d[i] = v;
        }
        double[] out = new double[mask.length]; for (int i = 0; i < d.length; i++) out[i] = d[i] / 3.0;
        return out;
    }
    /** The character's edge colour, darkened, for the sides of the volume behind the picture. */
    static int shade(int[] argb, boolean[] mask, int w, int h) {
        double[] distance = distances(mask, w, h); long r = 0, g = 0, b = 0; int n = 0;
        int band = Math.max(2, Math.min(w, h) / 60);
        for (int i = 0; i < mask.length; i++) if (mask[i] && distance[i] <= band) { int c = argb[i]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++; }
        if (n == 0) return 0xff7a6a5a;
        return 0xff000000 | (int) (r / n * 0.72) << 16 | (int) (g / n * 0.72) << 8 | (int) (b / n * 0.72);
    }
    static String num(double v) { double r = Math.round(v * 10) / 10.0; return r == Math.rint(r) ? Long.toString((long) r) : String.format(Locale.ROOT, "%.1f", r); }
    private static double round(double v) { return Math.round(v * 100) / 100.0; }
}
