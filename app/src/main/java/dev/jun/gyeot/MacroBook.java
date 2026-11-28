package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import dev.jun.gyeot.core.Macros;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The user's macros, kept on the phone (their own preferences file, so the app's settings stay small) and read once: the
 * parsed list stays in memory while the stored text is the same. Also the one phone job of 퍼레's just done that could be
 * saved as a macro (offer: "이대로 매크로로 저장할까?"), in memory for a while.
 */
final class MacroBook {
    private MacroBook() {}
    static final String PREFS = "macros", LIST = "list";
    private static String raw; private static List<Macros.Macro> parsed = List.of();
    private static final Object LOCK = new Object();
    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static List<Macros.Macro> list(Context c) {
        synchronized (LOCK) {
            String now = prefs(c).getString(LIST, "");
            if (!now.equals(raw)) { parsed = Macros.parse(now); raw = now; }
            return parsed;
        }
    }
    /** The list changed by how (it may throw with words for the user), kept, and the app's screens told. Returns the new list. */
    static List<Macros.Macro> change(Context c, UnaryOperator<List<Macros.Macro>> how) {
        List<Macros.Macro> next;
        synchronized (LOCK) {
            next = how.apply(list(c));
            String text = Macros.json(next);
            prefs(c).edit().putString(LIST, text).apply();
            raw = text; parsed = Macros.parse(text);
        }
        try { ((GyeotApp) c.getApplicationContext()).store().changed(); } catch (RuntimeException noStore) { /* tests */ }
        return next;
    }
    static Macros.Macro byId(Context c, String id) { return Macros.byId(list(c), id); }
    /** The macro words ask for ("출근 체크 해 줘", "출근 체크 매크로 돌려 줘"), or by its exact name; null when none. */
    static Macros.Macro named(Context c, String said) {
        List<Macros.Macro> all = list(c);
        Macros.Macro exact = Macros.find(all, said);
        return exact != null ? exact : Macros.named(all, said);
    }
    /** A run's end, kept on the macro: when, and its line ("✓ …" done). */
    static void ran(Context c, String id, long at, String how) {
        try { change(c, all -> { Macros.Macro m = Macros.byId(all, id); return m == null ? all : Macros.save(all, m.ranAt(at, how)); }); }
        catch (RuntimeException gone) { /* deleted meanwhile */ }
    }
    /** For the tests: forget what was read. */
    static void forget() { synchronized (LOCK) { raw = null; parsed = List.of(); } }

    // ── a phone job of 퍼레's that could be one ──
    /** A phone job just done, its steps and the words it was asked with; offered for OFFER_MS. */
    record Offer(List<Macros.Step> steps, String request, long at) {}
    static final long OFFER_MS = 15 * 60_000L;
    private static volatile Offer offer;
    static void offer(List<Macros.Step> steps, String request, long at) { offer = steps == null ? null : new Offer(steps, request == null ? "" : request, at); }
    static Offer offered(long now) { Offer o = offer; return o != null && now - o.at < OFFER_MS ? o : null; }
    /** Saves the offer under name (its request when blank); throws with words when there is none or the name is taken. */
    static Macros.Macro saveOffer(Context c, String name, long now) {
        Offer o = offered(now);
        if (o == null) throw new IllegalStateException("저장할 폰 작업이 없어. 폰 작업을 맡긴 바로 뒤에 말해 줘.");
        List<Macros.Macro> all = list(c);
        String chosen = name == null || name.isBlank() ? Macros.freeName(all, o.request.length() > 20 ? o.request.substring(0, 20) : o.request) : name.strip();
        Macros.Macro made = new Macros.Macro("", chosen, o.steps, now, 0, 0, "");
        change(c, list -> Macros.save(list, made));
        offer = null;
        return made;
    }
}
