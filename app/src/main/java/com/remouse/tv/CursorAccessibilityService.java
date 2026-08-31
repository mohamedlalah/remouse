package com.remouse.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.List;

/**
 * 核心无障碍服务
 * 拦截遥控器按键事件，将方向键映射为光标移动/滚动，OK键映射为点击
 *
 * 点击优先走无障碍节点的 ACTION_CLICK（电视 App 大多是焦点导航、不响应注入的触摸手势），
 * 只有找不到可点节点时才回退到 dispatchGesture 注入触摸。
 *
 * 模式切换：长按OK键（>800ms）切换 光标模式 ↔ 滚动模式
 * 暂停/恢复：长按返回键（>800ms）。暂停后除返回键外所有按键原样交还系统，
 *            用于对付那些不吃 ACTION_CLICK、光标反而碍事的界面。
 * 光标模式：方向键移动光标，OK短按=点击
 * 滚动模式：方向键滚动页面，OK短按=点击
 */
public class CursorAccessibilityService extends AccessibilityService {

    private static final String TAG = "Remouse";

    private float cursorX;
    private float cursorY;
    private int screenWidth;
    private int screenHeight;

    private boolean movingUp = false;
    private boolean movingDown = false;
    private boolean movingLeft = false;
    private boolean movingRight = false;

    private long moveStartTime = 0;
    /** 起步速度是设定速度的两成，短按一下自然只走十来像素——微调精度就从这来的 */
    private static final float START_SPEED_RATIO = 0.2f;
    private static final float MAX_SPEED_MULTIPLIER = 4.0f;
    private static final long ACCELERATION_RAMP_MS = 900;

    private boolean okKeyDown = false;
    private long okKeyDownTime = 0;
    private static final long LONG_PRESS_THRESHOLD = 800;
    private boolean longPressHandled = false;

    /** 光标顶住屏幕边缘多久开始翻页，以及之后每页的间隔 */
    private static final long EDGE_HOLD_MS = 300;
    private static final long EDGE_SCROLL_INTERVAL_MS = 400;
    private static final float EDGE_THRESHOLD_PX = 2f;
    private long edgeSince = 0;
    private long lastEdgeScroll = 0;
    private int edgeDirection = -1;

    /** 停手多久后光标淡出 */
    private static final long IDLE_HIDE_MS = 3000;
    private static final long FADE_OUT_MS = 300;
    private static final long FADE_IN_MS = 120;
    private boolean cursorFadedOut = false;

    /** 暂停状态：按键全部还给系统，光标和高亮隐藏。长按返回键切换 */
    private boolean paused = false;
    private boolean backKeyDown = false;
    private boolean backLongPressHandled = false;

    private WindowManager windowManager;
    private CursorOverlayView cursorView;
    private HighlightOverlayView highlightView;
    private SettingsManager settings;
    private Vibrator vibrator;
    private Handler handler;

    /**
     * 节点查找全部走这个线程。
     * getWindows() 和遍历节点树都是跨进程调用，放主线程会直接卡在光标移动的帧上。
     */
    private HandlerThread nodeThread;
    private Handler nodeHandler;

    /** 下一帧延时，正常 16ms；节点滚动一次跨度是一页，由 handleScrollMovement 临时调大 */
    private static final long FRAME_INTERVAL_MS = 16;
    private long nextFrameDelay = FRAME_INTERVAL_MS;

    private final Runnable moveRunnable = new Runnable() {
        @Override
        public void run() {
            if (isMoving()) {
                // 移动中绝不淡出，不能指望遥控器一定会发按键重复事件
                handler.removeCallbacks(fadeOutRunnable);
                nextFrameDelay = FRAME_INTERVAL_MS;
                updateCursorPosition();
                handler.postDelayed(this, nextFrameDelay);
            }
        }
    };

    /** 光标停下或移动中定时刷新「光标下是什么元素」，不跟着 16ms 的移动帧跑，避免频繁遍历节点树 */
    private static final long HOVER_REFRESH_MS = 120;
    private long lastHoverRefresh = 0;

