package com.rafi.glasshome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

import java.util.Random;

/**
 * The street at the bottom of the screensaver: cars, taxis, cyclists and people drifting by,
 * streetlights that come on at dusk, apartment windows switching on and off, a plane at night.
 * A few things follow the live data: a subway entrance glows and lets people out when a
 * 6 train is due, an M15 pulls into its stop when a bus is arriving, and the Citi Bike dock
 * holds as many bikes as the real one.
 * Street trees follow the seasons (blossom, green, autumn colours, bare) and wear lights
 * through the holidays.
 * Everything is batched into a handful of draw calls; nothing allocates per frame.
 */
final class CityLife {
    private static final int CAR = 0, TAXI = 1, BUS = 2, BIKE = 3;
    private static final int[] CAR_COLORS = {0xFF3A3F4A, 0xFF59606C, 0xFF2B303A, 0xFF6A5252, 0xFF4A5A6A, 0xFF8A8F99};
    private static final int MAX_V = 16, MAX_P = 18;

    private final float dp;
    private final Random rnd = new Random();
    private final Bitmap puff;
    private final float puffW;
    private int w, h;
    private float ground, curb, laneFar, laneNear;

    // Street trees and their season
    private float[] treeX;
    private float[] treeLights; // points around bare canopies for December
    private int month = 6, day = 1;

    // Apartment windows
    private float[] winXY;
    private boolean[] lit;
    private float[] litPts;
    private int litN;
    private long nextToggleMs;

    // Streetlights
    private float[] lampX, lampLevel, lampThreshold, postLines;

    // Vehicles: x is the back end; dir +1 moves right (near lane), -1 left (far lane)
    private final float[] vX = new float[MAX_V], vSpeed = new float[MAX_V], vLen = new float[MAX_V];
    private final float[] vPause = new float[MAX_V];
    private final int[] vKind = new int[MAX_V], vColor = new int[MAX_V], vDir = new int[MAX_V];
    private final boolean[] vOn = new boolean[MAX_V], vStopped = new boolean[MAX_V];

    // People
    private final float[] pX = new float[MAX_P], pSpeed = new float[MAX_P], pPhase = new float[MAX_P];
    private final boolean[] pOn = new boolean[MAX_P];
    private final float[] pLines = new float[MAX_P * 4], pHeads = new float[MAX_P * 2];

    // Plane
    private float planeX, planeY;
    private boolean planeOn;
    private long nextPlaneMs;

    // Data-driven bits
    private float subwayX, subwayPulse, busStopX, dockX;
    private int dockBikes;
    private boolean busWanted;
    private int pendingRiders;
    private float riderTimer;

    // Sky state from SkyView
    private float darkness, hour;
    private int mood;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint squares = new Paint();
    private final Paint glow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final PorterDuffColorFilter warm = tint(0xFFFFC27A), head = tint(0xFFFFF0CC),
            tail = tint(0xFFFF3B30), green = tint(0xFF3DDC84), white = tint(0xFFFFFFFF);
    private final RectF r = new RectF();
    private final float[] dashLines;

