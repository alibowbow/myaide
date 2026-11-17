package dev.jun.gyeot.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 네이버 길안내: with the user's NAVER API HUB key, a spoken destination becomes a place with
 * coordinates (지역 검색), and Naver Map starts its navigation to it (nmap://navigation) or shows the route for another way of
 * travel (nmap://route/…). Several places that fit are offered back for the user to pick by voice. Without a key, a search
 * that fails, or a 근처 request (지역 검색 does not know where the user is), DeviceActions' map search opens as before.
 */
public final class NaverPlaces {
    /** NAVER API HUB 지역 검색: GET, X-NCP-APIGW-API-KEY-ID / X-NCP-APIGW-API-KEY, query, display 1-5. */
    public static final String ENDPOINT = "https://naverapihub.apigw.ntruss.com/search/v1/local";
    public static final String KEY_ID = "X-NCP-APIGW-API-KEY-ID", KEY_SECRET = "X-NCP-APIGW-API-KEY";
    /** Places a search returns, the most 지역 검색 gives at once. */
    public static final int RESULTS = 5;
    /** Ways of travel: car starts turn-by-turn navigation; the others show Naver Map's route for that way. */
    public static final List<String> MODES = List.of("car", "public", "walk", "bicycle");
    private static final List<String> MODE_LABELS = List.of("자동차", "대중교통", "도보", "자전거");
    private NaverPlaces() {}

    /** One place that fits: its name, road address (or lot address), and WGS84 latitude and longitude. */
    public record Place(String name, String address, double lat, double lng) {
        public JSONObject json(int number) { return obj("number", number, "name", name, "address", address); }
    }

    /** The search URL for a destination: up to RESULTS places, ranked by accuracy. */
    public static String url(String destination) {
        return ENDPOINT + "?query=" + URLEncoder.encode(destination == null ? "" : destination.strip(), StandardCharsets.UTF_8).replace("+", "%20") + "&display=" + RESULTS + "&sort=random";
    }
    /** The places in a 지역 검색 response, in its order, each with a name and coordinates inside Naver Map's range; never more than RESULTS. */
    public static List<Place> parse(JSONObject response) {
        List<Place> places = new ArrayList<>();
        JSONArray items = response == null ? null : response.optJSONArray("items");
        for (int i = 0; items != null && i < items.length() && places.size() < RESULTS; i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            String name = plain(item.optString("title")), road = plain(item.optString("roadAddress")), lot = plain(item.optString("address"));
            double lng = coordinate(item.optString("mapx")), lat = coordinate(item.optString("mapy"));
            if (name.isEmpty() || !inKorea(lat, lng)) continue;
            places.add(new Place(name, road.isEmpty() ? lot : road, lat, lng));
        }
        return places;
    }
    /**
     * A WGS84 coordinate as 지역 검색 writes it: a decimal ("127.0276368") or the same scaled by 10^7 ("1270276368"). NaN for
     * anything else.
     */
    static double coordinate(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) return Double.NaN;
        try {
            double value = Double.parseDouble(text);
            return Math.abs(value) > 1000 ? value / 1e7 : value;
        } catch (NumberFormatException invalid) { return Double.NaN; }
    }
    /** Naver Map's own range for map coordinates (its URL scheme's lat 31.43-44.35, lng 122.37-132.00). */
    static boolean inKorea(double lat, double lng) { return lat >= 31.43 && lat <= 44.35 && lng >= 122.37 && lng <= 132.00; }
    /** A title or address without the search's <b> marks and HTML entities. */
    static String plain(String html) {
        String text = html == null ? "" : html.replaceAll("<[^>]*>", "");
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        return text.replaceAll("\\s+", " ").strip();
    }
    /**
     * The one place to go to without asking: the only one found, or the first when its name is the destination itself or
     * holds it and no other one does. null when the user should pick (a chain's branches, several stations of one name).
     */
    public static Place sure(String destination, List<Place> places) {
        if (places == null || places.isEmpty()) return null;
        if (places.size() == 1) return places.get(0);
        String wanted = compact(destination), first = compact(places.get(0).name());
        if (wanted.isEmpty()) return null;
        if (first.equals(wanted)) return places.get(0);
        int holding = 0;
        for (Place place : places) if (compact(place.name()).contains(wanted)) holding++;
        return holding == 1 && first.contains(wanted) ? places.get(0) : null;
    }
    private static String compact(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·]+", ""); }
    /** car (the default), public, walk or bicycle; anything else is refused. */
    public static String mode(String raw) {
        String mode = raw == null || raw.isBlank() ? "car" : raw.strip().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw new IllegalArgumentException("이동 수단(mode)은 car, public, walk, bicycle 중 하나예요.");
        return mode;
    }
    public static String modeLabel(String mode) { return MODE_LABELS.get(MODES.indexOf(mode)); }
    /**
     * Naver Map's link for this place: navigation from where the user is (car), or the route for another way of travel, with
     * the place's name. appname is this app's id, as Naver Map asks.
     */
    public static String link(Place place, String mode, String appName) {
        String target = "dlat=" + String.format(Locale.ROOT, "%.7f", place.lat()) + "&dlng=" + String.format(Locale.ROOT, "%.7f", place.lng())
            + "&dname=" + URLEncoder.encode(place.name(), StandardCharsets.UTF_8).replace("+", "%20") + "&appname=" + appName;
        return "car".equals(mode) ? "nmap://navigation?" + target : "nmap://route/" + mode + "?" + target;
    }
    /**
     * Whether a destination is an address and not a place's name: it ends in a road (…로, …길), a neighbourhood (…동, …리, …가)
     * or a town (…읍, …면) with its number, with or without 번길, a hyphenated
     * number, 번지 or 호 after it. A name that only holds such a word (a branch number, an exit number) is not one.
     *
     * 지역 검색 finds places by name, not addresses, and a shorter search for one finds whatever is called by its last
     * words. An address is given to the maps app's own search instead, which knows addresses.
     */
    private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile(
        "(?:^|\\s)[가-힣A-Za-z0-9]*(?:로|길|동|리|가|읍|면)\\s*\\d+(?:\\s*번길\\s*\\d+)?(?:-\\d+)?\\s*(?:번지|호)?$");
    public static boolean address(String destination) { return destination != null && ADDRESS.matcher(destination.strip()).find(); }
    /** A search left with no name in it: only a number, or a number with 번지, 호, 층, 번 (what is left of an address). */
    private static final java.util.regex.Pattern NAMELESS = java.util.regex.Pattern.compile("[\\d\\s-]*(?:번지|번길|호|층|번)?[\\d\\s-]*");
    /**
     * Shorter searches for a destination 지역 검색 found nothing for: without its first word, then its first two
     * ("a b c" → "b c" → "c"), while at least one word is left; at most two. An address is never
     * shortened, and a try that would have no name left in it is not made.
     */
    public static List<String> shorter(String destination) {
        String[] words = destination == null ? new String[0] : destination.strip().split("\\s+");
        List<String> tries = new ArrayList<>();
        if (address(destination)) return tries;
        for (int drop = 1; drop <= 2 && words.length - drop >= 1; drop++) {
            String rest = String.join(" ", java.util.Arrays.copyOfRange(words, drop, words.length));
            if (rest.length() >= 2 && !NAMELESS.matcher(rest).matches()) tries.add(rest);
        }
        return tries;
    }
    /**
     * Places a shorter search found (shorter): always offered to confirm, never gone to at once, since fewer words may name
     * another place. The user picks one by number, as from choose.
     */
    public static JSONObject looser(String destination, String searched, List<Place> places) {
        JSONObject offer = choose(destination, places);
        String first = places.get(0).name();
        put(offer, "searched", searched);
        put(offer, "say", "‘" + destination + "’ 그대로는 찾지 못해서 ‘" + searched + "’" + Brand.euro(searched).substring(searched.length()) + " 찾았어요. " + (places.size() == 1 ? "‘" + first + "’" + Brand.josa(first, "이", "가").substring(first.length()) + " 맞아요?" : "이 중 어디로 갈까요?"));
        put(offer, "next", "Read the candidates' names, ask whether one is the place, then call navigate again with the same destination and pick set to its number. Nothing has opened yet.");
        return offer;
    }
    /**
     * Nothing found, even shorter: nothing is opened. The words may be misheard (a brand's spelling), so the model may try once
     * more with the proper name it knows; otherwise Naver Map's own search, which forgives misspellings, or the user's words again.
     */
    public static JSONObject notFound(String destination) { return notFound(destination, ""); }
    /**
     * As above, with heard: the user's words as the app wrote them down (정밀 자막's Transcribe, when on). The conversation model
     * chose destination from its own hearing; when the two differ, the caption's spelling of the place may be the right one.
     */
    public static JSONObject notFound(String destination, String heard) {
        String say = "네이버 지역 검색에서 ‘" + destination + "’" + Brand.josa(destination, "을", "를").substring(destination.length()) + " 찾지 못했어요.";
        JSONObject result = obj("ok", true, "tool", DeviceActions.NAVIGATE, "outcome", "not_found", "destination", destination, "opened", false, "say", say,
            "next", "Nothing opened. The words may be misheard: if heard spells the place differently, or a known place's proper name is close (a brand's real spelling, a branch), call navigate once more with it. "
                + "If that finds nothing either, ask the user to say it again, or call navigate with nearby true to open Naver Map's own search, which forgives misspellings.");
        String words = heard == null ? "" : heard.strip();
        if (!words.isEmpty()) put(result, "heard", words.length() > 200 ? words.substring(0, 200) : words);
        return result;
    }
    /**
     * The result of a navigate call Gemini cancelled while it ran (the user went on speaking): told to the model as a note of its
     * own, so it says what happened and does not ask again for what already opened.
     */
    public static String late(JSONObject result) {
        JSONObject told = obj("outcome", result.optString("outcome"), "say", result.optString("say"));
        for (String key : new String[]{"place", "candidates", "next"}) if (result.has(key)) put(told, key, result.opt(key));
        return "[" + Brand.NAME + "] (자동 안내) 방금 취소된 navigate 요청은 앱이 끝까지 처리했어요. 같은 요청으로 navigate를 다시 부르지 말고, 이 결과를 사용자에게 짧게 전하고 이어 가세요: " + told;
    }
    /** The places to choose from, numbered from 1, for the model to read out and the user to pick by number or name. */
    public static JSONObject choose(String destination, List<Place> places) {
        JSONArray list = arr();
        for (int i = 0; i < places.size(); i++) list.put(places.get(i).json(i + 1));
        String say = "‘" + destination + "’" + Brand.josa(destination, "이", "가").substring(destination.length()) + " 여러 곳이에요. 어디로 갈까요?";
        return obj("ok", true, "tool", DeviceActions.NAVIGATE, "outcome", "choose", "destination", destination, "candidates", list,
            "say", say, "next", "Read the candidates' names (and a short address part when names repeat), ask which one, then call navigate again with the same destination and pick set to its number. Nothing has opened yet.");
    }
}
