package com.rafi.glasshome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remote app buttons (YouTube, Prime Video, ...) on this Google TV are routed to the
 * stock launcher, which is disabled. This service sees keys before that routing
 * and opens the app itself. Mapping: res/values/config.xml "remote_buttons".
 * It reads key presses only; it never looks at screen content.
 */
public class RemoteButtonService extends AccessibilityService {
    private final SparseArray<String> buttons = new SparseArray<>();

    @Override
    protected void onServiceConnected() {
        buttons.clear();
        for (String entry : getResources().getStringArray(R.array.remote_buttons)) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            try {
                buttons.put(Integer.parseInt(entry.substring(0, eq).trim()), entry.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {
                // Skip malformed lines.
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String pkg = buttons.get(event.getKeyCode());
        if (pkg == null) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // App disabled or gone; swallow the key either way.
                }
            }
        }
        return true; // consume down and up so the dead stock route never sees it
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: the service only filters key events.
    }

    @Override
    public void onInterrupt() {
    }
}
