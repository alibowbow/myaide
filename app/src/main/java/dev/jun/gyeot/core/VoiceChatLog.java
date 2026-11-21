package dev.jun.gyeot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a spoken conversation adds to the chat, in the order it was said. The server writes down the user's words a little
 * after it has begun to answer them, so an answer can be ready before the words it answers: each user turn keeps its
 * place (from the moment the server took it) until its words arrive, and what comes after it waits. A turn whose words
 * do not come within USER_WAIT_MS is left out. Pure Java; one thread.
 */
public final class VoiceChatLog {
    public static final long USER_WAIT_MS = 5000;
    /**
     * A line for the chat: the user's or the answer's words; cut when the user stopped the answer partway; key is the
     * conversation's own id for it (the user's item, the answer's response), which its sound is kept under.
     */
    public record Line(boolean user, String text, boolean cut, String key) {
        public Line(boolean user, String text, boolean cut) { this(user, text, cut, ""); }
    }
    private static final class Slot {
        final boolean user; final String key; final long at; String text = ""; boolean filled, cut;
        Slot(boolean user, String key, long at) { this.user = user; this.key = key; this.at = at; }
    }
    private final ArrayDeque<Slot> slots = new ArrayDeque<>();
    private final Set<String> users = new HashSet<>(), answers = new HashSet<>();

    /** The server took a turn of the user's (item): its words come later. */
    public void heard(String item, long now) {
        if (item == null || item.isEmpty() || !users.add(item)) return;
        slots.addLast(new Slot(true, item, now));
    }
    /** The user's words for item; a turn never announced goes last, one already left out is dropped. */
    public void said(String item, String text, long now) {
        String key = item == null ? "" : item;
        for (Slot s : slots) if (s.user && s.key.equals(key) && !s.filled) { s.text = clean(text); s.filled = true; return; }
        if (!key.isEmpty() && !users.add(key)) return; // already written, or left out
        Slot s = new Slot(true, key, now); s.text = clean(text); s.filled = true; slots.addLast(s);
    }
    /** An answer's words (once per answer); cut when the user stopped it. */
    public void answered(String response, String text, boolean cut, long now) {
        if (response != null && !response.isEmpty() && !answers.add(response)) return;
        Slot s = new Slot(false, response == null ? "" : response, now); s.text = clean(text); s.cut = cut; s.filled = true; slots.addLast(s);
    }
    /** The lines ready to add now, in order: those before any turn still waiting for its words. */
    public List<Line> drain(long now) {
        List<Line> ready = new ArrayList<>();
        while (!slots.isEmpty()) {
            Slot head = slots.peekFirst();
            if (!head.filled) { if (now - head.at < USER_WAIT_MS) break; slots.removeFirst(); continue; }
            slots.removeFirst();
            if (!head.text.isEmpty()) ready.add(new Line(head.user, head.text, head.cut, head.key == null ? "" : head.key));
        }
        return ready;
    }
    /** Everything left, at the end of the conversation: turns still waiting are left out. */
    public List<Line> rest() { return drain(Long.MAX_VALUE); }
    /** Whether a turn still waits for its words. */
    public boolean waiting() { for (Slot s : slots) if (!s.filled) return true; return false; }
    private static String clean(String text) { return text == null ? "" : text.strip(); }
}
