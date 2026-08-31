# 版本记录

> v3.0 之前项目叫 AirMouse，包名 `com.airmouse.tv`。开源时改名为 Remouse，包名 `com.remouse.tv`。
> 下面的历史条目保留当时的名字。测试机型为 TCL Smart TV Pro（MT9655，Android 14）。

## v3.0 (versionCode 30) — 2026-08-31 【开源改名】

- 项目改名 **Remouse**，包名 `com.airmouse.tv` → `com.remouse.tv`，日志 TAG、SharedPreferences 名同步更换。
- 构建配置改成：根目录有 `platform.keystore` 才用平台签名，没有则退回默认 debug 签名 —— 克隆下来直接能编译，仓库里不含任何密钥。
- 补 README（中英）、MIT LICENSE、`docs/install-on-tcl.md`（TCL / 雷鸟机型的 ADB 连接方法与三道厂商限制）。
- 功能与 v2.5 完全一致，只改了标识。

## v2.5 (versionCode 25) — 2026-08-30 【光标大小生效 + 边缘翻页】

### 1. 光标大小滑块一直是失效的
`createCursorOverlay()` 建窗口时按设置给了一次宽高，之后 `updateCursorOverlayPosition()`
只改 `params.x/y`，从没把 `params.width/height` 写回去 —— 所以滑块拖到哪，光标都是首次创建时那么大。
现在每次更新位置都带上尺寸；另外设置界面拖完滑块会调 `refreshCursorSizeFromUi()`
立刻刷新，不用等光标移动（光标静止时根本不走那个方法）。

### 2. 光标顶到屏幕边缘自动翻页
光标推到屏幕边界还在继续往外推时，翻底下容器的一页。
- 要**顶住 300ms** 才开始翻，之后每 400ms 一页。加这个延迟是因为够角落里的按钮时光标本来就会贴边，
  没有延迟的话页面会自己滚走。
- 松开方向键或离开边缘立刻清零。
- 找可滚动容器：先用光标坐标找，找不到再退回屏幕中心找（光标贴边时很可能已经在容器外面）。
- 上/左边缘 → `ACTION_SCROLL_BACKWARD`，下/右边缘 → `ACTION_SCROLL_FORWARD`，跑在节点线程上。
- 滚动模式不受影响，那边方向键本来就是翻页。

## v2.4 (versionCode 24) — 2026-08-30 【光标改版 · 手感重做】

### 1. 光标换成中性指针（方案 A）
照 iPadOS 指针的路子：**中性色、不发光、静止不动**。
- 深色半透明填充 `#0A0B0D 46%` + 白描边 `1.5dp #FFF 86%` + 投影（黑 42%，模糊 10dp，Y 偏移 2dp）。
  一暗一明两层，压在亮背景还是暗背景上都有一条边能看见。
- **删掉呼吸动画**（原来每 1.5s 缩放一轮、持续重绘）和青色发光渐变。静止的东西不该一直动。
- 滚动模式不再换成橙色，改用圆内的上下箭头区分 —— 形状比颜色安静。
- 点击涟漪改成中性白，320ms。
- 高亮框同步换成中性：白描边 92% + 白填充 13% + 外侧一圈 0.5dp 暗边（保证亮背景上也分得清）。

### 2. 移动手感重做 —— “卡卡的”的真正原因
v2.3 是「按下走 12px → 静止 180ms → 突然切到连续移动」，那个停顿就是卡感。
现在：**按下立刻以 `速度×0.2`（默认 2px/帧）起步，900ms 内按二次曲线平滑加速到 `速度×4`（默认 40px/帧）**。
全程没有任何停顿，短按天然只走十来像素（微调精度照旧），按住才越走越快。
`nudge()` / `TAP_HOLD_MS` / `continuousStarted` 这套逻辑整个删掉。

### 3. 节点查找搬到后台线程
卡顿的另一半。原来每 120ms 在主线程 `getWindows()` + 遍历节点树，都是跨进程调用，直接卡在光标移动的帧上。
现在起一个 `HandlerThread("AirMouseNode")`，悬停高亮、点击、滚动的节点操作全在上面跑，
结果 post 回主线程更新 UI。点击的视觉和振动反馈仍在主线程立即给，不等跨进程调用回来。

