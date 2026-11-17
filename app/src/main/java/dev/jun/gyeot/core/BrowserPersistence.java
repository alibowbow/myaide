package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** One serial disk executor; navigation only queues immutable snapshots on the UI thread. */
public final class BrowserPersistence {
    public interface Disk {
        String get(String key) throws Exception;
        void put(String key, String value) throws Exception;
    }
    public record Saved(String tabs, String result, String conversation) {}
    private final Disk disk;
    private final Executor io;
    private String pendingTabs, writtenTabs;
    private boolean writing;
    private Consumer<Boolean> tabsCallback = saved -> {};
    private String pendingConversation;
    private Consumer<Boolean> conversationCallback;
    private boolean writingConversation;
    public static final String STATE_KEY = "browser.state.v2";
    private String pendingState, writtenState;
    private final List<Consumer<Boolean>> stateCallbacks = new ArrayList<>();
    private boolean writingState;

    public BrowserPersistence(Disk disk, Executor serialExecutor) { this.disk = disk; io = serialExecutor; }
    public void load(Consumer<Saved> callback) {
        io.execute(() -> {
            String state;
            try { state = disk.get(STATE_KEY); }
            catch (Exception unreadable) { callback.accept(new Saved(null, "", null)); return; }
            if (state == null) { callback.accept(new Saved(null, "", null)); return; }
            if (state.isBlank()) {
                String lastResult;
                try { lastResult = disk.get("browser.lastResult"); }
                catch (Exception unreadable) { callback.accept(new Saved(null, "", null)); return; }
                if (lastResult == null) { callback.accept(new Saved(null, "", null)); return; }
                callback.accept(new Saved(readTabs(), lastResult, readConversation())); return;
            }
            Saved result;
            try {
                JSONObject value = new JSONObject(state);
                if (!Integer.valueOf(2).equals(value.opt("version"))
                        || !(value.opt("tabs") instanceof String) || !(value.opt("conversation") instanceof String)) throw new IllegalArgumentException("Invalid browser state");
                // An explicit v2 snapshot, including empty history, permanently retires legacy lastResult import.
                result = new Saved(value.getString("tabs"), "", value.getString("conversation"));
            } catch (Exception malformed) { result = new Saved(null, "", null); }
            callback.accept(result);
        });
    }
    private String readTabs() { try { return disk.get("browser.tabs"); } catch (Exception ignored) { return null; } }
    private String readConversation() { try { return disk.get("browser.conversation"); } catch (Exception ignored) { return null; } }
    /** Tabs, AI memory and display history commit together, never as independently resurrectable keys. */
    public synchronized void state(String tabs, String conversation, Consumer<Boolean> callback) {
        if (tabs == null || conversation == null) throw new IllegalArgumentException("Complete state required");
        String value = obj("version", 2, "tabs", tabs, "conversation", conversation).toString();
        if (!writingState && value.equals(writtenState)) { if (callback != null) callback.accept(true); return; }
        pendingState = value; if (callback != null) stateCallbacks.add(callback);
        if (writingState) return;
        writingState = true; io.execute(this::writeState);
    }
    private void writeState() {
        while (true) {
            String next; List<Consumer<Boolean>> callbacks; boolean unchanged;
            synchronized (this) {
                next = pendingState; pendingState = null;
                if (next == null) { writingState = false; return; }
                callbacks = new ArrayList<>(stateCallbacks); stateCallbacks.clear();
                unchanged = next.equals(writtenState);
            }
            if (unchanged) { notifyState(callbacks, true); continue; }
            boolean saved = false;
            try {
                disk.put(STATE_KEY, next);
                synchronized (this) { writtenState = next; }
                saved = true;
            } catch (Exception ignored) { /* A failed complete snapshot may be retried without losing the prior pair. */ }
            notifyState(callbacks, saved);
        }
    }
    private static void notifyState(List<Consumer<Boolean>> callbacks, boolean saved) {
        for (Consumer<Boolean> callback : callbacks) {
            try { callback.accept(saved); } catch (RuntimeException ignored) { /* One observer cannot strand the serial storage queue. */ }
        }
    }
    public synchronized void tabs(String value) { tabs(value, saved -> {}); }
    public synchronized void tabs(String value, Consumer<Boolean> callback) {
        if (!writing && value.equals(writtenTabs)) { callback.accept(true); return; }
        tabsCallback = callback;
        pendingTabs = value;
        if (writing) return;
        writing = true;
        io.execute(this::writeTabs);
    }
    private void writeTabs() {
        while (true) {
            String next; Consumer<Boolean> callback;
            synchronized (this) {
                callback = tabsCallback; next = pendingTabs; pendingTabs = null;
                if (next == null) { writing = false; return; }
                if (next.equals(writtenTabs)) { callback.accept(true); continue; }
            }
            try {
                disk.put("browser.tabs", next);
                synchronized (this) { writtenTabs = next; }
                callback.accept(true);
            } catch (Exception ignored) { callback.accept(false); /* A later snapshot can retry. */ }
        }
    }
    public synchronized void conversation(String value, Consumer<Boolean> callback) {
        pendingConversation = value; conversationCallback = callback;
        if (writingConversation) return;
        writingConversation = true; io.execute(this::writeConversation);
    }
    private void writeConversation() {
        while (true) {
            String next; Consumer<Boolean> callback;
            synchronized (this) {
                next = pendingConversation; callback = conversationCallback; pendingConversation = null;
                if (next == null) { writingConversation = false; return; }
            }
            boolean saved = false;
            try { disk.put("browser.conversation", next); saved = true; } catch (Exception ignored) { }
            callback.accept(saved);
        }
    }
    public void result(String value) { io.execute(() -> { try { disk.put("browser.lastResult", value); } catch (Exception ignored) { } }); }
}

