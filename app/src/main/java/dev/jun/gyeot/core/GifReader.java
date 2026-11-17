package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads an animated GIF into whole pictures, one per frame, so its frames can be steadied against each other (GifSteady)
 * and written again (GifWriter). GIF87a and GIF89a: global and local colour tables, interlaced frames, transparency, frame
 * rectangles and the three disposal methods. A file that is not a GIF, is cut short or is larger than the limits given is
 * refused with IllegalArgumentException; nothing is read past the array. Pure Java; no Android.
 */
public final class GifReader {
    private GifReader() {}
    /** The frames as the viewer would show them (ARGB, width × height each), each frame's time, and whether any shows through. */
    public record Clip(int width, int height, List<int[]> frames, List<Integer> delaysMs, boolean transparent) {}

    public static Clip read(byte[] gif, int maxSide, int maxFrames) {
        if (gif == null || gif.length < 13) throw bad();
        String head = new String(gif, 0, 6, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!head.equals("GIF89a") && !head.equals("GIF87a")) throw bad();
        int width = u16(gif, 6), height = u16(gif, 8), packed = gif[10] & 0xFF, at = 13;
        if (width < 1 || height < 1 || width > maxSide || height > maxSide) throw new IllegalArgumentException("GIF 크기가 맞지 않아요.");
        int[] global = null;
        if ((packed & 0x80) != 0) { int size = 2 << (packed & 7); global = table(gif, at, size); at += size * 3; }
        int[] canvas = new int[width * height];
        List<int[]> frames = new ArrayList<>(); List<Integer> delays = new ArrayList<>();
        int disposal = 0, delay = 100, clear = -1; boolean transparent = false;
        while (true) {
            if (at >= gif.length) throw bad();
            int block = gif[at++] & 0xFF;
            if (block == 0x3B) break;
            if (block == 0x21) {
                if (at >= gif.length) throw bad();
                int label = gif[at++] & 0xFF;
                if (label == 0xF9 && at + 5 < gif.length && (gif[at] & 0xFF) == 4) {
                    int flags = gif[at + 1] & 0xFF;
                    disposal = (flags >> 2) & 7; delay = u16(gif, at + 2) * 10; clear = (flags & 1) != 0 ? gif[at + 4] & 0xFF : -1;
                }
                at = skip(gif, at);
            } else if (block == 0x2C) {
                if (at + 9 > gif.length) throw bad();
                int left = u16(gif, at), top = u16(gif, at + 2), w = u16(gif, at + 4), h = u16(gif, at + 6), flags = gif[at + 8] & 0xFF; at += 9;
                if (w < 1 || h < 1 || left + w > width || top + h > height) throw bad();
                int[] colors = global;
                if ((flags & 0x80) != 0) { int size = 2 << (flags & 7); colors = table(gif, at, size); at += size * 3; }
                if (colors == null) throw bad();
                if (at >= gif.length) throw bad();
                int codeSize = gif[at++] & 0xFF;
                int end = skip(gif, at);
                byte[] indices = lzw(gif, at, codeSize, w * h); at = end;
                int[] before = disposal == 3 ? canvas.clone() : null;
                boolean interlaced = (flags & 0x40) != 0;
                for (int row = 0; row < h; row++) {
                    int y = top + (interlaced ? interlacedRow(row, h) : row);
                    for (int x = 0; x < w; x++) {
                        int index = indices[row * w + x] & 0xFF;
                        if (index == clear) continue;
                        canvas[y * width + left + x] = index < colors.length ? colors[index] : 0xFF000000;
                    }
                }
                int[] shown = canvas.clone();
                if (!transparent) for (int pixel : shown) if ((pixel >>> 24) != 0xFF) { transparent = true; break; }
                frames.add(shown); delays.add(delay <= 10 ? 100 : delay);
                if (frames.size() > maxFrames) throw new IllegalArgumentException("GIF 장면이 너무 많아요.");
                if (disposal == 2) for (int y = top; y < top + h; y++) java.util.Arrays.fill(canvas, y * width + left, y * width + left + w, 0);
                else if (disposal == 3) canvas = before;
                disposal = 0; delay = 100; clear = -1;
            } else throw bad();
        }
        if (frames.isEmpty()) throw bad();
        return new Clip(width, height, frames, delays, transparent);
    }

    private static IllegalArgumentException bad() { return new IllegalArgumentException("GIF를 읽지 못했어요."); }
    private static int u16(byte[] b, int at) { return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8; }
    private static int[] table(byte[] gif, int at, int size) {
        if (at + size * 3 > gif.length) throw bad();
        int[] colors = new int[size];
        for (int i = 0; i < size; i++) colors[i] = 0xFF000000 | (gif[at + i * 3] & 0xFF) << 16 | (gif[at + i * 3 + 1] & 0xFF) << 8 | (gif[at + i * 3 + 2] & 0xFF);
        return colors;
    }
    /** Past a run of data sub-blocks and its zero terminator. */
    private static int skip(byte[] gif, int at) {
        while (true) {
            if (at >= gif.length) throw bad();
            int size = gif[at++] & 0xFF;
            if (size == 0) return at;
            at += size;
        }
    }
    /** The row of the picture that the n-th stored row of an interlaced frame is. */
    private static int interlacedRow(int n, int height) {
        int first = (height + 7) / 8, second = (height + 3) / 8, third = (height + 1) / 4;
        if (n < first) return n * 8;
        n -= first; if (n < second) return n * 8 + 4;
        n -= second; if (n < third) return n * 4 + 2;
        return (n - third) * 2 + 1;
    }
    /** The colour indices of one frame from its LZW data sub-blocks; a short stream leaves the rest at index 0. */
    private static byte[] lzw(byte[] gif, int at, int minCode, int count) {
        if (minCode < 1 || minCode > 11) throw bad();
        byte[] out = new byte[count];
        int clear = 1 << minCode, stop = clear + 1, size = minCode + 1, next = clear + 2, old = -1, written = 0;
        short[] prefix = new short[4096]; byte[] suffix = new byte[4096], stack = new byte[4097];
        for (int i = 0; i < clear; i++) suffix[i] = (byte) i;
        int bits = 0, held = 0, first = 0, left = 0;
        while (written < count) {
            if (left == 0) { if (at >= gif.length) break; left = gif[at++] & 0xFF; if (left == 0) break; }
            if (at >= gif.length) break;
            held |= (gif[at++] & 0xFF) << bits; bits += 8; left--;
            while (bits >= size && written < count) {
                int code = held & ((1 << size) - 1); held >>>= size; bits -= size;
                if (code == clear) { size = minCode + 1; next = clear + 2; old = -1; continue; }
                if (code == stop) return out;
                if (old == -1) { if (code >= clear) throw bad(); out[written++] = suffix[code]; first = code; old = code; continue; }
                int in = code, top = 0;
                if (code >= next) { if (code > next) throw bad(); stack[top++] = (byte) first; code = old; }
                while (code >= clear) { if (top >= 4096) throw bad(); stack[top++] = suffix[code]; code = prefix[code]; }
                first = suffix[code] & 0xFF; stack[top++] = (byte) first;
                if (next < 4096) { prefix[next] = (short) old; suffix[next] = (byte) first; next++; if (next == (1 << size) && size < 12) size++; }
                old = in;
                while (top > 0 && written < count) out[written++] = stack[--top];
            }
        }
        return out;
    }
}
