# MiTV Optimizer 0.8 — 双模式精简 + 四按钮简化 UI

本项目依据已经实际捕获并核实的原 Windows EXE ADB 动作制作，运行在小米电视本机，不需要电脑端 CMD。

0.8 在 0.7 的老款 MiTV4A ADB 兼容实现基础上继续收敛交互：主界面只保留 4 个按钮，自动安装文件只认 `launcher.apk`，并增加“安全禁用 / 原版卸载”两种精简模式。

## 主界面

主界面固定一屏 2×2：

```text
检测桌面    | 测试连接
精简设置    | 开始精简
```

四个按钮全部为 4 个汉字，遥控器焦点仍使用明显橙黄色。整个操作区不再撑满电视画面，而是居中并保留较大边距。

主页面只保留两行状态摘要：

```text
桌面状态    ADB状态    已选数量
精简模式在“开始精简”时选择
```

详细内容全部使用非全屏弹窗。短内容弹窗自动收紧；长列表/长日志限制在约 70% 屏幕高度，内容区域滚动，不再铺满整个电视画面。

## U 盘文件规则

0.8 **只自动识别并安装一个文件**：

```text
任意目录/
├─ optimizer.apk
└─ launcher.apk
```

要求：

- `launcher.apk` 必须存在且能解析为有效 APK。
- 若扫描到多个不同目录的 `launcher.apk`，禁止继续精简，要求只保留一个。
- `1.apk / 2.apk / 3.apk` 自动安装逻辑已经从 0.8 删除。
- 其它第三方 APK 一律忽略；需要时由用户自行从 U 盘安装。

Android 通常不会可靠提供“optimizer.apk 当初从哪个 U 盘目录安装”的信息，因此程序仍会扫描可读共享/外接存储，精确寻找文件名 `launcher.apk`，并以其父目录作为桌面 APK 所在目录。

## 精简设置：先扫描，再显示

点击“精简设置”后，程序会先通过本机 ADB 自动扫描当前 user 0 的应用状态，然后再显示目标清单。

每个已核实目标会被标记为：

```text
[待精简]
[已禁用]
[已卸载]
[本机无此项]
```

“本机无此项”的包不会占用列表空间。整体状态按当前实际目标统计为：

- 未精简：已处理比例 ≤ 20%
- 部分精简：20%～80%
- 已精简：已处理比例 ≥ 80%

这里的“已处理”包括当前已禁用以及已从 user 0 卸载的已知包。程序优先使用 `pm list packages --user 0`、`-d`、`-u` 区分当前安装、禁用和已卸载状态；老固件不支持 `--user 0` 时自动回退到旧命令格式。

若检测到安全禁用项目，精简设置弹窗会出现“恢复禁用”按钮。由本工具记录的禁用项默认勾选；检测到但没有本工具记录的禁用项会明确标成“已禁用/未记录”并默认不勾选，用户可自行选择。恢复时执行：

```text
pm enable --user 0 <package>
```

原版卸载模式产生的普通卸载项不承诺在工具内恢复。

## 两种精简模式

点击“开始精简”后才选择模式，不在主界面额外增加按钮。

### 1. 安全禁用（可恢复）

普通已核实目标执行：

```text
pm disable-user --user 0 <package>
```

第三方 HOME 验证通过后，小米原生桌面也使用：

```text
pm disable-user --user 0 com.mitv.tvhome
```

这些项目可以从“精简设置 → 恢复禁用”重新启用。

### 2. 原版卸载（恢复出厂设置兜底）

普通已核实目标使用原 EXE 的主要处理方式：

```text
pm uninstall --user 0 <package>
```

第三方 HOME 验证通过后，小米原生桌面最后执行：

```text
pm uninstall --user 0 com.mitv.tvhome
```

这个模式更接近最初 Windows EXE 的视觉精简结果，但**仍保留新工具已经确认的硬保护规则**，不会机械复制原 EXE 的危险处理。工具不保证对该模式提供一键恢复；出现异常时以电视恢复出厂设置作为兜底。

## 永久硬保护

无论使用哪种模式，下列关键包都不会进入普通精简清单：

- `com.xiaomi.mitv.systemui` — 已实机确认与音量 OSD 等 SystemUI 浮层有关。
- `com.droidlogic`
- `com.android.vpndialogs`
- `com.android.proxyhandler`
- `com.android.statementservice`
- `com.xiaomi.account.auth`

`com.mitv.tvhome` 仍属于条件处理项目：只有 `launcher.apk` 安装成功、注册 HOME、实际启动成功、二次 HOME 验证成功后，才会最后处理。

## 正式执行顺序

1. 再次确认唯一、有效的 `launcher.apk`。
2. 再次验证本机 ADB `127.0.0.1:5555`。
3. 安装 `launcher.apk`。
4. 确认其注册为 HOME。
5. 实际启动第三方 HOME。
6. 二次验证第三方 HOME。
7. 扫描当前目标包状态，跳过本机不存在/已经达到目标状态的包。
8. 根据所选模式，对普通目标执行 `disable-user` 或 `uninstall --user 0`。
9. 最后按所选模式处理 `com.mitv.tvhome`。
10. 再次启动第三方桌面。
11. **重新扫描目标状态做结果复核**，把仍未达到预期状态的包明确列在结果窗口里。
12. 弹出完成结果，由用户选择“立即重启”或“稍后重启”。

0.8 不会因为 shell 命令返回了文字“Success”就直接认定全部完成；正式流程结束后会重新读取包状态，降低“显示成功但桌面仍残留”的误判。

## ADB 兼容实现

MiTV4A 实机返回过不含 `features=` 的旧/定制 adbd CNXN 信息。0.7 起已经从 `dadb` 切换为：

```text
com.tananaev:adblib:1.3
127.0.0.1:5555
legacy shell:
sync: v1 SEND/DATA/DONE
```

0.8 保留这套兼容实现不变。APK 通过 ADB `sync:` v1 上传到 `/data/local/tmp`，再执行 `pm install -r`。

RSA 密钥保存在应用私有目录：

```text
filesDir/adbkeys_legacy/
```

## 重启行为

精简完成后**不会强制自动重启**。结果弹窗提供：

```text
立即重启 | 稍后重启
```

只有用户主动选择“立即重启”才发送 `reboot`。

## v0.7 恢复状态兼容

0.8 仍保留对 v0.7 `home_removed` 记录的兼容恢复入口，仅用于已经由旧版本记录的小米原生桌面恢复尝试。

0.8 新的“原版卸载模式”不会把普通卸载项目登记成“可一键恢复”。

## GitHub Actions 编译

工程继续使用 `.github/workflows/build-apk.yml`，本地无需 Android Studio / SDK / Gradle。

1. 把工程内容上传到 GitHub 仓库根目录。
2. 打开 **Actions → Build Android APK → Run workflow**。
3. 编译成功后下载 `MiTVOptimizer-debug` Artifact。
4. 解压得到 `MiTVOptimizer-debug.apk` 和 SHA-256 文件。

如果构建失败，工作流仍会上传 `MiTVOptimizer-build-log`。
