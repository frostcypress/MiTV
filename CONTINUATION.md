# MiTV Optimizer 项目累计式接续文档

> 文档用途：**后续如果本聊天窗口崩溃，只需要把本文件 +
> 最终工程压缩包发到新聊天，并说明"按接续文档继续"即可。**
>
> 最后更新：2026-09-24\
> 当前工程版本：`0.8-dualmode-simpleui`\
> 当前状态：**0.7 已通过 GitHub Actions 编译并安装到 MiTV4A 实机；2×4 UI 已实机确认，且用户已经进入精简后的桌面观察结果。0.8 根据第二轮实机反馈完成源码重构：四按钮 UI、只认 launcher.apk、自动状态扫描、双精简模式、禁用恢复合并进“精简设置”、执行后自动复核。下一步是 GitHub Actions 编译 0.8 + MiTV4A 复测。**

------------------------------------------------------------------------

## 0. 当前有效规则（0.8，优先级最高）

> 本节是当前实现的唯一有效摘要。后文 0.6 / 0.7 章节作为历史记录保留；若与本节冲突，以本节为准。

### 0.1 主界面最终收敛为 4 项

``` text
检测桌面    | 测试连接
精简设置    | 开始精简
```

四个按钮都是 4 个汉字。主操作区居中缩小，不再铺满画面；橙黄色仍作为遥控器焦点。

不再设置单独的：

``` text
执行前完整预览
应用 / 系统核查
查看当前已禁用应用
恢复已精简应用
更多工具
```

这些必要逻辑已经合并到“精简设置 / 开始精简 / 完成结果”里。

### 0.2 U 盘规则已改：只认 launcher.apk

0.8 **彻底移除**自动安装 `1.apk / 2.apk / 3.apk` 的逻辑。

当前自动文件规则仅为：

``` text
optimizer.apk
launcher.apk
```

其它 APK 一律忽略，需要时用户自行从 U 盘安装。

### 0.3 “精简设置”先自动扫描当前状态

点击“精简设置”后，先通过本机 ADB 读取：

``` text
pm list packages --user 0
pm list packages -d --user 0
pm list packages -u --user 0
```

老固件若不支持 `--user 0`，自动回退旧格式。

目标状态区分：

``` text
[待精简]
[已禁用]
[已卸载]
[本机无此项]
```

整体状态按适用目标的已处理比例显示：

``` text
0%~20%   → 未精简
20%~80%  → 部分精简
80%~100% → 已精简
```

其中“已禁用 + 已从 user 0 卸载”计为已处理；固件本身不存在的包不作为已处理依据。

“恢复禁用”也并入该弹窗，只处理 `disable-user` 类项目；本工具自己记录的禁用项默认勾选，未记录但当前已禁用的安全目标明确标记并默认不勾选。原版卸载不承诺一键恢复。

### 0.4 “开始精简”时再选择两种模式

**安全禁用（可恢复）**：

``` text
pm disable-user --user 0 <package>
```

0.8 中连 `com.mitv.tvhome` 在安全模式下也改为 `disable-user`，第三方 HOME 完整验证成功后才最后禁用，因此该模式真正保持工具内可恢复。

**原版卸载（恢复出厂设置兜底）**：

``` text
pm uninstall --user 0 <package>
```

更接近原 Windows EXE 的主要精简方式，但**不会复制原 EXE 已经确认危险的 SystemUI 等动作**。`com.mitv.tvhome` 仍然必须在第三方 HOME 完整验证后最后卸载。

原版模式普通卸载项不登记为“可一键恢复”；用户明确接受电视“恢复出厂设置”作为兜底。

### 0.5 两种模式共用永久硬保护

以下包无论哪种模式都不进入普通精简：

``` text
com.xiaomi.mitv.systemui
com.droidlogic
com.android.vpndialogs
com.android.proxyhandler
com.android.statementservice
com.xiaomi.account.auth
```

尤其 `com.xiaomi.mitv.systemui` 的永久保护结论不能推翻。

### 0.6 结果不再只相信命令返回值

0.8 正式执行结束后，会再次扫描目标包状态。若某个本次执行目标仍保持启用/安装状态，会在完成弹窗中列为“未达到预期状态”。这是为解决 0.7 实机反馈中“显示已经精简，但桌面仍有小米商城、小米音响、天气、日历等图标”的核查需求。

注意：不能仅凭图标名称新增包；仍必须使用已捕获清单和电视真实包状态判断。

### 0.7 第二轮实机反馈（已发生）

0.7 GitHub Actions 已成功构建。用户已在 MiTV4A 安装 0.7，并实机确认：

