package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the character still across frames an image model drew one cell at a time on a sprite sheet. Each cell is drawn on
 * its own, so the head lands a little higher, lower, larger or smaller from cell to cell, and played as a GIF the picture
 * jumps about. Each frame's scale and shift against one reference frame (the frame most like the rest) is found by
 * matching brightness, coarse to fine; every frame is redrawn with its move, and all are cropped to the square every
 * frame still covers, so no smeared edge shows. Hair, outline and body outweigh the face's changes, so expressions barely
 * sway the match. A frame that matches no better moved than left alone stays as it is. A frame far out of place is
 * moved back like the others; only the crop leaves it out, so its far edge is filled from its own border (a plain
 * background there does not show it). Left where it was, it was the one large jump of the animation. Pure Java; no Android.
 */
public final class FrameAligner {
    private FrameAligner() {}
    /** How far a frame may be moved: drawn this much smaller or larger, shifted this share of the side. */
    static final double MIN_SCALE = 0.8, MAX_SCALE = 1.25, MAX_SHIFT = 0.18;
    /** The crop never goes below this share of the side; the frames moved most are left alone instead. */
    static final double MIN_KEEP = 0.7;
    /** A move must lower the mismatch by this share to be used. */
    static final double GAIN = 0.05;

    /** A frame drawn {@code scale} times as large about its centre, then shifted by dx, dy (shares of the side). */
    public record Move(double scale, double dx, double dy) {
        public static final Move NONE = new Move(1, 0, 0);
        public boolean none() { return scale == 1 && dx == 0 && dy == 0; }
    }
    /** The steadied frames (square, side × side, ARGB, same order) and the move each one got. */
    public record Steady(int side, List<int[]> pixels, List<Move> moves) {}

    /** frames are square ARGB, side × side each. With nothing to move, the same frames come back untouched. */
    public static Steady steady(List<int[]> frames, int side) {
        int n = frames.size();
        List<Move> moves = new ArrayList<>(Collections.nCopies(n, Move.NONE));
        if (n < 2 || side < 48) return new Steady(side, frames, moves);
        for (int[] f : frames) if (f == null || f.length != side * side) throw new IllegalArgumentException("장면 크기가 서로 달라요.");
        int[] sizes = side >= 192 ? new int[] {32, 64, 128, Math.min(side, 256)} : side >= 96 ? new int[] {32, 64, side} : new int[] {32, side};
        float[][][] levels = new float[sizes.length][n][];
        for (int l = 0; l < sizes.length; l++) for (int i = 0; i < n; i++) levels[l][i] = luminance(frames.get(i), side, sizes[l]);
        int ref = medoid(levels[0], sizes[0]), last = sizes.length - 1;
        for (int i = 0; i < n; i++) {
            if (i == ref) continue;
            Move m = coarse(levels[0][ref], levels[0][i], sizes[0]);
            for (int l = 1; l < sizes.length; l++) m = refine(levels[l][ref], levels[l][i], sizes[l], m, l == last ? 0.004 : 0.012, l == last ? 0.5 : 1);
            double still = cost(levels[last][ref], levels[last][i], sizes[last], Move.NONE), moved = cost(levels[last][ref], levels[last][i], sizes[last], m);
            if (moved < still * (1 - GAIN)) moves.set(i, m);
        }
        // Keep enough of the picture: while the shared square is too small, the frame moved most no longer counts for the
        // crop. It is still moved back into place (render fills what it does not cover from its border).
        List<Move> cropped = new ArrayList<>(moves);
        while (keep(common(cropped)) < MIN_KEEP) {
            int worst = -1; double least = 2;
            for (int i = 0; i < n; i++) {
                if (cropped.get(i).none()) continue;
                double own = keep(common(List.of(cropped.get(i))));
                if (own < least) { least = own; worst = i; }
            }
            cropped.set(worst, Move.NONE);
        }
        boolean still = true; for (Move m : moves) still &= m.none();
        if (still) return new Steady(side, frames, moves);
        double[] box = common(cropped);
        int out = Math.max(8, (int) Math.floor(keep(box) * side));
        double ox = (box[0] + box[2]) / 2 * side - out / 2.0, oy = (box[1] + box[3]) / 2 * side - out / 2.0;
        List<int[]> pixels = new ArrayList<>(n);
        for (int i = 0; i < n; i++) pixels.add(render(frames.get(i), side, moves.get(i), ox, oy, out));
        return new Steady(out, pixels, moves);
    }

    /** The part of the reference frame every moved frame still covers: {x0, y0, x1, y1} as shares of the side. */
    static double[] common(List<Move> moves) {
        double[] box = {0, 0, 1, 1};
        for (Move m : moves) {
            box[0] = Math.max(box[0], 0.5 + m.dx - m.scale / 2); box[2] = Math.min(box[2], 0.5 + m.dx + m.scale / 2);
            box[1] = Math.max(box[1], 0.5 + m.dy - m.scale / 2); box[3] = Math.min(box[3], 0.5 + m.dy + m.scale / 2);
        }
        return box;
    }
    private static double keep(double[] box) { return Math.max(0, Math.min(box[2] - box[0], box[3] - box[1])); }

    /** The frame most like all the others, so the moves stay small. */
    static int medoid(float[][] frames, int size) {
        int best = 0; double least = Double.MAX_VALUE;
        for (int i = 0; i < frames.length; i++) {
            double sum = 0; for (int j = 0; j < frames.length; j++) if (j != i) sum += cost(frames[i], frames[j], size, Move.NONE);
            if (sum < least) { least = sum; best = i; }
        }
        return best;
    }

