package com.sr.nfkd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private final ArrayList<String> deviceLabels = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private BluetoothAdapter bluetooth;
    private BluetoothLeScanner scanner;
    private BleClient client;
    private Button scan, connect, disconnect, choose, rotate, send, clear, firmwareButton, themeButton, fit;
    private SeekBar zoom;
    private SeekBar redDepth, blackDepth, yellowDepth;
    private Button resetDepth;
    private TextView zoomValue;
    private int zoomPercent = 100, themeIndex;
    private boolean draggingZoom;
    // Background, text and accent; changing colours does not recreate the BLE connection.
    private static final int[][] PALETTES = {
            {0xfff6f2e9, 0xff24211d, 0xff62574b},
            {0xffedf4ef, 0xff18382d, 0xff28664c},
            {0xffeef3fb, 0xff20334e, 0xff315f9d},
            {0xfffcf0ef, 0xff4e272c, 0xff9e3e50}};
    private Spinner deviceList, imageStyle;
    private CheckBox invert;
    private TextView connectionStatus, imageStatus;
    private ImageView preview;
    private ProgressBar progress;
    private boolean ready, busy, processing, scanning, destroyed;
    private Bitmap original;
    private byte[] frame;
    private int rotation, imageGeneration;
    private final Runnable stopScanTask = this::stopScan;
    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasPermissions()) startScan();
                else connectionStatus.setText("需要允许“附近的设备”权限才能连接墨水屏");
            });
    private final ActivityResultLauncher<Intent> enableBluetooth = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (bluetooth != null && bluetooth.isEnabled()) startScan();
                else connectionStatus.setText("请开启手机蓝牙后重试");
            });
    private final ActivityResultLauncher<String[]> pickFirmware = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) loadFirmware(uri); });
    private final ActivityResultLauncher<String[]> pickImage = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) loadImage(uri); });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scan = findViewById(R.id.scan); connect = findViewById(R.id.connect);
        disconnect = findViewById(R.id.disconnect); choose = findViewById(R.id.choose);
        rotate = findViewById(R.id.rotate); send = findViewById(R.id.send); clear = findViewById(R.id.clear);
        deviceList = findViewById(R.id.devices); imageStyle = findViewById(R.id.image_style);
        invert = findViewById(R.id.invert); preview = findViewById(R.id.preview);
        connectionStatus = findViewById(R.id.connection_status);
        imageStatus = findViewById(R.id.image_status); progress = findViewById(R.id.progress);
        themeButton = findViewById(R.id.theme); fit = findViewById(R.id.fit);
        zoom = findViewById(R.id.zoom); zoomValue = findViewById(R.id.zoom_value);
        redDepth = findViewById(R.id.red_depth); blackDepth = findViewById(R.id.black_depth);
        yellowDepth = findViewById(R.id.yellow_depth); resetDepth = findViewById(R.id.reset_depth);
        deviceAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, deviceLabels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView view = (TextView)super.getView(position, recycled, parent);
                view.setTextColor(PALETTES[themeIndex][1]);
                return view;
            }
        };
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceList.setAdapter(deviceAdapter);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetooth = manager == null ? null : manager.getAdapter();
        client = new BleClient(this, (message, isReady, isBusy, percent) -> {
            connectionStatus.setText(message); ready = isReady; busy = isBusy;
            progress.setProgress(percent); updateButtons();
        });
        scan.setOnClickListener(v -> startScan());
        connect.setOnClickListener(v -> {
            if (!hasPermissions()) { startScan(); return; }
            int position = deviceList.getSelectedItemPosition();
            if (position >= 0 && position < devices.size()) {
                stopScan(); client.connect(devices.get(position));
            }
        });
        disconnect.setOnClickListener(v -> client.disconnect());
        choose.setOnClickListener(v -> pickImage.launch(new String[] {"image/*"}));
        rotate.setOnClickListener(v -> { rotation = (rotation + 90) % 360; processImage(); });
        ArrayAdapter<String> styles = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, ReinkRaster.MODES);
        styles.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        imageStyle.setAdapter(styles);
        imageStyle.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                ((TextView)findViewById(R.id.style_hint)).setText(getResources().getStringArray(R.array.style_hints)[position]);
                processImage();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        invert.setOnCheckedChangeListener((v, checked) -> processImage());
        send.setOnClickListener(v -> { if (frame != null && ready) client.send(frame); });
        clear.setOnClickListener(v -> client.clear());
        firmwareButton = findViewById(R.id.update_firmware);
        firmwareButton.setOnClickListener(v -> pickFirmware.launch(new String[] {"*/*"}));
        themeIndex = Math.max(0, Math.min(PALETTES.length - 1,
                getPreferences(MODE_PRIVATE).getInt("theme", 0)));
        themeButton.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.theme_title)
                .setSingleChoiceItems(R.array.theme_names, themeIndex, (dialog, index) -> {
                    themeIndex = index;
                    getPreferences(MODE_PRIVATE).edit().putInt("theme", index).apply();
                    applyPalette(); dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show());
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { draggingZoom = true; }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                zoomPercent = value;
                zoomValue.setText(getString(R.string.zoom_value, value));
                if (fromUser) {
                    frame = null; updateButtons();
                    if (!draggingZoom) processImage();
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                draggingZoom = false; processImage();
            }
        });
        fit.setOnClickListener(v -> { zoom.setProgress(100); processImage(); });
        bindDepth(redDepth, R.id.red_depth_value, R.string.red_depth);
        bindDepth(blackDepth, R.id.black_depth_value, R.string.black_depth);
        bindDepth(yellowDepth, R.id.yellow_depth_value, R.string.yellow_depth);
        resetDepth.setOnClickListener(v -> {
            redDepth.setProgress(100); blackDepth.setProgress(100); yellowDepth.setProgress(100);
            processImage();
        });
        applyPalette();
        connectionStatus.setText(R.string.offline_hint);
        updateButtons();
    }
    private void bindDepth(SeekBar bar, int labelId, int textId) {
        TextView label = findViewById(labelId);
        label.setText(getString(textId, bar.getProgress()));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean dragging;
            @Override public void onStartTrackingTouch(SeekBar seekBar) { dragging = true; }
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                label.setText(getString(textId, value));
                if (fromUser) {
                    frame = null; updateButtons();
                    if (!dragging) processImage();
                }
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false; processImage();
            }
        });
    }
    private void applyPalette() {
        int[] palette = PALETTES[themeIndex];
        View root = findViewById(R.id.main);
        root.setBackgroundColor(palette[0]);
        tintText(root, palette[1]);
        ColorStateList tint = new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {0xffd2d1cc, palette[2]});
        for (Button button : new Button[] {scan, connect, disconnect, choose, rotate, send,
                clear, firmwareButton, themeButton, fit, resetDepth}) {
            button.setBackgroundTintList(tint);
            button.setTextColor(Color.WHITE);
        }
        invert.setButtonTintList(tint);
        zoom.setProgressTintList(tint); zoom.setThumbTintList(tint);
        SeekBar[] depthBars = {redDepth, blackDepth, yellowDepth};
        int[] inkColors = {0xffb44945, 0xff727272, 0xffb58a20};
        for (int i = 0; i < depthBars.length; i++) {
            ColorStateList ink = new ColorStateList(
                    new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                    new int[] {0xffd2d1cc, inkColors[i]});
            depthBars[i].setProgressTintList(ink); depthBars[i].setThumbTintList(ink);
        }
        progress.setProgressTintList(tint);
        themeButton.setText(getString(R.string.theme_current,
                getResources().getStringArray(R.array.theme_names)[themeIndex]));
        deviceAdapter.notifyDataSetChanged();
    }
    private void tintText(View view, int color) {
        if (view instanceof TextView) ((TextView)view).setTextColor(color);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) tintText(group.getChildAt(i), color);
        }
    }
    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    private void startScan() {
        if (!hasPermissions()) {
            permissions.launch(new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT});
            return;
        }
        if (bluetooth == null) { connectionStatus.setText("这台手机不支持蓝牙"); return; }
        if (!bluetooth.isEnabled()) { enableBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return; }
        stopScan(); client.disconnect();
        devices.clear(); deviceLabels.clear(); deviceAdapter.notifyDataSetChanged();
        scanner = bluetooth.getBluetoothLeScanner();
        if (scanner == null) { connectionStatus.setText("蓝牙扫描不可用，请重试"); return; }
        scanning = true; connectionStatus.setText("正在搜索墨水屏（15 秒）…"); updateButtons();
        try {
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder()
                            .setServiceUuid(new ParcelUuid(EpaperProtocol.SERVICE)).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            handler.postDelayed(stopScanTask, 15000);
        } catch (SecurityException | IllegalStateException e) {
            stopScan(); connectionStatus.setText("扫描失败：" + e.getMessage());
        }
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            handler.post(() -> {
                if (!scanning || destroyed) return;
                BluetoothDevice device = result.getDevice();
                if (!devices.contains(device)) {
                    devices.add(device); deviceLabels.add("EPD-Calendar · " + device.getAddress());
                    deviceAdapter.notifyDataSetChanged(); updateButtons();
                    connectionStatus.setText("找到 " + devices.size() + " 台墨水屏，选择后点击连接");
                }
            });
        }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
        }
        @Override public void onScanFailed(int code) {
            handler.post(() -> { stopScan(); connectionStatus.setText("扫描失败（" + code + "），请稍后重试"); });
        }
    };
    private void stopScan() {
        handler.removeCallbacks(stopScanTask);
        if (scanning && scanner != null && hasPermissions()) {
            try { scanner.stopScan(scanCallback); } catch (IllegalStateException ignored) { }
        }
        if (scanning && devices.isEmpty()) connectionStatus.setText("未找到墨水屏，请确认设备上电且未被其他手机连接");
        scanning = false; updateButtons();
    }
    private void updateButtons() {
        if (scan == null) return;
        scan.setEnabled(!busy && !scanning);
        connect.setEnabled(!busy && !ready && !devices.isEmpty());
        disconnect.setEnabled(ready || busy);
        send.setEnabled(ready && frame != null && !processing);
        clear.setEnabled(ready && !processing);
        firmwareButton.setEnabled(ready && !processing);
        // Local image editing is independent of connection, scanning and device work.
        choose.setEnabled(!processing);
        rotate.setEnabled(original != null && !processing);
        zoom.setEnabled(original != null && !processing);
        fit.setEnabled(original != null && !processing);
        redDepth.setEnabled(original != null && !processing);
        blackDepth.setEnabled(original != null && !processing);
        yellowDepth.setEnabled(original != null && !processing);
        resetDepth.setEnabled(original != null && !processing);
        imageStyle.setEnabled(!processing); invert.setEnabled(!processing);
    }
    private void loadFirmware(Uri uri) {
        if (!ready || processing) return;
        processing = true; updateButtons(); imageStatus.setText("正在读取固件…");
        images.execute(() -> {
            try (java.io.InputStream input = getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                if (input == null) throw new java.io.IOException("无法打开文件");
                byte[] chunk = new byte[8192]; int count;
                while ((count = input.read(chunk)) != -1) {
                    if (output.size() + count > EpaperProtocol.FIRMWARE_MAX_SIZE)
                        throw new java.io.IOException("固件超过设备分区容量");
                    output.write(chunk, 0, count);
                }
                byte[] bytes = output.toByteArray();
                String project = EpaperProtocol.validateFirmware(bytes);
                handler.post(() -> {
                    if (destroyed) return;
                    processing = false; updateButtons(); imageStatus.setText("已读取" + (project.endsWith("_BLE") ? "蓝牙版" : "Wi-Fi 版") + "固件 · " + bytes.length / 1024 + " KB");
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("更新设备固件")
                            .setMessage("更新后请" + EpaperProtocol.firmwareConnection(project) + "。上传过程中请保持 App 在前台、手机靠近设备，并保持设备供电。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("开始更新", (dialog, which) -> client.updateFirmware(bytes)).show();
                });
            } catch (Exception | OutOfMemoryError e) {
                handler.post(() -> {
                    if (destroyed) return;
                    processing = false; imageStatus.setText("无法使用该固件：" + e.getMessage()); updateButtons();
                });
            }
        });
    }
    private void loadImage(Uri uri) {
        int generation = ++imageGeneration;
        processing = true; frame = null; original = null; preview.setImageDrawable(null);
        updateButtons(); imageStatus.setText("正在读取图片…");
        images.execute(() -> {
            try {
                Bitmap decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, info, source) -> {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                            float scale = Math.min(1f, 1536f / Math.max(w, h));
                            decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                        });
                handler.post(() -> {
                    if (destroyed || generation != imageGeneration) { decoded.recycle(); return; }
                    original = decoded; rotation = 0; zoom.setProgress(100);
                    processing = false; processImage();
                });
            } catch (Exception | OutOfMemoryError e) {
                handler.post(() -> {
                    if (destroyed || generation != imageGeneration) return;
                    processing = false; imageStatus.setText("无法读取图片：" + e.getMessage()); updateButtons();
                });
            }
        });
    }
    private void processImage() {
        if (original == null || processing) return;
        int generation = ++imageGeneration, angle = rotation, magnification = zoomPercent;
        Bitmap source = original;
        int style = imageStyle.getSelectedItemPosition();
        boolean useInvert = invert.isChecked();
        int red = redDepth.getProgress(), black = blackDepth.getProgress(), yellow = yellowDepth.getProgress();
        processing = true; frame = null; updateButtons(); imageStatus.setText("正在生成四色预览…");
        images.execute(() -> {
            try {
                int width = EpaperProtocol.WIDTH, height = EpaperProtocol.HEIGHT;
                Bitmap canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(canvasBitmap); canvas.drawColor(Color.WHITE);
                // Portrait editing uses the native landscape framebuffer turned clockwise.
                int panelAngle = (angle + 90) % 360;
                float scale = ImagePlacement.scale(source.getWidth(), source.getHeight(), panelAngle, magnification);
                float w = source.getWidth() * scale, h = source.getHeight() * scale;
                canvas.translate(width / 2f, height / 2f); canvas.rotate(panelAngle);
                canvas.drawBitmap(source, null, new RectF(-w / 2, -h / 2, w / 2, h / 2),
                        new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                int[] pixels = new int[width * height];
                canvasBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                byte[] bytes = FrameCodec.encode(pixels, width, height, style, useInvert, red, black, yellow);
                canvasBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
                Matrix portrait = new Matrix(); portrait.postRotate(-90);
                Bitmap portraitPreview = Bitmap.createBitmap(canvasBitmap, 0, 0, width, height, portrait, false);
                canvasBitmap.recycle();
                handler.post(() -> {
                    if (destroyed || generation != imageGeneration) { portraitPreview.recycle(); return; }
                    frame = bytes; preview.setImageBitmap(portraitPreview); processing = false;
                    imageStatus.setText(getString(R.string.preview_status, angle, magnification,
                            magnification > 100 ? getString(R.string.zoom_crop) : getString(R.string.zoom_contain)));
                    updateButtons();
                });
            } catch (Exception | OutOfMemoryError e) {
                handler.post(() -> {
                    if (destroyed || generation != imageGeneration) return;
                    processing = false; imageStatus.setText("图片处理失败：" + e.getMessage()); updateButtons();
                });
            }
        });
    }
    @Override protected void onStop() { stopScan(); super.onStop(); }
    @Override protected void onDestroy() {
        destroyed = true; ++imageGeneration; stopScan(); client.disconnect();
        images.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
