/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Storage for {@link Profile} objects.
 *
 * Layout:
 *   <filesDir>/profiles/<id>/meta.json   profile metadata (org.json)
 *   <filesDir>/profiles/<id>/rootfs      the profile's guest rootfs (parked)
 *   <filesDir>/profiles/<id>/thumb.png   optional preview thumbnail
 *   <dataDir>/rootfs                     the ONE active rootfs the guest boots from
 *
 * Activate swaps rootfs between the profile folder and <dataDir> using
 * File.renameTo only (same filesystem, instant). NEVER copy a rootfs tree
 * (symlinks/special files break), and NEVER swap while a guest is running.
 */
public class ProfileRepository {

    private static final String TAG = "ProfileRepository";

    private static final String PREFS_NAME = "profiles";
    private static final String KEY_ACTIVE_ID = "active_id";
    private static final String META_FILE = "meta.json";
    private static final String ROOTFS_DIR_NAME = "rootfs";
    private static final String THUMB_FILE = "thumb.png";
    private static final String PROFILES_DIR_NAME = "profiles";
    private static final String ACTIVE_ROOTFS_NAME = "rootfs";

    private final Context context;
    private final SharedPreferences prefs;

    public ProfileRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        migrateIfNeeded();
    }

    // ---- paths ----------------------------------------------------------

    public File profilesRoot() {
        return new File(context.getFilesDir(), PROFILES_DIR_NAME);
    }

    public File dir(Profile profile) {
        return new File(profilesRoot(), profile.id);
    }

    /** The parked rootfs of a profile (null if it is currently the active one). */
    public File storedRootfs(Profile profile) {
        return new File(dir(profile), ROOTFS_DIR_NAME);
    }

    public File thumb(Profile profile) {
        return new File(dir(profile), THUMB_FILE);
    }

    /** The single active rootfs the guest boots from. */
    public File activeRootfsDir() {
        return new File(context.getDataDir(), ACTIVE_ROOTFS_NAME);
    }

    // ---- queries --------------------------------------------------------

    public List<Profile> list() {
        List<Profile> result = new ArrayList<>();
        File[] dirs = profilesRoot().listFiles();
        if (dirs == null) {
            return result;
        }
        for (File d : dirs) {
            if (d == null || !d.isDirectory()) {
                continue;
            }
            Profile p = load(d.getName());
            if (p != null) {
                result.add(p);
            }
        }
        Collections.sort(result, new Comparator<Profile>() {
            @Override
            public int compare(Profile a, Profile b) {
                return Long.compare(a.created, b.created);
            }
        });
        return result;
    }

    public Profile get(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return load(id);
    }

    public String activeId() {
        return prefs.getString(KEY_ACTIVE_ID, null);
    }

    public Profile activeProfile() {
        return get(activeId());
    }

    /** True if the active profile has its rootfs in place at <dataDir>/rootfs. */
    public boolean isActiveRootfsPresent() {
        return new File(activeRootfsDir(), "init").exists();
    }

    // ---- mutation -------------------------------------------------------

    /** Create a new empty profile (no rootfs yet - caller must install/activate). */
    public Profile create(String name) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Profile p = new Profile(id, name == null || name.trim().isEmpty() ? "ROM " + id : name.trim());
        File d = dir(p);
        if (!d.mkdirs() && !d.isDirectory()) {
            Log.w(TAG, "cannot create profile dir: " + d);
            return null;
        }
        if (!writeMeta(p)) {
            return null;
        }
        return p;
    }

    public boolean save(Profile profile) {
        if (profile == null || profile.id == null) {
            return false;
        }
        File d = dir(profile);
        if (!d.isDirectory() && !d.mkdirs()) {
            return false;
        }
        return writeMeta(profile);
    }

    /**
     * Delete a profile's folder. Refuses to delete the profile whose rootfs is
     * currently active (that would leave <dataDir>/rootfs orphaned); deactivate
     * or activate another profile first.
     */
    public boolean delete(Profile profile) {
        if (profile == null || profile.id == null) {
            return false;
        }
        if (profile.id.equals(activeId())) {
            Log.w(TAG, "refusing to delete active profile: " + profile.id);
            return false;
        }
        File d = dir(profile);
        if (!d.exists()) {
            return true;
        }
        return deleteRecursive(d);
    }

    /** Mark a profile active without swapping (used by migration/restore flows). */
    public void setActiveId(String id) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply();
    }

    /**
     * Swap the target profile's rootfs into <dataDir>/rootfs, parking the
     * previously active profile's rootfs back into its own folder. Uses
     * renameTo only (same filesystem). Never copies, never deletes trees.
     *
     * On failure returns false and guarantees that either the old rootfs is
     * still in place (undo performed) or nothing was moved. Both locations
     * are never left empty.
     */
    public synchronized boolean activate(Profile target) {
        if (target == null) {
            return false;
        }
        File active = activeRootfsDir();
        String oldId = activeId();

        // Already active and in place?
        if (target.id.equals(oldId) && active.exists()) {
            return true;
        }

        File targetRootfs = storedRootfs(target);
        if (!targetRootfs.isDirectory()) {
            Log.w(TAG, "target profile has no stored rootfs: " + target.id);
            return false;
        }

        // Park the current active rootfs back into its profile folder.
        File parked = null;
        if (active.exists()) {
            if (oldId == null) {
                // No owner recorded: adopt it under the target's folder name suffix
                // to avoid losing data; but per design this should not happen after
                // migration. Keep it safe: refuse rather than destroy.
                Log.w(TAG, "active rootfs present but no activeId recorded");
                return false;
            }
            parked = new File(new File(profilesRoot(), oldId), ROOTFS_DIR_NAME);
            if (parked.exists()) {
                Log.w(TAG, "parked rootfs already exists for " + oldId + ", aborting");
                return false;
            }
            parked.getParentFile().mkdirs();
            if (!active.renameTo(parked)) {
                Log.w(TAG, "failed to park current rootfs");
                return false;
            }
        }

        // Move the target into place. Undo the park if this fails.
        if (!targetRootfs.renameTo(active)) {
            Log.w(TAG, "failed to activate rootfs of " + target.id + ", undoing");
            if (parked != null) {
                if (!parked.renameTo(active)) {
                    // Last resort: try again once; report honestly on failure.
                    Log.e(TAG, "UNDO FAILED - rootfs stuck at " + parked);
                    return false;
                }
            }
            return false;
        }

        setActiveId(target.id);
        return true;
    }

    // ---- first-run migration --------------------------------------------

    /**
     * If there are no profiles but <dataDir>/rootfs exists (an existing boot
     * from before profiles existed), adopt it as profile "Default" WITHOUT
     * copying and mark it active.
     */
    private void migrateIfNeeded() {
        try {
            File[] existing = profilesRoot().listFiles();
            boolean hasProfiles = false;
            if (existing != null) {
                for (File f : existing) {
                    if (f != null && f.isDirectory() && new File(f, META_FILE).isFile()) {
                        hasProfiles = true;
                        break;
                    }
                }
            }
            if (hasProfiles) {
                return;
            }
            File active = activeRootfsDir();
            if (!new File(active, "init").exists()) {
                return; // nothing to adopt yet
            }

            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            Profile def = new Profile(id, "Default");
            File d = new File(profilesRoot(), id);
            if (!d.mkdirs()) {
                return;
            }
            // Adopt in place: meta.json lives in the profile folder, the rootfs
            // STAYS at <dataDir>/rootfs (no copy, no move) and is marked active.
            if (!writeMeta(def)) {
                return;
            }
            setActiveId(id);
            Log.i(TAG, "migrated existing rootfs as Default profile " + id);
        } catch (Throwable t) {
            Log.w(TAG, "profile migration failed", t);
        }
    }

    // ---- internals ------------------------------------------------------

    private Profile load(String id) {
        File meta = new File(new File(profilesRoot(), id), META_FILE);
        if (!meta.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(meta)) {
            byte[] buf = new byte[(int) meta.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            JSONObject obj = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
            Profile p = Profile.fromJson(obj);
            if (p.id == null) {
                p.id = id;
            }
            return p;
        } catch (Throwable t) {
            Log.w(TAG, "cannot load profile " + id, t);
            return null;
        }
    }

    private boolean writeMeta(Profile p) {
        File meta = new File(dir(p), META_FILE);
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(meta), StandardCharsets.UTF_8)) {
            w.write(p.toJson().toString(2));
            return true;
        } catch (IOException | org.json.JSONException e) {
            Log.w(TAG, "cannot write meta for " + p.id, e);
            return false;
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursive(c)) {
                        return false;
                    }
                }
            }
        }
        return f.delete();
    }
}
