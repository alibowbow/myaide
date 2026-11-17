package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Backups of what the assistant remembers, for 들퍼레/백업: the long-term notes (memories.json), the "나에 대해" text and
 * the main chat's pinned notes, as a versioned JSON the app reads back and a Markdown copy for people. Importing merges and
 * never replaces: what is kept stays as it is, duplicates and secret-looking text are left out, the stores' caps hold (no
 * older note is pushed out), and everything left out is counted for the preview. Also picks memories out of another AI's
 * answer to PROMPT, through the same filters.
 */
public final class MemoryBackup {
    public static final String FORMAT = "dev.jun.gyeot.memory-backup", FOLDER = "백업", JSON = "application/json", MARKDOWN = "text/markdown";
    public static final int VERSION = 1;
    /** The largest backup read back; a full memory (300 notes) stays well below it. */
    public static final int MAX_BYTES = 1024 * 1024;
    /** Main chat pinned notes as MainChatStore.validateNote keeps them: kinds, count, total and per-note characters. */
    public static final List<String> NOTE_KINDS = List.of("제약", "선호", "사실");
    public static final int NOTES = 32, NOTE_CHARS = 5000, NOTE_TEXT = 800;
    /** How much of another AI's answer is read, and how many notes it may offer. */
    public static final int PASTE_LIMIT = 20000, PASTE_ITEMS = 100;
    /** What the user pastes into another AI so it answers with what it remembers about them, one fact per line. */
    public static final String PROMPT = "내가 알려 준 나에 대한 기억을 목록으로 정리해 줘. 이름과 호칭, 가족과 가까운 사람, 하는 일, 취향, 습관, 진행 중인 일과 계획처럼 오래 기억해 둘 만한 것만 골라 줘. "
        + "한 줄에 하나씩 ‘- ’로 시작하는 짧은 한국어 문장으로 쓰고, 다른 설명은 붙이지 마. 비밀번호, 인증번호, 카드·계좌 번호, API 키 같은 정보는 빼 줘.";
    private static final String FOREIGN = Brand.NAME + " 기억 백업 파일이 아니에요. 들퍼레/백업의 기억-….json 파일을 골라 주세요.",
        BROKEN = "백업 파일이 손상돼서 가져오지 않았어요. 다른 백업을 골라 주세요.";
    /** A list marker at the start of a line: -, *, •, ·, 1., 2), (3). */
    private static final Pattern BULLET = Pattern.compile("^(?:[-*+]\\s+|[•·▪◦‣–—]\\s*|\\d{1,3}[.)]\\s+|\\(\\d{1,3}\\)\\s*)");
    /** A line that is only a bold heading, e.g. **가족** or **취향:**. */
    private static final Pattern HEADING = Pattern.compile("^\\*\\*[^*]+\\*\\*\\s*[:：]?$");

    private MemoryBackup() {}

    /** One kept thing: kind (about/phone in memory, 제약/선호/사실 for a pinned note), its text, the app of a phone recipe, and its times. */
    public record Item(String kind, String text, String app, long createdAt, long updatedAt) {}
    /** A backup file read back; broken counts entries that were not readable. */
    public record Backup(long exportedAt, List<Item> memories, String persona, List<Item> notes, int broken) {}
    /** What was left out, and why. */
    public record Skipped(int duplicates, int secrets, int full, int invalid, int unavailable) {
        public int total() { return duplicates + secrets + full + invalid + unavailable; }
        /** "이미 있는 것 3개 · 한도가 차서 1개"; empty when nothing was left out. */
        public String text() {
            List<String> parts = new ArrayList<>();
            if (duplicates > 0) parts.add("이미 있는 것 " + duplicates + "개");
            if (secrets > 0) parts.add("비밀번호·인증번호처럼 보이는 것 " + secrets + "개");
            if (full > 0) parts.add("한도가 차서 " + full + "개");
            if (invalid > 0) parts.add("비었거나 형식이 맞지 않는 것 " + invalid + "개");
            if (unavailable > 0) parts.add("메인 대화 저장소를 열지 못한 고정 기억 " + unavailable + "개");
            return String.join(" · ", parts);
        }
    }
    /** What an import adds: new memories and pinned notes, and the merged "나에 대해" (null when it stays) with how many lines it gains. */
    public record Plan(List<Item> memories, List<Item> notes, String persona, int personaLines, Skipped skipped) {
        public boolean empty() { return memories.isEmpty() && notes.isEmpty() && persona == null; }
    }
    /** Notes found in another AI's answer that are new and may be kept, and what was left out. */
    public record Picked(List<String> notes, Skipped skipped) {}

