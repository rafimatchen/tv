package com.rafi.glasshome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The screensaver's sky: colours follow the real sun (dawn, day, golden hour, dusk, night),
 * the weather is drawn into it (clouds, rain, snow, fog, lightning), and a Manhattan
 * skyline sits along the bottom with windows that light up after dark.
 * Pure Canvas drawing, no blur and no bitmaps beyond one 96px cloud puff. The frame rate
 * drops to what the current weather needs (1 fps on a clear day, 30 fps in rain).
 */
final class SkyView extends View {
    static final int CLEAR = 0, PARTLY = 1, OVERCAST = 2, FOG = 3, DRIZZLE = 4, RAIN = 5, SNOW = 6, STORM = 7;

    // {minutes from sunrise/sunset, top, middle, horizon}
    private static final int[][] DAWN = {
            {-90, 0xFF03050D, 0xFF070F26, 0xFF0B1636},
            {-45, 0xFF070C24, 0xFF1C2050, 0xFF3A2C5E},
            {-15, 0xFF1A2752, 0xFF4E3F7C, 0xFFD27A80},
            {0, 0xFF2B4C8C, 0xFF8090C4, 0xFFF3A66A},
            {45, 0xFF2D69B3, 0xFF6EA2DA, 0xFFBFD9EE},
            {90, 0xFF2862AE, 0xFF5B98DE, 0xFFA8CEF0},
    };
    private static final int[][] DUSK = {
            {-90, 0xFF2862AE, 0xFF5B98DE, 0xFFA8CEF0},
            {-45, 0xFF3567A6, 0xFF8EA6CC, 0xFFF0C080},
            {0, 0xFF2A3A72, 0xFFA0567A, 0xFFF08A4C},
            {20, 0xFF151D46, 0xFF3D2D64, 0xFF8C4E6C},
            {45, 0xFF080E2A, 0xFF16173E, 0xFF2D2450},
            {90, 0xFF03050D, 0xFF070F26, 0xFF0B1636},
    };

    private final float dp;
    private final Random rnd = new Random(29);

    // State from the outside world.
    private long sunrise, sunset;      // epoch seconds, today
    private int mood = CLEAR;
    private long fakeNow;              // preview: fixed clock, 0 = real time

    // Derived each frame.
    private final int[] sky = new int[3];
    private float darkness;            // 0 = full day, 1 = full night
    private float cloudiness;

    private final Paint skyPaint = new Paint();
    private final Paint scrimPaint = new Paint();
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint puffPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // No anti-aliasing: the buildings are axis-aligned, and AA edges let thin seams of sky through.
    private final Paint skylinePaint = new Paint();
    private final Paint windowPaint = new Paint();
    private final Paint flashPaint = new Paint();
    private final RadialGradient unitGlow = new RadialGradient(0, 0, 1, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private final Matrix glowMatrix = new Matrix();
    private Bitmap puff;
    private int puffColor, glowColor;

    // Stars in four twinkle groups, each drawn with one drawPoints call.
    private float[][] starPts;
    private float[] starGroupPhase;
    private float[] cloudX, cloudY, cloudScale, cloudSpeed;
    private float[] dropX, dropY, dropSpeed, dropLen;
    private float[] dropBuf; // batched lines (rain) or points (snow)
    private Path skyline;
    private RectF crown;
    private float[] winX, winY;
    private CityLife city;
    private float hour = 12;
    private int w, h;
    private int skyKey = -1;

    private long lastFrameMs, startMs;
    private long nextFlashMs, flashMs;

    SkyView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        rainPaint.setStrokeWidth(1.3f * dp);
        rainPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setShader(unitGlow);
        startMs = SystemClock.uptimeMillis();
        long[] sun = fallbackSun();
        sunrise = sun[0];
        sunset = sun[1];
    }

