package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import dev.jun.gyeot.core.RealtimeVoice;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/**
 * One GPT-Live call over WebRTC, as the Codex apps make it, on the phone's own WebView (its WebRTC, echo cancelling and
 * noise suppression): the microphone goes up and the answer plays as a call (a headset first, else the speaker). The SDP
 * offer goes to RealtimeVoice.LIVE_CALLS with the session and the Codex sign-in, as the Codex CLI sends it; the call's
 * events come on the "oai-events" data channel and on its sideband (joined by the call's id), whichever brings one first
 * (an error aside) being followed, and what the app sends goes the same way. The page is this
 * class's own and goes nowhere else; only it may use the microphone. Bridged, the call takes the app's own 24 kHz PCM
 * instead of the microphone (push) and gives back the answer's sound as PCM (Listener.audio) instead of playing it, for a
 * caller that keeps its own microphone, speaker and barge-in (the voice assistant, 판서's narration). Main thread, except
 * the listener (any thread).
 */
final class LiveCall {
    interface Listener {
        /** The data channel is open: events can go both ways. */
        void opened();
        /** One event from the server (JSON text). */
        void message(String json);
        /** The call could not go on; status is the server's HTTP status when it turned the call down (0 otherwise). */
        void failed(String why, int status);
        /** Bridged: the answer's sound, 24 kHz 16-bit mono PCM (silence left out). */
        default void audio(byte[] pcm) {}
    }
    /** GPT-Live did not open in this process: the voices that can go on without it use their Realtime voice instead. */
    static volatile boolean refused;
    /** The server turned the call down with this HTTP status. */
    static final class Refused extends Exception { final int status; Refused(String message, int status) { super(message); this.status = status; } }

    private static final String ORIGIN = "https://live.gyeot.local/";
    private final Context context; private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Route route; private final boolean bridged;
    private WebView web;
    private volatile String token = "", account = ""; private volatile JSONObject session;
    private final String sessionId = java.util.UUID.randomUUID().toString();
    /** The call's sideband, and where its events come from (0 not yet, 1 the data channel, 2 the sideband). */
    private volatile WebSocketClient side; private volatile int source;
    private volatile boolean closed, failed, muted;
    /** When the answer was last heard playing. */
    private volatile long lastLoud;

    LiveCall(Context context, Listener listener) { this(context, listener, false); }
    LiveCall(Context context, Listener listener, boolean bridged) {
        this.context = context.getApplicationContext(); this.listener = listener; this.bridged = bridged;
        route = new Route(this.context, main);
    }
    private void fail(String why, int status) {
        if (closed || failed) return;
        failed = true; listener.failed(why == null || why.isBlank() ? "음성 연결이 끊겼어요." : why, status);
    }

