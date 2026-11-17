package dev.jun.gyeot;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.MainSearch;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;

/**
 * Google's search suggestions for an answer Gemini grounded with Google Search (searchEntryPoint.renderedContent), in the
 * main chat and in 작업창, as the Gemini API terms ask: the HTML and CSS exactly as Google sent them (its own light and
 * dark styles), the full width of the answer, shown whenever the answer is. A tapped suggestion goes straight to its
 * Google Search results page in the app's browser. The snippet is inline HTML, CSS and SVG, so it runs with JavaScript,
 * storage, file and content access and every network load off, and nothing but a tapped http or https link leaves it.
 * A phone without a working WebView (being updated, disabled) gets the suggestions as plain chips instead.
 */
final class SearchSuggestions {
    private SearchSuggestions() {}
    static final String LABEL = "Google 검색 추천";
    /** Makes the WebView; a test stands in a phone whose WebView is missing or being updated. */
    static Function<Context, WebView> webViews = WebView::new;

    /** The snippet under an answer: Google's own, or plain chips when no WebView can be made; null when there is nothing to show. */
    static View view(Context context, Ui ui, String html, Consumer<String> open) {
        WebView web = null;
        try {
            web = webViews.apply(context);
            sandbox(web, open);
            web.setBackgroundColor(0); web.setVerticalScrollBarEnabled(false); web.setContentDescription(LABEL);
            web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            return web;
        } catch (RuntimeException unavailable) {
            if (web != null) try { web.destroy(); } catch (RuntimeException ignored) {}
            return chips(context, ui, html, open);
        }
    }
    /**
     * A WebView that shows given HTML and nothing more: JavaScript, storage, file and content access, location and popups
     * off, every network load blocked and every subresource answered with a 403. Only a tapped http or https link leaves,
     * to open. Also 작업창's grounding views and its 크게 보기 document.
     */
    static void sandbox(WebView web, Consumer<String> open) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(false); s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setDomStorageEnabled(false);
        s.setGeolocationEnabled(false); s.setBlockNetworkLoads(true); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false); s.setSupportZoom(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // The page itself is the given HTML (its own load passes; network loads are blocked, and every navigation is
                // cancelled below); everything it would load beside it is refused.
                if (request.isForMainFrame()) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", Map.of(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (request.hasGesture() && (url.startsWith("https://") || url.startsWith("http://"))) open.accept(url);
                return true;
            }
        });
    }
    /**
     * The suggestions as a row of plain chips (MainSearch.queries), each opening its Google Search results page the same
     * way; null when the snippet holds no Google Search link.
     */
    static View chips(Context context, Ui ui, String html, Consumer<String> open) {
        JSONArray queries = MainSearch.queries(html);
        if (queries.length() == 0) return null;
        HorizontalScrollView strip = new HorizontalScrollView(context); strip.setHorizontalScrollBarEnabled(false); strip.setContentDescription(LABEL);
        LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = ui.text(LABEL, FOOTNOTE, 600, MUTED); label.setPadding(ui.dp(2), 0, ui.dp(8), 0); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(label);
        for (int i = 0; i < queries.length(); i++) {
            JSONObject query = queries.optJSONObject(i); String words = query.optString("query"), url = query.optString("url");
            TextView chip = ui.text(words, 13, 600, TEXT); chip.setSingleLine(true); chip.setEllipsize(TextUtils.TruncateAt.END); chip.setMaxWidth(ui.dp(220));
            chip.setGravity(Gravity.CENTER_VERTICAL); chip.setMinHeight(ui.dp(44)); chip.setPadding(ui.dp(14), 0, ui.dp(14), 0); chip.setBackground(ui.shape(FILL, PILL));
            chip.setContentDescription(LABEL + " · " + words + " · Google 검색 결과 열기"); chip.setOnClickListener(v -> open.accept(url)); Ui.pressable(chip, true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(8); row.addView(chip, p);
        }
        strip.addView(row);
        return strip;
    }
}
