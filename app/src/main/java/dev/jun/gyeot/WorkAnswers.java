package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import dev.jun.gyeot.core.RichBlocks;
import dev.jun.gyeot.core.WorkBook;
import dev.jun.gyeot.core.WorkQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 작업창 answers drawn as the main chat draws them (RichAnswer): prose, tables, code cards, sandboxed visuals and the next
 * requests an answer suggests. The room is drawn again whole whenever it changes, and a room holds many long answers, so
 * each drawn answer is kept by room, place, text and mode and put back as it is (a page is loaded once, not on every
 * redraw); answers a drawing did not show are freed after it, pages included. Only the newest answers' visuals run
 * inline, MainChatPage.LIVE_VISUALS in all; pages pause while their screen is hidden. The answer being written is drawn
 * by RichAnswer.Live and kept across redraws of its room the same way.
 */
final class WorkAnswers {
    /** What a drawn 작업창 answer asks of its screen. */
    interface Screen {
        void copy(String text);
        /** A link the user tapped in a visual. */
        void open(String url);
        /** A suggested request the user tapped: into the message box, never sent from here. */
        void suggest(String text);
        /** A long press on a table or a card: the answer's own actions (read aloud, keep, share). Prose is selectable instead. */
        void menu(JSONObject thread, JSONObject message);
        void error(String message);
    }
    private final Activity activity; private final Ui ui; private final Screen screen;
    /** Drawn answers by room, place, text and mode; shown says which of them the drawing under way has used. */
    private final Map<String, Holder> kept = new HashMap<>(); private final Set<String> shown = new HashSet<>();
    /** The room the plan below is for (works are parsed anew for each drawing, so a new drawing is a new object). */
    private JSONObject planned; private int plannedCount = -1;
    /** How many of each answer's visuals run inline, each message's place in its room, and the answer that offers its suggestions. */
    private final IdentityHashMap<JSONObject, Integer> live = new IdentityHashMap<>(), places = new IdentityHashMap<>();
    private JSONObject newest;
    /** The answer being written: its room, its views, and whether the drawing under way has used it. */
    private String writingRoom = ""; private RichAnswer.Live writing; private Holder writingView; private boolean writingShown;
    private final List<Dialog> viewers = new ArrayList<>();
    WorkAnswers(Activity activity, Ui ui, Screen screen) { this.activity = activity; this.ui = ui; this.screen = screen; }

