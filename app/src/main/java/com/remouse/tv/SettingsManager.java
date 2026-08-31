package com.remouse.tv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 管理 Remouse 的所有用户设置
 */
public class SettingsManager {

    private static final String PREFS_NAME = "remouse_settings";

    private static final String KEY_CURSOR_SPEED = "cursor_speed";
    private static final String KEY_SCROLL_SPEED = "scroll_speed";
    private static final String KEY_ACCELERATION = "acceleration_enabled";
    private static final String KEY_HAPTIC = "haptic_enabled";
    private static final String KEY_CURSOR_SIZE = "cursor_size";
    private static final String KEY_PAUSED = "paused";
    private static final String KEY_SNAP = "snap_enabled";
    private static final String KEY_AUTO_HIDE = "auto_hide_enabled";
    private static final String KEY_HIGHLIGHT = "highlight_enabled";

    private static final int DEFAULT_CURSOR_SPEED = 5;
    private static final int DEFAULT_SCROLL_SPEED = 5;
    private static final boolean DEFAULT_ACCELERATION = true;
    private static final boolean DEFAULT_HAPTIC = true;
    private static final int DEFAULT_CURSOR_SIZE = 48;

    private final SharedPreferences prefs;
    private static SettingsManager instance;

    public static synchronized SettingsManager getInstance(Context context) {
        if (instance == null) {
            instance = new SettingsManager(context.getApplicationContext());
        }
        return instance;
    }

    private SettingsManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int getCursorSpeed() {
        return prefs.getInt(KEY_CURSOR_SPEED, DEFAULT_CURSOR_SPEED);
    }

    public void setCursorSpeed(int speed) {
        prefs.edit().putInt(KEY_CURSOR_SPEED, Math.max(1, Math.min(10, speed))).apply();
    }

    public int getScrollSpeed() {
        return prefs.getInt(KEY_SCROLL_SPEED, DEFAULT_SCROLL_SPEED);
    }

    public void setScrollSpeed(int speed) {
        prefs.edit().putInt(KEY_SCROLL_SPEED, Math.max(1, Math.min(10, speed))).apply();
    }

    public boolean isAccelerationEnabled() {
        return prefs.getBoolean(KEY_ACCELERATION, DEFAULT_ACCELERATION);
    }

    public void setAccelerationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ACCELERATION, enabled).apply();
    }

    public boolean isHapticEnabled() {
        return prefs.getBoolean(KEY_HAPTIC, DEFAULT_HAPTIC);
    }

    public void setHapticEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HAPTIC, enabled).apply();
    }

    public int getCursorSize() {
        return prefs.getInt(KEY_CURSOR_SIZE, DEFAULT_CURSOR_SIZE);
    }

    public void setCursorSize(int sizeDp) {
        prefs.edit().putInt(KEY_CURSOR_SIZE, Math.max(24, Math.min(96, sizeDp))).apply();
    }

    /** 暂停后所有按键原样交还系统，光标隐藏；状态跨重启保留 */
    public boolean isPaused() {
        return prefs.getBoolean(KEY_PAUSED, false);
    }

    public void setPaused(boolean paused) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply();
    }

    /** 就近吸附：光标没压在按钮上、但离得够近时也认这个按钮 */
    public boolean isSnapEnabled() {
        return prefs.getBoolean(KEY_SNAP, true);
    }

    public void setSnapEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SNAP, enabled).apply();
    }

    /** 停手 3 秒后光标淡出 */
    public boolean isAutoHideEnabled() {
        return prefs.getBoolean(KEY_AUTO_HIDE, true);
    }

    public void setAutoHideEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_HIDE, enabled).apply();
    }

    /** 悬停高亮框。关掉后连带停掉后台的节点查找，最省资源 */
    public boolean isHighlightEnabled() {
        return prefs.getBoolean(KEY_HIGHLIGHT, true);
    }

    public void setHighlightEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HIGHLIGHT, enabled).apply();
    }

    public float getCursorPixelSpeed() {
        return getCursorSpeed() * 2f;
    }

    public float getScrollPixelDistance() {
        return getScrollSpeed() * 40f;
    }
}
