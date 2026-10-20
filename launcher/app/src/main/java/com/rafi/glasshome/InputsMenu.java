package com.rafi.glasshome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact glass dropdown under the Inputs pill: HDMI ports, AV, Live TV (and AirPlay
 * when the TV has it). Switches through the TV input framework, so the TV's own
 * Live TV app does the actual switching.
 */
final class InputsMenu {
    private static final class Entry {
        final String id;
        final String label;
        /** The broadcast tuner is opened through its channel list; everything else as passthrough. */
        final boolean tuner;
        /** Physical ports show their cable state; other sources are always "available". */
        final boolean port;
        final boolean connected;

        Entry(String id, String label, boolean tuner, boolean port, boolean connected) {
            this.id = id;
            this.label = label;
            this.tuner = tuner;
            this.port = port;
            this.connected = connected;
        }
    }

    private final Context context;
    private final FrameLayout host;
    private final float dp;
    private final List<Entry> entries = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();
    private LinearLayout panel;
    private int selected;

    InputsMenu(Context context, FrameLayout host) {
        this.context = context;
        this.host = host;
        this.dp = context.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return panel != null;
    }

    /** @param anchor the Inputs pill's bounds in host coordinates */
    void show(RectF anchor) {
        if (panel != null) return;
        loadEntries();
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * dp);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(22 * dp);
        bg.setColor(0xF01E1E22);
        bg.setStroke((int) Math.max(1, dp), 0x2EFFFFFF);
        panel.setBackground(bg);

        rows.clear();
        for (Entry e : entries) panel.addView(makeRow(e));
        if (entries.isEmpty()) panel.addView(makeRow(new Entry(null, "No inputs found", false, false, false)));

        int width = (int) (250 * dp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT);
        int margin = (int) (40 * dp);
        lp.leftMargin = (int) Math.min(anchor.left, host.getWidth() - margin - width);
        lp.topMargin = (int) (anchor.bottom + 12 * dp);
        host.addView(panel, lp);

        selected = 0;
        updateSelection();
        panel.setAlpha(0f);
        panel.setTranslationY(-10 * dp);
        panel.setPivotY(0);
        panel.setScaleY(0.94f);
        panel.animate().alpha(1f).translationY(0).scaleY(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    /** Opens the broadcast tuner directly. Returns false if the TV has none. */
    boolean openLiveTv() {
        loadEntries();
        for (Entry e : entries) {
            if (e.tuner) {
                switchTo(e);
                return true;
            }
        }
        return false;
    }

    void dismiss() {
        if (panel == null) return;
        View p = panel;
        panel = null;
        p.animate().alpha(0f).translationY(-6 * dp).setDuration(130)
                .withEndAction(() -> host.removeView(p)).start();
    }

    /** Handles a key-down while open. */
    void onKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (selected < rows.size() - 1) {
                    selected++;
                    updateSelection();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (selected > 0) {
                    selected--;
                    updateSelection();
                } else {
                    dismiss();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (selected < entries.size()) switchTo(entries.get(selected));
                dismiss();
                break;
            default: // Back, Left, Right
                dismiss();
        }
    }

    private void loadEntries() {
        entries.clear();
        TvInputManager tim = context.getSystemService(TvInputManager.class);
        if (tim == null) return;
        List<Entry> hdmi = new ArrayList<>(), av = new ArrayList<>(), other = new ArrayList<>();
        Entry tuner = null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.isHidden(context) || info.getParentId() != null) continue;
            boolean system = (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!system) continue; // streaming apps register "inputs" too; only show the TV's own
            CharSequence custom = info.loadCustomLabel(context);
            String label = String.valueOf(TextUtils.isEmpty(custom) ? info.loadLabel(context) : custom);
            boolean connected = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
            switch (info.getType()) {
                case TvInputInfo.TYPE_HDMI:
                case TvInputInfo.TYPE_DVI:
                    hdmi.add(new Entry(info.getId(), label, false, true, connected));
                    break;
                case TvInputInfo.TYPE_COMPOSITE:
                case TvInputInfo.TYPE_COMPONENT:
                case TvInputInfo.TYPE_SVIDEO:
                case TvInputInfo.TYPE_SCART:
                case TvInputInfo.TYPE_VGA:
                    av.add(new Entry(info.getId(), label, false, true, connected));
                    break;
                case TvInputInfo.TYPE_TUNER:
                    // Hardware-backed inputs have ids ending in "/HW<n>". On this TV, AirPlay
                    // also registers as a tuner, so only the hardware tuner counts as Live TV.
                    if (!info.getId().contains("/HW")) {
                        other.add(new Entry(info.getId(), label, false, false, true));
                    } else if (tuner == null || info.getId().compareTo(tuner.id) < 0) {
                        tuner = new Entry(info.getId(), "Live TV", true, false, true);
                    }
                    break;
                default:
                    other.add(new Entry(info.getId(), label, false, false, true));
            }
        }
        Collator collator = Collator.getInstance();
        hdmi.sort((a, b) -> collator.compare(a.label, b.label));
        entries.addAll(hdmi);
        entries.addAll(av);
        if (tuner != null) entries.add(tuner);
        entries.addAll(other);
    }

    private View makeRow(Entry e) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int h = (int) (16 * dp);
        row.setPadding(h, 0, h, 0);

        TextView t = new TextView(context);
        t.setText(e.label);
        t.setTypeface(Fonts.get(context, 400));
        t.setTextSize(16);
        t.setTextColor(e.connected ? 0xF2FFFFFF : 0x80FFFFFF);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (e.port && e.connected) {
            ImageView dot = new ImageView(context);
            dot.setImageDrawable(new Icons(Icons.DOT, 0xFF30D158));
            int s = (int) (7 * dp);
            row.addView(dot, new LinearLayout.LayoutParams(s, s));
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (40 * dp)));
        rows.add(row);
        return row;
    }

    private void updateSelection() {
        for (int i = 0; i < rows.size(); i++) {
            if (i == selected) {
                GradientDrawable sel = new GradientDrawable();
                sel.setCornerRadius(14 * dp);
                sel.setColor(0x33FFFFFF);
                sel.setStroke((int) Math.max(1, dp), 0x40FFFFFF);
                rows.get(i).setBackground(sel);
            } else {
                rows.get(i).setBackground(null);
            }
        }
    }

    private void switchTo(Entry e) {
        if (e.id == null) return;
        Intent i;
        if (e.tuner) {
            i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelsUriForInput(e.id), TvContract.Channels.CONTENT_TYPE);
        } else {
            // The stock Inputs menu uses passthrough URIs for AirPlay too, even though
            // that input doesn't declare itself as passthrough.
            i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(e.id));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
        } catch (ActivityNotFoundException ex) {
            // Fall back to the TV's own input picker.
            try {
                context.startActivity(new Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
                // Nothing on this TV can switch inputs.
            }
        }
    }
}
