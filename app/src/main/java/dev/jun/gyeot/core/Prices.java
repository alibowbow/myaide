package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 비용이 보이게 (두뇌 배분): what each model costs per million tokens on its provider's own price page, with the page and the
 * day it was read, and what the usage ledger's tokens come to. A model not in the table is 모름, never a guess. A request on
 * a subscription sign-in (ChatGPT plan, Grok, Antigravity, the Codex sign-in) is 구독; NVIDIA NIM's free API, Groq's free tier,
 * Gemma and a Gemini key marked 무료 are 무료; an answer made on the phone (기기 안) costs nothing. Pure Java.
 *
 * All prices are each provider's standard (not batch, not priority, not fast mode) rates for a short prompt: the dearer
 * long-context rates (Claude Haiku 5.5 over 100,000 tokens, GPT over 272,000, Grok and Gemini Pro over 200,000) are not
 * applied, so a very long request is counted lower than it was billed. Claude's cache writes are counted at the 5-minute
 * rate. A request that reported its prompt without the cached part is counted as all uncached input.
 */
public final class Prices {
    private Prices() {}
    /** The won to the dollar the page shows, fixed here (not a live rate): 1 USD = 1,400원. */
    public static final double KRW_PER_USD = 1_400;
    /** When the table below was last read from the providers' pages. */
    public static final String CHECKED = "2026-11-27";
    public static final String ANTHROPIC = "platform.claude.com/docs/en/about-claude/pricing", OPENAI = "developers.openai.com/api/docs/pricing",
        GOOGLE = "aistudio.google.com/docs/pricing", XAI = "docs.x.ai/docs/models", GROQ = "console.groq.com/docs/models";

    /** USD per million tokens: input not read from the cache, cache read, cache write (Claude only; elsewhere 0), output. */
    public record Price(double input, double cacheRead, double cacheWrite, double output, String source, String checked, String note) {
        /** "$4/$20": input and output per million tokens, the way the picker shows it. */
        public String pair() { return "$" + money(input) + "/$" + money(output); }
    }
    private static final Map<String, Price> TABLE = new LinkedHashMap<>();
    private static void add(String id, double in, double read, double write, double out, String source) { TABLE.put(id, new Price(in, read, write, out, source, CHECKED, "")); }
    private static void add(String id, double in, double read, double write, double out, String source, String note) { TABLE.put(id, new Price(in, read, write, out, source, CHECKED, note)); }
    static {
        // Anthropic: base input, cache hit, 5-minute cache write, output.
        add("claude-fable-5-2", 8, 0.20, 10, 40, ANTHROPIC);
        add("claude-fable-5-1", 10, 0.25, 12.5, 50, ANTHROPIC);
        add("claude-opus-5-5", 4, 0.20, 5, 20, ANTHROPIC);
        add("claude-opus-5", 5, 0.50, 6.25, 25, ANTHROPIC);
        add("claude-sonnet-5-5", 2, 0.10, 2.5, 10, ANTHROPIC);
        add("claude-sonnet-5", 2, 0.20, 2.5, 10, ANTHROPIC);
        add("claude-haiku-5-5", 0.10, 0.01, 0.125, 0.50, ANTHROPIC, "프롬프트 10만 토큰 이하 요금");
        // OpenAI, Standard, prompts up to 272K tokens: input, cached input, output.
        add("gpt-6.2-astra", 8, 0.40, 0, 40, OPENAI);
        add("gpt-6.2-sol", 2, 0.10, 0, 10, OPENAI);
        add("gpt-6.1-sol", 2, 0.10, 0, 10, OPENAI);
        add("gpt-6.1-terra", 1, 0.05, 0, 5, OPENAI);
        add("gpt-6-astra", 10, 1.00, 0, 50, OPENAI);
        add("gpt-6-sol", 2, 0.20, 0, 10, OPENAI);
        add("gpt-6-luna", 0.10, 0.01, 0, 0.50, OPENAI);
        // Google, paid Standard tier: input, context caching, output (thinking included).
        add("gemini-4-carbon", 2, 0.10, 0, 10, GOOGLE, "2026-12-01부터 $4/$20");
        add("gemini-4-argon", 2, 0.10, 0, 10, GOOGLE, "2026-12-01부터 $4/$20");
        add("gemini-4-neon", 3, 0.15, 0, 15, GOOGLE);
        add("gemini-4-hydrogen", 1.5, 0.075, 0, 7.5, GOOGLE);
        add("gemini-3.8-flash", 0.75, 0.075, 0, 3.75, GOOGLE, "2027-01-01부터 $1.50/$7.50");
        add("gemini-3.6-flash", 0.75, 0.075, 0, 3.75, GOOGLE, "2027-01-01부터 $1.50/$7.50");
        add("gemini-3.5-flash-lite", 0.30, 0.03, 0, 2.50, GOOGLE);
        add("gemini-3.1-flash-lite", 0.25, 0.025, 0, 1.50, GOOGLE);
        add("gemini-3.1-pro-preview", 2, 0.20, 0, 12, GOOGLE, "20만 토큰 이하 요금");
        add("gemini-3-flash-preview", 0.50, 0.05, 0, 3, GOOGLE);
        add("gemini-2.5-flash", 0.30, 0.03, 0, 2.50, GOOGLE);
        add("gemini-2.5-flash-lite", 0.10, 0.01, 0, 0.40, GOOGLE);
        // xAI: prompts under 200K tokens.
        add("grok-4.7", 2, 0.50, 0, 6, XAI, "20만 토큰 미만 요금");
        // Groq's paid tier (the app uses its free tier, so these only rank the models).
        add("openai/gpt-oss-120b", 0.15, 0, 0, 0.60, GROQ);
        add("openai/gpt-oss-20b", 0.075, 0, 0, 0.30, GROQ);
        add("qwen/qwen3.8-27b", 0.80, 0, 0, 4, GROQ);
    }
    /** The table, for 설정 → 사용량's 요금표. */
    public static Map<String, Price> table() { return java.util.Collections.unmodifiableMap(TABLE); }
    /**
     * The price of a model as the ledger names it ("models/" and a "-latest" alias are not resolved: an alias may point
     * anywhere, so it is 모름). Null when the table does not have it.
     */
    public static Price of(String model) {
        if (model == null || model.isBlank()) return null;
        String id = model.strip().toLowerCase(Locale.ROOT);
        if (id.startsWith("models/")) id = id.substring(7);
        return TABLE.get(id);
    }

