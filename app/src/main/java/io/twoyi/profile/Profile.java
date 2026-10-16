/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.profile;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A saved ROM profile: metadata for one guest rootfs stored under
 * &lt;filesDir&gt;/profiles/&lt;id&gt;/. The app can only run ONE guest at a time,
 * so running a profile means swapping its rootfs into &lt;dataDir&gt;/rootfs
 * (see {@link ProfileRepository#activate}).
 */
public class Profile {

    public static final int DEFAULT_FPS = 45;

    /** 8 chars of a UUID; also the folder name under profiles/. */
    public String id;

    public String name;

    /** epoch millis. */
    public long created;

    /** epoch millis of the last successful Run, 0 if never run. */
    public long lastRun;

    /** Display settings for this profile (Phase 3 fields). */
    public int width;
    public int height;
    public int dpi;

    /** Requested frame cap for this profile. */
    public int fps = DEFAULT_FPS;

    /** Root flag (informational in this phase; no verified code path yet). */
    public boolean root;

    public Profile() {
    }

    public Profile(String id, String name) {
        this.id = id;
        this.name = name;
        this.created = System.currentTimeMillis();
        this.lastRun = 0;
        // 0 means 'not set' -> fall back to global defaults at use time.
        this.width = 0;
        this.height = 0;
        this.dpi = 0;
        this.fps = DEFAULT_FPS;
        this.root = false;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("name", name);
            obj.put("created", created);
            obj.put("lastRun", lastRun);
            obj.put("width", width);
            obj.put("height", height);
            obj.put("dpi", dpi);
            obj.put("fps", fps);
            obj.put("root", root);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return obj;
    }

    public static Profile fromJson(JSONObject obj) {
        Profile p = new Profile();
        p.id = obj.optString("id", null);
        p.name = obj.optString("name", "");
        p.created = obj.optLong("created", System.currentTimeMillis());
        p.lastRun = obj.optLong("lastRun", 0);
        p.width = obj.optInt("width", 0);
        p.height = obj.optInt("height", 0);
        p.dpi = obj.optInt("dpi", 0);
        p.fps = obj.optInt("fps", DEFAULT_FPS);
        p.root = obj.optBoolean("root", false);
        return p;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Profile)) return false;
        Profile other = (Profile) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
