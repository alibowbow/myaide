package dev.jun.gyeot.core;
import java.util.ArrayList;
import java.util.List;
/** Bounded, memory-only unverified phone response. Overflow discards audio, never releases a partial claim. */
public final class PhoneReportAudio {
    private static final int LIMIT = 1_000_000;
    private final List<byte[]> chunks = new ArrayList<>();
    private int bytes;
    private boolean overflow, spoke;
    public void add(byte[] pcm) {
        if (pcm != null && pcm.length > 0) spoke = true;
        if (pcm == null || overflow) return;
        if (pcm.length > LIMIT - bytes) { chunks.clear(); bytes = 0; overflow = true; return; }
        chunks.add(pcm.clone()); bytes += pcm.length;
    }
    public List<byte[]> take() { List<byte[]> value = new ArrayList<>(chunks); clear(); return value; }
    /** Whether any audio came since the last take or clear, even audio dropped for being too long. */
    public boolean spoke() { return spoke; }
    public void clear() { chunks.clear(); bytes = 0; overflow = false; spoke = false; }
}
