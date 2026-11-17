package dev.jun.gyeot.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Executor;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Warms the network path to the Live host before a call may start: one DNS lookup and one TLS
 * handshake, then the socket closes. Nothing is sent over it, so no session opens and nothing is billed; what it buys is a
 * cached lookup, an awake radio and a loaded TLS stack when the call connects. At most once a minute, failed or not.
 */
public final class LiveWarmup {
    public static final String HOST = "generativelanguage.googleapis.com";
    public static final long EVERY_MS = 60_000;
    private static final int TIMEOUT_MS = 10_000;
    /** The network step; tests pass their own. */
    public interface Connector { void warm(String host) throws Exception; }
    /** A network step that does nothing: what tests get unless one opts in to the real handshake. */
    public static final Connector NONE = host -> {};
    private final Connector connector;
    private boolean warmed;
    private long last;
    public LiveWarmup(Connector connector) { this.connector = connector; }

    /** Starts one warm-up on executor (never the main thread) unless one started in the last minute; true when it did. */
    public synchronized boolean maybe(long now, Executor executor) {
        if (warmed && now - last < EVERY_MS) return false;
        warmed = true; last = now;
        try { executor.execute(() -> { try { connector.warm(HOST); } catch (Exception quiet) { /* the call connects as before */ } }); }
        catch (RuntimeException rejected) { return false; }
        return true;
    }

    /**
     * The lookup and the handshake as the Live socket makes them: the system resolver, the default TLS factory (whose session
     * cache the socket shares) and the host name checked, so a wrong certificate fails here too.
     */
    public static void handshake(String host) throws IOException {
        InetAddress address = InetAddress.getAllByName(host)[0];
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(address, 443), TIMEOUT_MS); raw.setSoTimeout(TIMEOUT_MS);
            try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true)) {
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS"); parameters.setServerNames(List.of(new SNIHostName(host)));
                tls.setSSLParameters(parameters);
                tls.startHandshake();
            }
        }
    }
}
