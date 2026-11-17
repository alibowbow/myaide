package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the character of an animated GIF still: an image model draws each frame on its own, a little to the left or right,
 * larger or smaller, and played in a small profile circle that reads as the whole picture shaking. The GIF is read
 * (GifReader), its frames are matched to each other and redrawn in one place (FrameAligner), and it is written again
 * (GifWriter) with the frame time it had. What moves on purpose inside the picture (eyes, mouth, a tear, hearts) stays.
 *
 * It never makes a GIF worse or loses one: a GIF that cannot be read, has one frame, shows through (its edges cannot be
 * filled), is already still, or would come out larger than the limit is returned as it was. Pure Java; no Android.
 */
public final class GifSteady {
    private GifSteady() {}
    /** The largest picture and the most frames looked at: an expression GIF is 512 px and 9 frames. */
    static final int MAX_SIDE = 1024, MAX_FRAMES = 64;
    /** gif: the GIF to keep (the one given when nothing changed); moved: how many frames were redrawn in a new place. */
    public record Result(byte[] gif, int frames, int moved) {
        public boolean changed() { return moved > 0; }
    }

    public static Result steady(byte[] gif, int maxBytes) {
        GifReader.Clip clip;
        try { clip = GifReader.read(gif, MAX_SIDE, MAX_FRAMES); } catch (RuntimeException unreadable) { return new Result(gif, 0, 0); }
        int count = clip.frames().size();
        if (count < 2 || clip.transparent()) return new Result(gif, count, 0);
        // The aligner works on squares: the middle square of a wide or tall GIF (a profile picture shows that part).
        int side = Math.min(clip.width(), clip.height()), left = (clip.width() - side) / 2, top = (clip.height() - side) / 2;
        List<int[]> square = new ArrayList<>(count);
        for (int[] frame : clip.frames()) {
            if (side == clip.width() && side == clip.height()) { square.add(frame); continue; }
            int[] cut = new int[side * side];
            for (int y = 0; y < side; y++) System.arraycopy(frame, (top + y) * clip.width() + left, cut, y * side, side);
            square.add(cut);
        }
        FrameAligner.Steady steady;
        try { steady = FrameAligner.steady(square, side); } catch (RuntimeException failed) { return new Result(gif, count, 0); }
        int moved = 0; for (FrameAligner.Move move : steady.moves()) if (!move.none()) moved++;
        if (moved == 0) return new Result(gif, count, 0);
        byte[] out = GifWriter.encode(steady.pixels(), steady.side(), steady.side(), delay(clip.delaysMs()));
        return out.length > maxBytes ? new Result(gif, count, 0) : new Result(out, count, moved);
    }
    /** One time for every frame, as GifWriter writes: the middle one of the GIF's own. */
    static int delay(List<Integer> delays) {
        List<Integer> sorted = new ArrayList<>(delays); java.util.Collections.sort(sorted);
        return Math.max(20, sorted.get(sorted.size() / 2));
    }
}
