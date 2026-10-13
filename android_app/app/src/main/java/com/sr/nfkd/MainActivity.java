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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
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
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.ArrayList;
import java.util.Collections;
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
    private Button scan, connect, disconnect, choose, rotate, send, clear;
    private Spinner deviceList;
    private CheckBox dither, invert;
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
        deviceList = findViewById(R.id.devices); dither = findViewById(R.id.dither);
        invert = findViewById(R.id.invert); preview = findViewById(R.id.preview);
        connectionStatus = findViewById(R.id.connection_status);
        imageStatus = findViewById(R.id.image_status); progress = findViewById(R.id.progress);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, deviceLabels);
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
        dither.setOnCheckedChangeListener((v, checked) -> processImage());
        invert.setOnCheckedChangeListener((v, checked) -> processImage());
        send.setOnClickListener(v -> { if (frame != null && ready) client.send(frame); });
        clear.setOnClickListener(v -> client.clear());
        updateButtons();
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
                    devices.add(device); deviceLabels.add("C3FN4 墨水屏 · " + device.getAddress());
                    deviceAdapter.notifyDataSetChanged(); updateButtons();
                    connectionStatus.setText("找到 " + devices.size() + " 台墨水屏，选择后点击连接");
                }
            });
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
        if (scanning && devices.isEmpty()) connectionStatus.setText("未找到墨水屏，请确认设备已烧录 BLE 固件并上电");
        scanning = false; updateButtons();
    }
    private void updateButtons() {
        if (scan == null) return;
        scan.setEnabled(!busy && !scanning);
        connect.setEnabled(!busy && !ready && !devices.isEmpty());
        disconnect.setEnabled(ready || busy);
        send.setEnabled(ready && frame != null && !processing);
        clear.setEnabled(ready && !processing);
        choose.setEnabled(!busy && !processing);
        rotate.setEnabled(original != null && !busy && !processing);
        dither.setEnabled(!busy && !processing); invert.setEnabled(!busy && !processing);
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
                    original = decoded; rotation = 0; processing = false; processImage();
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
        int generation = ++imageGeneration, angle = rotation;
        Bitmap source = original;
        boolean useDither = dither.isChecked(), useInvert = invert.isChecked();
        processing = true; frame = null; updateButtons(); imageStatus.setText("正在生成四色预览…");
        images.execute(() -> {
            try {
                int width = EpaperProtocol.WIDTH, height = EpaperProtocol.HEIGHT;
                Bitmap canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(canvasBitmap); canvas.drawColor(Color.WHITE);
                boolean quarterTurn = angle % 180 != 0;
                float boxWidth = quarterTurn ? height : width, boxHeight = quarterTurn ? width : height;
                float scale = Math.min(boxWidth / source.getWidth(), boxHeight / source.getHeight());
                float w = source.getWidth() * scale, h = source.getHeight() * scale;
                canvas.translate(width / 2f, height / 2f); canvas.rotate(angle);
                canvas.drawBitmap(source, null, new RectF(-w / 2, -h / 2, w / 2, h / 2),
                        new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                int[] pixels = new int[width * height];
                canvasBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                byte[] bytes = FrameCodec.encode(pixels, width, height, useDither, useInvert);
                canvasBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
                handler.post(() -> {
                    if (destroyed || generation != imageGeneration) { canvasBitmap.recycle(); return; }
                    frame = bytes; preview.setImageBitmap(canvasBitmap); processing = false;
                    imageStatus.setText("768 × 552 · 四色预览 · 整图按比例缩放 · " + angle + "°"); updateButtons();
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
