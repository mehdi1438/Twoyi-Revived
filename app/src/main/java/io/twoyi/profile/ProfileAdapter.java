/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.profile;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import io.twoyi.R;
import io.twoyi.TwoyiStatusManager;

/**
 * Adapter for displaying ROM profiles in RecyclerView (Material Design card style with live running preview).
 */
public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.ViewHolder> {

    public interface OnProfileActionListener {
        void onRun(RomProfile profile);
        void onConfig(RomProfile profile);
        void onBackup(RomProfile profile);
        void onDelete(RomProfile profile);
    }

    private final Context context;
    private final List<RomProfile> profiles = new ArrayList<>();
    private String activeProfileId = "";
    private final OnProfileActionListener listener;

    public ProfileAdapter(Context context, OnProfileActionListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setProfiles(List<RomProfile> newProfiles, String activeProfileId) {
        this.profiles.clear();
        if (newProfiles != null) {
            this.profiles.addAll(newProfiles);
        }
        this.activeProfileId = activeProfileId != null ? activeProfileId : "";
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_rom_profile, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RomProfile profile = profiles.get(position);
        boolean isActive = profile.getId().equals(activeProfileId);
        boolean isRunning = isActive && TwoyiStatusManager.getInstance().isStarted();

        holder.tvProfileName.setText(profile.getName());
        holder.tvActiveBadge.setVisibility(isActive ? View.VISIBLE : View.GONE);

        // Live preview container when running
        holder.previewContainer.setVisibility(isRunning ? View.VISIBLE : View.GONE);

        String sizeFormatted = Formatter.formatFileSize(context, profile.getSizeBytes());
        holder.tvStorageUsage.setText(sizeFormatted);

        String dateFormatted = DateFormat.getDateTimeInstance().format(new Date(profile.getLastRunTime()));
        holder.tvLastRun.setText("Last Run: " + dateFormatted);

        holder.tvDisplayConfig.setText(String.format("Res: %s | DPI: %d | FPS: %d",
                profile.getResolution(), profile.getDpi(), profile.getFps()));

        String rootStatus = profile.isRootEnabled() ? "Root: Enabled" : "Root: Disabled";
        holder.tvRootStatus.setText(rootStatus);

        holder.btnDelete.setEnabled(!isActive && profiles.size() > 1);

        holder.fabRun.setOnClickListener(v -> {
            if (listener != null) listener.onRun(profile);
        });

        holder.btnConfig.setOnClickListener(v -> {
            if (listener != null) listener.onConfig(profile);
        });

        holder.btnBackup.setOnClickListener(v -> {
            if (listener != null) listener.onBackup(profile);
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(profile);
        });
    }

    @Override
    public int getItemCount() {
        return profiles.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvProfileName;
        TextView tvActiveBadge;
        TextView tvStorageUsage;
        TextView tvLastRun;
        TextView tvDisplayConfig;
        TextView tvRootStatus;
        FrameLayout previewContainer;

        ImageButton btnConfig;
        FloatingActionButton fabRun;
        Button btnBackup;
        Button btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvProfileName = itemView.findViewById(R.id.tvProfileName);
            tvActiveBadge = itemView.findViewById(R.id.tvActiveBadge);
            tvStorageUsage = itemView.findViewById(R.id.tvStorageUsage);
            tvLastRun = itemView.findViewById(R.id.tvLastRun);
            tvDisplayConfig = itemView.findViewById(R.id.tvDisplayConfig);
            tvRootStatus = itemView.findViewById(R.id.tvRootStatus);
            previewContainer = itemView.findViewById(R.id.previewContainer);

            btnConfig = itemView.findViewById(R.id.btnConfig);
            fabRun = itemView.findViewById(R.id.fabRun);
            btnBackup = itemView.findViewById(R.id.btnBackup);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
