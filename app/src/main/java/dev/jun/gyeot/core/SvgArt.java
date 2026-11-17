package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SVG a model drew, checked before the phone renders it: a static vector picture and nothing else. No DOCTYPE or
 * entities (no entity expansion), no scripts, event handlers or foreignObject, and no reference to anything outside the
 * file (only #fragment links; no external images, stylesheets or fonts). Size and element count are bounded.
 */
public final class SvgArt {
    public static final int MAX_CHARS = 2 * 1024 * 1024, MAX_ELEMENTS = 40_000;
    private static final Pattern ROOT = Pattern.compile("(?s)\\A(?:\\s|<\\?xml[^>]*\\?>|<!--.*?-->)*<svg[\\s>]");
    private static final Pattern FORBIDDEN = Pattern.compile("(?is)<!doctype|<!entity|<script|<foreignobject|<iframe|<object|<embed|<audio|<video|<animate|<set[\\s>/]|javascript:|vbscript:|@import|\\son[a-z]+\\s*=");
    private static final Pattern HREF = Pattern.compile("(?is)(?:xlink:)?href\\s*=\\s*(\"[^\"]*\"|'[^']*')");
    private static final Pattern URL = Pattern.compile("(?is)url\\(\\s*(['\"]?)([^)'\"]*)\\1\\s*\\)");
    private static final Pattern DIMENSION = Pattern.compile("(?is)<svg\\b[^>]*?\\b(viewBox|width|height)\\s*=");
    private SvgArt() {}

    /** The picture's SVG text, tidied (BOM and surrounding space removed), or IllegalArgumentException with a reason the user can read. */
    public static String clean(String raw) {
        String text = raw == null ? "" : raw.replace("\uFEFF", "").strip();
        if (text.isEmpty()) throw new IllegalArgumentException("SVG가 비어 있어요.");
        if (text.length() > MAX_CHARS) throw new IllegalArgumentException("SVG가 너무 커요(2MB까지).");
        if (!ROOT.matcher(text).find()) throw new IllegalArgumentException("SVG 그림이 아니에요.");
        if (!text.toLowerCase(Locale.ROOT).endsWith("</svg>")) throw new IllegalArgumentException("SVG가 중간에 끊겼어요.");
        Matcher bad = FORBIDDEN.matcher(text);
        if (bad.find()) throw new IllegalArgumentException("SVG에 움직이는 코드나 외부 내용(" + bad.group().strip() + ")이 있어 쓰지 않았어요.");
        Matcher href = HREF.matcher(text);
        while (href.find()) {
            String value = href.group(1).substring(1, href.group(1).length() - 1).strip();
            if (!value.startsWith("#")) throw new IllegalArgumentException("SVG가 바깥 파일을 불러오려고 해서 쓰지 않았어요.");
        }
        Matcher url = URL.matcher(text);
        while (url.find()) if (!url.group(2).strip().startsWith("#")) throw new IllegalArgumentException("SVG가 바깥 파일을 불러오려고 해서 쓰지 않았어요.");
        int elements = 0;
        for (int i = text.indexOf('<'); i >= 0; i = text.indexOf('<', i + 1)) if (++elements > MAX_ELEMENTS) throw new IllegalArgumentException("SVG 도형이 너무 많아요.");
        if (!DIMENSION.matcher(text).find()) throw new IllegalArgumentException("SVG에 크기(viewBox) 정보가 없어요.");
        return text;
    }

    /** The SVG text out of a model's answer: the first <svg …</svg> block, even inside prose or a code fence. */
    public static String extract(String answer) {
        String text = answer == null ? "" : answer;
        int start = text.toLowerCase(Locale.ROOT).indexOf("<svg"), end = text.toLowerCase(Locale.ROOT).lastIndexOf("</svg>");
        if (start < 0 || end < start) throw new IllegalArgumentException("SVG 그림을 찾지 못했어요.");
        return clean(text.substring(start, end + 6));
    }
}
