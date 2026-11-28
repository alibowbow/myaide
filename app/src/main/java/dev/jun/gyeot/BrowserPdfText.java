package dev.jun.gyeot;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.ext.SdkExtensions;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.mozilla.geckoview.WebResponse;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The words of a PDF the AI 브라우저 shows in Gecko's PDF viewer, where the page bridge never runs: the viewer's own copy of the
 * document (PdfFileSaver) is read with the phone's PDF reader (PdfRenderer's text, Android 15 or the PDF module that brings
 * it), page by page, off the main thread. No library is added; on a phone without it the old answer stays (pdf_viewer).
 */
final class BrowserPdfText {
    private BrowserPdfText() {}
    /**
     * Every page is read; only the words kept in memory are bounded (about a thousand dense pages, a few MB), so a huge book
     * cannot fill the phone's memory. It is a memory guard, not a page limit: a scan of any length is read page by page.
     */
    static final int MAX_CHARS = 2_000_000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "browser-pdf-text"); t.setDaemon(true); return t; });

    /** Whether this phone's PDF reader gives text (PdfRenderer.Page.getTextContents). */
    static boolean available() {
        try { return Build.VERSION.SDK_INT >= 35 || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13; }
        catch (RuntimeException | LinkageError unknown) { return false; }
    }
    /** Reads the PDF the response holds; done hears {ok, text, pages, pages_read, truncated} or {ok:false, error_code, error}, off the main thread. */
    static void read(Context context, WebResponse response, Consumer<JSONObject> done) {
        File dir = new File(context.getCacheDir(), "browser-pdf");
        IO.execute(() -> {
            JSONObject out; File copy = null;
            try (InputStream body = response.body) {
                if (body == null) throw new IOException("PDF 본문을 받지 못했어요.");
                try { response.setReadTimeoutMillis(30_000); } catch (RuntimeException notGecko) { /* no timeout to set */ }
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("PDF를 잠시 둘 곳을 만들지 못했어요.");
                copy = File.createTempFile("page", ".pdf", dir);
                try (OutputStream file = new FileOutputStream(copy)) { body.transferTo(file); }
                out = text(copy);
            } catch (SecurityException locked) {
                out = obj("ok", false, "error_code", "pdf_locked", "error", "암호가 걸린 PDF라 글을 읽지 못했어요. PDF 화면에서 직접 열어 보거나 다운로드해서 다른 앱으로 열어 주세요.");
            } catch (Exception | OutOfMemoryError failed) {
                out = obj("ok", false, "error_code", "pdf_unreadable", "error", "PDF의 글을 읽지 못했어요(" + message(failed, "알 수 없는 오류") + "). 화면의 PDF는 그대로 볼 수 있어요.");
            } finally { if (copy != null) copy.delete(); }
            done.accept(out);
        });
    }
    /** The text of the file's pages, each after its "[N쪽]" line. */
    static JSONObject text(File file) throws IOException {
        if (!available()) return obj("ok", false, "error_code", "pdf_reader_missing", "error", "이 휴대폰에는 PDF의 글을 읽는 기능이 없어요(안드로이드 15부터).");
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer pdf = new PdfRenderer(fd)) {
            int pages = pdf.getPageCount(), read = 0; StringBuilder words = new StringBuilder(); boolean any = false;
            for (int i = 0; i < pages && words.length() < MAX_CHARS; i++, read++) {
                try (PdfRenderer.Page page = pdf.openPage(i)) {
                    if (words.length() > 0) words.append("\n\n");
                    words.append('[').append(i + 1).append("쪽]\n");
                    for (var content : page.getTextContents()) {
                        String said = content.getText();
                        if (said == null || said.isBlank()) continue;
                        any = true; words.append(said.strip()).append('\n');
                    }
                }
            }
            if (!any) return obj("ok", false, "error_code", "pdf_no_text", "pages", pages,
                "error", "이 PDF는 글자가 아니라 그림(스캔한 문서)이라 읽을 글이 없어요. PDF 화면은 그대로 볼 수 있어요.");
            boolean cut = read < pages || words.length() > MAX_CHARS;
            return obj("ok", true, "text", words.length() > MAX_CHARS ? words.substring(0, MAX_CHARS) : words.toString(), "pages", pages, "pages_read", read, "truncated", cut);
        }
    }
}
