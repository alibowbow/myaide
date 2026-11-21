package dev.jun.gyeot;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;

/**
 * The browser's saved logins (비밀번호 관리자): every site's accounts, encrypted together on this device with the app's
 * Keystore key (SecretStore), never in a backup and never in anything an AI is sent. Gecko asks it for a site's logins and
 * fills the form itself (the page's own fields, never the AI's tools); when someone logs in with new details, Gecko asks
 * whether to keep them (BrowserActivity's save prompt) and hands them here. Each use is written down (when, which site and
 * account, whether an AI task was running), without the password.
 */
final class LoginVault implements Autocomplete.StorageDelegate {
    static final String KEY = "browser.logins", LOG = "browser.loginLog", LEGACY = "browser.vault.";
    /** Sites the user said never to ask about (origins), and whether to ask at all; not secret, in settings. */
    static final String NEVER = "browserLoginNever", ASK = "browserLoginAsk";
    static final int MAX_LOG = 200;
    record Login(String guid, String origin, String formActionOrigin, String httpRealm, String username, String password, long created, long used, int uses) {
        String host() { String h = origin.replaceFirst("^[a-z]+://", ""); int slash = h.indexOf('/'); return slash < 0 ? h : h.substring(0, slash); }
        JSONObject json() {
            JSONObject o = new JSONObject();
            try { o.put("guid", guid).put("origin", origin).put("formActionOrigin", formActionOrigin).put("httpRealm", httpRealm).put("username", username).put("password", password).put("created", created).put("used", used).put("uses", uses); }
            catch (Exception ignored) {}
            return o;
        }
        static Login of(JSONObject o) {
            return new Login(o.optString("guid"), o.optString("origin"), o.optString("formActionOrigin"), o.optString("httpRealm"), o.optString("username"), o.optString("password"),
                o.optLong("created"), o.optLong("used"), o.optInt("uses"));
        }
        Autocomplete.LoginEntry entry() {
            Autocomplete.LoginEntry.Builder b = new Autocomplete.LoginEntry.Builder().guid(guid).origin(origin).username(username).password(password);
            if (!formActionOrigin.isEmpty()) b.formActionOrigin(formActionOrigin);
            if (!httpRealm.isEmpty()) b.httpRealm(httpRealm);
            return b.build();
        }
    }
    record Use(long at, String origin, String username, boolean ai) {}

    private final SecretStore secrets;
    private final SharedPreferences settings;
    private final java.util.function.BooleanSupplier aiRunning;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static LoginVault installed;
    /** The app's one vault (nothing of Gecko is started for it). */
    static synchronized LoginVault of(android.content.Context context) {
        if (installed == null) {
            AppStore store = ((GyeotApp) context.getApplicationContext()).store();
            installed = new LoginVault(store.secrets, store.settings, BrowserEngine::busy);
        }
        return installed;
    }
    /** Given to the browser's runtime when it is made (BrowserRuntime.prepare): Gecko fills forms from the vault and asks it to keep new logins. */
    static void attach(android.content.Context context, org.mozilla.geckoview.GeckoRuntime runtime) {
        runtime.setAutocompleteStorageDelegate(of(context));
        runtime.getSettings().setLoginAutofillEnabled(true);
    }
    LoginVault(SecretStore secrets, SharedPreferences settings, java.util.function.BooleanSupplier aiRunning) {
        this.secrets = secrets; this.settings = settings; this.aiRunning = aiRunning;
    }
    void listen(Runnable l) { listeners.addIfAbsent(l); }
    void unlisten(Runnable l) { listeners.remove(l); }
    private void changed() { main.post(() -> { for (Runnable l : listeners) l.run(); }); }