- 2×4 一屏 UI 和橙黄色焦点正常显示；
- 但界面铺得过满，希望整体缩小留白；
- 主界面 8 项过多，要求收敛为 4 项；
- 用户不再需要 `1.apk / 2.apk / 3.apk` 自动安装；
- 用户在当前精简结果中仍看到小米商城、小米音响、天气、日历等应用入口；
- 用户历史上直接使用最初精简方式时，最终可见系统应用基本只剩“电视管家”和“小米电视设置”，这作为后续原版卸载模式的**实机效果参考**，不是新增包名的依据；
- 用户希望保留两种模式：可恢复的 disable 模式 + 接近 EXE 的 uninstall 模式；
- 用户接受原版卸载模式异常时以恢复出厂设置兜底；
- 所有弹窗不要撑满屏幕，内容过多时在弹窗内部滚动。

### 0.8 下一步唯一正确动作

``` text
1. GitHub Actions 编译 0.8
2. MiTV4A 安装/升级
3. 检查四按钮居中 UI 与弹窗尺寸
4. 测试“精简设置”自动识别：未精简 / 部分精简 / 已精简
5. 测试安全禁用 + 恢复禁用
6. 测试原版卸载模式（测试机，接受恢复出厂设置兜底）
7. 对照完成后的自动复核结果与桌面实际残留图标
8. 特别观察小米商城、小米音响、天气、日历等是否仍残留；不要仅凭名称猜包名
```

------------------------------------------------------------------------

## 1. 项目目标

制作一个运行在小米电视上的"精简助手" APK，替代原来的 Windows EXE + ADB
电脑操作。

最终用户体验目标：

1.  U 盘准备初装文件。
2.  电视端只安装一个 `optimizer.apk`。
3.  精简助手自动寻找同目录的第三方桌面和最多 3 个初装应用。
4.  第三方桌面验证成功后，才允许移除小米原生桌面。
5.  普通系统精简项采用更容易恢复的 `pm disable-user --user 0`。
6.  `com.xiaomi.mitv.systemui` 等关键系统组件硬保护。
7.  不需要 Windows CMD。
8.  支持一键恢复本工具自己做过的修改。
9.  GitHub Actions 云端编译，不要求本地安装 Android Studio / SDK /
    Gradle。

------------------------------------------------------------------------

## 2. 原始 Windows EXE 的逆向核实结果

### 2.1 原 EXE

原 EXE SHA-256：

``` text
590126be244802ae77d36a038abdaad5e8083137c88bf43a62cbf38ec83757dc
```

已确认相关行为导入包括：

``` text
CreateProcessW
CreatePipe
PeekNamedPipe
WaitForSingleObject
GetExitCodeProcess
CreateFileW
WriteFile
EnumResourceTypesW
EnumResourceNamesW
LoadResource
FindResourceW
```

说明它确实存在"启动子进程 + 读取输出"的执行链。

### 2.2 内嵌脚本/资源

发现一个约：

``` text
20533 bytes
```

的 RCDATA 加密/不透明资源。

资源中存在：

``` text
e10adc3949ba59abbe56e057f20f883e
Password prompt
Please enter the correct password to continue:
Wrong password
```

该 MD5 对应：

``` text
123456
```

同时确认该 EXE 属于 Bat To Exe 类封装，存在 `-b2edecompile` / `-b2epass`
这一类反编译入口线索。

### 2.3 实际 ADB 行为已经抓到

当前项目中的：

``` text
app/src/main/assets/commands.json
```

保存了从原 EXE 实际捕获/整理出的 `68` 条动作。

动作统计：

``` text
uninstall_user0 : 47
install         : 12
uninstall_global: 3
start_activity  : 3
connect         : 1
reboot          : 1
kill_server     : 1
```

非常重要：

**原 EXE 对大部分精简目标实际使用的是：**

``` text
pm uninstall --user 0 包名
```

而不是 `pm disable-user`。

新 APK 为降低风险，普通精简项已经改为：

``` text
pm disable-user --user 0 包名
```

只有小米原生桌面仍按照原作者思路，在第三方桌面验证成功后执行：

``` text
pm uninstall --user 0 com.mitv.tvhome
```

------------------------------------------------------------------------

## 3. 已确认的关键系统组件

### 3.1 音量图标消失的关键发现

原 EXE 实际执行：

``` text
pm uninstall --user 0 com.xiaomi.mitv.systemui
```

用户此前实测：

-   音量 +/- 本身还能改变音量；
-   但屏幕不显示音量图标/音量 OSD。

因此当前项目把：

``` text
com.xiaomi.mitv.systemui
```

