package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 길찾기 → 자주 가는 곳: words the user says for a place ("집", "회사", "엄마집") and the place itself, with its coordinates, so
 * "집으로 가자" starts Naver Map's navigation there at once, with no search and no question. The words are matched whole,
 * whatever the particle after them (집으로 · 집에 · 집까지) and with or without spaces (우리 집 · 우리집). Pure Java.
 */
public final class SavedPlaces {
    private SavedPlaces() {}
    public static final int MOST = 30, WORDS = 5;

    /** One saved place: the words for it, the place's name and address, where it is, and its way of travel ("" the default). */
    public record Saved(List<String> words, String name, String address, double lat, double lng, String mode, List<NaverPlaces.Place> via) {
        public Saved(List<String> words, String name, String address, double lat, double lng) { this(words, name, address, lat, lng, "", List.of()); }
        public NaverPlaces.Place place() { return new NaverPlaces.Place(name, address, lat, lng); }
        public JSONObject json() {
            JSONArray said = new JSONArray(); for (String word : words) said.put(word);
            JSONArray stops = new JSONArray(); for (NaverPlaces.Place stop : via) stops.put(obj("name", stop.name(), "address", stop.address(), "lat", stop.lat(), "lng", stop.lng()));
            return obj("words", said, "name", name, "address", address, "lat", lat, "lng", lng, "mode", mode, "via", stops);
        }
        public static Saved of(JSONObject json) {
            if (json == null) return null;
            List<String> words = SavedPlaces.words(json.optJSONArray("words"));
            double lat = json.optDouble("lat", Double.NaN), lng = json.optDouble("lng", Double.NaN);
            if (words.isEmpty() || Double.isNaN(lat) || Double.isNaN(lng)) return null;
            String mode = json.optString("mode");
            List<NaverPlaces.Place> via = new ArrayList<>(); JSONArray stops = json.optJSONArray("via");
            for (int i = 0; stops != null && i < stops.length() && via.size() < NaverPlaces.MOST_VIA; i++) {
                JSONObject stop = stops.optJSONObject(i); if (stop == null) continue;
                double a = stop.optDouble("lat", Double.NaN), b = stop.optDouble("lng", Double.NaN);
                if (!Double.isNaN(a) && !Double.isNaN(b)) via.add(new NaverPlaces.Place(stop.optString("name"), stop.optString("address"), a, b));
            }
            return new Saved(words, json.optString("name").strip(), json.optString("address").strip(), lat, lng, NaverPlaces.MODES.contains(mode) ? mode : "", via);
        }
    }

    /** The saved places in their JSON (settings "savedPlaces"); anything unreadable is left out. */
    public static List<Saved> load(String json) {
        List<Saved> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(json == null || json.isBlank() ? "[]" : json);
            for (int i = 0; i < list.length() && out.size() < MOST; i++) { Saved one = Saved.of(list.optJSONObject(i)); if (one != null) out.add(one); }
        } catch (Exception broken) { /* None. */ }
        return out;
    }
    public static String save(List<Saved> places) {
        JSONArray list = new JSONArray(); for (Saved one : places) list.put(one.json());
        return list.toString();
    }
    /** "집, 우리집 / 우리 집" typed as one line: each word, trimmed, without its particle, at most WORDS, no repeats. */
    public static List<String> words(String typed) {
        List<String> out = new ArrayList<>();
        for (String part : (typed == null ? "" : typed).split("[,，、/·\\n]+")) {
            String word = part.strip(); if (word.isEmpty()) continue;
            if (out.stream().noneMatch(w -> key(w).equals(key(word)))) out.add(word);
            if (out.size() >= WORDS) break;
        }
        return out;
    }
    private static List<String> words(JSONArray list) {
        List<String> out = new ArrayList<>();
        for (int i = 0; list != null && i < list.length() && out.size() < WORDS; i++) { String word = list.optString(i).strip(); if (!word.isEmpty()) out.add(word); }
        return out;
    }
    /** The place a destination names by one of its words, or null: "집", "집으로", "우리 집까지", "회사 쪽으로" all match. */
    public static Saved match(String destination, List<Saved> places) {
        String wanted = key(destination);
        if (wanted.isEmpty() || places == null) return null;
        for (Saved one : places) for (String word : one.words()) if (key(word).equals(wanted)) return one;
        return null;
    }
    /** A word as matched: no spaces or marks, lower case, and the endings of a spoken destination taken off (으로, 로, 에, 까지, 쪽 …). */
    static String key(String text) {
        String value = (text == null ? "" : text).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·]+", "");
        String before;
        do {
            before = value;
            value = value.replaceAll("(으로|로|에|에게|까지|쪽|방향|가는길|가자|가줘|안내|길안내)$", "");
        } while (!value.equals(before) && !value.isEmpty());
        return value.isEmpty() ? before : value;
    }
    /** For the voice assistant: the saved words, so it passes them as the destination as they are. "" when there are none. */
    public static String instructions(List<Saved> places) {
        if (places == null || places.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\n\nThe user saved places for 길찾기 (자주 가는 곳). When they ask to go to one by any of its words "
            + "(\"집으로 가자\", \"회사까지 안내해 줘\"), call navigate at once with that word as destination, as said, without asking:\n<saved_places>");
        for (Saved one : places) {
            text.append("\n- ").append(String.join(", ", one.words())).append(" → ").append(one.name().isEmpty() ? one.address() : one.name());
            if (!one.via().isEmpty()) text.append(" (경유: ").append(String.join(", ", one.via().stream().map(NaverPlaces.Place::name).toList())).append(")");
        }
        return text.append("\n</saved_places>").toString();
    }
}
