package dev.jun.gyeot.core;

import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Function declarations written in Gemini's schema form (upper-case types, nullable), as the voice assistant's 마이아카이브
 * and connector functions are (Archive.declarations, Mcp.tools), turned into the JSON Schema form the app's other tools
 * use: GitHubFunctions.openAi and claude send it as it is, and GitHubFunctions.gemini turns it back. A function without
 * properties gets no parameters, which every provider takes.
 */
public final class ToolSchemas {
    private ToolSchemas() {}

    /** Each declaration as {name, description, parameters?}; one without a name is left out. */
    public static JSONArray canonical(JSONArray declarations) {
        JSONArray result = arr();
        for (int i = 0; declarations != null && i < declarations.length(); i++) {
            JSONObject declaration = declarations.optJSONObject(i);
            if (declaration != null && !declaration.optString("name").isBlank()) result.put(canonical(declaration));
        }
        return result;
    }
    public static JSONObject canonical(JSONObject declaration) {
        JSONObject result = obj("name", declaration.optString("name"), "description", declaration.optString("description"));
        JSONObject parameters = declaration.optJSONObject("parameters");
        JSONObject properties = parameters == null ? null : parameters.optJSONObject("properties");
        if (properties != null && properties.length() > 0) put(result, "parameters", lower(parameters, 0));
        return result;
    }
    /** One schema with lower-case types; only the parts every provider reads are kept (nullable is Gemini's own and goes). */
    static JSONObject lower(JSONObject schema, int depth) {
        if (schema == null || depth > 8) return obj("type", "string");
        String type = schema.optString("type", "string").toLowerCase(Locale.ROOT);
        JSONObject out = obj("type", type);
        String description = schema.optString("description", "");
        if (!description.isBlank()) put(out, "description", description);
        JSONArray values = schema.optJSONArray("enum");
        if (values != null && values.length() > 0) put(out, "enum", values);
        if (type.equals("array")) put(out, "items", lower(schema.optJSONObject("items"), depth + 1));
        if (type.equals("object")) {
            JSONObject properties = schema.optJSONObject("properties"), converted = obj();
            if (properties != null) for (Iterator<String> keys = properties.keys(); keys.hasNext(); ) {
                String key = keys.next(); JSONObject property = properties.optJSONObject(key);
                if (property != null) put(converted, key, lower(property, depth + 1));
            }
            put(out, "properties", converted);
            JSONArray required = schema.optJSONArray("required"), kept = arr();
            if (required != null) for (int i = 0; i < required.length(); i++) if (converted.has(required.optString(i))) kept.put(required.optString(i));
            if (kept.length() > 0) put(out, "required", kept);
        }
        return out;
    }
    /** Arguments as a model sent them: an object (Claude, Gemini) or JSON text (Responses, Chat Completions); anything else is none. */
    public static JSONObject arguments(Object value) {
        if (value instanceof JSONObject object) return object;
        String text = value == null ? "" : String.valueOf(value).strip();
        if (text.isEmpty()) return obj();
        try { return new JSONObject(text); } catch (Exception e) { throw new IllegalArgumentException("도구 인자를 읽지 못했어요."); }
    }
}
