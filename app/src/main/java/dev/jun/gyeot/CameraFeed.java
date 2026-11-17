package dev.jun.gyeot;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import dev.jun.gyeot.core.CameraView;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The camera for 카메라로 보며 대화: a preview on a TextureView, and about once a second the frame on screen, made small and
 * JPEG, handed to frames (base64). Camera2 only, so no library. The camera is open only while a preview is attached; the
 * activity detaches it whenever the 비서 screen goes away. Callbacks to frames and failed come on the main thread.
 */
final class CameraFeed {
    private final Context context;
    private final Consumer<String> frames, failed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("gyeot-camera");
    private final Handler worker;
    private final Executor onWorker;
    private volatile TextureView view;
    private volatile CameraDevice device;
    private volatile CameraCaptureSession session;
    private volatile boolean stopped;
    private Size size;
    private boolean front;
    private final Runnable tick = this::capture;

    CameraFeed(Context context, Consumer<String> frames, Consumer<String> failed) {
        this.context = context.getApplicationContext(); this.frames = frames; this.failed = failed;
        thread.start(); worker = new Handler(thread.getLooper()); onWorker = worker::post;
    }
    boolean front() { return front; }

    /** Shows the preview on this view and starts sending frames once the camera runs. Main thread. */
    void attach(TextureView target) {
        if (stopped) return;
        view = target;
        target.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { if (view == target) open(target); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { if (view == target) transform(target); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { if (view == target) detach(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        if (target.isAvailable()) open(target);
    }
    /** Closes the camera and stops the frames; the feed can be attached again. Main thread. */
    void detach() {
        main.removeCallbacks(tick);
        TextureView old = view; view = null;
        if (old != null) old.setSurfaceTextureListener(null);
        CameraCaptureSession s = session; CameraDevice d = device; session = null; device = null;
        worker.post(() -> {
            try { if (s != null) s.close(); } catch (RuntimeException ignored) {}
            try { if (d != null) d.close(); } catch (RuntimeException ignored) {}
        });
    }
    /** For good: the camera closes and the camera thread ends. */
    void stop() { stopped = true; detach(); thread.quitSafely(); }
    /** Switches between the back and the front camera. */
    void flip() {
        front = !front;
        TextureView target = view;
        detach();
        if (target != null) attach(target);
    }

    private void open(TextureView target) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { fail("카메라 권한이 필요해요."); return; }
        CameraManager manager = context.getSystemService(CameraManager.class);
        try {
            String id = pick(manager, front);
            if (id == null) { fail(front ? "앞 카메라를 찾지 못했어요." : "카메라를 찾지 못했어요."); return; }
            StreamConfigurationMap map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] offered = map == null ? new Size[0] : map.getOutputSizes(SurfaceTexture.class);
            int[][] sizes = new int[offered == null ? 0 : offered.length][];
            for (int i = 0; i < sizes.length; i++) sizes[i] = new int[]{offered[i].getWidth(), offered[i].getHeight()};
            int[] chosen = CameraView.preview(sizes);
            size = new Size(chosen[0], chosen[1]);
            SurfaceTexture texture = target.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Surface surface = new Surface(texture);
            transform(target);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (stopped || view != target) { camera.close(); return; }
                    device = camera;
                    try {
                        camera.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, List.of(new OutputConfiguration(surface)), onWorker,
                            new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession configured) {
                                    if (device != camera) { configured.close(); return; }
                                    session = configured;
                                    try {
                                        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        request.addTarget(surface);
                                        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                        configured.setRepeatingRequest(request.build(), null, worker);
                                        // The first frame once the exposure has settled, then one a second.
                                        main.postDelayed(tick, 800);
                                    } catch (CameraAccessException | RuntimeException e) { fail("카메라 화면을 시작하지 못했어요."); }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession configured) { fail("카메라 화면을 열지 못했어요."); }
                            }));
                    } catch (CameraAccessException | RuntimeException e) { fail("카메라 화면을 열지 못했어요."); }
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); if (device == camera) { device = null; fail("다른 앱이 카메라를 가져갔어요."); } }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close(); if (device == camera) device = null;
                    fail(error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE ? "다른 앱이 카메라를 쓰고 있어요." : error == ERROR_CAMERA_DISABLED ? "이 휴대폰에서 카메라가 막혀 있어요." : "카메라를 열지 못했어요 (" + error + ").");
                }
            }, worker);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) { fail("카메라를 열지 못했어요."); }
    }
    /** The back camera (or the front one when asked); any camera when the phone has only the other kind. */
    private static String pick(CameraManager manager, boolean front) throws CameraAccessException {
        String any = null; int wanted = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == wanted) return id;
            if (any == null) any = id;
        }
        return front ? null : any;
    }
    /** The frame on screen, small and JPEG, off the main thread; the next one in a second. Main thread. */
    private void capture() {
        TextureView target = view;
        if (stopped || target == null) return;
        main.postDelayed(tick, CameraView.INTERVAL_MS);
        if (session == null || !target.isAvailable() || target.getWidth() <= 0) return;
        int[] fit = CameraView.fit(target.getWidth(), target.getHeight());
        Bitmap frame = target.getBitmap(fit[0], fit[1]);
        if (frame == null) return;
        worker.post(() -> {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            frame.compress(Bitmap.CompressFormat.JPEG, CameraView.QUALITY, jpeg);
            frame.recycle();
            String data = Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP);
            main.post(() -> { if (!stopped && view == target) frames.accept(data); });
        });
    }
    /**
     * Turns the preview upright when the phone is held sideways (the camera already rotates it for portrait), scaled to fill
     * the view; the view itself has the preview's shape.
     */
    private void transform(TextureView target) {
        if (size == null || target.getDisplay() == null) return;
        int rotation = target.getDisplay().getRotation(), width = target.getWidth(), height = target.getHeight();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, width, height), bufferRect = new RectF(0, 0, size.getHeight(), size.getWidth());
        float centerX = viewRect.centerX(), centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) height / size.getHeight(), (float) width / size.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) matrix.postRotate(180, centerX, centerY);
        target.setTransform(matrix);
    }
    private void fail(String reason) { main.post(() -> { if (!stopped) failed.accept(reason); }); }
}
