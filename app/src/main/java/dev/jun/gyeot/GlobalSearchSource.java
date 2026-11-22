package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.GlobalSearch;
import dev.jun.gyeot.core.GlobalSearch.Hit;
import dev.jun.gyeot.core.PageBook;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads the app's stores for GlobalSearch: what holds the words, and the whole text of one result. Off the main thread. */
final class GlobalSearchSource {
    private GlobalSearchSource() {}
    /** The most results of one kind, and the folders of fetched texts. */
    static final int PER_KIND = 40;
    static final String[] KEPT_KINDS = {YouTubeTranscriptClient.KIND, WebArticleClient.KIND};

    /** Everything that holds every word of query: title matches first, then the newest, at most PER_KIND of a kind. */
    static List<Hit> find(Context context, AppStore store, String query) {
        List<String> words = GlobalSearch.words(query); List<Hit> out = new ArrayList<>();
        if (words.isEmpty()) return out;
        out.addAll(GlobalSearch.ranked(chat(store, words), PER_KIND));
        out.addAll(GlobalSearch.ranked(works(store, words), PER_KIND));
        out.addAll(GlobalSearch.ranked(pages(context, query, words), PER_KIND));
        out.addAll(GlobalSearch.ranked(memories(store, words), PER_KIND));
        out.addAll(GlobalSearch.ranked(notes(store, words), PER_KIND));
        out.addAll(GlobalSearch.ranked(voices(store, words), PER_KIND));
        out.addAll(GlobalSearch.ranked(recordings(store, words), PER_KIND));
        out.addAll(GlobalSearch.ranked(kept(context, words), PER_KIND));
        return out;
    }

