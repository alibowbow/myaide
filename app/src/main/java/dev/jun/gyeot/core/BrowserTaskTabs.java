package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The tabs one AI 브라우저 task works in (준: "쓸데 없는 제한이 너무 많아"): the tab it was given, the tabs it opened (open_tab,
 * or a page's new window while it worked: a target=_blank link, window.open) and the tabs it moved to (switch, or a step that
 * names another tab). Its steps go to the tab it is on (focus) unless they name one. The one rule kept: a tab another running
 * task uses is never taken (one task per tab). It closes only tabs it opened itself, never the user's.
 * T is the engine's tab, compared as itself; nothing here touches a page.
 */
public final class BrowserTaskTabs<T> {
    private final T own;
    private T focus;
    private final Set<T> used = new LinkedHashSet<>(), opened = new LinkedHashSet<>();
    /** What happened beside the task's steps (a new tab a page opened, a site's alert it accepted, a file saved), for its next step's result. */
    private final List<JSONObject> events = new ArrayList<>();
    /** At most this many events wait for the next step: a page that alerts in a loop cannot fill memory. */
    public static final int EVENTS = 8;

    public BrowserTaskTabs(T own) { this.own = own; focus = own; if (own != null) used.add(own); }
    public T own() { return own; }
    /** The tab the task's steps go to when they name none. */
    public T focus() { return focus; }
    public boolean uses(T tab) { return tab != null && used.contains(tab); }
    public boolean opened(T tab) { return tab != null && opened.contains(tab); }
    /** Whether the task works in more than its own tab: its journal then names the tab of each step. */
    public boolean several() { return used.size() > 1; }

    /**
     * The task works in tab from now on (a step names it, or switch). null when it may; otherwise the step's answer, which
     * runs nothing: the tab is gone, or another running task uses it (busy).
     */
    public JSONObject use(T tab, boolean busy) {
        if (tab == null) return refusal("tab_closed", "그 탭은 닫혔어요. tabs로 열린 탭을 다시 보고 이어 가세요.");
        if (busy && !used.contains(tab))
            return refusal("tab_busy", "그 탭은 다른 AI 작업이 쓰고 있어서 건드리지 않았어요(탭 하나에는 작업 하나). 다른 탭을 쓰거나 open_tab으로 새 탭을 여세요.");
        used.add(tab); focus = tab; return null;
    }
    /** A tab the task opened itself (open_tab): it works there next and may close it. */
    public void opened(T tab, JSONObject about) {
        if (tab == null) return;
        used.add(tab); opened.add(tab); focus = tab;
    }
    /**
     * A page the task worked on opened a new window (a target=_blank link, window.open): the task may use it and close it, its
     * steps stay where they were, and its next step's result says the tab is there (about: its id and address).
     */
    public void popup(T tab, JSONObject about) {
        if (tab == null) return;
        used.add(tab); opened.add(tab);
        event(obj("new_tab", about == null ? obj() : about, "note",
            "The page opened a new tab while this task worked (a link with target=_blank or window.open). It is not read yet; this task may use it: switch with its tab id to read it, or go on here. This tab stayed as it was."));
    }
    /** null when the task may close tab: only a tab it opened. Otherwise the answer, which closes nothing. */
    public JSONObject closing(T tab) {
        if (tab == null) return refusal("tab_closed", "그 탭은 이미 닫혔어요.");
        if (tab.equals(own)) return refusal("own_tab", "작업을 맡긴 탭은 닫지 않아요. 다른 탭에서 일을 마쳤으면 switch로 이 탭에 돌아오세요.");
        if (!opened.contains(tab)) return refusal("not_opened_by_task", "이 작업이 연 탭만 닫아요. 사용자가 연 탭은 그대로 뒀어요.");
        return null;
    }
    /** The tab went away (closed by the task, the page or the user): its steps go back to the task's own tab. */
    public void closed(T tab) {
        if (tab == null) return;
        used.remove(tab); opened.remove(tab);
        if (tab.equals(focus)) focus = own;
    }
    /** Something the next step's result should say; the oldest goes when EVENTS wait. */
    public void event(JSONObject event) {
        if (event == null) return;
        events.add(event);
        while (events.size() > EVENTS) events.remove(0);
    }
    /** The events since the last step, oldest first; they are given once. */
    public JSONArray drain() {
        JSONArray out = arr();
        for (JSONObject e : events) out.put(e);
        events.clear();
        return out;
    }
    /** A step that ran nothing because of its tab: ok (the planner chooses again, the task does not end), with why. */
    public static JSONObject refusal(String code, String note) {
        return obj("ok", true, "action_executed", false, "tab_refused", true, "refusal_code", code, "note", note);
    }
}
