package com.rafi.glasshome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. All D-pad input is handled here against a simple
 * three-zone model (top bar, dock, grid) instead of Android's focus search, so
 * navigation is deterministic: Up/Down always move exactly one row.
 */
public class HomeActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int ZONE_TOP = 0, ZONE_DOCK = 1, ZONE_GRID = 2;
    private static final int COLS = 4;
    private static final int PILL_INPUTS = 0, PILL_WIFI = 1, PILL_SETTINGS = 2;
    private static final float PILL_SCALE = 1.06f;
    private static final String KEY_FOCUS_ZONE = "focusZone";
    private static final String KEY_FOCUS_PKG = "focusPkg";
    private static final String KEY_TOP_INDEX = "topIndex";
    private static final String[] WALLPAPER_NAMES = {"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"};
    private static final String LIVE_TV_PKG = "com.vt.tv.tvcenter";

    // Geometry, in px (derived from dp in computeMetrics).
    private float dp;
    private int screenW, screenH, side, gap, cardW, cardH, bandPadH, bandPadV, bandH, bandTop, cellW, cellH, gridTop;
    private float cardRadius;

    private FrameLayout root, page;
    private ImageView wallpaper;
    private View scrim, glow;
    private GradientDrawable glowDrawable;
    private int glowColor = 0;
    private ValueAnimator glowAnim;
    private GlassHighlight highlight;
    private TextView greeting, clock, date, title, wifiLabel;
    private final View[] pills = new View[3];
    private LinearLayout dockRow;
    private RecyclerView grid;
    private GridAdapter gridAdapter;
    private InputsMenu inputsMenu;

    private final List<TileView> dockTiles = new ArrayList<>();
    private final List<AppInfo> dockApps = new ArrayList<>();
    private final List<AppInfo> gridApps = new ArrayList<>();

    private int zone = ZONE_DOCK, topIndex, dockIndex, gridIndex;
    private View focusedView;
    private boolean laidOut, inForeground, selectArmed;

    private SharedPreferences prefs;
    private ConnectivityManager connectivity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long wallpaperStamp = -1;

    private final Runnable reloadApps = this::reloadApps;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            handler.removeCallbacks(reloadApps);
            handler.postDelayed(reloadApps, 400);
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    setNetworkName(describeNetwork(caps));
                }

                @Override
                public void onLost(Network network) {
                    setNetworkName("Offline");
                }
            };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE);
        computeMetrics();
        buildViews();
        setContentView(root);
        inputsMenu = new InputsMenu(this, root);

        reloadApps();
        restoreFocusState();
        afterLayout(() -> {
            laidOut = true;
            applyFocus(false);
        });

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        connectivity = getSystemService(ConnectivityManager.class);
        setNetworkName("Wi-Fi");
        connectivity.registerDefaultNetworkCallback(networkCallback, handler);
        prefs.registerOnSharedPreferenceChangeListener(this);
        getExternalFilesDir(null); // creates the folder the wallpaper can be pushed to
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        updateClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        checkWallpaper(); // a file stat; pressing Home picks up a newly pushed wallpaper
    }

    @Override
    protected void onStop() {
        super.onStop();
        inForeground = false;
        unregisterReceiver(timeReceiver);
        inputsMenu.dismiss();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        connectivity.unregisterNetworkCallback(networkCallback);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        io.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the dock. Coming back from an app
        // keeps the remembered focus.
        if (inForeground) {
            inputsMenu.dismiss();
            if (!dockApps.isEmpty()) zone = ZONE_DOCK;
            applyFocus(true);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (ConfigReceiver.KEY_DOCK.equals(key)) {
            reloadApps();
        } else if (ConfigReceiver.KEY_NAME.equals(key)) {
            updateClock();
        }
    }

    // ---------------------------------------------------------------- layout

    private void computeMetrics() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        dp = dm.density;
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        side = px(60);
        gap = px(24);
        cardW = (screenW - 2 * side - (COLS - 1) * gap) / COLS;
        cardH = cardW * 9 / 16;
        cardRadius = 14 * dp;
        bandPadH = px(20);
        bandPadV = px(18);
        bandH = cardH + 2 * bandPadV;
        bandTop = screenH - px(28) - bandH;
        cellW = cardW + gap;
        cellH = cardH + px(56);
        gridTop = screenH + px(16);
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setVisibility(View.GONE);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x59000000, 0xD9000000}));
        scrim.setVisibility(View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        page = new FrameLayout(this);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        root.addView(page, new FrameLayout.LayoutParams(-1, screenH));

        // Ambient glow behind the app name, tinted from the focused dock app's banner.
        glow = new View(this);
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(300 * dp);
        glowDrawable.setGradientCenter(0.5f, 0.5f);
        glowDrawable.setColors(new int[]{0, 0});
        glow.setBackground(glowDrawable);
        // Centred on the title; big enough that the gradient fades out before the view edge.
        page.addView(glow, at(px(900), px(640), side + px(70) - px(450), bandTop - px(10) - px(320)));

        // Dock band: slightly lighter than the background.
        View band = new View(this);
        GradientDrawable bandBg = new GradientDrawable();
        bandBg.setCornerRadius(30 * dp);
        bandBg.setColor(0x17FFFFFF);
        bandBg.setStroke(Math.max(1, px(1)), 0x12FFFFFF);
        band.setBackground(bandBg);
        page.addView(band, at(COLS * cardW + (COLS - 1) * gap + 2 * bandPadH, bandH, side - bandPadH, bandTop));

        highlight = new GlassHighlight(this);
        page.addView(highlight, new FrameLayout.LayoutParams(-1, -1));

        title = text(300, 42, 0xFFFFFFFF);
        title.setGravity(Gravity.BOTTOM | Gravity.START);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        page.addView(title, at(screenW - 2 * side, px(64), side, bandTop - px(76)));

        LinearLayout clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        greeting = text(400, 19, 0xB3FFFFFF);
        clock = text(200, 84, 0xFFFFFFFF);
        clock.setLetterSpacing(-0.02f);
        date = text(400, 19, 0x99FFFFFF);
        clockBlock.addView(greeting, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(2);
        clockBlock.addView(clock, clp);
        clockBlock.addView(date, new LinearLayout.LayoutParams(-2, -2));
        page.addView(clockBlock, at(-2, -2, side, px(34)));

        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setClipChildren(false);
        pills[PILL_INPUTS] = makePill(pillRow, Icons.INPUT, "Inputs", false);
        pills[PILL_WIFI] = makePill(pillRow, Icons.WIFI, "Wi-Fi", true);
        pills[PILL_SETTINGS] = makePill(pillRow, Icons.SETTINGS, "Settings", true);
        wifiLabel = (TextView) ((LinearLayout) pills[PILL_WIFI]).getChildAt(1);
        wifiLabel.setMaxWidth(px(200));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.topMargin = px(40);
        plp.rightMargin = side;
        page.addView(pillRow, plp);

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setClipChildren(false);
        page.addView(dockRow, at(-2, cardH, side, bandTop + bandPadV));

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, COLS));
        gridAdapter = new GridAdapter(cellW, cellH, cardW, cardH, cardRadius);
        grid.setAdapter(gridAdapter);
        grid.setItemAnimator(null);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setFocusable(false);
        grid.setDescendantFocusability(RecyclerView.FOCUS_BLOCK_DESCENDANTS);
        page.addView(grid, at(COLS * cellW, cellH, side, gridTop));
    }

    private static FrameLayout.LayoutParams at(int w, int h, int left, int top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = left;
        lp.topMargin = top;
        return lp;
    }

    private TextView text(int weight, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(Fonts.get(this, weight));
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        // Variable-font measuring can come out a hair narrower than the drawn text,
        // which wraps the last word onto a clipped second line.
        t.setSingleLine(true);
        return t;
    }

    private View makePill(LinearLayout row, int icon, String label, boolean spaced) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(px(15), 0, px(18), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(21 * dp);
        bg.setColor(0x1FFFFFFF);
        bg.setStroke(Math.max(1, px(1)), 0x1AFFFFFF);
        p.setBackground(bg);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Icons(icon, 0xE6FFFFFF));
        p.addView(iv, new LinearLayout.LayoutParams(px(18), px(18)));
        TextView t = text(500, 15, 0xE6FFFFFF);
        t.setText(label);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(9);
        p.addView(t, tlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(42));
        if (spaced) lp.leftMargin = px(12);
        row.addView(p, lp);
        return p;
    }

    private void afterLayout(Runnable r) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
    }

    // ---------------------------------------------------------------- apps

    private List<String> dockPackages() {
        List<String> out = new ArrayList<>();
        String custom = prefs.getString(ConfigReceiver.KEY_DOCK, null);
        if (custom != null) {
            for (String s : custom.split(",")) if (!s.isEmpty()) out.add(s);
        } else {
            for (String s : getResources().getStringArray(R.array.default_dock)) out.add(s);
        }
        return out;
    }

    private void reloadApps() {
        String keepPkg = focusedPackage();
        List<AppInfo> all = AppInfo.loadAll(this);
        Map<String, AppInfo> byPkg = new HashMap<>();
        for (AppInfo a : all) byPkg.put(a.pkg, a);

        dockApps.clear();
        for (String pkg : dockPackages()) {
            AppInfo a = byPkg.get(pkg);
            if (a != null && !dockApps.contains(a) && dockApps.size() < COLS) dockApps.add(a);
        }
        gridApps.clear();
        for (AppInfo a : all) if (!dockApps.contains(a)) gridApps.add(a);

        dockRow.removeAllViews();
        dockTiles.clear();
        for (int i = 0; i < dockApps.size(); i++) {
            TileView t = new TileView(this, cardW, cardH, cardRadius, false);
            t.bind(dockApps.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
            if (i > 0) lp.leftMargin = gap;
            dockRow.addView(t, lp);
            dockTiles.add(t);
        }

        gridAdapter.setItems(gridApps);
        int rows = (gridApps.size() + COLS - 1) / COLS;
        grid.getLayoutParams().height = Math.max(1, rows) * cellH;
        page.getLayoutParams().height = Math.max(screenH, gridTop + rows * cellH + px(40));
        page.requestLayout();

        focusedView = null;
        if (keepPkg != null) selectPackage(keepPkg);
        clampFocus();
        if (laidOut) afterLayout(() -> applyFocus(false));
    }

    // ---------------------------------------------------------------- focus model

    private String focusedPackage() {
        if (zone == ZONE_DOCK && dockIndex < dockApps.size()) return dockApps.get(dockIndex).pkg;
        if (zone == ZONE_GRID && gridIndex < gridApps.size()) return gridApps.get(gridIndex).pkg;
        return null;
    }

    private boolean selectPackage(String pkg) {
        for (int i = 0; i < dockApps.size(); i++) {
            if (dockApps.get(i).pkg.equals(pkg)) {
                zone = ZONE_DOCK;
                dockIndex = i;
                return true;
            }
        }
        for (int i = 0; i < gridApps.size(); i++) {
            if (gridApps.get(i).pkg.equals(pkg)) {
                zone = ZONE_GRID;
                gridIndex = i;
                return true;
            }
        }
        return false;
    }

    private void clampFocus() {
        dockIndex = Math.max(0, Math.min(dockIndex, dockApps.size() - 1));
        gridIndex = Math.max(0, Math.min(gridIndex, gridApps.size() - 1));
        topIndex = Math.max(0, Math.min(topIndex, pills.length - 1));
        if (zone == ZONE_DOCK && dockApps.isEmpty()) zone = gridApps.isEmpty() ? ZONE_TOP : ZONE_GRID;
        if (zone == ZONE_GRID && gridApps.isEmpty()) zone = dockApps.isEmpty() ? ZONE_TOP : ZONE_DOCK;
    }

    private void restoreFocusState() {
        topIndex = prefs.getInt(KEY_TOP_INDEX, PILL_INPUTS);
        int savedZone = prefs.getInt(KEY_FOCUS_ZONE, ZONE_DOCK);
        String pkg = prefs.getString(KEY_FOCUS_PKG, null);
        zone = ZONE_DOCK;
        dockIndex = 0;
        if (savedZone == ZONE_TOP) {
            zone = ZONE_TOP;
        } else if (pkg != null) {
            selectPackage(pkg);
        }
        clampFocus();
    }

    private void saveFocusState() {
        SharedPreferences.Editor e = prefs.edit()
                .putInt(KEY_FOCUS_ZONE, zone)
                .putInt(KEY_TOP_INDEX, topIndex);
        String pkg = focusedPackage();
        if (pkg != null) e.putString(KEY_FOCUS_PKG, pkg);
        e.apply();
    }

    private void moveTo(int newZone, int index) {
        zone = newZone;
        if (newZone == ZONE_TOP) topIndex = index;
        else if (newZone == ZONE_DOCK) dockIndex = index;
        else gridIndex = index;
        applyFocus(true);
        saveFocusState();
    }

    private void onLeft() {
        if (zone == ZONE_TOP && topIndex > 0) moveTo(ZONE_TOP, topIndex - 1);
        else if (zone == ZONE_DOCK && dockIndex > 0) moveTo(ZONE_DOCK, dockIndex - 1);
        else if (zone == ZONE_GRID && gridIndex % COLS > 0) moveTo(ZONE_GRID, gridIndex - 1);
        // At the left end of any row: stay put.
    }

    private void onRight() {
        if (zone == ZONE_TOP && topIndex < pills.length - 1) moveTo(ZONE_TOP, topIndex + 1);
        else if (zone == ZONE_DOCK && dockIndex < dockApps.size() - 1) moveTo(ZONE_DOCK, dockIndex + 1);
        else if (zone == ZONE_GRID && gridIndex % COLS < COLS - 1 && gridIndex + 1 < gridApps.size()) {
            moveTo(ZONE_GRID, gridIndex + 1);
        }
    }

    private void onUp() {
        if (zone == ZONE_DOCK) {
            moveTo(ZONE_TOP, topIndex);
        } else if (zone == ZONE_GRID) {
            if (gridIndex >= COLS) moveTo(ZONE_GRID, gridIndex - COLS);
            else if (!dockApps.isEmpty()) moveTo(ZONE_DOCK, Math.min(gridIndex, dockApps.size() - 1));
            else moveTo(ZONE_TOP, topIndex);
        }
    }

    private void onDown() {
        if (zone == ZONE_TOP) {
            if (!dockApps.isEmpty()) moveTo(ZONE_DOCK, dockIndex);
            else if (!gridApps.isEmpty()) moveTo(ZONE_GRID, 0);
        } else if (zone == ZONE_DOCK) {
            if (!gridApps.isEmpty()) moveTo(ZONE_GRID, Math.min(dockIndex, Math.min(COLS, gridApps.size()) - 1));
        } else if (zone == ZONE_GRID) {
            int row = gridIndex / COLS, lastRow = (gridApps.size() - 1) / COLS;
            if (row < lastRow) moveTo(ZONE_GRID, Math.min(gridIndex + COLS, gridApps.size() - 1));
        }
    }

    /** Puts the visuals (tile scale, highlight, scroll, hero title) in line with the focus state. */
    private void applyFocus(boolean animate) {
        if (!laidOut) return;
        View target;
        RectF rect;
        float radius;
        if (zone == ZONE_TOP) {
            target = pills[topIndex];
            rect = scaled(rectInPage(target), PILL_SCALE, px(3));
            radius = rect.height() / 2f;
        } else {
            TileView tile;
            if (zone == ZONE_DOCK) {
                tile = dockTiles.get(dockIndex);
            } else {
                gridAdapter.setFocused(gridIndex);
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(gridIndex);
                if (vh == null) {
                    afterLayout(() -> applyFocus(animate));
                    grid.requestLayout();
                    return;
                }
                tile = (TileView) vh.itemView;
            }
            target = tile;
            rect = scaled(rectInPage(tile.card), TileView.FOCUS_SCALE, px(7));
            radius = cardRadius * TileView.FOCUS_SCALE + px(7);
        }
        if (zone != ZONE_GRID) gridAdapter.setFocused(-1);

        if (focusedView != target) {
            setViewFocused(focusedView, false, animate);
            setViewFocused(target, true, animate);
            focusedView = target;
        }
        highlight.moveTo(rect, radius, animate);
        scrollPage(animate);
        updateHero(animate);
    }

    private void setViewFocused(View v, boolean focused, boolean animate) {
        if (v == null) return;
        if (v instanceof TileView) {
            ((TileView) v).setFocusedState(focused, animate);
        } else {
            float s = focused ? PILL_SCALE : 1f;
            v.animate().cancel();
            if (animate) v.animate().scaleX(s).scaleY(s).setDuration(200).start();
            else {
                v.setScaleX(s);
                v.setScaleY(s);
            }
        }
    }

    private RectF rectInPage(View v) {
        float x = 0, y = 0;
        View c = v;
        while (c != null && c != page) {
            x += c.getLeft();
            y += c.getTop();
            ViewParent p = c.getParent();
            c = p instanceof View ? (View) p : null;
        }
        return new RectF(x, y, x + v.getWidth(), y + v.getHeight());
    }

    private static RectF scaled(RectF r, float scale, float pad) {
        float hw = r.width() * scale / 2f + pad, hh = r.height() * scale / 2f + pad;
        return new RectF(r.centerX() - hw, r.centerY() - hh, r.centerX() + hw, r.centerY() + hh);
    }

    private void scrollPage(boolean animate) {
        float y = 0;
        if (zone == ZONE_GRID) {
            // First grid row sits just under the dock (which parks near the top);
            // after the second row, each step down scrolls exactly one row.
            int row = gridIndex / COLS;
            y = -(bandTop - px(24)) - Math.max(0, row - 1) * cellH;
        }
        if (page.getTranslationY() == y) return;
        page.animate().cancel();
        if (animate) {
            page.animate().translationY(y).setDuration(450).setInterpolator(new DecelerateInterpolator(2.2f)).start();
        } else {
            page.setTranslationY(y);
        }
    }

    private void updateHero(boolean animate) {
        float alpha = zone == ZONE_DOCK ? 1f : zone == ZONE_TOP ? 0.45f : 0f;
        if (!dockApps.isEmpty()) {
            AppInfo a = dockApps.get(zone == ZONE_DOCK ? dockIndex : Math.min(dockIndex, dockApps.size() - 1));
            if (!a.label.contentEquals(title.getText())) {
                title.setText(a.label);
                if (animate) title.setAlpha(0.2f);
            }
            setGlowColor(a.glowColor(), animate);
        } else {
            title.setText("");
        }
        title.animate().cancel();
        glow.animate().cancel();
        if (animate) {
            title.animate().alpha(alpha).setDuration(260).start();
            glow.animate().alpha(alpha).setDuration(400).start();
        } else {
            title.setAlpha(alpha);
            glow.setAlpha(alpha);
        }
    }

    private void setGlowColor(int color, boolean animate) {
        if (color == glowColor) return;
        if (glowAnim != null) glowAnim.cancel();
        int from = glowColor == 0 ? (color & 0x00FFFFFF) : glowColor;
        glowColor = color;
        if (!animate) {
            paintGlow(color);
            return;
        }
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), from, color);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> paintGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void paintGlow(int c) {
        int rgb = c & 0x00FFFFFF;
        int a = c >>> 24;
        glowDrawable.setColors(new int[]{((a * 0x5C / 255) << 24) | rgb, ((a * 0x22 / 255) << 24) | rgb, rgb});
        title.setShadowLayer(26 * dp, 0, 0, ((a * 0x99 / 255) << 24) | rgb);
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        boolean nav = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;

        if (inputsMenu.isShowing()) {
            if (nav || select || code == KeyEvent.KEYCODE_BACK) {
                if (down && e.getRepeatCount() == 0 || down && nav) inputsMenu.onKey(code);
                selectArmed = false;
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (nav) {
            if (down) {
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: onLeft(); break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: onRight(); break;
                    case KeyEvent.KEYCODE_DPAD_UP: onUp(); break;
                    default: onDown();
                }
            }
            return true;
        }
        if (select) {
            // Act on release so the key-up doesn't leak into the app we open.
            if (down && e.getRepeatCount() == 0) selectArmed = true;
            else if (!down && selectArmed && !e.isCanceled()) {
                selectArmed = false;
                onSelect();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            // Home never closes; Back returns to the dock.
            if (down && e.getRepeatCount() == 0 && zone != ZONE_DOCK && !dockApps.isEmpty()) {
                moveTo(ZONE_DOCK, dockIndex);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void onSelect() {
        if (zone == ZONE_TOP) {
            switch (topIndex) {
                case PILL_INPUTS: {
                    RectF r = rectInPage(pills[PILL_INPUTS]);
                    r.offset(0, page.getTranslationY());
                    inputsMenu.show(r);
                    break;
                }
                // CLEAR_TASK so Settings opens on the right page rather than wherever it was left.
                case PILL_WIFI:
                    startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK), null);
                    break;
                default:
                    startSafely(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK), null);
            }
        } else if (zone == ZONE_DOCK && !dockApps.isEmpty()) {
            launch(dockApps.get(dockIndex), dockTiles.get(dockIndex).card);
        } else if (zone == ZONE_GRID && focusedView instanceof TileView) {
            launch(gridApps.get(gridIndex), ((TileView) focusedView).card);
        }
    }

    private void launch(AppInfo app, View from) {
        // The stock Live TV app resumes whatever source was used last (HDMI, AirPlay...),
        // so open the tuner explicitly.
        if (LIVE_TV_PKG.equals(app.pkg) && inputsMenu.openLiveTv()) return;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(app.component);
        }
        Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        startSafely(i, opts);
    }

    private void startSafely(Intent i, Bundle opts) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i, opts);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- top bar

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        String part = h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon" : "Good evening";
        String name = prefs.getString(ConfigReceiver.KEY_NAME, getString(R.string.greeting_name));
        greeting.setText(TextUtils.isEmpty(name) ? part : part + ", " + name);
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), now));
    }

    private String describeNetwork(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            TransportInfo ti = caps.getTransportInfo();
            String ssid = ti instanceof WifiInfo ? ((WifiInfo) ti).getSSID() : null;
            if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid)) return "Wi-Fi";
            if (ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length() - 1);
            return ssid;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        return "Connected";
    }

    private void setNetworkName(String name) {
        if (name.contentEquals(wifiLabel.getText())) return;
        wifiLabel.setText(name);
        if (zone == ZONE_TOP && laidOut) afterLayout(() -> applyFocus(true)); // pill width changed
    }

    // ---------------------------------------------------------------- wallpaper

    /** Optional wallpaper pushed over ADB (see README); otherwise pitch black. */
    private void checkWallpaper() {
        File dir = getExternalFilesDir(null);
        File found = null;
        if (dir != null) {
            for (String n : WALLPAPER_NAMES) {
                File f = new File(dir, n);
                if (f.isFile()) {
                    found = f;
                    break;
                }
            }
        }
        long stamp = found == null ? 0 : found.lastModified() * 31 + found.length();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        if (found == null) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            scrim.setVisibility(View.GONE);
            return;
        }
        File file = found;
        io.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), d);
            handler.post(() -> {
                wallpaper.setImageBitmap(bmp);
                wallpaper.setVisibility(bmp == null ? View.GONE : View.VISIBLE);
                scrim.setVisibility(bmp == null ? View.GONE : View.VISIBLE);
            });
        });
    }
}
