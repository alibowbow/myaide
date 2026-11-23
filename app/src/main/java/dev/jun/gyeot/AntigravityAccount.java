package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import dev.jun.gyeot.core.Reasoning;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Antigravity as one of the app's engines (WorkBook.ANTIGRAVITY), beside ChatGPT, Claude, Gemini, Grok and NIM: the
 * official agy CLI in the app's Linux (AntigravityCli), signed in with the user's Antigravity subscription. Its models are
 * agy's own list (agy models: Gemini, Claude and GPT models, the reasoning level in each name), shown as agy names them;
 * the one chosen goes with --model. Answers are text only: agy is asked once per turn, without the app's tools.
 */
final class AntigravityAccount {
    /** The model id that sends no --model: agy's own default. */
    static final String DEFAULT = "agy-default";
    private static final Pattern MODEL_LINE = Pattern.compile("^\\s*([a-z][a-z0-9._-]+)\\s+(.+)$"), SAFE = Pattern.compile("[a-z0-9][a-z0-9._-]{1,120}");
    private final Context context; private final SharedPreferences settings; private final Runnable changed;
    AntigravityAccount(Context context, SharedPreferences settings, Runnable changed) { this.context = context; this.settings = settings; this.changed = changed; }

    /** Installed and signed in at the last check (설정 → Antigravity · 상태 확인). */
    boolean connected() { return settings.getBoolean("agySignedIn", false) && AntigravityCli.installed(context); }
    /** agy's models as [{id, name}], the default first; the list read at the last check. */
    JSONArray models() {
        JSONArray out = new JSONArray().put(item(DEFAULT, "agy 기본 모델"));
        try { JSONArray saved = new JSONArray(settings.getString("agyModels", "[]")); for (int i = 0; i < saved.length(); i++) out.put(saved.get(i)); } catch (Exception ignored) {}
        return out;
    }
    String model() { String m = settings.getString("agyModel", DEFAULT); return listed(m) ? m : DEFAULT; }
    void model(String id) { settings.edit().putString("agyModel", listed(id) ? id : DEFAULT).apply(); changed.run(); }
    boolean listed(String id) { JSONArray all = models(); for (int i = 0; i < all.length(); i++) if (id.equals(all.optJSONObject(i).optString("id"))) return true; return false; }
    String label(String id) { JSONArray all = models(); for (int i = 0; i < all.length(); i++) if (id.equals(all.optJSONObject(i).optString("id"))) return all.optJSONObject(i).optString("name", id); return id; }
    /** agy carries the reasoning level in the model it lists, so there is no separate level to choose. */
    Reasoning.Profile profile(String model) { return Reasoning.Profile.of(List.of(), ""); }
    String effort(String model) { return ""; }
    private static JSONObject item(String id, String name) { try { return new JSONObject().put("id", id).put("name", name); } catch (Exception e) { return new JSONObject(); } }

    /**
     * Reads agy's state again: signed in or not (and the plan's limits, AntigravityCli.status), and its model list. Blocking:
     * a background thread. The settings page and the model pickers read what it keeps.
     */
    AntigravityCli.Status refresh() {
        AntigravityCli.Status st = AntigravityCli.status(context);
        SharedPreferences.Editor edit = settings.edit();
        if (st.signedIn() != null) edit.putBoolean("agySignedIn", st.installed() && st.signedIn());
        if (st.installed() && Boolean.TRUE.equals(st.signedIn())) {
            try {
                LinuxRuntime.Result r = LinuxRuntime.run(context, AntigravityCli.BINARY + " models </dev/null", 90_000, AntigravityCli.env());
                if (r.code() == 0) {
                    JSONArray list = new JSONArray();
                    for (String line : LinuxTerminal.plain(r.output()).split("\n")) {
                        Matcher m = MODEL_LINE.matcher(line);
                        if (m.matches() && SAFE.matcher(m.group(1)).matches() && !m.group(1).equals(DEFAULT)) list.put(item(m.group(1), m.group(2).strip()));
                    }
                    if (list.length() > 0) edit.putString("agyModels", list.toString()).putLong("agyModelsAt", System.currentTimeMillis());
                }
            } catch (Exception ignored) { /* The list read before stays. */ }
        }
        edit.apply(); changed.run();
        return st;
    }

    /** One answer from agy on the chosen model: the instructions and the conversation in one prompt. Blocking. */
    String ask(String model, String instructions, String input, AtomicReference<Runnable> cancel) throws Exception {
        return AntigravityCli.ask(context, model == null || model.isBlank() || DEFAULT.equals(model) ? "" : model, instructions, input, 300_000, cancel);
    }
}
