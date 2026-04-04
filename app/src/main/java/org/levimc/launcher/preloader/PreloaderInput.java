package org.levimc.launcher.preloader;

public class PreloaderInput {
    public static native boolean nativeOnTouch(int action, int pointerId, float x, float y);
    public static native boolean nativeOnKeyChar(int unicodeChar);
    public static native boolean nativeOnKeyDown(int keyCode);
    public static native void nativeSetActivity(Object activity);
    public static native void nativeClearActivity();

    public static boolean onTouch(int action, int pointerId, float x, float y) {
        try {
            return nativeOnTouch(action, pointerId, x, y);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean onKeyChar(int unicodeChar) {
        try {
            return nativeOnKeyChar(unicodeChar);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean onKeyDown(int keyCode) {
        try {
            return nativeOnKeyDown(keyCode);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static void setActivity(Object activity) {
        try {
            nativeSetActivity(activity);
        } catch (UnsatisfiedLinkError e) {
        }
    }

    public static void clearActivity() {
        try {
            nativeClearActivity();
        } catch (UnsatisfiedLinkError e) {
        }
    }
}

