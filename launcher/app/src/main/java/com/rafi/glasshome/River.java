package com.rafi.glasshome;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * The foreground of the screensaver, as seen looking west from Queens: the FDR Drive along
 * the shore (streams of headlights one way, taillights the other) and the East River below,
 * with the city's lights shimmering in it, a glitter path under the sun or moon, and the odd
 * ferry or tug. All of it is a few batched draw calls.
 */
final class River {
    private static final int LANE_CARS = 22, REFLECTIONS = 46, DASHES = 5;

    private final float dp;
    private final Random rnd = new Random(1955); // the FDR opened in sections from the 1930s to the 1950s
    private final int w, h;
    private final float fdrTop, waterTop;

    // FDR traffic: x positions per lane; left lane heads left (headlights), right lane heads right (taillights)
    private final float[] west = new float[LANE_CARS], east = new float[LANE_CARS];
    private final float[] westSpeed = new float[LANE_CARS], eastSpeed = new float[LANE_CARS];
    private final float[] lightPts = new float[LANE_CARS * 2];

    // Reflections: columns of short horizontal dashes under lit buildings
    private final float[] refX = new float[REFLECTIONS], refPhase = new float[REFLECTIONS];
    private final int[] refColor = new int[REFLECTIONS];
    private final float[] dashLines = new float[REFLECTIONS * DASHES * 4];
    private final float[] glitter = new float[14 * 4];

    // Boat
    private boolean boatOn, boatFerry;
    private float boatX, boatSpeed;
    private long nextBoatMs;

    private final Paint fill = new Paint();
    private final Paint water = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int waterKey = 0;

    River(float dp, int w, int h, float fdrTop, float waterTop) {
        this.dp = dp;
        this.w = w;
        this.h = h;
        this.fdrTop = fdrTop;
        this.waterTop = waterTop;
        for (int i = 0; i < LANE_CARS; i++) {
            west[i] = rnd.nextFloat() * w;
            east[i] = rnd.nextFloat() * w;
            westSpeed[i] = (70 + rnd.nextFloat() * 30) * dp;
            eastSpeed[i] = (70 + rnd.nextFloat() * 30) * dp;
        }
        for (int i = 0; i < REFLECTIONS; i++) {
            refX[i] = rnd.nextFloat() * w;
            refPhase[i] = rnd.nextFloat() * 6.28f;
            refColor[i] = rnd.nextFloat() < 0.8f ? 0xFFFFCB80 : 0xFFE6EEFF;
        }
        dots.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        nextBoatMs = android.os.SystemClock.uptimeMillis() + 15_000;
    }

    void update(float dt, long ms, float hour, float darkness) {
        // Fewer cars at 3 am, a steady stream at rush hour.
        int active = hour < 5 ? 7 : hour < 7 ? 13 : hour < 20 ? LANE_CARS : 15;
        for (int i = 0; i < LANE_CARS; i++) {
            west[i] -= westSpeed[i] * dt;
            if (west[i] < -10 * dp) west[i] = w + rnd.nextFloat() * 120 * dp;
            east[i] += eastSpeed[i] * dt;
            if (east[i] > w + 10 * dp) east[i] = -rnd.nextFloat() * 120 * dp;
            if (i >= active) { // parked off-screen until traffic picks up
                west[i] = Math.max(west[i], w + 20 * dp);
                east[i] = Math.min(east[i], -20 * dp);
            }
        }
        if (!boatOn && ms > nextBoatMs) {
            boatOn = true;
            boatFerry = rnd.nextFloat() < 0.65f;
            boolean right = rnd.nextBoolean();
            boatSpeed = (right ? 1 : -1) * (boatFerry ? 16 : 9) * dp;
            boatX = right ? -40 * dp : w + 40 * dp;
        }
        if (boatOn) {
            boatX += boatSpeed * dt;
            if (boatX < -60 * dp || boatX > w + 60 * dp) {
                boatOn = false;
                nextBoatMs = ms + 70_000 + rnd.nextInt(110_000);
            }
        }
    }

