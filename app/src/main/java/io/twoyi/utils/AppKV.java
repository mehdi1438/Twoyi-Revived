/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * @author weishu
 * @date 2019/2/28.
 */
public class AppKV {


    private static final String PREF_NAME = "app_kv";

    public static final String ADD_APP_NOT_SHOW_SYSTEM= "add_app_not_show_system";
    public static final String ADD_APP_NOT_SHOW_ADDED = "add_app_not_show_added";
    public static final String SHOW_ANDROID12_TIPS = "show_android12_tips_v2";
    public static final String ADD_APP_NOT_SHOW_32BIT = "add_app_not_show_32bit";

    // 是否应该重新安装 ROM
    // 1. 恢复出厂设置
    // 2. 替换 ROM
    public static final String FORCE_ROM_BE_RE_INSTALL = "rom_should_be_re_install";

    // 是否应该使用第三方 ROM
    public static final String SHOULD_USE_THIRD_PARTY_ROM = "should_use_third_party_rom";

    public static final String ACTIVE_PROFILE_ID = "active_profile_id";

    // Phase 3: custom guest resolution and DPI (off by default -> host-window behaviour)
    public static final String CUSTOM_RESOLUTION_ENABLED = "custom_resolution_enabled";
    public static final String CUSTOM_RESOLUTION_WIDTH = "custom_resolution_width";
    public static final String CUSTOM_RESOLUTION_HEIGHT = "custom_resolution_height";
    public static final String CUSTOM_DPI = "custom_dpi";
    public static final String FPS_CAP = "fps_cap";

    public static final int DEFAULT_FPS_CAP = 45;

    public static final int DEFAULT_CUSTOM_WIDTH = 720;
    public static final int DEFAULT_CUSTOM_HEIGHT = 1280;
    public static final int DEFAULT_CUSTOM_DPI = 320;

    public static final int MIN_DPI = 120;
    public static final int MAX_DPI = 640;
    public static final int MIN_DIMENSION = 120;
    public static final int MAX_DIMENSION = 4096;

    /** True when a valid custom width/height/DPI triple is stored and the switch is on. */
    public static boolean isCustomResolutionEnabled(Context context) {
        if (!getBooleanConfig(context, CUSTOM_RESOLUTION_ENABLED, false)) {
            return false;
        }
        int w = getIntConfig(context, CUSTOM_RESOLUTION_WIDTH, 0);
        int h = getIntConfig(context, CUSTOM_RESOLUTION_HEIGHT, 0);
        int dpi = getIntConfig(context, CUSTOM_DPI, 0);
        return isValidSize(w, h) && isValidDpi(dpi);
    }

    public static boolean isValidSize(int width, int height) {
        return width >= MIN_DIMENSION && width <= MAX_DIMENSION
                && height >= MIN_DIMENSION && height <= MAX_DIMENSION;
    }

    public static boolean isValidDpi(int dpi) {
        return dpi >= MIN_DPI && dpi <= MAX_DPI;
    }

    public static int getCustomWidth(Context context) {
        return getIntConfig(context, CUSTOM_RESOLUTION_WIDTH, DEFAULT_CUSTOM_WIDTH);
    }

    public static int getCustomHeight(Context context) {
        return getIntConfig(context, CUSTOM_RESOLUTION_HEIGHT, DEFAULT_CUSTOM_HEIGHT);
    }

    public static int getCustomDpi(Context context) {
        return getIntConfig(context, CUSTOM_DPI, DEFAULT_CUSTOM_DPI);
    }

    /** Requested guest FPS cap: 30, 45 (default), 60, 90 or 120. */
    public static int getFpsCap(Context context) {
        int fps = getIntConfig(context, FPS_CAP, DEFAULT_FPS_CAP);
        if (fps != 30 && fps != 60 && fps != 90 && fps != 120) {
            fps = DEFAULT_FPS_CAP;
        }
        return fps;
    }

    @SuppressLint("ApplySharedPref")
    public static void restoreDisplayDefaults(Context context) {
        getPref(context).edit()
                .putBoolean(CUSTOM_RESOLUTION_ENABLED, false)
                .putInt(CUSTOM_RESOLUTION_WIDTH, DEFAULT_CUSTOM_WIDTH)
                .putInt(CUSTOM_RESOLUTION_HEIGHT, DEFAULT_CUSTOM_HEIGHT)
                .putInt(CUSTOM_DPI, DEFAULT_CUSTOM_DPI)
                .commit();
    }

    public static boolean getBooleanConfig(Context context,  String key, boolean fallback) {
        return getPref(context).getBoolean(key, fallback);
    }

    @SuppressLint("ApplySharedPref")
    public static void setBooleanConfig(Context context, String key, boolean value) {
        getPref(context).edit().putBoolean(key, value).commit();
    }

    public static int getIntConfig(Context context, String key, int fallback) {
        return getPref(context).getInt(key, fallback);
    }

    @SuppressLint("ApplySharedPref")
    public static void setIntConfig(Context context, String key, int value) {
        getPref(context).edit().putInt(key, value).commit();
    }

    public static String getStringConfig(Context context, String key, String fallback) {
        return getPref(context).getString(key, fallback);
    }

    @SuppressLint("ApplySharedPref")
    public static void setStringConfig(Context context, String key, String value) {
        getPref(context).edit().putString(key, value).commit();
    }

    private static SharedPreferences getPref(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
