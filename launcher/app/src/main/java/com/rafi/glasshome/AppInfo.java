package com.rafi.glasshome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AppInfo {
    private static final int FALLBACK_GLOW = 0xFF8E8EA0;

    final String pkg;
    final ComponentName component;
    final String label;
    final Drawable art;
    final boolean isBanner;
    private int glowColor;
    private boolean glowSampled;

    private AppInfo(String pkg, ComponentName component, String label, Drawable art, boolean isBanner) {
        this.pkg = pkg;
        this.component = component;
        this.label = label;
        this.art = art;
        this.isBanner = isBanner;
    }

    /** Every enabled app with a TV launcher entry, sorted by name. */
    static List<AppInfo> loadAll(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);
        List<AppInfo> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : resolved) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(context.getPackageName()) || !seen.add(ai.packageName)) continue;
            Drawable banner = ai.loadBanner(pm);
            Drawable art = banner != null ? banner : ai.loadIcon(pm);
            apps.add(new AppInfo(ai.packageName, new ComponentName(ai.packageName, ai.name),
                    String.valueOf(ri.loadLabel(pm)), art, banner != null));
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    /** Dominant vivid colour of the app's artwork, brightened for use as a glow. */
    int glowColor() {
        if (!glowSampled) {
            glowColor = sampleGlow(art);
            glowSampled = true;
        }
        return glowColor;
    }

    private static int sampleGlow(Drawable d) {
        final int w = 32, h = 18;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(bmp));
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        bmp.recycle();

        // Hue histogram weighted by saturation x brightness; the heaviest bin wins.
        final int bins = 24;
        float[] weight = new float[bins];
        float[] r = new float[bins], g = new float[bins], b = new float[bins];
        float[] hsv = new float[3];
        for (int c : px) {
            if (Color.alpha(c) < 128) continue;
            Color.colorToHSV(c, hsv);
            float wgt = hsv[1] * hsv[2] * hsv[2];
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
            int bin = Math.min(bins - 1, (int) (hsv[0] / 360f * bins));
            weight[bin] += wgt;
            r[bin] += Color.red(c) * wgt;
            g[bin] += Color.green(c) * wgt;
            b[bin] += Color.blue(c) * wgt;
        }
        int best = -1;
        for (int i = 0; i < bins; i++) {
            if (best < 0 || weight[i] > weight[best]) best = i;
        }
        if (best < 0 || weight[best] < 0.6f) return FALLBACK_GLOW;
        int c = Color.rgb((int) (r[best] / weight[best]), (int) (g[best] / weight[best]), (int) (b[best] / weight[best]));
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(hsv[1], 0.85f);
        hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(hsv);
    }
}