列为**永久硬保护**。

新 APK 不会对它执行精简。

------------------------------------------------------------------------

## 4. 原 EXE 捕获到的 47 个 `pm uninstall --user 0` 目标

### 原始目标全部列表

``` text
com.xiaomi.mitv.upgrade
com.xiaomi.mitv.systemui
com.mitv.alarmcenter
com.xiaomi.mitv.calendar
com.xiaomi.tv.gallery
com.xiaomi.tweather
com.xiaomi.mitv.smartshare
com.mitv.gallery
com.xiaomi.voicecontrol
com.xiaomi.mitv.handbook
com.droidlogic
com.xiaomi.mitv.payment
com.xiaomi.mitv.pay
com.xiaomi.tv.appupgrade
com.android.vpndialogs
com.xiaomi.account.auth
com.jiajia.yundonghui.mitv
com.mipay.wallet.tv
com.xiaomi.smarthome.tv
com.xiaomi.mitv.appstore
com.xiaomi.milink.udt
com.mi.miplay.mitvupnpsink
com.mi.umifrontend
com.miui.tv.analytics
com.xiaomi.dlnatvservice
com.xiaomi.mitv.assistant.manual
com.xiaomi.mitv.shop
com.xiaomi.devicereport
com.xiaomi.mibox.lockscreen
com.duokan.airkan.tvbox
com.mi.umi
com.xiaomi.gamecenter.sdk.service.mibo
com.android.proxyhandler
com.android.statementservice
com.xiaomi.mitv.advertis
com.xiaomi.screenrecorder
com.mitv.screensaver
com.ktcp.tvvideo
com.miui.systemAdSolution
com.pptv.tvsports.preinstall
com.duokan.videodaily
com.pplive.atv
com.xiaomi.mitv.advertise
com.mitv.tvhome
com.cibn.tv
com.gitvdemo.video
com.ktcp.video
```

### 新 APK 当前硬保护

以下项目默认不处理：

``` text
com.xiaomi.mitv.systemui
com.droidlogic
com.android.vpndialogs
com.android.proxyhandler
com.android.statementservice
com.xiaomi.account.auth
```

另外：

``` text
com.mitv.tvhome
```

不是普通硬保护，而是**条件保护**：

只有：

``` text
launcher.apk 找到
→ APK 能解析
→ 安装成功
→ 注册 HOME
→ 实际启动成功
→ 二次 HOME 验证通过
```

全部成功后，才允许：

``` text
pm uninstall --user 0 com.mitv.tvhome
```

------------------------------------------------------------------------

## 5. 原 EXE 的其它重要动作

原 EXE 还执行过：

### 安装 `zm.apk`

``` text
adb install ./root/zm.apk
```

这属于原作者附带的安装逻辑。

新项目**不再捆绑 `zm.apk`**。

原因：最终方案由用户自己准备 `launcher.apk`。

### 原 EXE 安装 8 个附加 APK

原动作里存在：

``` text
install -r root\1.apk
install -r root\2.apk
install -r root\3.apk
install -r root\4.apk
install -r root\5.apk
install -r root\6.apk
install -r root\7.apk
install -r root\8.apk
```

新项目不再采用 8 个。

最终规则只保留：

``` text
1.apk
2.apk
3.apk
```

### 原 EXE 删除三个视频包

原动作包括：

``` text
pm uninstall --user 0 com.cibn.tv
pm uninstall --user 0 com.gitvdemo.video
pm uninstall --user 0 com.ktcp.video
```

以及：

``` text
uninstall com.cibn.tv
uninstall com.gitvdemo.video
uninstall com.ktcp.video
```

新 APK 的安全策略目前主要采用 `disable-user`，不要直接照搬这些 global
uninstall 行为。

### 原 EXE 启动桌面

原动作包括：

``` text
am start com.dangbei.tvlauncher/.activity.SplashActivity
am start com.ktcp.launcher/.activity.SplashActivity
am start com.aijia.launcher/.activity.HomeActivity
```

并最终：

``` text
adb shell reboot
```

新开发版**暂时不自动 reboot**，首次实机测试确认正常后再决定是否加入。

------------------------------------------------------------------------

## 6. 历史 U 盘文件规则（0.7；已被第 0 节的 0.8 规则取代）

这是已经确定的最终用户方案：

``` text
任意同一目录/
├─ optimizer.apk
├─ launcher.apk
├─ 1.apk
├─ 2.apk
└─ 3.apk
```

### `launcher.apk`

必须存在。

要求：

