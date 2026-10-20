package com.rafi.glasshome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

/** An inline route badge (green "6" circle, blue "M15" pill, Citi Bike icon) inside a line of text. */
final class BadgeSpan extends ReplacementSpan {
    private final String label;
    private final Drawable icon;
    private final int color;
    private final boolean round;
    private final Typeface face;
    private final float height, padH, textSize;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    BadgeSpan(String label, Drawable icon, int color, boolean round, Typeface face, float height) {
        this.label = label;
        this.icon = icon;
        this.color = color;
        this.round = round;
        this.face = face;
        this.height = height;
        this.padH = height * 0.28f;
        this.textSize = height * (label != null && label.length() > 2 ? 0.46f : 0.56f);
    }

    private float width() {
        if (round) return height;
        paint.setTypeface(face);
        paint.setTextSize(textSize);
        return paint.measureText(label) + padH * 2;
    }

    @Override
    public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return Math.round(width());
    }

    @Override
    public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint p) {
        float w = width();
        float cy = (top + bottom) / 2f;
        rect.set(x, cy - height / 2, x + w, cy + height / 2);
        paint.setColor(color);
        float radius = round ? height / 2 : height * 0.24f;
        c.drawRoundRect(rect, radius, radius, paint);
        if (icon != null) {
            int pad = Math.round(height * 0.18f);
            icon.setBounds(Math.round(rect.left) + pad, Math.round(rect.top) + pad,
                    Math.round(rect.right) - pad, Math.round(rect.bottom) - pad);
            icon.draw(c);
        } else {
            paint.setColor(0xFFFFFFFF);
            paint.setTypeface(face);
            paint.setTextSize(textSize);
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics m = paint.getFontMetrics();
            c.drawText(label, rect.centerX(), cy - (m.ascent + m.descent) / 2, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }
}
