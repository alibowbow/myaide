package dev.jun.gyeot.core;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Run-scoped circuit breaker. Only explicitly enabled profiles may receive a handoff. */
public final class ModelRouting {
    public interface Attempt<T> { T run(JSONObject profile, boolean handoff) throws Exception; }
    public record Answer<T>(T value, JSONObject profile) {}
    private final List<JSONObject> alternatives;
    private final boolean automatic;
    private final LongSupplier clock;
    private final Map<String, Long> cooling = new HashMap<>();
    public ModelRouting(JSONArray profiles, boolean automatic) { this(profiles, automatic, System::currentTimeMillis); }
    public ModelRouting(JSONArray profiles, boolean automatic, LongSupplier clock) {
        alternatives = new ArrayList<>(TeamPlan.enabled(profiles).values()); this.automatic = automatic; this.clock = clock;
    }
    public synchronized long remaining(String engine) { return Math.max(0, cooling.getOrDefault(engine, 0L) - clock.getAsLong()); }
    private synchronized void limited(String engine, ModelLimit error) { cooling.put(engine, Math.max(cooling.getOrDefault(engine, 0L), clock.getAsLong() + error.cooldownMillis)); }
    public <T> Answer<T> run(JSONObject requested, Attempt<T> attempt, Runnable guard, Consumer<JSONObject> event) throws Exception {
        List<JSONObject> choices = new ArrayList<>(); choices.add(TeamPlan.profile(requested));
        if (automatic) choices.addAll(alternatives);
        Set<String> tried = new HashSet<>(); JSONObject previous = null; ModelLimit last = null;
        for (JSONObject p : choices) {
            guard.run(); String engine = p.optString("engine"), identity = engine + "/" + p.optString("model");
            if (!tried.add(identity)) continue;
            if (automatic && remaining(engine) > 0) { if (previous == null) previous = p; continue; }
            boolean handoff = previous != null;
            if (handoff) event.accept(obj("from", previous.optString("model"), "to", p.optString("model"), "engine", engine,
                "reason", "quota", "at", clock.getAsLong()));
            try { return new Answer<>(attempt.run(p, handoff), p); }
            catch (ModelLimit limit) {
                guard.run(); limited(engine, limit); last = limit;
                event.accept(obj("from", p.optString("model"), "engine", engine, "reason", "quota", "status", limit.status,
                    "cooldown_ms", limit.cooldownMillis, "at", clock.getAsLong()));
                if (!automatic || !limit.safeToResume) throw limit;
                previous = p;
            }
        }
        guard.run();
        throw new IllegalStateException("허용한 모델이 모두 사용 한도에 도달했어요. 완료한 작업은 보관하고 자동 재시도는 멈췄어요.", last);
    }
}
