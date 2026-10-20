package com.rafi.glasshome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small line icons drawn in code, so there are no image assets to ship. */
final class Icons extends Drawable {
    static final int INPUT = 0, WIFI = 1, SETTINGS = 2, DOT = 3;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    Icons(int kind, int color) {
        this.kind = kind;
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        float x0 = b.left + (b.width() - s) / 2f, y0 = b.top + (b.height() - s) / 2f;
        paint.setStrokeWidth(s * 0.1f);
        switch (kind) {
            case INPUT: {
                // A screen with an arrow coming in from the left.
                paint.setStyle(Paint.Style.STROKE);
                r.set(x0 + s * 0.30f, y0 + s * 0.18f, x0 + s * 0.94f, y0 + s * 0.74f);
                c.drawRoundRect(r, s * 0.08f, s * 0.08f, paint);
                c.drawLine(x0 + s * 0.48f, y0 + s * 0.90f, x0 + s * 0.76f, y0 + s * 0.90f, paint);
                c.drawLine(x0 + s * 0.06f, y0 + s * 0.46f, x0 + s * 0.58f, y0 + s * 0.46f, paint);
                c.drawLine(x0 + s * 0.44f, y0 + s * 0.32f, x0 + s * 0.58f, y0 + s * 0.46f, paint);
                c.drawLine(x0 + s * 0.44f, y0 + s * 0.60f, x0 + s * 0.58f, y0 + s * 0.46f, paint);
                break;
            }
            case WIFI: {
                paint.setStyle(Paint.Style.STROKE);
                float cx = x0 + s / 2f, cy = y0 + s * 0.84f;
                for (float k : new float[]{0.30f, 0.56f, 0.82f}) {
                    float rad = s * k;
                    r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                    c.drawArc(r, 225, 90, false, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy - s * 0.02f, s * 0.08f, paint);
                break;
            }
            case SETTINGS: {
                paint.setStyle(Paint.Style.STROKE);
                float cx = x0 + s / 2f, cy = y0 + s / 2f;
                c.drawCircle(cx, cy, s * 0.26f, paint);
                c.drawCircle(cx, cy, s * 0.09f, paint);
                paint.setStrokeWidth(s * 0.14f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    c.drawLine(cx + cos * s * 0.36f, cy + sin * s * 0.36f, cx + cos * s * 0.43f, cy + sin * s * 0.43f, paint);
                }
                break;
            }
            default: {
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(x0 + s / 2f, y0 + s / 2f, s / 2f, paint);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