    /** Every saved login, newest use first; the single logins of older versions (one per site) are taken in once. */
    synchronized List<Login> all() {
        List<Login> out = new ArrayList<>();
        try {
            migrate();
            String raw = secrets.get(KEY); JSONArray list = raw.isBlank() ? new JSONArray() : new JSONArray(raw);
            for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !o.optString("password").isEmpty()) out.add(Login.of(o)); }
        } catch (Exception unreadable) { return out; }
        out.sort((a, b) -> Long.compare(Math.max(b.used, b.created), Math.max(a.used, a.created)));
        return out;
    }
    private void write(List<Login> logins) throws Exception {
        JSONArray list = new JSONArray(); for (Login l : logins) list.put(l.json());
        secrets.put(KEY, logins.isEmpty() ? "" : list.toString());
    }
    private void migrate() throws Exception {
        List<String> old = secrets.names(LEGACY); if (old.isEmpty()) return;
        String raw = secrets.get(KEY); JSONArray list = raw.isBlank() ? new JSONArray() : new JSONArray(raw);
        for (String name : old) {
            try {
                JSONObject saved = new JSONObject(secrets.get(name)); String origin = name.substring(LEGACY.length());
                if (!saved.optString("password").isEmpty()) list.put(new Login(UUID.randomUUID().toString(), origin, "", "", saved.optString("username"), saved.optString("password"), System.currentTimeMillis(), 0, 0).json());
            } catch (Exception skipped) { /* an unreadable old entry is dropped with its key */ }
            secrets.put(name, "");
        }
        secrets.put(KEY, list.toString());
    }

    /** The logins kept for a site (its origin, or the whole host when the origin is only a domain). */
    synchronized List<Login> forSite(String originOrDomain) {
        String wanted = host(originOrDomain); List<Login> out = new ArrayList<>();
        for (Login l : all()) { String h = l.host(); if (h.equals(wanted) || h.endsWith("." + wanted) || wanted.endsWith("." + h)) out.add(l); }
        return out;
    }
    static String host(String value) {
        String h = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", ""); int cut = h.indexOf('/'); if (cut >= 0) h = h.substring(0, cut);
        int port = h.indexOf(':'); return port >= 0 ? h.substring(0, port) : h;
    }

    /** Keeps a login: the same site and account is updated, a new one is added. */
    synchronized Login save(String origin, String formActionOrigin, String httpRealm, String username, String password) throws Exception {
        List<Login> logins = all(); long now = System.currentTimeMillis(); Login kept = null;
        for (int i = 0; i < logins.size(); i++) {
            Login l = logins.get(i);
            if (l.origin.equals(origin) && l.username.equals(username)) { kept = new Login(l.guid, origin, nz(formActionOrigin, l.formActionOrigin), nz(httpRealm, l.httpRealm), username, password, l.created, l.used, l.uses); logins.set(i, kept); break; }
        }
        if (kept == null) { kept = new Login(UUID.randomUUID().toString(), origin, nz(formActionOrigin, ""), nz(httpRealm, ""), username, password, now, 0, 0); logins.add(kept); }
        write(logins); changed(); return kept;
    }
    private static String nz(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    synchronized void delete(String guid) throws Exception {
        List<Login> logins = all(); logins.removeIf(l -> l.guid.equals(guid)); write(logins); changed();
    }
    synchronized void deleteAll() throws Exception { secrets.put(KEY, ""); secrets.put(LOG, ""); changed(); }

    /** Writes down one use of a login (no password), and when it was last used. */
    synchronized void used(String guid, String origin, String username) {
        try {
            List<Login> logins = all();
            for (int i = 0; i < logins.size(); i++) { Login l = logins.get(i); if (l.guid.equals(guid) || l.origin.equals(origin) && l.username.equals(username)) { logins.set(i, new Login(l.guid, l.origin, l.formActionOrigin, l.httpRealm, l.username, l.password, l.created, System.currentTimeMillis(), l.uses + 1)); break; } }
            write(logins);
            String raw = secrets.get(LOG); JSONArray log = raw.isBlank() ? new JSONArray() : new JSONArray(raw); JSONArray next = new JSONArray();
            next.put(new JSONObject().put("at", System.currentTimeMillis()).put("origin", origin).put("username", username).put("ai", aiRunning.getAsBoolean()));
            for (int i = 0; i < log.length() && next.length() < MAX_LOG; i++) next.put(log.opt(i));
            secrets.put(LOG, next.toString());
        } catch (Exception quiet) { /* the use still happened; only its record is missing */ }
        changed();
    }
    /** The uses written down, newest first; of one site when origin is given. */
    synchronized List<Use> uses(String origin) {
        List<Use> out = new ArrayList<>();
        try {
            String raw = secrets.get(LOG); JSONArray log = raw.isBlank() ? new JSONArray() : new JSONArray(raw);
            for (int i = 0; i < log.length(); i++) {
                JSONObject o = log.optJSONObject(i); if (o == null) continue;
                if (origin != null && !host(o.optString("origin")).equals(host(origin))) continue;
                out.add(new Use(o.optLong("at"), o.optString("origin"), o.optString("username"), o.optBoolean("ai")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    boolean asks() { return settings.getBoolean(ASK, true); }
    void asks(boolean value) { settings.edit().putBoolean(ASK, value).apply(); changed(); }
    boolean never(String origin) { return settings.getStringSet(NEVER, java.util.Set.of()).contains(origin); }
    void never(String origin, boolean value) {
        java.util.Set<String> set = new java.util.HashSet<>(settings.getStringSet(NEVER, java.util.Set.of()));
        if (value) set.add(origin); else set.remove(origin);
        settings.edit().putStringSet(NEVER, set).apply(); changed();
    }

    // ── Gecko ─────────────────────────────────────────────────────────────

    @Override public GeckoResult<Autocomplete.LoginEntry[]> onLoginFetch(String domain) {
        List<Login> found = forSite(domain); Autocomplete.LoginEntry[] out = new Autocomplete.LoginEntry[found.size()];
        for (int i = 0; i < out.length; i++) out[i] = found.get(i).entry();
        return GeckoResult.fromValue(out);
    }
    @Override public GeckoResult<Autocomplete.LoginEntry[]> onLoginFetch() {
        List<Login> found = all(); Autocomplete.LoginEntry[] out = new Autocomplete.LoginEntry[found.size()];
        for (int i = 0; i < out.length; i++) out[i] = found.get(i).entry();
        return GeckoResult.fromValue(out);
    }
    /** Called after the user chose 저장 in the save prompt. */
    @Override public void onLoginSave(Autocomplete.LoginEntry login) {
        if (login == null || login.password == null || login.password.isEmpty() || login.origin == null) return;
        try { save(login.origin, login.formActionOrigin, login.httpRealm, login.username == null ? "" : login.username, login.password); } catch (Exception quiet) {}
    }
    @Override public void onLoginUsed(Autocomplete.LoginEntry login, int usedFields) {
        if (login == null) return;
        used(login.guid == null ? "" : login.guid, login.origin == null ? "" : login.origin, login.username == null ? "" : login.username);
    }
}
