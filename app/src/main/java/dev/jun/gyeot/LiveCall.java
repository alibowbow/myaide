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
import java.util.Comparator;
import org.json.JSONObject;

/**
 * One GPT-Live call over WebRTC, as the Codex apps make it, on the phone's own WebView (its WebRTC, echo cancelling and
 * noise suppression): the microphone goes up and the answer plays as a call (a headset first, else the speaker), the SDP
 * offer is answered by the server (Signal), and the call's events come on the "oai-events" data channel. The page is this
 * class's own and goes nowhere else; only it may use the microphone. Main thread, except the listener (any thread).
 */
final class LiveCall {
    interface Listener {
        /** The data channel is open: events can go both ways. */
        void opened();
        /** One event from the server (JSON text). */
        void message(String json);
        /** The call could not go on; status is the server's HTTP status when it turned the call down (0 otherwise). */
        void failed(String why, int status);
    }
    /** Sends the offer and returns the server's answer SDP; blocking, off the main thread. */
    interface Signal { String answer(String offer) throws Exception; }
    /** The server turned the call down with this HTTP status. */
    static final class Refused extends Exception { final int status; Refused(String message, int status) { super(message); this.status = status; } }

    private static final String ORIGIN = "https://live.gyeot.local/";
    private final Context context; private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Route route;
    private WebView web; private Signal signal;
    private volatile boolean closed, failed, muted;
    /** When the answer was last heard playing. */
    private volatile long lastLoud;

    LiveCall(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        route = new Route(this.context, main);
    }
    private void fail(String why, int status) {
        if (closed || failed) return;
        failed = true; listener.failed(why == null || why.isBlank() ? "음성 연결이 끊겼어요." : why, status);
    }

    /** Opens the call's page: it asks for the microphone, makes the offer, and signal answers it. */
    void start(Signal signal) {
        this.signal = signal;
        main.post(() -> {
            if (closed) return;
            route.begin();
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
            web.loadDataWithBaseURL(ORIGIN, PAGE.replace("__MUTED__", String.valueOf(muted)), "text/html", "utf-8", null);
        });
    }
    /** What the page tells (on WebView's bridge thread). */
    private final class Bridge {
        @JavascriptInterface public void offer(String sdp) {
            if (closed) return;
            Thread post = new Thread(() -> {
                try {
                    String answer = signal.answer(sdp);
                    if (answer == null || !answer.startsWith("v=")) { fail("GPT-Live가 연결 답을 주지 않았어요.", 0); return; }
                    js("answer(" + JSONObject.quote(answer) + ")");
                } catch (Refused refused) { fail(refused.getMessage(), refused.status); }
                catch (Exception error) { fail(error.getMessage() == null ? "GPT-Live에 연결하지 못했어요." : error.getMessage(), 0); }
            }, "gyeot-live-offer");
            post.setDaemon(true); post.start();
        }
        @JavascriptInterface public void opened() { if (!closed) listener.opened(); }
        @JavascriptInterface public void message(String json) { if (!closed && json != null) listener.message(json); }
        @JavascriptInterface public void failed(String why) { fail(why, 0); }
        @JavascriptInterface public void level(double value) { if (value > 0.04) lastLoud = SystemClock.uptimeMillis(); }
    }
    private void js(String script) { main.post(() -> { if (!closed && web != null) web.evaluateJavascript(script, null); }); }

    /** An event for the server; the page keeps it until the data channel opens. */
    void send(String json) { js("send(" + JSONObject.quote(json) + ")"); }
    /** The microphone sends nothing while muted. */
    void mute(boolean value) { muted = value; js("mute(" + value + ")"); }
    /** Whether the answer was heard playing in the last half second. */
    boolean playing() { return SystemClock.uptimeMillis() - lastLoud < 500; }

    void close() {
        main.post(() -> {
            if (closed) return;
            closed = true;
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
        function fail(e) { try { GyeotLive.failed(String((e && e.message) || e)); } catch (_) {} }
        async function start() {
          try { mic = await navigator.mediaDevices.getUserMedia({audio: {echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1}}); }
          catch (e) { fail('마이크를 열지 못했어요 (' + e.name + ').'); return; }
          const track = mic.getAudioTracks()[0]; track.enabled = !muted;
          pc = new RTCPeerConnection();
          pc.addTrack(track, mic);
          pc.ontrack = (e) => { const out = document.getElementById('out'); out.srcObject = e.streams[0] || new MediaStream([e.track]); out.play().catch(() => {}); watch(out.srcObject); };
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
        function hangup() { try { if (dc) dc.close(); if (pc) pc.close(); if (mic) mic.getTracks().forEach((t) => t.stop()); } catch (_) {} }
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
