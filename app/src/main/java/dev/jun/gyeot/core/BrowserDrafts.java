package dev.jun.gyeot.core;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Same-process UI drafts only: never disk, transcript, model context, or a task submission. */
public final class BrowserDrafts {
    public record Draft(String text, int selectionStart, int selectionEnd) {}

    private static final class Owner extends WeakReference<Object> {
        private final int hash;
        Owner(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object value) {
            if (this == value) return true;
            Object owner = get();
            return owner != null && value instanceof Owner other && owner == other.get();
        }
    }
    private final ReferenceQueue<Object> released = new ReferenceQueue<>();
    private final Map<Owner, Map<String, Draft>> drafts = new HashMap<>();

    /** Keep the exact text and UTF-16 selection used by EditText; do not silently truncate. */
    public synchronized void save(Object tabOwner, String conversationId, String text, int start, int end) {
        if (tabOwner == null || conversationId == null || conversationId.isBlank()) return;
        collectReleasedOwners();
        String value = text == null ? "" : text;
        drafts.computeIfAbsent(new Owner(tabOwner, released), ignored -> new HashMap<>())
            .put(conversationId, new Draft(value, clamp(start, value.length()), clamp(end, value.length())));
    }

    /** A different tab object or conversation cannot inherit a draft, even if its title/URL matches. */
    public synchronized Draft restore(Object tabOwner, String conversationId) {
        if (tabOwner == null || conversationId == null || conversationId.isBlank()) return null;
        collectReleasedOwners();
        Map<String, Draft> scoped = drafts.get(new Owner(tabOwner, null));
        return scoped == null ? null : scoped.get(conversationId);
    }

    private static int clamp(int value, int length) { return Math.max(0, Math.min(length, value)); }
    private void collectReleasedOwners() {
        Owner owner;
        while ((owner = (Owner) released.poll()) != null) drafts.remove(owner);
    }
}
