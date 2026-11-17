package dev.jun.gyeot.core;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONObject;

/**
 * What identifies an AI connection: the API key, or the signed-in account (ChatGPT's client and subject, Grok's account),
 * never the short-lived tokens a sign-in refreshes on its own. A refreshed token is the same connection; another key or
 * another account is a different one. Used for the fingerprint that stops replies and jobs bound to an old connection.
 */
public final class ConnectionIdentity {
    /** Stored fields a sign-in rewrites without the user doing anything. */
    static final Set<String> ROTATING = Set.of("accessToken", "refreshToken", "idToken", "expiresAt", "earliestRefreshAt", "obtainedAt", "refreshedAt");
    private ConnectionIdentity() {}

    /** The stable part of a stored credential: a plain key as it is, a stored sign-in without its rotating tokens. */
    public static String stable(String credential) {
        if (credential == null) return "";
        String raw = credential.strip();
        if (!raw.startsWith("{")) return credential;
        JSONObject json;
        try { json = new JSONObject(raw); } catch (Exception notJson) { return credential; }
        TreeMap<String, String> kept = new TreeMap<>();
        for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (ROTATING.contains(key)) continue;
            String value = String.valueOf(json.opt(key));
            // A refresh may list the same granted scopes in another order.
            if (key.equals("scope")) { String[] scopes = value.strip().split("\\s+"); Arrays.sort(scopes); value = String.join(" ", scopes); }
            kept.put(key, value);
        }
        return kept.toString();
    }
}
