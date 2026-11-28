package dev.jun.gyeot;

import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.PlanUsage;
import dev.jun.gyeot.core.SpareQuota;
import dev.jun.gyeot.core.WorkBook;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 남는 한도 for the whole app (core/SpareQuota) on what GeminiQuotas counts: which of the Groq key's chat models a 퍼레 talk, a
 * 잡일 or spare work goes to now, and the browser's hand-over order by what each subscription last reported left. Without the
 * app's store (tests, another process) every choice is the caller's own. Any thread; browser() reads the usage ledger and
 * the connections' identities, so never on the main thread.
 */
final class SpareQuotas {
    private SpareQuotas() {}

    /**
     * The key's Groq chat models this chooses among (SpareQuota.tier knows them), home first, then by tier and GroqModels' order;
     * just home when the key's list was never read (as before: one model).
     */
    static List<String> groq(AppStore store, String home) {
        List<String> out = new ArrayList<>(); if (home != null && !home.isBlank()) out.add(home);
        if (store == null) return out;
        org.json.JSONArray listed = store.groq.models(); List<String> others = new ArrayList<>();
        for (int i = 0; i < listed.length(); i++) { String id = listed.optString(i); if (!out.contains(id) && !others.contains(id) && SpareQuota.tier(id) != null) others.add(id); }
        others.sort(java.util.Comparator.comparingInt((String id) -> SpareQuota.tier(id).ordinal()).thenComparingInt(SpareQuotas::rank).thenComparing(id -> id));
        out.addAll(others);
        return out;
    }
    private static int rank(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        return l.contains("gpt-oss-120b") ? 0 : l.contains("qwen") ? 1 : l.contains("gpt-oss") ? 2 : l.contains("llama") ? 3 : 4;
    }
    /**
     * 퍼레's talk (BuddyTalk): home (the Qwen of 퍼레's brain, the user's choice) while it can take tokens; when it cannot, the
     * Groq model of its tier with the most of its day left, or a MID one too when no one else can stand in (standIn false: no
     * NVIDIA, no free or subscribed AI chosen for 퍼레). home when none can.
     */
    static String talk(String home, int tokens, boolean standIn) {
        AppStore s = GeminiQuotas.store(); if (s == null || home == null) return home;
        try {
            String got = SpareQuota.pick(SpareQuota.Kind.TALK, tokens, standIn ? SpareQuota.Tier.HIGH : SpareQuota.Tier.MID, groq(s, home), List.of(), s.geminiQuota(), System.currentTimeMillis());
            return got == null ? home : got;
        } catch (RuntimeException unreadable) { return home; }
    }
    /** 잡일 (Chores): GPT OSS 120B while it is within reach of the roomiest of its tier, else that one; MID before none. Null: none can take it. */
    static String light(int tokens) { return choose(SpareQuota.Kind.LIGHT, tokens, SpareQuota.Tier.MID, null); }
    /** Spare work (SpareWork): the HIGH model with the most spare tokens (SpareQuota.spare), never refused; null when none has. */
    static String spare(int tokens) { return choose(SpareQuota.Kind.SPARE, tokens, SpareQuota.Tier.HIGH, null); }
    /** As light(tokens), leaving out a model that just refused (one hand-over within Groq). */
    static String lightBut(int tokens, String refused) { return choose(SpareQuota.Kind.LIGHT, tokens, SpareQuota.Tier.MID, refused); }
    private static String choose(SpareQuota.Kind kind, int tokens, SpareQuota.Tier floor, String without) {
        AppStore s = GeminiQuotas.store();
        if (s == null) return kind == SpareQuota.Kind.SPARE || GroqWork.CHORES.equals(without) ? null : GroqWork.CHORES;
        try {
            long now = System.currentTimeMillis();
            org.json.JSONObject state = s.geminiQuota();
            // The one that refused rests in this choice (as Groq's refusal already made it in the app's counts); the others keep their roles.
            if (without != null) dev.jun.gyeot.core.GeminiQuota.rest(state, without, now, 60_000);
            // 퍼레's Qwen is kept for 퍼레: 잡일 and spare work take it only while it keeps RESERVE of its day.
            return SpareQuota.pick(kind, tokens, floor, groq(s, GroqWork.CHORES), List.of(BuddyTalk.qwen(s)), state, now);
        } catch (RuntimeException unreadable) { return kind == SpareQuota.Kind.SPARE ? null : GroqWork.CHORES; }
    }

    /**
     * The browser's next AIs (BrowserAgent.FALLBACK: gpt, grok, nim, claude) in the order to try them: a subscription or free
     * tier whose tightest window it last reported nearly spent goes after the others (core/SpareQuota.order); a paid key keeps
     * its place. Off the main thread.
     */
    static List<String> browser(AppStore store, String[] chain) {
        List<String> order = new ArrayList<>(List.of(chain));
        if (store == null) return order;
        try {
            long now = System.currentTimeMillis();
            Map<String, Double> shares = new LinkedHashMap<>(); Set<String> paid = new HashSet<>();
            for (String brain : chain) {
                String engine = WorkBook.engine(brain); if (!store.ready(engine)) continue;
                String route = store.usageRoute(engine);
                // Money per request (an API key, or not known to be free): kept where it is.
                dev.jun.gyeot.core.Prices.Billing billing = dev.jun.gyeot.core.Prices.billing(engine, route, "", false, false);
                if (billing != dev.jun.gyeot.core.Prices.Billing.PLAN && billing != dev.jun.gyeot.core.Prices.Billing.FREE) { paid.add(brain); continue; }
                Double share = share(store, engine, route, now);
                if (share != null) shares.put(brain, share);
            }
            return SpareQuota.order(order, shares, paid);
        } catch (RuntimeException unreadable) { return order; }
    }
    /** The tightest share an account last reported left, or null (tightest). */
    static Double share(AppStore store, String engine, String route, long now) {
        Limits.Window w = tightest(store, engine, route, now);
        return w == null ? null : w.left();
    }
    /**
     * An account's tightest current window as it last reported it (core/Limits in the usage ledger: the ChatGPT plan with its
     * Codex sign-in's 5-hour and weekly windows too, Antigravity with agy's own panel), or null when none is current.
     */
    static Limits.Window tightest(AppStore store, String engine, String route, long now) {
        List<Limits.Window> seen = new ArrayList<>();
        Limits.Window own = SpareQuota.tightest(store.usage.limits(engine, route, store.usageIdentity(engine)), now);
        if (own != null) seen.add(own);
        if (WorkBook.CHATGPT.equals(engine) && store.codexImages.connected()) {
            Limits.Window codex = SpareQuota.tightest(store.usage.limits(WorkBook.CHATGPT, PlanUsage.CODEX_ROUTE, store.codexImages.usageIdentity()), now);
            if (codex != null) seen.add(codex);
        }
        if (WorkBook.ANTIGRAVITY.equals(engine)) { Limits.Window agy = SpareQuota.tightest(store.antigravity.limits(), now); if (agy != null) seen.add(agy); }
        Limits.Window least = null;
        for (Limits.Window w : seen) if (least == null || w.left() < least.left()) least = w;
        return least;
    }
}