    /** Every scale step and whole-pixel shift in reach, on a small picture. */
    static Move coarse(float[] ref, float[] img, int size) {
        int reach = (int) Math.round(MAX_SHIFT * size), steps = 14;
        Move best = Move.NONE; double least = cost(ref, img, size, best);
        for (int k = 0; k <= steps + 1; k++) {
            double s = k > steps ? 1 : MIN_SCALE * Math.pow(MAX_SCALE / MIN_SCALE, k / (double) steps);
            for (int y = -reach; y <= reach; y++) for (int x = -reach; x <= reach; x++) {
                Move m = new Move(s, x / (double) size, y / (double) size);
                double c = cost(ref, img, size, m);
                if (c < least) { least = c; best = m; }
            }
        }
        return best;
    }
    /** A finer look around a move, on a larger picture: scale ± two steps, shift ± two steps of {@code pixel} pixels. */
    static Move refine(float[] ref, float[] img, int size, Move around, double step, double pixel) {
        Move best = around; double least = cost(ref, img, size, around);
        for (int k = -2; k <= 2; k++) {
            double s = Math.min(MAX_SCALE, Math.max(MIN_SCALE, around.scale * (1 + k * step)));
            for (int y = -2; y <= 2; y++) for (int x = -2; x <= 2; x++) {
                double dx = Math.min(MAX_SHIFT, Math.max(-MAX_SHIFT, around.dx + x * pixel / size));
                double dy = Math.min(MAX_SHIFT, Math.max(-MAX_SHIFT, around.dy + y * pixel / size));
                Move m = new Move(s, dx, dy);
                double c = cost(ref, img, size, m);
                if (c < least) { least = c; best = m; }
            }
        }
        return best;
    }

    /**
     * The mean brightness difference between the reference and the moved frame where they overlap, a little worse the
     * less they overlap (so a match of plain background at the edge does not win), and unusable under 45% overlap.
     */
    static double cost(float[] ref, float[] img, int size, Move m) {
        double c = size / 2.0, inv = 1 / m.scale, sx = m.dx * size, sy = m.dy * size, sum = 0;
        int count = 0, stride = size > 128 ? 2 : 1, all = 0; // every other pixel on large pictures: as precise, four times faster
        for (int y = 0; y < size; y += stride) for (int x = 0; x < size; x += stride) all++;
        for (int y = 0; y < size; y += stride) {
            double qy = c + (y + 0.5 - c - sy) * inv - 0.5;
            if (qy < 0 || qy > size - 1) continue;
            for (int x = 0; x < size; x += stride) {
                double qx = c + (x + 0.5 - c - sx) * inv - 0.5;
                if (qx < 0 || qx > size - 1) continue;
                sum += Math.abs(ref[y * size + x] - sample(img, size, qx, qy)); count++;
            }
        }
        if (count < all * 0.45) return Double.MAX_VALUE;
        double cover = count / (double) all;
        return sum / count * (1 + 0.5 * (1 - cover));
    }
    private static float sample(float[] img, int size, double x, double y) {
        int x0 = Math.min(size - 2, (int) x), y0 = Math.min(size - 2, (int) y);
        double fx = x - x0, fy = y - y0;
        int i = y0 * size + x0;
        return (float) ((img[i] * (1 - fx) + img[i + 1] * fx) * (1 - fy) + (img[i + size] * (1 - fx) + img[i + size + 1] * fx) * fy);
    }

    /** Brightness 0..1 at size × size (area average; transparent parts as over white). */
    static float[] luminance(int[] argb, int side, int size) {
        int[] small = side == size ? argb : SpriteSheet.scale(argb, side, size);
        float[] out = new float[size * size];
        for (int i = 0; i < out.length; i++) {
            int c = small[i], a = c >>> 24;
            double l = 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF);
            out[i] = (float) ((l * a + 255.0 * (255 - a)) / 255 / 255);
        }
        return out;
    }

    /** The frame with its move, cut to out × out from (ox, oy) of the reference, bilinear. */
    static int[] render(int[] frame, int side, Move m, double ox, double oy, int out) {
        int[] dst = new int[out * out];
        double c = side / 2.0, inv = 1 / m.scale, sx = m.dx * side, sy = m.dy * side;
        for (int v = 0; v < out; v++) {
            double qy = Math.min(side - 1, Math.max(0, c + (oy + v + 0.5 - c - sy) * inv - 0.5));
            for (int u = 0; u < out; u++) {
                double qx = Math.min(side - 1, Math.max(0, c + (ox + u + 0.5 - c - sx) * inv - 0.5));
                dst[v * out + u] = argb(frame, side, qx, qy);
            }
        }
        return dst;
    }
    private static int argb(int[] img, int side, double x, double y) {
        int x0 = Math.min(side - 2, (int) x), y0 = Math.min(side - 2, (int) y);
        double fx = x - x0, fy = y - y0;
        int i = y0 * side + x0, a = img[i], b = img[i + 1], c = img[i + side], d = img[i + side + 1], out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            double top = ((a >>> shift) & 0xFF) * (1 - fx) + ((b >>> shift) & 0xFF) * fx;
            double bottom = ((c >>> shift) & 0xFF) * (1 - fx) + ((d >>> shift) & 0xFF) * fx;
            out |= ((int) Math.round(top * (1 - fy) + bottom * fy) & 0xFF) << shift;
        }
        return out;
    }
}
