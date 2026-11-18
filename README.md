# 手机翻页器

用 Windows 键盘通过低功耗蓝牙控制安卓手机。支持小说横滑、短视频竖滑、反向手势、自定义上一页和下一页，以及可开关的全局快捷键。两端运行时无需网络。

## 安装与使用

从 [GitHub Releases](https://github.com/mindydora8023-prog/phone-page-remote/releases) 下载 Windows 程序和安卓接收端。在 Windows 解压后双击程序即可运行。

1. 在 Windows 10 2004 或更新版本、Windows 11 的 x64 电脑上打开 `PhonePageRemote.exe`。需要开启蓝牙并装有 Microsoft Edge WebView2 Runtime。本版 Windows 程序未做 Authenticode 签名。
2. 在支持 BLE 广播的安卓 8.0 或更新版本上安装 `PhonePageReceiver-Android.apk`。打开「手机翻页遥控」，按页面提示授权并开启该应用的无障碍服务，再点「启动蓝牙接收」。如果系统对侧载应用显示「受限设置」，可在应用信息中允许受限设置后返回无障碍界面。
3. 在 Windows 点「扫描手机」，选择列表中的接收端，然后二选一：

   - **免密配对**（推荐）：点「免密配对」，电脑会显示一个 6 位数字，手机同时弹出通知显示同一个数字。两边一致就点「允许」，电脑随即自动连接。之后每次连接都不再需要任何输入。
   - **手动输入**：把手机页面上的 32 位配对密钥填进「手机连接码」再点「连接」。输入一次后同样会被记住。

   手机端「更换密钥」或重新配对后，旧密钥自动失效并提示重新输入；也可点「忘记密钥」清掉。电脑保存的密钥用 Windows DPAPI 加密，只有当前 Windows 用户能解开。如果手机重启过蓝牙或接收服务，重新扫描会刷新蓝牙地址。
4. 打开手机上的小说或短视频，保持屏幕亮着且已解锁。Windows 端选择对应手势模式，按 `F9` 上一页、`F10` 下一页，也可点大按键试发。方向相反时选择该模式的反向项。
5. 点「改键」，按下新的按键或组合键。支持方向键、Page Up、Page Down、Home、End、F1–F24（F12 除外），以及由 Ctrl、Alt、Shift、Win 修饰的常用键，部分组合键可能由 Windows 或其他应用占用。要在 Windows 切到其他应用后继续工作，在连接手机后启用全局快捷键。若默认单键在后台没有反应，可试用实测通过的 `Ctrl+Shift+F7` 上一页和 `Ctrl+Shift+F8` 下一页。每次启动默认关闭全局快捷键。

手机端可从常驻通知停止接收。Windows 端会记住按键、手势模式和上一次通过验证的配对密钥（明文存在 `%APPDATA%\PhonePageRemote\settings.json`，只有当前 Windows 用户可读；随时可用页面上的「忘记密钥」清除）。连接码本身仍只在当次连接时参与 BLE 握手，不会被发送到任何地方。此版本的接收端面向安卓，iPhone 尚未适配。具体小说或短视频应用得能响应相应滑动。命令收到 GATT 确认时，界面会提示手机已接收指令，实际翻页仍由目标应用处理。

在 Windows 可右键 `PhonePageRemote.exe`，选择「显示更多选项 → 发送到 → 桌面快捷方式」。从源码目录也可运行 `./windows/install-shortcut.ps1 -ExePath 'C:\路径\PhonePageRemote.exe'`，它会在当前用户桌面创建「手机翻页器」，并取用程序内置图标。

## 源码与构建

- Windows 窗口采用 [MyGo 框架](https://mygo.egoist.dev/docs/getting-started)，BLE 调用使用 WinRT，手势映射由 [MyGO 语言](https://github.com/mygo-lang/mygo) 编写并生成 Go 代码。
- 在此目录使用 PowerShell 运行 `./windows/build.ps1`。需要 Go 1.27，脚本会获取固定版本的 MyGO 编译器、生成手势映射、运行 Windows 单元测试和 `go vet`，并在 `dist/` 下构建 EXE。
- 安卓构建使用 `./android/build.ps1`。平台依赖和验证命令见 [安卓端构建说明](android/README.md)。

## 界面

两端共用一套视觉语言：6px/dp 品牌蓝顶条、display 级标题配实时状态标签、1px 发丝线分节、每节一个蓝色主操作加右对齐的次级小块。颜色、字号与间距在两端各有唯一的定义处——Windows 是 `windows/ui/style.css` 顶部的 `:root` 令牌，安卓是 `res/values/colors.xml` 与 `dimens.xml`（夜间为 `values-night/colors.xml`）。改视觉请改令牌，不要在别处写死数值。

两端都跟随系统主题：Windows 走 `prefers-color-scheme`，安卓走 `values-night` 并同步系统栏图标与底色。字号使用相对单位（Windows 的 `clamp()`、安卓的 `sp` 加标题 autosize），系统字体放大到 200% 时标题、按钮与小块会一起变高，不会裁字。Windows 窗口在 510、720、1200 三档宽度下均无横向溢出；安卓在竖屏、横屏（居中的 648dp 阅读宽度）与 200% 字号下均验证通过。`prefers-reduced-motion` 下关闭所有过渡。

## 已验证

Windows x64 成品已在本机验证按键录入、手势模式切换、设置重启恢复、蓝牙扫描完成后的空列表状态及正常关闭。安卓 APK 通过协议测试、APK 对齐和 v2/v3 签名检查。

安卓 17 实机联调验证了 BLE 扫描发现、错误连接码拒绝、正确连接码配对以及四个方向的触摸事件。Windows 翻页器最小化、记事本处于前台时，`Ctrl+Shift+F7` 和 `Ctrl+Shift+F8` 都能触发手机滑动。默认单键 `F9` 和 `F10` 在翻页器前台验证通过，在此次后台测试中未触发。手势使用独立触摸测试页核验，未逐一测试小说和短视频应用。

