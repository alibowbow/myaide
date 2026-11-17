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
    private int next;

    void accept(JSONObject event) throws Exception {
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
        for (Item item : items.values()) {
            JSONObject value = item.value;
            if (value == null) throw new IllegalArgumentException();
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
