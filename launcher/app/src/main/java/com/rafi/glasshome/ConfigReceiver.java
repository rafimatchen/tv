package com.rafi.glasshome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Runtime overrides sent over ADB (see README):
 *   SET_DOCK --es dock "pkg1,pkg2,pkg3,pkg4"   (--es dock default = back to config.xml)
 *   SET_NAME --es name "Rafi"                   (--es name default = config.xml, --esn name = no name)
 * HomeActivity listens for the preference change and redraws.
 */
public class ConfigReceiver extends BroadcastReceiver {
    static final String PREFS = "glasshome";
    static final String KEY_DOCK = "dock";
    static final String KEY_NAME = "name";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        String action = String.valueOf(intent.getAction());
        if (action.endsWith("SET_DOCK")) {
            String dock = intent.getStringExtra("dock");
            if (dock == null || dock.trim().isEmpty() || dock.equals("default")) e.remove(KEY_DOCK);
            else e.putString(KEY_DOCK, dock.replace(" ", ""));
        } else if (action.endsWith("SET_NAME")) {
            String name = intent.getStringExtra("name");
            if ("default".equals(name)) e.remove(KEY_NAME);
            else e.putString(KEY_NAME, name == null ? "" : name.trim());
        }
        e.apply();
    }
}
