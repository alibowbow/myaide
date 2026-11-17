package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a picture the image model drew as a grid of animation frames (a sprite sheet) into same-sized square frames.
 * The model is asked for an even grid, but its cells may sit a little off: each cut is put on the emptiest line (fewest
 * pixels unlike the background) near where an even grid would cut. Each frame is the centre square of its cell, a small
 * inset in from the edges so seams never show, scaled down by area averaging when larger than asked. Pure Java.
 */
public final class SpriteSheet {
    private SpriteSheet() {}
    /** How far from an even cut the emptiest line is looked for, as a share of the cell. */
    private static final double SEARCH = 0.18, INSET = 0.035;

    /** Square frames of one side, ARGB, in reading order (left to right, top to bottom). */
    public record Frames(int side, List<int[]> pixels) {}

    public static Frames frames(int[] argb, int width, int height, int columns, int rows, int maxSide) {
        if (argb == null || width < columns * 8 || height < rows * 8 || argb.length != width * height || columns < 1 || rows < 1)
            throw new IllegalArgumentException("장면을 나눌 그림이 너무 작거나 올바르지 않아요.");
        int background = background(argb, width, height);
        int[] colInk = new int[width], rowInk = new int[height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (differs(argb[y * width + x], background)) { colInk[x]++; rowInk[y]++; }
        }
        int[] xs = cuts(colInk, columns), ys = cuts(rowInk, rows);
        int side = Integer.MAX_VALUE;
        for (int c = 0; c < columns; c++) side = Math.min(side, xs[c + 1] - xs[c]);
        for (int r = 0; r < rows; r++) side = Math.min(side, ys[r + 1] - ys[r]);
        side -= 2 * (int) Math.round(side * INSET);
        if (side < 8) throw new IllegalArgumentException("장면이 너무 작아요.");
        int out = maxSide > 0 ? Math.min(side, maxSide) : side;
        List<int[]> frames = new ArrayList<>(columns * rows);
        for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
            int left = (xs[c] + xs[c + 1] - side) / 2, top = (ys[r] + ys[r + 1] - side) / 2;
            int[] cell = new int[side * side];
            for (int y = 0; y < side; y++) System.arraycopy(argb, (top + y) * width + left, cell, y * side, side);
            frames.add(out == side ? cell : scale(cell, side, out));
        }
        return new Frames(out, frames);
    }

    /** Where to cut a run of length ink.length into parts: 0, the cuts, and the length. */
    static int[] cuts(int[] ink, int parts) {
        int length = ink.length, cell = length / parts, reach = Math.max(1, (int) Math.round(cell * SEARCH));
        int[] at = new int[parts + 1]; at[parts] = length;
        for (int k = 1; k < parts; k++) {
            int even = k * length / parts, best = even, from = Math.max(at[k - 1] + 8, even - reach), to = Math.min(length - 8, even + reach);
            for (int i = from; i <= to; i++) {
                if (ink[i] < ink[best] || ink[i] == ink[best] && Math.abs(i - even) < Math.abs(best - even)) best = i;
            }
            // In a gap several lines wide, cut in its middle.
            int start = best, end = best;
            while (start > from && ink[start - 1] == ink[best]) start--;
            while (end < to && ink[end + 1] == ink[best]) end++;
            at[k] = (start + end) / 2;
        }
        return at;
    }

    /** The background: the commonest colour of the four corners (or their first, when all differ). */
    static int background(int[] argb, int width, int height) {
        int[] corners = {argb[0], argb[width - 1], argb[(height - 1) * width], argb[height * width - 1]};
        int best = corners[0], votes = 0;
        for (int a : corners) {
            int n = 0; for (int b : corners) if (!differs(a, b)) n++;
            if (n > votes) { votes = n; best = a; }
        }
        return best;
    }
    static boolean differs(int a, int b) {
        int d = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) + Math.abs((a & 0xFF) - (b & 0xFF))
            + Math.abs((a >>> 24) - (b >>> 24));
        return d > 48;
    }

    /** Area-average downscale of a square ARGB picture. */
    static int[] scale(int[] source, int from, int to) {
        int[] out = new int[to * to];
        for (int oy = 0; oy < to; oy++) {
            int y0 = oy * from / to, y1 = Math.max(y0 + 1, (oy + 1) * from / to);
            for (int ox = 0; ox < to; ox++) {
                int x0 = ox * from / to, x1 = Math.max(x0 + 1, (ox + 1) * from / to);
                long a = 0, r = 0, g = 0, b = 0; int n = 0;
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                    int c = source[y * from + x];
                    a += c >>> 24; r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
                }
                out[oy * to + ox] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        return out;
    }
}
