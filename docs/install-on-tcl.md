# 在 TCL / 雷鸟电视上安装

适用于 **TCL 和雷鸟（FFALCON）的新系统**（Android 12 / 13 / 14 一代，含 T7L Ultra 等机型，固件 170 以上验证过）。这两家用的是同一套系统底子，下面的方法通用，不是只对某一个型号有效。

整个过程分两段：**先连上 ADB**，再**清掉三道厂商限制**。两段都卡人，尤其是第一段 —— 新系统默认没有开放的网络 ADB 端口。

---

## 第一段：连上 ADB

新系统不能直接 `adb connect 电视IP:5555`，端口没开。要先用 USB 有线连一次，把网络 ADB 打开。

### 1. 用双 Type-A 公头线连电脑

一条**两头都是 Type-A 公头**的数据线（不是常见的 A-to-C 或 A-to-B）。

- **电脑侧插 USB 3.0 口**
- **电视侧插 USB 2.0 口**

插口别搞反，反过来多半识别不到。电视上先在「设置 → 关于本机」里连按版本号打开开发者选项，再开启 USB 调试。

接好后在电脑上：

```bash
adb devices
```

能看到设备就对了。

### 2. 打开网络 ADB

```bash
adb tcpip 5555
```

### 3. 用手机触发授权弹窗

这一步是关键，也是最容易忽略的：**电视上的 ADB 授权弹窗，得由一次网络连接请求触发。**

手机上装一个 ADB 客户端（例如「甲壳虫 ADB」），连：

```
电视IP:5555
```

这时电视屏幕上会弹出授权对话框，用遥控器选**允许**（勾上「一律允许」）。授权完就可以退出手机上的 ADB 客户端了，后面用电脑连。

### 4. 断电重启之后 5555 会失效

关机、**拔掉电源**、重新开机之后，`电视IP:5555` 就连不上了 —— `adb tcpip` 打开的端口不持久。

不用重新走一遍 USB。电视自己有一个常驻的 ADB 端口，位置藏在：

```
全部设置 → 关于本机 → 本机信息 → 在这个页面快速连按遥控器按键
```

连按几下之后页面上会多出隐藏信息，其中就有 **ADB 端口号**。用那个端口连：

```bash
adb connect 电视IP:<那个端口>
```

如果端口显示为 `0` 或者一串错误值，把系统设置里的 ADB 开关关掉再打开，重新看一次就正常了。

> 之前授权过的 RSA 指纹是保存下来的，所以换端口重连不需要再授权一次。

---

## 第二段：三道厂商限制

连上 ADB 之后还有三关，报错互相掩盖，逐个试开关会浪费很多时间。三个都是 TCL 在 AOSP 上加的定制管控，不是标准 Android 行为。

### 坑 1：装不上，报 `INSTALL_FAILED_VERIFICATION_FAILURE`

```
adb: failed to install Remouse.apk:
  Failure [INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for ...]
```

关掉所有标准的校验开关都没用：

```bash
# 这些全都不管用
adb shell settings put global package_verifier_enable 0
adb shell settings put global verifier_verify_adb_installs 0
adb shell settings put secure install_non_market_apps 1
```

因为拦截来自系统安装器 `com.android.packageinstaller` 里的一条定制校验链（`verifier.VerifierReciver` → `StrategyService`），不受这些开关控制。

**解法**：装的时候临时停用系统安装器，装完立刻恢复。

```bash
adb shell pm disable-user com.android.packageinstaller
adb install -r Remouse.apk
adb shell pm enable com.android.packageinstaller
```

**装完一定要 enable 回来**，否则电视上从 U 盘、文件管理器装应用会全部失效。

### 坑 2：无障碍开关写不进去

```bash
adb shell settings put secure enabled_accessibility_services com.remouse.tv/com.remouse.tv.CursorAccessibilityService
adb shell settings get secure enabled_accessibility_services
# 回读是 null —— 连系统原有的无障碍服务都被一起清空了
```

这是 Android 13+ 的**受限设置**在拦旁加载安装的应用，而且它不是把你的服务剔掉，是把整个值清空。

**解法**：先解除该应用的受限设置。

```bash
adb shell appops set com.remouse.tv ACCESS_RESTRICTED_SETTINGS allow
```

### 坑 3：写进去了，服务却起不来

```
W ActivityManager: [TclAppBoot]:forbid bind service ComponentInfo{com.remouse.tv/...CursorAccessibilityService}
I TclAppBoot: ... reason = 'callee_doesn't_have_OP_AUTO_START_permission'
```

TCL 的自启动管控拦住了服务绑定。

**解法**：

```bash
adb shell appops set com.remouse.tv AUTO_START allow
```

授完之后把无障碍开关**重写一遍**触发重新绑定。

---

## 完整命令

```bash
adb connect <电视IP>:<端口>

# 装
adb shell pm disable-user com.android.packageinstaller
adb install -r Remouse.apk
adb shell pm enable com.android.packageinstaller

# 授权
adb shell appops set com.remouse.tv ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set com.remouse.tv AUTO_START allow

# 启用无障碍服务（保留系统自带的那个，用冒号拼接）
adb shell settings put secure enabled_accessibility_services \
  com.tcl.walleve/com.tcl.walleve.platform.accessibility.AiAccessibilityService:com.remouse.tv/com.remouse.tv.CursorAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 确认
adb shell dumpsys accessibility | grep -i "Enabled services"
adb logcat -s Remouse:V
```

看到这两行就成了：

```
I Remouse: Remouse 无障碍服务已连接
I Remouse: 光标浮层创建成功
```

---

## 两个注意事项

**别去掉系统自带的无障碍服务。** TCL 自己的 `com.tcl.walleve/...AiAccessibilityService` 是禁不掉的 —— 从 `enabled_accessibility_services` 里删掉它，系统几秒后会自动加回来。改这个值时用冒号把它一起拼上。

**每次重装都要重新授权。** 重新安装会重置 `ACCESS_RESTRICTED_SETTINGS` 和 `AUTO_START` 两个 appop，升级后必须重新执行那两条 `appops set`，否则服务起不来。
