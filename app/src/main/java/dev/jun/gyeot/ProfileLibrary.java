package dev.jun.gyeot;

import android.content.Context;
import android.util.AtomicFile;
import dev.jun.gyeot.core.ProfileMotion;
import dev.jun.gyeot.core.ProfileShelf;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The profile picture library on this device (files/assistant-profiles-v1/, never uploaded): every picture made or
 * chosen for the profile, so any of them can be the profile again, plus the expression GIFs made for a picture. Files are
 * written atomically and checked like the profile itself before they are kept; the index is written last.
 */
final class ProfileLibrary {
    /** About 190 MB for pictures and expression GIFs together; the user deletes old ones to make room. */
    static final long BUDGET = 190L * 1024 * 1024;
    private static final int INDEX_LIMIT = 512 * 1024;
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private ProfileLibrary() {}

    static File dir(Context c) { return new File(c.getFilesDir(), "assistant-profiles-v1"); }
    private static File index(Context c) { return new File(dir(c), "index.json"); }
    private static File file(Context c, String name) { return new File(dir(c), name); }

    /** The library, newest first. An unreadable index is set aside (never overwritten) and the library starts empty. */
    static ProfileShelf read(Context c) {
        synchronized (LOCK) {
            AtomicFile f = new AtomicFile(index(c));
            if (!f.getBaseFile().exists()) return new ProfileShelf();
            try (InputStream in = f.openRead()) {
                byte[] bytes = in.readNBytes(INDEX_LIMIT + 1);
                if (bytes.length > INDEX_LIMIT) throw new IOException("index too large");
                return ProfileShelf.parse(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception | OutOfMemoryError e) {
                File aside = file(c, "index-unreadable-" + System.currentTimeMillis() + ".json");
                if (!f.getBaseFile().renameTo(aside)) f.delete();
                return new ProfileShelf();
            }
        }
    }
    private static void write(Context c, ProfileShelf shelf) throws IOException { put(index(c), shelf.json().getBytes(StandardCharsets.UTF_8)); }
    private static void put(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("보관함 폴더를 만들지 못했어요.");
        AtomicFile file = new AtomicFile(target); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(bytes); out.flush(); file.finishWrite(out); out = null; }
        finally { if (out != null) file.failWrite(out); }
    }
    /** A kept file's bytes, at most limit. */
    static byte[] bytes(File target, int limit) throws IOException {
        AtomicFile file = new AtomicFile(target);
        if (!file.getBaseFile().exists()) throw new IOException("보관함 파일이 없어요. 다시 만들어 주세요.");
        try (InputStream in = file.openRead()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("보관함 파일이 너무 커요.");
            return bytes;
        }
    }
    private static long used(Context c) {
        long total = 0; File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }
    private static void room(Context c, long more) throws IOException {
        if (used(c) + more > BUDGET) throw new IOException("보관함이 가득 찼어요(약 190MB). 쓰지 않는 그림이나 표정 GIF를 지워 주세요.");
    }
    private static void delete(Context c, ProfileShelf.Entry e) {
        new AtomicFile(file(c, e.imageFile())).delete(); new AtomicFile(file(c, e.svgFile())).delete();
        for (String mood : dev.jun.gyeot.core.BotAvatar.MOODS) new AtomicFile(file(c, e.moodFile(mood))).delete();
    }
    private static String shown(Context c) {
        try { byte[] now = AssistantProfile.read(c).bytes(); return now.length == 0 ? null : AssistantAvatar.checksum(now); } catch (Exception e) { return null; }
    }

    /**
     * Keeps a checked picture first in the library and returns its entry; the same picture again moves its entry up
     * (keeping its GIFs). svg is the vector it was drawn from, or null. Past the limit the oldest go, never the profile shown now.
     */
    static ProfileShelf.Entry add(Context c, byte[] image, String source, String name, String prompt, String svg) throws IOException {
        AssistantProfile.Info info = AssistantProfile.inspect(image);
        String checksum = AssistantAvatar.checksum(image), format = info.format().toLowerCase(Locale.ROOT);
        synchronized (LOCK) {
            ProfileShelf shelf = read(c);
            ProfileShelf.Entry fresh = ProfileShelf.entry(ProfileShelf.id(System.currentTimeMillis(), RANDOM.nextLong()), name, source, prompt, checksum, format, System.currentTimeMillis(), svg != null);
            ProfileShelf.Entry same = shelf.byChecksum(checksum);
            byte[] vector = svg == null ? null : svg.getBytes(StandardCharsets.UTF_8);
            room(c, (same == null ? image.length : 0) + (vector == null ? 0 : vector.length));
            if (same == null) put(file(c, fresh.imageFile()), image);
            if (vector != null) put(file(c, (same == null ? fresh : same).svgFile()), vector);
            ProfileShelf.Added added = shelf.add(fresh, shown(c));
            write(c, shelf);
            for (ProfileShelf.Entry gone : added.dropped()) delete(c, gone);
            return added.entry();
        }
    }
    /** The first time the library is opened, the profile picture set before it existed becomes its first picture. */
    static void adopt(Context c) {
        synchronized (LOCK) {
            if (dir(c).isDirectory() && index(c).exists()) return;
            try {
                byte[] now = AssistantProfile.read(c).bytes();
                if (now.length > 0) add(c, now, "saved", "지금 프로필", "", null);
                else write(c, new ProfileShelf());
            } catch (Exception ignored) {}
        }
    }

