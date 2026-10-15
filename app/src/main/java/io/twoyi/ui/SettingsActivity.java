/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.provider.DocumentsContract;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.util.Pair;

import com.microsoft.appcenter.crashes.Crashes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

import io.twoyi.R;
import io.twoyi.utils.AppKV;
import io.twoyi.utils.LogEvents;
import io.twoyi.utils.RomManager;
import io.twoyi.utils.UIHelper;

/**
 * @author weishu
 * @date 2022/1/2.
 */

public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_GET_FILE = 1000;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);
        SettingsFragment settingsFragment = new SettingsFragment();
        getFragmentManager().beginTransaction()
                .replace(R.id.settingsFrameLayout, settingsFragment)
                .commit();

        ActionBar actionBar = getSupportActionBar();

        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setBackgroundDrawable(getResources().getDrawable(R.color.colorPrimary));
            actionBar.setTitle(R.string.title_settings);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragment {
        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_settings);
        }

        private Preference findPreference(@StringRes int id) {
            String key = getString(id);
            return findPreference(key);
        }

        @Override
        public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            Preference importApp = findPreference(R.string.settings_key_import_app);
            Preference export = findPreference(R.string.settings_key_manage_files);

            Preference shutdown = findPreference(R.string.settings_key_shutdown);
            Preference reboot = findPreference(R.string.settings_key_reboot);
            Preference replaceRom = findPreference(R.string.settings_key_replace_rom);
            Preference factoryReset = findPreference(R.string.settings_key_factory_reset);

            Preference donate = findPreference(R.string.settings_key_donate);
            Preference sendLog = findPreference(R.string.settings_key_sendlog);
            Preference about = findPreference(R.string.settings_key_about);

            importApp.setOnPreferenceClickListener(preference -> {
                UIHelper.startActivity(getContext(), SelectAppActivity.class);
                return true;
            });

            export.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setType(DocumentsContract.Document.MIME_TYPE_DIR);
                startActivity(intent);
                return true;
            });

            shutdown.setOnPreferenceClickListener(preference -> {
                Activity activity = getActivity();
                activity.finishAffinity();
                RomManager.shutdown(activity);
                return true;
            });

            reboot.setOnPreferenceClickListener(preference -> {
                Activity activity = getActivity();
                activity.finishAndRemoveTask();
                RomManager.reboot(activity);
                return true;
            });

            replaceRom.setOnPreferenceClickListener(preference -> {

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);

                // you can only select one rootfs
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                intent.setType("*/*"); // apk file
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, REQUEST_GET_FILE);
                } catch (Throwable ignored) {
                    Toast.makeText(getContext(), "Error", Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            factoryReset.setOnPreferenceClickListener(preference -> {
                UIHelper.getDialogBuilder(getActivity())
                        .setTitle(android.R.string.dialog_alert_title)
                        .setMessage(R.string.factory_reset_confirm_message)
                        .setPositiveButton(R.string.i_confirm_it, (dialog, which) -> {
                            AppKV.setBooleanConfig(getActivity(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);
                            AppKV.setBooleanConfig(getActivity(), AppKV.FORCE_ROM_BE_RE_INSTALL, true);
                            dialog.dismiss();

                            RomManager.reboot(getActivity());
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                        .show();
                return true;
            });

            donate.setOnPreferenceClickListener(preference -> {
                Context context = getContext();
                if (context instanceof Activity) {
                    UIHelper.showDonateDialog((Activity) context);
                    return true;
                }
                return false;
            });

            sendLog.setOnPreferenceClickListener(preference -> {
                Context context = getActivity();
                byte[] bugreport = LogEvents.getBugreport(context);
                File tmpLog = new File(context.getCacheDir(), "bugreport.zip");
                try {
                    Files.write(tmpLog.toPath(), bugreport);
                } catch (IOException e) {
                    Crashes.trackError(e);
                }
                Uri uri = FileProvider.getUriForFile(context, "io.twoyi.fileprovider", tmpLog);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.setDataAndType(uri, "application/zip");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                context.startActivity(Intent.createChooser(shareIntent, getString(R.string.settings_key_sendlog)));

                return true;
            });

            about.setOnPreferenceClickListener(preference -> {
                UIHelper.startActivity(getContext(), AboutActivity.class);
                return true;
            });

            setupDisplayPreferences();
        }

        // ---- Phase 3: custom resolution and DPI ----

        private SwitchPreference mCustomResSwitch;
        private EditTextPreference mWidthPref;
        private EditTextPreference mHeightPref;
        private EditTextPreference mDpiPref;
        private ListPreference mPresetPref;

        private void setupDisplayPreferences() {
            Context context = getActivity();

            mCustomResSwitch = findPreference(getString(R.string.settings_key_custom_resolution));
            mWidthPref = findPreference(getString(R.string.settings_custom_width_key));
            mHeightPref = findPreference(getString(R.string.settings_custom_height_key));
            mDpiPref = findPreference(getString(R.string.settings_custom_dpi_key));
            mPresetPref = findPreference(getString(R.string.settings_custom_preset_key));

            if (mCustomResSwitch == null || mWidthPref == null || mHeightPref == null
                    || mDpiPref == null || mPresetPref == null) {
                return;
            }

            // The switch state lives in AppKV (the runtime reads it from there), so mirror it
            // into the preference instead of using a separate SharedPreferences file.
            boolean enabled = AppKV.getBooleanConfig(context, AppKV.CUSTOM_RESOLUTION_ENABLED, false);
            mCustomResSwitch.setChecked(enabled);
            mWidthPref.setText(String.valueOf(AppKV.getCustomWidth(context)));
            mHeightPref.setText(String.valueOf(AppKV.getCustomHeight(context)));
            mDpiPref.setText(String.valueOf(AppKV.getCustomDpi(context)));
            syncPresetSummary();

            mCustomResSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean on = Boolean.TRUE.equals(newValue);
                AppKV.setBooleanConfig(getActivity(), AppKV.CUSTOM_RESOLUTION_ENABLED, on);
                Toast.makeText(getActivity(), R.string.settings_restart_required_note, Toast.LENGTH_LONG).show();
                return true;
            });

            mWidthPref.setOnPreferenceChangeListener(this::onSizeChanged);
            mHeightPref.setOnPreferenceChangeListener(this::onSizeChanged);
            mDpiPref.setOnPreferenceChangeListener(this::onDpiChanged);

            mPresetPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String preset = String.valueOf(newValue);
                int w;
                int h;
                if ("480x854".equals(preset)) {
                    w = 480; h = 854;
                } else if ("720x1280".equals(preset)) {
                    w = 720; h = 1280;
                } else if ("1080x1920".equals(preset)) {
                    w = 1080; h = 1920;
                } else {
                    w = AppKV.DEFAULT_CUSTOM_WIDTH;
                    h = AppKV.DEFAULT_CUSTOM_HEIGHT;
                }
                AppKV.setIntConfig(getActivity(), AppKV.CUSTOM_RESOLUTION_WIDTH, w);
                AppKV.setIntConfig(getActivity(), AppKV.CUSTOM_RESOLUTION_HEIGHT, h);
                mWidthPref.setText(String.valueOf(w));
                mHeightPref.setText(String.valueOf(h));
                syncPresetSummary();
                Toast.makeText(getActivity(), R.string.settings_restart_required_note, Toast.LENGTH_LONG).show();
                return true;
            });

            Preference restoreDefaults = findPreference(getString(R.string.settings_key_restore_defaults));
            if (restoreDefaults != null) {
                restoreDefaults.setOnPreferenceClickListener(preference -> {
                    AppKV.restoreDisplayDefaults(getActivity());
                    mCustomResSwitch.setChecked(false);
                    mWidthPref.setText(String.valueOf(AppKV.DEFAULT_CUSTOM_WIDTH));
                    mHeightPref.setText(String.valueOf(AppKV.DEFAULT_CUSTOM_HEIGHT));
                    mDpiPref.setText(String.valueOf(AppKV.DEFAULT_CUSTOM_DPI));
                    syncPresetSummary();
                    Toast.makeText(getActivity(), R.string.settings_restart_required_note, Toast.LENGTH_LONG).show();
                    return true;
                });
            }
        }

        private boolean onSizeChanged(Preference preference, Object newValue) {
            int value;
            try {
                value = Integer.parseInt(String.valueOf(newValue).trim());
            } catch (NumberFormatException e) {
                value = -1;
            }
            int otherW = preference == mWidthPref ? value : safeParse(mWidthPref.getText(), -1);
            int otherH = preference == mHeightPref ? value : safeParse(mHeightPref.getText(), -1);
            if (!AppKV.isValidSize(otherW, otherH)) {
                Toast.makeText(getActivity(), getString(R.string.settings_invalid_size,
                        AppKV.MIN_DIMENSION, AppKV.MAX_DIMENSION), Toast.LENGTH_LONG).show();
                return false;
            }
            AppKV.setIntConfig(getActivity(),
                    preference == mWidthPref ? AppKV.CUSTOM_RESOLUTION_WIDTH : AppKV.CUSTOM_RESOLUTION_HEIGHT,
                    value);
            syncPresetSummary();
            Toast.makeText(getActivity(), R.string.settings_restart_required_note, Toast.LENGTH_LONG).show();
            return true;
        }

        private boolean onDpiChanged(Preference preference, Object newValue) {
            int value;
            try {
                value = Integer.parseInt(String.valueOf(newValue).trim());
            } catch (NumberFormatException e) {
                value = -1;
            }
            if (!AppKV.isValidDpi(value)) {
                Toast.makeText(getActivity(), getString(R.string.settings_invalid_dpi,
                        AppKV.MIN_DPI, AppKV.MAX_DPI), Toast.LENGTH_LONG).show();
                return false;
            }
            AppKV.setIntConfig(getActivity(), AppKV.CUSTOM_DPI, value);
            Toast.makeText(getActivity(), R.string.settings_restart_required_note, Toast.LENGTH_LONG).show();
            return true;
        }

        private static int safeParse(String s, int fallback) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Throwable e) {
                return fallback;
            }
        }

        private void syncPresetSummary() {
            if (mPresetPref == null) {
                return;
            }
            String current = AppKV.getCustomWidth(getActivity()) + "x" + AppKV.getCustomHeight(getActivity());
            String resolved = null;
            for (CharSequence entry : mPresetPref.getEntries()) {
                String e = String.valueOf(entry);
                if (e.contains(current)) {
                    resolved = e;
                    break;
                }
            }
            if (resolved == null) {
                resolved = mPresetPref.getEntries()[0].toString();
            }
            mPresetPref.setValue(resolved);
            mPresetPref.setSummary(resolved);
        }


        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);

            if (!(requestCode == REQUEST_GET_FILE && resultCode == Activity.RESULT_OK)) {
                return;
            }

            if (data == null) {
                return;
            }

            Uri uri = data.getData();
            if (uri == null) {
                return;
            }

            Activity activity = getActivity();
            ProgressDialog dialog = UIHelper.getProgressDialog(activity);
            dialog.setCancelable(false);
            dialog.show();

            // start copy 3rd rom
            UIHelper.defer().when(() -> {

                File rootfs3rd = RomManager.get3rdRootfsFile(activity);

                ContentResolver contentResolver = activity.getContentResolver();
                try (InputStream inputStream = contentResolver.openInputStream(uri); OutputStream os = new FileOutputStream(rootfs3rd)) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = inputStream.read(buffer)) > 0) {
                        os.write(buffer, 0, count);
                    }
                }

                RomManager.RomInfo romInfo = RomManager.getRomInfo(rootfs3rd);
                return Pair.create(rootfs3rd, romInfo);
            }).done(result -> {

                File rootfs3rd = result.first;
                RomManager.RomInfo romInfo = result.second;
                UIHelper.dismiss(dialog);

                // copy finished, show dialog confirm
                if (romInfo.isValid()) {

                    String author = romInfo.author;
                    if ("weishu".equalsIgnoreCase(author) || "twoyi".equalsIgnoreCase(author)) {
                        Toast.makeText(activity, R.string.replace_rom_unofficial_tips, Toast.LENGTH_SHORT).show();
                        rootfs3rd.delete();
                        return;

                    }
                    UIHelper.getDialogBuilder(activity)
                            .setTitle(R.string.replace_rom_confirm_title)
                            .setMessage(getString(R.string.replace_rom_confirm_message, author, romInfo.version, romInfo.desc))
                            .setPositiveButton(R.string.i_confirm_it, (dialog1, which) -> {
                                AppKV.setBooleanConfig(activity, AppKV.SHOULD_USE_THIRD_PARTY_ROM, true);
                                AppKV.setBooleanConfig(activity, AppKV.FORCE_ROM_BE_RE_INSTALL, true);

                                dialog1.dismiss();

                                RomManager.reboot(getActivity());
                            })
                            .setNegativeButton(android.R.string.cancel, (dialog12, which) -> dialog12.dismiss())
                            .show();
                } else {
                    Toast.makeText(activity, R.string.replace_rom_invalid, Toast.LENGTH_SHORT).show();
                    rootfs3rd.delete();
                }
            }).fail(result -> activity.runOnUiThread(() -> {
                Toast.makeText(activity, getResources().getString(R.string.install_failed_reason, result.getMessage()), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                activity.finish();
            }));

        }
    }
}
