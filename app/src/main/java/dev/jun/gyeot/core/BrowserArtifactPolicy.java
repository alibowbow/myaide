package dev.jun.gyeot.core;

import java.io.*;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Untrusted download metadata and bounded original-body transfer. No networking or execution. */
public final class BrowserArtifactPolicy {
    /** The largest file a browser job keeps, whatever its kind; executables and installers are never kept. */
    public static final long LIMIT = 4L * 1024 * 1024 * 1024;
    public record Info(String name, String mime, String kind, long limit) {}
    private BrowserArtifactPolicy() {}
    /** Only explicitly saved Work result metadata authorizes an artifact URI; paths never come from callers. */
    public static org.json.JSONObject stored(org.json.JSONArray rooms, String id) {
        if (id == null || !id.matches("b[0-9a-f]{32}")) return null;
        for (int r = 0; rooms != null && r < rooms.length(); r++) {
            org.json.JSONObject room = rooms.optJSONObject(r);
            org.json.JSONArray messages = room == null ? null : room.optJSONArray("messages");
            for (int m = 0; messages != null && m < messages.length(); m++) {
                org.json.JSONObject message = messages.optJSONObject(m);
                org.json.JSONArray files = message == null ? null : message.optJSONArray("files");
                for (int f = 0; files != null && f < files.length(); f++) {
                    org.json.JSONObject file = files.optJSONObject(f);
                    if (file != null && id.equals(file.optString("id")) && file.optJSONObject("provenance") != null && WorkBook.fileName(file.optString("name")) != null) return Jsons.copy(file);
                }
            }
        }
        return null;
    }
    public static Info classify(String url, Map<String,String> headers) throws IOException {
        String name = MusicDownload.dispositionName(MusicDownload.header(headers, "Content-Disposition"));
        if (name.isBlank()) try { String path = URI.create(url).getPath(); name = path.substring(path.lastIndexOf('/') + 1); } catch (Exception ignored) { }
        name = WorkBook.fileName(name.replaceAll("[\\p{Cf}]", "_"));
        if (name == null) throw new IOException("실행 파일은 저장하지 않아요.");
        String mime = MusicDownload.header(headers, "Content-Type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!mime.matches("[a-z0-9.+-]{1,60}/[a-z0-9.+-]{1,80}")) mime = "application/octet-stream";
        if (mime.matches(".*(executable|x-msdownload|android.package|java-archive|x-sh|x-shellscript).*")) throw new IOException("실행 파일은 저장하지 않아요.");
        // Do not infer a supported model format from an untrusted extension against an explicit MIME.
        String kind = mime.equals("application/octet-stream") ? "" : WorkBook.kind(mime, "");
        return new Info(name, mime, kind, LIMIT);
    }
    public static long expected(Map<String,String> headers) throws IOException {
        String encoding = MusicDownload.header(headers, "Content-Encoding");
        if (!encoding.isBlank() && !encoding.equalsIgnoreCase("identity")) return -1;
        String length = MusicDownload.header(headers, "Content-Length");
        if (length.isBlank()) return -1;
        try { long value = Long.parseLong(length); if (value < 0) throw new NumberFormatException(); return value; }
        catch (NumberFormatException e) { throw new IOException("다운로드 크기 정보가 올바르지 않아요."); }
    }
    public static long copy(InputStream input, OutputStream output, Info info, long expected, BooleanSupplier cancelled) throws IOException {
        if (input == null) throw new IOException("다운로드 본문이 없어요.");
        if (expected > info.limit()) throw new IOException(tooLarge(info));
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("다운로드를 취소했어요.");
        byte[] prefix = input.readNBytes(8);
        if (prefix.length > info.limit()) throw new IOException(tooLarge(info));
        validatePrefix(prefix, prefix.length, info);
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("다운로드를 취소했어요.");
        output.write(prefix);
        byte[] buffer = new byte[32768]; long count = prefix.length; int size;
        while (true) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("다운로드를 취소했어요.");
            size = input.read(buffer); if (size == -1) break;
            if (size == 0) continue;
            count += size;
            if (count > info.limit()) throw new IOException(tooLarge(info));
            output.write(buffer, 0, size);
        }
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("다운로드를 취소했어요.");
        if (count == 0 || (expected >= 0 && expected != count)) throw new IOException("다운로드가 완성되지 않았어요.");
        return count;
    }
    private static String tooLarge(Info info) { return "파일이 " + (info.limit() >= 1L << 30 ? (info.limit() >> 30) + "GB" : (info.limit() >> 20) + "MB") + "를 넘어 저장하지 않았어요."; }
    private static void validatePrefix(byte[] bytes, int size, Info info) throws IOException {
        if (size >= 2 && bytes[0] == 'M' && bytes[1] == 'Z' || size >= 4 && bytes[0] == 127 && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F')
            throw new IOException("실행 파일은 저장하지 않아요.");
        if (info.kind().equals(WorkBook.PDF) && !(size >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-'))
            throw new IOException("PDF 본문으로 확인되지 않았어요.");
    }
}
