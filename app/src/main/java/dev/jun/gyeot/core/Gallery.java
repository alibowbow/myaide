package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 갤러리: the pictures ChatGPT drew in 작업창, newest first, each with what was asked for and by which conversation, so they
 * can be browsed by date, kind, conversation, album or favorite, searched, and followed through their edits. An entry:
 * {id (the small private copy), uri (the full picture in Pictures/들퍼레), name, prompt, revised (the image model's own
 * description), thread, title (the conversation's), model, at, size, w, h, from (the drawing it was edited from),
 * photo (made from an attached photo), favorite, album}. Pure data; stored by the app.
 */
public final class Gallery {
    public static final int LIMIT = 1000, ALBUM_NAME = 30;
    public static final String DATE = "date", KIND = "kind", THREAD = "thread", ALBUM = "album", FAVORITE = "favorite";
    public static final String[] VIEWS = {DATE, KIND, THREAD, ALBUM, FAVORITE};
    public static final String[] VIEW_NAMES = {"날짜별", "종류별", "대화별", "앨범", "★ 즐겨찾기"};
    public static final String PHOTO_EDIT = "사진 편집", OTHER = "일러스트·기타", NO_ALBUM = "앨범 없음";
    /** Kinds by what was asked for, first match wins; words that are part of many others (산, 개, 새, 방) are left out on purpose. */
    private static final String[][] KINDS = {
        {"로고", "로고|logo|엠블럼|emblem|워드마크|wordmark|심볼 마크"},
        {"아이콘·스티커", "아이콘|icon|이모지|emoji|이모티콘|스티커|sticker"},
        {"포스터·배너", "포스터|poster|배너|banner|전단|썸네일|thumbnail|광고|표지|커버|cover|초대장|카드뉴스|명함|flyer|브로셔"},
        {"캐릭터", "캐릭터|character|마스코트|mascot|아바타|avatar"},
        {"인물", "인물|사람|초상|portrait|프로필 사진|얼굴|셀카|selfie|person|woman|man\\b"},
        {"동물", "고양이|강아지|댕댕이|cat\\b|cats\\b|dog\\b|dogs\\b|동물|animal|토끼|rabbit|곰돌이|여우|fox|펭귄|penguin|햄스터"},
        {"음식", "음식|요리|food|디저트|dessert|케이크|cake|커피|coffee|음료|빵|bread|피자|pizza"},
        {"풍경", "풍경|landscape|바다|ocean|해변|beach|하늘|sky|도시|city|숲|forest|노을|sunset|야경|자연|nature|호수|lake|들판|mountain"},
        {"인테리어·제품", "인테리어|interior|제품|product|목업|mockup|패키지|package|가구|furniture"},
        {"도표·지도", "다이어그램|diagram|도표|인포그래픽|infographic|차트|chart|지도|map\\b|흐름도|flowchart"},
    };
    private static final Pattern[] KIND_PATTERNS = new Pattern[KINDS.length];
    static { for (int i = 0; i < KINDS.length; i++) KIND_PATTERNS[i] = Pattern.compile(KINDS[i][1], Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
    private Gallery() {}

    /** Exact renderer input when available; older pictures retain their recorded request/model revision. */
    public static String prompt(JSONObject entry) {
        for (String key : new String[]{"generationPrompt", "revised", "prompt"}) if (!entry.optString(key).isBlank()) return entry.optString(key);
        return "";
    }
    public static String promptLabel(JSONObject entry) {
        return !entry.optString("generationPrompt").isBlank() ? "생성 프롬프트" : !entry.optString("revised").isBlank() ? "이미지 모델 프롬프트" : "요청 프롬프트";
    }

    /** A new entry for a picture just drawn. */
    public static JSONObject entry(String id, String uri, String name, String prompt, String revised, String thread, String title, String model,
            long at, long size, int width, int height, String from, boolean photo) {
        JSONObject entry = obj("id", id, "uri", uri == null ? "" : uri, "name", name == null ? "그림" : name, "prompt", clip(prompt, 4000), "revised", clip(revised, 4000),
            "thread", thread == null ? "" : thread, "title", clip(title, 120), "model", model == null ? "" : model, "at", at, "size", Math.max(0, size),
            "w", Math.max(0, width), "h", Math.max(0, height), "favorite", false, "album", "");
        if (from != null && WorkBook.safeId(from)) put(entry, "from", from);
        if (photo) put(entry, "photo", true);
        return entry;
    }
    /** Adds an entry, newest first; the same picture is never listed twice, and past LIMIT the oldest unstarred one goes. Returns ids dropped. */
    public static List<String> add(JSONArray gallery, JSONObject entry) {
        List<String> dropped = new ArrayList<>();
        if (entry == null || !WorkBook.safeId(entry.optString("id")) || find(gallery, entry.optString("id")) != null) return dropped;
        JSONArray next = arr(); next.put(entry);
        for (int i = 0; i < gallery.length(); i++) next.put(gallery.opt(i));
        while (next.length() > LIMIT) {
            int drop = -1;
            for (int i = next.length() - 1; i > 0 && drop < 0; i--) if (!next.optJSONObject(i).optBoolean("favorite")) drop = i;
            if (drop < 0) drop = next.length() - 1;
            dropped.add(next.optJSONObject(drop).optString("id")); next.remove(drop);
        }
        replace(gallery, next);
        return dropped;
    }
    public static JSONObject find(JSONArray gallery, String id) {
        for (int i = 0; gallery != null && i < gallery.length(); i++) if (gallery.optJSONObject(i) != null && gallery.optJSONObject(i).optString("id").equals(id)) return gallery.optJSONObject(i);
        return null;
    }
    public static boolean remove(JSONArray gallery, String id) {
        for (int i = 0; i < gallery.length(); i++) if (gallery.optJSONObject(i) != null && gallery.optJSONObject(i).optString("id").equals(id)) { gallery.remove(i); return true; }
        return false;
    }
    public static void favorite(JSONArray gallery, String id, boolean on) { JSONObject entry = find(gallery, id); if (entry != null) put(entry, "favorite", on); }
    /** Puts the picture in an album ("" takes it out). */
    public static void album(JSONArray gallery, String id, String name) {
        JSONObject entry = find(gallery, id);
        if (entry != null) put(entry, "album", albumName(name));
    }
    /** The conversation's new name on its pictures; how many changed. */
    public static int retitle(JSONArray gallery, String thread, String title) {
        int changed = 0;
        for (int i = 0; gallery != null && thread != null && !thread.isEmpty() && i < gallery.length(); i++) {
            JSONObject entry = gallery.optJSONObject(i);
            if (entry != null && thread.equals(entry.optString("thread")) && !entry.optString("title").equals(clip(title, 120))) { put(entry, "title", clip(title, 120)); changed++; }
        }
        return changed;
    }
    public static String albumName(String name) { String value = name == null ? "" : name.strip().replaceAll("\\s+", " "); return value.length() > ALBUM_NAME ? value.substring(0, ALBUM_NAME) : value; }
    /** Albums in use, by name. */
    public static List<String> albums(JSONArray gallery) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (int i = 0; i < gallery.length(); i++) { String album = gallery.optJSONObject(i) == null ? "" : gallery.optJSONObject(i).optString("album"); if (!album.isBlank()) names.add(album); }
        return new ArrayList<>(names);
    }
    public static Set<String> ids(JSONArray gallery) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; gallery != null && i < gallery.length(); i++) if (gallery.optJSONObject(i) != null) ids.add(gallery.optJSONObject(i).optString("id"));
        return ids;
    }

    /** What the picture is of: a photo edit, or a kind matched from the request (then the image model's description), else OTHER. */
    public static String kind(JSONObject entry) {
        if (entry.optBoolean("photo")) return PHOTO_EDIT;
        for (String text : new String[]{entry.optString("prompt"), entry.optString("revised")})
            for (int i = 0; i < KINDS.length; i++) if (!text.isBlank() && KIND_PATTERNS[i].matcher(text).find()) return KINDS[i][0];
        return OTHER;
    }

    /**
     * The pictures to show for one view and search, in sections: [{title, items:[entry]}]. By date the sections run 오늘,
     * 어제, 이번 주, 이번 달, then month by month; by kind, conversation and album the biggest group comes first (albums by
     * name, those in none last); favorites by date.
     */
    public static JSONArray sections(JSONArray gallery, String view, String query, long now, ZoneId zone) {
        String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        Map<String, JSONArray> groups = new LinkedHashMap<>();
        for (int i = 0; gallery != null && i < gallery.length(); i++) {
            JSONObject entry = gallery.optJSONObject(i); if (entry == null) continue;
            if (FAVORITE.equals(view) && !entry.optBoolean("favorite")) continue;
            if (!matches(entry, words)) continue;
            String key = switch (view == null ? DATE : view) {
                case KIND -> kind(entry);
                case THREAD -> entry.optString("title").isBlank() ? "이름 없는 대화" : entry.optString("title");
                case ALBUM -> entry.optString("album").isBlank() ? NO_ALBUM : entry.optString("album");
                default -> dateGroup(now, entry.optLong("at"), zone);
            };
            groups.computeIfAbsent(key, k -> arr()).put(entry);
        }
        List<Map.Entry<String, JSONArray>> ordered = new ArrayList<>(groups.entrySet());
        if (KIND.equals(view) || THREAD.equals(view)) ordered.sort((a, b) -> b.getValue().length() - a.getValue().length());
        if (ALBUM.equals(view)) ordered.sort((a, b) -> a.getKey().equals(NO_ALBUM) ? 1 : b.getKey().equals(NO_ALBUM) ? -1 : a.getKey().compareTo(b.getKey()));
        JSONArray result = arr();
        for (Map.Entry<String, JSONArray> group : ordered) result.put(obj("title", group.getKey(), "items", group.getValue()));
        return result;
    }
    private static boolean matches(JSONObject entry, String[] words) {
        String text = (entry.optString("prompt") + "\n" + entry.optString("generationPrompt") + "\n" + entry.optString("revised") + "\n" + entry.optString("title") + "\n" + entry.optString("album") + "\n" + kind(entry) + "\n" + entry.optString("name")).toLowerCase(Locale.ROOT);
        for (String word : words) if (!word.isEmpty() && !text.contains(word)) return false;
        return true;
    }
    static String dateGroup(long now, long at, ZoneId zone) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate();
        long days = ChronoUnit.DAYS.between(day, today);
        if (days <= 0) return "오늘";
        if (days == 1) return "어제";
        if (days < 7) return "이번 주";
        if (day.getYear() == today.getYear() && day.getMonth() == today.getMonth()) return "이번 달";
        return day.getYear() == today.getYear() ? day.getMonthValue() + "월" : day.getYear() + "년 " + day.getMonthValue() + "월";
    }
    /** "방금", "5분 전", "3시간 전", "어제 14:05", "10월 3일", "2025년 10월 3일": when, as people say it. */
    public static String ago(long now, long at, ZoneId zone) {
        if (at <= 0) return "";
        long seconds = Math.max(0, (now - at) / 1000);
        if (seconds < 60) return "방금";
        if (seconds < 3600) return (seconds / 60) + "분 전";
        ZonedDateTime then = Instant.ofEpochMilli(at).atZone(zone), current = Instant.ofEpochMilli(now).atZone(zone);
        long days = ChronoUnit.DAYS.between(then.toLocalDate(), current.toLocalDate());
        if (days == 0) return (seconds / 3600) + "시간 전";
        if (days == 1) return String.format(Locale.ROOT, "어제 %d:%02d", then.getHour(), then.getMinute());
        return then.getYear() == current.getYear() ? then.getMonthValue() + "월 " + then.getDayOfMonth() + "일" : then.getYear() + "년 " + then.getMonthValue() + "월 " + then.getDayOfMonth() + "일";
    }

    /** Every version of the picture's line of edits, oldest first: the drawing it came from, back to the first, and those edited from them. */
    public static JSONArray versions(JSONArray gallery, String id) {
        JSONObject entry = find(gallery, id);
        if (entry == null) return arr();
        // Up to the first drawing (guarding against a loop), then every drawing made from one in the line.
        JSONObject root = entry; Set<String> seen = new HashSet<>();
        while (root.has("from") && seen.add(root.optString("id"))) { JSONObject parent = find(gallery, root.optString("from")); if (parent == null) break; root = parent; }
        Set<String> line = new HashSet<>(); line.add(root.optString("id"));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < gallery.length(); i++) {
                JSONObject other = gallery.optJSONObject(i);
                if (other != null && line.contains(other.optString("from")) && line.add(other.optString("id"))) grew = true;
            }
        }
        List<JSONObject> found = new ArrayList<>();
        for (int i = 0; i < gallery.length(); i++) if (gallery.optJSONObject(i) != null && line.contains(gallery.optJSONObject(i).optString("id"))) found.add(gallery.optJSONObject(i));
        found.sort((a, b) -> Long.compare(a.optLong("at"), b.optLong("at")));
        JSONArray result = arr();
        for (JSONObject version : found) result.put(version);
        return result;
    }

    /**
     * Entries for pictures drawn before the gallery existed, read from the work threads: each answer's drawings with the
     * request before it, oldest first so add() leaves the newest on top.
     */
    public static JSONArray backfill(JSONArray threads) {
        List<JSONObject> found = new ArrayList<>();
        for (int t = 0; threads != null && t < threads.length(); t++) {
            JSONObject thread = threads.optJSONObject(t); JSONArray messages = thread == null ? null : thread.optJSONArray("messages");
            String prompt = "";
            for (int m = 0; messages != null && m < messages.length(); m++) {
                JSONObject message = messages.optJSONObject(m); if (message == null) continue;
                if (WorkBook.USER.equals(message.optString("role"))) { prompt = message.optString("text"); continue; }
                JSONArray files = message.optJSONArray("files");
                for (int f = 0; files != null && f < files.length(); f++) {
                    JSONObject file = files.optJSONObject(f);
                    if (file == null || !WorkBook.safeId(file.optString("picture"))) continue;
                    JSONObject recovered = entry(file.optString("picture"), file.optString("uri"), file.optString("name"), prompt, "", thread.optString("id"), thread.optString("title"),
                        message.optString("model"), message.optLong("at"), file.optLong("size"), 0, 0, "", false);
                    put(recovered, "generationPrompt", file.optString("generationPrompt")); found.add(recovered);
                }
            }
        }
        found.sort((a, b) -> Long.compare(a.optLong("at"), b.optLong("at")));
        JSONArray result = arr();
        for (JSONObject entry : found) result.put(entry);
        return result;
    }

    private static String clip(String text, int limit) { String value = text == null ? "" : text.strip(); return value.length() > limit ? value.substring(0, limit) : value; }
    private static void replace(JSONArray target, JSONArray from) { while (target.length() > 0) target.remove(0); for (int i = 0; i < from.length(); i++) target.put(from.opt(i)); }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
}
