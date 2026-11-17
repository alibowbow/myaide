package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;

public final class Jsons {
    /** Android's JSON API uses checked exceptions; callers only pass known JSON-compatible values. */
    public static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (org.json.JSONException e) { throw new IllegalArgumentException("JSON 값을 저장하지 못했어요.", e); }
    }
    private Jsons() {}
    public static JSONObject obj(Object... pairs) {
        try {
            JSONObject value = new JSONObject();
            for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
            return value;
        } catch (Exception error) { throw new IllegalArgumentException(error); }
    }
    public static JSONArray arr(Object... items) {
        JSONArray value = new JSONArray();
        for (Object item : items) value.put(item);
        return value;
    }
    public static JSONArray copy(JSONArray value) {
        try { return new JSONArray(value.toString()); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
    }
    public static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
    }
    public static String message(Throwable error, String key) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        if (key != null && !key.isBlank()) value = value.replace(key, "[API key]");
        return value.replaceAll("(?i)(key=)[^&\\s]+", "$1[redacted]");
    }
}
