/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.profile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.twoyi.R;

/**
 * Grid adapter for ROM profiles. Shows preview thumbnail (or placeholder),
 * name, meta line and Active/Stored status, plus Run and an overflow button.
 */
public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.VH> {

    public interface Listener {
        void onRun(Profile profile);

        void onOverflow(View anchor, Profile profile);
    }

    private final Context context;
    private final List<Profile> items = new ArrayList<>();
    private String activeId = null;
    private final Listener listener;

    public ProfileAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setItems(List<Profile> profiles, String activeId) {
        items.clear();
        if (profiles != null) {
            items.addAll(profiles);
        }
        this.activeId = activeId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_profile, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final Profile p = items.get(position);
        boolean isActive = p.id != null && p.id.equals(activeId);

        holder.name.setText(p.name);

        if (p.width > 0 && p.height > 0 && p.dpi > 0) {
            holder.meta.setText(context.getString(R.string.home_meta_resolution,
                    p.width, p.height, p.fps));
        } else {
            holder.meta.setText(R.string.home_meta_default);
        }

        holder.status.setText(isActive
                ? R.string.home_status_active : R.string.home_status_stored);

        File thumbFile = new ProfileRepository(context).thumb(p);
        Bitmap bmp = thumbFile.isFile() ? BitmapFactory.decodeFile(thumbFile.getPath()) : null;
        if (bmp != null) {
            holder.preview.setImageBitmap(bmp);
            holder.preview.setVisibility(View.VISIBLE);
            holder.placeholder.setVisibility(View.GONE);
        } else {
            holder.preview.setImageDrawable(null);
            holder.preview.setVisibility(View.INVISIBLE);
            holder.placeholder.setVisibility(View.VISIBLE);
        }

        holder.run.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRun(p);
            }
        });
        holder.overflow.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOverflow(v, p);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView preview;
        final TextView placeholder;
        final TextView status;
        final TextView name;
        final TextView meta;
        final View run;
        final ImageButton overflow;

        VH(@NonNull View itemView) {
            super(itemView);
            preview = itemView.findViewById(R.id.imgPreview);
            placeholder = itemView.findViewById(R.id.tvPlaceholder);
            status = itemView.findViewById(R.id.tvStatus);
            name = itemView.findViewById(R.id.tvName);
            meta = itemView.findViewById(R.id.tvMeta);
            run = itemView.findViewById(R.id.btnRun);
            overflow = itemView.findViewById(R.id.btnOverflow);
        }
    }
}
