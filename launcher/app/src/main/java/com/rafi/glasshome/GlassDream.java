package com.rafi.glasshome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Screensaver: the live sky over Manhattan (SkyView) with the time, the weather, and the
 * next 6 train, M15 bus and nearby Citi Bikes. Trains and buses you can no longer make
 * (sooner than the walk there) are left out; the next one turns amber when it's time to go.
 */
public class GlassDream extends DreamService implements Feeds.Listener, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int BRIGHT = 0xF2FFFFFF, DIM = 0x99FFFFFF, FAINT = 0x66FFFFFF, AMBER = 0xFFFFB547;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private float dp;
    private SkyView sky;
    private FrameLayout info;
    private TextView clock, date, weatherLine, sportsLine, subwayLine, busLine, bus3Line, bikeLine;
    private Feeds feeds;
    private SharedPreferences prefs;
    private Typeface regular, medium;
    /** Arrivals already turned into street events, keyed by kind and minute. */
    private final java.util.Set<Long> scheduled = new java.util.HashSet<>();

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
            renderTransit();
        }
    };

    /** Countdowns change between feed refreshes, so re-render every 20 s. */
    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            renderTransit();
            handler.postDelayed(this, 20_000);
        }
    };

    /** Nudge the text a few dp every few minutes so nothing sits in one place for hours. */
    private final Runnable shift = new Runnable() {
        @Override
        public void run() {
            info.animate().translationX((random.nextFloat() - 0.5f) * 20 * dp)
                    .translationY((random.nextFloat() - 0.5f) * 16 * dp).setDuration(3000).start();
            handler.postDelayed(this, 4 * 60_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        dp = getResources().getDisplayMetrics().density;
        regular = Fonts.get(this, 300);
        medium = Fonts.get(this, 500);
        prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        sky = new SkyView(this);
        root.addView(sky, new FrameLayout.LayoutParams(-1, -1));
        info = new FrameLayout(this);
        root.addView(info, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        clock = text(150, 108);
        clock.setLetterSpacing(-0.02f);
        date = text(300, 22);
        weatherLine = text(300, 20);
        top.addView(clock, wrap());
        top.addView(date, wrap());
        LinearLayout.LayoutParams wl = wrap();
        wl.topMargin = px(6);
        top.addView(weatherLine, wl);
        sportsLine = text(300, 20);
        LinearLayout.LayoutParams spl = wrap();
        spl.topMargin = px(6);
        top.addView(sportsLine, spl);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.leftMargin = px(64);
        tl.topMargin = px(40);
        info.addView(top, tl);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        subwayLine = row(bottom, subwayBadge());
        busLine = row(bottom, busBadge(getString(R.string.bus_route)));
        bus3Line = row(bottom, busBadge(getString(R.string.bus3_badge)));
        bikeLine = row(bottom, bikeBadge());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        bl.leftMargin = px(64);
        bl.bottomMargin = px(74); // above the street trees, the FDR and the river
        info.addView(bottom, bl);

        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        prefs.registerOnSharedPreferenceChangeListener(this);
        updateClock();
        onFeedsUpdated(); // whatever was cached from last time, straight away
        feeds = new Feeds(this);
        feeds.start(this);
        handler.postDelayed(countdown, 20_000);
        handler.postDelayed(shift, 4 * 60_000);
    }

    @Override
    public void onDreamingStopped() {
        feeds.stop();
        handler.removeCallbacksAndMessages(null);
        scheduled.clear();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        unregisterReceiver(tick);
        super.onDreamingStopped();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (ConfigReceiver.KEY_PREVIEW_MOOD.equals(key) || ConfigReceiver.KEY_PREVIEW_HOUR.equals(key)) {
            onFeedsUpdated();
        } else if (ConfigReceiver.KEY_PREVIEW_EVENT.equals(key)) {
            String ev = sp.getString(key, "");
            if (ev.startsWith("bus")) sky.busArriving();
            else if (ev.startsWith("train")) sky.trainArriving();
            else if (ev.startsWith("fireworks")) sky.previewFireworks();
            else if (ev.startsWith("meteor")) sky.previewMeteor();
        }
    }

    // ---------------------------------------------------------------- data -> screen

    @Override
    public void onFeedsUpdated() {
        Feeds.Weather w = Feeds.weather;
        if (w != null) {
            sky.setSun(w.sunrise, w.sunset);
            sky.setWind(w.windKmh, w.windFromDeg);
            sky.setSnowCover(w.snowDepthM > 0.01);
        }
        int mood = w != null ? SkyView.moodFor(w.code, w.cloudCover) : SkyView.CLEAR;

        String preview = prefs.getString(ConfigReceiver.KEY_PREVIEW_MOOD, null);
        if (preview != null) {
            mood = previewMood(preview, mood);
            float hour = prefs.getFloat(ConfigReceiver.KEY_PREVIEW_HOUR, -1f);
            String date = prefs.getString(ConfigReceiver.KEY_PREVIEW_DATE, null);
            if (hour >= 0) {
                Calendar c = Calendar.getInstance();
                if (date != null && date.matches("\\d{1,2}-\\d{1,2}")) {
                    String[] md = date.split("-");
                    c.set(Calendar.MONTH, Integer.parseInt(md[0]) - 1);
                    c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(md[1]));
                }
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0);
                sky.setFakeNow(c.getTimeInMillis() / 1000 + (long) (hour * 3600));
            } else {
                sky.setFakeNow(0);
            }
        } else {
            sky.setFakeNow(0);
        }
        sky.setMood(mood);

        if (w != null) {
            weatherLine.setText(celsius(w.temp) + "°C / " + Math.round(w.temp) + "°F   " + SkyView.moodLabel(mood)
                    + "   ·   H " + celsius(w.high) + " / " + Math.round(w.high) + "°   L "
                    + celsius(w.low) + " / " + Math.round(w.low) + "°");
            weatherLine.setVisibility(View.VISIBLE);
        } else {
            weatherLine.setVisibility(View.INVISIBLE);
        }
        renderSports();
        renderTransit();
    }

    /** Game days: a line under the weather and a lit sign on a rooftop. */
    private void renderSports() {
        Sports.Game g = Sports.headline();
        if (g == null) {
            sportsLine.setVisibility(View.GONE);
            sky.setSign(null, 0);
            return;
        }
        boolean live = "in".equals(g.state);
        SpannableStringBuilder s = new SpannableStringBuilder();
        span(s, "\u25CF  ", brighten(g.color), regular);
        span(s, Sports.describe(g, true), live ? BRIGHT : DIM, live ? medium : regular);
        sportsLine.setText(s);
        sportsLine.setVisibility(View.VISIBLE);
        String sign = live || "post".equals(g.state)
                ? g.us + " " + g.usScore + "  " + g.them + " " + g.themScore
                : g.us + " vs " + g.them;
        sky.setSign(sign.toUpperCase(Locale.US), live ? 0xFFFFC857 : 0xFFDDE6FF);
    }

    /** Team navies vanish on a night sky; lift dark colours so the dot stays visible. */
    private static int brighten(int c) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[2] = Math.max(hsv[2], 0.85f);
        hsv[1] = Math.min(hsv[1], 0.75f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    private static long celsius(double fahrenheit) {
        return Math.round((fahrenheit - 32) * 5 / 9);
    }

    private static int previewMood(String name, int fallback) {
        switch (name) {
            case "clear": return SkyView.CLEAR;
            case "partly": return SkyView.PARTLY;
            case "cloudy": return SkyView.OVERCAST;
            case "fog": return SkyView.FOG;
            case "drizzle": return SkyView.DRIZZLE;
            case "rain": return SkyView.RAIN;
            case "snow": return SkyView.SNOW;
            case "storm": return SkyView.STORM;
            default: return fallback; // "live": real weather, previewed time
        }
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), now));
    }

    private void renderTransit() {
        long now = Feeds.now();
        int subwayWalk = getResources().getInteger(R.integer.subway_walk_min);
        int busWalk = getResources().getInteger(R.integer.bus_walk_min);

        SpannableStringBuilder s = new SpannableStringBuilder();
        span(s, getString(R.string.subway_stop_label), DIM, regular);
        s.append("     ");
        Feeds.Arrivals sub = Feeds.subway;
        if (!fresh(sub, now)) {
            span(s, sub == null ? "…" : "no data", FAINT, regular);
        } else {
            direction(s, "Uptown", sub.uptown, subwayWalk, now);
            s.append("      ");
            direction(s, "Downtown", sub.downtown, subwayWalk, now);
        }
        subwayLine.setText(s);

        s = new SpannableStringBuilder();
        span(s, getString(R.string.bus_stop_label), DIM, regular);
        s.append("     ");
        // Both run downtown from the same corner: local and Select Bus side by side.
        Feeds.Arrivals bus = Feeds.bus, sbs = Feeds.busSbs;
        if (!fresh(bus, now) && !fresh(sbs, now)) {
            span(s, bus == null && sbs == null ? "…" : "no data", FAINT, regular);
        } else {
            span(s, "Downtown   ", DIM, regular); // 2nd Ave runs south
            direction(s, "Local", fresh(bus, now) ? bus.downtown : new long[0], busWalk, now);
            s.append("      ");
            direction(s, "SBS", fresh(sbs, now) ? sbs.downtown : new long[0], busWalk, now);
        }
        busLine.setText(s);

        s = new SpannableStringBuilder();
        span(s, getString(R.string.bus3_stop_label), DIM, regular);
        s.append("     ");
        Feeds.Arrivals third = Feeds.busThird;
        if (!fresh(third, now)) span(s, third == null ? "…" : "no data", FAINT, regular);
        else direction(s, "Uptown", third.uptown, getResources().getInteger(R.integer.bus3_walk_min), now);
        bus3Line.setText(s);

        s = new SpannableStringBuilder();
        String[] labels = getResources().getStringArray(R.array.bike_station_labels);
        Feeds.Station[] st = Feeds.bikes;
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) s.append("      ");
            span(s, labels[i], DIM, regular);
            s.append("  ");
            Feeds.Station x = st != null && i < st.length ? st[i] : null;
            if (x == null) {
                span(s, st == null ? "…" : "—", FAINT, regular);
            } else if (!x.renting) {
                span(s, "closed", FAINT, regular);
            } else {
                // GBFS counts e-bikes inside num_bikes_available, so classic = total - electric.
                int classic = Math.max(0, x.bikes - x.ebikes);
                span(s, String.valueOf(classic), x.bikes == 0 ? AMBER : BRIGHT, medium);
                s.append("  ");
                bolt(s);
                span(s, String.valueOf(x.ebikes), x.bikes == 0 ? AMBER : BRIGHT, medium);
            }
        }
        bikeLine.setText(s);
        if (st != null && st.length > 0 && st[0] != null) sky.setDockBikes(st[0].bikes);

        // Street events timed to real arrivals: the subway entrance glows and lets people
        // out when a 6 is due; an M15 pulls into its stop when a bus is arriving.
        scheduled.removeIf(k -> (k % 100_000_000L) < now / 60 - 5);
        if (fresh(sub, now)) {
            scheduleEvent(1, sub.uptown, now, sky::trainArriving);
            scheduleEvent(1, sub.downtown, now, sky::trainArriving);
        }
        if (fresh(bus, now)) scheduleEvent(2, bus.downtown, now, sky::busArriving);
        if (fresh(sbs, now)) scheduleEvent(3, sbs.downtown, now, sky::busArriving);
        if (fresh(third, now)) scheduleEvent(4, third.uptown, now, sky::busArriving);
    }

    private void scheduleEvent(int kind, long[] times, long now, Runnable event) {
        for (long t : times) {
            long in = t - now;
            if (in < 0 || in > 25) continue;
            if (scheduled.add(kind * 100_000_000L + t / 60)) handler.postDelayed(event, in * 1000);
        }
    }

    private static boolean fresh(Feeds.Arrivals a, long now) {
        return Feeds.fresh(a, now);
    }

    /** "Uptown  10 · 14 min", hiding departures you can't reach; amber + "leave now" when it's time. */
    private void direction(SpannableStringBuilder s, String label, long[] times, int walkMin, long now) {
        span(s, label + "  ", DIM, regular);
        int shown = 0;
        for (long t : times) {
            int min = (int) ((t - now) / 60);
            if (min < walkMin || min > 60) continue;
            if (shown == 0) {
                boolean go = min - walkMin <= 1;
                span(s, String.valueOf(min), go ? AMBER : BRIGHT, medium);
                if (go) {
                    int start = s.length();
                    span(s, " leave now", AMBER, regular);
                    s.setSpan(new RelativeSizeSpan(0.8f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                span(s, "  ·  " + min, DIM, regular);
            }
            if (++shown == 2) break;
        }
        if (shown == 0) span(s, "—", FAINT, regular);
        else span(s, " min", FAINT, regular);
    }

    private static void span(SpannableStringBuilder s, String text, int color, Typeface face) {
        int start = s.length();
        s.append(text);
        s.setSpan(new ForegroundColorSpan(color), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new TypefaceSpan(face), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void bolt(SpannableStringBuilder s) {
        Icons d = new Icons(Icons.BOLT, DIM);
        int size = px(13);
        d.setBounds(0, 0, size, size);
        int start = s.length();
        s.append(" ");
        s.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---------------------------------------------------------------- views

    private TextView text(int weight, float sp) {
        TextView t = new TextView(this);
        t.setTypeface(Fonts.get(this, weight));
        t.setTextSize(sp);
        t.setTextColor(BRIGHT);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        // A soft shadow keeps white text readable on a bright daytime sky.
        t.setShadowLayer(8 * dp, 0, dp, 0x73000000);
        return t;
    }

    private TextView row(LinearLayout parent, View badge) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout slot = new FrameLayout(this);
        FrameLayout.LayoutParams blp = badge.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) badge.getLayoutParams() : new FrameLayout.LayoutParams(-2, -2);
        blp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        slot.addView(badge, blp);
        row.addView(slot, new LinearLayout.LayoutParams(px(50), px(30)));
        TextView t = text(300, 19);
        row.addView(t, wrap());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(5);
        parent.addView(row, lp);
        return t;
    }

    private View subwayBadge() {
        TextView b = badgeText(getString(R.string.subway_route), 15);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF00933C); // MTA Lexington Ave line green
        b.setBackground(bg);
        b.setLayoutParams(new FrameLayout.LayoutParams(px(28), px(28)));
        return b;
    }

    private View busBadge(String label) {
        TextView b = badgeText(label, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(6 * dp);
        bg.setColor(0xFF1C5BB8);
        b.setBackground(bg);
        b.setPadding(px(6), px(4), px(6), px(4));
        return b;
    }

    private View bikeBadge() {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new Icons(Icons.BIKE, 0xFFFFFFFF));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF0A6FD1); // Citi Bike blue
        v.setBackground(bg);
        v.setPadding(px(5), px(5), px(5), px(5));
        v.setLayoutParams(new FrameLayout.LayoutParams(px(28), px(28)));
        return v;
    }

    private TextView badgeText(String label, float sp) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTypeface(Fonts.get(this, 600));
        b.setTextSize(sp);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        return b;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }
}