    /** @param ground the bottom of the street (top of the FDR) */
    CityLife(float dp, Bitmap puff, int w, int h, float ground, float[] winX, float[] winY) {
        this.dp = dp;
        this.puff = puff;
        this.puffW = puff.getWidth();
        this.w = w;
        this.h = h;
        this.ground = ground;
        curb = ground - 21 * dp;
        laneFar = ground - 10 * dp;
        laneNear = ground - 2 * dp;
        line.setStrokeCap(Paint.Cap.ROUND);
        dots.setStrokeCap(Paint.Cap.ROUND);
        squares.setStrokeCap(Paint.Cap.SQUARE);

        winXY = new float[winX.length * 2];
        for (int i = 0; i < winX.length; i++) {
            winXY[i * 2] = winX[i];
            winXY[i * 2 + 1] = winY[i];
        }
        lit = new boolean[winX.length];
        litPts = new float[winXY.length];

        int lamps = (int) (w / (68 * dp)) + 1;
        lampX = new float[lamps];
        lampLevel = new float[lamps];
        lampThreshold = new float[lamps];
        postLines = new float[lamps * 8];
        for (int i = 0; i < lamps; i++) {
            float x = 26 * dp + i * 68 * dp + (rnd.nextFloat() - 0.5f) * 8 * dp;
            lampX[i] = x;
            lampThreshold[i] = 0.22f + rnd.nextFloat() * 0.3f; // they come on one by one
            float top = curb - 24 * dp;
            int k = i * 8;
            postLines[k] = x; postLines[k + 1] = curb; postLines[k + 2] = x; postLines[k + 3] = top;
            postLines[k + 4] = x; postLines[k + 5] = top; postLines[k + 6] = x + 5 * dp; postLines[k + 7] = top;
        }
        // A tree between each pair of lamps, with fixed spots for holiday lights.
        treeX = new float[lamps];
        treeLights = new float[lamps * 9 * 2];
        for (int i = 0; i < lamps; i++) {
            treeX[i] = lampX[i] + 34 * dp + (rnd.nextFloat() - 0.5f) * 10 * dp;
            for (int k = 0; k < 9; k++) {
                double a = Math.PI * (0.9 + 1.2 * k / 8.0);
                float rr = (5 + rnd.nextFloat() * 3) * dp;
                treeLights[(i * 9 + k) * 2] = treeX[i] + (float) Math.cos(a) * rr;
                treeLights[(i * 9 + k) * 2 + 1] = curb - 15 * dp + (float) Math.sin(a) * rr * 0.8f;
            }
        }
        int dashes = (int) (w / (26 * dp)) + 1;
        dashLines = new float[dashes * 4];
        float mid = (laneFar + laneNear) / 2 - 3 * dp;
        for (int i = 0; i < dashes; i++) {
            dashLines[i * 4] = i * 26 * dp;
            dashLines[i * 4 + 1] = mid;
            dashLines[i * 4 + 2] = i * 26 * dp + 10 * dp;
            dashLines[i * 4 + 3] = mid;
        }
        subwayX = w * 0.40f;
        dockX = w * 0.50f;
        busStopX = w * 0.84f;
        nextPlaneMs = android.os.SystemClock.uptimeMillis() + 20_000;
    }

