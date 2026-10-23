package com.rafi.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Home-screen "now playing" card: cover, title and artist, a small equalizer that dances while
 * music plays, a thin progress line, and a tint taken from the cover. It only animates while
 * it's on screen and playing (about 12 frames a second for the bars; the progress line moves
 * once a second).
 */
final class NowPlayingCard extends FrameLayout {
    private final float dp;
    private final ImageView art;
    private final TextView label, title, artist;
    private final Equalizer eq;
    private final View progress, track;
    private final GradientDrawable tint;
    private NowPlaying.Info info;
    private int accent = 0xFF1ED760;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (info != null && info.playing) postDelayed(this, 1000);
        }
    };

    NowPlayingCard(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(20 * dp);
        glass.setColor(0xD91A1A1E);
        glass.setStroke(Math.max(1, px(1)), 0x24FFFFFF);
        // Cover-coloured wash fading from left to right, layered on the glass. (Part of the
        // background rather than a child view, which would stretch the card to full height.)
        tint = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0, 0});
        tint.setCornerRadius(20 * dp);
        setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{glass, tint}));
        setClipToOutline(true);
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(12), px(12), px(16), px(12));
        addView(row, new LayoutParams(-1, -2));

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0xFF2A2A2E);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 10 * dp);
            }
        });
        art.setClipToOutline(true);
        row.addView(art, new LinearLayout.LayoutParams(px(60), px(60)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = px(14);
        row.addView(col, cl);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        eq = new Equalizer(c);
        head.addView(eq, new LinearLayout.LayoutParams(px(13), px(11)));
        label = text(600, 10.5f, 0xB3FFFFFF);
        label.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.leftMargin = px(7);
        head.addView(label, ll);
        col.addView(head);

        title = text(500, 17, 0xF2FFFFFF);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = px(5);
        col.addView(title, tl);
        artist = text(300, 14, 0x99FFFFFF);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = px(2);
        col.addView(artist, al);

        FrameLayout bar = new FrameLayout(c);
        track = new View(c);
        track.setBackgroundColor(0x26FFFFFF);
        bar.addView(track, new LayoutParams(-1, px(2)));
        progress = new View(c);
        bar.addView(progress, new LayoutParams(0, px(2)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, px(2));
        bl.topMargin = px(8);
        col.addView(bar, bl);

        setAlpha(0f);
        setVisibility(GONE);
    }

    /** Show a track, or fade away when nothing is playing. */
    void bind(NowPlaying.Info next) {
        boolean wasShown = info != null;
        info = next;
        removeCallbacks(tick);
        if (next == null) {
            eq.setPlaying(false);
            if (wasShown) animate().alpha(0f).setDuration(300).withEndAction(() -> setVisibility(GONE)).start();
            return;
        }
        title.setText(next.title);
        artist.setText(next.artist);
        label.setText(next.playing ? "NOW PLAYING" : "PAUSED");
        if (next.art != null) {
            art.setImageBitmap(next.art);
            accent = brighten(AppInfo.sampleGlow(new BitmapDrawable(getResources(), next.art)));
        } else {
            art.setImageDrawable(new Icons(Icons.DOT, 0x33FFFFFF));
            accent = 0xFF1ED760; // Spotify green
        }
        tint.setColors(new int[]{(accent & 0x00FFFFFF) | 0x38000000, (accent & 0x00FFFFFF) | 0x08000000});
        eq.setColor(accent);
        progress.setBackgroundColor(accent);
        eq.setPlaying(next.playing);
        updateProgress();
        if (next.playing) postDelayed(tick, 1000);
        float target = next.playing ? 1f : 0.6f; // paused: dimmed
        if (!wasShown) {
            setVisibility(VISIBLE);
            animate().alpha(target).setDuration(350).start();
        } else {
            animate().alpha(target).setDuration(250).start();
        }
    }

    private void updateProgress() {
        if (info == null || info.duration <= 0 || track.getWidth() == 0) {
            progress.getLayoutParams().width = 0;
        } else {
            progress.getLayoutParams().width = (int) (track.getWidth() * Math.min(1f, info.positionNow() / (float) info.duration));
        }
        progress.requestLayout();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private static int brighten(int c) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    private TextView text(int weight, float sp, int color) {
        TextView t = new TextView(getContext());
        t.setTypeface(Fonts.get(getContext(), weight));
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }

    /** Three little bars bobbing to an imaginary beat; flat while paused. */
    private static final class Equalizer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean playing;
        private final long start = android.os.SystemClock.uptimeMillis();

        Equalizer(Context c) {
            super(c);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setColor(int c) {
            paint.setColor(c);
            invalidate();
        }

        void setPlaying(boolean p) {
            playing = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float bw = w / 5f;
            paint.setStrokeWidth(bw);
            float t = (android.os.SystemClock.uptimeMillis() - start) / 1000f;
            for (int i = 0; i < 3; i++) {
                float level = playing
                        ? 0.3f + 0.7f * (float) Math.abs(Math.sin(t * (2.3f + i * 0.9f) + i * 1.7f))
                        : 0.25f;
                float x = bw / 2 + i * bw * 2;
                c.drawLine(x, h - bw / 2, x, h - bw / 2 - (h - bw) * level, paint);
            }
            if (playing && isShown()) postInvalidateDelayed(80); // ~12 fps, only while visible
        }
    }
}
