package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Antigravity's per-model quota as agy's /usage panel shows it (준: "안티그래비티 모델별 구독한도가 안 떠"): a block per model
 * group, its name on one line, a bar ("███ ░░░ 40%"), then "40% remaining · Refreshes in 126h 49m" or "Quota available".
 * The app read only one-line rows ("name  window  80%  reset") and so read none. The panel's text comes through a terminal
 * (escape codes, box characters, a line drawn twice), so this reads it loosely and keeps each group once. Pure Java.
 */
public final class AgyUsage {
    private AgyUsage() {}
    /** One model group: its name, what is left (0–100, or -1 for "Quota available": no limit reached), and when it refreshes ("" unknown). */
    public record Row(String name, int left, String refresh) {
        /** As the settings page shows it: "Gemini 3.1 Pro (High) · 40% 남음 · 126시간 49분 뒤 초기화". */
        public String line() {
            String when = refresh.isEmpty() ? "" : " · " + korean(refresh) + " 뒤 초기화";
            return name + " · " + (left < 0 ? "한도 여유 있음" : left + "% 남음") + when;
        }
    }
    private static final Pattern ESCAPES = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]");
    private static final Pattern REMAINING = Pattern.compile("(?i)(\\d{1,3})\\s*%\\s*(?:remaining|left|남음)(?:\\s*[·•|,-]?\\s*(?:refreshes|resets)\\s+in\\s+([0-9dhms ]+))?");
    private static final Pattern AVAILABLE = Pattern.compile("(?i)quota\\s+available");
    private static final Pattern BAR = Pattern.compile("[█░▓▒■□]");
    private static final Pattern MODEL = Pattern.compile("^(?:Gemini|Claude|GPT|Gemma|o\\d|Grok|Llama|Qwen|DeepSeek|Mistral)[A-Za-z0-9 .\\-]*(?:\\([^)]{1,30}\\))?$|^[A-Z][A-Za-z0-9.\\-]+(?: [A-Za-z0-9.\\-]+){0,5} \\([A-Za-z ]{2,20}\\)$");
    /** The banner agy shows when a quota ran out: "Individual quota reached … Resets in 126h51m11s". */
    private static final Pattern REACHED = Pattern.compile("(?i)quota reached[^\\n]*?resets in\\s+([0-9dhms ]+)");
    /** A one-line row as the app read before: "Gemini 3 Pro  weekly  40%  14:30". */
    private static final Pattern TABLE = Pattern.compile("^\\s*(\\S.*?)(?:\\t+|\\s{2,})(\\S.*?)(?:\\t+|\\s{2,})(\\d{1,3}(?:\\.\\d+)?)%(?:(?:\\t+|\\s{2,})(\\S+))?\\s*$");

    /** The text with the terminal's escape codes and drawing characters gone, one line per line. */
    public static String plain(String text) {
        String s = ESCAPES.matcher(text == null ? "" : text).replaceAll("\n").replace('\r', '\n');
        s = s.replaceAll("[│┃┆┊╎║─━┄┈╌═└┘┌┐├┤┬┴┼╭╮╯╰⎿]", " ");
        // Runs of spaces stay: the one-line table tells its columns apart by them.
        return s.replace('\u00A0', ' ');
    }
    /** The groups in the panel, each once, in the order shown. */
    public static List<Row> rows(String text) {
        List<Row> out = new ArrayList<>(); java.util.Set<String> seen = new java.util.HashSet<>();
        String name = "";
        for (String raw : plain(text).split("\n")) {
            String line = raw.strip(); if (line.isEmpty()) continue;
            Matcher left = REMAINING.matcher(line);
            if (left.find()) { add(out, seen, name, Integer.parseInt(left.group(1)), left.group(2)); name = ""; continue; }
            if (AVAILABLE.matcher(line).find()) { add(out, seen, name, -1, null); name = ""; continue; }
            Matcher table = TABLE.matcher(line);
            if (!BAR.matcher(line).find() && table.matches() && !table.group(1).isBlank()) {
                add(out, seen, table.group(1).strip() + " · " + table.group(2).strip(), (int) Math.round(Double.parseDouble(table.group(3))), table.group(4)); continue;
            }
            if (BAR.matcher(line).find()) continue;
            String candidate = line.replaceAll("^[>•*\\-\\s]+", "").strip();
            if (candidate.length() <= 60 && MODEL.matcher(candidate).matches()) name = candidate;
        }
        return out;
    }
    private static void add(List<Row> out, java.util.Set<String> seen, String name, int left, String refresh) {
        if (name == null || name.isBlank() || !seen.add(name)) return;
        out.add(new Row(name, Math.max(-1, Math.min(100, left)), refresh == null ? "" : refresh.strip()));
    }
    /** The "quota reached" banner's wait, "" when there is none. */
    public static String reached(String text) { Matcher m = REACHED.matcher(plain(text).replace('\n', ' ')); return m.find() ? m.group(1).strip() : ""; }
    /** "126h 49m" → "126시간 49분"; "2d 3h" → "2일 3시간". */
    public static String korean(String wait) {
        String s = wait.strip();
        Matcher part = Pattern.compile("(\\d+)\\s*([dhms])").matcher(s); StringBuilder out = new StringBuilder(); boolean any = false;
        while (part.find()) {
            if ("s".equals(part.group(2))) continue;
            out.append(any ? " " : "").append(part.group(1)).append(switch (part.group(2)) { case "d" -> "일"; case "h" -> "시간"; default -> "분"; }); any = true;
        }
        return any ? out.toString() : s;
    }
}
