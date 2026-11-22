// COMPILE-ONLY signature header, never packaged. GrapheneOS 2026110200 public API.
// Signature SHA-256: 0ecc8825d00906cbeb37b361bda0ec748ae9a9570c65e58bb0d3fee5825d4bf8
package com.android.extensions.computercontrol;
public final class ComputerControlSession implements AutoCloseable {
public void attachNotificationInfo(int arg0, String arg1) { throw new RuntimeException("Contract stub"); }
public void clearLifecycleCallback() { throw new RuntimeException("Contract stub"); }
public void clearStabilityListener() { throw new RuntimeException("Contract stub"); }
public void close() { throw new RuntimeException("Contract stub"); }
public java.util.List<android.view.accessibility.AccessibilityWindowInfo> getAccessibilityWindows() { throw new RuntimeException("Contract stub"); }
public android.util.Size getDisplaySize() { throw new RuntimeException("Contract stub"); }
public android.media.Image getScreenshot() { throw new RuntimeException("Contract stub"); }
public void handOverApplications() { throw new RuntimeException("Contract stub"); }
public void insertText(String arg0, boolean arg1, boolean arg2) { throw new RuntimeException("Contract stub"); }
public void launchApplication(android.content.ComponentName arg0) { throw new RuntimeException("Contract stub"); }
public void launchApplication(String arg0) { throw new RuntimeException("Contract stub"); }
public void longPress(int arg0, int arg1) { throw new RuntimeException("Contract stub"); }
public void notifyBlocked() { throw new RuntimeException("Contract stub"); }
public void performAction(int arg0) { throw new RuntimeException("Contract stub"); }
public void requestScreenshot(java.util.concurrent.Executor arg0, android.os.OutcomeReceiver<android.media.Image, com.android.extensions.computercontrol.ComputerControlSession.ScreenshotException> arg1, android.os.CancellationSignal arg2) { throw new RuntimeException("Contract stub"); }
public void setLifecycleCallback(java.util.concurrent.Executor arg0, com.android.extensions.computercontrol.ComputerControlSession.LifecycleCallback arg1) { throw new RuntimeException("Contract stub"); }
public void setPreviewIntent(android.app.PendingIntent arg0) { throw new RuntimeException("Contract stub"); }
public void setStabilityListener(java.time.Duration arg0, java.util.concurrent.Executor arg1, com.android.extensions.computercontrol.ComputerControlSession.StabilityListener arg2) { throw new RuntimeException("Contract stub"); }
public void setStabilityListener(java.util.concurrent.Executor arg0, com.android.extensions.computercontrol.ComputerControlSession.StabilityListener arg1) { throw new RuntimeException("Contract stub"); }
public void setStabilityListener(long arg0, java.util.concurrent.Executor arg1, com.android.extensions.computercontrol.ComputerControlSession.StabilityListener arg2) { throw new RuntimeException("Contract stub"); }
public void swipe(int arg0, int arg1, int arg2, int arg3) { throw new RuntimeException("Contract stub"); }
public void tap(int arg0, int arg1) { throw new RuntimeException("Contract stub"); }
public static final int ACTION_GO_BACK = 1; // 0x1
public static final int BLOCK_REASON_CALLER_INITIATED = 3; // 0x3
public static final int BLOCK_REASON_DISALLOWED_ACTIVITY_LAUNCH = 2; // 0x2
public static final int BLOCK_REASON_SECURE_CONTENT = 1; // 0x1
public static final int BLOCK_REASON_UNKNOWN = 0; // 0x0
public static final int CLOSE_REASON_CALLER_INITIATED = 1; // 0x1
public static final int CLOSE_REASON_SESSION_EMPTY = 4; // 0x4
public static final int CLOSE_REASON_SESSION_TIMED_OUT = 3; // 0x3
public static final int CLOSE_REASON_UNKNOWN = 0; // 0x0
public static final int CLOSE_REASON_USER_INITIATED = 2; // 0x2
public static final int ERROR_DEVICE_LOCKED = 2; // 0x2
public static final int ERROR_PERMISSION_DENIED = 3; // 0x3
public static final int ERROR_SESSION_LIMIT_REACHED = 1; // 0x1
public static final int ERROR_UNKNOWN = 0; // 0x0
public static final int UNSTABLE_REASON_CALLER_INTERACTION = 1; // 0x1
public static final int UNSTABLE_REASON_UNKNOWN = 0; // 0x0
public static interface Callback {
public void onSessionClosed();
public void onSessionCreated(com.android.extensions.computercontrol.ComputerControlSession arg0);
public void onSessionCreationFailed(int arg0);
public void onSessionPending(android.content.IntentSender arg0);
}

public static interface LifecycleCallback {
public void onActive();
public void onBlocked(int arg0, String arg1);
public void onClosed(int arg0);
}

public static final class NotificationParams {
public android.app.Notification getNotification() { throw new RuntimeException("Contract stub"); }
public int getNotificationId() { throw new RuntimeException("Contract stub"); }
public String getNotificationTag() { throw new RuntimeException("Contract stub"); }
public static final class Builder {
public Builder(android.app.Notification arg0, int arg1) { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.NotificationParams build() { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.NotificationParams.Builder setNotificationTag(String arg0) { throw new RuntimeException("Contract stub"); }
}

}

public static class Params {
public android.app.AppInteractionAttribution getAppInteractionAttribution() { throw new RuntimeException("Contract stub"); }
public android.companion.DeviceId getCompanionDeviceId() { throw new RuntimeException("Contract stub"); }
public android.content.Context getContext() { throw new RuntimeException("Contract stub"); }
public String getName() { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.NotificationParams getNotificationParams() { throw new RuntimeException("Contract stub"); }
public java.util.List<String> getTargetPackageNames() { throw new RuntimeException("Contract stub"); }
public static class Builder {
public Builder(android.content.Context arg0) { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.Params build() { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.Params.Builder setAppInteractionAttribution(android.app.AppInteractionAttribution arg0) { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.Params.Builder setCompanionDeviceId(android.companion.DeviceId arg0) { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.Params.Builder setName(String arg0) { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.Params.Builder setNotificationParams(com.android.extensions.computercontrol.ComputerControlSession.NotificationParams arg0) { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.Params.Builder setPreviewIntent(android.app.PendingIntent arg0) { throw new RuntimeException("Contract stub"); }
public com.android.extensions.computercontrol.ComputerControlSession.Params.Builder setTargetPackageNames(java.util.List<String> arg0) { throw new RuntimeException("Contract stub"); }
}

}

public static class ScreenshotException extends Exception {
public int getErrorCode() { throw new RuntimeException("Contract stub"); }
public static final int ERROR_CANCELED = 2; // 0x2
public static final int ERROR_DUPLICATE_REQUEST = 6; // 0x6
public static final int ERROR_INTERNAL = 4; // 0x4
public static final int ERROR_PROHIBITED = 3; // 0x3
public static final int ERROR_REMOTE = 5; // 0x5
public static final int ERROR_SCREEN_UNCHANGED = 7; // 0x7
public static final int ERROR_TIMEOUT = 1; // 0x1
public static final int ERROR_UNKNOWN = 0; // 0x0
}

public static interface StabilityListener {
public void onSessionStable();
public default void onSessionUnstable(int arg0) { throw new RuntimeException("Contract stub"); }
}

}
