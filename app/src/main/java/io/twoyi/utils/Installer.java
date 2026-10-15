/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.R;

public class Installer {

    public interface InstallResult {
        void onSuccess(List<File> files);

        void onFail(List<File> files, String msg);
    }

    /**
     * Callback for batch installs (one APK set per guest package). Invoked from the
     * installer worker thread; UI consumers must hop back to the main thread themselves.
     */
    public interface BatchInstallResult {
        void onTaskStarted(int index, int total, String label);

        void onTaskFinished(boolean success, String label, String msg);

        /** Called once when adb could not reach the guest at all; no task was attempted. */
        void onConnectionFailed(int taskCount);
    }

    private static final String TAG = "Installer";

    public static final int REQUEST_INSTALL_APP = 101;

    private static final String CONNECT_TARGET = "localhost:22122";
    private static final int ADB_PORT = 9563;

    public static void installAsync(Context context, String path, InstallResult callback) {
        installAsync(context, Collections.singletonList(new File(path)), callback);
    }

    public static void installAsync(Context context, List<File> files, InstallResult callback) {
        new Thread(() -> install(context, files, callback)).start();
    }

    /**
     * Install multiple apps (each entry is a base APK + its split APKs) into the guest
     * through the same adb channel used for single installs. The adb server/connect step
     * runs once for the whole batch; every task reports its own success/failure and the
     * caller can cancel via {@code cancelled}.
     */
    public static void installBatch(Context context, List<InstallTask> tasks, AtomicBoolean cancelled,
                                    BatchInstallResult callback) {
        new Thread(() -> {
            String envCmd = adbEnvCmd(context);
            String adbPath = adbPath(context);

            if (!ensureConnected(context, envCmd, adbPath)) {
                cleanupStagedFiles(tasks);
                if (callback != null) {
                    callback.onConnectionFailed(tasks.size());
                }
                return;
            }

            int index = 0;
            for (InstallTask task : tasks) {
                index++;
                if (cancelled.get()) {
                    cleanupStagedFilesFrom(tasks, index - 1);
                    return;
                }
                if (callback != null) {
                    callback.onTaskStarted(index, tasks.size(), task.getLabel());
                }
                String error = installFiles(context, task.getFiles(), envCmd, adbPath);
                // The guest adbd already consumed the APKs (successfully or not):
                // the staged copy has served its purpose either way.
                IOUtils.deleteAll(task.getFiles());
                if (cancelled.get()) {
                    cleanupStagedFilesFrom(tasks, index);
                    return;
                }
                if (callback != null) {
                    callback.onTaskFinished(error == null, task.getLabel(), error);
                }
            }
        }).start();
    }

    /** Delete staged/temp APK files that were never handed to the guest. */
    private static void cleanupStagedFiles(List<InstallTask> tasks) {
        cleanupStagedFilesFrom(tasks, 0);
    }

    private static void cleanupStagedFilesFrom(List<InstallTask> tasks, int fromIndex) {
        for (int i = fromIndex; i < tasks.size(); i++) {
            try {
                IOUtils.deleteAll(tasks.get(i).getFiles());
            } catch (Throwable ignored) {
            }
        }
    }

    public static void install(Context context, List<File> files, InstallResult callback) {

//        Shell.enableVerboseLogging = true;

        String envCmd = adbEnvCmd(context);
        String adbPath = adbPath(context);

        if (!ensureConnected(context, envCmd, adbPath)) {
            if (callback != null) {
                callback.onFail(files, "Adb connect failed!");
            }
            return;
        }

        String error = installFiles(context, files, envCmd, adbPath);

        if (callback == null) {
            return;
        }
        if (error == null) {
            callback.onSuccess(files);
        } else {
            callback.onFail(files, error);
        }
    }

    private static String adbPath(Context context) {
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        return nativeLibraryDir + File.separator + "libadb.so";
    }

    private static String adbEnvCmd(Context context) {
        String envPath = context.getCacheDir().getAbsolutePath();
        return String.format("export TMPDIR=%s;export HOME=%s;", envPath, envPath);
    }