    /**
     * An answer's views with the room and message they stand for now (a kept view outlives the drawing that made it).
     * Its pages pause and resume with what the user can see of it: another screen over the room, the app in the background.
     */
    private static final class Holder extends FrameLayout {
        JSONObject thread, message;
        Holder(Context context) { super(context); }
        @Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); RichAnswer.active(this, visible); }
    }

    /**
     * Which answers' visuals run and which answer offers its suggested requests: the newest answers' visuals, up to
     * LIVE_VISUALS in all (an answer's first ones when only some fit); the suggestions of the room's last message when it
     * is an answer and nothing is running or waiting after it.
     */
    private void plan(JSONObject thread) {
        JSONArray messages = thread.optJSONArray("messages");
        planned = thread; plannedCount = messages == null ? 0 : messages.length(); live.clear(); places.clear(); newest = null;
        if (messages == null) return;
        int left = MainChatPage.LIVE_VISUALS;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i); if (m == null) continue;
            places.put(m, i);
            if (left <= 0 || WorkBook.USER.equals(m.optString("role")) || WorkBook.ERROR.equals(m.optString("role"))) continue;
            String text = m.optString("text"), lower = text.toLowerCase(Locale.ROOT);
            if (!text.contains("```") && !text.contains("~~~") && !lower.contains("<svg") && !lower.contains("<html") && !lower.contains("<!doctype")) continue;
            int count = RichBlocks.visuals(text); if (count > 0) { live.put(m, Math.min(count, left)); left -= count; }
        }
        JSONObject last = messages.optJSONObject(messages.length() - 1);
        boolean idle = !WorkBook.RUNNING.equals(thread.optString("state")) && WorkQueue.size(thread) == 0;
        if (idle && last != null && !WorkBook.USER.equals(last.optString("role")) && !WorkBook.ERROR.equals(last.optString("role"))) newest = last;
    }

    /** The answer's body: made once, then put back as it is while its room, place, text and mode stay the same. */
    View body(JSONObject thread, JSONObject message) {
        JSONArray messages = thread.optJSONArray("messages");
        if (planned != thread || plannedCount != (messages == null ? 0 : messages.length())) plan(thread);
        String text = message.optString("text"), room = thread.optString("id"); int visuals = live.getOrDefault(message, 0); Integer place = places.get(message);
        // A message outside a saved room (a preview) is drawn but not kept: nothing says when it stops being shown.
        boolean keep = place != null && !room.isEmpty();
        String key = room + ":" + place + ":" + message.optLong("at") + ":" + text.hashCode() + ":" + text.length() + ":" + visuals + ":" + Ui.DARK;
        Holder holder = keep ? kept.get(key) : null;
        if (holder == null) {
            holder = new Holder(activity); holder.addView(RichAnswer.build(activity, ui, text, visuals, host(holder)), new FrameLayout.LayoutParams(-1, -2));
            if (keep) kept.put(key, holder);
        } else if (holder.getParent() instanceof ViewGroup parent) parent.removeView(holder);
        if (keep) shown.add(key);
        holder.thread = thread; holder.message = message;
        // Only the room's newest answer offers its suggested requests; a kept view just shows or hides them.
        RichAnswer.offer(holder, message == newest);
        return holder;
    }

    /**
     * The answer being written in this room, drawn as it arrives: only what is still open is drawn again, visuals run once
     * their fence has closed. A redraw of the same room gets the same views back.
     */
    View draft(String room, String text) {
        if (writing == null || !writingRoom.equals(room)) {
            if (writingView != null) RichAnswer.destroy(writingView);
            writingView = new Holder(activity); writingRoom = room;
            writing = new RichAnswer.Live(activity, ui, host(writingView), true, MainChatPage.LIVE_VISUALS);
            writingView.setPadding(0, ui.dp(6), 0, 0); writingView.addView(writing.view(), new FrameLayout.LayoutParams(-1, -2));
        } else if (writingView.getParent() instanceof ViewGroup parent) parent.removeView(writingView);
        writingShown = true; write(text);
        return writingView;
    }
    /** More of the answer being written has arrived. */
    void write(String text) {
        if (writing == null) return;
        String next = text == null ? "" : text;
        if (!next.equals(writing.text())) writing.update(next);
        writingView.setVisibility(next.isBlank() ? View.GONE : View.VISIBLE);
    }

    /** Before the screen is cleared for a drawing: kept answers leave it first, so clearing does not free their pages. */
    void detach() {
        for (Holder holder : kept.values()) if (holder.getParent() instanceof ViewGroup parent) parent.removeView(holder);
        if (writingView != null && writingView.getParent() instanceof ViewGroup parent) parent.removeView(writingView);
        shown.clear(); writingShown = false;
    }
    /** After a drawing: answers it did not show are freed (their pages destroyed), so only those on screen are kept. */
    void release() {
        for (java.util.Iterator<Map.Entry<String, Holder>> entries = kept.entrySet().iterator(); entries.hasNext();) {
            Map.Entry<String, Holder> entry = entries.next();
            if (!shown.contains(entry.getKey())) { RichAnswer.destroy(entry.getValue()); entries.remove(); }
        }
        if (!writingShown && writingView != null) { RichAnswer.destroy(writingView); writing = null; writingView = null; writingRoom = ""; }
        if (kept.isEmpty() && writing == null) dismissViewers();
    }
    /** The screen is going away: every page and full-screen viewer with it. */
    void close() { detach(); release(); dismissViewers(); }
    private void dismissViewers() { for (Dialog viewer : new ArrayList<>(viewers)) viewer.dismiss(); viewers.clear(); }

    private RichAnswer.Host host(Holder holder) {
        return new RichAnswer.Host() {
            @Override public void copy(String text) { screen.copy(text); }
            @Override public void open(String url) { screen.open(url); }
            @Override public void expand(RichBlocks.Block block) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try { Dialog viewer = RichAnswer.viewer(activity, ui, block, this); viewers.removeIf(d -> !d.isShowing()); viewers.add(viewer); viewer.show(); }
                catch (RuntimeException unavailable) { screen.error("이 기기에서 시각화를 열지 못했어요. 코드 복사로 내용을 확인할 수 있어요."); }
            }
            @Override public void menu() { if (holder.thread != null && holder.message != null) screen.menu(holder.thread, holder.message); }
            @Override public void suggest(String text) { screen.suggest(text); }
            @Override public boolean selectable() { return true; }
        };
    }
}
