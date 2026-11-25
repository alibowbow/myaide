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
    /**
     * The limits as the usage bars show them, one per family and window ("Claude·GPT 5시간", "Gemini 주간"), each with its
     * reset as a time (준: "요렇게 표시되니 못알아보겠어" — the rows had shown as "Claude · 100% 남음 · 2026-12-02T00:13:50Z 뒤
     * 초기화 (and GPT models · Weekly Limit Remaining)"). From agy's table ("Claude and GPT models  Weekly Limit Remaining
     * 100%  2026-12-02T00:13:50Z") or its panel ("Gemini 3.1 Pro (High)", "40% remaining · Refreshes in 126h 49m"). Models of
     * one family with the same share and reset are one bar; one that differs keeps its own name.
     */
    public static List<Limits.Window> windows(List<Row> rows, long now) {
        record Found(int family, int rank, Limits.Window window) {}
        List<Found> found = new ArrayList<>(); java.util.Map<String, Limits.Window> byKey = new java.util.HashMap<>();
        for (Row row : rows) {
            String name = row.name(); int cut = name.indexOf(" · ");
            String group = cut < 0 ? name : name.substring(0, cut), label = cut < 0 ? "" : name.substring(cut + 3);
            String span = span(label + " " + group), who = families(group).isEmpty() ? families(label) : families(group);
            double left = row.left() < 0 ? 1 : row.left() / 100.0;
            if (USED.matcher(label + " " + group).find() && !LEFT_WORD.matcher(label + " " + group).find()) left = 1 - left;
            long reset = reset(row.refresh(), now);
            String plainGroup = group.replaceAll("(?i)\\s+models?$", "").strip();
            String title = (who.isEmpty() ? plainGroup : who) + (span.isEmpty() ? "" : " " + span);
            Limits.Window same = byKey.get(title);
            if (same != null) {
                if (Math.abs(same.left() - left) < 0.005 && Math.abs(same.reset() - reset) < 120_000) continue;
                title = plainGroup + (span.isEmpty() ? "" : " " + span);
                if (byKey.containsKey(title)) continue;
            }
            Limits.Window window = new Limits.Window(title, Math.max(0, Math.min(1, left)), reset, false);
            byKey.put(title, window);
            String first = who.isEmpty() ? "" : who.split("·")[0];
            int family = java.util.Arrays.asList(FAMILIES).indexOf(first);
            found.add(new Found(family < 0 ? FAMILIES.length : family, rank(span), window));
        }
        found.sort(java.util.Comparator.comparingInt(Found::family).thenComparingInt(Found::rank));
        List<Limits.Window> out = new ArrayList<>(); for (Found f : found) out.add(f.window()); return out;
    }
    private static final Pattern USED = Pattern.compile("(?i)\\bused\\b|사용");
    private static final Pattern LEFT_WORD = Pattern.compile("(?i)remaining|left|남음|남은");
    /** The window a row names: "5시간", "주간", "하루", "월간", "N시간"; "" when it names none. */
    static String span(String text) {
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.matches("(?s).*\\b(?:weekly|week|7[- ]?days?)\\b.*") || t.contains("주간")) return "주간";
        if (t.matches("(?s).*\\bmonth(?:ly)?\\b.*") || t.contains("월간")) return "월간";
        if (t.matches("(?s).*\\b(?:daily|day|24[- ]?h(?:our)?s?)\\b.*") || t.contains("하루")) return "하루";
        if (t.matches("(?s).*\\bfive[- ]?hours?\\b.*")) return "5시간";
        Matcher hours = Pattern.compile("\\b(\\d{1,2})[- ]?h(?:our)?s?\\b").matcher(t);
        if (hours.find()) return hours.group(1) + "시간";
        Matcher korean = Pattern.compile("(\\d{1,2})\\s*시간").matcher(t);
        return korean.find() ? korean.group(1) + "시간" : "";
    }
    private static int rank(String span) {
        if (span.endsWith("시간")) { try { return Integer.parseInt(span.substring(0, span.length() - 2)); } catch (NumberFormatException e) { return 5; } }
        return switch (span) { case "하루" -> 24; case "주간" -> 168; case "월간" -> 720; default -> 1000; };
    }
    /** The families a group names, in its order: "Claude and GPT models" → "Claude·GPT"; "" when it names none. */
    static String families(String group) {
        java.util.TreeMap<Integer, String> at = new java.util.TreeMap<>();
        Matcher m = Pattern.compile("(?i)\\b(claude|gpt|gemini|gemma|o\\d)").matcher(group);
        while (m.find()) { String f = family(m.group(1)); if (!at.containsValue(f) && !"기타".equals(f)) at.put(m.start(), f); }
        return String.join("·", at.values());
    }
    /** A reset as epoch milliseconds: a time ("2026-12-02T00:13:50Z") or a wait from now ("126h 49m"); 0 unknown. */
    static long reset(String refresh, long now) {
        if (refresh == null || refresh.isBlank()) return 0;
        long at = Limits.instant(refresh.strip()); if (at > 0) return at;
        Matcher part = Pattern.compile("(\\d+)\\s*([dhms])").matcher(refresh); long ms = 0; boolean any = false;
        while (part.find()) { any = true; long n = Long.parseLong(part.group(1)); ms += switch (part.group(2)) { case "d" -> n * 86_400_000L; case "h" -> n * 3_600_000L; case "m" -> n * 60_000L; default -> n * 1_000L; }; }
        return any ? now + ms : 0;
    }
    /** The model families agy's limits go by, in the order shown (준: "클로드/gpt/제미나이 모델별"). */
    static final String[] FAMILIES = {"Claude", "GPT", "Gemini"};
    static String family(String name) {
        String n = name.strip().toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("claude")) return "Claude";
        if (n.startsWith("gpt") || n.matches("^o\\d.*")) return "GPT";
        if (n.startsWith("gemini") || n.startsWith("gemma")) return "Gemini";
        return "기타";
    }
    /**
     * The rows as the settings and usage pages show them: one line per family and state, the models in it after it, as
     * "Gemini · 40% 남음 · 126시간 49분 뒤 초기화 (3.5 Flash, 3.1 Pro)". A family whose models differ gets a line per state.
     */
    public static List<String> lines(List<Row> rows) {
        java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>> byFamily = new java.util.LinkedHashMap<>();
        for (String f : FAMILIES) byFamily.put(f, new java.util.LinkedHashMap<>());
        byFamily.put("기타", new java.util.LinkedHashMap<>());
        for (Row row : rows) {
            String f = family(row.name());
            String state = (row.left() < 0 ? "한도 여유 있음" : row.left() + "% 남음") + (row.refresh().isEmpty() ? "" : " · " + korean(row.refresh()) + " 뒤 초기화");
            // The model without its family word and its reasoning level: "Gemini 3.1 Pro (High)" → "3.1 Pro".
            String model = row.name().replaceAll("\\s*\\([^)]*\\)\\s*$", "").strip();
            if (!"기타".equals(f) && model.toLowerCase(java.util.Locale.ROOT).startsWith(f.toLowerCase(java.util.Locale.ROOT) + " ")) model = model.substring(f.length()).strip();
            byFamily.get(f).computeIfAbsent(state, k -> new java.util.LinkedHashSet<>()).add(model);
        }
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>> f : byFamily.entrySet())
            for (java.util.Map.Entry<String, java.util.LinkedHashSet<String>> state : f.getValue().entrySet())
                out.add(f.getKey() + " · " + state.getKey() + " (" + String.join(", ", state.getValue()) + ")");
        return out;
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