    private static PorterDuffColorFilter tint(int c) {
        return new PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN);
    }

    // ---------------------------------------------------------------- events from live data

    void trainArriving() {
        subwayPulse = 1f;
        pendingRiders += 2 + rnd.nextInt(3);
    }

    void busArriving() {
        busWanted = true;
    }

    void setDate(int month, int day) {
        this.month = month;
        this.day = day;
    }

    void setDockBikes(int n) {
        dockBikes = Math.max(0, Math.min(n, 10));
    }

    // ---------------------------------------------------------------- simulation

    void update(float dt, long ms, float darkness, float hour, int mood) {
        this.darkness = darkness;
        this.hour = hour;
        this.mood = mood;
        boolean wet = mood == SkyView.RAIN || mood == SkyView.STORM || mood == SkyView.DRIZZLE;
        boolean snow = mood == SkyView.SNOW;

        for (int i = 0; i < lampX.length; i++) {
            float target = darkness > lampThreshold[i] ? 1f : 0f;
            lampLevel[i] += Math.signum(target - lampLevel[i]) * Math.min(Math.abs(target - lampLevel[i]), dt * 0.4f);
        }

        if (ms > nextToggleMs && darkness > 0.05f) {
            nextToggleMs = ms + 700 + rnd.nextInt(1800);
            toggleWindow(windowTarget(hour));
        }

        // Vehicles
        int cars = 0;
        for (int i = 0; i < MAX_V; i++) if (vOn[i] && vKind[i] != BUS) cars++;
        float carGoal = carTarget(hour) * (snow ? 0.5f : wet ? 0.8f : 1f);
        if (cars < carGoal && rnd.nextFloat() < dt * 0.6f) spawnVehicle(wet || snow);
        if (busWanted) {
            busWanted = false;
            spawnBus();
        }
        for (int i = 0; i < MAX_V; i++) {
            if (!vOn[i]) continue;
            if (vKind[i] == BUS && !vStopped[i]) {
                float front = vX[i]; // buses run leftward; the front is the left end
                if (front <= busStopX && front > busStopX - 30 * dp) {
                    vStopped[i] = true;
                    vPause[i] = 4f;
                }
            }
            if (vPause[i] > 0) {
                vPause[i] -= dt;
                continue;
            }
            vX[i] += vDir[i] * vSpeed[i] * dt;
            if (vX[i] > w + 60 * dp || vX[i] + vLen[i] < -60 * dp) vOn[i] = false;
        }

        // People
        int people = 0;
        for (int i = 0; i < MAX_P; i++) if (pOn[i]) people++;
        float peopleGoal = peopleTarget(hour) * (snow ? 0.4f : wet ? 0.5f : 1f);
        if (people < peopleGoal && rnd.nextFloat() < dt * 0.5f) spawnPerson(rnd.nextBoolean() ? -8 * dp : w + 8 * dp);
        if (pendingRiders > 0) {
            riderTimer -= dt;
            if (riderTimer <= 0) {
                spawnPerson(subwayX + (rnd.nextFloat() - 0.5f) * 6 * dp);
                pendingRiders--;
                riderTimer = 0.9f + rnd.nextFloat();
            }
        }
        for (int i = 0; i < MAX_P; i++) {
            if (!pOn[i]) continue;
            pX[i] += pSpeed[i] * dt;
            pPhase[i] += dt * 7f;
            if (pX[i] < -12 * dp || pX[i] > w + 12 * dp) pOn[i] = false;
        }

        subwayPulse = Math.max(0, subwayPulse - dt * 0.12f);

        // Plane: an occasional blinking pair of lights crossing the night sky.
        if (!planeOn && darkness > 0.5f && ms > nextPlaneMs) {
            planeOn = true;
            planeX = -10 * dp;
            planeY = h * (0.08f + rnd.nextFloat() * 0.2f);
        }
        if (planeOn) {
            planeX += 16 * dp * dt;
            planeY -= 1.2f * dp * dt;
            if (planeX > w + 10 * dp) {
                planeOn = false;
                nextPlaneMs = ms + 120_000 + rnd.nextInt(180_000);
            }
        }
    }

    /** Share of apartment windows lit at a given hour. */
    private static float windowTarget(float hour) {
        if (hour < 1) return 0.38f;
        if (hour < 5) return 0.14f;
        if (hour < 7) return 0.26f;
        if (hour < 17) return 0.3f;
        if (hour < 19) return 0.5f;
        if (hour < 23) return 0.6f;
        return 0.45f;
    }

    private static float carTarget(float hour) {
        if (hour < 5) return 2;
        if (hour < 7) return 4;
        if (hour < 10) return 9;
        if (hour < 16) return 6;
        if (hour < 19) return 10;
        if (hour < 23) return 6;
        return 3;
    }

    private static float peopleTarget(float hour) {
        if (hour < 5) return 1;
        if (hour < 7) return 3;
        if (hour < 10) return 9;
        if (hour < 16) return 8;
        if (hour < 20) return 10;
        if (hour < 23) return 5;
        return 2;
    }

    private void toggleWindow(float target) {
        int n = lit.length;
        if (n == 0) return;
        float frac = litN / (float) n;
        int i = rnd.nextInt(n);
        boolean want = frac < target;
        for (int tries = 0; tries < 12 && lit[i] == want; tries++) i = rnd.nextInt(n);
        lit[i] = want;
        if (rnd.nextFloat() < 0.5f) { // and someone else flips the other way, so it never looks static
            int j = rnd.nextInt(n);
            lit[j] = !lit[j];
        }
        litN = 0;
        for (int k = 0; k < n; k++) {
            if (lit[k]) {
                litPts[litN * 2] = winXY[k * 2];
                litPts[litN * 2 + 1] = winXY[k * 2 + 1];
                litN++;
            }
        }
    }

    private int freeVehicle() {
        for (int i = 0; i < MAX_V; i++) if (!vOn[i]) return i;
        return -1;
    }

    private void spawnVehicle(boolean badWeather) {
        int i = freeVehicle();
        if (i < 0) return;
        float roll = rnd.nextFloat();
        int kind = roll < 0.18f ? TAXI : (!badWeather && hour > 6 && hour < 22 && roll > 0.88f) ? BIKE : CAR;
        int dir = rnd.nextBoolean() ? 1 : -1;
        vKind[i] = kind;
        vDir[i] = dir;
        vLen[i] = (kind == BIKE ? 9 : 19 + rnd.nextFloat() * 6) * dp;
        vSpeed[i] = (kind == BIKE ? 11 + rnd.nextFloat() * 5 : 32 + rnd.nextFloat() * 22) * dp;
        vColor[i] = kind == TAXI ? 0xFFE8B730 : CAR_COLORS[rnd.nextInt(CAR_COLORS.length)];
        vX[i] = dir > 0 ? -vLen[i] - 10 * dp : w + 10 * dp;
        vPause[i] = 0;
        vStopped[i] = false;
        vOn[i] = true;
    }

    private void spawnBus() {
        for (int i = 0; i < MAX_V; i++) if (vOn[i] && vKind[i] == BUS) return; // one at a time
        int i = freeVehicle();
        if (i < 0) return;
        vKind[i] = BUS;
        vDir[i] = -1;
        vLen[i] = 46 * dp;
        vSpeed[i] = 30 * dp;
        vColor[i] = 0xFF1C5BB8;
        vX[i] = w + 10 * dp;
        vPause[i] = 0;
        vStopped[i] = false;
        vOn[i] = true;
    }

    private void spawnPerson(float x) {
        for (int i = 0; i < MAX_P; i++) {
            if (pOn[i]) continue;
            pX[i] = x;
            boolean right = x < 0 || (x < w && rnd.nextBoolean());
            if (x > w) right = false;
            pSpeed[i] = (right ? 1 : -1) * (5 + rnd.nextFloat() * 4) * dp;
            pPhase[i] = rnd.nextFloat() * 6.28f;
            pOn[i] = true;
            return;
        }
    }

    // ---------------------------------------------------------------- drawing

    /** Things in the sky (drawn before clouds). */
    void drawSky(Canvas c, long ms) {
        if (!planeOn) return;
        boolean red = (ms / 700) % 2 == 0;
        boolean strobe = ms % 1300 < 90;
        sprite(c, planeX, planeY, 5 * dp, red ? tail : white, red ? 0.55f : 0.25f);
        dots.setStrokeWidth(2.2f * dp);
        dots.setColor(red ? 0xFFFF5A4E : 0x99FFFFFF);
        c.drawPoint(planeX, planeY, dots);
        if (strobe) {
            dots.setColor(0xFFFFFFFF);
            c.drawPoint(planeX + 4 * dp, planeY, dots);
        }
    }

    /** Lit apartment windows; drawn inside the skyline's drift transform. */
    void drawWindows(Canvas c) {
        if (darkness < 0.05f || litN == 0) return;
        squares.setStrokeWidth(1.8f * dp);
        squares.setColor(Color.argb((int) (200 * darkness), 255, 206, 132));
        c.drawPoints(litPts, 0, litN * 2, squares);
    }

    void drawStreet(Canvas c, int skylineColor, long ms) {
        boolean night = darkness > 0.3f;
        boolean wet = mood == SkyView.RAIN || mood == SkyView.STORM || mood == SkyView.DRIZZLE;

        // Road and sidewalk edge
        fill.setColor(SkyView.lerpColor(skylineColor, 0xFF2C3038, 0.22f));
        c.drawRect(0, curb, w, ground, fill);
        line.setStrokeWidth(dp);
        line.setColor(0x26FFFFFF);
        c.drawLine(0, curb, w, curb, line);
        line.setColor(night ? 0x1FFFFFFF : 0x33FFFFFF);
        c.drawLines(dashLines, line);

        // Streetlights: posts, then warm pools where they're on
        line.setStrokeWidth(1.4f * dp);
        line.setColor(SkyView.lerpColor(skylineColor, 0xFF5A606C, 0.35f));
        c.drawLines(postLines, line);
        for (int i = 0; i < lampX.length; i++) {
            float lv = lampLevel[i];
            if (lv <= 0.01f) continue;
            float hx = lampX[i] + 5 * dp, hy = curb - 24 * dp;
            // One soft pool per lamp, tall enough to take in the lamp head and the pavement.
            spriteWide(c, hx, curb - 8 * dp, 22 * dp, 20 * dp, warm, (wet ? 0.55f : 0.42f) * lv);
            dots.setStrokeWidth(2.6f * dp);
            dots.setColor(Color.argb((int) (255 * lv), 255, 236, 200));
            c.drawPoint(hx, hy + dp, dots);
        }

        drawTrees(c, night, ms);

        // Subway entrance: two green globes that brighten when a 6 train is due
        float globe = night ? 0.35f + 0.65f * subwayPulse : 0.2f + 0.5f * subwayPulse;
        for (int s = -1; s <= 1; s += 2) {
            float gx = subwayX + s * 8 * dp, gy = curb - 10 * dp;
            line.setColor(0xFF3A3F45);
            c.drawLine(gx, curb, gx, gy, line);
            sprite(c, gx, gy, 9 * dp, green, 0.6f * globe);
            dots.setStrokeWidth(3.4f * dp);
            dots.setColor(SkyView.lerpColor(0xFF1F6B3F, 0xFF7CFFB2, globe));
            c.drawPoint(gx, gy, dots);
        }

        // Citi Bike dock: one little blue bike per real bike docked
        line.setStrokeWidth(1.1f * dp);
        line.setColor(0xFF2E8BE6);
        for (int i = 0; i < dockBikes; i++) {
            float bx = dockX + i * 5.5f * dp, by = curb - 2 * dp;
            c.drawCircle(bx, by, 1.6f * dp, line);
            c.drawLine(bx, by - 1.6f * dp, bx + 1.5f * dp, by - 4 * dp, line);
        }

        // Bus stop sign
        line.setStrokeWidth(1.2f * dp);
        line.setColor(0xFF4A505A);
        c.drawLine(busStopX, curb, busStopX, curb - 16 * dp, line);
        fill.setColor(0xFF2F6FD0);
        c.drawRect(busStopX - 2 * dp, curb - 19 * dp, busStopX + 2 * dp, curb - 14 * dp, fill);

        drawPeople(c, night, wet);
        drawVehicles(c, night, wet, -1);
        drawVehicles(c, night, wet, 1);
    }

    /** Season colours for the canopy, or 0 for bare branches (winter). Month is 1-12. */
    private int canopyColor(int i) {
        switch (month) {
            case 4: return day < 20 ? 0xFFF4C7D6 : 0xFF9CC77A;   // blossom, then fresh green
            case 5: case 6: case 7: case 8: return 0xFF3E6B3F;
            case 9: return i % 3 == 0 ? 0xFF8A8F3A : 0xFF52733D; // first yellowing
            case 10: return i % 3 == 0 ? 0xFFD9822B : i % 3 == 1 ? 0xFFB4442F : 0xFFD4A531;
            case 11: return day < 15 ? 0xFF9A5B2E : 0;           // last brown leaves, then bare
            default: return 0;                                   // December to March
        }
    }

    private void drawTrees(Canvas c, boolean night, long ms) {
        float top = curb - 15 * dp;
        line.setStrokeWidth(1.6f * dp);
        int trunk = night ? 0xFF2A2B30 : 0xFF3B3430;
        for (int i = 0; i < treeX.length; i++) {
            float x = treeX[i];
            line.setColor(trunk);
            c.drawLine(x, curb, x, top + 2 * dp, line);
            int leaf = canopyColor(i);
            if (leaf == 0) { // bare branches
                line.setStrokeWidth(1f * dp);
                c.drawLine(x, top + 3 * dp, x - 5 * dp, top - 3 * dp, line);
                c.drawLine(x, top + 1 * dp, x + 5 * dp, top - 4 * dp, line);
                c.drawLine(x, top + 2 * dp, x + 1 * dp, top - 6 * dp, line);
                line.setStrokeWidth(1.6f * dp);
                continue;
            }
            fill.setColor(night ? SkyView.lerpColor(leaf, 0xFF0D0F14, 0.72f * darkness) : leaf);
            float rad = (month == 11 ? 4.8f : 6.5f) * dp;
            c.drawCircle(x - 2.5f * dp, top, rad, fill);
            c.drawCircle(x + 3 * dp, top - 1.5f * dp, rad * 0.85f, fill);
        }
        // Holiday lights on the bare trees, 1 December to 6 January, twinkling softly
        boolean holidays = month == 12 || (month == 1 && day <= 6);
        if (holidays && darkness > 0.2f) {
            float tw = 0.65f + 0.35f * (float) Math.sin(ms / 900.0);
            dots.setStrokeWidth(2f * dp);
            dots.setColor(Color.argb((int) (230 * darkness * tw), 255, 226, 160));
            c.drawPoints(treeLights, dots);
        }
    }

    private void drawPeople(Canvas c, boolean night, boolean wet) {
        int n = 0;
        for (int i = 0; i < MAX_P; i++) {
            if (!pOn[i]) continue;
            float bob = (float) Math.abs(Math.sin(pPhase[i])) * 0.7f * dp;
            pLines[n * 4] = pX[i];
            pLines[n * 4 + 1] = curb - 2 * dp;
            pLines[n * 4 + 2] = pX[i];
            pLines[n * 4 + 3] = curb - 8 * dp - bob;
            pHeads[n * 2] = pX[i];
            pHeads[n * 2 + 1] = curb - 10.5f * dp - bob;
            n++;
        }
        if (n == 0) return;
        int color = night ? 0xB3AEB6C8 : 0xE61B1E25;
        line.setStrokeWidth(2.2f * dp);
        line.setColor(color);
        c.drawLines(pLines, 0, n * 4, line);
        dots.setStrokeWidth(3f * dp);
        dots.setColor(color);
        c.drawPoints(pHeads, 0, n * 2, dots);
        if (wet) { // umbrellas
            line.setStrokeWidth(1.4f * dp);
            line.setColor(night ? 0x99707A90 : 0xFF2A2F3A);
            for (int k = 0; k < n; k++) {
                float x = pHeads[k * 2], y = pHeads[k * 2 + 1] - 1.5f * dp;
                r.set(x - 4.5f * dp, y - 3 * dp, x + 4.5f * dp, y + 3 * dp);
                c.drawArc(r, 180, 180, false, line);
            }
        }
    }

    private void drawVehicles(Canvas c, boolean night, boolean wet, int dir) {
        float base = dir > 0 ? laneNear : laneFar;
        for (int i = 0; i < MAX_V; i++) {
            if (!vOn[i] || vDir[i] != dir) continue;
            float x = vX[i], len = vLen[i];
            if (vKind[i] == BIKE) {
                float y = base - 2 * dp;
                line.setStrokeWidth(1.1f * dp);
                line.setColor(night ? 0xFF8A93A6 : 0xFF2A2F3A);
                c.drawCircle(x + 1.6f * dp, y, 1.6f * dp, line);
                c.drawCircle(x + len - 1.6f * dp, y, 1.6f * dp, line);
                c.drawLine(x + 1.6f * dp, y, x + len / 2, y - 4 * dp, line);
                c.drawLine(x + len / 2, y - 4 * dp, x + len - 1.6f * dp, y, line);
                if (night) {
                    float fx = dir > 0 ? x + len : x;
                    sprite(c, fx, y - 2 * dp, 5 * dp, head, 0.6f);
                }
                continue;
            }
            float bodyH = (vKind[i] == BUS ? 12 : 6.5f) * dp;
            r.set(x, base - bodyH, x + len, base);
            fill.setColor(night ? SkyView.lerpColor(vColor[i], 0xFF101216, 0.7f) : vColor[i]);
            c.drawRoundRect(r, 2.5f * dp, 2.5f * dp, fill);
            if (vKind[i] == BUS) {
                fill.setColor(night ? 0xCCFFF1C9 : 0x66FFFFFF); // window strip, lit at night
                c.drawRect(x + 4 * dp, base - bodyH + 2.5f * dp, x + len - 4 * dp, base - bodyH + 6 * dp, fill);
            } else if (!night) {
                fill.setColor(0x40FFFFFF); // roof highlight
                c.drawRect(x + len * 0.3f, base - bodyH, x + len * 0.72f, base - bodyH + 1.5f * dp, fill);
            }
            if (night || wet) {
                float front = dir > 0 ? x + len : x, back = dir > 0 ? x : x + len;
                float ly = base - bodyH * 0.45f;
                sprite(c, front, ly, 11 * dp, head, night ? 0.55f : 0.3f);
                dots.setStrokeWidth(2 * dp);
                dots.setColor(0xFFFFF4D6);
                c.drawPoint(front, ly, dots);
                dots.setColor(0xFFFF4A3D);
                c.drawPoint(back, ly, dots);
            }
        }
    }

    private void sprite(Canvas c, float x, float y, float radius, PorterDuffColorFilter tint, float alpha) {
        spriteWide(c, x, y, radius, radius, tint, alpha);
    }

    private void spriteWide(Canvas c, float x, float y, float rx, float ry, PorterDuffColorFilter tint, float alpha) {
        if (alpha <= 0.01f) return;
        glow.setColorFilter(tint);
        glow.setAlpha((int) (255 * Math.min(1f, alpha)));
        c.save();
        c.translate(x, y);
        c.scale(rx * 2 / puffW, ry * 2 / puffW);
        c.drawBitmap(puff, -puffW / 2, -puffW / 2, glow);
        c.restore();
    }
}