-   文件名必须严格为 `launcher.apk`
-   必须能解析为有效 APK
-   安装成功
-   安装后必须注册 HOME
-   必须实际启动成功
-   二次 HOME 验证成功

否则：

**禁止进入系统精简。**

### `1.apk / 2.apk / 3.apk`

全部可选。

例如：

``` text
launcher.apk
1.apk
3.apk
```

也是合法的。

缺少其中任何一个都不影响精简。

### 其它 APK

一律忽略。

例如 U 盘中有：

``` text
movie.apk
game.apk
some-other-tv.apk
```

即使同目录存在，也不会安装。

程序只认：

``` text
launcher.apk
1.apk
2.apk
3.apk
```

------------------------------------------------------------------------

## 7. 当前程序如何寻找"同目录"

Android 安装 APK 后，通常不会可靠地告诉应用：

> optimizer.apk 当初是从 U 盘哪个物理目录安装的。

因此当前实现不是读取"自身安装来源目录"，而是：

1.  扫描可读共享存储；
2.  扫描外接存储；
3.  精确寻找文件名 `launcher.apk`；
4.  找到后把它的父目录视为初装目录；
5.  只读取这个目录中的：
    -   `1.apk`
    -   `2.apk`
    -   `3.apk`
6.  其它 APK 忽略。

如果发现多个不同目录存在 `launcher.apk`：

``` text
禁止继续
```

并要求用户只保留一个。

当前扫描还有深度/目录数量上限：

``` text
MAX_SCAN_DEPTH = 5
MAX_SCANNED_DIRS = 800
```

------------------------------------------------------------------------

## 8. 当前 APK 的主要代码结构

``` text
app/
├─ src/main/assets/commands.json
├─ src/main/java/com/example/mitvoptimizer/
│  ├─ MainActivity.java
│  ├─ LauncherGuard.java
│  ├─ OptimizerPlan.java
│  └─ LocalAdb.kt
└─ src/main/res/
   ├─ layout/activity_main.xml
   └─ values/styles.xml
```

### `LauncherGuard.java`

负责：

-   找 `launcher.apk`
-   找唯一初装目录
-   读取 `1.apk / 2.apk / 3.apk`
-   解析 launcher 包名
-   检查 HOME
-   检查已安装第三方 HOME
-   防止多个 `launcher.apk`

### `LocalAdb.kt`

当前（0.7 起）使用：

``` text
com.tananaev:adblib:1.3
legacy shell: + sync: v1
```

直接连接：

``` text
127.0.0.1:5555
```

主要能力：

-   ADB shell
-   push APK 到 `/data/local/tmp`
-   `pm install -r`
-   shell 命令执行
-   本应用私有目录保存 ADB key

测试命令：

``` text
echo MITV_LOCAL_ADB_OK
```

### `OptimizerPlan.java`

从：

``` text
commands.json
```

读取原 EXE 捕获到的包名。

默认过滤：

-   `blocked_by_default = true`
-   `com.mitv.tvhome`

普通目标最终用于：

``` text
pm disable-user --user 0
```

------------------------------------------------------------------------

## 9. 历史执行顺序（0.7；已被第 0 节的 0.8 规则取代）

正式精简目前设计为：

``` text
① 找到唯一 launcher.apk
        ↓
② 解析 APK
        ↓
③ 本机 ADB 连接 127.0.0.1:5555
        ↓
④ 安装 launcher.apk
        ↓
⑤ 检查 HOME
        ↓
⑥ 启动第三方桌面
        ↓
⑦ 二次 HOME 验证
        ↓
⑧ 安装同目录 1.apk / 2.apk / 3.apk
        ↓
⑨ 普通系统包执行 disable-user
        ↓
⑩ 最后处理 com.mitv.tvhome
        ↓
⑪ 再次启动第三方桌面
        ↓
⑫ 当前版本不自动重启
```

如果第 1～7 步任何一个关键步骤失败：

``` text
停止
```

不会继续移除原生桌面。

普通附加 APK 安装失败：

``` text
记录失败
继续系统精简
```

------------------------------------------------------------------------

## 10. 历史恢复逻辑（0.7；0.8 当前规则见第 0 节）

程序用 SharedPreferences 保存：

``` text
disabled_by_tool
```

只恢复本工具自己成功执行过的 `disable-user` 项目。

恢复方式：

``` text
pm enable --user 0 包名
```

如果本工具处理过：

``` text
com.mitv.tvhome
```

则尝试：

``` text
cmd package install-existing --user 0 com.mitv.tvhome
pm enable --user 0 com.mitv.tvhome
```

并保留旧系统回退：

``` text
pm install-existing --user 0 com.mitv.tvhome
```