    /** Opens the call's page: it asks for the microphone, makes the offer, and the server answers it with this session. */
    void start(String token, String account, JSONObject session) {
        this.token = token == null ? "" : token; this.account = account == null ? "" : account; this.session = session;
        main.post(() -> {
            if (closed) return;
            if (!bridged) route.begin();
            try { web = new WebView(context); }
            catch (RuntimeException unavailable) { fail("이 휴대폰의 WebView로 음성 연결을 열지 못했어요.", 0); return; }
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true); settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onPermissionRequest(PermissionRequest request) {
                    // Only this call's own page, and only the microphone.
                    Uri origin = request.getOrigin();
                    boolean ours = origin != null && "live.gyeot.local".equals(origin.getHost());
                    for (String resource : request.getResources())
                        if (ours && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) { request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); return; }
                    request.deny();
                }
            });
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            });
            web.addJavascriptInterface(new Bridge(), "GyeotLive");
            web.loadDataWithBaseURL(ORIGIN, PAGE.replace("__MUTED__", String.valueOf(muted)).replace("__BRIDGED__", String.valueOf(bridged)), "text/html", "utf-8", null);
        });
    }
    /** What the page tells (on WebView's bridge thread). */
    private final class Bridge {
        @JavascriptInterface public void offer(String sdp) {
            if (closed) return;
            Thread post = new Thread(() -> {
                try {
                    String answer = post(sdp);
                    if (answer == null || !answer.startsWith("v=")) { fail("GPT-Live가 연결 답을 주지 않았어요.", 0); return; }
                    js("answer(" + JSONObject.quote(answer) + ")");
                } catch (Refused refused) { fail(refused.getMessage(), refused.status); }
                catch (Exception error) { fail(error.getMessage() == null ? "GPT-Live에 연결하지 못했어요." : error.getMessage(), 0); }
            }, "gyeot-live-offer");
            post.setDaemon(true); post.start();
        }
        @JavascriptInterface public void opened() { if (!closed) listener.opened(); }
        @JavascriptInterface public void message(String json) { deliver(1, json); }
        @JavascriptInterface public void failed(String why) { fail(why, 0); }
        @JavascriptInterface public void level(double value) { if (value > 0.04) lastLoud = SystemClock.uptimeMillis(); }
        @JavascriptInterface public void pcm(String base64) {
            if (closed || base64 == null) return;
            try { byte[] pcm = java.util.Base64.getDecoder().decode(base64); lastLoud = SystemClock.uptimeMillis(); listener.audio(pcm); }
            catch (IllegalArgumentException bad) {}
        }
    }
    private void js(String script) { main.post(() -> { if (!closed && web != null) web.evaluateJavascript(script, null); }); }

    /** The offer to LIVE_CALLS with the session; the answer SDP back, and the call's sideband joined by its id. Blocking. */
    private String post(String offer) throws Exception {
        java.net.HttpURLConnection http = (java.net.HttpURLConnection) new java.net.URL(RealtimeVoice.LIVE_CALLS).openConnection();
        try {
            http.setRequestMethod("POST"); http.setConnectTimeout(15_000); http.setReadTimeout(20_000); http.setDoOutput(true);
            for (Map.Entry<String, String> h : RealtimeVoice.liveHeaders(token, account, sessionId).entrySet()) http.setRequestProperty(h.getKey(), h.getValue());
            http.setRequestProperty("Content-Type", "application/json");
            byte[] body = RealtimeVoice.liveCall(offer, session).toString().getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream out = http.getOutputStream()) { out.write(body); }
            int status = http.getResponseCode();
            java.io.InputStream in = status >= 400 ? http.getErrorStream() : http.getInputStream();
            String reply = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (status >= 300) { String why = RealtimeVoice.liveRefusal(reply); throw new Refused("GPT-Live " + status + (why.isEmpty() ? "" : " · " + why), status); }
            String id = RealtimeVoice.liveCallId(http.getHeaderField("Location"));
            if (!id.isEmpty()) sideband(id);
            return RealtimeVoice.liveAnswer(reply);
        } finally { http.disconnect(); }
    }
    /** The call's events by its id, as Codex follows them. */
    private void sideband(String id) {
        if (closed) return;
        try {
            WebSocketClient ws = new WebSocketClient(URI.create(RealtimeVoice.LIVE_SIDEBAND + id), new Draft_6455(), RealtimeVoice.liveHeaders(token, account, sessionId), 15_000) {
                @Override public void onOpen(ServerHandshake handshake) {}
                @Override public void onMessage(String text) { if (side == this) deliver(2, text); }
                @Override public void onMessage(ByteBuffer bytes) {}
                @Override public void onClose(int code, String reason, boolean remote) { if (side == this) side = null; }
                @Override public void onError(Exception error) {}
            };
            ws.setConnectionLostTimeout(30);
            side = ws; ws.connect();
        } catch (Exception unavailable) { /* The data channel still carries the call's events. */ }
    }
    /** An event from the data channel (1) or the sideband (2): the first to bring one (an error aside) is followed from then on. */
    private void deliver(int from, String json) {
        if (closed || json == null) return;
        RealtimeVoice.Type type = RealtimeVoice.parseLive(json).type();
        if (type == RealtimeVoice.Type.OTHER || type == RealtimeVoice.Type.AUDIO) return;
        synchronized (this) {
            if (source == 0) { if (type == RealtimeVoice.Type.ERROR && from != 1) return; if (type != RealtimeVoice.Type.ERROR) source = from; }
            else if (source != from) return;
        }
        listener.message(json);
    }

    /** An event for the server, the way the call's events come; the page keeps it until the data channel opens. */
    void send(String json) {
        WebSocketClient w = side;
        if (source == 2 && w != null && w.isOpen()) { try { w.send(json); } catch (Exception dropped) {} return; }
        js("send(" + JSONObject.quote(json) + ")");
    }
    /** Bridged: 24 kHz 16-bit mono PCM for the call to send as the user's voice (any thread). */
    void push(byte[] pcm24k) {
        if (!bridged || closed || pcm24k == null || pcm24k.length == 0) return;
        js("pcm('" + java.util.Base64.getEncoder().encodeToString(pcm24k) + "')");
    }
    /** The microphone sends nothing while muted. */
    void mute(boolean value) { muted = value; js("mute(" + value + ")"); }
    /** Whether the answer was heard playing in the last half second. */
    boolean playing() { return SystemClock.uptimeMillis() - lastLoud < 500; }

    void close() {
        main.post(() -> {
            if (closed) return;
            closed = true;
            WebSocketClient ws = side; side = null;
            if (ws != null) {
                // close() does nothing before the handshake, so a stalled connect is cut at the socket.
                if (!ws.isOpen()) { java.net.Socket raw = ws.getSocket(); if (raw != null) try { raw.close(); } catch (Exception ignored) {} }
                try { ws.close(); } catch (Exception ignored) {}
            }
            WebView w = web; web = null;
            if (w != null) {
                try { w.evaluateJavascript("hangup()", null); w.removeJavascriptInterface("GyeotLive"); w.loadUrl("about:blank"); w.destroy(); }
                catch (RuntimeException ignored) {}
            }
            route.close();
        });
    }

    /** The call's page: the microphone, the peer, the data channel, the answer's sound and how loud it plays. */
    private static final String PAGE = """
        <!doctype html><html><head><meta charset="utf-8"></head><body><audio id="out" autoplay playsinline></audio><script>
        let pc = null, dc = null, mic = null, queue = [], muted = __MUTED__, lost = 0;
        const bridged = __BRIDGED__; let ctx = null, fed = [], at = 0;
        function fail(e) { try { GyeotLive.failed(String((e && e.message) || e)); } catch (_) {} }
        async function start() {
          if (bridged) {
            // The app's own voice goes up: its PCM plays into a stream of this page, with no microphone of its own.
            ctx = new AudioContext({sampleRate: 24000}); try { await ctx.resume(); } catch (_) {}
            const dest = ctx.createMediaStreamDestination(), feed = ctx.createScriptProcessor(1024, 1, 1), hush = ctx.createGain(); hush.gain.value = 0;
            feed.onaudioprocess = (e) => {
              const out = e.outputBuffer.getChannelData(0); let i = 0;
              while (i < out.length && fed.length) { const head = fed[0], n = Math.min(out.length - i, head.length - at); out.set(head.subarray(at, at + n), i); i += n; at += n; if (at >= head.length) { fed.shift(); at = 0; } }
              out.fill(0, i);
            };
            feed.connect(dest); feed.connect(hush); hush.connect(ctx.destination);
            mic = dest.stream;
          } else {
            try { mic = await navigator.mediaDevices.getUserMedia({audio: {echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1}}); }
            catch (e) { fail('마이크를 열지 못했어요 (' + e.name + ').'); return; }
          }
          const track = mic.getAudioTracks()[0]; track.enabled = !muted;
          pc = new RTCPeerConnection();
          pc.addTrack(track, mic);
          pc.ontrack = (e) => {
            const out = document.getElementById('out'), stream = e.streams[0] || new MediaStream([e.track]); out.srcObject = stream;
            if (bridged) { out.muted = true; out.play().catch(() => {}); tap(stream); } else { out.play().catch(() => {}); watch(stream); }
          };
          pc.onconnectionstatechange = () => {
            if (pc.connectionState === 'failed') fail('음성 연결이 끊겼어요.');
            lost = pc.connectionState === 'disconnected' ? (lost || Date.now()) : 0;
          };
          dc = pc.createDataChannel('oai-events');
          dc.onopen = () => { while (queue.length) dc.send(queue.shift()); GyeotLive.opened(); };
          dc.onmessage = (e) => { if (typeof e.data === 'string') GyeotLive.message(e.data); };
          try {
            await pc.setLocalDescription(await pc.createOffer());
            // ICE gathering normally completes first; the offer goes up after two seconds in any case.
            await new Promise((done) => {
              if (pc.iceGatheringState === 'complete') done();
              pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') done(); };
              setTimeout(done, 2000);
            });
            GyeotLive.offer(pc.localDescription.sdp);
          } catch (e) { fail('음성 연결을 만들지 못했어요.'); }
        }
        function answer(sdp) { pc.setRemoteDescription({type: 'answer', sdp: sdp}).catch(() => fail('음성 연결을 맞추지 못했어요.')); }
        function send(json) { if (dc && dc.readyState === 'open') dc.send(json); else queue.push(json); }
        function mute(value) { muted = value; if (mic) mic.getAudioTracks().forEach((t) => { t.enabled = !value; }); }
        function hangup() { try { if (dc) dc.close(); if (pc) pc.close(); if (mic) mic.getTracks().forEach((t) => t.stop()); if (ctx) ctx.close(); } catch (_) {} }
        // Bridged: the app's 24 kHz PCM (base64) to send; at most about two seconds wait.
        function pcm(b64) {
          if (!bridged) return;
          const raw = atob(b64), n = raw.length >> 1, f = new Float32Array(n);
          for (let i = 0; i < n; i++) { let v = raw.charCodeAt(2 * i) | (raw.charCodeAt(2 * i + 1) << 8); if (v >= 32768) v -= 65536; f[i] = v / 32768; }
          fed.push(f); while (fed.length > 100) { fed.shift(); at = 0; }
        }
        // Bridged: the answer's sound back to the app as 24 kHz PCM, silence left out.
        function tap(stream) {
          const src = ctx.createMediaStreamSource(stream), proc = ctx.createScriptProcessor(2048, 1, 1), hush = ctx.createGain(); hush.gain.value = 0;
          proc.onaudioprocess = (e) => {
            const d = e.inputBuffer.getChannelData(0), bytes = new Uint8Array(d.length * 2); let loud = 0;
            for (let i = 0; i < d.length; i++) { const v = Math.max(-1, Math.min(1, d[i])); loud = Math.max(loud, Math.abs(v)); const x = (v < 0 ? v * 32768 : v * 32767) | 0; bytes[2 * i] = x & 255; bytes[2 * i + 1] = (x >> 8) & 255; }
            if (loud < 0.001) return;
            let bin = ''; for (let i = 0; i < bytes.length; i += 4096) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 4096));
            GyeotLive.pcm(btoa(bin));
          };
          src.connect(proc); proc.connect(hush); hush.connect(ctx.destination);
        }
        function watch(stream) {
          try {
            const ctx = new AudioContext(), analyser = ctx.createAnalyser(); analyser.fftSize = 512;
            ctx.createMediaStreamSource(stream).connect(analyser);
            const samples = new Float32Array(analyser.fftSize);
            setInterval(() => { analyser.getFloatTimeDomainData(samples); let sum = 0; for (const v of samples) sum += v * v; GyeotLive.level(Math.min(1, Math.sqrt(sum / samples.length) * 5)); }, 200);
          } catch (_) {}
        }
        // A connection lost for fifteen seconds ends the call.
        setInterval(() => { if (lost && Date.now() - lost > 15000) { lost = 0; fail('음성 연결이 끊겼어요.'); } }, 1000);
        start();
        </script></body></html>
        """;

    /** The call's sound route: a headset (Bluetooth, then wired) before the phone's speaker; as it was afterwards. */
    private static final class Route {
        private final AudioManager manager; private final Handler handler;
        private int previousMode; private boolean started;
        private final AudioDeviceCallback devices = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] values) { select(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] values) { select(); }
        };
        Route(Context context, Handler handler) { manager = context.getSystemService(AudioManager.class); this.handler = handler; }
        void begin() {
            if (manager == null || started) return;
            try {
                previousMode = manager.getMode(); started = true;
                manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                manager.registerAudioDeviceCallback(devices, handler); select();
            } catch (RuntimeException refused) { /* Android's own route stays. */ }
        }
        static int priority(int type) {
            return switch (type) {
                case AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0;
                case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> 1;
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2;
                default -> 99;
            };
        }
        private void select() {
            if (!started) return;
            try {
                AudioDeviceInfo preferred = manager.getAvailableCommunicationDevices().stream().filter(d -> priority(d.getType()) < 99)
                    .min(Comparator.comparingInt(d -> priority(d.getType()))).orElse(null);
                if (preferred != null && !manager.setCommunicationDevice(preferred)) manager.clearCommunicationDevice();
            } catch (RuntimeException ignored) { /* Android's own route stays. */ }
        }
        void close() {
            if (!started) return;
            started = false;
            try { manager.unregisterAudioDeviceCallback(devices); manager.clearCommunicationDevice(); } catch (RuntimeException ignored) {}
            // A phone call's or another app's new mode is left alone.
            if (manager.getMode() == AudioManager.MODE_IN_COMMUNICATION) manager.setMode(previousMode);
        }
    }
}
