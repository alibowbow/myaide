package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiProtocol;
import dev.jun.gyeot.core.GeminiSearch;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** Answers search_web calls with one grounded generateContent request. Blocking; call it off the main thread. */
final class GeminiSearchClient {
    private GeminiSearchClient() {}
    /** The model that last answered with search, tried first next time. */
    static String model(AppStore store) { return store.settings.getString("searchModel", ""); }
    static GeminiSearch.Result search(AppStore store, String key, String query) throws Exception {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("검색어가 비어 있어요.");
        byte[] body = GeminiSearch.request(query.strip(), GeminiProtocol.clock(ZonedDateTime.now())).toString().getBytes(StandardCharsets.UTF_8);
        List<String> models = new ArrayList<>();
        if (!model(store).isBlank()) models.add(model(store));
        for (String model : GeminiSearch.MODELS) if (!models.contains(model)) models.add(model);
        Exception last = null;
        for (String model : models) {
            HttpURLConnection connection = (HttpURLConnection) URI.create(GeminiSearch.url(model)).toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                // The key goes in a header, never the URL, so it cannot end up in an error message.
                connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("x-goog-api-key", key);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                int status = connection.getResponseCode();
                InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = input == null ? "" : OpenAiAccount.Http.read(input, 2 * 1024 * 1024);
                JSONObject json; try { json = new JSONObject(text); } catch (Exception e) { json = new JSONObject(); }
                if (status == 200) {
                    GeminiSearch.Result result = GeminiSearch.parse(json);
                    if (!model.equals(model(store))) store.settings.edit().putString("searchModel", model).apply();
                    return result;
                }
                JSONObject error = json.optJSONObject("error");
                String message = error == null ? "" : error.optString("message");
                IllegalStateException failure = new IllegalStateException(GeminiProtocol.explain(status + " " + message));
                if (!GeminiSearch.tryNext(status, message)) throw failure;
                last = failure;
            } finally { connection.disconnect(); }
        }
        throw last != null ? last : new IllegalStateException("검색할 모델을 찾지 못했어요.");
    }
}