    /** The picture's bytes, checked against the checksum it was kept with. */
    static byte[] image(Context c, ProfileShelf.Entry e) throws IOException {
        byte[] bytes;
        synchronized (LOCK) { bytes = bytes(file(c, e.imageFile()), AssistantProfile.MAX_BYTES); }
        if (!AssistantAvatar.checksum(bytes).equals(e.checksum)) throw new IOException("보관함 그림이 손상됐어요. 지우고 다시 만들어 주세요.");
        return bytes;
    }
    static String svg(Context c, ProfileShelf.Entry e) throws IOException {
        if (!e.svg) return null;
        synchronized (LOCK) { return new String(bytes(file(c, e.svgFile()), dev.jun.gyeot.core.SvgArt.MAX_CHARS * 4), StandardCharsets.UTF_8); }
    }
    static byte[] mood(Context c, ProfileShelf.Entry e, String mood) throws IOException {
        synchronized (LOCK) { return bytes(file(c, e.moodFile(mood)), ProfileMotion.GIF_LIMIT); }
    }
    /** The library entry of the profile picture shown now (same checksum), or null. */
    static ProfileShelf.Entry current(Context c, byte[] profile) {
        if (profile == null || profile.length == 0) return null;
        return read(c).byChecksum(AssistantAvatar.checksum(profile));
    }
    /** Keeps one expression GIF (already checked by the caller's decode) for a picture; returns the updated entry. */
    static ProfileShelf.Entry saveMood(Context c, String id, String mood, byte[] gif) throws IOException {
        AssistantProfile.Info info = AssistantProfile.inspect(gif);
        if (!info.format().equals("GIF")) throw new IOException("표정은 GIF로만 보관해요.");
        synchronized (LOCK) {
            ProfileShelf shelf = read(c); ProfileShelf.Entry e = shelf.find(id);
            if (e == null) throw new IOException("보관함에서 그림이 지워져서 표정을 보관하지 못했어요.");
            room(c, gif.length);
            put(file(c, e.moodFile(mood)), gif);
            ProfileShelf.Entry next = e.withMood(mood);
            shelf.replace(next); write(c, shelf);
            return next;
        }
    }
    static ProfileShelf.Entry useMoods(Context c, String id, boolean on) throws IOException {
        synchronized (LOCK) {
            ProfileShelf shelf = read(c); ProfileShelf.Entry e = shelf.find(id);
            if (e == null) throw new IOException("보관함에서 그림을 찾지 못했어요.");
            ProfileShelf.Entry next = e.useMoods(on); shelf.replace(next); write(c, shelf); return next;
        }
    }
    static ProfileShelf.Entry describe(Context c, String id, String prompt) throws IOException {
        synchronized (LOCK) {
            ProfileShelf shelf = read(c); ProfileShelf.Entry e = shelf.find(id);
            if (e == null) throw new IOException("보관함에서 그림을 찾지 못했어요.");
            ProfileShelf.Entry next = e.described(prompt); shelf.replace(next); write(c, shelf); return next;
        }
    }
    static ProfileShelf.Entry clearMoods(Context c, String id) throws IOException {
        synchronized (LOCK) {
            ProfileShelf shelf = read(c); ProfileShelf.Entry e = shelf.find(id);
            if (e == null) throw new IOException("보관함에서 그림을 찾지 못했어요.");
            for (String mood : e.moods) new AtomicFile(file(c, e.moodFile(mood))).delete();
            ProfileShelf.Entry next = e.withoutMoods(); shelf.replace(next); write(c, shelf); return next;
        }
    }
    /** Removes a picture and its SVG and GIFs. The profile itself is a separate copy and stays as it is. */
    static void remove(Context c, String id) throws IOException {
        synchronized (LOCK) {
            ProfileShelf shelf = read(c); ProfileShelf.Entry e = shelf.remove(id);
            if (e == null) return;
            write(c, shelf); delete(c, e);
        }
    }
}
