package com.example.mitvoptimizer;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OptimizerPlan {
    private OptimizerPlan() {}

    /** Packages captured from the original EXE and allowed in our safer profile. */
    public static List<String> safeDisablePackages(Context context) throws Exception {
        InputStream in = context.getAssets().open("commands.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        JSONObject root = new JSONObject(out.toString("UTF-8"));
        JSONArray actions = root.getJSONArray("original_actions");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.getJSONObject(i);
            if (!"uninstall_user0".equals(a.optString("type"))) continue;
            String pkg = a.optString("package");
            if (pkg.isEmpty()) continue;
            if (LauncherGuard.XIAOMI_HOME.equals(pkg)) continue; // handled separately
            if (a.optBoolean("blocked_by_default", false)) continue;
            if (!result.contains(pkg)) result.add(pkg);
        }
        Collections.sort(result);
        return result;
    }

    public static String friendlyName(String pkg) {
        switch (pkg) {
            case "com.xiaomi.mitv.upgrade": return "系统升级";
            case "com.xiaomi.voicecontrol": return "小爱/语音控制";
            case "com.xiaomi.mitv.appstore": return "小米电视应用商店";
            case "com.miui.tv.analytics": return "小米电视统计分析";
            case "com.xiaomi.devicereport": return "设备报告/遥测";
            case "com.xiaomi.mitv.advertis":
            case "com.xiaomi.mitv.advertise": return "小米电视广告组件";
            case "com.miui.systemAdSolution": return "MIUI 系统广告组件";
            case "com.xiaomi.tweather": return "天气";
            case "com.xiaomi.mitv.calendar": return "日历";
            case "com.xiaomi.mitv.payment":
            case "com.xiaomi.mitv.pay":
            case "com.mipay.wallet.tv": return "支付/钱包";
            case "com.xiaomi.smarthome.tv": return "米家/智能家居";
            case "com.xiaomi.dlnatvservice": return "DLNA 服务";
            case "com.duokan.airkan.tvbox": return "投屏/AirKan";
            case "com.xiaomi.mitv.smartshare": return "智能分享";
            case "com.mitv.screensaver": return "屏保";
            case "com.xiaomi.screenrecorder": return "录屏";
            default: return pkg;
        }
    }

    public static String displayLine(String pkg) {
        String n = friendlyName(pkg);
        return n.equals(pkg) ? pkg : n + "\n" + pkg;
    }
}