    /** How a request is paid. */
    public enum Billing { PAID, PLAN, FREE, DEVICE, UNKNOWN }
    /**
     * company and route as the ledger keeps them (WorkBook's engine names; "plan", "key", "codex", "local"); geminiFree: the
     * Gemini key is marked 무료 (GeminiKeyRing); geminiTier: its tier is known at all.
     */
    public static Billing billing(String company, String route, String model, boolean geminiFree, boolean geminiTier) {
        String c = company == null ? "" : company, r = route == null ? "" : route;
        if (BrainRoutes.DEVICE.equals(c) || BrainRoutes.LOCAL.equals(r)) return Billing.DEVICE;
        if ("plan".equals(r) || "codex".equals(r)) return Billing.PLAN;
        if (WorkBook.NIM.equals(c) || WorkBook.GROQ.equals(c)) return Billing.FREE;
        if (WorkBook.ANTIGRAVITY.equals(c) || WorkBook.GROK.equals(c) && !"key".equals(r)) return Billing.PLAN;
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (WorkBook.GEMINI.equals(c)) {
            if (m.contains("gemma")) return Billing.FREE;
            if (geminiFree) return Billing.FREE;
            if (!geminiTier) return Billing.UNKNOWN;
        }
        return of(model) == null ? Billing.UNKNOWN : Billing.PAID;
    }
    /** USD for these token sums at price: input (not cached), cache read, cache write, output. */
    public static double usd(Price price, long input, long cacheRead, long cacheWrite, long output) {
        return (input * price.input() + cacheRead * price.cacheRead() + cacheWrite * (price.cacheWrite() > 0 ? price.cacheWrite() : price.input()) + output * price.output()) / 1_000_000.0;
    }

