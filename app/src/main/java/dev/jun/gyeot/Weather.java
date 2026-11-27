package dev.jun.gyeot;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 들퍼레 홈 화면's weather (준: "가운데 퍼레 준비됐어요를 빼고 날씨위젯을 넣어줘"): where the phone roughly is (the approximate
 * location permission, the last place the phone knows; never a fresh fix that keeps the radio on), the weather there from
 * Open-Meteo (free, no key; only the rounded coordinates are sent) and the place's name from the phone's own geocoder.
 * Kept half an hour; null without the permission or a place. Off the main thread.
 */
final class Weather {
    private Weather() {}
    private static volatile JSONObject kept; private static volatile long keptAt;
    static boolean allowed(Context c) { return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    /** {temp, code, day, hi, lo, place}, or null. */
    static JSONObject now(Context c) {
        long t = System.currentTimeMillis();
        if (kept != null && t - keptAt < 30 * 60_000L) return kept;
        if (!allowed(c)) return null;
        Location at = last(c);
        if (at == null) return kept;
        // About a kilometre: enough for the weather, not the user's door.
        double lat = Math.round(at.getLatitude() * 100) / 100.0, lon = Math.round(at.getLongitude() * 100) / 100.0;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new java.net.URL(String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1", lat, lon)).openConnection();
            h.setConnectTimeout(8_000); h.setReadTimeout(8_000); h.setRequestProperty("User-Agent", "Gyeot/1.0");
            if (h.getResponseCode() != 200) return kept;
            JSONObject body;
            try (InputStream in = h.getInputStream()) { body = new JSONObject(new String(in.readNBytes(100_000), StandardCharsets.UTF_8)); }
            JSONObject cur = body.optJSONObject("current"), daily = body.optJSONObject("daily");
            if (cur == null) return kept;
            JSONObject out = new JSONObject();
            out.put("temp", Math.round(cur.optDouble("temperature_2m"))).put("code", cur.optInt("weather_code")).put("day", cur.optInt("is_day", 1) == 1);
            if (daily != null && daily.optJSONArray("temperature_2m_max") != null) {
                out.put("hi", Math.round(daily.getJSONArray("temperature_2m_max").optDouble(0))).put("lo", Math.round(daily.getJSONArray("temperature_2m_min").optDouble(0)));
            }
            out.put("place", place(c, at));
            // 하루 기록: the neighbourhood, from the place the phone already knew (no new location asked for).
            DayLogs.area(out.optString("place"));
            kept = out; keptAt = t;
            return out;
        } catch (Exception offline) { return kept; }
        finally { if (h != null) h.disconnect(); }
    }
    @SuppressWarnings("MissingPermission")
    private static Location last(Context c) {
        LocationManager m = c.getSystemService(LocationManager.class); if (m == null) return null;
        Location best = null;
        for (String p : List.of(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try { Location l = m.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } catch (RuntimeException ignored) {}
        }
        return best;
    }
    /** The neighbourhood's name (사천읍), else the town's, from the phone's own geocoder; "" when it does not know. */
    @SuppressWarnings("deprecation")
    private static String place(Context c, Location at) {
        try {
            if (!Geocoder.isPresent()) return "";
            List<Address> found = new Geocoder(c, Locale.KOREA).getFromLocation(at.getLatitude(), at.getLongitude(), 1);
            if (found == null || found.isEmpty()) return "";
            Address a = found.get(0);
            for (String name : new String[]{a.getSubLocality(), a.getLocality(), a.getSubAdminArea(), a.getAdminArea()})
                if (name != null && !name.isBlank()) return name.strip();
        } catch (Exception unknown) { /* no name */ }
        return "";
    }
}
