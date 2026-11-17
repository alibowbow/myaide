package dev.jun.gyeot;

import dev.jun.gyeot.core.*;
import java.util.*;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Browser-only model and effort selections. Work/voice defaults remain independent. */
final class BrowserModels {
    private final AppStore store;
    BrowserModels(AppStore store) { this.store = store; }
    JSONArray catalog(String brain) {
        return switch (brain) {
            case "gpt" -> store.openai.models(); case "claude" -> store.claude.models();
            case "gemini" -> store.gemini.models(); case "grok" -> store.grok.models(); case "nim" -> store.nim.models();
            default -> liveModels();
        };
    }
    String model(String brain) {
        String fallback = switch (brain) {
            case "gpt" -> store.openai.model(); case "claude" -> store.claude.model(); case "gemini" -> store.gemini.model();
            case "grok" -> store.grok.model(); case "nim" -> store.nim.model(); default -> store.liveModel();
        };
        // A replaced model saved here is read as its successor (core/ModelIds); brain names match WorkBook's engines.
        String selected = ModelIds.current(brain, store.settings.getString("browserModel." + brain, ""));
        if (!selected.isBlank()) {
            if ("gpt".equals(brain)) return selected; // Never silently replace an explicit GPT choice during catalog rollout.
            JSONArray models = catalog(brain); for (int i = 0; i < models.length(); i++) { JSONObject item = models.optJSONObject(i); if (item != null && selected.equals(item.optString("id"))) return selected; }
        }
        if (fallback.isBlank()) { JSONArray models = catalog(brain); for (int i = 0; i < models.length(); i++) { JSONObject item = models.optJSONObject(i); if (item != null && !item.optString("id").isBlank()) return item.optString("id"); } }
        return fallback;
    }
    private static JSONArray liveModels() {
        JSONArray list = arr(); for (String[] model : GeminiProtocol.LIVE_MODELS) list.put(obj("id", model[0], "name", model[1].replace(" (기본)", ""))); return list;
    }
    Reasoning.Profile profile(String brain, String model) {
        return switch (brain) {
            case "gpt" -> store.openai.reasoningProfile(model); case "claude" -> store.claude.profile(model);
            case "gemini" -> store.gemini.profile(model); case "grok" -> store.grok.profile(model); case "nim" -> store.nim.profile(model);
            default -> Reasoning.Profile.of(GeminiProtocol.extended(model) ? List.of("low", "medium", "high") : List.of(), "medium");
        };
    }
    String selectedEffort(String brain, String model) { return new Reasoning.Selections(store.settings.getString("browserReasoning", "{}")).get(brain + ":" + model, profile(brain, model)); }
    String effort(String brain, String model) { return profile(brain, model).resolve(selectedEffort(brain, model)); }
    String speed(String model) {
        if (!store.openai.speedEpoch().equals(store.settings.getString("browserSpeedEpoch." + model, ""))) return "standard";
        return GptSpeed.normalize(store.settings.getString("browserSpeed." + model, "standard"));
    }
    void saveSpeed(String model, String value) {
        String speed = GptSpeed.normalize(value);
        if (model == null || model.isBlank() || ("fast".equals(speed) && !GptSpeed.supported(store.openai.describe().optString("mode"), model, store.openai.models())))
            throw new IllegalArgumentException("이 연결과 모델에서 Fast 지원을 확인하지 못했어요.");
        if (!store.settings.edit().putString("browserSpeed." + model, speed).putString("browserSpeedEpoch." + model, store.openai.speedEpoch()).commit()) throw new IllegalStateException("브라우저 속도를 저장하지 못했어요.");
        store.changed();
    }
    void save(String brain, String model, String effort) {
        if (!Set.of("none", "gpt", "claude", "gemini", "grok", "nim").contains(brain) || model.isBlank()) throw new IllegalArgumentException("브라우저 모델을 선택해 주세요.");
        boolean offered = false; JSONArray models = catalog(brain);
        for (int i = 0; i < models.length(); i++) { JSONObject item = models.optJSONObject(i); offered |= item != null && model.equals(item.optString("id")); }
        if (!offered && !model.equals(model(brain))) throw new IllegalArgumentException("현재 연결에서 제공하는 모델을 선택해 주세요.");
        Reasoning.Selections values = new Reasoning.Selections(store.settings.getString("browserReasoning", "{}")); values.set(brain + ":" + model, effort, profile(brain, model));
        store.settings.edit().putString("browserBrain", brain).putString("browserModel." + brain, model).putString("browserReasoning", values.serialize()).apply(); store.changed();
    }
    String label(String brain, String model) {
        JSONArray list = catalog(brain); for (int i = 0; i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null && model.equals(item.optString("id"))) { String label = item.optString("name"); return label.isBlank() ? model : label; } }
        return model;
    }
    String summary(String brain) { String model = model(brain), effort = effort(brain, model); return label(brain, model) + ("gpt".equals(brain) && "fast".equals(speed(model)) ? " · Fast 요청" : "") + (effort.isEmpty() ? "" : " · " + ("gpt".equals(brain) ? Reasoning.gptLabel(effort) : Reasoning.label(effort))); }
}
