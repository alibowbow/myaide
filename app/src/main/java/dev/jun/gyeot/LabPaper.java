package dev.jun.gyeot;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import dev.jun.gyeot.core.Markdown;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 연구실's 논문 as one HTML page for 페이지 (and its 공유): the Markdown laid out as a paper, each ![그림 n. …](e2-plot.png) the
 * experiment's own figure from the project's files, made small (JPEG, 1000 px wide) and kept inside the page, so the page
 * stands alone. A page over PageBook's size drops figures from the last, each leaving its caption. Off the main thread.
 */
final class LabPaper {
    private LabPaper() {}
    private static final Pattern FIGURE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");
    private static final int MOST = 950_000;

    static String html(String markdown, File files) {
        List<String[]> figures = new ArrayList<>(); // {caption, file name}
        Matcher m = FIGURE.matcher(markdown == null ? "" : markdown); StringBuilder marked = new StringBuilder();
        while (m.find()) { figures.add(new String[]{m.group(1).strip(), m.group(2).strip()}); m.appendReplacement(marked, "\n\nGYEOTFIGURE" + (figures.size() - 1) + "X\n\n"); }
        m.appendTail(marked);
        String body = Markdown.html(marked.toString(), true);
        String title = title(markdown);
        List<String> pictures = new ArrayList<>();
        for (String[] f : figures) pictures.add(picture(files, f[1]));
        String page;
        // Too big for 페이지: the last figures give way to their captions.
        int keep = pictures.size();
        do {
            String filled = body;
            for (int i = 0; i < figures.size(); i++) {
                String caption = esc(figures.get(i)[0]), data = i < keep ? pictures.get(i) : null;
                String figure = "<figure>" + (data == null ? "<div class=\"missing\">그림 · " + esc(figures.get(i)[1]) + (i < keep ? " (파일을 찾지 못했어요)" : " (페이지 크기 때문에 뺐어요)") + "</div>"
                    : "<img alt=\"" + caption + "\" src=\"data:image/jpeg;base64," + data + "\">") + (caption.isEmpty() ? "" : "<figcaption>" + caption + "</figcaption>") + "</figure>";
                filled = filled.replace("<p>GYEOTFIGURE" + i + "X</p>", figure).replace("GYEOTFIGURE" + i + "X", figure);
            }
            page = document(title, filled);
            keep--;
        } while (page.length() > MOST && keep >= 0);
        return page;
    }
    static String title(String markdown) {
        for (String line : (markdown == null ? "" : markdown).split("\n")) if (line.startsWith("# ")) return line.substring(2).strip();
        return "연구 논문";
    }
    /** The figure as a small JPEG in base64, or null when its file is not among the project's. */
    private static String picture(File files, String name) {
        String plain = name.replaceAll(".*/", "");
        if (!plain.matches("[A-Za-z0-9._-]+")) return null;
        File f = new File(files, plain);
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), bounds);
            int sample = 1; while (bounds.outWidth / (sample * 2) >= 1000) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts); if (b == null) return null;
            if (b.getWidth() > 1000) b = Bitmap.createScaledBitmap(b, 1000, Math.round(b.getHeight() * 1000f / b.getWidth()), true);
            // White under a transparent plot, as it shows on paper.
            Bitmap flat = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(flat); c.drawColor(0xffffffff); c.drawBitmap(b, 0, 0, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); flat.compress(Bitmap.CompressFormat.JPEG, 82, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (RuntimeException | OutOfMemoryError unreadable) { return null; }
    }
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String document(String title, String body) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + esc(title) + "</title><style>"
            + ":root{--bg:#fbfaf7;--text:#1d1c1a;--muted:#6f6b63;--line:rgba(0,0,0,.12);--accent:#b5532d}"
            + "@media (prefers-color-scheme:dark){:root{--bg:#141413;--text:#ecebe6;--muted:#9b978e;--line:rgba(255,255,255,.14);--accent:#e0896a}}"
            + "body{margin:0;background:var(--bg);color:var(--text);font:16px/1.7 'Noto Serif KR','Noto Serif',Georgia,serif}"
            + "article{max-width:760px;margin:0 auto;padding:28px 18px 48px}"
            + "h1{font-size:1.55em;line-height:1.3;margin:.2em 0 .6em}h2{font-size:1.15em;margin:1.8em 0 .5em;border-bottom:1px solid var(--line);padding-bottom:.25em}h3{font-size:1.02em;margin:1.3em 0 .4em}"
            + "p{margin:.6em 0}ul,ol{padding-left:1.3em}a{color:var(--accent);word-break:break-all}"
            + "table{border-collapse:collapse;width:100%;margin:1em 0;font:14px/1.45 system-ui,sans-serif;display:block;overflow-x:auto}th,td{border:1px solid var(--line);padding:6px 9px;text-align:left}th{background:rgba(127,127,127,.08)}"
            + "figure{margin:1.4em 0;text-align:center}figure img{max-width:100%;height:auto;border-radius:6px;border:1px solid var(--line)}figcaption{font:13.5px/1.5 system-ui,sans-serif;color:var(--muted);margin-top:.5em}"
            + ".missing{font:13px system-ui,sans-serif;color:var(--muted);border:1px dashed var(--line);padding:18px;border-radius:6px}"
            + "pre,code{font:13px/1.5 ui-monospace,monospace}pre{background:rgba(127,127,127,.08);padding:10px;border-radius:6px;overflow-x:auto}em{color:var(--muted)}"
            + "</style></head><body><article>" + body + "</article></body></html>";
    }
}