    private static void add(List<Hit> out, Hit hit) { if (hit != null) out.add(hit); }
    private static List<Hit> chat(AppStore store, List<String> words) {
        List<Hit> out = new ArrayList<>();
        try {
            JSONArray rows = store.mainChat.containing(words, PER_KIND);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                out.add(new Hit(GlobalSearch.CHAT, "" + row.optLong("id"), "user".equals(row.optString("role")) ? "내가 쓴 말" : Brand.NAME + "의 답", GlobalSearch.snippet(row.optString("text"), words, GlobalSearch.SNIPPET), row.optLong("created"), false));
            }
        } catch (RuntimeException closed) { /* The main chat is not open yet: the other kinds are still searched. */ }
        return out;
    }
    private static String workText(JSONObject thread) {
        StringBuilder text = new StringBuilder(); JSONArray messages = thread.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length() && text.length() < 400_000; i++) {
            JSONObject message = messages.optJSONObject(i); String words = message == null ? "" : message.optString("text");
            if (!words.isBlank()) text.append("user".equals(message.optString("role")) ? "나: " : "").append(words.strip()).append("\n\n");
        }
        return text.toString().strip();
    }
    private static List<Hit> works(AppStore store, List<String> words) {
        List<Hit> out = new ArrayList<>(); JSONArray threads = store.works();
        for (int i = 0; i < threads.length(); i++) {
            JSONObject thread = threads.optJSONObject(i); if (thread == null) continue;
            JSONArray tags = thread.optJSONArray("tags"); StringBuilder tagged = new StringBuilder();
            for (int t = 0; tags != null && t < tags.length(); t++) tagged.append(tags.optString(t)).append(' ');
            add(out, GlobalSearch.hit(GlobalSearch.WORK, thread.optString("id"), thread.optString("title"), workText(thread) + "\n" + tagged, Math.max(thread.optLong("updatedAt"), thread.optLong("createdAt")), words));
        }
        return out;
    }
    private static List<Hit> pages(Context context, String query, List<String> words) {
        List<Hit> out = new ArrayList<>(); PageBook book = PageStore.book(context);
        for (PageBook.Page page : book.find(String.join(" ", words), null, PER_KIND)) {
            String body; try { body = book.body(page.id()); } catch (Exception missing) { body = ""; }
            Hit hit = GlobalSearch.hit(GlobalSearch.PAGE, page.id(), page.title(), body, page.updated(), words);
            // Found by its category or tags alone: still a result, with the page's first words.
            out.add(hit != null ? hit : new Hit(GlobalSearch.PAGE, page.id(), page.title(), GlobalSearch.snippet(body, words, GlobalSearch.SNIPPET), page.updated(), false));
        }
        return out;
    }
    private static List<Hit> memories(AppStore store, List<String> words) {
        List<Hit> out = new ArrayList<>(); JSONArray list = store.memories();
        for (int i = 0; i < list.length(); i++) {
            JSONObject memory = list.optJSONObject(i); if (memory == null) continue;
            Hit hit = GlobalSearch.hit(GlobalSearch.MEMORY, memory.optString("id", "" + i), "", memory.optString("text"), Math.max(memory.optLong("updatedAt"), memory.optLong("createdAt")), words);
            if (hit != null) out.add(new Hit(hit.kind(), hit.id(), "기억", hit.snippet(), hit.time(), false));
        }
        return out;
    }
    private static List<Hit> notes(AppStore store, List<String> words) {
        List<Hit> out = new ArrayList<>(); JSONArray list = store.notes();
        for (int i = 0; i < list.length(); i++) { JSONObject note = list.optJSONObject(i); if (note != null) add(out, GlobalSearch.hit(GlobalSearch.NOTE, note.optString("id"), note.optString("title"), note.optString("body"), note.optLong("createdAt"), words)); }
        return out;
    }
    static String voiceTitle(JSONObject session) {
        String mode = session.optString("mode");
        return "interpreter".equals(mode) ? "통역 기록" : "dictation".equals(mode) ? "받아쓰기 기록" : "음성 대화 기록";
    }
    private static List<Hit> voices(AppStore store, List<String> words) {
        List<Hit> out = new ArrayList<>(); JSONArray list = store.allSessions();
        for (int i = 0; i < list.length(); i++) {
            JSONObject session = list.optJSONObject(i); if (session == null) continue;
            Hit hit = GlobalSearch.hit(GlobalSearch.VOICE, session.optString("id"), "", AppStore.sessionText(session), session.optLong("startedAt"), words);
            if (hit != null) out.add(new Hit(hit.kind(), hit.id(), voiceTitle(session), hit.snippet(), hit.time(), false));
        }
        return out;
    }
    private static List<Hit> recordings(AppStore store, List<String> words) {
        List<Hit> out = new ArrayList<>(); JSONArray list = store.recordings();
        for (int i = 0; i < list.length(); i++) { JSONObject recording = list.optJSONObject(i); if (recording != null) add(out, GlobalSearch.hit(GlobalSearch.RECORDING, recording.optString("id"), recording.optString("title"), recording.optString("transcript") + "\n" + recording.optString("summary"), recording.optLong("createdAt"), words)); }
        return out;
    }
    private static List<Hit> kept(Context context, List<String> words) {
        List<Hit> out = new ArrayList<>();
        for (String kind : KEPT_KINDS) {
            File[] files = new File(context.getFilesDir(), kind).listFiles((dir, name) -> name.endsWith(".txt"));
            for (int i = 0; files != null && i < files.length; i++) {
                String key = files[i].getName().substring(0, files[i].getName().length() - 4), text = KeptText.saved(context, kind, key);
                String title = GlobalSearch.keptTitle(text);
                add(out, GlobalSearch.hit(GlobalSearch.KEPT, kind + "/" + key, title.isEmpty() ? keptName(kind) : title, GlobalSearch.keptBody(text), files[i].lastModified(), words));
            }
        }
        return out;
    }
    static String keptName(String kind) { return YouTubeTranscriptClient.KIND.equals(kind) ? "유튜브 대본" : "가져온 웹 글"; }

    /** The newest main chat messages 뜻으로 찾기 indexes. */
    static final int SEMANTIC_CHAT = 300;
    /**
     * Everything 뜻으로 찾기 (SemanticIndex) indexes, one Doc a thing, with the same kind and id a word search gives, so a
     * result opens the same way. Off the main thread.
     */
    static List<dev.jun.gyeot.core.SemanticSearch.Doc> docs(Context context, AppStore store) {
        List<dev.jun.gyeot.core.SemanticSearch.Doc> out = new ArrayList<>();
        try {
            long before = 0;
            for (int read = 0; read < SEMANTIC_CHAT; ) {
                JSONArray rows = store.mainChat.page(before, 40); if (rows.length() == 0) break;
                for (int i = rows.length() - 1; i >= 0; i--) {
                    JSONObject row = rows.optJSONObject(i); if (row == null) continue;
                    String role = row.optString("role");
                    if ("user".equals(role) || "assistant".equals(role)) out.add(new dev.jun.gyeot.core.SemanticSearch.Doc(GlobalSearch.CHAT, "" + row.optLong("id"), "user".equals(role) ? "내가 쓴 말" : Brand.NAME + "의 답", row.optString("text"), row.optLong("created")));
                }
                read += rows.length(); long first = rows.optJSONObject(0) == null ? 0 : rows.optJSONObject(0).optLong("id");
                if (first <= 1 || first == before) break; before = first;
            }
        } catch (RuntimeException closed) { /* The main chat is not open yet. */ }
        JSONArray threads = store.works();
        for (int i = 0; i < threads.length(); i++) {
            JSONObject thread = threads.optJSONObject(i); if (thread == null) continue;
            out.add(new dev.jun.gyeot.core.SemanticSearch.Doc(GlobalSearch.WORK, thread.optString("id"), thread.optString("title"), workText(thread), Math.max(thread.optLong("updatedAt"), thread.optLong("createdAt"))));
        }
        PageBook book = PageStore.book(context);
        for (PageBook.Page page : book.pages()) {
            String body; try { body = book.body(page.id()); } catch (Exception missing) { body = ""; }
            out.add(new dev.jun.gyeot.core.SemanticSearch.Doc(GlobalSearch.PAGE, page.id(), page.title(), body, page.updated()));
        }
        JSONArray list = store.memories();
        for (int i = 0; i < list.length(); i++) {
            JSONObject memory = list.optJSONObject(i); if (memory == null) continue;
            out.add(new dev.jun.gyeot.core.SemanticSearch.Doc(GlobalSearch.MEMORY, memory.optString("id", "" + i), "기억", memory.optString("text"), Math.max(memory.optLong("updatedAt"), memory.optLong("createdAt"))));
        }
        list = store.notes();
        for (int i = 0; i < list.length(); i++) { JSONObject note = list.optJSONObject(i); if (note != null) out.add(new dev.jun.gyeot.core.SemanticSearch.Doc(GlobalSearch.NOTE, note.optString("id"), note.optString("title"), note.optString("body"), note.optLong("createdAt"))); }
        list = store.allSessions();
        for (int i = 0; i < list.length(); i++) {
            JSONObject session = list.optJSONObject(i); if (session == null) continue;
            String title = session.optString("title").isBlank() ? voiceTitle(session) : session.optString("title"), summary = session.optString("summary");
            out.add(new dev.jun.gyeot.core.SemanticSearch.Doc(GlobalSearch.VOICE, session.optString("id"), title, (summary.isBlank() ? "" : summary + "\n") + AppStore.sessionText(session), session.optLong("startedAt")));
        }
        list = store.recordings();
        for (int i = 0; i < list.length(); i++) {
            JSONObject recording = list.optJSONObject(i); if (recording == null) continue;
            String summary = recording.optString("summary");
            out.add(new dev.jun.gyeot.core.SemanticSearch.Doc(GlobalSearch.RECORDING, recording.optString("id"), recording.optString("title"), (summary.isBlank() ? "" : summary + "\n") + recording.optString("transcript"), recording.optLong("createdAt")));
        }
        for (String kind : KEPT_KINDS) {
            File[] files = new File(context.getFilesDir(), kind).listFiles((dir, name) -> name.endsWith(".txt"));
            for (int i = 0; files != null && i < files.length; i++) {
                String key = files[i].getName().substring(0, files[i].getName().length() - 4), text = KeptText.saved(context, kind, key), title = GlobalSearch.keptTitle(text);
                out.add(new dev.jun.gyeot.core.SemanticSearch.Doc(GlobalSearch.KEPT, kind + "/" + key, title.isEmpty() ? keptName(kind) : title, GlobalSearch.keptBody(text), files[i].lastModified()));
            }
        }
        return out;
    }

    /** The whole text of one result, to read in a sheet; "" when it is gone. */
    static String text(Context context, AppStore store, Hit hit) {
        try {
            switch (hit.kind()) {
                case GlobalSearch.CHAT -> { JSONObject row = store.mainChat.message(Long.parseLong(hit.id())); return row == null ? "" : row.optString("text"); }
                case GlobalSearch.WORK -> { JSONObject thread = dev.jun.gyeot.core.WorkBook.find(store.works(), hit.id()); return thread == null ? "" : workText(thread); }
                case GlobalSearch.PAGE -> { return PageStore.book(context).body(hit.id()); }
                case GlobalSearch.MEMORY -> { JSONArray list = store.memories(); for (int i = 0; i < list.length(); i++) if (list.optJSONObject(i).optString("id", "" + i).equals(hit.id())) return list.optJSONObject(i).optString("text"); return ""; }
                case GlobalSearch.NOTE -> { JSONArray list = store.notes(); for (int i = 0; i < list.length(); i++) if (list.optJSONObject(i).optString("id").equals(hit.id())) return list.optJSONObject(i).optString("body"); return ""; }
                case GlobalSearch.VOICE -> { JSONArray list = store.allSessions(); for (int i = 0; i < list.length(); i++) if (list.optJSONObject(i).optString("id").equals(hit.id())) return AppStore.sessionText(list.optJSONObject(i)); return ""; }
                case GlobalSearch.RECORDING -> { JSONArray list = store.recordings(); for (int i = 0; i < list.length(); i++) if (list.optJSONObject(i).optString("id").equals(hit.id())) { String summary = list.optJSONObject(i).optString("summary"); return (summary.isBlank() ? "" : summary.strip() + "\n\n") + list.optJSONObject(i).optString("transcript"); } return ""; }
                case GlobalSearch.KEPT -> { int cut = hit.id().indexOf('/'); return cut < 0 ? "" : GlobalSearch.keptBody(KeptText.saved(context, hit.id().substring(0, cut), hit.id().substring(cut + 1))); }
                default -> { return ""; }
            }
        } catch (Exception gone) { return ""; }
    }
}