    /** Until the weather arrives: a typical NYC day, sunrise 6:45, sunset 18:30. */
    static long[] fallbackSun() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 6);
        c.set(java.util.Calendar.MINUTE, 45);
        long rise = c.getTimeInMillis() / 1000;
        return new long[]{rise, rise + (11 * 60 + 45) * 60};
    }

    void setSun(long sunriseSec, long sunsetSec) {
        sunrise = sunriseSec;
        sunset = sunsetSec;
        skyKey = -1;
    }

    void setMood(int m) {
        if (m == mood) return;
        mood = m;
        skyKey = -1;
        rebuildParticles();
    }

    void trainArriving() {
        if (city != null) city.trainArriving();
    }

    void busArriving() {
        if (city != null) city.busArriving();
    }

    void setDockBikes(int n) {
        if (city != null) city.setDockBikes(n);
    }

    /** Preview a time of day (epoch seconds), or 0 for the real clock. */
    void setFakeNow(long epochSec) {
        fakeNow = epochSec;
        skyKey = -1;
    }

    static int moodFor(int wmoCode, int cloudCover) {
        if (wmoCode >= 95) return STORM;
        if ((wmoCode >= 71 && wmoCode <= 77) || wmoCode == 85 || wmoCode == 86) return SNOW;
        if ((wmoCode >= 61 && wmoCode <= 67) || (wmoCode >= 80 && wmoCode <= 82)) return RAIN;
        if (wmoCode >= 51 && wmoCode <= 57) return DRIZZLE;
        if (wmoCode == 45 || wmoCode == 48) return FOG;
        if (wmoCode == 3) return OVERCAST;
        if (wmoCode == 1 || wmoCode == 2 || cloudCover > 40) return PARTLY;
        return CLEAR;
    }

    static String moodLabel(int m) {
        switch (m) {
            case PARTLY: return "Partly cloudy";
            case OVERCAST: return "Cloudy";
            case FOG: return "Fog";
            case DRIZZLE: return "Drizzle";
            case RAIN: return "Rain";
            case SNOW: return "Snow";
            case STORM: return "Thunderstorms";
            default: return "Clear";
        }
    }

    // ---------------------------------------------------------------- setup

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw;
        h = nh;
        starPts = new float[4][];
        starGroupPhase = new float[4];
        int[] perGroup = {44, 32, 22, 12}; // fewer of the bigger stars
        for (int g = 0; g < 4; g++) {
            starPts[g] = new float[perGroup[g] * 2];
            for (int i = 0; i < perGroup[g]; i++) {
                starPts[g][i * 2] = rnd.nextFloat() * w;
                starPts[g][i * 2 + 1] = rnd.nextFloat() * h * 0.62f;
            }
            starGroupPhase[g] = rnd.nextFloat() * 6.283f;
        }
        starPaint.setStrokeCap(Paint.Cap.ROUND);
        snowPaint.setStrokeCap(Paint.Cap.ROUND);
        buildPuff();
        buildSkyline();
        city = new CityLife(dp, puff, w, h, winX, winY);
        rebuildParticles();
        skyKey = -1;
    }

    private void buildPuff() {
        int s = 96;
        puff = Bitmap.createBitmap(s, s, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(puff).drawCircle(s / 2f, s / 2f, s / 2f, p);
    }

    /** Procedural Midtown East skyline with the Empire State Building, plus window positions. */
    private void buildSkyline() {
        Random r = new Random(1931);
        skyline = new Path();
        float x = -20 * dp;
        float esbX = w * 0.70f;
        float[] wx = new float[400], wy = new float[400];
        int wn = 0;
        while (x < w + 20 * dp) {
            float bw = (16 + r.nextFloat() * 30) * dp;
            boolean tall = r.nextFloat() < 0.18f;
            float bh = (tall ? 105 + r.nextFloat() * 45 : 42 + r.nextFloat() * 60) * dp;
            if (x + bw > esbX - 40 * dp && x < esbX + 40 * dp) {
                bh = Math.min(bh, 95 * dp); // keep the landmark's neighbours lower
            } else if (x < w * 0.62f) {
                // Foreground towers behind the transit rows, so the text always sits on a
                // dark silhouette, even against a bright daytime sky.
                bh = Math.max(bh, (176 + r.nextFloat() * 36) * dp);
            }
            skyline.addRect(x, h - bh, x + bw + 1, h, Path.Direction.CW);
            for (float yy = h - bh + 8 * dp; yy < h - 6 * dp; yy += 7 * dp) {
                for (float xx = x + 4 * dp; xx < x + bw - 4 * dp; xx += 6 * dp) {
                    boolean behindText = xx < w * 0.66f && yy > h - 215 * dp;
                    if (!behindText && wn < wx.length && r.nextFloat() < 0.07f) {
                        wx[wn] = xx;
                        wy[wn] = yy;
                        wn++;
                    }
                }
            }
            x += bw;
        }
        // Empire State Building: base, setbacks, crown and mast.
        float cx = esbX;
        skyline.addRect(cx - 30 * dp, h - 150 * dp, cx + 30 * dp, h, Path.Direction.CW);
        skyline.addRect(cx - 22 * dp, h - 196 * dp, cx + 22 * dp, h, Path.Direction.CW);
        skyline.addRect(cx - 15 * dp, h - 214 * dp, cx + 15 * dp, h, Path.Direction.CW);
        skyline.addRect(cx - 9 * dp, h - 228 * dp, cx + 9 * dp, h, Path.Direction.CW);
        skyline.addRect(cx - 4 * dp, h - 240 * dp, cx + 4 * dp, h, Path.Direction.CW);
        skyline.addRect(cx - 1.3f * dp, h - 270 * dp, cx + 1.3f * dp, h, Path.Direction.CW);
        crown = new RectF(cx - 9 * dp, h - 228 * dp, cx + 9 * dp, h - 214 * dp);
        for (float yy = h - 190 * dp; yy < h - 20 * dp; yy += 9 * dp) {
            for (float xx = cx - 18 * dp; xx < cx + 18 * dp; xx += 6 * dp) {
                if (wn < wx.length && r.nextFloat() < 0.12f) {
                    wx[wn] = xx;
                    wy[wn] = yy;
                    wn++;
                }
            }
        }
        winX = java.util.Arrays.copyOf(wx, wn);
        winY = java.util.Arrays.copyOf(wy, wn);
    }

    private void rebuildParticles() {
        if (w == 0) return;
        int clouds;
        switch (mood) {
            case PARTLY: clouds = 5; break;
            case OVERCAST: case RAIN: case STORM: case DRIZZLE: clouds = 10; break;
            case SNOW: clouds = 8; break;
            case FOG: clouds = 7; break;
            default: clouds = 0;
        }
        cloudX = new float[clouds];
        cloudY = new float[clouds];
        cloudScale = new float[clouds];
        cloudSpeed = new float[clouds];
        for (int i = 0; i < clouds; i++) {
            cloudX[i] = rnd.nextFloat() * (w + 400 * dp) - 200 * dp;
            cloudY[i] = mood == FOG ? h * (0.35f + rnd.nextFloat() * 0.5f) : h * (0.05f + rnd.nextFloat() * 0.45f);
            cloudScale[i] = (mood == FOG ? 2.6f : 1.4f) + rnd.nextFloat() * 1.6f;
            cloudSpeed[i] = (4 + rnd.nextFloat() * 8) * dp; // px per second
        }
        int drops = mood == RAIN ? 140 : mood == STORM ? 180 : mood == DRIZZLE ? 70 : mood == SNOW ? 120 : 0;
        dropBuf = new float[drops * 4];
        dropX = new float[drops];
        dropY = new float[drops];
        dropSpeed = new float[drops];
        dropLen = new float[drops];
        for (int i = 0; i < drops; i++) {
            dropX[i] = rnd.nextFloat() * w;
            dropY[i] = rnd.nextFloat() * h;
            if (mood == SNOW) {
                dropSpeed[i] = (28 + rnd.nextFloat() * 40) * dp;
                dropLen[i] = (1.1f + rnd.nextFloat() * 1.9f) * dp; // flake radius
            } else {
                dropSpeed[i] = (mood == DRIZZLE ? 420 : 720 + rnd.nextFloat() * 320) * dp;
                dropLen[i] = (mood == DRIZZLE ? 8 : 14 + rnd.nextFloat() * 12) * dp;
            }
        }
        nextFlashMs = SystemClock.uptimeMillis() + 4000;
    }

    // ---------------------------------------------------------------- sky colours

    private long now() {
        return fakeNow != 0 ? fakeNow : System.currentTimeMillis() / 1000;
    }

    private void updateSky() {
        long now = now();
        int key = (int) (now / 60);
        if (key == skyKey) return;
        skyKey = key;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now * 1000);
        hour = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f;
        darkness = computeSky(now, sunrise, sunset, mood, sky);
        cloudiness = cloudinessFor(mood);
        skyPaint.setShader(new LinearGradient(0, 0, 0, h, new int[]{sky[0], sky[1], sky[2], sky[2]},
                new float[]{0f, 0.5f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        scrimPaint.setShader(new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        skylinePaint.setColor(lerpColor(0xFF020306, sky[2], 0.10f + 0.08f * (1 - darkness)));
        int cloud = lerpColor(sky[1], darkness > 0.6f ? 0xFF8A93AD : 0xFFFFFFFF, mood == STORM ? 0.25f : 0.6f);
        if (cloud != puffColor) {
            puffColor = cloud;
            puffPaint.setColorFilter(new PorterDuffColorFilter(cloud, PorterDuff.Mode.SRC_IN));
        }
    }

    /**
     * Sky colours (top, middle, horizon) for a moment, with the weather mixed in.
     * Also used by the home screen for its faint sky tint. Returns darkness, 0 day to 1 night.
     */
    static float computeSky(long now, long sunrise, long sunset, int mood, int[] out) {
        // Today's times, shifted a day if needed so "now" sits between the right pair.
        long rise = sunrise, set = sunset;
        while (now > set + 12 * 3600) { rise += 86400; set += 86400; }
        while (now < rise - 12 * 3600) { rise -= 86400; set -= 86400; }
        float fromRise = (now - rise) / 60f, fromSet = (now - set) / 60f;
        int[] c;
        if (Math.abs(fromRise) <= 90) c = lerpTable(DAWN, fromRise);
        else if (Math.abs(fromSet) <= 90) c = lerpTable(DUSK, fromSet);
        else if (now > rise && now < set) c = new int[]{DAWN[5][1], DAWN[5][2], DAWN[5][3]};
        else c = new int[]{DAWN[0][1], DAWN[0][2], DAWN[0][3]};

        float dark;
        if (now > rise + 45 * 60 && now < set - 45 * 60) dark = 0;
        else if (now < rise - 60 * 60 || now > set + 60 * 60) dark = 1;
        else {
            float m = now <= (rise + set) / 2 ? -fromRise : fromSet; // minutes into the dark side
            dark = clamp((m + 45) / 105f);
        }

        float grey, dim;
        switch (mood) {
            case PARTLY: grey = 0.12f; dim = 1f; break;
            case OVERCAST: grey = 0.55f; dim = 0.9f; break;
            case FOG: grey = 0.65f; dim = 0.82f; break;
            case DRIZZLE: grey = 0.5f; dim = 0.85f; break;
            case RAIN: grey = 0.62f; dim = 0.75f; break;
            case SNOW: grey = 0.55f; dim = 0.95f; break;
            case STORM: grey = 0.7f; dim = 0.6f; break;
            default: grey = 0f; dim = 1f;
        }
        for (int i = 0; i < 3; i++) out[i] = weatherTint(c[i], grey, dim);
        return dark;
    }

    private static float cloudinessFor(int mood) {
        switch (mood) {
            case PARTLY: return 0.3f;
            case OVERCAST: case DRIZZLE: case SNOW: return 0.9f;
            case FOG: case RAIN: case STORM: return 1f;
            default: return 0f;
        }
    }

    private static int[] lerpTable(int[][] t, float m) {
        for (int i = 0; i < t.length - 1; i++) {
            if (m <= t[i + 1][0]) {
                float f = clamp((m - t[i][0]) / (t[i + 1][0] - t[i][0]));
                return new int[]{lerpColor(t[i][1], t[i + 1][1], f), lerpColor(t[i][2], t[i + 1][2], f),
                        lerpColor(t[i][3], t[i + 1][3], f)};
            }
        }
        int[] last = t[t.length - 1];
        return new int[]{last[1], last[2], last[3]};
    }

    private static int weatherTint(int c, float grey, float dim) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        float l = 0.3f * r + 0.59f * g + 0.11f * b;
        r = (int) Math.min(255, (r + (l - r) * grey) * dim);
        g = (int) Math.min(255, (g + (l - g) * grey) * dim);
        b = (int) Math.min(255, (b + (l - b) * grey) * dim);
        return Color.rgb(r, g, b);
    }

    static int lerpColor(int a, int b, float f) {
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long ms = SystemClock.uptimeMillis();
        float dt = lastFrameMs == 0 ? 0 : Math.min(0.1f, (ms - lastFrameMs) / 1000f);
        lastFrameMs = ms;
        float t = (ms - startMs) / 1000f;
        updateSky();

        city.update(dt, ms, darkness, hour, mood);

        canvas.drawRect(0, 0, w, h, skyPaint);
        drawStars(canvas, t);
        drawSunOrMoon(canvas);
        city.drawSky(canvas, ms);
        drawClouds(canvas, dt);
        drawPrecipitation(canvas, dt, t);
        canvas.drawRect(0, h * 0.55f, w, h, scrimPaint);
        drawSkyline(canvas, t);
        city.drawStreet(canvas, skylinePaint.getColor(), ms);
        drawLightning(canvas, ms);

        // 25 fps for falling rain or snow; 15 fps is plenty for slow street life.
        boolean falling = mood == RAIN || mood == STORM || mood == DRIZZLE || mood == SNOW;
        postInvalidateDelayed(falling ? 40 : 66);
    }

    private void drawStars(Canvas c, float t) {
        float vis = darkness * (1 - cloudiness * 0.95f);
        if (vis < 0.02f) return;
        for (int g = 0; g < starPts.length; g++) {
            float tw = 0.55f + 0.45f * (float) Math.sin(t * (0.5f + g * 0.17f) + starGroupPhase[g]);
            starPaint.setStrokeWidth((1.1f + g * 0.55f) * dp);
            starPaint.setColor(Color.argb((int) (255 * vis * tw * 0.85f), 235, 240, 255));
            c.drawPoints(starPts[g], starPaint);
        }
    }

    private void drawSunOrMoon(Canvas c) {
        long now = now();
        long rise = sunrise, set = sunset;
        while (now > set + 12 * 3600) { rise += 86400; set += 86400; }
        while (now < rise - 12 * 3600) { rise -= 86400; set -= 86400; }
        float horizon = h * 0.86f;
        float vis = 1 - cloudiness * 0.85f;
        if (now >= rise - 900 && now <= set + 900) {
            float p = (now - rise) / (float) (set - rise);
            float x = w * (0.42f + 0.50f * p); // right of the clock and weather text
            float y = horizon - (float) Math.sin(Math.PI * clamp(p)) * h * 0.62f;
            float low = 1 - clamp((float) Math.sin(Math.PI * clamp(p)) * 3f); // near horizon: warmer, bigger
            int glow = lerpColor(0xFFFFF1C9, 0xFFFF9A4D, low);
            drawGlow(c, x, y, (110 + 50 * low) * dp, (glow & 0x00FFFFFF) | ((int) (120 * vis) << 24));
            discPaint.setColor((lerpColor(0xFFFFFBEA, 0xFFFFC48A, low) & 0x00FFFFFF) | ((int) (255 * vis) << 24));
            c.drawCircle(x, y, 20 * dp, discPaint);
        } else {
            long nightStart = now > set ? set : set - 86400;
            long nightEnd = now > set ? rise + 86400 : rise;
            float p = (now - nightStart) / (float) (nightEnd - nightStart);
            float x = w * (0.42f + 0.50f * p);
            float y = horizon - (float) Math.sin(Math.PI * clamp(p)) * h * 0.55f;
            drawGlow(c, x, y, 70 * dp, ((int) (60 * vis) << 24) | 0xC9D6FF);
            drawMoon(c, x, y, 15 * dp, vis);
        }
    }

    private void drawGlow(Canvas c, float x, float y, float r, int argb) {
        glowMatrix.setScale(r, r);
        glowMatrix.postTranslate(x, y);
        unitGlow.setLocalMatrix(glowMatrix);
        if (argb != glowColor) {
            glowColor = argb;
            glowPaint.setColorFilter(new PorterDuffColorFilter(argb, PorterDuff.Mode.MULTIPLY));
        }
        c.drawCircle(x, y, r, glowPaint);
    }

    /** Moon with today's phase (days since a known new moon, 2000-01-06 18:14 UTC). */
    private void drawMoon(Canvas c, float x, float y, float r, float vis) {
        double days = (now() - 947182440L) / 86400.0;
        double phase = (days % 29.530588) / 29.530588;
        discPaint.setColor(Color.argb((int) (40 * vis), 200, 210, 235));
        c.drawCircle(x, y, r, discPaint); // earthshine
        RectF oval = new RectF(x - r, y - r, x + r, y + r);
        float k = (float) Math.cos(2 * Math.PI * phase) * r;
        Path half = new Path();
        boolean waxing = phase < 0.5;
        half.addArc(oval, waxing ? -90 : 90, 180);
        half.close();
        Path term = new Path();
        term.addOval(new RectF(x - Math.abs(k), y - r, x + Math.abs(k), y + r), Path.Direction.CW);
        boolean crescent = phase < 0.25 || phase > 0.75;
        half.op(term, crescent ? Path.Op.DIFFERENCE : Path.Op.UNION);
        discPaint.setColor(Color.argb((int) (235 * vis), 238, 242, 255));
        c.drawPath(half, discPaint);
    }

    private void drawClouds(Canvas c, float dt) {
        float alpha = mood == FOG ? 0.32f : mood == PARTLY ? 0.55f : 0.6f;
        puffPaint.setAlpha((int) (255 * alpha));
        float pw = puff.getWidth();
        for (int i = 0; i < cloudX.length; i++) {
            cloudX[i] += cloudSpeed[i] * dt;
            float s = cloudScale[i] * 80 * dp / pw;
            float span = pw * s * 3.2f;
            if (cloudX[i] - span > w) cloudX[i] = -span;
            // A cloud is three overlapping puffs; fog stretches them into wide banks.
            for (int k = 0; k < 3; k++) {
                float ox = (k - 1) * pw * s * 0.7f, oy = (k == 1 ? -0.25f : 0) * pw * s;
                c.save();
                c.translate(cloudX[i] + ox, cloudY[i] + oy);
                c.scale(mood == FOG ? s * 4f : s * 1.25f, s * 1.15f);
                c.drawBitmap(puff, -pw / 2, -pw / 2, puffPaint);
                c.restore();
            }
        }
    }

    private void drawPrecipitation(Canvas c, float dt, float t) {
        int n = dropX.length;
        if (n == 0) return;
        boolean snow = mood == SNOW;
        float slant = 0.18f;
        for (int i = 0; i < n; i++) {
            dropY[i] += dropSpeed[i] * dt;
            if (snow) dropX[i] += (float) Math.sin(t * 0.8f + i) * 10 * dp * dt;
            else dropX[i] -= dropSpeed[i] * slant * dt;
            if (dropY[i] > h + 30 * dp) {
                dropY[i] = -30 * dp;
                dropX[i] = rnd.nextFloat() * (w + 100 * dp);
            }
            if (snow) {
                dropBuf[i * 2] = dropX[i];
                dropBuf[i * 2 + 1] = dropY[i];
            } else {
                dropBuf[i * 4] = dropX[i];
                dropBuf[i * 4 + 1] = dropY[i];
                dropBuf[i * 4 + 2] = dropX[i] + dropLen[i] * slant;
                dropBuf[i * 4 + 3] = dropY[i] - dropLen[i];
            }
        }
        if (snow) {
            // Three flake sizes, one drawPoints each.
            int third = n / 3;
            for (int g = 0; g < 3; g++) {
                snowPaint.setStrokeWidth((2.2f + g * 1.5f) * dp);
                snowPaint.setColor(g == 2 ? 0xF2FFFFFF : 0xBFFFFFFF);
                int from = g * third, count = g == 2 ? n - from : third;
                c.drawPoints(dropBuf, from * 2, count * 2, snowPaint);
            }
        } else {
            rainPaint.setColor(mood == DRIZZLE ? 0x4DFFFFFF : 0x59DCE6FF);
            c.drawLines(dropBuf, 0, n * 4, rainPaint);
        }
    }

    private void drawSkyline(Canvas c, float t) {
        // A slow sideways drift (about 20dp over an hour) so the silhouette never sits still.
        float drift = (float) Math.sin(t / 3600f * Math.PI * 2) * 20 * dp;
        c.save();
        c.translate(drift, 0);
        c.drawPath(skyline, skylinePaint);
        city.drawWindows(c);
        if (darkness > 0.05f) {
            // The Empire State's floodlit crown: a soft glow and two lit bands.
            drawGlow(c, crown.centerX(), crown.centerY(), 34 * dp, ((int) (70 * darkness) << 24) | 0xBFD4FF);
            windowPaint.setColor(Color.argb((int) (150 * darkness), 225, 236, 255));
            float band = 2.2f * dp;
            c.drawRect(crown.left, crown.top, crown.right, crown.top + band, windowPaint);
            c.drawRect(crown.left + 3 * dp, crown.centerY(), crown.right - 3 * dp, crown.centerY() + band, windowPaint);
        }
        c.restore();
    }

    private void drawLightning(Canvas c, long ms) {
        if (mood != STORM) return;
        if (ms > nextFlashMs) {
            flashMs = ms;
            nextFlashMs = ms + 7000 + rnd.nextInt(11000);
        }
        long since = ms - flashMs;
        if (since < 450) {
            float a = since < 80 ? 0.22f : since < 160 ? 0.05f : since < 230 ? 0.16f : 0.16f * (450 - since) / 220f;
            flashPaint.setColor(Color.argb((int) (255 * a), 230, 235, 255));
            c.drawRect(0, 0, w, h, flashPaint);
        }
    }
}
