package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Model-specific reasoning options. Account-advertised capabilities take precedence over the documented fallback. */
public final class Reasoning {
    public static final String[] MODEL_FIELDS = {"supported_reasoning_levels", "supported_reasoning_efforts", "supportedReasoningEfforts", "reasoningEfforts",
        "default_reasoning_level", "default_reasoning_effort", "defaultReasoningEffort", "supports_reasoning"};
    private static final List<String> ORDER = List.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");
    private Reasoning() {}
    /** How deep a level thinks: none 0 … ultra 7 (unknown and the model default -1), for listing them shallow to deep. */
    public static int rank(String effort) { return ORDER.indexOf(effort == null ? "" : effort); }

    public static final class Profile {
        public final List<String> efforts;
        public final String defaultEffort;
        public final boolean advertised, known;
        /** A profile a provider documents for its models (Gemini's thinking levels). */
        public static Profile of(List<String> efforts, String defaultEffort) { return new Profile(efforts, defaultEffort, true, true); }
        private Profile(List<String> efforts, String defaultEffort, boolean advertised, boolean known) {
            this.efforts = List.copyOf(efforts); this.defaultEffort = efforts.contains(defaultEffort) ? defaultEffort : "";
            this.advertised = advertised; this.known = known;
        }
        /** Empty is the saved "model default" choice; resolve it against the advertised default before sending. */
        public String normalize(String effort) { return efforts.contains(effort) ? effort : ""; }
        public String resolve(String effort) { String selected = normalize(effort); return selected.isEmpty() ? defaultEffort : selected; }
        public String[] values() {
            String[] result = new String[efforts.size() + 1]; result[0] = "";
            for (int i = 0; i < efforts.size(); i++) result[i + 1] = efforts.get(i);
            return result;
        }
        /** GPT-specific display names; values and provider capability resolution are unchanged. */
        public String[] gptLabels() {
            String[] values = values(), labels = new String[values.length];
            labels[0] = "모델 기본값" + (defaultEffort.isEmpty() ? "" : " · " + gptLabel(defaultEffort));
            for (int i = 1; i < values.length; i++) labels[i] = gptLabel(values[i]);
            return labels;
        }
        public String[] labels() {
            String[] values = values(), labels = new String[values.length];
            labels[0] = "모델 기본값" + (defaultEffort.isEmpty() ? "" : " · " + label(defaultEffort));
            for (int i = 1; i < values.length; i++) labels[i] = label(values[i]);
            return labels;
        }
    }
    /** Presentation only: Light still sends the provider's low effort, not a speed tier. */
    public static String gptLabel(String effort) { return "low".equals(effort) ? "Light · 낮음" : label(effort); }
    public static String gptShortLabel(String effort) {
        return switch (effort) {
            case "none" -> "끔";
            case "minimal" -> "최소";
            case "low" -> "Light";
            case "medium" -> "Medium";
            case "high" -> "높음";
            case "xhigh" -> "Extra High";
            case "max" -> "Max";
            case "ultra" -> "Ultra";
            default -> "기본";
        };
    }
    /** Claude's own words for its levels, as on claude.ai: 낮음 · 중간 · 높음 · 엑스트라 · 최대. */
    public static String claudeLabel(String effort) {
        return switch (effort) {
            case "low" -> "낮음"; case "medium" -> "중간"; case "high" -> "높음"; case "xhigh" -> "엑스트라"; case "max" -> "최대";
            case "none" -> "끔"; case "minimal" -> "최소"; case "ultra" -> "최고"; default -> "기본";
        };
    }
    public static String label(String effort) {
        return switch (effort) {
            case "none" -> "None · 추론 끄기";
            case "minimal" -> "Minimal · 최소";
            case "low" -> "Low · 낮음";
            case "medium" -> "Medium · 중간";
            case "high" -> "High · 높음";
            case "xhigh" -> "Extra high · 매우 높음";
            case "max" -> "Max · 최대";
            case "ultra" -> "Ultra · 최고";
            default -> "모델 기본값";
        };
    }
    public static Profile profile(String id, JSONArray models) {
        if (models != null) for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && id != null && id.equals(model.optString("id"))) return profile(id, model);
        }
        return profile(id, (JSONObject) null);
    }
    public static Profile profile(String id, JSONObject model) {
        if (model != null) {
            if (model.has("supports_reasoning") && !model.optBoolean("supports_reasoning", true)) return new Profile(List.of(), "", true, true);
            for (String key : new String[]{"supported_reasoning_levels", "supported_reasoning_efforts", "supportedReasoningEfforts", "reasoningEfforts"}) {
                JSONArray levels = model.optJSONArray(key);
                if (levels == null) continue;
                List<String> supported = new ArrayList<>();
                for (int i = 0; i < levels.length(); i++) {
                    JSONObject level = levels.optJSONObject(i);
                    String value = level == null ? levels.optString(i) : level.optString("effort", level.optString("reasoningEffort", level.optString("reasoning_effort")));
                    if (ORDER.contains(value) && !supported.contains(value)) supported.add(value);
                }
                supported.sort(java.util.Comparator.comparingInt(ORDER::indexOf));
                String fallback = model.optString("default_reasoning_level", model.optString("default_reasoning_effort", model.optString("defaultReasoningEffort")));
                return new Profile(supported, fallback, true, true);
            }
        }
        // Official model pages checked 2026-10-22. Match exact families/snapshots, never guess capabilities for future models.
        String family = id == null ? "" : id.toLowerCase(Locale.ROOT).replaceFirst("-\\d{4}-\\d{2}-\\d{2}$", "");
        Profile fallback = switch (family) {
            // GPT-6.2 Astra and Sol: their model pages say only that none is rejected, so each keeps the levels of the model it follows.
            case "gpt-6-astra", "gpt-6.2-astra" -> fallback("", "low", "medium", "high", "xhigh", "max");
            case "gpt-6.1-sol", "gpt-6.2-sol" -> fallback("medium", "low", "medium", "high", "xhigh", "max");
            case "gpt-6.1-terra" -> fallback("medium", "none", "low", "medium", "high", "xhigh", "max"); // API model page, 2026-10-27; advertised levels still win.
            case "gpt-6-sol", "gpt-6-luna", "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna" -> fallback("medium", "none", "low", "medium", "high", "xhigh", "max");
            case "gpt-5.5" -> fallback("medium", "none", "low", "medium", "high", "xhigh");
            case "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano", "gpt-5.2" -> fallback("none", "none", "low", "medium", "high", "xhigh");
            case "gpt-5.4-pro", "gpt-5.2-pro" -> fallback("medium", "medium", "high", "xhigh");
            case "gpt-5.3-codex", "gpt-5.2-codex", "gpt-5.1-codex-max" -> fallback("", "low", "medium", "high", "xhigh");
            case "gpt-5.1" -> fallback("none", "none", "low", "medium", "high");
            case "gpt-5", "gpt-5-mini", "gpt-5-nano" -> fallback("medium", "minimal", "low", "medium", "high");
            case "o1", "o3", "o3-mini", "o4-mini" -> fallback("medium", "low", "medium", "high");
            case "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4o", "gpt-4o-mini" -> new Profile(List.of(), "", false, true);
            default -> new Profile(List.of(), "", false, false);
        };
        // A catalog may advertise a default without repeating the whole capability list.
        if (model != null) {
            String advertisedDefault = model.optString("default_reasoning_level", model.optString("default_reasoning_effort", model.optString("defaultReasoningEffort")));
            if (fallback.efforts.contains(advertisedDefault)) return new Profile(fallback.efforts, advertisedDefault, true, true);
            if (!fallback.known && ORDER.contains(advertisedDefault)) return new Profile(List.of(advertisedDefault), advertisedDefault, true, true);
        }
        return fallback;
    }
    private static Profile fallback(String defaultEffort, String... levels) { return new Profile(List.of(levels), defaultEffort, false, true); }

    /** Persisted independently by model; the old global value migrates only to the model selected at upgrade time. */
    public static final class Selections {
        private final JSONObject values;
        public Selections(String stored) {
            JSONObject parsed;
            try { parsed = new JSONObject(stored == null ? "{}" : stored); } catch (Exception e) { parsed = new JSONObject(); }
            values = parsed;
        }
        public String get(String model, Profile profile) { return profile.normalize(values.optString(model)); }
        public void set(String model, String effort, Profile profile) {
            if (model == null || model.isBlank() || effort == null || (!effort.isEmpty() && !profile.efforts.contains(effort))) throw new IllegalArgumentException("이 모델이 지원하는 추론 레벨을 골라 주세요.");
            try { values.put(model, effort); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        public void migrate(String model, String legacy, Profile profile) {
            if (model != null && !model.isBlank() && !values.has(model)) set(model, profile.normalize(legacy), profile);
        }
        public String serialize() { return values.toString(); }
    }
    /** Reject an unsupported selected effort explicitly, rather than silently retrying at a different level. */
    public static boolean rejected(int status, JSONObject body) {
        if (status != 400 && status != 422) return false;
        JSONObject error = body.optJSONObject("error"); if (error == null) error = body;
        String param = error.optString("param"), message = OpenAiProtocol.message(body, "").toLowerCase(Locale.ROOT);
        return param.equals("reasoning.effort") || param.equals("reasoning_effort") || param.equals("reasoning")
            || message.contains("reasoning_effort") || message.contains("reasoning.effort") || (message.contains("reasoning") && message.contains("effort"));
    }
    public static String rejection(String model, String effort) {
        return "이 연결에서 " + model + "의 " + label(effort) + " 추론 설정을 거부했어요. 설정 → ChatGPT에서 모델 새로고침을 누르거나 추론 레벨을 모델 기본값으로 바꿔 주세요.";
    }
}