### 4. 空闲自动隐藏
3 秒没按键 → 300ms 淡出；按任意键 → 120ms 淡回。看片时按个音量键不会留个圆点杵在画面中间。
移动过程中强制不淡出 —— 实测这台遥控器长按方向键**不发按键重复事件**，
只靠 onKeyEvent 计时的话按住不放几秒后光标会自己消失。

### 5. 设置里加三个开关
排在「惯性加速 / 振动反馈」下面一行，样式一致，默认全开：
- **就近吸附** — 关掉后光标必须精确压在按钮上
- **空闲隐藏** — 关掉后光标常驻
- **高亮框** — 关掉后不画框，也不再做后台节点查找，最省资源

### 自检时修掉的两个问题
- 关掉吸附时搜索半径给的是 0，`Rect.intersects` 对零面积矩形永远返回 false，会一个节点都找不到 → 改成 1px。
- 大段替换时误删了 `dispatchScrollGesture` / `toggleMode` / `adjustSpeed` / `createCursorOverlay`，编译报错后补回。

## v2.3 (versionCode 23) — 2026-08-30 【小按钮点得准】

### 解决的问题
按钮很小时，连续移动的光标怎么都停不到按钮上。

### 改动
1. **就近吸附**：找目标节点的逻辑从「谁包含光标点」改成
   「先找把光标圈住的可点节点（多个取面积最小的，即最具体的那个），
   没有就在吸附半径内找最近的一个」。半径取屏幕宽度 5%（1080p 约 96px，下限 48px）。
   光标停在按钮旁边也能点中，高亮框会先把吸附到的目标框出来，所以点前就能确认。
   - 实现上换成「收集候选」而不是深度优先命中：以光标为中心取一个 2R 的搜索方框，
     父节点 bounds 与方框不相交就整棵子树剪掉，遍历量很小。
   - 顺带修掉一个旧问题：原来深度优先返回的是第一个命中的可点节点，
     嵌套结构里可能拿到外层大容器；现在按面积取最具体的那个。
2. **短按走一小步**：方向键按下先走一小步（默认 `光标速度 × 1.2`，约 12px），
   按住超过 180ms 才转入原来的连续移动 + 惯性加速 —— 和键盘首次重复延迟一个道理。
   连按几下就能一格格挪到位，不会一按就窜出去。
   滚动模式下短按则是滚一页（`scrollOnce`，优先节点滚动，失败回退滑动手势）。

## v2.2 (versionCode 22) — 2026-08-30 【暂停开关】

### 解决的问题
服务一启用就吃掉所有方向键，遇到不吃 `ACTION_CLICK`、光标又点不动的界面时进退两难。

### 改动
1. **长按返回键 800ms → 暂停 / 恢复**。暂停后除返回键外所有按键原样交还系统，
   遥控器立刻恢复原本的焦点导航；光标和高亮框隐藏；再长按一次恢复。
   - 短按返回仍是正常返回：长按判定必须先把 DOWN 拦下来，所以短按时由
     `performGlobalAction(GLOBAL_ACTION_BACK)` 补发。返回键是暂停状态下唯一仍被接管的键。
   - 长按的重复 DOWN 事件全部消费掉，不会连退多级。
2. 暂停状态存 SharedPreferences，**跨重启保留**；不随应用切换变化（明确不做按应用自动记忆）。
3. 切换时 Toast + 一次 120ms 振动反馈；切换瞬间清掉方向键按住状态，避免恢复后光标自己漂。
4. 设置界面：暂停中状态显示「⏸ 已暂停」，主按钮变成「恢复 AirMouse」，
   万一忘了长按返回键也能从 App 里恢复（`CursorAccessibilityService.togglePausedFromUi()`）。
5. 暂停时不再遍历节点树响应界面变化事件，省性能。

## v2.1 (versionCode 21) — 2026-08-30 【点击终于能"进得去"】

### 解决的问题
光标能移动，但按 OK 键点不进任何应用。日志里全是 `点击被取消`。

### 根因
电视 App（TCL 桌面、各类 TV 应用）是**焦点导航**界面，不处理触摸事件。
v1.5 的点击走 `dispatchGesture()` 注入触摸手势，实测：
- 手势注入到 TCL 桌面后只会让焦点复位到第一个卡片，不会打开应用；
- 且系统里 TCL 自己的 `AiAccessibilityService` 常驻（禁不掉，会被系统自动加回），
  两个服务的手势注入互相取消，回调持续走 `onCancelled`。

