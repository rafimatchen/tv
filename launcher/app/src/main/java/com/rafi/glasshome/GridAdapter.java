package com.rafi.glasshome;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class GridAdapter extends RecyclerView.Adapter<GridAdapter.Holder> {
    private final List<AppInfo> items = new ArrayList<>();
    private final int cellW, cellH, cardW, cardH;
    private final float radius;
    private int focused = -1;

    GridAdapter(int cellW, int cellH, int cardW, int cardH, float radius) {
        this.cellW = cellW;
        this.cellH = cellH;
        this.cardW = cardW;
        this.cardH = cardH;
        this.radius = radius;
    }

    void setItems(List<AppInfo> apps) {
        items.clear();
        items.addAll(apps);
        focused = -1;
        notifyDataSetChanged();
    }

    /** Remembered so rebinding a recycled view shows the right state. */
    void setFocused(int position) {
        focused = position;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TileView tile = new TileView(parent.getContext(), cardW, cardH, radius, true);
        tile.setLayoutParams(new RecyclerView.LayoutParams(cellW, cellH));
        return new Holder(tile);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.tile.bind(items.get(position));
        holder.tile.setFocusedState(position == focused, false);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TileView tile;

        Holder(TileView tile) {
            super(tile);
            this.tile = tile;
        }
    }
}
