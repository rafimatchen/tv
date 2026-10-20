package com.rafi.glasshome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Screensaver: black screen with a large thin clock and the date. Once a minute it
 * fades out and reappears somewhere else, so nothing stays put long enough to burn in.
 * No network and no continuous animation; the only work happens on the minute tick.
 */
public class GlassDream extends DreamService {
    private final Random random = new Random();
    private FrameLayout root;
    private LinearLayout block;
    private TextView clock, date;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateText();
            drift();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        clock = text(150, 120, 0xE6FFFFFF);
        clock.setLetterSpacing(-0.02f);
        date = text(300, 24, 0x8CFFFFFF);
        block.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        block.addView(date, new LinearLayout.LayoutParams(-2, -2));
        block.setAlpha(0f);
        root.addView(block, new FrameLayout.LayoutParams(-2, -2));
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
        updateText();
        block.post(() -> {
            place();
            block.animate().alpha(1f).setDuration(1500).start();
        });
    }

    @Override
    public void onDreamingStopped() {
        unregisterReceiver(tick);
        super.onDreamingStopped();
    }

    private TextView text(int weight, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(Fonts.get(this, weight));
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    private void updateText() {
        Calendar now = Calendar.getInstance();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), now));
    }

    /** Fade out, move, fade back in. The pause also lets the new text lay out before placing it. */
    private void drift() {
        block.animate().cancel();
        block.animate().alpha(0f).setDuration(800).withEndAction(() -> {
            place();
            block.animate().alpha(1f).setDuration(1500).start();
        }).start();
    }

    private void place() {
        int margin = Math.round(root.getWidth() * 0.06f);
        int rangeX = root.getWidth() - block.getWidth() - 2 * margin;
        int rangeY = root.getHeight() - block.getHeight() - 2 * margin;
        block.setTranslationX(margin + random.nextInt(Math.max(1, rangeX)));
        block.setTranslationY(margin + random.nextInt(Math.max(1, rangeY)));
    }

    @Override
    public void onDetachedFromWindow() {
        block.animate().cancel();
        super.onDetachedFromWindow();
    }
}
