package dev.jun.gyeot.core;

import java.util.function.Consumer;

/**
 * Progressing work has no fixed tool-count cap and no context cap of the app's own: how much a run may hold is the model's
 * to say (Claude also clears old tool results itself, ClaudeProtocol.contextEdits). One live run only. Never restarts a
 * provider or reconstructs/replays a tool call. It stops only a stuck loop or a run of failing tools.
 */
public final class WorkBudget {
    public static final int CHUNK_ROUNDS = 30;
    private long rounds;
    private int failures;
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private boolean stalled;
    public void result(String name, String arguments, String output, boolean failed) {
        recent.addLast(digest(name + "\n" + arguments + "\n" + output));
        if (recent.size() > 128) recent.removeFirst();
        String[] history = recent.toArray(new String[0]);
        // Sixteen repeated periods of 1..8 calls: catches A/B cycles as well as a stuck single call.
        for (int period = 1; period <= 8 && period * 16 <= history.length; period++) {
            boolean repeated = true;
            for (int i = history.length - period * 16 + period; i < history.length; i++)
                if (!history[i].equals(history[i - period])) { repeated = false; break; }
            if (repeated) { stalled = true; break; }
        }
        result(failed);
    }
    private static String digest(String value) {
        try { return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public void beforeBatch(int count, long contextChars, Runnable guard, Consumer<String> stage) {
        beforeTool(guard);
        if (rounds > 0 && rounds % CHUNK_ROUNDS == 0)
            stage.accept(rounds + "단계 진행 · 기존 요청과 도구 결과를 유지해 이어가는 중");
        if (rounds < Long.MAX_VALUE) rounds++; // Saturate telemetry, never stop useful work on counter overflow.
    }
    public void beforeTool(Runnable guard) {
        guard.run();
        if (stalled) throw new IllegalStateException("같은 도구 요청·결과 또는 호출 순환이 16회 반복되어 추가 실행을 멈췄어요. 정상적인 대기일 수도 있지만 작업은 미완료예요. 진행 기록과 변경 상태를 확인한 뒤 이어서 지시해 주세요.");
        if (failures >= 8) throw new IllegalStateException("도구가 연속으로 실패해 추가 실행을 멈췄어요. 작업은 미완료이며 이미 시도한 변경은 다시 실행하지 않았어요. 진행 기록의 오류와 변경 상태를 확인해 주세요.");
    }
    public void beforeRequest(int contextChars, Runnable guard) { guard.run(); }
    /** Consecutive explicit tool failures are not useful progress; successes reset this guard. */
    public void result(boolean failed) {
        failures = failed ? Math.min(8, failures + 1) : 0;
    }
}