------------------------------------------------------------------------

## 11. GitHub Actions

不要求本地：

-   Android Studio
-   Android SDK
-   Gradle

当前工作流：

``` text
.github/workflows/build-apk.yml
```

使用：

``` text
JDK 17
Android SDK 35
Build Tools 35.0.0
Gradle 8.7
```

主要编译：

``` text
gradle --no-daemon --stacktrace --warning-mode all :app:assembleDebug
```

成功后输出：

``` text
MiTVOptimizer-debug.apk
MiTVOptimizer-debug.apk.sha256
```

失败时仍上传：

``` text
MiTVOptimizer-build-log
```

------------------------------------------------------------------------

## 12. 当前 CI 已验证

用户已经实际运行 GitHub Actions。

最新上传的 `build.log` 明确显示：

``` text
:app:compileDebugKotlin
:app:compileDebugJavaWithJavac
:app:dexBuilderDebug
:app:packageDebug
:app:assembleDebug
```

最后：

``` text
BUILD SUCCESSFUL in 59s
36 actionable tasks: 36 executed
```

所以：

**这里记录的是 0.6 工程已经通过 CI 编译。0.7 修改后尚需重新跑一次 GitHub Actions，不能把 0.6 的成功结果当作 0.7 已编译通过。**

只有一个非致命警告：

``` text
Android Gradle plugin 8.5.2
tested up to compileSdk = 34
project uses compileSdk = 35
```

目前不影响 APK 构建。

后续可升级 AGP 或调整 compileSdk/警告处理。

------------------------------------------------------------------------

## 13. 当前最终工程文件

最新工程包：

``` text
MiTVOptimizerProject_same_dir_3apps.zip
```

版本：

``` text
0.7-legacyadb-tvui
```

主要文件：

``` text
build.gradle
gradle.properties
settings.gradle
README.md
REVERSE_NOTES.txt

app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/assets/commands.json

app/src/main/java/com/example/mitvoptimizer/MainActivity.java
app/src/main/java/com/example/mitvoptimizer/LauncherGuard.java
app/src/main/java/com/example/mitvoptimizer/OptimizerPlan.java
app/src/main/java/com/example/mitvoptimizer/LocalAdb.kt

app/src/main/res/layout/activity_main.xml
app/src/main/res/values/styles.xml

.github/workflows/build-apk.yml
```

------------------------------------------------------------------------

## 14. 下一阶段：电视实机测试

不要第一次就直接正式精简。

建议顺序：

### 测试 A：APK 启动

确认：

``` text
optimizer.apk
```

能够安装并打开。

### 测试 B：U 盘扫描

U 盘同一目录：

``` text
optimizer.apk
launcher.apk
1.apk
2.apk
3.apk
```

打开精简助手。

应该看到：

``` text
✓ launcher.apk 已找到
✓ APK 包名
附加应用：1.apk / 2.apk / 3.apk
```

### 测试 C：本机 ADB

点击：

``` text
测试本机 ADB
```

目标：

``` text
127.0.0.1:5555
```

如果电视弹出 RSA 授权：

``` text
允许
```

成功标志：

``` text
MITV_LOCAL_ADB_OK
```

### 测试 D：预览

确认待处理列表中：

``` text
com.xiaomi.mitv.systemui
```

绝对不能出现。

### 测试 E：第三方桌面

正式精简前确认：

``` text
launcher.apk
→ 安装成功
→ HOME 成功
→ 启动成功
```

### 测试 F：正式精简

第一次测试建议只在一台测试电视执行。

执行完成后检查：

-   第三方桌面
-   音量 OSD
-   音量 +/- 功能
-   设置
-   Wi-Fi
-   蓝牙
-   遥控器
-   输入法
-   应用启动
-   系统更新（如果被精简）
-   小米账号（如果保留）
-   投屏/DLNA（如果保留）

当前版本不会自动 reboot。

------------------------------------------------------------------------

## 15. 尚未完成 / 不要假设已经验证的事项

以下事项目前**不能认为已经实机验证**：

1.  0.7 新的 `com.tananaev:adblib:1.3` legacy `shell:` / `sync:` v1 通道在 MiTV4A 上的实际成功率（源码已改，尚待复测）。
2.  电视是否在重启后仍保持本机 5555 adbd 可用。
3.  0.7 新密钥第一次连接时的 RSA 授权行为（因密钥格式更换，预计会重新授权一次，尚待实机确认）。
4.  U 盘路径/Android 存储权限在具体电视 ROM 上是否能被完整扫描。
5.  所有 `disable-user` 包逐个实机兼容性。
6.  `com.mitv.tvhome` 的 `uninstall --user 0` 在具体机型上的恢复行为。
7.  所有被精简组件对 OTA、投屏、语音、商店等功能的实际影响。
8.  原 EXE 是否存在额外网络/隐私行为。

