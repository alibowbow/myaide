package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.Board;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 판서's boards on the phone: files/boards/index.json lists them (id, title, when changed, what is on them), and each
 * board is files/boards/<id>.json: its items and the lessons given on it (what each step said, so a lesson can be
 * given again). A board's pictures are files beside it, named <id>-<number>.jpg or .png. The newest board is first. At
 * most MOST boards are kept; making one more removes the oldest that is empty, or refuses.
 */
final class BoardStore {
    static final int MOST = 60, TITLE = 40, LESSONS = 40;
    /** A board as it is kept: what is on it, and its lessons [{id, title, says: [..]}]. */
    record Doc(JSONArray items, JSONArray lessons, JSONObject classroom) {
        Doc(JSONArray items,JSONArray lessons){this(items,lessons,obj());}
    }
    record Entry(String id, String title, long updated, String summary) {}
    private final File dir;
    BoardStore(Context context) { this(new File(context.getFilesDir(), "boards")); }
    BoardStore(File dir) { this.dir = dir; }
    File dir() { return dir; }

    private static boolean named(String id) { return id != null && id.matches("[a-z0-9]{6,16}"); }
    private File file(String id) { return new File(dir, id + ".json"); }
    private static String title(String title) { String clean = title == null ? "" : title.replaceAll("\\s+", " ").strip(); return clean.length() > TITLE ? clean.substring(0, TITLE).strip() : clean; }
    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile(); if (parent != null) parent.mkdirs();
        File temporary = new File(parent, file.getName() + ".tmp");
        Files.write(temporary.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    private JSONArray index() {
        try { File file = new File(dir, "index.json"); return file.isFile() ? new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : arr(); }
        catch (Exception unreadable) { return arr(); }
    }

    /** The boards, newest first. */
    synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>(); JSONArray index = index();
        for (int i = 0; i < index.length(); i++) {
            JSONObject entry = index.optJSONObject(i);
            if (entry != null && named(entry.optString("id")) && file(entry.optString("id")).isFile()) out.add(new Entry(entry.optString("id"), entry.optString("title"), entry.optLong("updated"), entry.optString("summary")));
        }
        out.sort((a, b) -> Long.compare(b.updated(), a.updated()));
        return out;
    }
    private void saveIndex(List<Entry> entries) throws IOException {
        JSONArray index = arr();
        for (Entry entry : entries) index.put(obj("id", entry.id(), "title", entry.title(), "updated", entry.updated(), "summary", entry.summary()));
        write(new File(dir, "index.json"), index.toString());
    }
    synchronized Entry entry(String id) { for (Entry entry : list()) if (entry.id().equals(id)) return entry; return null; }

