package com.example.mitvoptimizer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class LauncherGuard {
    public static final String XIAOMI_HOME = "com.mitv.tvhome";
    public static final String LAUNCHER_APK_NAME = "launcher.apk";
    private static final int MAX_SCAN_DEPTH = 5;
    private static final int MAX_SCANNED_DIRS = 800;

    private LauncherGuard() {}

    /**
     * Android normally does not expose the source folder from which this optimizer APK was installed.
     * Instead we locate an exact file named launcher.apk on readable shared/removable storage and then
     * treat its parent folder as the staging folder. v0.8 only consumes launcher.apk;
     * every other APK is deliberately ignored and must be installed manually by the user.
     */
    public static List<File> launcherDirectories(Context context) {
        LinkedHashSet<File> roots = new LinkedHashSet<>();

        // Internal shared storage (fallback, also useful when files were copied from USB).
        try {
            File primary = Environment.getExternalStorageDirectory();
            if (primary != null) roots.add(primary);
        } catch (Throwable ignored) {}

        // Common Android mount point containing removable USB/SD volumes.
        File storage = new File("/storage");
        if (storage.isDirectory()) {
            File[] children = safeListFiles(storage);
            if (children != null) {
                for (File child : children) {
                    if (!child.isDirectory()) continue;
                    String name = child.getName();
                    if ("self".equals(name) || "emulated".equals(name)) continue;
                    roots.add(child);
                }
            }
        }

        // Ask Android for app-specific dirs on all mounted volumes, then walk back to each volume root.
        try {
            File[] appDirs = context.getExternalFilesDirs(null);
            if (appDirs != null) {
                for (File appDir : appDirs) {
                    File volumeRoot = volumeRootFromExternalFilesDir(appDir);
                    if (volumeRoot != null) roots.add(volumeRoot);
                }
            }
        } catch (Throwable ignored) {}

        List<File> found = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();
        int[] scanned = new int[]{0};
        for (File root : roots) {
            scanForLauncher(root, 0, found, dedupe, scanned);
            if (scanned[0] >= MAX_SCANNED_DIRS) break;
        }

        // Prefer removable storage and shallower/shorter paths for deterministic behavior.
        Collections.sort(found, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                int ar = removableRank(a);
                int br = removableRank(b);
                if (ar != br) return Integer.compare(ar, br);
                int al = a.getAbsolutePath().length();
                int bl = b.getAbsolutePath().length();
                if (al != bl) return Integer.compare(al, bl);
                return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
            }
        });
        return found;
    }

    public static File stageRoot(Context context) {
        List<File> dirs = launcherDirectories(context);
        return dirs.size() == 1 ? dirs.get(0) : null;
    }

    public static File launcherApk(Context context) {
        File root = stageRoot(context);
        return root == null ? null : new File(root, LAUNCHER_APK_NAME);
    }

    public static boolean hasStagedLauncher(Context context) {
        File f = launcherApk(context);
        return isReadableNonEmptyFile(f);
    }

    public static String stagedLauncherPackage(Context context) {
        return packageNameForApk(context, launcherApk(context));
    }

    /** Parse a package name from any staged APK without installing it. */
    public static String packageNameForApk(Context context, File apk) {
        if (!isReadableNonEmptyFile(apk)) return null;
        PackageInfo pi = context.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_ACTIVITIES);
        return pi == null ? null : pi.packageName;
    }

    public static List<String> installedThirdPartyHomes(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> infos = pm.queryIntentActivities(home, PackageManager.MATCH_ALL);
        Set<String> packages = new HashSet<>();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || XIAOMI_HOME.equals(pkg)) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (ai != null && ai.enabled) packages.add(pkg);
        }
        List<String> out = new ArrayList<>(packages);
        Collections.sort(out);
        return out;
    }

    public static ResolveInfo findHome(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return null;
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setPackage(packageName);
        List<ResolveInfo> infos = context.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL);
        return infos == null || infos.isEmpty() ? null : infos.get(0);
    }

    public static boolean canHandleHome(Context context, String packageName) {
        return findHome(context, packageName) != null;
    }

    /** Hard condition: exactly one readable launcher.apk must be found and it must be a valid APK. */
    public static boolean preflightSatisfied(Context context) {
        List<File> dirs = launcherDirectories(context);
        return dirs.size() == 1 && stagedLauncherPackage(context) != null;
    }

    private static boolean isReadableNonEmptyFile(File f) {
        return f != null && f.isFile() && f.canRead() && f.length() > 0;
    }

    private static File[] safeListFiles(File dir) {
        try { return dir.listFiles(); } catch (SecurityException e) { return null; }
    }

    private static void scanForLauncher(File dir, int depth, List<File> out,
                                        Set<String> dedupe, int[] scanned) {
        if (dir == null || depth > MAX_SCAN_DEPTH || scanned[0] >= MAX_SCANNED_DIRS) return;
        if (!dir.isDirectory() || !dir.canRead()) return;
        scanned[0]++;

        File launcher = new File(dir, LAUNCHER_APK_NAME);
        if (isReadableNonEmptyFile(launcher)) {
            try {
                String key = dir.getCanonicalPath();
                if (dedupe.add(key)) out.add(dir);
            } catch (Exception e) {
                String key = dir.getAbsolutePath();
                if (dedupe.add(key)) out.add(dir);
            }
            // Once launcher.apk is found, this is a staging directory. No need to recurse below it.
            return;
        }

        if (depth == MAX_SCAN_DEPTH) return;
        File[] children = safeListFiles(dir);
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String n = child.getName();
            if (n.startsWith(".")) continue;
            if ("Android".equalsIgnoreCase(n) || "LOST.DIR".equalsIgnoreCase(n)) continue;
            scanForLauncher(child, depth + 1, out, dedupe, scanned);
            if (scanned[0] >= MAX_SCANNED_DIRS) return;
        }
    }

    private static File volumeRootFromExternalFilesDir(File appDir) {
        if (appDir == null) return null;
        File cur = appDir;
        // .../<volume>/Android/data/<package>/files -> <volume>
        for (int i = 0; i < 4 && cur != null; i++) cur = cur.getParentFile();
        if (cur != null && cur.isDirectory()) return cur;
        return null;
    }

    private static int removableRank(File dir) {
        String p = dir.getAbsolutePath();
        if (p.startsWith("/storage/") && !p.startsWith("/storage/emulated")) return 0;
        return 1;
    }
}