    /**
     * @param sky the sky colours (top, middle, horizon), mirrored in the water
     * @param lightX x of the sun or moon, for the glitter path (NaN if hidden)
     */
    void draw(Canvas c, float t, float darkness, int[] sky, int skylineColor, float lightX, int lightColor) {
        // FDR: a dark roadway with a rail on the river side
        fill.setColor(SkyView.lerpColor(skylineColor, 0xFF2C3038, 0.18f));
        c.drawRect(0, fdrTop, w, waterTop, fill);
        line.setStrokeWidth(dp);
        line.setColor(darkness > 0.3f ? 0x2EFFFFFF : 0x40FFFFFF);
        c.drawLine(0, waterTop - 0.5f * dp, w, waterTop - 0.5f * dp, line);

        float laneW = fdrTop + (waterTop - fdrTop) * 0.35f, laneE = fdrTop + (waterTop - fdrTop) * 0.72f;
        boolean night = darkness > 0.3f;
        dots.setStrokeWidth((night ? 2.4f : 2f) * dp);
        dots.setColor(night ? 0xFFFFF2D6 : 0xFF6A707C);
        drawLane(c, west, laneW);
        dots.setColor(night ? 0xFFFF4A3D : 0xFF7A808C);
        drawLane(c, east, laneE);

        // Water mirrors the sky, darker and greyer
        int key = sky[1] ^ sky[2] * 31;
        if (key != waterKey) {
            waterKey = key;
            int top = SkyView.lerpColor(sky[2], 0xFF05070C, 0.55f);
            int bottom = SkyView.lerpColor(sky[1], 0xFF020305, 0.8f);
            water.setShader(new LinearGradient(0, waterTop, 0, h, top, bottom, Shader.TileMode.CLAMP));
        }
        c.drawRect(0, waterTop, w, h, water);

        // City lights shimmering on the water at night; small sky glints by day
        int n = 0;
        float span = h - waterTop;
        for (int i = 0; i < REFLECTIONS; i++) {
            for (int k = 0; k < DASHES; k++) {
                float y = waterTop + (k + 0.6f) * span / (DASHES + 0.4f);
                float wob = (float) Math.sin(t * 1.3f + refPhase[i] + k * 1.7f) * 2.2f * dp;
                float len = (5 - k * 0.6f) * dp;
                dashLines[n++] = refX[i] + wob - len / 2;
                dashLines[n++] = y;
                dashLines[n++] = refX[i] + wob + len / 2;
                dashLines[n++] = y;
            }
        }
        line.setStrokeWidth(1.2f * dp);
        line.setColor(night ? Color.argb((int) (120 * darkness), 255, 205, 140) : 0x40FFFFFF);
        c.drawLines(dashLines, 0, n, line);

        // Glitter path under the sun or moon
        if (!Float.isNaN(lightX)) {
            int g = 0;
            for (int k = 0; k < glitter.length / 4; k++) {
                float y = waterTop + (k + 0.5f) * span / (glitter.length / 4f);
                float wob = (float) Math.sin(t * 2.1f + k * 2.3f) * (3 + k) * dp;
                float len = (7 + k * 1.2f) * dp;
                glitter[g++] = lightX + wob - len / 2;
                glitter[g++] = y;
                glitter[g++] = lightX + wob + len / 2;
                glitter[g++] = y;
            }
            line.setStrokeWidth(1.4f * dp);
            line.setColor((lightColor & 0x00FFFFFF) | 0x8C000000);
            c.drawLines(glitter, 0, g, line);
        }

        if (boatOn) drawBoat(c, night);
    }

    private void drawLane(Canvas c, float[] xs, float y) {
        int n = 0;
        for (float x : xs) {
            if (x < -5 * dp || x > w + 5 * dp) continue;
            lightPts[n++] = x;
            lightPts[n++] = y;
        }
        c.drawPoints(lightPts, 0, n, dots);
    }

    private void drawBoat(Canvas c, boolean night) {
        float y = waterTop + (h - waterTop) * 0.45f;
        float len = (boatFerry ? 30 : 16) * dp;
        float x = boatX - len / 2;
        // Hull
        fill.setColor(boatFerry ? (night ? 0xFF9AA3B5 : 0xFFF2F4F7) : (night ? 0xFF4A3A36 : 0xFF8C2F28));
        r.set(x, y - 4 * dp, x + len, y);
        c.drawRoundRect(r, 2 * dp, 2 * dp, fill);
        // Cabin
        fill.setColor(boatFerry ? 0xFF1F5FA8 : (night ? 0xFF2A2A2A : 0xFFE9E3D4));
        float cabin = boatFerry ? len * 0.62f : len * 0.4f;
        float cx = boatSpeed > 0 ? x + len * 0.18f : x + len - len * 0.18f - cabin;
        c.drawRect(cx, y - 8 * dp, cx + cabin, y - 4 * dp, fill);
        if (night) {
            if (boatFerry) { // a row of lit cabin windows
                fill.setColor(0xFFFFE2A8);
                c.drawRect(cx + 1.5f * dp, y - 7 * dp, cx + cabin - 1.5f * dp, y - 5.5f * dp, fill);
            }
            // Navigation lights: red to port, green to starboard
            dots.setStrokeWidth(2.4f * dp);
            dots.setColor(0xFFFF4A3D);
            c.drawPoint(boatSpeed > 0 ? x + len : x, y - 4 * dp, dots);
            dots.setColor(0xFF3DDC84);
            c.drawPoint(boatSpeed > 0 ? x : x + len, y - 4 * dp, dots);
        }
        // Wake
        line.setStrokeWidth(dp);
        line.setColor(night ? 0x33FFFFFF : 0x66FFFFFF);
        float tail = boatSpeed > 0 ? x : x + len;
        float dir = boatSpeed > 0 ? -1 : 1;
        c.drawLine(tail, y, tail + dir * 22 * dp, y + 1.5f * dp, line);
    }
}
