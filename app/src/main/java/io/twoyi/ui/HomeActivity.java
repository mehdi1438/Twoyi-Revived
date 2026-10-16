/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import io.twoyi.R;
import io.twoyi.Render2Activity;
import io.twoyi.TwoyiStatusManager;
import io.twoyi.profile.Profile;
import io.twoyi.profile.ProfileAdapter;
import io.twoyi.profile.ProfileRepository;

/**
 * Home screen: 2-column grid of ROM profiles. Run = activate the profile's
 * rootfs (rename swap, never while a guest runs) and start Render2Activity.
 */
public class HomeActivity extends Activity implements ProfileAdapter.Listener {

    private static final int MENU_SETTINGS = 100;

    private ProfileRepository repo;
    private ProfileAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        repo = new ProfileRepository(this);

        RecyclerView recycler = findViewById(R.id.recyclerProfiles);
        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new ProfileAdapter(this, this);
        recycler.setAdapter(adapter);

        findViewById(R.id.fabAddRom).setOnClickListener(v ->
                Toast.makeText(this, R.string.home_add_todo, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfiles();
    }

    private void loadProfiles() {
        List<Profile> profiles = repo.list();
        adapter.setItems(profiles, repo.activeId());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SETTINGS, 0, R.string.title_settings)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRun(Profile profile) {
        if (TwoyiStatusManager.getInstance().isStarted()) {
            Toast.makeText(this, R.string.home_guest_running_refuse,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!profile.id.equals(repo.activeId())
                && !repo.storedRootfs(profile).isDirectory()) {
            Toast.makeText(this, R.string.home_no_rootfs, Toast.LENGTH_SHORT).show();
            return;
        }
        if (repo.activate(profile)) {
            profile.lastRun = System.currentTimeMillis();
            repo.save(profile);
            startActivity(new Intent(this, Render2Activity.class));
        } else {
            Toast.makeText(this, R.string.home_activate_failed, Toast.LENGTH_SHORT).show();
        }
        loadProfiles();
    }

    @Override
    public void onOverflow(View anchor, final Profile profile) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.home_menu_rename);
        menu.getMenu().add(0, 2, 1, R.string.home_menu_backup);
        menu.getMenu().add(0, 3, 2, R.string.home_menu_delete);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                promptRename(profile);
                return true;
            }
            if (id == 2) {
                Toast.makeText(this, R.string.home_backup_todo,
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            if (id == 3) {
                if (profile.id.equals(repo.activeId())) {
                    Toast.makeText(this, R.string.confirm_delete_title,
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                repo.delete(profile);
                loadProfiles();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void promptRename(final Profile profile) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.home_rename_hint);
        input.setText(profile.name);

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.home_menu_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        profile.name = name;
                        repo.save(profile);
                        loadProfiles();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
