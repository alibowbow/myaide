package dev.jun.gyeot.core;

/**
 * A provider's refusal with its HTTP status kept beside the Korean explanation the clients already wrote, so the fallback
 * (core/Fallback) reads the status instead of guessing it from the words. Still an IllegalStateException, so every catch that
 * was there before catches it the same way. Never carries a response body or a key.
 */
public final class ProviderError extends IllegalStateException {
    public final int status;
    public ProviderError(int status, String message) { super(message); this.status = status; }
}
