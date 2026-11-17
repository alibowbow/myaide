package dev.jun.gyeot.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A looping animated GIF (GIF89a) from same-sized ARGB frames, made on the phone where the image service will not send a
 * GIF itself. One global 256-colour palette by median cut over all frames (so colours do not flicker between frames),
 * every frame full size and opaque (semi-transparent pixels are laid on white), LZW as the format specifies, and the
 * NETSCAPE2.0 block so it loops forever. Pure Java; no Android.
 */
public final class GifWriter {
    private GifWriter() {}
    private static final int COLORS = 256, SAMPLES = 65_536, MAX_CODE = 4096;

    /** frames are ARGB, width × height each; delayMs is rounded to centiseconds (at least 2, which every viewer honours). */
    public static byte[] encode(List<int[]> frames, int width, int height, int delayMs) {
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("GIF로 묶을 장면이 없어요.");
        if (width < 1 || height < 1 || width > 65_535 || height > 65_535) throw new IllegalArgumentException("GIF 크기가 올바르지 않아요.");
        for (int[] f : frames) if (f == null || f.length != width * height) throw new IllegalArgumentException("GIF 장면 크기가 서로 달라요.");
        List<int[]> opaque = new ArrayList<>(frames.size());
        for (int[] f : frames) opaque.add(flatten(f));
        int[] palette = palette(opaque);
        int[] lookup = new int[1 << 15]; Arrays.fill(lookup, -1);
        List<byte[]> indexed = new ArrayList<>(opaque.size());
        for (int[] frame : opaque) {
            byte[] indices = new byte[width * height];
            for (int i = 0; i < frame.length; i++) indices[i] = (byte) index(frame[i], palette, lookup);
            indexed.add(indices);
        }
        return indexed(indexed, width, height, palette, delayMs);
    }

    /** The file from frames already given as indices into a 256-colour palette. */
    static byte[] indexed(List<byte[]> frames, int width, int height, int[] palette, int delayMs) {
        if (palette.length != COLORS) throw new IllegalArgumentException("팔레트는 256색이어야 해요.");
        ByteArrayOutputStream out = new ByteArrayOutputStream(width * height * frames.size() / 2 + 2048);
        ascii(out, "GIF89a");
        short16(out, width); short16(out, height);
        out.write(0xF7); out.write(0); out.write(0); // global colour table of 256, 8-bit colour resolution
        for (int c : palette) { out.write((c >> 16) & 0xFF); out.write((c >> 8) & 0xFF); out.write(c & 0xFF); }
        out.write(0x21); out.write(0xFF); out.write(11); ascii(out, "NETSCAPE2.0"); out.write(3); out.write(1); short16(out, 0); out.write(0);
        int delay = Math.max(2, Math.round(delayMs / 10f));
        for (byte[] indices : frames) {
            if (indices.length != width * height) throw new IllegalArgumentException("GIF 장면 크기가 서로 달라요.");
            out.write(0x21); out.write(0xF9); out.write(4); out.write(0x04); short16(out, delay); out.write(0); out.write(0); // keep the frame; no transparency
            out.write(0x2C); short16(out, 0); short16(out, 0); short16(out, width); short16(out, height); out.write(0);
            lzw(out, indices);
        }
        out.write(0x3B);
        return out.toByteArray();
    }

    /** Opaque RGB: alpha laid over white, as the profile circle's light background would show it. */
    private static int[] flatten(int[] frame) {
        int[] rgb = new int[frame.length];
        for (int i = 0; i < frame.length; i++) {
            int c = frame[i], a = c >>> 24;
            if (a == 255) { rgb[i] = c & 0xFFFFFF; continue; }
            int r = ((c >> 16) & 0xFF) * a / 255 + 255 - a, g = ((c >> 8) & 0xFF) * a / 255 + 255 - a, b = (c & 0xFF) * a / 255 + 255 - a;
            rgb[i] = r << 16 | g << 8 | b;
        }
        return rgb;
    }

    // ── Palette: median cut ─────────────────────────────────────────────────

