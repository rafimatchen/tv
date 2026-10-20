package com.rafi.glasshome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Runtime overrides sent over ADB (see README):
 *   SET_DOCK --es dock "pkg1,pkg2,pkg3,pkg4"   (--es dock default = back to config.xml)
 *   SET_NAME --es name "Rafi"                   (--es name default = config.xml, --esn name = no name)
 *   SET_BUS_KEY --es key "..."                  (MTA Bus Time key; --esn key = go back to keyless)
 *   PREVIEW_SKY --es mood rain --ef hour 19.5   (screensaver preview; --es mood off = live)
 *   PREVIEW_SKY --es event bus|train            (play a street event now)
 * HomeActivity listens for the preference change and redraws.
 */
public class ConfigReceiver extends BroadcastReceiver {
    static final String PREFS = "glasshome";
    static final String KEY_DOCK = "dock";
    static final String KEY_NAME = "name";
    static final String KEY_BUS_KEY = "busKey";
    static final String KEY_PREVIEW_MOOD = "previewMood";
    static final String KEY_PREVIEW_HOUR = "previewHour";
    static final String KEY_PREVIEW_EVENT = "previewEvent";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        String action = String.valueOf(intent.getAction());
        if (action.endsWith("SET_DOCK")) {
            String dock = intent.getStringExtra("dock");
            if (dock == null || dock.trim().isEmpty() || dock.equals("default")) e.remove(KEY_DOCK);
            else e.putString(KEY_DOCK, dock.replace(" ", ""));
        } else if (action.endsWith("SET_BUS_KEY")) {
            // Kept in app storage, never in the repo or the APK.
            String key = intent.getStringExtra("key");
            if (key == null || key.trim().isEmpty()) e.remove(KEY_BUS_KEY);
            else e.putString(KEY_BUS_KEY, key.trim());
        } else if (action.endsWith("PREVIEW_SKY") && intent.hasExtra("event")) {
            // Timestamped so the same event can be played twice in a row.
            e.putString(KEY_PREVIEW_EVENT, intent.getStringExtra("event") + ":" + System.currentTimeMillis());
        } else if (action.endsWith("PREVIEW_SKY")) {
            String mood = intent.getStringExtra("mood");
            if (mood == null || mood.equals("off")) {
                e.remove(KEY_PREVIEW_MOOD).remove(KEY_PREVIEW_HOUR);
            } else {
                e.putString(KEY_PREVIEW_MOOD, mood);
                e.putFloat(KEY_PREVIEW_HOUR, intent.getFloatExtra("hour", -1f));
            }
        } else if (action.endsWith("SET_NAME")) {
            String name = intent.getStringExtra("name");
            if ("default".equals(name)) e.remove(KEY_NAME);
            else e.putString(KEY_NAME, name == null ? "" : name.trim());
        }
        e.apply();
    }
}
