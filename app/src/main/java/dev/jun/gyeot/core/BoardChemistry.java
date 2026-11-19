package dev.jun.gyeot.core;

import static dev.jun.gyeot.core.Jsons.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Element counts for school-level neutral formulae and equations; phases, charges and hydrates
 * require separate notation.
 */
final class BoardChemistry {
    private BoardChemistry() {}

    private static final Set<String> ELEMENTS =
            Set.of(
                    ("H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu"
                         + " Zn Ga Ge As Se Br Kr Rb Sr Y Zr Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I"
                         + " Xe Cs Ba La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm Yb Lu Hf Ta W Re Os Ir"
                         + " Pt Au Hg Tl Pb Bi Po At Rn Fr Ra Ac Th Pa U Np Pu Am Cm Bk Cf Es Fm Md"
                         + " No Lr Rf Db Sg Bh Hs Mt Ds Rg Cn Nh Fl Mc Lv Ts Og")
                            .split(" "));
    private static final String SUB = "₀₁₂₃₄₅₆₇₈₉";

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("중성 화학식과 정수 계수를 입력하세요. 원소 기호·아래첨자·괄호를 쓸 수 있어요.");
    }

    private static void add(Map<String, Long> all, Map<String, Long> group, long n) {
        for (var entry : group.entrySet()) {
            if (entry.getValue() > 1_000_000_000L / n) throw invalid();
            long v = all.getOrDefault(entry.getKey(), 0L) + entry.getValue() * n;
            if (v > 1_000_000_000L) throw invalid();
            all.put(entry.getKey(), v);
        }
    }

    private static final class Formula {
        final String text;
        int pos;

        Formula(String text) {
            this.text = text;
        }

        int count() {
            int start = pos;
            while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') pos++;
            if (start == pos) return 1;
            if (pos - start > 4 || text.charAt(start) == '0') throw invalid();
            return Integer.parseInt(text.substring(start, pos));
        }

        Map<String, Long> group(int depth) {
            if (depth > 6) throw invalid();
            Map<String, Long> out = new LinkedHashMap<>();
            while (pos < text.length() && text.charAt(pos) != ')') {
                Map<String, Long> part;
                if (text.charAt(pos) == '(') {
                    pos++;
                    part = group(depth + 1);
                    if (pos >= text.length() || text.charAt(pos++) != ')') throw invalid();
                } else {
                    int start = pos;
                    char c = text.charAt(pos++);
                    if (c < 'A' || c > 'Z') throw invalid();
                    if (pos < text.length() && text.charAt(pos) >= 'a' && text.charAt(pos) <= 'z')
                        pos++;
                    String symbol = text.substring(start, pos);
                    if (!ELEMENTS.contains(symbol)) throw invalid();
                    part = Map.of(symbol, 1L);
                }
                add(out, part, count());
            }
            if (out.isEmpty()) throw invalid();
            return out;
        }
    }

    private static String side(String text, Map<String, Long> atoms) {
        List<String> terms = new ArrayList<>();
        for (String term : text.split("\\+", -1)) {
            Formula f = new Formula(term.strip());
            int coefficient = f.count(), start = f.pos;
            add(atoms, f.group(0), coefficient);
            if (f.pos != f.text.length()) throw invalid();
            StringBuilder print = new StringBuilder(f.text.substring(0, start));
            for (char c : f.text.substring(start).toCharArray())
                print.append(c >= '0' && c <= '9' ? SUB.charAt(c - '0') : c);
            terms.add(print.toString());
        }
        return String.join(" + ", terms);
    }

    static BoardPlot.Made draw(JSONObject spec, float scale, Board.Measure measure) {
        String source = spec.optString("formula").strip().replace("->", "→").replace("=", "→");
        if (source.length() > 240) throw invalid();
        for (int n = 0; n < 10; n++) source = source.replace(SUB.charAt(n), (char) ('0' + n));
        String[] sides = source.split("→", -1);
        if (sides.length > 2) throw invalid();
        Map<String, Long> left = new LinkedHashMap<>(), right = new LinkedHashMap<>();
        String title = side(sides[0], left);
        boolean equation = sides.length == 2;
        if (equation) title += "  →  " + side(sides[1], right);
        float size = Math.max(25, Board.SMALL * scale);
        List<JSONObject> items = new ArrayList<>();
        JSONObject heading =
                Board.text(Board.MARGIN, 12, title, size * 1.35f, "blue", true, 900, Board.AI);
        items.add(heading);
        float y = Board.bounds(heading, measure)[3] + 14;
        JSONArray rows = arr(equation ? arr("원소", "반응 전", "반응 후") : arr("원소", "원자 수"));
        Set<String> symbols = new java.util.LinkedHashSet<>(left.keySet());
        symbols.addAll(right.keySet());
        if (symbols.size() >= BoardPlot.ROWS)
            throw new IllegalArgumentException("서로 다른 원소가 너무 많아요.");
        for (String symbol : symbols)
            rows.put(
                    equation
                            ? arr(
                                    symbol,
                                    left.getOrDefault(symbol, 0L),
                                    right.getOrDefault(symbol, 0L))
                            : arr(symbol, left.get(symbol)));
        var table = BoardPlot.tableLayout(rows, true, y, size, measure);
        items.addAll(table.items());
        y += table.height() + 12;
        if (equation) {
            boolean equal = left.equals(right);
            JSONObject result =
                    Board.text(
                            Board.MARGIN,
                            y,
                            equal ? "양쪽 원자 수가 같아요" : "양쪽 원자 수가 달라요",
                            size,
                            equal ? "green" : "red",
                            true,
                            900,
                            Board.AI);
            items.add(result);
            y = Board.bounds(result, measure)[3];
        }
        return new BoardPlot.Made(items, y + 8);
    }
}
