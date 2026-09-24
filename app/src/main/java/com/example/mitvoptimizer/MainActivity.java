package com.example.mitvoptimizer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private TextView statusSummary;
    private Button launcherCheck;
    private Button adbTest;
    private Button planSettings;
    private Button optimize;
    private Button[] actionButtons;
    private ProgressDialog progressDialog;

    private boolean preflightOk = false;
    private boolean adbVerified = false;
    private boolean busy = false;

    private static final int MODE_SAFE_DISABLE = 0;
    private static final int MODE_ORIGINAL_UNINSTALL = 1;

    private static final String PREFS = "optimizer_state";
    private static final String KEY_DISABLED = "disabled_by_tool";
    // Only kept so a v0.7 install can still recover a home package that v0.7 removed.
    private static final String KEY_HOME_REMOVED = "home_removed";
    private static final String KEY_SELECTED = "selected_packages";

    private enum PackageState {
        ACTIVE,
        DISABLED,
        UNINSTALLED,
        ABSENT
    }

    private static final class PlanSnapshot {
        final List<String> allTargets;
        final Map<String, PackageState> states;
        final Set<String> installed;
        final Set<String> disabled;
        final Set<String> knownIncludingUninstalled;
        final int activeCount;
        final int disabledCount;
        final int uninstalledCount;
        final int absentCount;
        final String overallState;

        PlanSnapshot(List<String> allTargets,
                     Map<String, PackageState> states,
                     Set<String> installed,
                     Set<String> disabled,
                     Set<String> knownIncludingUninstalled,
                     int activeCount,
                     int disabledCount,
                     int uninstalledCount,
                     int absentCount,
                     String overallState) {
            this.allTargets = allTargets;
            this.states = states;
            this.installed = installed;
            this.disabled = disabled;
            this.knownIncludingUninstalled = knownIncludingUninstalled;
            this.activeCount = activeCount;
            this.disabledCount = disabledCount;
            this.uninstalledCount = uninstalledCount;
            this.absentCount = absentCount;
            this.overallState = overallState;
        }
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        statusSummary = findViewById(R.id.statusSummary);
        launcherCheck = findViewById(R.id.launcherCheck);
        adbTest = findViewById(R.id.adbTest);
        planSettings = findViewById(R.id.planSettings);
        optimize = findViewById(R.id.optimize);
        actionButtons = new Button[]{launcherCheck, adbTest, planSettings, optimize};

        launcherCheck.setOnClickListener(v -> refreshPreflight(true));
        adbTest.setOnClickListener(v -> testLocalAdb());
        planSettings.setOnClickListener(v -> scanAndShowPlanSettings());
        optimize.setOnClickListener(v -> runOptimize());

        launcherCheck.requestFocus();
        if (!requestStorageIfNeeded()) refreshPreflight(false);
        else updateSummary();
    }

    private boolean requestStorageIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            return true;
        }
        return false;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 100) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshPreflight(true);
        } else {
            preflightOk = false;
            updateSummary();
            showInfoDialog("存储权限未授权", "无法扫描 U 盘中的 launcher.apk。请在系统设置中允许本应用读取存储后，再点击“检测桌面”。");
        }
    }

    /** v0.8 only recognises launcher.apk. Every other APK is deliberately ignored. */
    private void refreshPreflight(boolean showDialog) {
        StringBuilder sb = new StringBuilder();
        try {
            List<File> launcherDirs = LauncherGuard.launcherDirectories(this);
            File launcher = null;
            String launcherPkg = null;

            if (launcherDirs.size() == 1) {
                File root = launcherDirs.get(0);
                launcher = new File(root, LauncherGuard.LAUNCHER_APK_NAME);
                launcherPkg = LauncherGuard.packageNameForApk(this, launcher);
                sb.append("桌面 APK 目录：\n").append(root.getAbsolutePath()).append("\n\n");
            } else if (launcherDirs.isEmpty()) {
                sb.append("未检测到 launcher.apk。\n\n");
            } else {
                sb.append("检测到多个 launcher.apk，请只保留一个：\n");
                for (File d : launcherDirs) sb.append("• ").append(d.getAbsolutePath()).append('\n');
                sb.append('\n');
            }

            if (launcher != null && launcher.isFile() && launcher.canRead() && launcher.length() > 0) {
                sb.append("✓ launcher.apk 已找到\n");
                sb.append("  ").append(launcher.getAbsolutePath()).append('\n');
                if (launcherPkg != null) sb.append("✓ APK 包名：").append(launcherPkg).append('\n');
                else sb.append("✗ 无法解析 launcher.apk；禁止精简\n");
            } else {
                sb.append("✗ 未找到唯一可读的 launcher.apk；禁止精简\n");
            }

            sb.append("\n其它 APK：本工具一律忽略，不会自动安装。\n");
            sb.append("\n已安装第三方 HOME：\n");
            List<String> homes = LauncherGuard.installedThirdPartyHomes(this);
            if (homes.isEmpty()) sb.append("（未检测到；正式精简会先安装 launcher.apk）\n");
            else for (String h : homes) sb.append("• ").append(h).append('\n');

            preflightOk = launcherDirs.size() == 1 && launcherPkg != null;
            if (preflightOk) sb.append("\n✓ 桌面文件前置条件满足。\n");
            else sb.append("\n【禁止精简】请准备唯一、有效的 launcher.apk。\n");

            sb.append("\n硬保护：com.xiaomi.mitv.systemui、com.droidlogic、VPN/Proxy/Statement、账号认证。\n");
            sb.append("小米原生桌面只有在第三方 HOME 安装、启动和二次验证全部成功后才处理。");
        } catch (Throwable t) {
            preflightOk = false;
            sb.append("桌面检测失败：").append(errorText(t));
        }

        updateSummary();
        if (showDialog) showInfoDialog("检测桌面", sb.toString());
    }

    private void testLocalAdb() {
        setBusy(true, "正在连接电视本机 ADB 127.0.0.1:5555…\n首次使用兼容通道时可能出现 RSA 授权，请选择允许。");
        new Thread(() -> {
            try (LocalAdb adb = new LocalAdb(this)) {
                LocalAdb.Result r = adb.ping();
                boolean ok = r.getOk() && r.getOutput() != null && r.getOutput().contains("MITV_LOCAL_ADB_OK");
                adbVerified = ok;
                String msg = ok
                        ? "✓ 本机 ADB 连接正常。\n\n" + r.getOutput() + "\n\n可以继续进入“精简设置”或“开始精简”。"
                        : "ADB 已连接，但测试命令未通过：\n" + r.getOutput();
                runOnUiThread(() -> {
                    setBusy(false, null);
                    updateSummary();
                    showInfoDialog(ok ? "测试连接：成功" : "测试连接：失败", msg);
                });
            } catch (Throwable t) {
                adbVerified = false;
                runOnUiThread(() -> {
                    setBusy(false, null);
                    updateSummary();
                    showInfoDialog("测试连接：失败",
                            errorText(t) + "\n\n请确认电视已开启网络/ADB调试，且本机 5555 端口可用。首次使用兼容通道可能需要重新确认一次 RSA 授权。");
                });
            }
        }).start();
    }

    /**
     * 精简设置 is intentionally state-driven: it scans the TV first, then labels every
     * known target as 待精简 / 已禁用 / 已卸载 / 本机不存在. No separate audit page is needed.
     */
    private void scanAndShowPlanSettings() {
        setBusy(true, "正在扫描当前精简状态…");
        new Thread(() -> {
            try (LocalAdb adb = new LocalAdb(this)) {
                LocalAdb.Result ping = adb.ping();
                if (!ping.getOk() || ping.getOutput() == null || !ping.getOutput().contains("MITV_LOCAL_ADB_OK")) {
                    throw new Exception("本机 ADB 不可用: " + ping.getOutput());
                }
                adbVerified = true;
                PlanSnapshot snapshot = scanPlan(adb);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    updateSummary();
                    showPlanChooser(snapshot);
                });
            } catch (Throwable t) {
                adbVerified = false;
                runOnUiThread(() -> {
                    setBusy(false, null);
                    updateSummary();
                    showInfoDialog("精简状态扫描失败", errorText(t) + "\n\n请先确认“测试连接”能够成功。");
                });
            }
        }).start();
    }

    private PlanSnapshot scanPlan(LocalAdb adb) throws Exception {
        List<String> all = OptimizerPlan.safeDisablePackages(this);
        Set<String> installed = readPackageSet(adb, "");
        Set<String> disabled = readPackageSet(adb, "-d");
        Set<String> known = readPackageSet(adb, "-u");

        Map<String, PackageState> states = new HashMap<>();
        int active = 0;
        int disabledCount = 0;
        int uninstalled = 0;
        int absent = 0;

        for (String pkg : all) {
            PackageState state;
            if (disabled.contains(pkg)) {
                state = PackageState.DISABLED;
                disabledCount++;
            } else if (installed.contains(pkg)) {
                state = PackageState.ACTIVE;
                active++;
            } else if (known.contains(pkg)) {
                state = PackageState.UNINSTALLED;
                uninstalled++;
            } else {
                state = PackageState.ABSENT;
                absent++;
            }
            states.put(pkg, state);
        }

        int relevant = active + disabledCount + uninstalled;
        int processed = disabledCount + uninstalled;
        String overall;
        if (relevant <= 0) {
            overall = "未检测到适用项";
        } else {
            double ratio = processed / (double) relevant;
            if (ratio <= 0.20d) overall = "未精简";
            else if (ratio >= 0.80d) overall = "已精简";
            else overall = "部分精简";
        }

        return new PlanSnapshot(all, states, installed, disabled, known,
                active, disabledCount, uninstalled, absent, overall);
    }

    /** pm list packages --user 0 is preferred; old firmwares fall back to the legacy form. */
    private Set<String> readPackageSet(LocalAdb adb, String flags) throws Exception {
        String trimmedFlags = flags == null ? "" : flags.trim();
        String first = "pm list packages" + (trimmedFlags.isEmpty() ? "" : " " + trimmedFlags) + " --user 0";
        LocalAdb.Result r = adb.shell(first);
        if (!r.getOk() || looksLikeUnsupportedOption(r.getOutput())) {
            String fallback = "pm list packages" + (trimmedFlags.isEmpty() ? "" : " " + trimmedFlags);
            r = adb.shell(fallback);
        }
        if (!r.getOk() || looksLikeUnsupportedOption(r.getOutput())) {
            throw new Exception("读取应用状态失败: " + trim(r.getOutput()));
        }
        return parsePackageList(r.getOutput());
    }

    private static boolean looksLikeUnsupportedOption(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase(java.util.Locale.US);
        return lower.contains("unknown option") || lower.contains("unknown argument") ||
                lower.contains("unrecognized option") || lower.contains("invalid option");
    }

    private static Set<String> parsePackageList(String output) {
        Set<String> out = new HashSet<>();
        if (output == null) return out;
        String[] lines = output.replace("\r", "").split("\n");
        for (String line : lines) {
            String s = line.trim();
            if (!s.startsWith("package:")) continue;
            String pkg = s.substring("package:".length()).trim();
            int eq = pkg.lastIndexOf('=');
            if (eq >= 0 && eq + 1 < pkg.length()) pkg = pkg.substring(eq + 1).trim();
            if (!pkg.isEmpty()) out.add(pkg);
        }
        return out;
    }

    private void showPlanChooser(PlanSnapshot snapshot) {
        try {
            Set<String> current = new HashSet<>(selectedPackages());
            List<String> visible = new ArrayList<>();
            for (String pkg : snapshot.allTargets) {
                // Packages that do not exist on this firmware do not need to clutter the TV list.
                if (snapshot.states.get(pkg) != PackageState.ABSENT) visible.add(pkg);
            }

            String[] labels = new String[visible.size()];
            boolean[] checked = new boolean[visible.size()];
            for (int i = 0; i < visible.size(); i++) {
                String pkg = visible.get(i);
                PackageState state = snapshot.states.get(pkg);
                labels[i] = stateTag(state) + "  " + compactDisplay(pkg);
                checked[i] = state != PackageState.UNINSTALLED && current.contains(pkg);
            }

            String title = "精简设置 · " + snapshot.overallState;

            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存", (d, w) -> {
                        // Preserve selections for firmware-absent packages while updating all visible rows.
                        Set<String> chosen = new HashSet<>(current);
                        for (int i = 0; i < visible.size(); i++) {
                            if (checked[i]) chosen.add(visible.get(i));
                            else chosen.remove(visible.get(i));
                        }
                        getPrefs().edit().putStringSet(KEY_SELECTED, chosen).apply();
                        updateSummary();
                        showInfoDialog("精简设置已保存", "已选择 " + countSelectedRelevant(chosen, snapshot) + " 个当前可处理项目。\n\n“开始精简”时再选择安全禁用或原版卸载模式。");
                    });

            boolean hasRestorable = snapshot.disabledCount > 0 || snapshot.disabled.contains(LauncherGuard.XIAOMI_HOME)
                    || getPrefs().getBoolean(KEY_HOME_REMOVED, false);
            if (hasRestorable) {
                builder.setNeutralButton("恢复禁用", (d, w) -> showRestoreChooser(snapshot));
            }

            AlertDialog dialog = builder.create();
            dialog.show();
            fitDialog(dialog, 0.76f, visible.size() > 8 ? 0.72f : 0f);
        } catch (Throwable t) {
            showInfoDialog("无法读取精简设置", errorText(t));
        }
    }

    private int countSelectedRelevant(Set<String> chosen, PlanSnapshot snapshot) {
        int count = 0;
        for (String p : chosen) {
            PackageState s = snapshot.states.get(p);
            if (s == PackageState.ACTIVE || s == PackageState.DISABLED) count++;
        }
        return count;
    }

    private void showRestoreChooser(PlanSnapshot snapshot) {
        List<String> restorePkgs = new ArrayList<>();
        for (String pkg : snapshot.allTargets) {
            if (snapshot.disabled.contains(pkg)) restorePkgs.add(pkg);
        }
        if (snapshot.disabled.contains(LauncherGuard.XIAOMI_HOME) && !restorePkgs.contains(LauncherGuard.XIAOMI_HOME)) {
            restorePkgs.add(LauncherGuard.XIAOMI_HOME);
        }

        final boolean legacyHome = getPrefs().getBoolean(KEY_HOME_REMOVED, false)
                && !snapshot.disabled.contains(LauncherGuard.XIAOMI_HOME);

        if (restorePkgs.isEmpty() && !legacyHome) {
            showInfoDialog("恢复禁用", "当前没有检测到可恢复的安全禁用项目。\n\n原版卸载模式产生的项目不在这里恢复；需要时以恢复出厂设置作为兜底。");
            return;
        }

        List<String> rows = new ArrayList<>();
        for (String pkg : restorePkgs) rows.add(pkg);
        if (legacyHome) rows.add("__LEGACY_HOME__");

        Set<String> recorded = new HashSet<>(getPrefs().getStringSet(KEY_DISABLED, new HashSet<>()));
        String[] labels = new String[rows.size()];
        boolean[] checked = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            if ("__LEGACY_HOME__".equals(row)) {
                checked[i] = true;
                labels[i] = "[旧版记录] 小米原生桌面 · " + LauncherGuard.XIAOMI_HOME;
            } else {
                boolean byTool = recorded.contains(row);
                checked[i] = byTool;
                labels[i] = (byTool ? "[本工具记录] " : "[已禁用/未记录] ") + compactDisplay(row);
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("恢复安全禁用")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (d, w) -> {
                    List<String> chosen = new ArrayList<>();
                    boolean restoreLegacyHome = false;
                    for (int i = 0; i < rows.size(); i++) {
                        if (!checked[i]) continue;
                        if ("__LEGACY_HOME__".equals(rows.get(i))) restoreLegacyHome = true;
                        else chosen.add(rows.get(i));
                    }
                    executeRestore(chosen, restoreLegacyHome);
                })
                .create();
        dialog.show();
        fitDialog(dialog, 0.72f, rows.size() > 7 ? 0.68f : 0f);
    }

    private void executeRestore(List<String> packages, boolean restoreLegacyHome) {
        setBusy(true, "正在恢复所选安全禁用项目…");
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            try (LocalAdb adb = new LocalAdb(this)) {
                LocalAdb.Result ping = adb.ping();
                if (!ping.getOk()) throw new Exception("本机 ADB 不可用: " + ping.getOutput());
                adbVerified = true;

                Set<String> recorded = new HashSet<>(getPrefs().getStringSet(KEY_DISABLED, new HashSet<>()));
                for (String pkg : packages) {
                    LocalAdb.Result r = adb.shell("pm enable --user 0 " + shellQuote(pkg));
                    log.append(r.getOk() ? "✓ " : "✗ ")
                            .append("恢复 ").append(compactDisplay(pkg)).append(": ")
                            .append(trim(r.getOutput())).append('\n');
                    if (r.getOk()) recorded.remove(pkg);
                }
                getPrefs().edit().putStringSet(KEY_DISABLED, recorded).apply();

                if (restoreLegacyHome) {
                    LocalAdb.Result h = adb.shell("cmd package install-existing --user 0 " + LauncherGuard.XIAOMI_HOME);
                    if (!h.getOk()) h = adb.shell("pm install-existing --user 0 " + LauncherGuard.XIAOMI_HOME);
                    if (h.getOk()) {
                        adb.shell("pm enable --user 0 " + LauncherGuard.XIAOMI_HOME);
                        getPrefs().edit().putBoolean(KEY_HOME_REMOVED, false).apply();
                    }
                    log.append(h.getOk() ? "✓ " : "✗ ")
                            .append("恢复旧版记录的小米原生桌面: ")
                            .append(trim(h.getOutput())).append('\n');
                }

                if (log.length() == 0) log.append("没有选择需要恢复的项目。");
                final String out = log.toString();
                runOnUiThread(() -> {
                    setBusy(false, null);
                    updateSummary();
                    showInfoDialog("恢复结果", out);
                });
            } catch (Throwable t) {
                final String out = log.append("\n恢复过程出错：").append(errorText(t)).toString();
                runOnUiThread(() -> {
                    setBusy(false, null);
                    updateSummary();
                    showInfoDialog("恢复失败", out);
                });
            }
        }).start();
    }

    private void runOptimize() {
        refreshPreflight(false);
        if (!preflightOk) {
            showInfoDialog("禁止精简", "没有找到唯一且有效的 launcher.apk。请先点击“检测桌面”查看详情。");
            return;
        }
        if (!adbVerified) {
            showInfoDialog("禁止精简", "本次启动尚未通过 ADB 验证。请先点击“测试连接”；“精简设置”的自动扫描成功也会完成连接验证。");
            return;
        }

        String[] modes = new String[]{
                "安全禁用（可恢复）",
                "原版卸载（恢复出厂设置兜底）"
        };
        final int[] choice = new int[]{MODE_SAFE_DISABLE};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择精简模式")
                .setSingleChoiceItems(modes, MODE_SAFE_DISABLE, (d, which) -> choice[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步", (d, w) -> showFinalPreview(choice[0]))
                .create();
        dialog.show();
        fitDialog(dialog, 0.66f, 0f);
    }

    private void showFinalPreview(int mode) {
        try {
            List<String> packages = selectedPackages();
            File launcher = LauncherGuard.launcherApk(this);
            StringBuilder sb = new StringBuilder();
            sb.append("模式：").append(modeName(mode)).append('\n');
            sb.append("桌面：").append(launcher == null ? "未找到" : launcher.getAbsolutePath()).append('\n');
            sb.append("目标：").append(packages.size()).append(" 个普通精简项\n");
            sb.append("硬保护：SystemUI、Droidlogic、VPN/Proxy/Statement、账号认证\n\n");

            if (mode == MODE_SAFE_DISABLE) {
                sb.append("安全禁用模式：普通目标和小米原生桌面使用 disable-user；可在“精简设置 → 恢复禁用”中重新启用。\n\n");
            } else {
                sb.append("原版卸载模式：普通目标使用 pm uninstall --user 0，更接近最初 EXE 的效果；仍保留本工具的硬保护。工具不保证一键恢复，以恢复出厂设置作为兜底。\n\n");
            }

            sb.append("执行前会再次验证 ADB、安装并验证 launcher.apk，只有第三方 HOME 安装/启动/二次验证全部成功后才处理小米原生桌面。\n\n");
            sb.append("—— 本次选择 ——\n");
            for (String p : packages) sb.append("• ").append(compactDisplay(p)).append('\n');

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("确认开始精简")
                    .setMessage(sb.toString())
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认精简", (d, w) -> executeOptimize(mode))
                    .create();
            dialog.show();
            fitDialog(dialog, 0.76f, 0.72f);
        } catch (Throwable t) {
            showInfoDialog("预览失败", errorText(t));
        }
    }

    private void executeOptimize(int mode) {
        setBusy(true, "正在执行“" + modeName(mode) + "”，请勿断电。关键步骤失败会立即中止后续危险操作。");
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            int commandOk = 0;
            int commandFail = 0;
            int alreadyProcessed = 0;
            int notPresent = 0;
            boolean homeProcessed = false;
            boolean reopenOk = false;
            Set<String> attempted = new LinkedHashSet<>();

            try (LocalAdb adb = new LocalAdb(this)) {
                if (!LauncherGuard.preflightSatisfied(this)) throw new Exception("launcher.apk 文件前置条件已变化，请重新检测");
                LocalAdb.Result ping = adb.ping();
                if (!ping.getOk() || ping.getOutput() == null || !ping.getOutput().contains("MITV_LOCAL_ADB_OK")) {
                    adbVerified = false;
                    throw new Exception("本机 ADB 已失效: " + ping.getOutput());
                }
                adbVerified = true;
                log.append("✓ 本机 ADB 已重新验证\n");

                // 1) launcher.apk is the only APK installed automatically by v0.8.
                File launcher = LauncherGuard.launcherApk(this);
                String launcherPkg = LauncherGuard.stagedLauncherPackage(this);
                if (launcher == null || launcherPkg == null) throw new Exception("launcher.apk 无法解析包名");
                LocalAdb.Result li = adb.install(launcher);
                log.append(li.getOk() ? "✓ " : "✗ ").append("安装 launcher.apk: ").append(trim(li.getOutput())).append('\n');
                if (!li.getOk()) throw new Exception("第三方桌面安装失败，已停止精简");
                sleep(1200);

                ResolveInfo home = LauncherGuard.findHome(this, launcherPkg);
                if (home == null || home.activityInfo == null) throw new Exception("launcher.apk 安装后未注册为 HOME，已停止精简");
                String component = launcherPkg + "/" + home.activityInfo.name;
                LocalAdb.Result start = adb.shell("am start -n " + shellQuote(component));
                log.append(start.getOk() ? "✓ " : "✗ ").append("启动第三方桌面: ").append(trim(start.getOutput())).append('\n');
                if (!start.getOk()) throw new Exception("第三方桌面启动失败，已停止精简");
                sleep(800);
                if (!LauncherGuard.canHandleHome(this, launcherPkg)) throw new Exception("第三方 HOME 二次验证失败，已停止精简");
                log.append("✓ 第三方桌面 HOME 验证通过: ").append(launcherPkg).append("\n");

                // 2) Scan current state so missing/already-processed packages are not reported as false failures.
                PlanSnapshot before = scanPlan(adb);
                List<String> packages = selectedPackages();
                Set<String> changed = new HashSet<>(getPrefs().getStringSet(KEY_DISABLED, new HashSet<>()));

                for (String pkg : packages) {
                    PackageState state = before.states.get(pkg);
                    if (state == null || state == PackageState.ABSENT) {
                        notPresent++;
                        log.append("· 本机不存在：").append(compactDisplay(pkg)).append('\n');
                        continue;
                    }
                    if (state == PackageState.UNINSTALLED) {
                        alreadyProcessed++;
                        log.append("· 已卸载：").append(compactDisplay(pkg)).append('\n');
                        continue;
                    }
                    if (mode == MODE_SAFE_DISABLE && state == PackageState.DISABLED) {
                        alreadyProcessed++;
                        log.append("· 已禁用：").append(compactDisplay(pkg)).append('\n');
                        continue;
                    }

                    String command = mode == MODE_SAFE_DISABLE
                            ? "pm disable-user --user 0 " + shellQuote(pkg)
                            : "pm uninstall --user 0 " + shellQuote(pkg);
                    LocalAdb.Result r = adb.shell(command);
                    attempted.add(pkg);
                    if (r.getOk()) {
                        commandOk++;
                        if (mode == MODE_SAFE_DISABLE) changed.add(pkg);
                        else changed.remove(pkg);
                    } else {
                        commandFail++;
                    }
                    log.append(r.getOk() ? "✓ " : "✗ ")
                            .append(mode == MODE_SAFE_DISABLE ? "禁用 " : "卸载 ")
                            .append(compactDisplay(pkg)).append(": ").append(trim(r.getOutput())).append('\n');
                }
                getPrefs().edit().putStringSet(KEY_DISABLED, changed).apply();

                // 3) Xiaomi launcher is always last and remains conditional on the verified third-party HOME.
                boolean xiaomiInstalled = before.installed.contains(LauncherGuard.XIAOMI_HOME);
                boolean xiaomiDisabled = before.disabled.contains(LauncherGuard.XIAOMI_HOME);
                if (mode == MODE_SAFE_DISABLE) {
                    if (xiaomiDisabled) {
                        homeProcessed = true;
                        log.append("· 小米原生桌面已经处于禁用状态\n");
                    } else if (xiaomiInstalled) {
                        LocalAdb.Result hr = adb.shell("pm disable-user --user 0 " + LauncherGuard.XIAOMI_HOME);
                        homeProcessed = hr.getOk();
                        log.append(homeProcessed ? "✓ " : "✗ ").append("禁用小米原生桌面: ").append(trim(hr.getOutput())).append('\n');
                        if (homeProcessed) {
                            changed.add(LauncherGuard.XIAOMI_HOME);
                            getPrefs().edit().putStringSet(KEY_DISABLED, changed).apply();
                        }
                    } else {
                        homeProcessed = true;
                        log.append("· 小米原生桌面当前已不在 user 0 安装列表\n");
                    }
                } else {
                    if (xiaomiInstalled) {
                        LocalAdb.Result hr = adb.shell("pm uninstall --user 0 " + LauncherGuard.XIAOMI_HOME);
                        homeProcessed = hr.getOk();
                        log.append(homeProcessed ? "✓ " : "✗ ").append("卸载小米原生桌面: ").append(trim(hr.getOutput())).append('\n');
                        if (homeProcessed) {
                            changed.remove(LauncherGuard.XIAOMI_HOME);
                            getPrefs().edit().putStringSet(KEY_DISABLED, changed).apply();
                        }
                    } else {
                        homeProcessed = true;
                        log.append("· 小米原生桌面已经不在 user 0 安装列表\n");
                    }
                    // v0.8 original mode deliberately does not create a one-click recovery record.
                }

                LocalAdb.Result sr = adb.shell("am start -n " + shellQuote(component));
                reopenOk = sr.getOk();
                log.append(reopenOk ? "✓ " : "✗ ").append("重新启动第三方桌面: ").append(trim(sr.getOutput())).append('\n');

                // 4) Post-run verification: do not trust command text alone.
                PlanSnapshot after = scanPlan(adb);
                List<String> residual = new ArrayList<>();
                for (String pkg : attempted) {
                    PackageState state = after.states.get(pkg);
                    boolean ok = mode == MODE_SAFE_DISABLE
                            ? state == PackageState.DISABLED || state == PackageState.UNINSTALLED || state == PackageState.ABSENT
                            : state == PackageState.UNINSTALLED || state == PackageState.ABSENT;
                    if (!ok) residual.add(pkg);
                }
                boolean homeVerified;
                if (mode == MODE_SAFE_DISABLE) {
                    homeVerified = after.disabled.contains(LauncherGuard.XIAOMI_HOME)
                            || !after.installed.contains(LauncherGuard.XIAOMI_HOME);
                } else {
                    homeVerified = !after.installed.contains(LauncherGuard.XIAOMI_HOME);
                }
                homeProcessed = homeProcessed && homeVerified;

                final int okFinal = commandOk;
                final int failFinal = commandFail;
                final int alreadyFinal = alreadyProcessed;
                final int absentFinal = notPresent;
                final boolean homeFinal = homeProcessed;
                final boolean reopenFinal = reopenOk;
                final List<String> residualFinal = new ArrayList<>(residual);
                final String logFinal = log.toString();
                runOnUiThread(() -> {
                    setBusy(false, null);
                    updateSummary();
                    showOptimizeComplete(mode, logFinal, okFinal, failFinal, alreadyFinal, absentFinal,
                            homeFinal, reopenFinal, residualFinal);
                });
            } catch (Throwable t) {
                log.append("\n【已中止】").append(errorText(t)).append("\n危险后续步骤不会继续执行。");
                final String failedLog = log.toString();
                runOnUiThread(() -> {
                    setBusy(false, null);
                    updateSummary();
                    showInfoDialog("精简已中止", failedLog);
                });
            }
        }).start();
    }

    private void showOptimizeComplete(int mode, String log, int ok, int fail, int already, int absent,
                                      boolean homeOk, boolean reopenOk, List<String> residual) {
        StringBuilder sb = new StringBuilder();
        sb.append("模式：").append(modeName(mode)).append('\n');
        sb.append("命令成功：").append(ok).append("；命令失败：").append(fail).append('\n');
        sb.append("已处理无需重复：").append(already).append("；本机无此项：").append(absent).append('\n');
        sb.append("小米原生桌面：").append(homeOk ? "处理后复核通过" : "未达到预期状态").append('\n');
        sb.append("第三方桌面重开：").append(reopenOk ? "成功" : "失败").append('\n');

        if (residual.isEmpty()) {
            sb.append("精简后复核：未发现本次已执行目标仍处于未处理状态。\n");
        } else {
            sb.append("精简后复核：仍有 ").append(residual.size()).append(" 项未达到预期状态：\n");
            for (String p : residual) sb.append("• ").append(compactDisplay(p)).append('\n');
        }

        if (mode == MODE_ORIGINAL_UNINSTALL) {
            sb.append("\n原版卸载模式不保证工具内恢复；出现异常时以恢复出厂设置作为兜底。\n");
        } else {
            sb.append("\n安全禁用模式可从“精简设置 → 恢复禁用”重新启用。\n");
        }
        sb.append("不会强制自动重启。你可以先检查结果，再决定是否重启。\n\n—— 完整执行日志 ——\n").append(log);

        boolean clean = fail == 0 && homeOk && reopenOk && residual.isEmpty();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(clean ? "精简流程已完成" : "精简完成（请检查结果）")
                .setMessage(sb.toString())
                .setNegativeButton("稍后重启", null)
                .setPositiveButton("立即重启", (d, w) -> rebootTv())
                .create();
        dialog.show();
        fitDialog(dialog, 0.78f, 0.72f);
    }

    private void rebootTv() {
        if (!adbVerified) {
            showInfoDialog("无法重启", "ADB 当前未处于已验证状态，请先重新点击“测试连接”。");
            return;
        }
        setBusy(true, "正在发送电视重启命令…");
        new Thread(() -> {
            try (LocalAdb adb = new LocalAdb(this)) {
                LocalAdb.Result r = adb.reboot();
                runOnUiThread(() -> {
                    setBusy(false, null);
                    showInfoDialog("重启命令", r.getOutput());
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    showInfoDialog("重启命令发送失败", errorText(t));
                });
            }
        }).start();
    }

    private List<String> selectedPackages() throws Exception {
        List<String> all = OptimizerPlan.safeDisablePackages(this);
        Set<String> saved = getPrefs().getStringSet(KEY_SELECTED, null);
        if (saved == null) return all;
        List<String> out = new ArrayList<>();
        for (String p : all) if (saved.contains(p)) out.add(p);
        return out;
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        for (Button b : actionButtons) b.setEnabled(!value);
        updateActionState();

        if (value) {
            if (progressDialog == null) {
                progressDialog = new ProgressDialog(this);
                progressDialog.setIndeterminate(true);
                progressDialog.setCancelable(false);
            }
            progressDialog.setMessage(message == null ? "正在处理…" : message);
            if (!progressDialog.isShowing()) progressDialog.show();
        } else if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void updateActionState() {
        if (busy) {
            optimize.setEnabled(false);
            return;
        }
        for (Button b : actionButtons) b.setEnabled(true);
        optimize.setEnabled(preflightOk && adbVerified);
    }

    private void updateSummary() {
        int selected = -1;
        try { selected = selectedPackages().size(); } catch (Throwable ignored) {}
        String file = preflightOk ? "✓ 桌面就绪" : "○ 桌面未就绪";
        String adb = adbVerified ? "✓ 连接正常" : "○ 连接未验证";
        String count = selected >= 0 ? "  ·  已选 " + selected + " 项" : "";
        statusSummary.setText(file + "    " + adb + count + "\n精简模式在“开始精简”时选择 · 橙黄色表示当前遥控器焦点");
        updateActionState();
    }

    private void showInfoDialog(String title, String message) {
        if (isFinishing()) return;
        String body = message == null || message.isEmpty() ? "（无输出）" : message;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("确定", null)
                .create();
        dialog.show();
        boolean longContent = body.length() > 520 || lineCount(body) > 13;
        fitDialog(dialog, longContent ? 0.76f : 0.68f, longContent ? 0.68f : 0f);
    }

    /** Width is always restrained; long/list dialogs get an internal scroll area instead of going full-screen. */
    private void fitDialog(AlertDialog dialog, float widthFraction, float heightFraction) {
        Window window = dialog.getWindow();
        if (window == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.max(420, (int) (dm.widthPixels * widthFraction));
        int height = heightFraction > 0f
                ? Math.max(260, (int) (dm.heightPixels * heightFraction))
                : WindowManager.LayoutParams.WRAP_CONTENT;
        window.setLayout(width, height);
    }

    private static int lineCount(String s) {
        int lines = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
        return lines;
    }

    private static String stateTag(PackageState state) {
        if (state == PackageState.DISABLED) return "[已禁用]";
        if (state == PackageState.UNINSTALLED) return "[已卸载]";
        if (state == PackageState.ABSENT) return "[本机无此项]";
        return "[待精简]";
    }

    private static String modeName(int mode) {
        return mode == MODE_ORIGINAL_UNINSTALL
                ? "原版卸载（恢复出厂设置兜底）"
                : "安全禁用（可恢复）";
    }

    private static String compactDisplay(String pkg) {
        if (LauncherGuard.XIAOMI_HOME.equals(pkg)) return "小米原生桌面 · " + pkg;
        String friendly = OptimizerPlan.friendlyName(pkg);
        return friendly.equals(pkg) ? pkg : friendly + " · " + pkg;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String trim(String s) {
        if (s == null) return "";
        s = s.trim().replace("\r", "");
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }

    private static String errorText(Throwable t) {
        if (t == null) return "未知错误";
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = t.getClass().getSimpleName();
        return t.getClass().getSimpleName() + ": " + msg;
    }
}
