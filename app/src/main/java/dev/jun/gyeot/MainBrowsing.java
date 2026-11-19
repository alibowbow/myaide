package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.AutomationGate;
import dev.jun.gyeot.core.BrowserWorkJobs;
import dev.jun.gyeot.core.MainBrowser;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The browser and the 들퍼레 folder for one main-chat reply (core/MainBrowser): browser_tasks run in tabs the user does not
 * see, under the user's own words for this question, with the reply's progress shown under it; the files they download are
 * this reply's, kept with folder_save into 들퍼레 or removed when the reply ends. While the browser works the app keeps a
 * notification up (MainBrowserService), so leaving the app does not stop it.
 */
final class MainBrowsing implements AutoCloseable {
    private final Context context; private final AppStore store;
    private final Map<String, JSONObject> downloads = new ConcurrentHashMap<>(); private final List<String> copies = new CopyOnWriteArrayList<>();
    private final BrowserWorkTools browser; private final FolderWorkTools folders; private boolean held, closed;

    MainBrowsing(Context context, AppStore store, String run, String words, BooleanSupplier stopped, Consumer<String> status) {
        this.context = context.getApplicationContext(); this.store = store;
        String said = words == null ? "" : words.strip();
        String authority = AutomationGate.AUTHORITY_LINE + said.replace("\n", "\n" + AutomationGate.AUTHORITY_LINE) + "\n";
        browser = new BrowserWorkTools(this.context, store, "main", run == null || run.isBlank() ? "main" : run, () -> authority, stopped, () -> false, status, value -> {
            JSONObject artifact = value.optJSONObject("artifact"), attachment = value.optJSONObject("attachment");
            if (artifact != null && dev.jun.gyeot.core.WorkBook.safeId(artifact.optString("id"))) downloads.put(artifact.optString("id"), artifact);
            if (attachment != null && dev.jun.gyeot.core.WorkBook.safeId(attachment.optString("id"))) copies.add(attachment.optString("id"));
        });
        folders = new FolderWorkTools(this.context, () -> said, new FolderWorkTools.Downloads() {
            @Override public JSONObject find(String id) { return id == null ? null : downloads.get(id); }
            @Override public File bytes(String id) { return store.attachmentFile(id); }
        }, stopped);
    }
    /** browser_tasks and the folder functions, as one set; the reply may take MainBrowser.ROUNDS rounds of them. */
    WorkTools tools() {
        WorkTools browsing = new WorkTools() {
            @Override JSONArray declarations() { return browser.declarations(); }
            @Override String instructions() { return browser.instructions(); }
            @Override JSONObject run(String name, Object arguments) { if (BrowserWorkJobs.RUN.equals(name)) hold(); return browser.run(name, arguments); }
            @Override int rounds() { return MainBrowser.ROUNDS; }
        };
        return WorkTools.combine(browsing, folders);
    }
    private synchronized void hold() { if (held || closed) return; held = true; MainBrowserService.hold(context); }
    /** The reply ended: its browser work stops, and the files it downloaded but did not keep in 들퍼레 are removed. */
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        try { browser.close(); } finally {
            for (String id : downloads.keySet()) store.attachmentFile(id).delete();
            for (String id : copies) store.attachmentFile(id).delete();
            synchronized (this) { if (held) { held = false; MainBrowserService.release(context); } }
        }
    }
}
