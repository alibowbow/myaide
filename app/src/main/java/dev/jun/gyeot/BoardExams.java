package dev.jun.gyeot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import dev.jun.gyeot.core.ExamPapers;
import dev.jun.gyeot.core.MainRequest;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.json.JSONObject;

/**
 * 판서's 수능 기출: the exam papers in 들퍼레/수능 (core/ExamPapers reads what their names say), where each question of a
 * paper is (found once by the board's model, from pictures of its pages, and kept in the app's files), and the picture
 * of one question cut from its page. Nothing is written to the folder but the folder itself, when asked for.
 */
final class BoardExams {
    private BoardExams() {}
    /** A PDF in the folder: its place inside 들퍼레/수능, its size, and when it last changed. */
    record Found(String path, long size, long modified) {}
    /** A paper as the sheet shows it: what is known about it, and the file. */
    record Entry(ExamPapers.Paper paper, Found file, boolean indexed) {}
    /** 들퍼레/수능 on the phone; tests put their own. */
    interface Shelf {
        boolean allowed();
        List<Found> list() throws IOException;
        ParcelFileDescriptor open(String path) throws IOException;
        void make() throws IOException;
    }
    static Shelf shelf;
    /** A paper's pages as pictures: the phone's PDF renderer, or a test's own. */
    interface Pages extends AutoCloseable { int count(); Bitmap render(int page, int width); @Override void close(); }
    interface Opener { Pages open(Shelf shelf, Found found) throws IOException; }
    static Opener opener;
    static Pages pages(Shelf shelf, Found found) throws IOException {
        if (opener != null) return opener.open(shelf, found);
        ParcelFileDescriptor descriptor = shelf.open(found.path()); PdfRenderer pdf;
        try { pdf = new PdfRenderer(descriptor); } catch (IOException | RuntimeException unreadable) { descriptor.close(); throw new IOException("PDF를 열지 못했어요.", unreadable); }
        return new Pages() {
            @Override public int count() { return pdf.getPageCount(); }
            @Override public Bitmap render(int page, int width) { return BoardExams.render(pdf, page, width); }
            @Override public void close() { pdf.close(); }
        };
    }
    /** How many requests for an index go out at once; tests make it one. */
    static int parallel = 3;
    static final int INDEX_PIXELS = 1100, CUT_PIXELS = 1600, DEPTH = 3, FILES = 400;

    static Shelf shelf(Context context) { return shelf != null ? shelf : new Managed(context.getApplicationContext()); }

    /** The folder through ManagedSharedStorage: only 들퍼레/수능 and below, regular files, never a link. */
    static final class Managed implements Shelf {
        private final Context context;
        Managed(Context context) { this.context = context; }
        private static final String ROOT = ManagedSharedStorage.ROOT_NAME + "/" + ExamPapers.FOLDER;
        @Override public boolean allowed() { try { return Environment.isExternalStorageManager(); } catch (RuntimeException unknown) { return false; } }
        @Override public List<Found> list() throws IOException {
            List<Found> out = new ArrayList<>();
            try (ManagedSharedStorage.Access access = ManagedSharedStorage.access(context)) {
                ManagedSharedStorage.Node root;
                try { root = access.directory(ROOT, false); } catch (IOException missing) { if (ManagedSharedStorage.missing(missing)) return out; throw missing; }
                root.close();
                walk(access, "", 0, out);
            }
            return out;
        }
        private void walk(ManagedSharedStorage.Access access, String relative, int depth, List<Found> out) throws IOException {
            List<String> folders = new ArrayList<>();
            try (ManagedSharedStorage.Node folder = access.directory(ROOT + (relative.isEmpty() ? "" : "/" + relative), false); var children = folder.children()) {
                for (java.nio.file.Path child : children) {
                    if (out.size() >= FILES) return;
                    String name = child.getFileName().toString(); if (name.startsWith(".")) continue;
                    StructStat stat; try { stat = Os.lstat(child.toString()); } catch (ErrnoException gone) { continue; }
                    String path = relative.isEmpty() ? name : relative + "/" + name;
                    if (OsConstants.S_ISDIR(stat.st_mode)) { if (depth < DEPTH) folders.add(path); continue; }
                    if (OsConstants.S_ISREG(stat.st_mode) && name.toLowerCase(Locale.ROOT).endsWith(".pdf")) out.add(new Found(path, stat.st_size, stat.st_mtime * 1000L));
                }
            }
            for (String folder : folders) walk(access, folder, depth + 1, out);
        }
        @Override public ParcelFileDescriptor open(String path) throws IOException {
            ManagedSharedStorage.safe(path);
            try (ManagedSharedStorage.Access access = ManagedSharedStorage.access(context); ManagedSharedStorage.Node file = access.node(ROOT + "/" + path, false)) {
                file.requireFile();
                return ParcelFileDescriptor.dup(file.last().getFileDescriptor());
            }
        }
        @Override public void make() throws IOException { try (ManagedSharedStorage.Access access = ManagedSharedStorage.access(context); ManagedSharedStorage.Node made = access.directory(ROOT, true)) { made.check(); } }
    }

