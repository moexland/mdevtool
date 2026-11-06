package land.moex.nx809j.als;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.RemoteException;
import android.view.WindowManagerGlobal;
import android.window.ScreenCapture;
import android.window.ScreenCaptureInternal;

/**
 * Captures part of a display through the window manager. Stock captured from inside
 * system_server with the physical display token and a private flag that this framework doesn't
 * have; the window manager also captures in system_server, so the result is the same.
 *
 * <p>Secure layers are included, since the sensor sees their light whatever their flags.
 * Protected (DRM) content stays black.
 *
 * <p>Java rather than Kotlin because CaptureArgs.Builder takes its own subclass as a type
 * parameter, which Kotlin can't instantiate directly.
 */
final class ScreenCapturer {
    private ScreenCapturer() {}

    /** Returns [crop] of the display, in display coordinates, as a software bitmap, or null. */
    static Bitmap capture(int displayId, Rect crop) throws RemoteException {
        ScreenCaptureInternal.CaptureArgs args = new ScreenCaptureInternal.CaptureArgs.Builder<>()
                .setSourceCrop(crop)
                .setSecureContentPolicy(
                        ScreenCapture.ScreenCaptureParams.SECURE_CONTENT_POLICY_CAPTURE)
                .setIncludeSystemOverlays(true)
                .build();
        ScreenCaptureInternal.SynchronousScreenCaptureListener listener =
                ScreenCaptureInternal.createSyncCaptureListener();
        WindowManagerGlobal.getWindowManagerService().captureDisplay(displayId, args, listener);
        ScreenCaptureInternal.ScreenshotHardwareBuffer screenshot = listener.getBuffer();
        if (screenshot == null) {
            return null;
        }
        try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
            Bitmap hardware = screenshot.asBitmap();
            if (hardware == null) {
                return null;
            }
            Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false);
            hardware.recycle();
            return software;
        }
    }
}
