/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.profile;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Data model for a ROM Profile.
 */
public class RomProfile {
    public static final String SOURCE_BUILT_IN = "built_in";
    public static final String SOURCE_IMPORTED = "imported";

    private String id;
    private String name;
    private String source;
    private long sizeBytes;
    private long lastRunTime;
    private boolean rootEnabled;
    private String resolution;
    private int dpi;
    private int fps;

    public RomProfile() {
        this.resolution = "1080x2340";
        this.dpi = 480;
        this.fps = 45;
    }

    public RomProfile(String id, String name, String source, long sizeBytes, long lastRunTime, boolean rootEnabled) {
        this.id = id;
        this.name = name;
        this.source = source;
        this.sizeBytes = sizeBytes;
        this.lastRunTime = lastRunTime;
        this.rootEnabled = rootEnabled;
        this.resolution = "1080x2340";
        this.dpi = 480;
        this.fps = 45;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public long getLastRunTime() {
        return lastRunTime;
    }

    public void setLastRunTime(long lastRunTime) {
        this.lastRunTime = lastRunTime;
    }

    public boolean isRootEnabled() {
        return rootEnabled;
    }

    public void setRootEnabled(boolean rootEnabled) {
        this.rootEnabled = rootEnabled;
    }

    public String getResolution() {
        return resolution != null ? resolution : "1080x2340";
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public int getDpi() {
        return dpi > 0 ? dpi : 480;
    }

    public void setDpi(int dpi) {
        this.dpi = dpi;
    }

    public int getFps() {
        return fps > 0 ? fps : 45;
    }

    public void setFps(int fps) {
        this.fps = fps;
    }

    public JSONObject toJsonObject() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("source", source);
        json.put("sizeBytes", sizeBytes);
        json.put("lastRunTime", lastRunTime);
        json.put("rootEnabled", rootEnabled);
        json.put("resolution", resolution);
        json.put("dpi", dpi);
        json.put("fps", fps);
        return json;
    }

    public static RomProfile fromJsonObject(JSONObject json) throws JSONException {
        RomProfile profile = new RomProfile();
        profile.setId(json.optString("id", ""));
        profile.setName(json.optString("name", "Unnamed Profile"));
        profile.setSource(json.optString("source", SOURCE_IMPORTED));
        profile.setSizeBytes(json.optLong("sizeBytes", 0L));
        profile.setLastRunTime(json.optLong("lastRunTime", 0L));
        profile.setRootEnabled(json.optBoolean("rootEnabled", true));
        profile.setResolution(json.optString("resolution", "1080x2340"));
        profile.setDpi(json.optInt("dpi", 480));
        profile.setFps(json.optInt("fps", 45));
        return profile;
    }
}