    // ── What is in the folder ───────────────────────────────────────────────────────────────────────

    private static File indexes(Context context) { File dir = new File(context.getFilesDir(), "exams"); dir.mkdirs(); return dir; }
    /** Where a paper's index is kept: by its place, size and time, so a changed file is read again. */
    static File indexFile(Context context, Found found) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest((found.path() + "|" + found.size() + "|" + found.modified()).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (int i = 0; i < 12; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return new File(indexes(context), hex + ".json");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static ExamPapers.Index cached(Context context, Found found) {
        File file = indexFile(context, found); if (!file.isFile()) return null;
        try { return ExamPapers.index(new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))); } catch (Exception unreadable) { return null; }
    }
    /** The papers in the folder, what their names say laid under what their first pages said when they were read. */
    static List<Entry> entries(Context context, Shelf shelf) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Found found : shelf.list()) {
            ExamPapers.Index index = cached(context, found);
            out.add(new Entry(ExamPapers.known(ExamPapers.parse(found.path()), index), found, index != null));
        }
        return out;
    }

    // ── Where the questions are ─────────────────────────────────────────────────────────────────────

    /**
     * Finds where every question of a paper is: its pages, a few at a time, shown to the board's model, which says where
     * each question's number is. Kept for the next time. progress hears pages read and pages in all.
     */
    static ExamPapers.Index index(Context context, Shelf shelf, Found found, BoardTeacher.Brain brain, AtomicReference<Runnable> cancel, BiConsumer<Integer, Integer> progress) throws Exception {
        ExamPapers.Index kept = cached(context, found); if (kept != null) return kept;
        List<List<MainRequest.Attachment>> batches = new ArrayList<>(); int pages;
        try (Pages pdf = pages(shelf, found)) {
            pages = Math.min(ExamPapers.PAGES, pdf.count());
            for (int first = 0; first < pages; first += ExamPapers.BATCH) {
                List<MainRequest.Attachment> batch = new ArrayList<>();
                for (int i = first; i < Math.min(pages, first + ExamPapers.BATCH); i++) {
                    Bitmap page = pdf.render(i, INDEX_PIXELS);
                    try { ByteArrayOutputStream jpeg = new ByteArrayOutputStream(); page.compress(Bitmap.CompressFormat.JPEG, 80, jpeg); batch.add(new MainRequest.Attachment("쪽 " + (i + 1) + ".jpg", "image/jpeg", Base64.getEncoder().encodeToString(jpeg.toByteArray()))); }
                    finally { page.recycle(); }
                }
                batches.add(batch);
            }
        }
        if (pages == 0) throw new IOException("빈 PDF예요.");
        List<ExamPapers.Spot> spots = java.util.Collections.synchronizedList(new ArrayList<>()); List<ExamPapers.Passage> passages = java.util.Collections.synchronizedList(new ArrayList<>());
        JSONObject[] paper = {null}; int total = pages; java.util.concurrent.atomic.AtomicInteger read = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.IntFunction<Void> ask = b -> {
            int first = b * ExamPapers.BATCH, count = batches.get(b).size();
            String reply;
            try { reply = brain.ask(ExamPapers.INSTRUCTIONS, ExamPapers.request(first, count, total), batches.get(b), cancel); }
            catch (RuntimeException failed) { throw failed; } catch (Exception failed) { throw new IllegalStateException(failed.getMessage(), failed); }
            JSONObject said = ExamPapers.read(reply, first, count, spots, passages);
            if (b == 0) paper[0] = said;
            progress.accept(read.addAndGet(count), total);
            return null;
        };
        if (parallel <= 1) for (int b = 0; b < batches.size(); b++) ask.apply(b);
        else {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallel, batches.size()));
            try {
                List<Future<Void>> running = new ArrayList<>(); for (int b = 0; b < batches.size(); b++) { int batch = b; running.add(pool.submit(() -> ask.apply(batch))); }
                for (Future<Void> one : running) {
                    try { one.get(); } catch (java.util.concurrent.ExecutionException failed) { Throwable cause = failed.getCause(); throw cause instanceof Exception e ? e : new IllegalStateException(cause); }
                }
            } finally { pool.shutdownNow(); }
        }
        List<ExamPapers.Spot> sorted = new ArrayList<>(spots);
        sorted.sort(java.util.Comparator.comparingInt(ExamPapers.Spot::page).thenComparing(s -> s.column() == 'R').thenComparingDouble(ExamPapers.Spot::top));
        if (sorted.isEmpty()) throw new IOException("문제 번호를 찾지 못했어요. 문제지 PDF가 맞는지 확인해 주세요.");
        JSONObject said = paper[0] == null ? new JSONObject() : paper[0];
        ExamPapers.Index index = new ExamPapers.Index(pages, sorted, new ArrayList<>(passages), said.optInt("year"), said.optInt("year") > 0, said.optString("exam"), said.optString("area"), said.optString("subject"));
        java.nio.file.Files.write(indexFile(context, found).toPath(), index.json().toString().getBytes(StandardCharsets.UTF_8));
        return index;
    }

    // ── The picture of a question ───────────────────────────────────────────────────────────────────

    static Bitmap render(PdfRenderer pdf, int number, int width) {
        try (PdfRenderer.Page page = pdf.openPage(number)) {
            int height = Math.max(1, Math.round(width * page.getHeight() / (float) Math.max(1, page.getWidth())));
            if (height > 4000) { width = Math.max(1, Math.round(width * 4000f / height)); height = 4000; }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
    }
    /** The pictures of the pieces of a paper's pages that show a question, each cut where no line is printed. */
    static List<Bitmap> pictures(Shelf shelf, Found found, List<ExamPapers.Cut> cuts) throws IOException {
        List<Bitmap> out = new ArrayList<>();
        try (Pages pdf = pages(shelf, found)) {
            for (ExamPapers.Cut cut : cuts) {
                if (cut.page() >= pdf.count()) continue;
                Bitmap page = pdf.render(cut.page(), CUT_PIXELS);
                try { out.add(cut(page, cut)); } finally { page.recycle(); }
            }
        }
        return out;
    }
    /** One column of a page between two heights, its edges moved up to rows with nothing printed. */
    static Bitmap cut(Bitmap page, ExamPapers.Cut cut) {
        int width = page.getWidth(), height = page.getHeight(), half = width / 2, gutter = Math.max(2, width / 300);
        int left = cut.column() == 'R' ? half + gutter : 0, right = cut.column() == 'L' ? half - gutter : width;
        if (cut.column() == 'F') return Bitmap.createBitmap(page, 0, Math.round(cut.top() * height), width, Math.max(1, Math.round((cut.bottom() - cut.top()) * height)));
        // How much is printed on each row, inside the column (not its edges, where a dividing line may run).
        int inset = Math.max(4, (right - left) / 25), from = left + inset, to = right - inset; int[] ink = new int[height], row = new int[width];
        for (int y = 0; y < height; y++) {
            page.getPixels(row, 0, width, 0, y, width, 1); int dark = 0;
            for (int x = from; x < to; x += 2) { int p = row[x]; if (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255) < 3 * 170) dark++; }
            ink[y] = dark >= 2 ? dark : 0;
        }
        // Edges sit in blank bands between lines, not in the hairline gaps inside a raised exponent or a fraction.
        int gap = Math.max(6, height / 160);
        int top = ExamPapers.topEdge(ink, Math.round(cut.top() * height + height * .012f), Math.round(height * .05f), gap);
        int bottom = ExamPapers.bottomEdge(ink, Math.round(cut.bottom() * height) - 2, Math.round(height * .04f), gap);
        if (bottom - top < height / 30) { top = Math.round(cut.top() * height); bottom = Math.round(cut.bottom() * height); }
        bottom = Math.max(top + 1, Math.min(height, bottom)); top = Math.max(0, Math.min(top, height - 1));
        return Bitmap.createBitmap(page, left, top, right - left, bottom - top);
    }
}