尤其第 8 点：

**目前没有把"没有隐私后台行为"作为已经完成的结论。**

如果以后继续做隐私/网络层核查，应单独检查：

-   EXE 网络连接；
-   APK 网络权限；
-   APK 第三方 SDK；
-   DNS/HTTP/HTTPS；
-   telemetry/analytics；
-   ADB 外连；
-   本机文件上传；
-   域名/IP；
-   运行时流量。

------------------------------------------------------------------------

## 16. 后续开发优先级

### 第一优先级

完成电视实机测试：

``` text
ADB
→ launcher
→ 1/2/3.apk
→ 预览
→ 精简
→ 恢复
```

### 第二优先级

完善 UI：

-   精简项逐项勾选
-   全选/取消
-   精简前确认
-   执行日志
-   恢复日志
-   当前禁用状态
-   明确显示硬保护项

### 第三优先级

增强安全：

-   执行前再次读取实际包是否存在
-   每个命令执行后验证状态
-   `com.mitv.tvhome` 处理后立即验证 HOME
-   失败自动停止
-   关键包双重保护
-   恢复测试

### 第四优先级

正式版：

-   release 签名
-   GitHub Secrets 保存 keystore
-   GitHub Actions 自动生成 release APK
-   SHA-256
-   版本号
-   更新日志

------------------------------------------------------------------------

## 17. 后续接续规则

新聊天开始时，把：

``` text
1. 本文件：CONTINUATION.md
2. 最终工程：MiTVOptimizer_Final_Handoff.zip
```

一起上传。

然后告诉 ChatGPT：

> "这是 MiTV Optimizer
> 的累计接续文档和最终工程，请严格按文档继续，不要重新猜测之前已经核实过的内容。"

接续时优先读取：

``` text
CONTINUATION.md
↓
README.md
↓
REVERSE_NOTES.txt
↓
commands.json
↓
MainActivity.java
LauncherGuard.java
OptimizerPlan.java
LocalAdb.kt
↓
GitHub Actions
```

不要重新用网上的 Xiaomi 精简清单替代 `commands.json`。

------------------------------------------------------------------------

## 18. 最重要的设计原则

### 原 EXE ≠ 新 APK

新 APK 的目标不是机械复制原 EXE，而是：

> **以原 EXE 实际捕获的行为作为来源，再采用更安全、可恢复的执行策略。**

核心区别：

``` text
原 EXE:
大量 pm uninstall --user 0

新 APK:
普通项目 → pm disable-user --user 0
原生桌面 → 满足第三方桌面硬条件后才 pm uninstall --user 0
SystemUI → 永久保护
```

### 永远不要再次默认处理

``` text
com.xiaomi.mitv.systemui
```

这是当前项目最重要的经验之一，因为原 EXE
实际卸载它与用户出现"音量正常但音量图标/OSD 消失"的现象高度吻合。

### 永远不要在没有第三方桌面的情况下移除

``` text
com.mitv.tvhome
```

必须：

``` text
launcher.apk
→ 安装
→ HOME
→ 启动
→ 二次验证
```

全部成功。

------------------------------------------------------------------------

## 19. 当前结论

截至本版本：

``` text
逆向来源：已建立
原 EXE 实际命令：已捕获并保存
危险 SystemUI：已识别
新 APK 安全策略：已建立
U 盘初装规则：已确定
launcher.apk：固定
1/2/3.apk：固定可选
其它 APK：忽略
本机 ADB：0.7 已更换为 legacy shell + sync v1 兼容实现
恢复功能：已接入代码
GitHub Actions：已配置
0.6 CI：已成功
0.7 CI：尚待重新编译
```

**当前真正剩下的核心工作是 0.7 编译与 MiTV4A 复测，而不是继续猜原 EXE。**


------------------------------------------------------------------------

## 20. 2026-09-24 第一轮 MiTV4A 实机结果与 0.7 修正

这是已经发生的实机结果，后续不要再把它当成“未验证猜测”。

### 20.1 实机与 ADB 失败信息

电视画面明确返回：

``` text
Failed to parse features from connection string:
device::ro.product.name=matrix;ro.product.model=MiTV4A;ro.product.device=matrix;
```

以及应用自己的错误页：

``` text
本机ADB连接失败：
IOException: Failed to parse features from connection string:
device::ro.product.name=matrix;ro.product.model=MiTV4A;ro.product.device=matrix;
```

