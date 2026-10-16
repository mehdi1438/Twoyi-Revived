/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.profile;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.twoyi.utils.AppKV;

/**
 * Storage and management for ROM Profiles stored under <filesDir>/profiles/<id>/.
 */
public class ProfileManager {
    private static final String TAG = "ProfileManager";
    public static final String DEFAULT_PROFILE_ID = "default_profile";
    private static final String META_FILE_NAME = "meta.json";

    private final Context context;

    public ProfileManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getProfilesDir() {
        File dir = new File(context.getFilesDir(), "profiles");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File getProfileDir(String profileId) {
        return new File(getProfilesDir(), profileId);
    }

    public File getProfileRootfsDir(String profileId) {
        return new File(getProfileDir(profileId), "rootfs");
    }

    public File getProfileMetaFile(String profileId) {
        return new File(getProfileDir(profileId), META_FILE_NAME);
    }

    public String getActiveProfileId() {
        String activeId = AppKV.getStringConfig(context, AppKV.ACTIVE_PROFILE_ID, DEFAULT_PROFILE_ID);
        if (activeId == null || activeId.isEmpty()) {
            activeId = DEFAULT_PROFILE_ID;
        }
        return activeId;
    }

    public void setActiveProfileId(String profileId) {
        AppKV.setStringConfig(context, AppKV.ACTIVE_PROFILE_ID, profileId);
    }

    public List<RomProfile> getAllProfiles() {
        ensureDefaultProfileExists();
        List<RomProfile> list = new ArrayList<>();
        File profilesDir = getProfilesDir();
        File[] files = profilesDir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    RomProfile profile = loadProfile(file.getName());
                    if (profile != null) {
                        list.add(profile);
                    }
                }
            }
        }
        return list;
    }

    public RomProfile loadProfile(String profileId) {
        File metaFile = getProfileMetaFile(profileId);
        if (!metaFile.exists()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(metaFile)) {
            byte[] data = new byte[(int) metaFile.length()];
            int read = fis.read(data);
            if (read <= 0) return null;
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            RomProfile profile = RomProfile.fromJsonObject(json);
            File rootfsDir = getProfileRootfsDir(profileId);
            if (rootfsDir.exists()) {
                profile.setSizeBytes(calculateFolderSize(rootfsDir));
            }
            return profile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load profile metadata for: " + profileId, e);
            return null;
        }
    }

    public void saveProfile(RomProfile profile) throws IOException {
        File profileDir = getProfileDir(profile.getId());
        if (!profileDir.exists()) {
            profileDir.mkdirs();
        }
        File metaFile = getProfileMetaFile(profile.getId());
        try {
            JSONObject json = profile.toJsonObject();
            try (FileOutputStream fos = new FileOutputStream(metaFile)) {
                fos.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new IOException("Failed to save profile JSON", e);
        }
    }

    public RomProfile createProfile(String name, String source) throws IOException {
        String id = "profile_" + UUID.randomUUID().toString().substring(0, 8);
        File rootfs = getProfileRootfsDir(id);
        rootfs.mkdirs();

        RomProfile profile = new RomProfile(id, name, source, 0L, System.currentTimeMillis(), true);
        saveProfile(profile);
        return profile;
    }

    public void deleteProfile(String profileId) {
        File profileDir = getProfileDir(profileId);
        deleteRecursively(profileDir);
    }

    public synchronized boolean swapToProfile(String newProfileId) {
        String currentActiveId = getActiveProfileId();
        if (newProfileId.equals(currentActiveId)) {
            // Already active, just update lastRunTime
            RomProfile activeProfile = loadProfile(newProfileId);
            if (activeProfile != null) {
                activeProfile.setLastRunTime(System.currentTimeMillis());
                try {
                    saveProfile(activeProfile);
                } catch (IOException ignored) {
                }
            }
            return true;
        }

        File currentRootfs = new File(context.getDataDir(), "rootfs");
        File currentProfileRootfs = getProfileRootfsDir(currentActiveId);
        File targetProfileRootfs = getProfileRootfsDir(newProfileId);

        // 1. Move current <dataDir>/rootfs back into <filesDir>/profiles/<currentActiveId>/rootfs
        if (currentRootfs.exists()) {
            if (currentProfileRootfs.exists()) {
                deleteRecursively(currentProfileRootfs);
            }
            File currentProfileDir = getProfileDir(currentActiveId);
            if (!currentProfileDir.exists()) currentProfileDir.mkdirs();
            boolean renamedCurrent = currentRootfs.renameTo(currentProfileRootfs);
            if (!renamedCurrent) {
                Log.e(TAG, "Failed to move current rootfs to profile dir");
                copyDirectory(currentRootfs, currentProfileRootfs);
                deleteRecursively(currentRootfs);
            }
        }

        // 2. Move target profile's rootfs into <dataDir>/rootfs
        if (targetProfileRootfs.exists()) {
            boolean renamedTarget = targetProfileRootfs.renameTo(currentRootfs);
            if (!renamedTarget) {
                Log.e(TAG, "Failed to move target rootfs to dataDir");
                copyDirectory(targetProfileRootfs, currentRootfs);
                deleteRecursively(targetProfileRootfs);
            }
        } else {
            currentRootfs.mkdirs();
        }

        // 3. Update active profile ID & last run time
        setActiveProfileId(newProfileId);
        RomProfile newProfile = loadProfile(newProfileId);
        if (newProfile != null) {
            newProfile.setLastRunTime(System.currentTimeMillis());
            try {
                saveProfile(newProfile);
            } catch (IOException ignored) {
            }
        }
        return true;
    }

    private static void copyDirectory(File sourceLocation, File targetLocation) {
        if (sourceLocation.isDirectory()) {
            if (!targetLocation.exists()) {
                targetLocation.mkdirs();
            }
            String[] children = sourceLocation.list();
            if (children != null) {
                for (String child : children) {
                    copyDirectory(new File(sourceLocation, child), new File(targetLocation, child));
                }
            }
        } else {
            try (FileInputStream in = new FileInputStream(sourceLocation);
                 FileOutputStream out = new FileOutputStream(targetLocation)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            } catch (IOException e) {
                Log.e(TAG, "Copy file error", e);
            }
        }
    }

    public synchronized void ensureDefaultProfileExists() {
        File defaultMeta = getProfileMetaFile(DEFAULT_PROFILE_ID);
        if (!defaultMeta.exists()) {
            File profileDir = getProfileDir(DEFAULT_PROFILE_ID);
            File profileRootfs = getProfileRootfsDir(DEFAULT_PROFILE_ID);

            if (!profileDir.exists()) {
                profileDir.mkdirs();
            }
            if (!profileRootfs.exists()) {
                profileRootfs.mkdirs();
            }

            RomProfile defaultProfile = new RomProfile(
                    DEFAULT_PROFILE_ID,
                    "Built-in ROM",
                    RomProfile.SOURCE_BUILT_IN,
                    0L,
                    System.currentTimeMillis(),
                    true
            );
            try {
                saveProfile(defaultProfile);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create default profile metadata", e);
            }
        }
    }

    public static long calculateFolderSize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();

        long size = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                size += calculateFolderSize(child);
            }
        }
        return size;
    }

    private static boolean deleteRecursively(File fileOrDirectory) {
        if (fileOrDirectory != null && fileOrDirectory.exists()) {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            return fileOrDirectory.delete();
        }
        return false;
    }
}