    /**
     * One group of the ledger (a surface, company, model and route over a period): its requests, those whose tokens were
     * told (priced), and their sums. A request without its counts is in requests but not in priced.
     */
    public record Tokens(String surface, String company, String model, String route, long requests, long priced, long input, long cacheRead, long cacheWrite, long output) {}
    /** A total over groups: dollars of the requests that could be priced, and how many requests were each way. */
    public record Spend(String key, double usd, long paid, long plan, long free, long unknown) {
        public long requests() { return paid + plan + free + unknown; }
        Spend plus(double more, Billing b, long n) {
            return new Spend(key, usd + more, paid + (b == Billing.PAID ? n : 0), plan + (b == Billing.PLAN ? n : 0), free + (b == Billing.FREE || b == Billing.DEVICE ? n : 0), unknown + (b == Billing.UNKNOWN ? n : 0));
        }
    }
    /** groups summed by company (byCompany) or by surface, the most money first, then the most requests, each priced or classed as above. */
    public static List<Spend> spend(List<Tokens> groups, boolean byCompany, boolean geminiFree, boolean geminiTier) {
        Map<String, Spend> out = new LinkedHashMap<>();
        for (Tokens g : groups) {
            String key = byCompany ? g.company() : g.surface();
            Spend s = out.getOrDefault(key, new Spend(key, 0, 0, 0, 0, 0));
            Billing b = billing(g.company(), g.route(), g.model(), geminiFree, geminiTier);
            if (b == Billing.PAID) {
                Price p = of(g.model());
                s = s.plus(usd(p, g.input(), g.cacheRead(), g.cacheWrite(), g.output()), Billing.PAID, g.priced());
                // Paid requests that did not tell their tokens: their cost is not known.
                if (g.requests() > g.priced()) s = s.plus(0, Billing.UNKNOWN, g.requests() - g.priced());
            } else s = s.plus(0, b, g.requests());
            out.put(key, s);
        }
        List<Spend> list = new ArrayList<>(out.values());
        list.sort((a, b) -> a.usd() != b.usd() ? Double.compare(b.usd(), a.usd()) : Long.compare(b.requests(), a.requests()));
        return list;
    }
    /**
     * A total in words, short: "$1.23 · 약 1,722원", "$0.04 · 약 56원 + 구독 3번", "구독", "무료", "모름", and how many requests were
     * not known ("· 2번은 모름") when some were priced.
     */
    public static String words(Spend s) {
        List<String> parts = new ArrayList<>();
        if (s.paid() > 0) parts.add(dollars(s.usd()));
        if (s.plan() > 0) parts.add(s.paid() > 0 || s.free() > 0 ? "구독 " + s.plan() + "번" : "구독");
        if (s.free() > 0) parts.add(s.paid() > 0 || s.plan() > 0 ? "무료 " + s.free() + "번" : "무료");
        if (parts.isEmpty()) return "모름";
        String line = String.join(" + ", parts);
        return s.unknown() > 0 ? line + " · " + s.unknown() + "번은 모름" : line;
    }
    /** "$1.23 · 약 1,722원"; under a cent "$0.004 · 약 6원", nothing "$0". */
    public static String dollars(double usd) {
        if (usd <= 0) return "$0";
        String d = usd >= 1 ? String.format(Locale.ROOT, "%,.2f", usd) : usd >= 0.01 ? String.format(Locale.ROOT, "%.2f", usd) : String.format(Locale.ROOT, "%.3f", usd);
        if (d.equals("0.000")) d = "0.001 미만";
        return "$" + d + " · 약 " + won(usd) + "원";
    }
    /** Won at KRW_PER_USD, whole, with commas; "1 미만" below one. */
    public static String won(double usd) {
        long w = Math.round(usd * KRW_PER_USD);
        return w < 1 ? "1 미만" : String.format(Locale.ROOT, "%,d", w);
    }
    /** A price in the shortest form: 4, 0.5, 0.125, 12.5. */
    public static String money(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() ? "0" : s;
    }
    /**
     * The one short line the model picker shows for a model: "100만 토큰 $4/$20" when it is paid, "구독" or "무료" by its route,
     * "요금 모름" for a model the table does not have.
     */
    public static String tag(String company, String route, String model, boolean geminiFree, boolean geminiTier) {
        Billing b = billing(company, route, model, geminiFree, geminiTier);
        return switch (b) {
            case PLAN -> "구독";
            case FREE, DEVICE -> "무료";
            case PAID -> "100만 토큰 " + of(model).pair();
            default -> "요금 모름";
        };
    }
    /**
     * The same for a Gemini model on the picker, which does not read the key's tier (that is a stored secret): Gemma is free,
     * a model in the table "무료 키 0원 · 유료 $2/$10", anything else (a "-latest" alias) 요금 모름.
     */
    public static String geminiTag(String model) {
        if (model != null && model.toLowerCase(Locale.ROOT).contains("gemma")) return "무료";
        Price p = of(model);
        return p == null ? "요금 모름" : "무료 키 0원 · 유료 " + p.pair();
    }
}