    private final Runnable hoverRunnable = new Runnable() {
        @Override
        public void run() {
            refreshHoverTarget();
        }
    };

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (okKeyDown && !longPressHandled) {
                longPressHandled = true;
                toggleMode();
            }
        }
    };

    private final Runnable fadeOutRunnable = new Runnable() {
        @Override
        public void run() {
            if (paused || cursorFadedOut) return;
            cursorFadedOut = true;
            if (cursorView != null) cursorView.animate().alpha(0f).setDuration(FADE_OUT_MS).start();
            if (highlightView != null) highlightView.animate().alpha(0f).setDuration(FADE_OUT_MS).start();
        }
    };

    private final Runnable backLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (backKeyDown && !backLongPressHandled) {
                backLongPressHandled = true;
                togglePaused();
            }
        }
    };

    private static ServiceStatusListener statusListener;
    private static boolean serviceRunning = false;
    private static CursorAccessibilityService sInstance;

    public interface ServiceStatusListener {
        void onServiceStatusChanged(boolean running);
    }

    public static void setStatusListener(ServiceStatusListener listener) {
        statusListener = listener;
    }

    public static boolean isServiceRunning() {
        return serviceRunning;
    }

    /** 给设置界面用：拖完光标大小滑块立刻生效，不用等光标移动 */
    public static void refreshCursorSizeFromUi() {
        CursorAccessibilityService service = sInstance;
        if (service == null || service.handler == null) return;
        service.handler.post(service::updateCursorOverlayPosition);
    }

    /** 给设置界面用：万一忘了「长按返回键」，也能从 App 里恢复 */
    public static boolean togglePausedFromUi() {
        CursorAccessibilityService service = sInstance;
        if (service == null) return false;
        service.handler.post(service::togglePaused);
        return true;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Remouse 无障碍服务已连接");

        settings = SettingsManager.getInstance(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        cursorX = screenWidth / 2f;
        cursorY = screenHeight / 2f;

        sInstance = this;
        paused = settings.isPaused();

        nodeThread = new HandlerThread("RemouseNode");
        nodeThread.start();
        nodeHandler = new Handler(nodeThread.getLooper());

        createHighlightOverlay();
        createCursorOverlay();
        applyPausedState();

        serviceRunning = true;
        if (statusListener != null) {
            statusListener.onServiceStatusChanged(true);
        }

        Toast.makeText(this, paused ? "Remouse 已启动（当前暂停中，长按返回键恢复）" : "Remouse 已启动 ✓",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeCursorOverlay();
        removeHighlightOverlay();
        handler.removeCallbacksAndMessages(null);

        if (nodeThread != null) {
            nodeThread.quitSafely();
            nodeThread = null;
            nodeHandler = null;
        }

        sInstance = null;
        serviceRunning = false;
        if (statusListener != null) {
            statusListener.onServiceStatusChanged(false);
        }
        Log.i(TAG, "Remouse 无障碍服务已断开");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int eventType = event.getEventType();
        // 界面变了，光标下的元素多半也变了，重新找一次高亮目标
        if (paused) return;
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleHoverRefresh();
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && System.currentTimeMillis() - lastHoverRefresh >= 400) {
            scheduleHoverRefresh();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Remouse 服务被中断");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // 诊断日志：打印每一个收到的按键，用于定位遥控器实际 keycode
        Log.i(TAG, "onKeyEvent 收到按键: keyCode=" + keyCode
                + " (" + KeyEvent.keyCodeToString(keyCode) + ")"
                + " action=" + (action == KeyEvent.ACTION_DOWN ? "DOWN" : action == KeyEvent.ACTION_UP ? "UP" : action)
                + " source=" + event.getSource()
                + " device=" + event.getDeviceId());

        if (!paused) {
            noteInteraction();
        }

        // 返回键无论暂停与否都要接管：短按补发返回，长按用来切换暂停/恢复
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return handleBackKey(action);
        }

        // 暂停中：其余按键一律原样交还系统
        if (paused) {
            return false;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return handleDirectionKey(keyCode, action);

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return handleOkKey(action);

            case KeyEvent.KEYCODE_VOLUME_UP:
                if (action == KeyEvent.ACTION_DOWN) {
                    adjustSpeed(1);
                    return true;
                }
                return action == KeyEvent.ACTION_UP;

            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action == KeyEvent.ACTION_DOWN) {
                    adjustSpeed(-1);
                    return true;
                }
                return action == KeyEvent.ACTION_UP;

            default:
                return false;
        }
    }

    private boolean handleDirectionKey(int keyCode, int action) {
        if (action == KeyEvent.ACTION_DOWN) {
            boolean wasMoving = isMoving();

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:    movingUp = true; break;
                case KeyEvent.KEYCODE_DPAD_DOWN:  movingDown = true; break;
                case KeyEvent.KEYCODE_DPAD_LEFT:  movingLeft = true; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: movingRight = true; break;
            }

            // 立刻开始移动，不等任何延时。速度从低起步再平滑加速，
            // 所以短按只走一小段（微调），按住才越走越快。
            if (!wasMoving && isMoving()) {
                moveStartTime = System.currentTimeMillis();
                handler.post(moveRunnable);
            }
            return true;

        } else if (action == KeyEvent.ACTION_UP) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:    movingUp = false; break;
                case KeyEvent.KEYCODE_DPAD_DOWN:  movingDown = false; break;
                case KeyEvent.KEYCODE_DPAD_LEFT:  movingLeft = false; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: movingRight = false; break;
            }

            if (!isMoving()) {
                handler.removeCallbacks(moveRunnable);
                edgeSince = 0;
                edgeDirection = -1;
                noteInteraction();
                scheduleHoverRefresh();
            }
            return true;
        }
        return false;
    }

    private void scrollOnce(int keyCode) {
        final boolean vertical = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        final boolean backward = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        final int x = (int) cursorX;
        final int y = (int) cursorY;

        postNode(() -> {
            AccessibilityNodeInfo scrollable = findScrollableAt(x, y);
            if (scrollable != null) {
                int scrollAction = backward
                        ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                if (scrollable.performAction(scrollAction)) return;
            }

            float distance = settings.getScrollPixelDistance();
            handler.post(() -> {
                if (vertical) {
                    int from = (int) (backward ? y - distance : y + distance);
                    int to = (int) (backward ? y + distance : y - distance);
                    dispatchScrollGesture(x, from, x, to);
                } else {
                    int from = (int) (backward ? x - distance : x + distance);
                    int to = (int) (backward ? x + distance : x - distance);
                    dispatchScrollGesture(from, y, to, y);
                }
            });
        });
    }

    private boolean handleOkKey(int action) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (!okKeyDown) {
                okKeyDown = true;
                okKeyDownTime = System.currentTimeMillis();
                longPressHandled = false;
                handler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD);
            }
            return true;
        } else if (action == KeyEvent.ACTION_UP) {
            okKeyDown = false;
            handler.removeCallbacks(longPressRunnable);
            if (!longPressHandled) {
                performClick();
            }
            return true;
        }
        return false;
    }

    private boolean handleBackKey(int action) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (!backKeyDown) {
                backKeyDown = true;
                backLongPressHandled = false;
                handler.postDelayed(backLongPressRunnable, LONG_PRESS_THRESHOLD);
            }
            return true;
        } else if (action == KeyEvent.ACTION_UP) {
            backKeyDown = false;
            handler.removeCallbacks(backLongPressRunnable);
            if (!backLongPressHandled) {
                // 短按：把返回补发给系统。这里不能放行原始按键，
                // 因为长按判定必须先把 DOWN 拦下来，否则系统会先执行一次返回。
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return true;
        }
        return false;
    }

    private void togglePaused() {
        paused = !paused;
        settings.setPaused(paused);

        // 暂停时把移动状态清干净，免得恢复后光标自己往一个方向漂
        movingUp = movingDown = movingLeft = movingRight = false;
        okKeyDown = false;
        handler.removeCallbacks(moveRunnable);
        handler.removeCallbacks(longPressRunnable);
        handler.removeCallbacks(hoverRunnable);

        applyPausedState();

        Toast.makeText(this, paused ? "Remouse 已暂停 ⏸  长按返回键恢复" : "Remouse 已恢复 ▶",
                Toast.LENGTH_SHORT).show();

        if (settings.isHapticEnabled() && vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }

        if (statusListener != null) {
            statusListener.onServiceStatusChanged(true);
        }

        Log.i(TAG, paused ? "已暂停：按键全部交还系统" : "已恢复");
    }

    /** 有按键就把光标叫回来，并重新排下一次淡出。淡入要快，响应不能拖 */
    private void noteInteraction() {
        handler.removeCallbacks(fadeOutRunnable);

        if (cursorFadedOut) {
            cursorFadedOut = false;
            if (cursorView != null) cursorView.animate().alpha(1f).setDuration(FADE_IN_MS).start();
            if (highlightView != null) highlightView.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        }

        if (settings.isAutoHideEnabled()) {
            handler.postDelayed(fadeOutRunnable, IDLE_HIDE_MS);
        }
    }

    private void applyPausedState() {
        handler.removeCallbacks(fadeOutRunnable);

        if (cursorView != null) {
            cursorView.setVisibility(paused ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        if (highlightView != null) {
            highlightView.setTarget(null);
            highlightView.setVisibility(paused ? android.view.View.GONE : android.view.View.VISIBLE);
        }

        if (!paused) {
            // 恢复时光标可能停在淡出后的透明状态，先拉回来
            cursorFadedOut = false;
            if (cursorView != null) cursorView.setAlpha(1f);
            if (highlightView != null) highlightView.setAlpha(1f);
            noteInteraction();
            scheduleHoverRefresh();
        }
    }

    private boolean isMoving() {
        return movingUp || movingDown || movingLeft || movingRight;
    }

    private void updateCursorPosition() {
        float target = settings.getCursorPixelSpeed();
        float speed;

        if (settings.isAccelerationEnabled()) {
            float base = target * START_SPEED_RATIO;
            float max = target * MAX_SPEED_MULTIPLIER;
            long elapsed = System.currentTimeMillis() - moveStartTime;
            float t = Math.min(1f, (float) elapsed / ACCELERATION_RAMP_MS);
            speed = base + (max - base) * t * t;   // 二次曲线：起步轻，越按越快
        } else {
            speed = target;
        }

        if (cursorView != null && cursorView.isScrollMode()) {
            handleScrollMovement(speed);
        } else {
            handleCursorMovement(speed);
        }
    }

    private void handleCursorMovement(float speed) {
        if (movingUp) cursorY -= speed;
        if (movingDown) cursorY += speed;
        if (movingLeft) cursorX -= speed;
        if (movingRight) cursorX += speed;

        cursorX = Math.max(0, Math.min(screenWidth - 1, cursorX));
        cursorY = Math.max(0, Math.min(screenHeight - 1, cursorY));

        checkEdgeScroll();

        updateCursorOverlayPosition();

        if (System.currentTimeMillis() - lastHoverRefresh >= HOVER_REFRESH_MS) {
            refreshHoverTarget();
        }
    }

    /**
     * 光标已经顶到屏幕边缘、还在继续往外推，就把底下的列表翻一页。
     * 先要顶住 EDGE_HOLD_MS 才开始 —— 否则只是想够到角落里的按钮，页面就先滚走了。
     */
    private void checkEdgeScroll() {
        int direction = -1;
        if (movingUp && cursorY <= EDGE_THRESHOLD_PX) {
            direction = KeyEvent.KEYCODE_DPAD_UP;
        } else if (movingDown && cursorY >= screenHeight - 1 - EDGE_THRESHOLD_PX) {
            direction = KeyEvent.KEYCODE_DPAD_DOWN;
        } else if (movingLeft && cursorX <= EDGE_THRESHOLD_PX) {
            direction = KeyEvent.KEYCODE_DPAD_LEFT;
        } else if (movingRight && cursorX >= screenWidth - 1 - EDGE_THRESHOLD_PX) {
            direction = KeyEvent.KEYCODE_DPAD_RIGHT;
        }

        if (direction == -1) {
            edgeSince = 0;
            edgeDirection = -1;
            return;
        }

        long now = System.currentTimeMillis();

        if (direction != edgeDirection) {
            edgeDirection = direction;
            edgeSince = now;
            lastEdgeScroll = 0;
            return;
        }

        if (now - edgeSince < EDGE_HOLD_MS) return;
        if (now - lastEdgeScroll < EDGE_SCROLL_INTERVAL_MS) return;

        lastEdgeScroll = now;
        edgeScroll(direction);
    }

    /** 边缘翻页：光标已经贴边了，从屏幕中心去找可滚动的容器 */
    private void edgeScroll(int keyCode) {
        final boolean backward = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        final int probeX = (int) Math.max(0, Math.min(screenWidth - 1, cursorX));
        final int probeY = (int) Math.max(0, Math.min(screenHeight - 1, cursorY));

        postNode(() -> {
            AccessibilityNodeInfo scrollable = findScrollableAt(probeX, probeY);
            if (scrollable == null) {
                scrollable = findScrollableAt(screenWidth / 2, screenHeight / 2);
            }
            if (scrollable == null) {
                Log.d(TAG, "边缘翻页：附近没有可滚动的容器");
                return;
            }

            int scrollAction = backward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            boolean ok = scrollable.performAction(scrollAction);
            Log.i(TAG, "边缘翻页 " + (ok ? "成功" : "失败") + ": " + scrollable.getClassName());

            if (ok) {
                handler.post(CursorAccessibilityService.this::scheduleHoverRefresh);
            }
        });
    }

    private void handleScrollMovement(float speed) {
        int keyCode = movingUp ? KeyEvent.KEYCODE_DPAD_UP
                : movingDown ? KeyEvent.KEYCODE_DPAD_DOWN
                : movingLeft ? KeyEvent.KEYCODE_DPAD_LEFT
                : movingRight ? KeyEvent.KEYCODE_DPAD_RIGHT
                : -1;
        if (keyCode == -1) return;

        scrollOnce(keyCode);
        // 节点滚动一次跨度就是一页，别按 16ms 的频率连发
        nextFrameDelay = 350;
    }

    private void performClick() {
        // 视觉和振动反馈立刻给，别等跨进程调用回来
        if (cursorView != null) {
            cursorView.showClickRipple();
        }

        if (settings.isHapticEnabled() && vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }

        final int x = (int) cursorX;
        final int y = (int) cursorY;

        postNode(() -> {
            // 电视 App 基本都是焦点导航，注入的触摸手势它们不理（TCL 桌面只会把焦点重置一下），
            // 所以优先直接对光标下的节点执行 ACTION_CLICK。
            if (clickNodeAt(x, y)) return;
            handler.post(() -> dispatchClickGesture(x, y));
        });
    }

    /** 对坐标处的可点节点执行点击，成功返回 true。跑在节点线程上 */
    private boolean clickNodeAt(int x, int y) {
        AccessibilityNodeInfo node = findTargetNode(x, y);
        if (node == null) {
            Log.i(TAG, "光标下没有可点节点，回退到手势注入");
            return false;
        }

        // 先给焦点：一部分电视控件的点击响应依赖自身是否处于焦点态
        if (node.isFocusable() && !node.isFocused()) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }

        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.i(TAG, "节点点击 " + (ok ? "成功" : "失败")
                + ": " + node.getClassName()
                + " text=" + node.getText()
                + " desc=" + node.getContentDescription());
        return ok;
    }

    /** 兜底：注入触摸手势 */
    private void dispatchClickGesture(int x, int y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 100));

        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "手势点击完成: (" + x + ", " + y + ")");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "手势点击被取消");
            }
        }, null);
    }

    private void dispatchScrollGesture(int startX, int startY, int endX, int endY) {
        startX = Math.max(0, Math.min(screenWidth - 1, startX));
        startY = Math.max(0, Math.min(screenHeight - 1, startY));
        endX = Math.max(0, Math.min(screenWidth - 1, endX));
        endY = Math.max(0, Math.min(screenHeight - 1, endY));

        Path scrollPath = new Path();
        scrollPath.moveTo(startX, startY);
        scrollPath.lineTo(endX, endY);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(scrollPath, 0, 200));

        dispatchGesture(builder.build(), null, null);
    }

    private void toggleMode() {
        if (cursorView != null) {
            boolean newScrollMode = !cursorView.isScrollMode();
            cursorView.setScrollMode(newScrollMode);
            if (highlightView != null) {
                highlightView.setScrollMode(newScrollMode);
                highlightView.setTarget(null);
            }
            scheduleHoverRefresh();

            String msg = newScrollMode ? "已切换到滚动模式 ↕" : "已切换到光标模式 ✛";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

            if (settings.isHapticEnabled() && vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            }
            Log.i(TAG, "模式切换: " + (newScrollMode ? "滚动" : "光标"));
        }
    }

    private void adjustSpeed(int delta) {
        if (cursorView != null && cursorView.isScrollMode()) {
            int newSpeed = settings.getScrollSpeed() + delta;
            newSpeed = Math.max(1, Math.min(10, newSpeed));
            settings.setScrollSpeed(newSpeed);
            Toast.makeText(this, "滚动速度: " + newSpeed, Toast.LENGTH_SHORT).show();
        } else {
            int newSpeed = settings.getCursorSpeed() + delta;
            newSpeed = Math.max(1, Math.min(10, newSpeed));
            settings.setCursorSpeed(newSpeed);
            Toast.makeText(this, "光标速度: " + newSpeed, Toast.LENGTH_SHORT).show();
        }

        if (settings.isHapticEnabled() && vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }
    }

    private void postNode(Runnable task) {
        Handler h = nodeHandler;
        if (h != null) h.post(task);
    }

    // ---------------- 节点查找 ----------------

    /** 吸附半径：光标没压在按钮上、但离得够近时，也认这个按钮。按屏幕宽度取 5%（1080p 约 96px） */
    private int snapRadiusPx() {
        return Math.max(48, (int) (screenWidth * 0.05f));
    }

    /**
     * 找光标要作用的可点节点。
     * 先看有没有节点把光标圈在里面（有多个就取面积最小的，即最具体的那个）；
     * 没有就在吸附半径内找最近的一个 —— 小按钮点不准主要靠这一步兜住。
     */
    private AccessibilityNodeInfo findTargetNode(int x, int y) {
        // 关掉吸附时半径给 1px 而不是 0：零面积矩形在 Rect.intersects 里永远不相交，会一个节点都找不到
        int radius = settings.isSnapEnabled() ? snapRadiusPx() : 1;

        Rect searchArea = new Rect(x - radius, y - radius, x + radius, y + radius);

        AccessibilityNodeInfo containing = null;
        int containingArea = Integer.MAX_VALUE;
        AccessibilityNodeInfo nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (AccessibilityNodeInfo root : collectRoots()) {
            Candidate candidate = new Candidate();
            collectClickable(root, x, y, searchArea, candidate);
            if (candidate.containing != null && candidate.containingArea < containingArea) {
                containing = candidate.containing;
                containingArea = candidate.containingArea;
            }
            if (candidate.nearest != null && candidate.nearestDist < nearestDist) {
                nearest = candidate.nearest;
                nearestDist = candidate.nearestDist;
            }
            // 高层窗口一旦有结果就不再看下层，避免弹窗底下的界面抢目标
            if (containing != null) break;
        }

        if (containing != null) return containing;
        if (nearest != null && nearestDist <= radius) return nearest;
        return null;
    }

    private static final class Candidate {
        AccessibilityNodeInfo containing;
        int containingArea = Integer.MAX_VALUE;
        AccessibilityNodeInfo nearest;
        int nearestDist = Integer.MAX_VALUE;
    }

    /** 按窗口层级从高到低取各窗口的根节点 */
    private List<AccessibilityNodeInfo> collectRoots() {
        List<AccessibilityNodeInfo> roots = new java.util.ArrayList<>();

        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
        } catch (Exception e) {
            Log.w(TAG, "getWindows 失败", e);
        }

        if (windows != null && !windows.isEmpty()) {
            AccessibilityWindowInfo[] sorted = windows.toArray(new AccessibilityWindowInfo[0]);
            java.util.Arrays.sort(sorted, (a, b) -> b.getLayer() - a.getLayer());
            for (AccessibilityWindowInfo w : sorted) {
                if (w == null) continue;
                // 跳过自己的浮层，否则永远命中光标自己
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) roots.add(root);
            }
        }

        if (roots.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) roots.add(root);
        }
        return roots;
    }

    private void collectClickable(AccessibilityNodeInfo node, int x, int y, Rect searchArea, Candidate out) {
        if (node == null) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        // 子节点不会超出父节点范围，父节点碰不到搜索区就整棵子树跳过
        if (!Rect.intersects(bounds, searchArea)) return;

        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            collectClickable(node.getChild(i), x, y, searchArea, out);
        }

        if (!node.isClickable() || !node.isEnabled() || !node.isVisibleToUser()) return;
        if (bounds.isEmpty()) return;

        if (bounds.contains(x, y)) {
            int area = bounds.width() * bounds.height();
            if (area < out.containingArea) {
                out.containingArea = area;
                out.containing = node;
            }
            return;
        }

        int dist = distanceToRect(bounds, x, y);
        if (dist < out.nearestDist) {
            out.nearestDist = dist;
            out.nearest = node;
        }
    }

    /** 点到矩形的距离，点在矩形内为 0 */
    private static int distanceToRect(Rect r, int x, int y) {
        int dx = Math.max(Math.max(r.left - x, 0), x - r.right);
        int dy = Math.max(Math.max(r.top - y, 0), y - r.bottom);
        return (int) Math.hypot(dx, dy);
    }

    /** 滚动模式用：该坐标下任意节点，再往上找可滚动的祖先 */
    private AccessibilityNodeInfo findScrollableAt(int x, int y) {
        for (AccessibilityNodeInfo root : collectRoots()) {
            AccessibilityNodeInfo deepest = deepestNodeAt(root, x, y);
            int depth = 0;
            while (deepest != null && depth < 15) {
                if (deepest.isScrollable() && deepest.isEnabled()) return deepest;
                deepest = deepest.getParent();
                depth++;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo deepestNodeAt(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) return null;

        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo hit = deepestNodeAt(node.getChild(i), x, y);
            if (hit != null) return hit;
        }
        return node;
    }

    // ---------------- 悬停高亮 ----------------

    private void scheduleHoverRefresh() {
        if (handler == null) return;
        handler.removeCallbacks(hoverRunnable);
        handler.postDelayed(hoverRunnable, HOVER_REFRESH_MS);
    }

    private void refreshHoverTarget() {
        lastHoverRefresh = System.currentTimeMillis();
        if (highlightView == null || paused) return;

        if (!settings.isHighlightEnabled()) {
            highlightView.setTarget(null);
            return;
        }

        if (cursorView != null && cursorView.isScrollMode()) {
            highlightView.setTarget(null);
            return;
        }

        final int x = (int) cursorX;
        final int y = (int) cursorY;

        postNode(() -> {
            AccessibilityNodeInfo node = findTargetNode(x, y);
            final Rect bounds;
            if (node == null) {
                bounds = null;
            } else {
                bounds = new Rect();
                node.getBoundsInScreen(bounds);
            }
            handler.post(() -> {
                if (highlightView != null && !paused) {
                    highlightView.setTarget(bounds);
                }
            });
        });
    }

    private void createHighlightOverlay() {
        highlightView = new HighlightOverlayView(this);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        params.x = 0;
        params.y = 0;

        try {
            windowManager.addView(highlightView, params);
            Log.i(TAG, "高亮浮层创建成功");
        } catch (Exception e) {
            Log.e(TAG, "创建高亮浮层失败", e);
            highlightView = null;
        }
    }

    private void removeHighlightOverlay() {
        if (highlightView != null) {
            highlightView.cleanup();
            try {
                windowManager.removeView(highlightView);
            } catch (Exception e) {
                Log.e(TAG, "移除高亮浮层失败", e);
            }
            highlightView = null;
        }
    }

    private void createCursorOverlay() {
        int cursorSizePx = dpToPx(settings.getCursorSize());

        cursorView = new CursorOverlayView(this);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                cursorSizePx,
                cursorSizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        params.x = (int) cursorX - cursorSizePx / 2;
        params.y = (int) cursorY - cursorSizePx / 2;
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;

        try {
            windowManager.addView(cursorView, params);
            Log.i(TAG, "光标浮层创建成功");
        } catch (Exception e) {
            Log.e(TAG, "创建光标浮层失败", e);
        }
    }

    private void updateCursorOverlayPosition() {
        if (cursorView == null || windowManager == null) return;

        try {
            int cursorSizePx = dpToPx(settings.getCursorSize());
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) cursorView.getLayoutParams();
            // 尺寸也要写回去：设置里的滑块改的是这个值，只更新坐标的话光标永远是创建时那么大
            params.width = cursorSizePx;
            params.height = cursorSizePx;
            params.x = (int) cursorX - cursorSizePx / 2;
            params.y = (int) cursorY - cursorSizePx / 2;
            windowManager.updateViewLayout(cursorView, params);
        } catch (Exception e) {
            Log.e(TAG, "更新光标位置失败", e);
        }
    }

    private void removeCursorOverlay() {
        if (cursorView != null) {
            cursorView.cleanup();
            try {
                windowManager.removeView(cursorView);
            } catch (Exception e) {
                Log.e(TAG, "移除光标浮层失败", e);
            }
            cursorView = null;
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
