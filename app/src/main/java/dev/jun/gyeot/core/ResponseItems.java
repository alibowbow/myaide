package dev.jun.gyeot.core;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Reconstructs completed Responses items when the terminal event omits its output array. */
final class ResponseItems {
    private static final class Item {
        JSONObject value;
        final StringBuilder arguments = new StringBuilder();
        boolean done, argumentsDone;
    }
    private final TreeMap<Integer, Item> items = new TreeMap<>();
    private final Map<String, Integer> identities = new HashMap<>();
    /** Items of a tool the provider runs itself (xAI's X search, web search): not the app's to check or to run. */
    private final java.util.Set<Integer> provider = new java.util.HashSet<>();
    private int next;

    /**
     * One stream event. Only the app's own function calls are held to strict order and identity: the app runs them. A
     * provider's own tool (Grok's x_search streams its inner calls with their own ids and arguments) is passed over, so
     * its stream never stops the answer (준's 트레이딩 news: "Grok 도구 응답의 순서나 식별자가 올바르지 않아").
     */
    void accept(JSONObject event) throws Exception {
        try { take(event); }
        catch (IllegalArgumentException | org.json.JSONException invalid) {
            if (appFunction(event)) throw invalid;
            Integer index = indexOf(event); if (index != null) provider.add(index);
        }
    }
    private Integer indexOf(JSONObject event) {
        JSONObject value = event.optJSONObject("item");
        String id = value == null ? event.optString("item_id", "") : value.optString("id", "");
        Integer known = id.isEmpty() ? null : identities.get(id);
        return known != null ? known : event.has("output_index") ? Integer.valueOf(event.optInt("output_index")) : null;
    }
    /** Whether the event is about one of the app's own function calls (a function_call item, or an item not seen yet). */
    private boolean appFunction(JSONObject event) {
        JSONObject value = event.optJSONObject("item");
        if (value != null) return "function_call".equals(value.optString("type"));
        Integer index = indexOf(event); Item item = index == null ? null : items.get(index);
        if (item != null && item.value != null) return "function_call".equals(item.value.optString("type"));
        // An item not announced yet: the app's only when the stream has carried a function call of the app's at all.
        for (Item seen : items.values()) if (seen.value != null && "function_call".equals(seen.value.optString("type"))) return true;
        return false;
    }
    private void take(JSONObject event) throws Exception {
        String type = event.optString("type");
        JSONObject value = event.optJSONObject("item");
        String id = value == null ? event.optString("item_id", "") : value.optString("id", "");
        // item.id and call_id are different identities. A late item.id may follow a call-only added item.
        Integer known = id.isEmpty() ? null : identities.get(id);
        int index = event.has("output_index") ? event.getInt("output_index") : known == null ? next : known;
        if (index < 0 || index > 1000 || (known != null && known != index)) throw new IllegalArgumentException();
        if (!id.isEmpty()) {
            for (Map.Entry<String, Integer> identity : identities.entrySet())
                if (identity.getValue() == index && !identity.getKey().equals(id)) throw new IllegalArgumentException();
            identities.put(id, index);
        }
        next = Math.max(next, index + 1);
        Item item = items.computeIfAbsent(index, unused -> new Item());
        if (value != null) {
            if (item.value != null) for (String key : new String[]{"type", "id", "call_id", "name"})
                if (!item.value.optString(key).isBlank() && !value.optString(key).isBlank() && !item.value.optString(key).equals(value.optString(key))) throw new IllegalArgumentException();
            if (item.value == null) item.value = new JSONObject();
            for (java.util.Iterator<String> keys = value.keys(); keys.hasNext();) {
                String key = keys.next();
                if (value.isNull(key) || value.opt(key) instanceof String s && s.isEmpty() && !item.value.optString(key).isEmpty()) continue;
                item.value.put(key, value.opt(key));
            }
        }
        switch (type) {
            case "response.output_item.done" -> item.done = true;
            case "response.function_call_arguments.delta" -> {
                if (item.argumentsDone || item.done) throw new IllegalArgumentException();
                String delta = event.getString("delta");
                if (item.arguments.length() + delta.length() > 100_000) throw new IllegalArgumentException();
                item.arguments.append(delta);
            }
            case "response.function_call_arguments.done" -> {
                String arguments = event.getString("arguments");
                if (arguments.length() > 100_000 || !arguments.startsWith(item.arguments.toString())) throw new IllegalArgumentException();
                item.arguments.setLength(0); item.arguments.append(arguments); item.argumentsDone = true;
            }
            default -> {}
        }
    }
    JSONArray completed() throws Exception {
        JSONArray output = arr();
        boolean appCalls = false; for (Item seen : items.values()) appCalls |= seen.value != null && "function_call".equals(seen.value.optString("type"));
        for (Map.Entry<Integer, Item> entry : items.entrySet()) {
            Item item = entry.getValue(); JSONObject value = item.value;
            // A provider's own tool item (search) is its record, not something to run: kept when whole, else left out.
            boolean own = provider.contains(entry.getKey()) || value != null && value.optString("type").endsWith("_call") && !"function_call".equals(value.optString("type"));
            if (own && (value == null || !item.done)) continue;
            // Arguments with no item: an error in a stream with the app's own calls, else a provider tool's leftover.
            if (value == null) { if (appCalls) throw new IllegalArgumentException(); continue; }
            if ("function_call".equals(value.optString("type"))) {
                if (value.optString("call_id").isBlank() || value.optString("name").isBlank()) throw new IllegalArgumentException();
                String arguments = value.optString("arguments");
                if (item.argumentsDone) {
                    if (!arguments.isBlank() && !arguments.equals(item.arguments.toString())) throw new IllegalArgumentException();
                    value.put("arguments", item.arguments.toString());
                } else if (!arguments.startsWith(item.arguments.toString())) throw new IllegalArgumentException();
            }
            if (!item.done) {
                if (!"function_call".equals(value.optString("type")) || !item.argumentsDone
                    || value.optString("call_id").isBlank() || value.optString("name").isBlank()) throw new IllegalArgumentException();
                value.put("arguments", item.arguments.toString());
                if (value.has("status")) value.put("status", "completed");
            }
            output.put(value);
        }
        return output;
    }
}
