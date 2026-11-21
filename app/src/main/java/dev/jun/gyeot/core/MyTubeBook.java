package dev.jun.gyeot.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 마이튜브's own files in the app's private storage, on this phone only: the feeds (feeds.json), each feed's last choice
 * with when it was made (feed-<id>.json), and each feed's taste (taste-<id>.json: liked, skipped and already played).
 * Writes go to a temporary file first and replace the old one. Pure Java; every method is synchronized.
 */
public final class MyTubeBook {
    /** How many likes, skips and plays a feed remembers. */
    static final int LIKED = 60, SKIPPED = 80, PLAYED = 300;
    private final File dir;
    public MyTubeBook(File dir) { this.dir = dir; }
    public File dir() { return dir; }

    public synchronized List<MyTube.Feed> feeds() {
        List<MyTube.Feed> out = new ArrayList<>();
        JSONArray list = read("feeds.json").optJSONArray("feeds");
        for (int i = 0; list != null && i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !o.optString("id").isEmpty()) out.add(MyTube.Feed.of(o)); }
        return out;
    }
    public synchronized MyTube.Feed feed(String id) { for (MyTube.Feed f : feeds()) if (f.id().equals(id)) return f; return null; }
    /** Adds the feed (a new id when it has none) or replaces the one with its id; returns it as saved. */
    public synchronized MyTube.Feed save(MyTube.Feed feed) throws IOException {
        List<MyTube.Feed> all = feeds();
        MyTube.Feed saved = feed.id() == null || feed.id().isEmpty() ? feed.with("f" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(all.size(), 36)) : feed;
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(saved.id())) { all.set(i, saved); replaced = true; }
        if (!replaced) all.add(saved);
        writeFeeds(all);
        return saved;
    }
    public synchronized void remove(String id) throws IOException {
        List<MyTube.Feed> all = feeds(); all.removeIf(f -> f.id().equals(id)); writeFeeds(all);
        new File(dir, "feed-" + id + ".json").delete(); new File(dir, "taste-" + id + ".json").delete();
    }
    /** Moves a feed one place earlier (up) or later. */
    public synchronized void move(String id, boolean up) throws IOException {
        List<MyTube.Feed> all = feeds();
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(id)) {
            int j = up ? i - 1 : i + 1; if (j < 0 || j >= all.size()) return;
            MyTube.Feed f = all.get(i); all.set(i, all.get(j)); all.set(j, f); writeFeeds(all); return;
        }
    }
    private void writeFeeds(List<MyTube.Feed> all) throws IOException {
        JSONArray list = new JSONArray(); for (MyTube.Feed f : all) list.put(f.json());
        write("feeds.json", obj("feeds", list));
    }

    /** 피드 목록 자동 정리: the feeds in this order (ids not listed keep their place after), with new names where given. */
    public synchronized void arrange(List<String> order, java.util.Map<String, String> renames) throws IOException {
        List<MyTube.Feed> all = feeds(), out = new ArrayList<>();
        for (String id : order) for (MyTube.Feed f : all) if (f.id().equals(id) && !out.contains(f)) out.add(f);
        for (MyTube.Feed f : all) if (!out.contains(f)) out.add(f);
        for (int i = 0; i < out.size(); i++) {
            MyTube.Feed f = out.get(i); String name = renames == null ? null : renames.get(f.id());
            if (name != null && !name.isBlank()) out.set(i, new MyTube.Feed(f.id(), name.strip(), f.kind(), f.about(), f.channel(), f.days()));
        }
        writeFeeds(out);
    }

    // ── 라이브 채널 (channels.json): channels saved to go straight to their live broadcast ──
    public synchronized List<MyTube.Channel> channels() {
        List<MyTube.Channel> out = new ArrayList<>(); JSONArray list = read("channels.json").optJSONArray("channels");
        for (int i = 0; list != null && i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !o.optString("id").isEmpty()) out.add(MyTube.Channel.of(o)); }
        return out;
    }
    /** Saves a channel (once; again moves it first), at most 20. */
    public synchronized void saveChannel(MyTube.Channel channel) throws IOException {
        List<MyTube.Channel> all = channels(); all.removeIf(c -> c.id().equals(channel.id())); all.add(0, channel);
        while (all.size() > 20) all.remove(all.size() - 1);
        JSONArray list = new JSONArray(); for (MyTube.Channel c : all) list.put(c.json());
        write("channels.json", obj("channels", list));
    }
    public synchronized void removeChannel(String id) throws IOException {
        List<MyTube.Channel> all = channels(); all.removeIf(c -> c.id().equals(id));
        JSONArray list = new JSONArray(); for (MyTube.Channel c : all) list.put(c.json());
        write("channels.json", obj("channels", list));
    }

    // ── 마이튜브 AI's conversation (chat.json), the newest CHAT lines ───────
    static final int CHAT = 200;
    /** One line of the conversation: the user's (typed or spoken), the AI's, or what the app did (note). */
    public record Line(long id, String role, String text, boolean spoken, long at) {
        public JSONObject json() { return obj("id", id, "role", role, "text", text, "spoken", spoken, "at", at); }
    }
    public synchronized List<Line> chat() {
        List<Line> out = new ArrayList<>(); JSONArray list = read("chat.json").optJSONArray("lines");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null) out.add(new Line(o.optLong("id"), o.optString("role"), o.optString("text"), o.optBoolean("spoken"), o.optLong("at")));
        }
        return out;
    }
    /** Writes a line; returns its id (increasing, never 0). */
    public synchronized long say(String role, String text, boolean spoken) throws IOException {
        String clean = text == null ? "" : text.strip(); if (clean.isEmpty()) return 0;
        if (clean.length() > 8000) clean = clean.substring(0, 8000);
        List<Line> lines = chat();
        long id = Math.max(System.currentTimeMillis(), lines.isEmpty() ? 0 : lines.get(lines.size() - 1).id() + 1);
        lines.add(new Line(id, role, clean, spoken, System.currentTimeMillis()));
        while (lines.size() > CHAT) lines.remove(0);
        JSONArray list = new JSONArray(); for (Line l : lines) list.put(l.json());
        write("chat.json", obj("lines", list));
        return id;
    }
    public synchronized void clearChat() throws IOException { write("chat.json", obj("lines", new JSONArray())); }

    /** The feed's last choice, best first. */
    public synchronized List<MyTube.Video> videos(String feed) { return list(read("feed-" + feed + ".json").optJSONArray("videos")); }
    /** When the feed's last choice was made (epoch ms; 0 for never). */
    public synchronized long refreshedAt(String feed) { return read("feed-" + feed + ".json").optLong("at"); }
    public synchronized void videos(String feed, List<MyTube.Video> videos, long at) throws IOException {
        JSONArray list = new JSONArray(); for (MyTube.Video v : videos) list.put(v.json());
        write("feed-" + feed + ".json", obj("at", at, "videos", list));
    }

    /** 저장: the video at the top of the feed's list (once), and liked, so the feed's next choices lean toward it. */
    public synchronized void keep(String feed, MyTube.Video video) throws IOException {
        List<MyTube.Video> list = videos(feed); list.removeIf(v -> v.id().equals(video.id())); list.add(0, video);
        videos(feed, list, refreshedAt(feed));
        mark(feed, video, true);
    }
    public synchronized MyTube.Taste taste(String feed) {
        JSONObject t = read("taste-" + feed + ".json");
        return new MyTube.Taste(list(t.optJSONArray("liked")), list(t.optJSONArray("skipped")));
    }
    /** The ids played in this feed lately, so a music feed keeps finding new songs. */
    public synchronized List<String> played(String feed) {
        JSONArray ids = read("taste-" + feed + ".json").optJSONArray("played"); List<String> out = new ArrayList<>();
        for (int i = 0; ids != null && i < ids.length(); i++) out.add(ids.optString(i));
        return out;
    }
    public synchronized boolean liked(String feed, String id) { for (MyTube.Video v : taste(feed).liked()) if (v.id().equals(id)) return true; return false; }
    /** A like (true) or a skip (false): it leaves the other list, newest first, each list capped. */
    public synchronized void mark(String feed, MyTube.Video video, boolean like) throws IOException {
        JSONObject t = read("taste-" + feed + ".json");
        JSONArray liked = without(t.optJSONArray("liked"), video.id()), skipped = without(t.optJSONArray("skipped"), video.id());
        JSONObject entry = obj("id", video.id(), "title", video.title(), "channel", video.channel());
        put(t, "liked", like ? first(entry, liked, LIKED) : liked);
        put(t, "skipped", like ? skipped : first(entry, skipped, SKIPPED));
        write("taste-" + feed + ".json", t);
    }
    /** Takes back a like. */
    public synchronized void unlike(String feed, String id) throws IOException {
        JSONObject t = read("taste-" + feed + ".json"); put(t, "liked", without(t.optJSONArray("liked"), id)); write("taste-" + feed + ".json", t);
    }
    public synchronized void playedNow(String feed, String id) throws IOException {
        JSONObject t = read("taste-" + feed + ".json");
        JSONArray ids = t.optJSONArray("played"), out = new JSONArray().put(id);
        for (int i = 0; ids != null && i < ids.length() && out.length() < PLAYED; i++) if (!ids.optString(i).equals(id)) out.put(ids.optString(i));
        put(t, "played", out); write("taste-" + feed + ".json", t);
    }

    private static JSONArray without(JSONArray list, String id) {
        JSONArray out = new JSONArray();
        for (int i = 0; list != null && i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !id.equals(o.optString("id"))) out.put(o); }
        return out;
    }
    private static JSONArray first(JSONObject entry, JSONArray rest, int cap) {
        JSONArray out = new JSONArray().put(entry);
        for (int i = 0; i < rest.length() && out.length() < cap; i++) out.put(rest.opt(i));
        return out;
    }
    private static List<MyTube.Video> list(JSONArray a) {
        List<MyTube.Video> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && !o.optString("id").isEmpty()) out.add(MyTube.Video.of(o)); }
        return out;
    }
    private JSONObject read(String name) {
        File f = new File(dir, name);
        try { return f.isFile() ? new JSONObject(Files.readString(f.toPath(), StandardCharsets.UTF_8)) : new JSONObject(); }
        catch (Exception unreadable) { return new JSONObject(); }
    }
    private void write(String name, JSONObject value) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("마이튜브 저장 폴더를 만들지 못했어요.");
        File tmp = new File(dir, name + ".tmp");
        Files.writeString(tmp.toPath(), value.toString(), StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), new File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
