package dev.jun.gyeot.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A file the user downloads in the AI 브라우저 (a PDF, ZIP, HWP/HWPX, an Office file, a picture, a video, a sound …), kept in
 * the phone's 다운로드 as Chrome keeps it. It is read the way the Work browser reads its files (BrowserArtifactPolicy: the
 * name and type from the response, an installer or a program refused by its name, its type and its first bytes, the length
 * the server said checked), with no size cap of its own: the user started it and the phone's storage is the limit.
 */
public final class BrowserDownloadPolicy {
    private BrowserDownloadPolicy() {}
    /** The file's name and type; throws IOException("실행 파일은 저장하지 않아요.") for an installer or a program. */
    public static BrowserArtifactPolicy.Info info(String url, Map<String, String> headers) throws IOException {
        BrowserArtifactPolicy.Info base = BrowserArtifactPolicy.classify(url, headers);
        String name = base.name();
        // A response with no name (a blob: or data: address, a script's download): named for what it is.
        if (name.equals("결과") && !hasName(url, headers)) name = "다운로드";
        if (!name.contains(".")) { String ext = extension(base.mime()); if (!ext.isEmpty()) name = name + "." + ext; }
        return new BrowserArtifactPolicy.Info(name, base.mime(), base.kind(), Long.MAX_VALUE);
    }
    private static boolean hasName(String url, Map<String, String> headers) {
        if (!MusicDownload.dispositionName(MusicDownload.header(headers, "Content-Disposition")).isBlank()) return true;
        try { String path = java.net.URI.create(url).getPath(); return path != null && !path.substring(path.lastIndexOf('/') + 1).isBlank(); }
        catch (RuntimeException unreadable) { return false; }
    }
    /** Copies the body whole: the first bytes checked (no program, a PDF that is one), the length the server said, and stops when cancelled. */
    public static long copy(InputStream input, OutputStream output, BrowserArtifactPolicy.Info info, Map<String, String> headers, BooleanSupplier cancelled) throws IOException {
        return BrowserArtifactPolicy.copy(input, output, info, BrowserArtifactPolicy.expected(headers), cancelled);
    }
    /** Whether the type says nothing (the phone then reads it from the name's extension). */
    public static boolean generic(String mime) { return mime == null || mime.isBlank() || mime.equals("application/octet-stream") || mime.equals("binary/octet-stream"); }
    /** The usual extension of a type, for a file whose name has none; "" when unknown. */
    public static String extension(String mime) {
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "application/pdf" -> "pdf"; case "application/zip", "application/x-zip-compressed" -> "zip";
            case "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp" -> "hwp"; case "application/vnd.hancom.hwpx", "application/haansofthwpx" -> "hwpx";
            case "application/msword" -> "doc"; case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.ms-excel" -> "xls"; case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.ms-powerpoint" -> "ppt"; case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "image/png" -> "png"; case "image/jpeg" -> "jpg"; case "image/gif" -> "gif"; case "image/webp" -> "webp";
            case "video/mp4" -> "mp4"; case "video/webm" -> "webm"; case "audio/mpeg" -> "mp3"; case "audio/mp4", "audio/x-m4a" -> "m4a";
            case "audio/wav", "audio/x-wav" -> "wav"; case "audio/ogg" -> "ogg"; case "text/plain" -> "txt"; case "text/csv" -> "csv";
            case "application/json" -> "json"; case "text/html" -> "html";
            default -> "";
        };
    }
}
