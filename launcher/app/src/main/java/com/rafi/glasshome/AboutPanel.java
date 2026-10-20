package com.rafi.glasshome;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

/**
 * Glass card with what Glass Home is doing: version, the health of each live data source,
 * memory, and the TV settings it relies on. Opened by long-pressing OK on the Settings pill
 * (or the Info/Menu key); any key closes it. Refreshes every 2 s while open.
 */
final class AboutPanel {
    private static final int BRIGHT = 0xF2FFFFFF, DIM = 0x99FFFFFF, GOOD = 0xFF30D158, WARN = 0xFFFFB547;

    private final Context context;
    private final FrameLayout host;
    private final float dp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout card, rows;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            fill();
            handler.postDelayed(this, 2000);
        }
    };

    AboutPanel(Context context, FrameLayout host) {
        this.context = context;
        this.host = host;
        this.dp = context.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return card != null;
    }

    void show() {
        if (card != null) return;
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = px(28);
        card.setPadding(pad, px(24), pad, px(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(26 * dp);
        bg.setColor(0xF51C1C20);
        bg.setStroke(Math.max(1, px(1)), 0x2EFFFFFF);
        card.setBackground(bg);

        TextView title = text(300, 30, BRIGHT);
        title.setText("Glass Home " + version());
        card.addView(title);
        TextView sub = text(400, 15, DIM);
        sub.setText(Build.MANUFACTURER + " " + Build.MODEL + "  ·  Android " + Build.VERSION.RELEASE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.bottomMargin = px(14);
        card.addView(sub, sl);

        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        card.addView(rows);
        TextView hint = text(400, 13, 0x66FFFFFF);
        hint.setText("Press any key to close");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = px(14);
        card.addView(hint, hl);

        host.addView(card, new FrameLayout.LayoutParams(px(600), -2, Gravity.CENTER));
        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new DecelerateInterpolator(2f)).start();
        handler.post(refresh);
    }

    void dismiss() {
        if (card == null) return;
        handler.removeCallbacks(refresh);
        View c = card;
        card = null;
        c.animate().alpha(0f).setDuration(140).withEndAction(() -> host.removeView(c)).start();
    }

    private void fill() {
        rows.removeAllViews();
        section("Live data");
        long now = Feeds.now();
        String busKey = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
                .getString(ConfigReceiver.KEY_BUS_KEY, "");
        for (Map.Entry<String, Feeds.Status> e : Feeds.STATUS.entrySet()) {
            Feeds.Status st = e.getValue();
            String name = e.getKey();
            if (name.equals("Buses")) name += busKey.isEmpty() ? " (keyless)" : " (MTA key)";
            String value;
            int color;
            if (st.lastAttempt == 0) {
                value = "not fetched yet";
                color = DIM;
            } else if (st.lastError == null) {
                value = "updated " + ago(now - st.lastOk) + " · " + String.format(java.util.Locale.US, "%.1f s", st.lastMillis / 1000f);
                color = GOOD;
            } else {
                value = st.lastError + (st.lastOk > 0 ? " · last good " + ago(now - st.lastOk) : "");
                color = WARN;
            }
            row(name, value, color);
        }

        section("This TV");
        ActivityManager am = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        row("Memory", "Glass Home " + Debug.getPss() / 1024 + " MB · TV free " + mi.availMem / (1024 * 1024) + " MB", BRIGHT);

        String saver = Settings.Secure.getString(context.getContentResolver(), "screensaver_components");
        long idle = Settings.System.getLong(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
        boolean ours = saver != null && saver.startsWith(context.getPackageName());
        row("Screensaver", (ours ? "Glass Home sky" : saver == null ? "none" : "other: " + saver)
                + (idle > 0 ? " · after " + idle / 60000 + " min" : ""), ours ? BRIGHT : WARN);

        String dnsMode = Settings.Global.getString(context.getContentResolver(), "private_dns_mode");
        String dnsHost = Settings.Global.getString(context.getContentResolver(), "private_dns_specifier");
        boolean adblock = "hostname".equals(dnsMode) && dnsHost != null;
        row("Ad-blocking DNS", adblock ? "on · " + dnsHost : "off", adblock ? BRIGHT : DIM);

        AccessibilityManager acc = context.getSystemService(AccessibilityManager.class);
        boolean buttons = false;
        for (AccessibilityServiceInfo info : acc.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(context.getPackageName())) buttons = true;
        }
        row("Remote buttons", buttons ? "on · YouTube and Prime keys" : "off · Settings → Accessibility", buttons ? BRIGHT : WARN);
    }

    private static String ago(long sec) {
        if (sec < 60) return sec + " s ago";
        if (sec < 3600) return sec / 60 + " min ago";
        return sec / 3600 + " h ago";
    }

    private String version() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void section(String label) {
        TextView t = text(500, 13, 0x80FFFFFF);
        t.setText(label.toUpperCase(java.util.Locale.US));
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(rows.getChildCount() == 0 ? 0 : 14);
        lp.bottomMargin = px(4);
        rows.addView(t, lp);
    }

    private void row(String label, String value, int valueColor) {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text(400, 16, DIM);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(px(190), -2));
        TextView v = text(400, 16, valueColor);
        v.setText(value);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(5);
        rows.addView(r, lp);
    }

    private TextView text(int weight, float sp, int color) {
        TextView t = new TextView(context);
        t.setTypeface(Fonts.get(context, weight));
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }
}
