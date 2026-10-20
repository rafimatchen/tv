package com.rafi.glasshome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Outfit (SIL OFL 1.1) is a variable font; each weight is an instance of the one file. */
final class Fonts {
    private static final SparseArray<Typeface> CACHE = new SparseArray<>();

    private Fonts() {}

    static Typeface get(Context context, int weight) {
        Typeface t = CACHE.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(context.getAssets(), "fonts/Outfit.ttf")
                        .setFontVariationSettings("'wght' " + weight)
                        .setWeight(weight)
                        .build();
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.create(Typeface.SANS_SERIF, weight, false);
            CACHE.put(weight, t);
        }
        return t;
    }
}
