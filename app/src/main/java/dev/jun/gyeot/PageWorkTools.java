package dev.jun.gyeot;

import dev.jun.gyeot.core.PageBook;
import dev.jun.gyeot.core.PageTools;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 페이지 for the 작업창's models and the main chat's: page_save, page_find, page_read and page_update on the
 * phone's PageBook, one session per reply or run (PageTools.Session: a whole new text only after reading the page to its end;
 * a save or a change only when the user's own words ask for it, or on a page the conversation's last replies made). Nothing
 * leaves the phone.
 */
final class PageWorkTools extends WorkTools {
    private final PageTools.Session session;
    /** source: PageBook.WORK or CHAT; origin: the model or the work, shown with the page; words: the user's own words, or null. */
    PageWorkTools(PageBook book, String source, String origin, Supplier<String> words) { session = new PageTools.Session(book, source, origin, words); }
    /** The same for one reply or run of a conversation (PageStore.begin): the pages its last replies saved or changed are its own. */
    PageWorkTools(PageBook book, String source, String origin, Supplier<String> words, String conversation) {
        java.util.List<java.util.Set<String>> turn = PageStore.begin(conversation);
        session = new PageTools.Session(book, source, origin, words, turn.get(0), turn.get(1)).about(() -> PageStore.aboutNow(conversation));
    }
    @Override JSONArray declarations() { return PageTools.declarations(); }
    @Override String instructions() { return PageTools.INSTRUCTIONS; }
    @Override JSONObject run(String name, Object arguments) { return session.run(name, arguments); }
}
