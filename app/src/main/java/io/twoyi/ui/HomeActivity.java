/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.List;

import io.twoyi.R;
import io.twoyi.profile.ProfileAdapter;
import io.twoyi.profile.ProfileManager;
import io.twoyi.profile.RomProfile;

/**
 * Home Activity displaying list of ROM Profiles (VMOS Pro card style).
 */
public class HomeActivity extends AppCompatActivity implements ProfileAdapter.OnProfileActionListener {

    private ProfileManager profileManager;
    private ProfileAdapter profileAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.title_rom_manager);
        }

        profileManager = new ProfileManager(this);

        RecyclerView recyclerView = findViewById(R.id.recyclerViewProfiles);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        profileAdapter = new ProfileAdapter(this, this);
        recyclerView.setAdapter(profileAdapter);

        Button btnAddRom = findViewById(R.id.btnAddRom);
        btnAddRom.setOnClickListener(v ->
            Toast.makeText(this, "Add ROM flow will be available in Step 4", Toast.LENGTH_SHORT).show()
        );

        Button btnRestoreRom = findViewById(R.id.btnRestoreRom);
        btnRestoreRom.setOnClickListener(v ->
            Toast.makeText(this, "Restore flow will be available in Step 5", Toast.LENGTH_SHORT).show()
        );

        loadProfiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfiles();
    }

    private void loadProfiles() {
        List<RomProfile> profiles = profileManager.getAllProfiles();
        String activeId = profileManager.getActiveProfileId();
        profileAdapter.setProfiles(profiles, activeId);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.title_settings)
                .setIcon(android.R.drawable.ic_menu_preferences)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRun(RomProfile profile) {
        if (io.twoyi.TwoyiStatusManager.getInstance().isStarted()) {
            Toast.makeText(this, "Guest is currently running. Reboot or stop the guest first.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean success = profileManager.swapToProfile(profile.getId());
        if (success) {
            startActivity(new Intent(this, io.twoyi.Render2Activity.class));
        } else {
            Toast.makeText(this, "Failed to swap profile", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onConfig(RomProfile profile) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = 32;
        layout.setPadding(pad, pad, pad, pad);

        final EditText inputName = new EditText(this);
        inputName.setHint("Profile Name");
        inputName.setText(profile.getName());
        layout.addView(inputName);

        final EditText inputRes = new EditText(this);
        inputRes.setHint("Resolution (e.g. 1080x2340)");
        inputRes.setText(profile.getResolution());
        layout.addView(inputRes);

        final EditText inputDpi = new EditText(this);
        inputDpi.setHint("DPI (e.g. 480)");
        inputDpi.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputDpi.setText(String.valueOf(profile.getDpi()));
        layout.addView(inputDpi);

        final EditText inputFps = new EditText(this);
        inputFps.setHint("FPS (e.g. 45 or 60)");
        inputFps.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputFps.setText(String.valueOf(profile.getFps()));
        layout.addView(inputFps);

        new AlertDialog.Builder(this)
                .setTitle("Configure ROM Profile")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName = inputName.getText().toString().trim();
                    String newRes = inputRes.getText().toString().trim();
                    String dpiStr = inputDpi.getText().toString().trim();
                    String fpsStr = inputFps.getText().toString().trim();

                    if (!newName.isEmpty()) profile.setName(newName);
                    if (!newRes.isEmpty()) profile.setResolution(newRes);
                    try {
                        if (!dpiStr.isEmpty()) profile.setDpi(Integer.parseInt(dpiStr));
                    } catch (NumberFormatException ignored) {}
                    try {
                        if (!fpsStr.isEmpty()) profile.setFps(Integer.parseInt(fpsStr));
                    } catch (NumberFormatException ignored) {}

                    try {
                        profileManager.saveProfile(profile);
                        loadProfiles();
                        Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        Toast.makeText(this, "Failed to save config: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onBackup(RomProfile profile) {
        Toast.makeText(this, "Backup flow will be available in Step 5", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDelete(RomProfile profile) {
        if (profile.getId().equals(profileManager.getActiveProfileId())) {
            Toast.makeText(this, "Cannot delete the active profile!", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(getString(R.string.confirm_delete_msg, profile.getName()))
                .setPositiveButton(R.string.btn_delete, (dialog, which) -> {
                    profileManager.deleteProfile(profile.getId());
                    loadProfiles();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
