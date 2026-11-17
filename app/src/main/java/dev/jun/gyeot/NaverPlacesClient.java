package dev.jun.gyeot;

import dev.jun.gyeot.core.NaverPlaces;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 네이버 길안내: the user's NAVER API HUB key, kept in SecretStore.NAVER_PLACES, and its 지역 검색.
 * Never on the main thread. The key goes only to NaverPlaces.ENDPOINT, in its two headers; no error text carries it.
 */
final class NaverPlacesClient {
    /** The user's application: Client ID and Client Secret as NAVER API HUB shows them under 인증 정보. */
    record Key(String id, String secret) {}
    private NaverPlacesClient() {}

    static boolean connected(AppStore store) { return store.secrets.has(SecretStore.NAVER_PLACES); }
    /** The saved key, or null for none (or one that cannot be read any more). Reads the vault: off the main thread when possible. */
    static Key key(AppStore store) {
        try {
            String raw = store.secrets.get(SecretStore.NAVER_PLACES); if (raw.isBlank()) return null;
            JSONObject value = new JSONObject(raw); String id = value.optString("id").strip(), secret = value.optString("secret").strip();
            return id.isEmpty() || secret.isEmpty() ? null : new Key(id, secret);
        } catch (Exception unreadable) { return null; }
    }
    static void save(AppStore store, Key key) throws Exception {
        if (key == null) { store.secrets.put(SecretStore.NAVER_PLACES, ""); store.changed(); return; }
        if (!valid(key.id()) || !valid(key.secret())) throw new IllegalArgumentException("Client ID와 Client Secret을 공백 없이 그대로 붙여 넣어 주세요.");
        store.secrets.put(SecretStore.NAVER_PLACES, obj("id", key.id().strip(), "secret", key.secret().strip()).toString()); store.changed();
    }
    /** A pasted value: printable, no spaces inside, not absurdly long. */
    static boolean valid(String value) { String text = value == null ? "" : value.strip(); return !text.isEmpty() && text.length() <= 200 && text.matches("[\\x21-\\x7e]+"); }

    /** Places for a destination, best match first (NaverPlaces.parse). A refusal says why in Korean, never with the key. */
    static List<NaverPlaces.Place> search(Key key, String destination, AtomicReference<Runnable> cancel) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(NaverPlaces.KEY_ID, key.id()); headers.put(NaverPlaces.KEY_SECRET, key.secret()); headers.put("Accept", "application/json");
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", NaverPlaces.url(destination), headers, null, 10_000, cancel)) {
            String body = OpenAiAccount.Http.read(response.body, 256 * 1024);
            if (response.status == 401 || response.status == 403) throw new IllegalStateException("네이버 길안내 키가 맞지 않거나 지역 검색이 선택되지 않았어요. 설정 → 커넥터 → 네이버 길안내에서 확인해 주세요.");
            if (response.status == 429) throw new IllegalStateException("네이버 지역 검색의 오늘 한도를 다 썼어요.");
            if (response.status >= 300) throw new IOException("네이버 지역 검색이 응답하지 않았어요 (HTTP " + response.status + ").");
            try { return NaverPlaces.parse(new JSONObject(body)); }
            catch (org.json.JSONException unreadable) { throw new IOException("네이버 지역 검색의 응답을 읽지 못했어요."); }
        }
    }
}
