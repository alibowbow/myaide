package dev.jun.gyeot;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import dev.jun.gyeot.core.NaverPlaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where a place or an address is, for 내비게이션: an address goes to the phone's own geocoder (Android's, which knows Korean
 * road and lot addresses), a name to Naver's 지역 검색 when its key is saved (and the geocoder when not, or when it finds
 * nothing). Blocking; never on the main thread.
 */
final class PlaceLocator {
    private PlaceLocator() {}

    /** Places for what was typed or said, best first, at most NaverPlaces.RESULTS. */
    static List<NaverPlaces.Place> find(Context context, AppStore store, String query) {
        String text = query == null ? "" : query.strip();
        List<NaverPlaces.Place> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        boolean address = NaverPlaces.address(text) || text.matches(".*(시|도|구|군)\\s.*\\d.*");
        if (!address) {
            try { NaverPlacesClient.Key key = NaverPlacesClient.key(store); if (key != null) out.addAll(NaverPlacesClient.search(key, text, null)); }
            catch (Exception ignored) { /* The geocoder below. */ }
        }
        if (out.isEmpty()) out.addAll(geocode(context, text));
        return out.size() <= NaverPlaces.RESULTS ? out : new ArrayList<>(out.subList(0, NaverPlaces.RESULTS));
    }
    /** An address (or a well-known place) by the phone's geocoder: its coordinates, the address as given back. */
    static List<NaverPlaces.Place> geocode(Context context, String text) {
        List<NaverPlaces.Place> out = new ArrayList<>();
        if (!Geocoder.isPresent()) return out;
        try {
            @SuppressWarnings("deprecation") List<Address> found = new Geocoder(context, Locale.KOREA).getFromLocationName(text, NaverPlaces.RESULTS);
            for (Address one : found == null ? List.<Address>of() : found) {
                if (!one.hasLatitude() || !one.hasLongitude()) continue;
                String line = one.getMaxAddressLineIndex() >= 0 ? one.getAddressLine(0) : text;
                String name = one.getFeatureName() != null && !one.getFeatureName().matches("[\\d\\-]+") ? one.getFeatureName() : text;
                out.add(new NaverPlaces.Place(name, line == null ? text : line.replaceFirst("^대한민국\\s*", ""), one.getLatitude(), one.getLongitude()));
            }
        } catch (Exception unavailable) { /* No network, or no geocoder service on this phone. */ }
        return out;
    }
}
