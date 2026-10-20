package com.rafi.glasshome;

import android.content.Context;
import android.graphics.Outline;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** An app tile: rounded 16:9 card with the name underneath, shown only while focused. */
final class TileView extends LinearLayout {
    static final float FOCUS_SCALE = 1.1f;

    final FrameLayout card;
    private final ImageView image;
    private final TextView label;
    private final boolean showLabel;
    private final int iconPad;
    AppInfo app;

    TileView(Context context, int cardW, int cardH, float radius, boolean showLabel) {
        super(context);
        this.showLabel = showLabel;
        float dp = context.getResources().getDisplayMetrics().density;
        iconPad = (int) (cardH * 0.22f);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);

        card = new FrameLayout(context);
        card.setBackgroundColor(0xFF1C1C1F);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        image = new ImageView(context);
        card.addView(image, new FrameLayout.LayoutParams(-1, -1));
        addView(card, new LayoutParams(cardW, cardH));

        label = new TextView(context);
        label.setTypeface(Fonts.get(context, 500));
        label.setTextSize(13);
        label.setTextColor(0xF2FFFFFF);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        label.setAlpha(0f);
        LayoutParams lp = new LayoutParams(cardW, LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (14 * dp);
        addView(label, lp);
        if (!showLabel) label.setVisibility(GONE);
    }

    void bind(AppInfo a) {
        app = a;
        image.setImageDrawable(a.art);
        if (a.isBanner) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setPadding(0, 0, 0, 0);
        } else {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(iconPad, iconPad, iconPad, iconPad);
        }
        label.setText(a.label);
    }

    void setFocusedState(boolean focused, boolean animate) {
        float s = focused ? FOCUS_SCALE : 1f;
        float a = focused && showLabel ? 1f : 0f;
        card.animate().cancel();
        label.animate().cancel();
        if (animate) {
            card.animate().scaleX(s).scaleY(s).setDuration(220).setInterpolator(new DecelerateInterpolator(1.5f)).start();
            label.animate().alpha(a).setDuration(focused ? 220 : 120).start();
        } else {
            card.setScaleX(s);
            card.setScaleY(s);
            label.setAlpha(a);
        }
    }
}