### 改动
1. **点击改走无障碍节点**：`performClick()` 先用 `getWindows()` 按窗口层级从高到低，
   在光标坐标下深度优先找可点击节点，命中后 `ACTION_FOCUS` + `ACTION_CLICK`；
   节点本身不可点时向上找可点祖先（列表项常见结构）；全找不到才回退 `dispatchGesture`。
2. **滚动同样改走节点**：滚动模式下先找 `isScrollable()` 的祖先执行
   `ACTION_SCROLL_FORWARD/BACKWARD`，一次一页，帧间隔临时拉到 350ms；失败才回退滑动手势。
3. **新增悬停高亮**（`HighlightOverlayView`）：全屏 `TYPE_ACCESSIBILITY_OVERLAY`，
   在光标下的可点元素外框画圆角描边 + 淡填充，元素之间切换有 140ms 平滑过渡，
   颜色跟随模式（光标青 / 滚动橙）。按 OK 之前就能看到会点到哪个元素。
   高亮浮层先于光标浮层创建，保证光标压在高亮框上面。
4. 清掉了历史遗留的无关代码和 BLE / 蓝牙 / 录音等用不到的权限。

### 装机踩的坑（TCL Smart TV Pro，MT9655，Android 14）
- **无障碍开关写不进去**：`settings put secure enabled_accessibility_services` 表面成功、
  回读是 `null`，连 TCL 自己原有的服务也被一起清空。原因是 Android 13+ 的**受限设置**
  拦截旁加载应用。解法：先 `appops set com.remouse.tv ACCESS_RESTRICTED_SETTINGS allow`。
- **adb 装不上**：`INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for ...`。
  `package_verifier_enable=0` / `verifier_verify_adb_installs=0` / `install_non_market_apps=1` 都不管用，
  因为拦截来自 TCL 定制安装器 `com.android.packageinstaller`（`verifier.VerifierReciver` → `StrategyService` 策略链）。
  解法：`pm disable-user com.android.packageinstaller` → 安装 → `pm enable com.android.packageinstaller`。
- **装完服务起不来**：TCL 的自启动管控 `TclAppBoot` 会 `forbid bind service`，
  日志写 `callee_doesn't_have_OP_AUTO_START_permission`。解法：`appops set com.remouse.tv AUTO_START allow`，
  然后把无障碍开关重写一遍触发重新绑定。
- 重装会重置 `ACCESS_RESTRICTED_SETTINGS` 和 `AUTO_START`，每次升级后都要重新授一遍。

### 一键装机命令
```bash
adb connect <电视IP>:5555
adb shell pm disable-user com.android.packageinstaller
adb install -r Remouse.apk
adb shell pm enable com.android.packageinstaller
adb shell appops set com.remouse.tv ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set com.remouse.tv AUTO_START allow
adb shell settings put secure enabled_accessibility_services \
  com.tcl.walleve/com.tcl.walleve.platform.accessibility.AiAccessibilityService:com.remouse.tv/com.remouse.tv.CursorAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

---

## v1.5 (versionCode 5) — 2026-06-18 【修复版 · 小米平板实测可用】
修复「遥控器方向键完全无法控制光标」。
- 根因：`accessibility_service_config.xml` 只声明了运行时 flag `flagRequestFilterKeyEvents`，
  漏了静态属性 `android:canRequestFilterKeyEvents="true"`，导致 `onKeyEvent` 永远不被回调。
- 验证：`dumpsys accessibility` 的 capabilities 由 `33` → `41`。

## v1.4 (versionCode 4) — 2026-06-18 【诊断版】
在 `onKeyEvent` 入口加 keycode 诊断日志（`adb logcat -s AirMouse:I`），保留至今。

## v1.3 (versionCode 3) — 2026-06-18
首个完整打包版本。方向键 8 向移动光标（1.5s 加速至 4 倍），OK 短按点击，
长按 OK 切换光标/滚动模式，音量键调速；光标用 `TYPE_ACCESSIBILITY_OVERLAY` 绘制，免悬浮窗权限。
