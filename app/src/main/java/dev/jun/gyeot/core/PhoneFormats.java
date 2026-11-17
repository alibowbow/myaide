package dev.jun.gyeot.core;

import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Successful command formats for this connection. Credentials and device data never enter this cache. */
public final class PhoneFormats {
    private JSONObject formats;
    private long generation;
    public PhoneFormats(String saved) {
        try { formats = saved != null && saved.length() <= 20_000 ? new JSONObject(saved) : obj(); }
        catch (Exception invalid) { formats = obj(); }
    }
    public synchronized boolean plans(String model) { return "json".equals(formats.optString(model)); }
    public synchronized long generation() { return generation; }
    /** A response from a previous account must not mark the new account's format as working. */
    public synchronized boolean rememberPlans(String model, long ticket) {
        if (ticket != generation || model == null || model.isBlank() || model.length() > 200 || plans(model) || (formats.length() >= 64 && !formats.has(model))) return false;
        try { formats.put(model, "json"); return true; } catch (Exception invalid) { return false; }
    }
    public synchronized void reset() { generation++; formats = obj(); }
    public synchronized String serialize() { return formats.toString(); }
}
