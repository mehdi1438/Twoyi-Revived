/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import java.io.File;
import java.util.List;

/**
 * A single guest-install task: the APK set (base + splits) belonging to one package.
 */

public class InstallTask {

    public interface Callback {
        void onTaskStarted(int index, int total, String label);

        void onTaskFinished(boolean success, String msg);
    }

    private final List<File> files;
    private final String label;

    public InstallTask(List<File> files, String label) {
        this.files = files;
        this.label = label;
    }

    public List<File> getFiles() {
        return files;
    }

    public String getLabel() {
        return label;
    }
}
