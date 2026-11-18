package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.VideoEdit;
import dev.jun.gyeot.core.WebArticle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the page at an address a message asks about (WebArticle.wanted) and hands its words to the request. Only public
 * web addresses are asked, never this phone or its network; redirects are followed one hop at a time and each is
 * checked again. The words are kept for the user to read (가져온 글 보기). A page that cannot be read gives a note that
 * says why. Never on the main thread.
 */
final class WebArticleClient {
    private WebArticleClient() {}
    static final String KIND = "web";
    private static final int PAGE_BYTES = 4 * 1024 * 1024, HOPS = 5;
    private static final Map<String, String> READ = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 4; }
    };

    /** What the request carries for the page at url: its words, or why there are none. */
    static String block(Context context, String url, AtomicReference<Runnable> cancel) {
        synchronized (READ) { String kept = READ.get(url); if (kept != null) return kept; }
        try {
            String address = url; byte[] body = null; String type = "";
            for (int hop = 0; hop <= HOPS && body == null; hop++) {
                URI target = VideoEdit.publicUrl(address);
                for (InetAddress resolved : InetAddress.getAllByName(target.getHost()))
                    if (resolved.isLoopbackAddress() || resolved.isSiteLocalAddress() || resolved.isLinkLocalAddress() || resolved.isAnyLocalAddress() || resolved.isMulticastAddress() || VideoEdit.privateAddress(resolved.getHostAddress()))
                        throw new IllegalStateException("휴대폰이나 내부 네트워크 주소는 읽지 않아요.");
                HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
                if (!dev.jun.gyeot.core.RequestAbort.register(cancel, connection::disconnect)) throw new java.util.concurrent.CancellationException();
                try {
                    connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
                    String agent = YouTubeTranscriptClient.agent(context);
                    if (agent != null && !agent.isBlank()) connection.setRequestProperty("User-Agent", agent);
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8"); connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6");
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.isBlank()) throw new IOException("이동할 주소 없이 넘겨졌어요 (HTTP " + status + ").");
                        address = target.resolve(location.strip()).toString(); continue;
                    }
                    if (status == 401 || status == 403) throw new IOException("이 페이지는 로그인이 필요하거나 앱의 접근을 막았어요 (HTTP " + status + ").");
                    if (status >= 400) throw new IOException("페이지를 받지 못했어요 (HTTP " + status + ").");
                    type = connection.getContentType() == null ? "" : connection.getContentType();
                    String kind = type.toLowerCase(java.util.Locale.ROOT);
                    if (!kind.isEmpty() && !kind.startsWith("text/") && !kind.contains("html") && !kind.contains("xml")) throw new IOException("웹 글이 아니라 파일이에요 (" + (type.contains(";") ? type.substring(0, type.indexOf(';')) : type) + ").");
                    try (InputStream in = connection.getInputStream()) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                        // A page longer than the limit is read up to it: an article is near the top.
                        while (bytes.size() < PAGE_BYTES && (count = in.read(buffer)) > 0) bytes.write(buffer, 0, count);
                        body = bytes.toByteArray();
                    }
                } finally { connection.disconnect(); }
            }
            if (body == null) throw new IOException("주소가 계속 바뀌어서 페이지를 읽지 못했어요.");
            String page = new String(body, WebArticle.charset(type, body));
            WebArticle.Article article = type.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain") ? new WebArticle.Article("", page.strip()) : WebArticle.read(page);
            if (article.text().length() < WebArticle.LEAST) return WebArticle.missing(url, "페이지에서 읽을 글을 거의 찾지 못했어요 (" + article.text().length() + "자). 화면을 그린 뒤에야 글이 나오는 페이지일 수 있어요.");
            KeptText.save(context, KIND, WebArticle.key(url), WebArticle.readable(url, article));
            String block = WebArticle.block(url, article, WebArticle.LIMIT);
            synchronized (READ) { READ.put(url, block); }
            return block;
        } catch (java.util.concurrent.CancellationException stopped) { throw stopped; }
        catch (Exception failed) {
            String why = failed instanceof java.net.UnknownHostException ? "주소를 찾지 못했어요." : failed.getMessage() == null || failed.getMessage().isBlank() ? "페이지에 연결하지 못했어요." : failed.getMessage();
            return WebArticle.missing(url, why);
        }
    }
}
