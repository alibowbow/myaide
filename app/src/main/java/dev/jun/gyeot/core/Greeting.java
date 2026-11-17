package dev.jun.gyeot.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONObject;

/**
 * 켤 때 인사하기: what the voice assistant sounds the moment a call starts (a tick, or a spoken
 * greeting from a clip, one per voice and text, 24 kHz mono 16-bit PCM, the Speaker's format), the sentence that tells the
 * Live model, and the clips not to ask for again yet. Nothing here touches the microphone: every frame goes on as it is.
 */
public final class Greeting {
    public static final String PLAIN = "네, 말씀하세요.";
    /**
     * Added to an assistant call's instructions while the greeting is on: one fixed sentence, the same for every connection of
     * the call, so the prompt cache is unaffected. The microphone may hear the tick (or, rarely, the words): before the user has
     * said anything that may be the app's own sound, and after that every input is the user's, even a short "네".
     */
    public static final String INSTRUCTIONS = " The app already greeted the user at the start of this call with a short tick or \"네, 말씀하세요.\" (sometimes with your name), so do not greet again; only before the user has said anything in this call can a short sound or that exact phrase be the app's own sound, and after that every input, even a short \"네\", is the user.";
    /** A profile name up to this many characters is said in the greeting; a longer one would make it long. */
    public static final int NAME_LIMIT = 8;
    /** Output PCM: 24 kHz mono 16-bit, 48 bytes a millisecond. */
    public static final int RATE = 24000, BYTES_PER_MS = RATE * 2 / 1000;
    private static final int SILENCE = 400, LEAD_MS = 30, END_MS = 80;
    /** The tick: one 5 kHz sine burst of 15 ms with a 0.3 ms rise, a 2.5 ms decay and a 1.5 ms fade, peaking near -33 dBFS. */
    private static final double TICK_HZ = 5000, TICK_S = 0.015, TICK_RISE = 0.0003, TICK_DECAY = 0.0025, TICK_FADE = 0.0015, TICK_LEVEL = 918;
    private Greeting() {}

