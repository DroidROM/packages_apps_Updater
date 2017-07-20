/*
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.updater.misc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lineageos.updater.R;
import org.lineageos.updater.UpdatesDbHelper;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.model.Update;
import org.lineageos.updater.model.UpdateDownload;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Utils {

    private static final String TAG = "Utils";

    private Utils() {
    }

    public static File getDownloadPath(Context context) {
        return new File(context.getString(R.string.download_path));
    }

    public static File getExportPath(Context context) {
        File dir = new File(Environment.getExternalStorageDirectory(),
                context.getString(R.string.export_path));
        if (!dir.isDirectory()) {
            if (dir.exists() || !dir.mkdirs()) {
                throw new RuntimeException("Could not create directory");
            }
        }
        return dir;
    }

    public static File getCachedUpdateList(Context context) {
        return new File(context.getCacheDir(), "updates.json");
    }

    // This should really return an Update object, but currently this only
    // used to initialize UpdateDownload objects
    private static UpdateDownload parseJsonUpdate(JSONObject object) throws JSONException {
        UpdateDownload update = new UpdateDownload();
        update.setTimestamp(object.getLong("datetime"));
        update.setName(object.getString("filename"));
        update.setDownloadId(object.getString("id"));
        update.setType(object.getString("romtype"));
        update.setDownloadUrl(object.getString("url"));
        update.setVersion(object.getString("version"));
        return update;
    }

    public static boolean isCompatible(Update update) {
        if (update.getTimestamp() < SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)) {
            Log.d(TAG, update.getName() + " is older than current build");
            return false;
        }
        if (!update.getType().equalsIgnoreCase(SystemProperties.get(Constants.PROP_RELEASE_TYPE))) {
            Log.d(TAG, update.getName() + " has type " + update.getType());
            return false;
        }
        return true;
    }

    public static boolean canInstall(Update update) {
        return update.getTimestamp() >= SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0) &&
                update.getVersion().equalsIgnoreCase(
                        SystemProperties.get(Constants.PROP_BUILD_VERSION));
    }

    public static List<UpdateDownload> parseJson(File file, boolean compatibleOnly)
            throws IOException, JSONException {
        List<UpdateDownload> updates = new ArrayList<>();

        String json = "";
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            for (String line; (line = br.readLine()) != null;) {
                json += line;
            }
        }

        JSONObject obj = new JSONObject(json);
        JSONArray updatesList = obj.getJSONArray("response");
        for (int i = 0; i < updatesList.length(); i++) {
            if (updatesList.isNull(i)) {
                continue;
            }
            try {
                UpdateDownload update = parseJsonUpdate(updatesList.getJSONObject(i));
                if (compatibleOnly && isCompatible(update)) {
                    updates.add(update);
                } else {
                    Log.d(TAG, "Ignoring incompatible update " + update.getName());
                }
            } catch (JSONException e) {
                Log.e(TAG, "Could not parse update object, index=" + i);
            }
        }

        return updates;
    }

    public static String getServerURL(Context context) {
        String serverUrl = SystemProperties.get(Constants.PROP_UPDATER_URI);
        if (serverUrl.trim().isEmpty()) {
            serverUrl = context.getString(R.string.conf_update_server_url_def);
        }
        String incrementalVersion = SystemProperties.get(Constants.PROP_BUILD_VERSION_INCREMENTAL);
        String device = SystemProperties.get(Constants.PROP_DEVICE).toLowerCase();
        String type = SystemProperties.get(Constants.PROP_RELEASE_TYPE).toLowerCase();
        return serverUrl + "/v1/" + device + "/" + type + "/" + incrementalVersion;
    }

    public static void triggerUpdate(Context context, String downloadId) {
        final Intent intent = new Intent(context, UpdaterService.class);
        intent.setAction(UpdaterService.ACTION_INSTALL_UPDATE);
        intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId);
        context.startService(intent);
    }

    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return !(info == null || !info.isConnected() || !info.isAvailable());
    }

    /**
     * Compares two json formatted updates list files
     *
     * @param oldJson old update list
     * @param newJson new update list
     * @return true if newJson has at least a compatible update not available in oldJson
     * @throws IOException
     * @throws JSONException
     */
    public static boolean checkForNewUpdates(File oldJson, File newJson)
            throws IOException, JSONException {
        List<UpdateDownload> oldList = parseJson(oldJson, true);
        List<UpdateDownload> newList = parseJson(newJson, true);
        Set<String> oldIds = new HashSet<>();
        for (Update update : oldList) {
            oldIds.add(update.getDownloadId());
        }
        // In case of no new updates, the old list should
        // have all (if not more) the updates
        for (Update update : newList) {
            if (!oldIds.contains(update.getDownloadId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the offset to the compressed data of a file inside the given zip
     *
     * @param zipFile input zip file
     * @param entryPath full path of the entry
     * @return the offset of the compressed, or -1 if not found
     * @throws IllegalArgumentException if the given entry is not found
     */
    public static long getZipEntryOffset(ZipFile zipFile, String entryPath) {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        final int FIXED_HEADER_SIZE = 30;
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        long offset = 0;
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            int n = entry.getName().length();
            int m = entry.getExtra() == null ? 0 : entry.getExtra().length;
            int headerSize = FIXED_HEADER_SIZE + n + m;
            offset += headerSize;
            if (entry.getName().equals(entryPath)) {
                return offset;
            }
            offset += entry.getCompressedSize();
        }
        Log.e(TAG, "Entry " + entryPath + " not found");
        throw new IllegalArgumentException("The given entry was not found");
    }

    /**
     * Cleanup the download directory, which is assumed to be a privileged location
     * the user can't access and that might have stale files. This can happen if
     * the data of the application are wiped.
     *
     * @param context
     */
    public static void cleanupDownloadsDir(Context context) {
        final String DOWNLOADS_CLEANUP_DONE = "cleanup_done";

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getBoolean(DOWNLOADS_CLEANUP_DONE, false)) {
            return;
        }

        File downloadPath = getDownloadPath(context);
        Log.d(TAG, "Cleaning " + downloadPath);
        if (!downloadPath.isDirectory()) {
            return;
        }
        File[] files = downloadPath.listFiles();
        if (files == null) {
            return;
        }

        // Ideally the database is empty when we get here
        UpdatesDbHelper dbHelper = new UpdatesDbHelper(context);
        List<String> knownPaths = new ArrayList<>();
        for (UpdateDownload update : dbHelper.getUpdates()) {
            knownPaths.add(update.getFile().getAbsolutePath());
        }
        for (File file : files) {
            if (!knownPaths.contains(file.getAbsolutePath())) {
                Log.d(TAG, "Deleting " + file.getAbsolutePath());
                file.delete();
            }
        }

        preferences.edit().putBoolean(DOWNLOADS_CLEANUP_DONE, true).apply();
    }

    public static File appendSequentialNumber(final File file) {
        String name;
        String extension;
        int extensionPosition = file.getName().lastIndexOf(".");
        if (extensionPosition > 0) {
            name = file.getName().substring(0, extensionPosition);
            extension = file.getName().substring(extensionPosition);
        } else {
            name = file.getName();
            extension = "";
        }
        final File parent = file.getParentFile();
        for (int i = 1; i < Integer.MAX_VALUE; i++) {
            File newFile = new File(parent, name + "-" + i + extension);
            if (!newFile.exists()) {
                return newFile;
            }
        }
        throw new IllegalStateException();
    }

    public static boolean isABUpdate(ZipFile zipFile) {
        return zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH) != null &&
                zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH) != null;
    }

    public static boolean isABUpdate(File file) throws IOException {
        ZipFile zipFile = new ZipFile(file);
        boolean isAB = isABUpdate(zipFile);
        zipFile.close();
        return isAB;
    }

    public static boolean hasTouchscreen(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    public static void addToClipboard(Context context, String label, String text, String toastMessage) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(
                Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show();
    }
}
