package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.WakeWord;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 호출어 recordings: the user's own "들퍼레" and "퍼레", 16 kHz mono PCM in app-private storage
 * ("wakeword/"), never shared or uploaded; and the settings the standby reads. The matcher is built from them on the phone.
 */
final class WakeWordFiles {
    static final String ENABLED = "wakeEnabled", CAR = "wakeCar", CAR_NAME = "wakeCarName", SENSITIVITY = "wakeSensitivity";
    /** The two words recorded, and how many of each. */
    static final String[] WORDS = {"들퍼레", "퍼레"};
    static final int[] COUNTS = {5, 3};
    private WakeWordFiles() {}

    static File dir(Context context) { File dir = new File(context.getFilesDir(), "wakeword"); dir.mkdirs(); return dir; }
    static File file(Context context, int word, int index) { return new File(dir(context), "w" + word + "-" + index + ".pcm"); }
    static void save(Context context, int word, int index, short[] pcm) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : pcm) bytes.putShort(sample);
        File target = file(context, word, index), temp = new File(target.getPath() + ".tmp");
        Files.write(temp.toPath(), bytes.array());
        if (!temp.renameTo(target)) throw new IOException("녹음을 저장하지 못했어요.");
    }
    static short[] load(File file) throws IOException {
        byte[] raw = Files.readAllBytes(file.toPath()); short[] pcm = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        return pcm;
    }
    /** Recordings kept for a word. */
    static int count(Context context, int word) {
        int n = 0; for (int i = 0; i < COUNTS[word]; i++) if (file(context, word, i).isFile()) n++;
        return n;
    }
    static boolean complete(Context context) { for (int w = 0; w < WORDS.length; w++) if (count(context, w) < COUNTS[w]) return false; return true; }
    static void clear(Context context) { File[] all = dir(context).listFiles(); if (all != null) for (File f : all) f.delete(); }
    /** The matcher from every usable recording, or null when fewer than two are usable. */
    static WakeWord.Model model(Context context, int sensitivity) {
        List<float[][]> templates = new ArrayList<>();
        for (int w = 0; w < WORDS.length; w++) for (int i = 0; i < COUNTS[w]; i++) {
            File f = file(context, w, i); if (!f.isFile()) continue;
            try { float[][] t = WakeWord.enrolled(load(f)); if (t != null) templates.add(t); } catch (IOException unreadable) { /* skipped */ }
        }
        return templates.size() < 2 ? null : WakeWord.Model.of(templates, sensitivity);
    }
    static int sensitivity(AppStore store) { return Math.max(WakeWord.SENSITIVITY_MIN, Math.min(WakeWord.SENSITIVITY_MAX, store.settings.getInt(SENSITIVITY, WakeWord.SENSITIVITY_DEFAULT))); }
}