    /**
     * "네, 말씀하세요.", or with a short name the user gave that ends in a Hangul syllable "네, (name)예요. 말씀하세요." (이에요 after a
     * final consonant). The app's own name, a long name or one ending in anything else (its particle cannot be told from
     * the spelling) leaves the name out.
     */
    public static String text(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.equals(Brand.NAME) || clean.codePointCount(0, clean.length()) > NAME_LIMIT) return PLAIN;
        if (clean.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '.' || c == ',')) return PLAIN;
        char last = clean.charAt(clean.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return PLAIN;
        return "네, " + Brand.josa(clean, "이에요", "예요") + ". 말씀하세요.";
    }

    /**
     * The Gemini speech voice a spoken greeting is made in: the Live voice's own name (all 30 are speech voices too). Empty
     * for 기본, which sends no voice so the Live model picks its own and the app cannot know it, and for anything else: then
     * there is no spoken clip, only the tick.
     */
    public static String voice(String live) {
        String chosen = live == null ? "" : live.strip();
        for (String name : GeminiProtocol.VOICES) if (!name.isEmpty() && name.equalsIgnoreCase(chosen) && GeminiSpeech.VOICES.contains(name)) return name;
        return "";
    }

    /** One clip's file name: 16 hex characters of a SHA-256 over the speech voice and the text, nothing readable. */
    public static String key(String voice, String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(("greeting-v1\u0000" + voice + "\u0000" + text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(Character.forDigit((hash[i] >> 4) & 15, 16)).append(Character.forDigit(hash[i] & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }

    /** How long 24 kHz PCM plays, in milliseconds. */
    public static long millis(byte[] pcm) { return pcm == null ? 0 : pcm.length / BYTES_PER_MS; }

    /**
     * The clip without the near-silence Gemini speech leaves before and after the words, so the first sound comes at once;
     * empty when nothing in it is louder than that.
     */
    public static byte[] trim(byte[] pcm) {
        int samples = pcm == null ? 0 : pcm.length / 2, first = -1, last = -1;
        for (int i = 0; i < samples; i++) if (Math.abs(sample(pcm, i)) >= SILENCE) { if (first < 0) first = i; last = i; }
        if (first < 0) return new byte[0];
        int from = Math.max(0, first - LEAD_MS * RATE / 1000), to = Math.min(samples, last + 1 + END_MS * RATE / 1000);
        return Arrays.copyOfRange(pcm, from * 2, to * 2);
    }

    /**
     * A very short soft tick (24 kHz PCM, 15 ms, below -30 dBFS): the greeting wherever the microphone can hear the output, and on
     * a headset until a clip in the assistant's voice is saved. It has no words, and it is too short and soft for a speech
     * detector to take for the start of speech (a chime of 0.26 s was).
     */
    public static byte[] tick() {
        int total = (int) (RATE * TICK_S);
        byte[] pcm = new byte[total * 2];
        for (int i = 0; i < total; i++) {
            double t = i / (double) RATE, left = (total - 1 - i) / (double) RATE;
            // A fast rise, a fast decay and a short fade to silence, so it is a soft click with no click at its end.
            double envelope = Math.min(1, t / TICK_RISE) * Math.exp(-t / TICK_DECAY) * Math.min(1, left / TICK_FADE);
            int v = (int) Math.round(TICK_LEVEL * envelope * Math.sin(2 * Math.PI * TICK_HZ * t));
            pcm[2 * i] = (byte) v; pcm[2 * i + 1] = (byte) (v >> 8);
        }
        return pcm;
    }

    /**
     * Clips on the phone, in an app-private folder: at most MAX_CLIPS files of 0.2 to 5 seconds, each written whole (to a
     * temporary file, synced, then renamed over its name) or not at all. One writer at a time; reads may come from any thread.
     */
    public static final class Cache {
        public static final int MAX_CLIPS = 4, MIN_BYTES = RATE * 2 / 5, MAX_BYTES = RATE * 2 * 5;
        private final File dir;
        public Cache(File dir) { this.dir = dir; }
        /** A clip this cache keeps: whole 16-bit samples, 0.2 to 5 seconds. */
        public static boolean fits(byte[] pcm) { return pcm != null && pcm.length >= MIN_BYTES && pcm.length <= MAX_BYTES && pcm.length % 2 == 0; }
        public boolean has(String key) { return file(key).isFile(); }
        /** The clip, or null when there is none; a file of a length this cache never writes is removed. */
        public byte[] read(String key) {
            File file = file(key);
            if (!file.isFile()) return null;
            long length = file.length();
            if (length < MIN_BYTES || length > MAX_BYTES || length % 2 != 0) { file.delete(); return null; }
            try { byte[] pcm = Files.readAllBytes(file.toPath()); return fits(pcm) ? pcm : null; }
            catch (IOException unreadable) { return null; }
        }
        /** Keeps the clip under key; then only the newest MAX_CLIPS stay, and a temporary file a dead write left goes. */
        public void write(String key, byte[] pcm) throws IOException {
            if (!fits(pcm)) throw new IOException("인사 음성 길이가 맞지 않아요.");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("인사 음성 폴더를 만들지 못했어요.");
            File temporary = new File(dir, key + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(pcm); out.getFD().sync(); }
            catch (IOException failed) { temporary.delete(); throw failed; }
            Files.move(temporary.toPath(), file(key).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            File[] files = dir.listFiles();
            if (files == null) return;
            List<File> clips = new ArrayList<>();
            for (File file : files) {
                if (file.getName().endsWith(".tmp")) file.delete();
                else if (file.getName().endsWith(".pcm")) clips.add(file);
            }
            clips.sort(Comparator.comparingLong(File::lastModified).reversed());
            for (int i = MAX_CLIPS; i < clips.size(); i++) clips.get(i).delete();
        }
        private File file(String key) {
            if (key == null || key.length() != 16 || !key.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) throw new IllegalArgumentException("key");
            return new File(dir, key + ".pcm");
        }
    }

    /**
     * Clips whose request failed or whose audio could not be kept, by clip key, with when (wall-clock ms), as kept in the app's
     * settings. None is asked for again within BACKOFF_MS, across app starts too, so a failure never pays again on every start.
     * At most LIMIT are remembered; a time ahead of now (the clock was set back) no longer holds a clip off.
     */
    public static final class Failures {
        public static final long BACKOFF_MS = 24 * 60 * 60 * 1000L;
        public static final int LIMIT = 8;
        private Failures() {}
        /** Whether the clip may be asked for now. */
        public static boolean due(String saved, String key, long now) {
            Long at = read(saved).get(key);
            return at == null || at > now || now - at >= BACKOFF_MS;
        }
        /** The value to keep after the clip failed now: its time, without entries no longer holding, the oldest beyond LIMIT gone. */
        public static String failed(String saved, String key, long now) {
            Map<String, Long> all = read(saved);
            all.remove(key);
            all.entrySet().removeIf(entry -> entry.getValue() > now || now - entry.getValue() >= BACKOFF_MS);
            List<Map.Entry<String, Long>> newest = new ArrayList<>(all.entrySet());
            newest.sort(Map.Entry.<String, Long>comparingByValue().reversed());
            JSONObject out = new JSONObject();
            Jsons.put(out, key, now);
            for (int i = 0; i < Math.min(LIMIT - 1, newest.size()); i++) Jsons.put(out, newest.get(i).getKey(), newest.get(i).getValue());
            return out.toString();
        }
        /** The value to keep once the clip is saved. */
        public static String made(String saved, String key) {
            Map<String, Long> all = read(saved);
            all.remove(key);
            JSONObject out = new JSONObject();
            for (Map.Entry<String, Long> entry : all.entrySet()) Jsons.put(out, entry.getKey(), entry.getValue());
            return out.toString();
        }
        private static Map<String, Long> read(String saved) {
            Map<String, Long> out = new TreeMap<>();
            try {
                JSONObject all = saved == null || saved.isBlank() ? new JSONObject() : new JSONObject(saved);
                for (java.util.Iterator<String> keys = all.keys(); keys.hasNext();) {
                    String key = keys.next(); long at = all.optLong(key, Long.MIN_VALUE);
                    if (at != Long.MIN_VALUE) out.put(key, at);
                }
            } catch (Exception unreadable) { /* A damaged value holds nothing off. */ }
            return out;
        }
    }

    private static int sample(byte[] pcm, int index) { return (short) ((pcm[2 * index] & 0xff) | (pcm[2 * index + 1] << 8)); }
}