    /** A new empty board. With MOST boards already, the oldest empty one makes room; with none empty it says so. */
    synchronized Entry create(String title, long now) throws IOException {
        List<Entry> entries = list();
        if (entries.size() >= MOST) {
            Entry spare = null;
            for (Entry entry : entries) if (entry.summary().equals(Board.summary(null))) spare = entry;
            if (spare == null) throw new IOException("판은 " + MOST + "개까지 둘 수 있어요. 쓰지 않는 판을 지워 주세요.");
            file(spare.id()).delete(); entries.remove(spare);
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Entry made = new Entry(id, title(title), now, Board.summary(null));
        write(file(id), obj("v", 2, "items", arr(), "lessons", arr()).toString()); entries.add(0, made); saveIndex(entries);
        return made;
    }
    /** The board's items; an empty board when its file is gone or unreadable. */
    synchronized JSONArray items(String id) { return load(id).items(); }
    /** The board with its lessons. A file from before lessons were kept is its items alone. */
    synchronized Doc load(String id) {
        if (!named(id)) return new Doc(arr(), arr());
        try {
            File file = file(id); if (!file.isFile()) return new Doc(arr(), arr());
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).strip();
            if (text.startsWith("[")) return new Doc(Board.clean(new JSONArray(text)), arr());
            JSONObject root = new JSONObject(text);
            return new Doc(Board.clean(root.optJSONArray("items")), lessons(root.optJSONArray("lessons")),dev.jun.gyeot.core.BoardWidget.copy(root.optJSONObject("classroom")));
        } catch (Exception unreadable) { return new Doc(arr(), arr()); }
    }
    /** Lessons as they are kept: an id, a title and what each step said; the newest LESSONS of them. */
    static JSONArray lessons(JSONArray stored) {
        JSONArray out = arr();
        for (int i = stored == null ? 0 : Math.max(0, stored.length() - LESSONS); stored != null && i < stored.length(); i++) {
            JSONObject lesson = stored.optJSONObject(i); if (lesson == null || !lesson.optString("id").matches("[a-z0-9]{1,12}")) continue;
            JSONArray says = arr(), from = lesson.optJSONArray("says");
            for (int k = 0; from != null && k < from.length() && k < dev.jun.gyeot.core.BoardScript.STEPS; k++) says.put(from.optString(k));
            out.put(obj("id", lesson.optString("id"), "title", title(lesson.optString("title")), "says", says, "frames",dev.jun.gyeot.core.BoardReplay.clean(lesson,says.length())));
        }
        return out;
    }
    /** Keeps the board's items (and its title, when one is given). A board that was deleted meanwhile stays deleted. */
    synchronized void save(String id, JSONArray items, String newTitle, long now) throws IOException { save(id, items, load(id).lessons(), newTitle, now); }
    /** As above, with the board's lessons. */
    synchronized void save(String id, JSONArray items, JSONArray lessons, String newTitle, long now) throws IOException {
        save(id,items,lessons,load(id).classroom(),newTitle,now);
    }
    synchronized void save(String id, JSONArray items, JSONArray lessons, JSONObject classroom, String newTitle, long now) throws IOException {
        if (!named(id)) throw new IOException("판을 찾지 못했어요.");
        List<Entry> entries = list(); Entry old = null;
        for (Entry entry : entries) if (entry.id().equals(id)) old = entry;
        if (old == null) return;
        JSONArray clean = Board.clean(items);
        write(file(id), obj("v", 2, "items", clean, "lessons", lessons(lessons), "classroom",classroom).toString());
        entries.remove(old); entries.add(0, new Entry(id, newTitle == null ? old.title() : title(newTitle), now, Board.summary(clean)));
        saveIndex(entries);
    }
    synchronized void rename(String id, String title) throws IOException {
        List<Entry> entries = list();
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(id)) { Entry old = entries.get(i); entries.set(i, new Entry(id, title(title), old.updated(), old.summary())); saveIndex(entries); return; }
    }
    synchronized void delete(String id) throws IOException {
        if (!named(id)) return;
        List<Entry> entries = list(); entries.removeIf(entry -> entry.id().equals(id));
        saveIndex(entries); file(id).delete();
        File[] pictures = dir.listFiles((folder, name) -> name.startsWith(id + "-") && Board.IMAGE_NAME.matcher(name).matches());
        if (pictures != null) for (File picture : pictures) picture.delete();
    }

    /** A picture's file by the name its item carries; null for a name that is not a picture's. */
    File picture(String name) { return name != null && Board.IMAGE_NAME.matcher(name).matches() ? new File(dir, name) : null; }
    /** A name no picture of this board has yet. png keeps a drawing's sharp edges; everything else is jpg. */
    synchronized String pictureName(String id, boolean png) {
        if (!named(id)) throw new IllegalArgumentException("판을 찾지 못했어요.");
        dir.mkdirs();
        for (int number = 1; number < 10_000; number++) {
            if (!new File(dir, id + "-" + number + ".jpg").exists() && !new File(dir, id + "-" + number + ".png").exists()) return id + "-" + number + (png ? ".png" : ".jpg");
        }
        throw new IllegalStateException("이 판에는 자료를 더 붙일 수 없어요.");
    }
}
