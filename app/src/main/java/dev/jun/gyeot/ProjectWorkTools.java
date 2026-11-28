package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.ProjectTools;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 프로젝트 for the 작업창's models and the main chat's (ProjectTools on ProjectMaker's book), one session per reply or run:
 * the projects a conversation made or changed in its last two replies or runs are its own (a follow-up "색만 바꿔 줘"
 * changes them without naming 프로젝트 again). The screens showing projects hear of each change.
 */
final class ProjectWorkTools extends WorkTools {
    private final ProjectTools.Session session;
    ProjectWorkTools(Context context, Supplier<String> words, String conversation) {
        java.util.List<java.util.Set<String>> turn = begin(conversation);
        ProjectMaker maker = ProjectMaker.get(context);
        session = new ProjectTools.Session(maker.book(), words, turn.get(0), turn.get(1), maker::touched);
    }
    @Override JSONArray declarations() { return ProjectTools.declarations(); }
    @Override String instructions() { return ProjectTools.INSTRUCTIONS; }
    @Override JSONObject run(String name, Object arguments) { return session.run(name, arguments); }

    private static final java.util.Map<String, java.util.ArrayDeque<java.util.Set<String>>> recent = new java.util.LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, java.util.ArrayDeque<java.util.Set<String>>> eldest) { return size() > 64; }
    };
    /** {the conversation's own projects so far, the set this reply notes its own in}. */
    static synchronized java.util.List<java.util.Set<String>> begin(String conversation) {
        java.util.ArrayDeque<java.util.Set<String>> turns = recent.computeIfAbsent(conversation, k -> new java.util.ArrayDeque<>());
        java.util.Set<String> own = new java.util.HashSet<>(); for (java.util.Set<String> t : turns) own.addAll(t);
        java.util.Set<String> now = java.util.concurrent.ConcurrentHashMap.newKeySet();
        turns.addLast(now); while (turns.size() > 2) turns.removeFirst();
        return java.util.List.of(own, now);
    }
}