这说明：

1. `127.0.0.1:5555` 确实已经收到了电视 adbd 的 CNXN 信息；不是单纯“5555 没开”。
2. 该 MiTV4A / `matrix` 固件返回的 CNXN 字符串**没有 `features=` 字段**。
3. 0.6 使用的：

``` text
dev.mobile:dadb:1.2.10
```

会因为缺少 `features=` 直接抛异常，因此无法进入 shell。
4. 用户已经在电视上开启 ADB；不能再让用户反复开关 ADB 来解释这个错误。

### 20.2 0.7 ADB 修改

0.7 已移除：

``` text
dev.mobile:dadb:1.2.10
```

改为：

``` text
com.tananaev:adblib:1.3
```

连接仍然是：

``` text
127.0.0.1:5555
```

但协议路径改为兼容旧 adbd 的：

``` text
CNXN: 不解析对端 features 字符串
shell: 传统 legacy shell 服务
sync:  v1 SEND / DATA / DONE 上传 APK
```

APK 不通过 shell 的 PTY 直接传二进制，而是使用 ADB `sync:` v1 上传到：

``` text
/data/local/tmp/mitv_<随机>.apk
```

然后：

``` text
pm install -r <临时APK>
rm -f <临时APK>
```

原因：老式 `shell:` 可能使用 PTY，不适合直接通过 `cat` 传二进制 APK；`sync:` v1 才是正确的旧 ADB 文件传输路径。

新的 RSA 密钥单独保存在：

``` text
filesDir/adbkeys_legacy/
```

不要复用 0.6 dadb 的磁盘密钥文件，因为两套库的密钥文件编码不同。因此 0.7 第一次测试 ADB 时可能再次出现一次 RSA 授权弹窗。

### 20.3 UI 实机问题已确认

用户实机反馈：

- 主界面内容超过一屏；
- 遥控器需要按多次“向下”才滚动到下面按钮；
- 当前焦点颜色不明显；
- 希望一屏显示全部操作，详细内容点按钮后弹窗查看。

0.7 已把主界面从整个纵向 `ScrollView` 改为固定一屏 2×4 按钮：

``` text
检测安装文件       | 测试本机 ADB
选择精简项目       | 执行前完整预览
应用 / 系统核查    | 查看当前已禁用应用
开始安全精简       | 一键恢复本工具改动
```

并且：

- 不再在主界面铺长日志；
- 详细信息全部用弹窗；
- 遥控器焦点使用明显橙黄色；
- 上下左右焦点顺序显式指定；
- 主页面只保留两行状态摘要。

### 20.4 精简按钮门禁加强

0.6 的问题：只要 `launcher.apk` 文件前置检查通过，⑤就可能被启用；ADB 测试失败并不会阻止用户点击⑤，只会在正式流程内部再中止。

0.7 改为：

``` text
launcher.apk 文件检查通过
+
本次启动 ADB 测试成功
=
⑤开始安全精简才启用
```

正式执行时还会再次重新检查：

``` text
launcher.apk
ADB ping
```

任一变化就立即中止。

### 20.5 “有品”图标核查

第一轮实机画面中应用列表出现“有品”。

当前不能直接把它当成新精简目标，也不能仅凭图标猜包名。

已经确认：0.6 当次 ADB 在进入安装/精简前就因为 dadb `features` 解析异常中止，因此这次失败流程没有证据表明是 Optimizer 在 ADB 安装阶段把“有品”装进去。

0.7 增加：

``` text
应用 / 系统核查
```

它使用 Android `PackageManager` 本机读取：

- 原始动作里已有的 `com.xiaomi.mitv.shop`；
- 应用名含“有品”；
- Xiaomi/MiTV 应用名含“商城”；
- 包名含 `youpin`；
- Xiaomi/MiTV 包名含 `shop`；
- 应用名、包名、系统/普通应用、启用状态；
- installer package；
- 首次安装时间和最近更新时间；
- 当前 U 盘 `launcher.apk / 1.apk / 2.apk / 3.apk` 各自实际解析出的包名；
- 0.7 以后本工具成功安装/更新的包记录。

这个核查只用于识别，**不会自动把额外 shop/youpin 包加入精简清单**。

### 20.6 重启行为

用户确认希望：

- 精简结束后不要突然强制重启；
- 结果窗口由用户选择是否立即重启。

0.7 已实现完成弹窗：

``` text
立即重启 | 稍后重启
```

只有用户点击“立即重启”才通过 ADB 发送：

``` text
reboot
```

### 20.7 0.7 下一步测试顺序

