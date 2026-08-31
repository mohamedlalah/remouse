package com.remouse.tv;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class MainActivity extends AppCompatActivity implements CursorAccessibilityService.ServiceStatusListener {

    private TextView tvServiceStatus;
    private Button btnToggleService;
    private SeekBar seekbarCursorSpeed;
    private SeekBar seekbarScrollSpeed;
    private SeekBar seekbarCursorSize;
    private TextView tvCursorSpeedValue;
    private TextView tvScrollSpeedValue;
    private TextView tvCursorSizeValue;
    private TextView tvVersion;
    private SwitchCompat switchAcceleration;
    private SwitchCompat switchHaptic;
    private SwitchCompat switchSnap;
    private SwitchCompat switchAutoHide;
    private SwitchCompat switchHighlight;

    private SettingsManager settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = SettingsManager.getInstance(this);
        initViews();
        loadSettings();
        setupListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CursorAccessibilityService.setStatusListener(this);
        updateServiceStatus(CursorAccessibilityService.isServiceRunning());
    }

    @Override
    protected void onPause() {
        super.onPause();
        CursorAccessibilityService.setStatusListener(null);
    }

    private void initViews() {
        tvServiceStatus = findViewById(R.id.tv_service_status);
        btnToggleService = findViewById(R.id.btn_toggle_service);
        seekbarCursorSpeed = findViewById(R.id.seekbar_cursor_speed);
        seekbarScrollSpeed = findViewById(R.id.seekbar_scroll_speed);
        seekbarCursorSize = findViewById(R.id.seekbar_cursor_size);
        tvCursorSpeedValue = findViewById(R.id.tv_cursor_speed_value);
        tvScrollSpeedValue = findViewById(R.id.tv_scroll_speed_value);
        tvCursorSizeValue = findViewById(R.id.tv_cursor_size_value);
        tvVersion = findViewById(R.id.tv_version);
        switchAcceleration = findViewById(R.id.switch_acceleration);
        switchHaptic = findViewById(R.id.switch_haptic);
        switchSnap = findViewById(R.id.switch_snap);
        switchAutoHide = findViewById(R.id.switch_auto_hide);
        switchHighlight = findViewById(R.id.switch_highlight);
    }

    private void loadSettings() {
        int cursorSpeed = settings.getCursorSpeed();
        seekbarCursorSpeed.setProgress(cursorSpeed - 1);
        tvCursorSpeedValue.setText(String.valueOf(cursorSpeed));

        int scrollSpeed = settings.getScrollSpeed();
        seekbarScrollSpeed.setProgress(scrollSpeed - 1);
        tvScrollSpeedValue.setText(String.valueOf(scrollSpeed));

        int cursorSize = settings.getCursorSize();
        seekbarCursorSize.setProgress(cursorSize - 24);
        tvCursorSizeValue.setText(cursorSize + "dp");

        switchAcceleration.setChecked(settings.isAccelerationEnabled());
        switchHaptic.setChecked(settings.isHapticEnabled());
        switchSnap.setChecked(settings.isSnapEnabled());
        switchAutoHide.setChecked(settings.isAutoHideEnabled());
        switchHighlight.setChecked(settings.isHighlightEnabled());

        if (tvVersion != null) {
            tvVersion.setText("v" + BuildConfig.VERSION_NAME);
        }
    }

    private void setupListeners() {
        btnToggleService.setOnClickListener(v -> {
            // 暂停中：这个按钮就是「恢复」，省得非要拿遥控器长按返回键
            if (CursorAccessibilityService.isServiceRunning() && settings.isPaused()) {
                if (CursorAccessibilityService.togglePausedFromUi()) {
                    return;
                }
            }

            Intent intent = new Intent("android.settings.ACCESSIBILITY_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                Toast.makeText(this, "请找到 \"Remouse\" 并启用", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
            }
        });

        seekbarCursorSpeed.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int value = progress + 1;
                settings.setCursorSpeed(value);
                tvCursorSpeedValue.setText(String.valueOf(value));
            }
        });

        seekbarScrollSpeed.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int value = progress + 1;
                settings.setScrollSpeed(value);
                tvScrollSpeedValue.setText(String.valueOf(value));
            }
        });

        seekbarCursorSize.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int value = progress + 24;
                settings.setCursorSize(value);
                tvCursorSizeValue.setText(value + "dp");
                CursorAccessibilityService.refreshCursorSizeFromUi();
            }
        });

        switchAcceleration.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.setAccelerationEnabled(isChecked));

        switchHaptic.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.setHapticEnabled(isChecked));

        switchSnap.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.setSnapEnabled(isChecked));

        switchAutoHide.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.setAutoHideEnabled(isChecked));

        switchHighlight.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.setHighlightEnabled(isChecked));
    }

    @Override
    public void onServiceStatusChanged(boolean running) {
        runOnUiThread(() -> updateServiceStatus(running));
    }

    private void updateServiceStatus(boolean running) {
        if (running && settings.isPaused()) {
            tvServiceStatus.setText(R.string.status_paused);
            tvServiceStatus.setTextColor(getResources().getColor(R.color.accent_orange));
            btnToggleService.setText(R.string.btn_resume);
            return;
        }
        if (running) {
            tvServiceStatus.setText(R.string.service_status_enabled);
            tvServiceStatus.setTextColor(getResources().getColor(R.color.accent_green));
            btnToggleService.setText(R.string.btn_disable_service);
        } else {
            tvServiceStatus.setText(R.string.service_status_disabled);
            tvServiceStatus.setTextColor(getResources().getColor(R.color.accent_orange));
            btnToggleService.setText(R.string.btn_enable_service);
        }
    }

    private static abstract class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
