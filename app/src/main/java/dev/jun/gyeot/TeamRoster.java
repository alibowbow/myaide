package dev.jun.gyeot;

import dev.jun.gyeot.core.*;
import java.util.*;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Team model identities and automatic candidates. Reads settings/catalogs only; never starts account requests. */
final class TeamRoster {
    private final AppStore store;
    private final ScopedModels selection;
    TeamRoster(AppStore store) { this(store, null); }
    TeamRoster(AppStore store, ScopedModels selection) { this.store = store; this.selection = selection; }
    JSONArray catalog(String engine) {
        return switch (engine) {
            case WorkBook.CHATGPT -> store.openai.models(); case WorkBook.CLAUDE -> store.claude.models();
            case WorkBook.GEMINI -> store.gemini.models(); case WorkBook.GROK -> store.grok.models(); case WorkBook.NIM -> store.nim.models();
            default -> arr();
        };
    }
    String selected(String engine) {
        if (selection != null) return selection.model(engine);
        return switch (engine) {
            case WorkBook.CHATGPT -> store.openai.model(); case WorkBook.CLAUDE -> store.claude.model();
            case WorkBook.GEMINI -> store.gemini.model(); case WorkBook.GROK -> store.grok.model(); case WorkBook.NIM -> store.nim.model();
            default -> "";
        };
    }
    Reasoning.Profile profile(String engine, String model) {
        return switch (engine) {
            case WorkBook.CHATGPT -> store.openai.reasoningProfile(model); case WorkBook.CLAUDE -> store.claude.profile(model);
            case WorkBook.GEMINI -> store.gemini.profile(model); case WorkBook.GROK -> store.grok.profile(model); case WorkBook.NIM -> store.nim.profile(model);
            default -> Reasoning.Profile.of(List.of(), "");
        };
    }
    String effort(String engine, String model) {
        if (selection != null) return selection.effectiveEffort(engine, model);
        return profile(engine, model).resolve(switch (engine) {
            case WorkBook.CHATGPT -> store.openai.reasoningEffort(model); case WorkBook.CLAUDE -> store.claude.effort(model);
            case WorkBook.GEMINI -> store.gemini.effort(model); case WorkBook.GROK -> store.grok.effort(model); case WorkBook.NIM -> store.nim.effort(model);
            default -> "";
        });
    }
    String label(String engine, String model) {
        JSONArray models = catalog(engine); String name = model;
        for (int i = 0; i < models.length(); i++) {
            JSONObject item = models.optJSONObject(i);
            if (item != null && model.equals(item.optString("id")) && !item.optString("name").isBlank()) { name = item.optString("name"); break; }
        }
        return WorkBook.CLAUDE.equals(engine) && name.startsWith("Claude ") ? name.substring(7)
            : WorkBook.GEMINI.equals(engine) && name.startsWith("Gemini ") ? name.substring(7) : name;
    }
    Set<String> excluded() {
        if (selection != null) return selection.teamExcluded();
        Set<String> result = new LinkedHashSet<>();
        try { JSONArray saved = new JSONArray(store.settings.getString("workTeamAutoExcluded", "[]"));
            for (int i = 0; i < saved.length(); i++) if (TeamPlan.ENGINES.contains(saved.optString(i))) result.add(saved.optString(i));
        } catch (Exception ignored) {}
        return result;
    }
    JSONArray automatic(Set<String> excluded) {
        return TeamPlan.automatic(engine -> store.ready(engine) && !excluded.contains(engine), this::catalog, this::selected, this::label, this::effort);
    }
    JSONArray manual() {
        JSONArray result = selection == null ? store.teamProfiles() : selection.teamProfiles();
        for (int i = 0; i < result.length(); i++) {
            JSONObject p = result.optJSONObject(i); String name = p.optString("name").strip();
            // A retired model's name (core/ModelIds) is never the user's own: it follows the replacement model.
            boolean custom = !ModelIds.retiredName(name) && (p.has("customName") ? p.optBoolean("customName") : !name.isBlank() && !modelName(name));
            put(p, "customName", custom);
            if (!custom) put(p, "name", label(p.optString("engine"), p.optString("model")));
        }
        return result;
    }
    private boolean modelName(String name) {
        for (String engine : TeamPlan.ENGINES) {
            if (name.equals(WorkBook.author(engine))) return true;
            JSONArray models = catalog(engine);
            for (int i = 0; i < models.length(); i++) { JSONObject item = models.optJSONObject(i); if (item == null) continue;
                if (name.equals(item.optString("id")) || name.equals(item.optString("name")) || name.equals(label(engine, item.optString("id")))) return true;
            }
        }
        return false;
    }
    JSONArray forTurn() {
        JSONArray result = (selection == null ? store.teamAutomatic() : selection.teamAutomatic()) ? automatic(excluded()) : manual();
        for (int i = 0; i < result.length(); i++) {
            JSONObject p = result.optJSONObject(i);
            if (!store.ready(p.optString("engine"))) put(p, "enabled", false);
            // Only this submitted room's explicit model selection can opt a child into Fast.
            put(p, "speed", selection != null && WorkBook.CHATGPT.equals(p.optString("engine")) ? selection.speed(p.optString("model")) : "standard");
            if ("fast".equals(p.optString("speed"))) put(p, "speedConsentEpoch", selection.snapshot().optString("speedConsentEpoch"));
            else p.remove("speedConsentEpoch");
        }
        return result;
    }
}