    private static final class Tally {
        int duplicates, secrets, full, invalid, unavailable;
        Skipped done() { return new Skipped(duplicates, secrets, full, invalid, unavailable); }
    }

    /** The backup of the long-term notes, "나에 대해" and the main chat's pinned notes (rows of MainChatStore.notes). */
    public static JSONObject export(JSONArray memories, String persona, JSONArray notes, long now) {
        JSONArray kept = arr(), pinned = arr();
        for (int i = 0; memories != null && i < memories.length(); i++) {
            JSONObject note = memories.optJSONObject(i);
            if (note == null || note.optString("text").isBlank()) continue;
            JSONObject item = obj("kind", Memory.PHONE.equals(note.optString("kind")) ? Memory.PHONE : Memory.ABOUT, "text", note.optString("text"),
                "createdAt", note.optLong("createdAt"), "updatedAt", note.optLong("updatedAt", note.optLong("createdAt")));
            if (!note.optString("app").isBlank()) put(item, "app", note.optString("app"));
            kept.put(item);
        }
        for (int i = 0; notes != null && i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note != null && !note.optString("text").isBlank()) pinned.put(obj("kind", note.optString("kind"), "text", note.optString("text"), "createdAt", note.optLong("created")));
        }
        return obj("format", FORMAT, "version", VERSION, "exportedAt", now, "memories", kept, "persona", persona(persona), "notes", pinned);
    }
    /** "나에 대해" as written, without the lines that look secret: the backup sits in shared storage, and the rest is kept already filtered. */
    public static String persona(String text) {
        StringBuilder kept = new StringBuilder();
        for (String line : text == null ? new String[0] : text.split("\\R", -1)) if (!Memory.sensitive(line)) kept.append(line).append('\n');
        return kept.toString().strip();
    }
    /** How many lines of "나에 대해" look secret and stay out of a backup. */
    public static int secretLines(String text) {
        int count = 0;
        for (String line : text == null ? new String[0] : text.split("\\R")) if (Memory.sensitive(line)) count++;
        return count;
    }
    /** True when a backup would hold nothing. */
    public static boolean empty(JSONObject backup) {
        return length(backup.optJSONArray("memories")) == 0 && length(backup.optJSONArray("notes")) == 0 && backup.optString("persona").isBlank();
    }
    /** "기억 12개 · 나에 대해 · 고정 기억 3개": what a backup holds. */
    public static String counts(JSONObject backup) {
        return counts(length(backup.optJSONArray("memories")), !backup.optString("persona").isBlank(), length(backup.optJSONArray("notes")));
    }
    private static String counts(int memories, boolean persona, int notes) {
        return "기억 " + memories + "개" + (persona ? " · 나에 대해" : "") + " · 고정 기억 " + notes + "개";
    }
    /** The file text the app reads back. */
    public static String json(JSONObject backup) {
        try { return backup.toString(2) + "\n"; } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    /** 기억-20261107-1420.json: the export time in the phone's zone. */
    public static String fileName(long now, ZoneId zone, String extension) {
        ZonedDateTime at = Instant.ofEpochMilli(now).atZone(zone);
        return String.format(Locale.ROOT, "기억-%04d%02d%02d-%02d%02d.%s", at.getYear(), at.getMonthValue(), at.getDayOfMonth(), at.getHour(), at.getMinute(), extension);
    }
    /** The same backup for people to read; it names json, the saved file that is imported. */
    public static String markdown(JSONObject backup, ZoneId zone, String json) {
        StringBuilder md = new StringBuilder("# ").append(Brand.NAME).append(" 기억 백업\n\n")
            .append(MainChatContext.now(backup.optLong("exportedAt"), zone)).append("에 내보냈어요. 앱에서 다시 가져올 때는 ").append(json).append(" 파일을 골라요.\n");
        String persona = backup.optString("persona").strip();
        if (!persona.isEmpty()) md.append("\n## 나에 대해\n\n").append(persona).append('\n');
        JSONArray memories = backup.optJSONArray("memories"), notes = backup.optJSONArray("notes");
        for (String[] kind : new String[][]{{Memory.ABOUT, "나에 대해 기억한 것"}, {Memory.PHONE, "배운 폰 사용법"}}) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; memories != null && i < memories.length(); i++) {
                JSONObject note = memories.optJSONObject(i);
                if (note != null && kind[0].equals(note.optString("kind"))) lines.add((note.optString("app").isBlank() ? "" : "[" + note.optString("app") + "] ") + note.optString("text"));
            }
            section(md, kind[1], lines);
        }
        List<String> pinned = new ArrayList<>();
        for (int i = 0; notes != null && i < notes.length(); i++) { JSONObject note = notes.optJSONObject(i); if (note != null) pinned.add("[" + note.optString("kind") + "] " + note.optString("text")); }
        section(md, "메인 대화 고정 기억", pinned);
        return md.toString();
    }
    private static void section(StringBuilder md, String title, List<String> lines) {
        if (lines.isEmpty()) return;
        md.append("\n## ").append(title).append(" (").append(lines.size()).append("개)\n\n");
        for (String line : lines) md.append("- ").append(line.replaceAll("\\s*\\R\\s*", " ").strip()).append('\n');
    }

    /** Reads a backup file; anything that is not one is refused with a message for the user. */
    public static Backup parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("백업 파일이 비어 있어요.");
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("백업 파일이 너무 커요. 1MB 이하 파일만 가져올 수 있어요.");
        JSONObject root;
        try { root = new JSONObject(text.strip()); } catch (Exception e) { throw new IllegalArgumentException(FOREIGN); }
        if (!FORMAT.equals(root.optString("format"))) throw new IllegalArgumentException(FOREIGN);
        int version = root.optInt("version", 0);
        if (version > VERSION) throw new IllegalArgumentException("더 새로운 " + Brand.NAME + "에서 만든 백업이에요. 앱을 업데이트한 뒤 가져와 주세요.");
        JSONArray memories = root.optJSONArray("memories"), notes = root.optJSONArray("notes");
        Object persona = root.opt("persona");
        if (version < 1 || memories == null || notes == null || (persona != null && !(persona instanceof String))) throw new IllegalArgumentException(BROKEN);
        // The app never exports more than it keeps; a larger file is not one of its backups and would only slow the merge.
        if (memories.length() > Memory.LIMIT || notes.length() > NOTES)
            throw new IllegalArgumentException("백업에 든 기억이 너무 많아요. 기억 " + Memory.LIMIT + "개·고정 기억 " + NOTES + "개까지 든 백업만 가져올 수 있어요.");
        int[] broken = {0};
        return new Backup(root.optLong("exportedAt"), items(memories, broken), persona == null ? "" : (String) persona, items(notes, broken), broken[0]);
    }
    private static List<Item> items(JSONArray list, int[] broken) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.optJSONObject(i);
            if (entry == null || !(entry.opt("text") instanceof String text) || !(entry.opt("kind") instanceof String kind)) { broken[0]++; continue; }
            items.add(new Item(kind, text, entry.opt("app") instanceof String app ? app : "", entry.optLong("createdAt"), entry.optLong("updatedAt")));
        }
        return items;
    }

    /**
     * What importing backup into the current memory, "나에 대해" and pinned notes would add, and what it leaves out.
     * notes is null when the main chat's store is not open: its notes are then left out and counted.
     */
    public static Plan plan(Backup backup, JSONArray memories, String persona, JSONArray notes, long now) {
        Tally tally = new Tally(); tally.invalid += backup.broken();
        List<Item> kept = merge(copy(memories == null ? arr() : memories), backup.memories(), now, tally);
        List<Item> pinned = List.of();
        if (notes == null) tally.unavailable += backup.notes().size(); else pinned = pin(notes, backup.notes(), tally);
        // "나에 대해" comes back as written when there is none yet; otherwise it gains the whole lines it lacks.
        String base = persona == null ? "" : persona.strip(), restored = persona(backup.persona());
        tally.secrets += secretLines(backup.persona());
        int lines = 0;
        if (base.isEmpty() && !restored.isEmpty() && restored.length() <= GeminiProtocol.PERSONA_LIMIT) {
            for (String line : restored.split("\\R")) if (!line.isBlank()) lines++;
            return new Plan(kept, pinned, restored, lines, tally.done());
        }
        Set<String> have = new HashSet<>(); for (String line : base.split("\\R")) have.add(key(line));
        StringBuilder merged = new StringBuilder(base);
        for (String line : restored.split("\\R")) {
            String value = line.strip();
            if (value.isEmpty()) continue;
            if (!have.add(key(value))) { tally.duplicates++; continue; }
            if (merged.length() + (merged.length() > 0 ? 1 : 0) + value.length() > GeminiProtocol.PERSONA_LIMIT) { tally.full++; continue; }
            if (merged.length() > 0) merged.append('\n');
            merged.append(value); lines++;
        }
        return new Plan(kept, pinned, lines == 0 ? null : merged.toString(), lines, tally.done());
    }
    /** The preview shown before anything is merged: what the backup holds, what goes in, what is left out and why. */
    public static String preview(Backup backup, Plan plan) {
        int phone = 0; for (Item item : plan.memories()) if (Memory.PHONE.equals(item.kind())) phone++;
        List<String> adds = new ArrayList<>();
        if (!plan.memories().isEmpty()) adds.add("기억 " + plan.memories().size() + "개" + (phone > 0 ? "(폰 사용법 " + phone + "개 포함)" : ""));
        if (plan.persona() != null) adds.add("‘나에 대해’ " + plan.personaLines() + "줄");
        if (!plan.notes().isEmpty()) adds.add("메인 대화 고정 기억 " + plan.notes().size() + "개");
        StringBuilder text = new StringBuilder("백업에 든 것: ").append(counts(backup.memories().size(), !backup.persona().isBlank(), backup.notes().size()));
        if (backup.exportedAt() > 0) text.append("\n내보낸 때: ").append(MainChatContext.now(backup.exportedAt()));
        text.append("\n\n").append(adds.isEmpty() ? "새로 합칠 것이 없어요." : "합칠 것: " + String.join(" · ", adds));
        if (plan.skipped().total() > 0) text.append("\n뺄 것: ").append(plan.skipped().text());
        if (plan.skipped().full() > 0) text.append("\n한도: 기억 ").append(Memory.LIMIT).append("개, 고정 기억 ").append(NOTES).append("개·총 5,000자, ‘나에 대해’ 1,000자");
        return text.append("\n\n지금 있는 기억은 지우거나 바꾸지 않고 그대로 둬요.").toString();
    }

    /** Adds the new ones of items to the long-term memory list with Memory.add, never past Memory.LIMIT; returns how many. */
    public static int addMemories(JSONArray list, List<Item> items, long now) { return merge(list, items, now, new Tally()).size(); }
    /** Adds what is new, safe and fits, keeping the backup's times; no older note is pushed out to make room. */
    private static List<Item> merge(JSONArray list, List<Item> items, long now, Tally tally) {
        List<Item> added = new ArrayList<>();
        for (Item item : items) {
            String kind = item.kind(), text = item.text() == null ? "" : item.text().strip();
            if (!Memory.ABOUT.equals(kind) && !Memory.PHONE.equals(kind) || text.isEmpty()) { tally.invalid++; continue; }
            if (Memory.sensitive(text)) { tally.secrets++; continue; }
            if (Memory.vague(text)) { tally.invalid++; continue; }
            if (Memory.match(list, kind, text, item.app()) != null) { tally.duplicates++; continue; }
            if (list.length() >= Memory.LIMIT) { tally.full++; continue; }
            JSONObject note;
            try { note = Memory.add(list, kind, text, item.app(), now); } catch (IllegalArgumentException refused) { tally.invalid++; continue; }
            long created = past(item.createdAt(), now), updated = past(item.updatedAt(), now);
            if (created > 0) put(note, "createdAt", created);
            if (updated > 0) put(note, "updatedAt", Math.max(updated, created));
            added.add(item);
        }
        return added;
    }
    /** Pinned notes that are new, safe and fit MainChatStore's caps next to the current ones. */
    private static List<Item> pin(JSONArray current, List<Item> items, Tally tally) {
        Set<String> seen = new HashSet<>(); int count = 0, chars = 0;
        for (int i = 0; i < current.length(); i++) {
            JSONObject note = current.optJSONObject(i);
            if (note == null) continue;
            seen.add(note.optString("kind") + "|" + key(note.optString("text"))); count++; chars += note.optString("text").length();
        }
        List<Item> added = new ArrayList<>();
        for (Item item : items) {
            String text = item.text() == null ? "" : item.text();
            if (!NOTE_KINDS.contains(item.kind()) || text.isBlank() || text.length() > NOTE_TEXT) { tally.invalid++; continue; }
            if (Memory.sensitive(text)) { tally.secrets++; continue; }
            if (!seen.add(item.kind() + "|" + key(text))) { tally.duplicates++; continue; }
            if (count >= NOTES || chars + text.length() > NOTE_CHARS) { tally.full++; continue; }
            count++; chars += text.length(); added.add(item);
        }
        return added;
    }

    /** The memories in another AI's answer to PROMPT that are new and may be kept, through the same filters as an import. */
    public static Picked pick(String answer, JSONArray memories, long now) {
        Tally tally = new Tally(); List<Item> items = new ArrayList<>();
        for (String line : lines(answer, tally)) items.add(new Item(Memory.ABOUT, line, "", 0, 0));
        List<String> notes = new ArrayList<>();
        for (Item item : merge(copy(memories == null ? arr() : memories), items, now, tally)) notes.add(item.text());
        return new Picked(notes, tally.done());
    }
    /** The items as a list answer gives them (markers dropped, lines without one skipped when others have one), or an extraction-style {"add":[…]}. */
    private static List<String> lines(String answer, Tally tally) {
        String text = answer == null ? "" : answer.length() > PASTE_LIMIT ? answer.substring(0, PASTE_LIMIT) : answer;
        String bare = text.strip().replaceFirst("^```[A-Za-z]*\\s*", "").replaceFirst("\\s*```$", "").strip();
        if (bare.startsWith("{") || bare.startsWith("[")) {
            try {
                JSONArray list = bare.startsWith("{") ? new JSONObject(bare).optJSONArray("add") : new JSONArray(bare);
                List<String> found = new ArrayList<>();
                for (int i = 0; list != null && i < list.length(); i++) { if (list.opt(i) instanceof String value) found.add(value); else tally.invalid++; }
                return cap(found, tally);
            } catch (Exception notJson) { /* Read it as lines. */ }
        }
        List<String> marked = new ArrayList<>(), plain = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("```") || line.startsWith("#") || line.endsWith(":") || line.endsWith("：") || HEADING.matcher(line).matches()) continue;
            Matcher bullet = BULLET.matcher(line); boolean listed = bullet.find();
            String value = (listed ? line.substring(bullet.end()) : line).replace("**", "").replace("__", "").replaceAll("\\s+", " ").strip();
            if (!value.isEmpty()) (listed ? marked : plain).add(value);
        }
        return cap(marked.isEmpty() ? plain : marked, tally);
    }
    private static List<String> cap(List<String> found, Tally tally) {
        if (found.size() > PASTE_ITEMS) { tally.full += found.size() - PASTE_ITEMS; found = found.subList(0, PASTE_ITEMS); }
        List<String> result = new ArrayList<>();
        for (String value : found) { String line = value.replaceAll("\\s+", " ").strip(); result.add(line.length() > Memory.TEXT_LIMIT ? line.substring(0, Memory.TEXT_LIMIT) : line); }
        return result;
    }

    /** A kept time from the file, or 0 when it is missing or in the future. */
    private static long past(long at, long now) { return at > 0 && at <= now ? at : 0; }
    private static String key(String text) { return text == null ? "" : text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT); }
    private static int length(JSONArray list) { return list == null ? 0 : list.length(); }
}
