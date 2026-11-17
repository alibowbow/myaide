package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.PageBook;
import java.io.File;

/**
 * The app's one PageBook, in its private files: the 페이지 screen, the main chat's and the 작업창's tools and
 * the save sheets all read and change the same pages. Not in the 들퍼레 folder, where the app may not rename, move or replace
 * a document in every storage mode, and not backed up (the app allows no backup): 공유 keeps a copy elsewhere.
 */
final class PageStore {
    private static PageBook book;
    private PageStore() {}
    static synchronized PageBook book(Context context) {
        if (book == null) book = new PageBook(new File(context.getApplicationContext().getFilesDir(), "pages"));
        return book;
    }
    /** Tests start from an empty folder. */
    static synchronized void reset() { book = null; recent.clear(); about.clear(); }
    /**
     * Per conversation (the main chat, a 작업창 room), the pages its last two replies or runs saved or changed, in memory only:
     * a follow-up ("거기에 한 줄 더 넣어 줘", "제목도 바꿔 줘") may change those without naming 페이지 again (PageTools.asked), while
     * the words that allow a save or a change are always the current request's alone.
     */
    private static final java.util.Map<String, java.util.ArrayDeque<java.util.Set<String>>> recent = new java.util.LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, java.util.ArrayDeque<java.util.Set<String>>> eldest) { return size() > 64; }
    };
    /** Starts a reply or run of the conversation: {its own pages so far, the set it notes its saves and changes in}. */
    static synchronized java.util.List<java.util.Set<String>> begin(String conversation) {
        java.util.ArrayDeque<java.util.Set<String>> turns = recent.computeIfAbsent(conversation, k -> new java.util.ArrayDeque<>());
        java.util.Set<String> own = new java.util.HashSet<>();
        for (java.util.Set<String> turn : turns) own.addAll(turn);
        java.util.Set<String> now = java.util.concurrent.ConcurrentHashMap.newKeySet();
        turns.addLast(now); while (turns.size() > 2) turns.removeFirst();
        return java.util.List.of(own, now);
    }
    /**
     * The page a conversation was opened about (질문하기 on the page's screen): its id, kept across app restarts
     * until the user takes it away in the conversation or the page is gone. "" when none.
     */
    private static final java.util.Map<String, String> about = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String ABOUT = "pages-about";
    static String about(Context context, String conversation) {
        String id = about.get(conversation);
        if (id == null) { id = context.getApplicationContext().getSharedPreferences(ABOUT, 0).getString(conversation, ""); about.put(conversation, id); }
        return id;
    }
    static void about(Context context, String conversation, String id) {
        String value = id == null ? "" : id; about.put(conversation, value);
        context.getApplicationContext().getSharedPreferences(ABOUT, 0).edit().putString(conversation, value).apply();
    }
    /** The same for a caller without a context (a reply's tools): what about(context, …) last read or set. */
    static String aboutNow(String conversation) { String id = about.get(conversation); return id == null ? "" : id; }
    /** That page while it exists; null when none is set or it was deleted (then the mark is cleared). */
    static PageBook.Page subject(Context context, String conversation) {
        String id = about(context, conversation); if (id.isEmpty()) return null;
        PageBook.Page page; try { page = book(context).page(id); } catch (RuntimeException unreadable) { return null; }
        if (page == null) about(context, conversation, "");
        return page;
    }
    /** Where a page came from, as the page screen says it. */
    static String source(String source) {
        return PageBook.WORK.equals(source) ? "작업창" : PageBook.CHAT.equals(source) ? "메인 대화" : "직접 저장";
    }
}
