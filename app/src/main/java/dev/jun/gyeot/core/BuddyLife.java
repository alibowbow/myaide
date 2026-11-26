package dev.jun.gyeot.core;

import org.json.JSONObject;

/**
 * 퍼레's life on the phone, like a Tamagotchi (준: "내 휴대폰 공간안의 환경을 이해하고 그 공간에서 살아가는 다마고치 같은 느낌"):
 * the phone is its home. The app reads the home itself (the battery is its food and charging a meal, the clock its day, the
 * weather its clothes, music it dances to); what 퍼레 keeps is how close it feels to the user (its heart, 0 to 100: petted,
 * given a snack, played with, talked to; it fades by one every two hours alone, never below FLOOR). It does not grow up
 * (준: "자라기 모드 없애줘"); what it lived through is its memory (BuddyMemory). Pure Java.
 */
public final class BuddyLife {
    private BuddyLife() {}
    public static final int FLOOR = 15, START = 55, MOST = 100;
    private static final long HOUR = 3_600_000L;

    /** born: the day they met; heart: 0–100; at: the last fade counted; snackAt: the last snack; pets: strokes in all. */
    public record State(long born, int heart, long at, long snackAt, int pets) {}
    /** An act's outcome: the new state and how 퍼레 takes it (love, yum, full, fun, happy, or "" when nothing changed). */
    public record Done(State state, String felt) {}

    /** The saved life (blank: they meet now), faded for the hours since. */
    public static State read(String saved, long now) {
        State s;
        try {
            JSONObject o = new JSONObject(saved == null || saved.isBlank() ? "{}" : saved);
            long born = o.optLong("born", 0);
            s = born <= 0 || born > now ? new State(now, START, now, 0, 0)
                : new State(born, clamp(o.optInt("heart", START)), Math.min(now, o.optLong("at", now)), o.optLong("snackAt", 0), Math.max(0, o.optInt("pets", 0)));
        } catch (Exception unreadable) { s = new State(now, START, now, 0, 0); }
        long steps = (now - s.at()) / (2 * HOUR);
        if (steps <= 0) return s;
        int heart = s.heart() <= FLOOR ? s.heart() : (int) Math.max(FLOOR, s.heart() - steps);
        return new State(s.born(), heart, s.at() + steps * 2 * HOUR, s.snackAt(), s.pets());
    }
    public static String save(State s) {
        return Jsons.obj("born", s.born(), "heart", s.heart(), "at", s.at(), "snackAt", s.snackAt(), "pets", s.pets()).toString();
    }
    /** What the user did: pet, snack (a real one at most every two hours, else 퍼레 is full), play, talk, back (came home). */
    public static Done act(State s, String kind, long now) {
        return switch (kind == null ? "" : kind) {
            case "pet" -> new Done(new State(s.born(), clamp(s.heart() + 4), s.at(), s.snackAt(), s.pets() + 1), "love");
            case "snack" -> now - s.snackAt() < 2 * HOUR
                ? new Done(new State(s.born(), clamp(s.heart() + 1), s.at(), s.snackAt(), s.pets()), "full")
                : new Done(new State(s.born(), clamp(s.heart() + 8), s.at(), now, s.pets()), "yum");
            case "play" -> new Done(new State(s.born(), clamp(s.heart() + 5), s.at(), s.snackAt(), s.pets()), "fun");
            case "talk" -> new Done(new State(s.born(), clamp(s.heart() + 3), s.at(), s.snackAt(), s.pets()), "happy");
            case "back" -> new Done(new State(s.born(), clamp(s.heart() + 1), s.at(), s.snackAt(), s.pets()), "happy");
            default -> new Done(s, "");
        };
    }
    /** For the board: its heart, and whether a snack would be a real one. */
    public static JSONObject json(State s, long now) {
        return Jsons.obj("heart", s.heart(), "snackReady", now - s.snackAt() >= 2 * HOUR, "pets", s.pets());
    }
    /** For 퍼레's own words (BuddyTalk): how it feels, in plain words. */
    public static String words(State s, long now) {
        int h = s.heart();
        String heart = h >= 80 ? "사용자가 정말 좋아서 들떠 있어" : h >= 55 ? "사용자와 사이가 좋아" : h >= 35 ? "조금 심심하고 관심이 고파" : "오래 혼자 있어서 쓸쓸해";
        return "퍼레(너)의 마음: " + heart + " (마음 " + h + "/100)";
    }
    private static int clamp(int v) { return Math.max(0, Math.min(MOST, v)); }
}