0.7 编译成功后，严格按以下顺序复测：

``` text
A. 安装并打开 0.7，确认一屏 2×4 UI 和橙黄色焦点
B. 检测 launcher.apk / 1~3.apk
C. 点击“应用 / 系统核查”，记录“有品”真实包名和安装信息
D. 点击“测试本机 ADB”
   - 如弹 RSA，允许
   - 目标必须看到 MITV_LOCAL_ADB_OK
E. 再点一次“测试本机 ADB”
   - 必须仍然成功，不能复现 features 解析异常
F. 完整预览，确认 com.xiaomi.mitv.systemui 不出现
G. 仅在以上全部成功后进行正式精简
H. 完成后先选择“稍后重启”，检查桌面/音量OSD/设置等
I. 再验证“立即重启”按钮
J. 最后测试一键恢复
```

0.7 尚未经过 GitHub Actions 和电视复测，所以不要把“新 ADB 已修好”写成实机已成功；当前准确状态是：**根因已通过实机错误定位，兼容实现已完成源码修改，等待编译与复测。**

### 20.8 本轮升级安装注意事项（GitHub debug 签名）

当前 GitHub Actions 仍构建默认 `debug` APK。不同全新 CI runner 生成的 debug 签名可能不一致，因此从 0.6 安装 0.7 时，如果电视提示“应用未安装 / 签名不一致”，本轮可以先卸载旧的 Optimizer 再装 0.7。

本次第一轮 0.6 实机流程在 ADB 握手阶段已经中止，没有成功执行精简，所以**仅就这一次 0.6 → 0.7 测试**，卸载旧 Optimizer 不会丢失已经执行的系统恢复记录。

但以后 0.7 一旦成功执行过精简，`SharedPreferences` 会保存“本工具禁用过哪些包 / 是否处理过原生桌面”等恢复状态；后续版本不要随意卸载应用。正式长期迭代应改为稳定签名或先导出/迁移恢复状态。


------------------------------------------------------------------------

## 21. 2026-09-24 v0.8 源码修改记录

当前源码版本：

``` text
0.8-dualmode-simpleui
versionCode 8
```

已完成源码修改：

1. `activity_main.xml` 从 2×4 八按钮改为居中 2×2 四按钮，固定按钮文案：`检测桌面 / 测试连接 / 精简设置 / 开始精简`。
2. 主面板使用更大左右留白、固定较小按钮高度，不再按权重撑满整屏。
3. `MainActivity.java` 删除独立预览、系统核查、禁用列表、独立恢复等主页入口。
4. `LauncherGuard.java` 删除 `1.apk / 2.apk / 3.apk` 读取 API；0.8 自动安装只剩 `launcher.apk`。
5. “精简设置”打开时自动通过 ADB 扫描目标状态，使用 `pm list packages` / `-d` / `-u` 区分待处理、禁用、卸载、本机不存在。
6. “恢复禁用”合并进“精简设置”，支持对安全禁用目标进行多选恢复；本工具记录项默认勾选，未记录禁用项默认不勾选。
7. “开始精简”第一层弹窗选择 `安全禁用` 或 `原版卸载`，第二层弹窗给完整执行预览。
8. 安全模式普通目标和小米原生桌面均使用 `pm disable-user --user 0`。
9. 原版模式普通目标和小米原生桌面使用 `pm uninstall --user 0`，但硬保护名单仍然有效。
10. 正式执行结束后重新扫描包状态，将本次执行但仍未达到预期状态的项目列为残留。
11. 完成后仍由用户选择 `立即重启 / 稍后重启`，绝不无提示强制重启。
12. AlertDialog 统一限制宽度；长内容/长列表限制高度并在弹窗内部滚动，避免电视上全屏铺满。
13. 保留 0.7 的 `home_removed` 旧状态兼容恢复，仅用于旧版本迁移；0.8 新原版卸载模式不会把普通卸载项目伪装成可一键恢复。

### 21.1 仍未验证的事项

0.8 目前是源码完成状态，**尚未经过 GitHub Actions 完整 Android 构建，也尚未经过 MiTV4A 实机复测**。

不能提前宣称：

- 四按钮布局在目标电视上的实际大小已经完美；
- `pm list packages -u --user 0` 在该 MiTV4A 固件上的输出完全符合预期（代码已带旧格式回退）；
- 两种模式的自动状态分类已经实机验证；
- 原版卸载后一定能达到“只剩电视管家 + 小米电视设置”的视觉结果；
- 所有残留图标对应包都已经定位。

下一轮应以实机结果为准继续，不要重新猜测原 EXE 已核实部分。