    /** 256 RGB colours (unused entries black) for every frame together. */
    static int[] palette(List<int[]> frames) {
        long total = 0; for (int[] f : frames) total += f.length;
        // Rounded up, so the samples spread over every frame (rounded down, the last frame could be left out).
        int step = (int) Math.max(1, (total + SAMPLES - 1) / SAMPLES), n = 0;
        int[] samples = new int[(int) Math.min(total, (long) SAMPLES)];
        long seen = 0;
        for (int[] f : frames) for (int c : f) { if (seen++ % step == 0 && n < samples.length) samples[n++] = c; }
        List<int[]> boxes = new ArrayList<>(); boxes.add(new int[] {0, n}); // [from, to) ranges of samples
        while (boxes.size() < COLORS) {
            int best = -1; long bestScore = 0; int bestChannel = 0;
            for (int i = 0; i < boxes.size(); i++) {
                int[] box = boxes.get(i); if (box[1] - box[0] < 2) continue;
                int[] range = range(samples, box[0], box[1]);
                int channel = range[0] >= range[1] && range[0] >= range[2] ? 0 : range[1] >= range[2] ? 1 : 2;
                long score = (long) range[channel] * (box[1] - box[0]);
                if (range[channel] > 0 && score > bestScore) { bestScore = score; best = i; bestChannel = channel; }
            }
            if (best < 0) break;
            int[] box = boxes.get(best); int shift = 16 - bestChannel * 8;
            sortBy(samples, box[0], box[1], shift);
            int mid = (box[0] + box[1]) >>> 1;
            boxes.set(best, new int[] {box[0], mid}); boxes.add(new int[] {mid, box[1]});
        }
        int[] palette = new int[COLORS];
        for (int i = 0; i < boxes.size(); i++) {
            int[] box = boxes.get(i); long r = 0, g = 0, b = 0; int count = box[1] - box[0];
            if (count == 0) continue;
            for (int s = box[0]; s < box[1]; s++) { int c = samples[s]; r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; }
            palette[i] = (int) (r / count) << 16 | (int) (g / count) << 8 | (int) (b / count);
        }
        return palette;
    }
    private static int[] range(int[] samples, int from, int to) {
        int[] min = {255, 255, 255}, max = {0, 0, 0};
        for (int s = from; s < to; s++) for (int ch = 0; ch < 3; ch++) {
            int v = (samples[s] >> (16 - ch * 8)) & 0xFF;
            if (v < min[ch]) min[ch] = v; if (v > max[ch]) max[ch] = v;
        }
        return new int[] {max[0] - min[0], max[1] - min[1], max[2] - min[2]};
    }
    /** Sorts samples[from, to) by one 8-bit channel (counting sort; values are 0..255). */
    private static void sortBy(int[] samples, int from, int to, int shift) {
        int[] counts = new int[257];
        for (int s = from; s < to; s++) counts[((samples[s] >> shift) & 0xFF) + 1]++;
        for (int i = 0; i < 256; i++) counts[i + 1] += counts[i];
        int[] sorted = new int[to - from];
        for (int s = from; s < to; s++) sorted[counts[(samples[s] >> shift) & 0xFF]++] = samples[s];
        System.arraycopy(sorted, 0, samples, from, sorted.length);
    }
    /** The nearest palette entry, looked up once per 15-bit colour cell (its centre stands for the cell). */
    private static int index(int rgb, int[] palette, int[] lookup) {
        int key = ((rgb >> 19) & 0x1F) << 10 | ((rgb >> 11) & 0x1F) << 5 | ((rgb >> 3) & 0x1F);
        int found = lookup[key];
        if (found >= 0) return found;
        int r = ((key >> 10) << 3) | 4, g = (((key >> 5) & 0x1F) << 3) | 4, b = ((key & 0x1F) << 3) | 4;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int c = palette[i], dr = ((c >> 16) & 0xFF) - r, dg = ((c >> 8) & 0xFF) - g, db = (c & 0xFF) - b;
            long d = 2L * dr * dr + 4L * dg * dg + 3L * db * db;
            if (d < best) { best = d; found = i; }
        }
        return lookup[key] = found;
    }

    // ── LZW (GIF variant, 8-bit pixels, codes up to 12 bits) ─────────────────

    private static void lzw(ByteArrayOutputStream out, byte[] pixels) {
        out.write(8);
        Bits bits = new Bits();
        final int clear = 256, end = 257, size = 8192;
        int[] keys = new int[size], codes = new int[size];
        Arrays.fill(keys, -1);
        int codeSize = 9, next = 258;
        bits.put(clear, codeSize);
        int prefix = pixels[0] & 0xFF;
        for (int i = 1; i < pixels.length; i++) {
            int c = pixels[i] & 0xFF, key = prefix << 8 | c, slot = (key * 0x9E3779B1 >>> 19) & (size - 1);
            while (keys[slot] != -1 && keys[slot] != key) slot = (slot + 1) & (size - 1);
            if (keys[slot] == key) { prefix = codes[slot]; continue; }
            bits.put(prefix, codeSize);
            // The code the next entry gets must fit: widen first, as the decoder will after this code.
            if (next > (1 << codeSize) - 1 && codeSize < 12) codeSize++;
            if (next < MAX_CODE) { keys[slot] = key; codes[slot] = next++; }
            else { bits.put(clear, codeSize); Arrays.fill(keys, -1); codeSize = 9; next = 258; }
            prefix = c;
        }
        bits.put(prefix, codeSize);
        if (next > (1 << codeSize) - 1 && codeSize < 12) codeSize++;
        bits.put(end, codeSize);
        byte[] data = bits.bytes();
        for (int at = 0; at < data.length; at += 255) {
            int n = Math.min(255, data.length - at);
            out.write(n); out.write(data, at, n);
        }
        out.write(0);
    }
    private static final class Bits {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int buffer, count;
        void put(int code, int size) {
            buffer |= code << count; count += size;
            while (count >= 8) { bytes.write(buffer & 0xFF); buffer >>>= 8; count -= 8; }
        }
        byte[] bytes() { if (count > 0) { bytes.write(buffer & 0xFF); buffer = 0; count = 0; } return bytes.toByteArray(); }
    }

    private static void short16(ByteArrayOutputStream out, int value) { out.write(value & 0xFF); out.write((value >> 8) & 0xFF); }
    private static void ascii(ByteArrayOutputStream out, String text) { byte[] b = text.getBytes(StandardCharsets.US_ASCII); out.write(b, 0, b.length); }
}