    /**
     * Start the private adb server (if not running) and connect it to the guest's adbd.
     * Safe to call repeatedly; connecting when already connected is a no-op for adb.
     */
    private static boolean ensureConnected(Context context, String envCmd, String adbPath) {

        String adbCommand = String.format(Locale.US, "%s -P %d connect %s", adbPath, ADB_PORT, CONNECT_TARGET);

        String adbServerCommand = String.format(Locale.US, "%s -P %d nodaemon server", adbPath, ADB_PORT);
        ShellUtil.newSh().newJob().add(envCmd).add(adbServerCommand).submit();

        Shell shell = ShellUtil.newSh();

        Shell.Result result = shell.newJob().add(envCmd).add(adbCommand).to(new ArrayList<>(), new ArrayList<>()).exec();

        String errMsg = Arrays.toString(result.getErr().toArray(new String[0]));
        String outMsg = Arrays.toString(result.getOut().toArray(new String[0]));

        Log.w(TAG, "success: " + result.isSuccess() + " err: " + errMsg + " out: " + outMsg);

        boolean connected = false;
        for (String s : result.getOut()) {

            // connected to localhost:22122
            // already connected to localhost
            if (s.contains("connected to")) {
                connected = true;
            }
        }

        try {
            shell.waitAndClose(1, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }

        return connected;
    }

    /**
     * Run `adb install`/`install-multiple` for one app (base + splits). Returns null on
     * success, or a human-readable failure message.
     */
    private static String installFiles(Context context, List<File> files, String envCmd, String adbPath) {

        StringBuilder sb = new StringBuilder();
        for (File file : files) {
            sb.append(shellQuote(file.getAbsolutePath())).append(" ");
        }

        String fileArgs = sb.toString();

        String installCommand;
        if (files.size() == 1) {
            installCommand = String.format(Locale.US, "%s -P %d -s %s install -t -r %s", adbPath, ADB_PORT, CONNECT_TARGET, fileArgs);
        } else {
            // http://aospxref.com/android-10.0.0_r47/xref/system/core/adb/client/adb_install.cpp#447
            installCommand = String.format(Locale.US, "%s -P %d -s %s install-multiple -t -r %s", adbPath, ADB_PORT, CONNECT_TARGET, fileArgs);
        }

        Log.w(TAG, "installCommand: " + installCommand);

        Shell installShell = ShellUtil.newSh();

        try {
            // Blocking exec on the installer worker thread - keeps the batch sequential
            // and avoids relying on async Job callbacks for per-app results.
            Shell.Result out = installShell.newJob().add(envCmd).add(installCommand)
                    .to(new ArrayList<>(), new ArrayList<>()).exec();

            Log.w(TAG, "install result: " + out.isSuccess());
            if (out.isSuccess()) {
                return null;
            }
            String msg = Arrays.toString(out.getErr().toArray(new String[0]));
            if (msg == null || msg.length() == 0 || "[]".equals(msg)) {
                msg = Arrays.toString(out.getOut().toArray(new String[0]));
            }
            Log.w(TAG, "msg: " + msg);
            return msg;
        } catch (Throwable e) {
            Log.e(TAG, "installFiles error", e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            try {
                installShell.waitAndClose(1, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Single-quote a path for the `sh` shell used by libsu's non-root shell. */
    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    public static boolean checkFile(Context context, List<File> files) {
        boolean valid = true;
        for (File file : files) {
            if (!checkFile(context, file.getAbsolutePath())) {
                valid = false;
                break;
            }
        }

        return valid;
    }

    public static boolean checkFile(Context context, String path) {
        if (path == null) {
            return false;
        }

        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return false;
        }

        PackageInfo packageInfo = pm.getPackageArchiveInfo(path, 0);

        if (packageInfo == null) {
            Toast.makeText(context.getApplicationContext(), R.string.check_file_invlid_apk, Toast.LENGTH_SHORT).show();
            return false;
        }

        String packageName = packageInfo.packageName;

        if (TextUtils.equals(packageName, context.getPackageName())) {
            Toast.makeText(context.getApplicationContext(), R.string.check_file_create_self_tip, Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }
}
