package com.fraudintel.a11yguard;

import android.view.Window;

/** Marker so the SDK never double-wraps a window's callback. */
public interface GuardedCallback {
    Window.Callback getWrapped();
}
