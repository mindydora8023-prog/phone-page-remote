# 手机翻页遥控 · Android

从 [GitHub Releases](https://github.com/mindydora8023-prog/phone-page-remote/releases) 下载安装包。本地构建的调试包输出到 `build/outputs/apk/debug/phone-page-remote-android-debug.apk`。应用包名为 `com.phonepageremote.android`，最低 Android 8.0（API 26），目标 Android 16（API 36）。手机需要支持 BLE 外围设备模式和广播。

## 设置

1. 打开「手机翻页遥控」。按页面提示授予附近设备和通知权限，再打开蓝牙。
2. 点「打开无障碍设置」，启用「手机翻页遥控」。如果系统对侧载应用显示「受限设置」，在该应用的系统信息页允许受限设置后，再返回开启无障碍服务。
3. 将页面显示的 32 位配对密钥填入 Windows 端。更换密钥后，需要在 Windows 端填入新密钥。
4. 点「启动蓝牙接收」。等接收端显示正在广播后，从 Windows 连接。实际滑动要求手机亮屏且已解锁。
5. 可从设置页或常驻通知停止接收。快速连按时，当前滑动结束前的新滑动会回报失败，随后可重新发送。

## 界面与设计令牌

手机端与 Windows 端共用同一套视觉语言。令牌集中在 `src/main/res/values/colors.xml` 与 `dimens.xml`，取值对应 `../windows/ui/style.css`：`#002FA7` 品牌蓝、`#15171B` 正文、`#555A65` 次要、`#D2D5DB` 发丝线、`#F7F7F8` 浅底。

页面由代码构建（`SetupActivity` 只做状态与接线，视图全部来自 `Design.java`），因为本构建链没有 Gradle，也就没有 AppCompat/Material 可依赖。为了让它客观上仍是同一个产品，结构固定为：满宽 6dp 品牌条 → display 级标题（34sp、字距 -0.03em、随宽度 autosize 到 20sp）＋右上角实时状态标签 → 用 1dp 发丝线分节，每节「18sp 标题 + 14sp 说明 + 一个满宽蓝色主按钮 + 右对齐的次级 chip」。配对密钥用等宽字（15sp、字距 +0.04em）放进描边卡片，对应 Windows 的键帽。

状态标签覆盖五种真实状态：已停止（安静）、待授权、启动中、启动失败、接收中（后三者转品牌蓝加粗），状态来源是 `ReceiverService.statusResource()`，不是界面自己猜的。

夜间模式由 `values-night/colors.xml` 提供同一套角色的深色值，并在 `values-night/styles.xml` 里翻转系统栏图标；宽屏（横屏、平板）下正文列居中且最大 648dp。控件用 `minHeight` 而非固定高度，系统字号放大到 200% 时按钮与小块会一起长高。

改尺寸改 `dimens.xml`（8dp 栅格：`gutter` 24、`section_gap` 26、`title_gap` 18、控件高 52），不要写死 px；字号一律用 sp，跟随系统字体缩放。

## 本地构建与核验

在本目录用 PowerShell 执行 `./build.ps1`。构建脚本接受 `-SdkRoot` 和 `-JdkHome`，也支持环境变量 `ANDROID_SDK_ROOT`、`ANDROID_HOME`、`JAVA_HOME`。本机使用 JDK 11.0.14，并使用 `../.toolchain/android/` 下从 Google 下载的 Android SDK Platform 36 rev 2 和 Build Tools 36.0.0。构建使用 AAPT2、javac、D8、zipalign 和 apksigner，调试签名密钥写到 `../.toolchain/android/page-remote-debug.p12`，供再次构建时复用。

构建会先运行 39 条 JVM 协议断言，再编译、签名并核验对齐与签名。断言覆盖独立 HMAC 测试向量、5 种命令、地址隔离、一次性挑战、失败后失效、到期、断连、停止和密钥更新。可用 `aapt2 dump badging` 以及 `aapt2 dump xmltree --file AndroidManifest.xml` 检查成品。

在具备 BLE 外围设备模式的测试机上，可从 Windows 连接并依次验证 ping、四个方向、滑动时快速按键、锁屏和解锁、错误密钥及更换密钥、从通知停止。收到 GATT 写成功表示系统已接受滑动请求，手势最终完成还取决于屏幕与系统输入状态。
