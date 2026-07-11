package com.splannes.fileshares;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.google.zxing.BarcodeFormat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Main Activity for FileShare Pro.
 *
 * Features:
 * - File sharing (pick & serve files, folders)
 * - Screen mirroring (start/stop MediaProjection)
 * - Token-based server authentication
 * - Image-to-PDF and Text-to-PDF conversion
 * - Device discovery with mDNS (NsdManager)
 * - Share-to-device with allow/deny prompt
 * - Proper lifecycle handling
 */
public class MainActivity extends Activity implements FileListAdapter.OnFileActionListener {

    private static final String TAG = "MainActivity";

    private static final int PICK_FILE_REQUEST = 1;
    private static final int PICK_IMAGE_REQUEST = 2;
    private static final int PICK_FOLDER_REQUEST = 3;
    private static final int PICK_TEXT_REQUEST = 4;
    private static final int REQUEST_STORAGE_PERMISSION = 100;
    private static final int REQUEST_MEDIA_PROJECTION = 200;
    private static final int SERVER_PORT = 8988;

    // Views
    private MaterialButton btnSelectFiles, btnPickImage, btnImageToPdf;
    private MaterialButton btnSelectFolder, btnStartServer, btnStartMirror, btnHotspotGuide;
    private MaterialButton btnTextToPdf, btnDiscoverDevices, btnSendToDevices;
    private TextView tvServerUrl, tvConnectionStatus, tvFileCount, tvAuthToken, tvQrHint;
    private ImageView ivQr;
    private View layoutAuthToken, indicatorStatus;
    private MaterialCardView cardQr, cardFiles, cardDevices;
    private View emptyState;
    private RecyclerView recyclerViewFiles;
    private ListView listViewDevices;
    private LinearLayout layoutDeviceButtons;

    // Data
    private FileListAdapter fileAdapter;
    private final ArrayList<FileItem> managedFiles = new ArrayList<>();
    private final ArrayList<Uri> selectedFiles = new ArrayList<>();
    private final ArrayList<Uri> selectedImages = new ArrayList<>();
    private final ArrayList<String> selectedFolders = new ArrayList<>();
    private int receivedFilesCount = 0;

    // Auth token generated per session
    private String authToken;

    // Mirror state
    private boolean isMirroring = false;
    private int mediaProjectionResultCode;
    private Intent mediaProjectionResultData;

    // Device discovery
    private DeviceDiscoveryManager discoveryManager;
    private final ArrayList<DeviceDiscoveryManager.DiscoveredDevice> discoveredDevices = new ArrayList<>();
    private ArrayAdapter<String> deviceListAdapter;
    private final ArrayList<String> deviceDisplayNames = new ArrayList<>();

    // Share request receiver
    private ShareRequestReceiver shareRequestReceiver;

    // Broadcast receiver for uploaded files
    private final BroadcastReceiver fileUploadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String fileName = intent.getStringExtra("fileName");
            String filePath = intent.getStringExtra("filePath");
            long fileSize = intent.getLongExtra("fileSize", 0);

            if (fileName != null && filePath != null) {
                receivedFilesCount++;

                File file = new File(filePath);
                Uri fileUri = FileProvider.getUriForFile(MainActivity.this,
                        getPackageName() + ".fileprovider", file);

                FileItem item = new FileItem(fileName, filePath, fileSize, fileUri.toString());
                managedFiles.add(0, item);
                fileAdapter.addFile(item);
                updateFileListUI();
                updateConnectionStatus();

                Toast.makeText(MainActivity.this, "Received: " + fileName, Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Generate auth token for this session
        authToken = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        initializeViews();
        setupClickListeners();
        checkStoragePermission();
        updateConnectionStatus();

        LocalBroadcastManager.getInstance(this).registerReceiver(
                fileUploadReceiver, new IntentFilter("FILE_UPLOADED"));

        // Initialize device discovery
        discoveryManager = new DeviceDiscoveryManager(this);
        discoveryManager.setListener(new DeviceDiscoveryManager.OnDeviceListener() {
            @Override
            public void onDeviceFound(DeviceDiscoveryManager.DiscoveredDevice device) {
                runOnUiThread(() -> {
                    // Filter out our own device — mDNS discovers our own registered service too
                    String myIp = NetworkUtils.getLocalIpAddress(MainActivity.this);
                    if (device.host.equals(myIp) && device.port == SERVER_PORT) {
                        Log.d(TAG, "Skipping self-discovery: " + device.host + ":" + device.port);
                        return;
                    }

                    // Avoid duplicates
                    for (DeviceDiscoveryManager.DiscoveredDevice existing : discoveredDevices) {
                        if (existing.host.equals(device.host) && existing.port == device.port) {
                            return;
                        }
                    }
                    discoveredDevices.add(device);
                    deviceDisplayNames.add(device.name + " (" + device.host + ":" + device.port + ")");
                    deviceListAdapter.notifyDataSetChanged();
                    updateDeviceListUI();
                });
            }

            @Override
            public void onDeviceLost(DeviceDiscoveryManager.DiscoveredDevice device) {
                runOnUiThread(() -> {
                    for (int i = 0; i < discoveredDevices.size(); i++) {
                        DeviceDiscoveryManager.DiscoveredDevice existing = discoveredDevices.get(i);
                        if (existing.serviceName.equals(device.serviceName)) {
                            discoveredDevices.remove(i);
                            deviceDisplayNames.remove(i);
                            break;
                        }
                    }
                    deviceListAdapter.notifyDataSetChanged();
                    updateDeviceListUI();
                });
            }

            @Override
            public void onServiceRegistered(String name) {
                runOnUiThread(() -> Log.d(TAG, "Service registered: " + name));
            }

            @Override
            public void onServiceRegistrationFailed(String name, int errorCode) {
                runOnUiThread(() -> Log.e(TAG, "Service registration failed: " + name + " code=" + errorCode));
            }

            @Override
            public void onDiscoveryStopped() {
                runOnUiThread(() -> Log.d(TAG, "Discovery stopped"));
            }
        });

        // Initialize share request receiver
        shareRequestReceiver = new ShareRequestReceiver(this);
        shareRequestReceiver.register();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(fileUploadReceiver);
        stopMirror();
        if (discoveryManager != null) {
            discoveryManager.shutdown();
        }
        if (shareRequestReceiver != null) {
            shareRequestReceiver.unregister();
            shareRequestReceiver.shutdown();
        }
    }

    private void initializeViews() {
        btnSelectFiles = findViewById(R.id.btn_select_files);
        btnPickImage = findViewById(R.id.btn_select_images);
        btnImageToPdf = findViewById(R.id.btn_image_to_pdf);
        btnSelectFolder = findViewById(R.id.btn_select_folder);
        btnHotspotGuide = findViewById(R.id.btn_hotspot_guide);
        btnStartServer = findViewById(R.id.btn_start_server);
        btnStartMirror = findViewById(R.id.btn_start_mirror);

        // New buttons
        btnTextToPdf = findViewById(R.id.btn_text_to_pdf);
        btnDiscoverDevices = findViewById(R.id.btn_discover_devices);
        btnSendToDevices = findViewById(R.id.btn_send_to_devices);

        tvServerUrl = findViewById(R.id.tv_server_url);
        tvConnectionStatus = findViewById(R.id.tv_connection_status);
        tvFileCount = findViewById(R.id.tv_file_count);
        tvAuthToken = findViewById(R.id.tv_auth_token);
        tvQrHint = findViewById(R.id.tv_qr_hint);
        ivQr = findViewById(R.id.iv_qr);
        layoutAuthToken = findViewById(R.id.layout_auth_token);
        indicatorStatus = findViewById(R.id.indicator_status);
        cardQr = findViewById(R.id.card_qr);
        cardFiles = findViewById(R.id.card_files);
        cardDevices = findViewById(R.id.card_devices);
        emptyState = findViewById(R.id.empty_state);
        recyclerViewFiles = findViewById(R.id.recycler_view_files);
        listViewDevices = findViewById(R.id.list_view_devices);
        layoutDeviceButtons = findViewById(R.id.layout_device_buttons);

        cardQr.setVisibility(MaterialCardView.GONE);
        tvServerUrl.setVisibility(TextView.GONE);
        if (cardDevices != null) cardDevices.setVisibility(MaterialCardView.GONE);

        // Show auth token badge
        if (layoutAuthToken != null) {
            layoutAuthToken.setVisibility(View.VISIBLE);
        }
        if (tvAuthToken != null) {
            tvAuthToken.setText(authToken);
        }

        recyclerViewFiles.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewFiles.setNestedScrollingEnabled(false);
        fileAdapter = new FileListAdapter(this);
        recyclerViewFiles.setAdapter(fileAdapter);

        // Device list adapter
        deviceListAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, deviceDisplayNames);
        if (listViewDevices != null) {
            listViewDevices.setAdapter(deviceListAdapter);
            listViewDevices.setOnItemClickListener((parent, view, position, id) -> {
                if (position < discoveredDevices.size()) {
                    DeviceDiscoveryManager.DiscoveredDevice device = discoveredDevices.get(position);
                    sendShareRequest(device);
                }
            });
        }
    }

    private void setupClickListeners() {
        btnSelectFiles.setOnClickListener(v -> pickFiles());
        btnPickImage.setOnClickListener(v -> pickImages());
        btnImageToPdf.setOnClickListener(v -> convertSelectedImagesToPdf());
        btnSelectFolder.setOnClickListener(v -> pickFolder());
        btnHotspotGuide.setOnClickListener(v -> showHotspotGuide());
        btnStartServer.setOnClickListener(v -> startFileServer());
        btnStartMirror.setOnClickListener(v -> toggleMirror());

        // New: Text to PDF
        if (btnTextToPdf != null) {
            btnTextToPdf.setOnClickListener(v -> pickTextFileForPdf());
        }

        // New: Discover devices
        if (btnDiscoverDevices != null) {
            btnDiscoverDevices.setOnClickListener(v -> toggleDeviceDiscovery());
        }

        // New: Send to device
        if (btnSendToDevices != null) {
            btnSendToDevices.setOnClickListener(v -> showSendToDeviceDialog());
        }

        tvServerUrl.setOnClickListener(v -> {
            if (tvServerUrl.getText() != null) {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Server URL", tvServerUrl.getText().toString());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "URL Copied!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==========================================
    // FILE PICKING
    // ==========================================

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }

    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    private void pickFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, PICK_FOLDER_REQUEST);
        } else {
            Toast.makeText(this, "Folder sharing requires Android 5.0+", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickTextFileForPdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Also allow PDF, office docs
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/*", "application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        });
        startActivityForResult(intent, PICK_TEXT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        switch (requestCode) {
            case PICK_FILE_REQUEST:
                handleFilePick(data);
                break;
            case PICK_IMAGE_REQUEST:
                handleImagePick(data);
                break;
            case PICK_FOLDER_REQUEST:
                handleFolderPick(data);
                break;
            case PICK_TEXT_REQUEST:
                handleTextPickForPdf(data);
                break;
            case REQUEST_MEDIA_PROJECTION:
                if (resultCode == RESULT_OK && data != null) {
                    mediaProjectionResultCode = resultCode;
                    mediaProjectionResultData = data;
                    startMirror();
                } else {
                    Toast.makeText(this, "Screen mirror permission denied", Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }

    private void handleFilePick(Intent data) {
        selectedFiles.clear();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                selectedFiles.add(uri);
                addFileToList(uri);
            }
        } else if (data.getData() != null) {
            Uri uri = data.getData();
            selectedFiles.add(uri);
            addFileToList(uri);
        }
        Toast.makeText(this, "Selected " + selectedFiles.size() + " file(s)", Toast.LENGTH_SHORT).show();
        startFileServer();
    }

    private void handleImagePick(Intent data) {
        selectedImages.clear();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) {
                selectedImages.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            selectedImages.add(data.getData());
        }
        Toast.makeText(this, "Selected " + selectedImages.size() + " image(s)", Toast.LENGTH_SHORT).show();
    }

    private void handleFolderPick(Intent data) {
        Uri treeUri = data.getData();
        if (treeUri == null) return;

        getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);

        new Thread(() -> {
            try {
                String folderName = getFolderName(treeUri);
                File localFolder = new File(getFilesDir(), "shared_folders/" + folderName);
                if (!localFolder.exists()) localFolder.mkdirs();

                copyFolderFromSaf(treeUri, localFolder);

                selectedFolders.add(localFolder.getAbsolutePath());

                runOnUiThread(() -> {
                    FileItem folderItem = new FileItem(folderName, localFolder.getAbsolutePath(),
                            0, "", FileItem.Type.FOLDER, localFolder.getAbsolutePath());
                    managedFiles.add(0, folderItem);
                    fileAdapter.addFile(folderItem);
                    updateFileListUI();
                    Toast.makeText(this, "Folder added: " + folderName, Toast.LENGTH_SHORT).show();
                    startFileServer();
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error copying folder: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void handleTextPickForPdf(Intent data) {
        ArrayList<Uri> textUris = new ArrayList<>();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) {
                textUris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            textUris.add(data.getData());
        }
        if (!textUris.isEmpty()) {
            convertTextFilesToPdf(textUris);
        }
    }

    // ==========================================
    // TEXT / FILE TO PDF
    // ==========================================

    /**
     * Convert text files to PDF. Reads text content and renders it
     * as formatted pages in a PDF document.
     */
    private void convertTextFilesToPdf(ArrayList<Uri> textUris) {
        new Thread(() -> {
            try {
                android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();

                // A4 page dimensions in points
                final int PAGE_WIDTH = 595;
                final int PAGE_HEIGHT = 842;
                final int MARGIN = 40;
                final int FONT_SIZE = 10;
                final int LINE_HEIGHT = 14;
                final int CHARS_PER_LINE = 80;

                Paint paint = new Paint();
                paint.setTextSize(FONT_SIZE);
                paint.setColor(0xFF000000); // Black text
                paint.setAntiAlias(true);

                int pageNum = 0;

                for (Uri uri : textUris) {
                    String fileName = getFileNameFromUri(uri);

                    // Read text content
                    StringBuilder textContent = new StringBuilder();
                    try (InputStream is = getContentResolver().openInputStream(uri);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            textContent.append(line).append("\n");
                        }
                    } catch (Exception e) {
                        textContent.append("[Could not read file: ").append(fileName).append("]");
                    }

                    if (textContent.length() == 0) {
                        textContent.append("[Empty file: ").append(fileName).append("]");
                    }

                    // Add file header
                    String header = "=== " + fileName + " ===\n\n";
                    String fullText = header + textContent.toString();

                    // Split text into lines that fit the page width
                    ArrayList<String> lines = new ArrayList<>();
                    for (String rawLine : fullText.split("\n")) {
                        if (rawLine.isEmpty()) {
                            lines.add("");
                        } else {
                            // Word-wrap long lines
                            while (rawLine.length() > CHARS_PER_LINE) {
                                lines.add(rawLine.substring(0, CHARS_PER_LINE));
                                rawLine = rawLine.substring(CHARS_PER_LINE);
                            }
                            lines.add(rawLine);
                        }
                    }

                    // Render lines to PDF pages
                    int lineIdx = 0;
                    int linesPerPage = (PAGE_HEIGHT - 2 * MARGIN) / LINE_HEIGHT;

                    while (lineIdx < lines.size()) {
                        pageNum++;
                        android.graphics.pdf.PdfDocument.PageInfo info =
                                new android.graphics.pdf.PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create();
                        android.graphics.pdf.PdfDocument.Page page = pdf.startPage(info);
                        Canvas canvas = page.getCanvas();

                        // Draw file name as header on each page
                        Paint headerPaint = new Paint();
                        headerPaint.setTextSize(8);
                        headerPaint.setColor(0xFF888888);
                        canvas.drawText(fileName, MARGIN, MARGIN - 10, headerPaint);

                        // Draw text lines
                        int y = MARGIN + FONT_SIZE;
                        int linesThisPage = 0;
                        while (lineIdx < lines.size() && linesThisPage < linesPerPage) {
                            canvas.drawText(lines.get(lineIdx), MARGIN, y, paint);
                            y += LINE_HEIGHT;
                            lineIdx++;
                            linesThisPage++;
                        }

                        pdf.finishPage(page);
                    }

                    // Add a separator page between files (except after the last)
                    if (uri != textUris.get(textUris.size() - 1)) {
                        pageNum++;
                        android.graphics.pdf.PdfDocument.PageInfo sepInfo =
                                new android.graphics.pdf.PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create();
                        android.graphics.pdf.PdfDocument.Page sepPage = pdf.startPage(sepInfo);
                        Paint sepPaint = new Paint();
                        sepPaint.setTextSize(12);
                        sepPaint.setColor(0xFFCCCCCC);
                        sepPage.getCanvas().drawLine(MARGIN, PAGE_HEIGHT / 2, PAGE_WIDTH - MARGIN, PAGE_HEIGHT / 2, sepPaint);
                        pdf.finishPage(sepPage);
                    }
                }

                if (pageNum == 0) {
                    pdf.close();
                    runOnUiThread(() -> Toast.makeText(this, "No content to convert", Toast.LENGTH_SHORT).show());
                    return;
                }

                // Write PDF to file
                File pdfFile = new File(getFilesDir(), "document_" + System.currentTimeMillis() + ".pdf");
                try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
                    pdf.writeTo(fos);
                } finally {
                    pdf.close();
                }

                // Also save to Downloads
                savePdfToDownloads(pdfFile);

                Uri pdfUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", pdfFile);
                selectedFiles.add(pdfUri);

                FileItem item = new FileItem(pdfFile.getName(), pdfFile.getAbsolutePath(),
                        pdfFile.length(), pdfUri.toString());
                managedFiles.add(0, item);

                runOnUiThread(() -> {
                    fileAdapter.addFile(item);
                    updateFileListUI();
                    Toast.makeText(this, "PDF created from " + textUris.size() + " file(s)!", Toast.LENGTH_SHORT).show();
                    startFileServer();
                });

            } catch (Exception e) {
                Log.e(TAG, "Text to PDF error", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void savePdfToDownloads(File pdfFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, pdfFile.getName());
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileShare");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                ContentResolver resolver = getContentResolver();
                Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri itemUri = resolver.insert(collection, values);
                if (itemUri != null) {
                    try (OutputStream os = resolver.openOutputStream(itemUri);
                         java.io.FileInputStream fis = new java.io.FileInputStream(pdfFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = fis.read(buffer)) != -1) os.write(buffer, 0, len);
                    }
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(itemUri, values, null, null);
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File subDir = new File(dir, "FileShare");
                if (!subDir.exists()) subDir.mkdirs();
                File dest = new File(subDir, pdfFile.getName());
                try (java.io.FileInputStream fis = new java.io.FileInputStream(pdfFile);
                     FileOutputStream fos = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) != -1) fos.write(buffer, 0, len);
                }
                android.media.MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, null, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not save PDF to Downloads: " + e.getMessage());
        }
    }

    // ==========================================
    // IMAGE TO PDF
    // ==========================================

    private void convertSelectedImagesToPdf() {
        if (selectedImages.isEmpty()) {
            Toast.makeText(this, "Select images first!", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                File pdfFile = new File(getFilesDir(), "images_" + System.currentTimeMillis() + ".pdf");
                android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();

                final int A4_WIDTH = 595;
                final int A4_HEIGHT = 842;

                for (int i = 0; i < selectedImages.size(); i++) {
                    Uri uri = selectedImages.get(i);

                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(getContentResolver().openInputStream(uri), null, options);

                    int maxDim = Math.max(options.outWidth, options.outHeight);
                    options.inSampleSize = 1;
                    while (maxDim / options.inSampleSize > 2048) {
                        options.inSampleSize *= 2;
                    }
                    options.inJustDecodeBounds = false;

                    Bitmap bmp = BitmapFactory.decodeStream(getContentResolver().openInputStream(uri), null, options);
                    if (bmp == null) continue;

                    float scale = Math.min((float) A4_WIDTH / bmp.getWidth(), (float) A4_HEIGHT / bmp.getHeight());
                    int pageW = Math.max(1, Math.round(bmp.getWidth() * scale));
                    int pageH = Math.max(1, Math.round(bmp.getHeight() * scale));

                    android.graphics.pdf.PdfDocument.PageInfo info =
                            new android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create();
                    android.graphics.pdf.PdfDocument.Page page = pdf.startPage(info);
                    Canvas canvas = page.getCanvas();
                    canvas.drawBitmap(bmp, 0f, 0f, null);
                    pdf.finishPage(page);
                    bmp.recycle();
                }

                try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
                    pdf.writeTo(fos);
                } finally {
                    pdf.close();
                }

                Uri pdfUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", pdfFile);
                selectedFiles.add(pdfUri);

                FileItem item = new FileItem(pdfFile.getName(), pdfFile.getAbsolutePath(),
                        pdfFile.length(), pdfUri.toString());
                managedFiles.add(0, item);

                runOnUiThread(() -> {
                    fileAdapter.addFile(item);
                    updateFileListUI();
                    Toast.makeText(this, "PDF created!", Toast.LENGTH_SHORT).show();
                    startFileServer();
                });

            } catch (Exception e) {
                Log.e(TAG, "PDF conversion error", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ==========================================
    // DEVICE DISCOVERY
    // ==========================================

    private boolean isDiscovering = false;

    private void toggleDeviceDiscovery() {
        if (isDiscovering) {
            discoveryManager.stopDiscovery();
            discoveryManager.unregisterService();
            isDiscovering = false;
            btnDiscoverDevices.setText("Find Devices");
            Toast.makeText(this, "Discovery stopped", Toast.LENGTH_SHORT).show();
        } else {
            // Start discovery
            discoveryManager.startDiscovery();
            // Also register our service so others can find us
            String deviceName = Build.MODEL != null ? Build.MODEL : "FileShare";
            discoveryManager.registerService(SERVER_PORT, deviceName);
            isDiscovering = true;
            btnDiscoverDevices.setText("Stop Discovery");
            updateDeviceListUI();
            Toast.makeText(this, "Discovering devices...", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateDeviceListUI() {
        if (cardDevices == null) return;

        if (isDiscovering || !discoveredDevices.isEmpty()) {
            cardDevices.setVisibility(MaterialCardView.VISIBLE);

            if (btnSendToDevices != null) {
                btnSendToDevices.setEnabled(!discoveredDevices.isEmpty());
                btnSendToDevices.setAlpha(discoveredDevices.isEmpty() ? 0.5f : 1.0f);
            }
        } else {
            cardDevices.setVisibility(MaterialCardView.GONE);
        }
    }

    /**
     * Show a dialog to select a device to send a share request to.
     */
    private void showSendToDeviceDialog() {
        if (discoveredDevices.isEmpty()) {
            Toast.makeText(this, "No devices found. Tap 'Find Devices' first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[discoveredDevices.size()];
        for (int i = 0; i < discoveredDevices.size(); i++) {
            DeviceDiscoveryManager.DiscoveredDevice device = discoveredDevices.get(i);
            names[i] = device.name + "\n" + device.host + ":" + device.port;
        }

        new AlertDialog.Builder(this)
                .setTitle("Send To Device")
                .setItems(names, (dialog, which) -> {
                    sendShareRequest(discoveredDevices.get(which));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Send a share request to a discovered device.
     * The receiving device will show an allow/deny prompt.
     * If accepted, the receiving device's browser opens to our file server.
     */
    private void sendShareRequest(DeviceDiscoveryManager.DiscoveredDevice device) {
        if (managedFiles.isEmpty() && selectedFiles.isEmpty() && selectedFolders.isEmpty()) {
            Toast.makeText(this, "No files to share! Add files first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String myIp = NetworkUtils.getLocalIpAddress(this);
        String myName = Build.MODEL != null ? Build.MODEL : "FileShare";
        int fileCount = managedFiles.size();

        new Thread(() -> {
            try {
                String callbackUrl = "http://" + myIp + ":" + SERVER_PORT + "/share-response";
                String jsonBody = "{\"senderName\":\"" + myName.replace("\"", "\\\"") + "\"," +
                        "\"senderIp\":\"" + myIp + "\"," +
                        "\"senderPort\":" + SERVER_PORT + "," +
                        "\"fileCount\":" + fileCount + "," +
                        "\"callbackUrl\":\"" + callbackUrl + "\"}";

                URL url = new URL("http://" + device.host + ":" + device.port + "/share-request");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes("UTF-8"));
                }

                int responseCode = conn.getResponseCode();
                conn.disconnect();

                runOnUiThread(() -> {
                    if (responseCode == 200) {
                        Toast.makeText(this, "Share request sent to " + device.name + "! Waiting for response...", Toast.LENGTH_LONG).show();
                    } else if (responseCode == 503) {
                        Toast.makeText(this, device.name + " server not ready. Make sure file sharing is started on that device.", Toast.LENGTH_LONG).show();
                    } else if (responseCode == 404) {
                        Toast.makeText(this, device.name + " doesn't support file sharing. Is the app running?", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Error connecting to " + device.name + " (code " + responseCode + ")", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (java.net.ConnectException e) {
                runOnUiThread(() -> Toast.makeText(this, "Cannot reach " + device.name + ". Make sure the device is on the same network and file sharing is started.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to reach " + device.name + ": " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ==========================================
    // SERVER
    // ==========================================

    private void startFileServer() {
        if (selectedFiles.isEmpty() && receivedFilesCount == 0 && selectedFolders.isEmpty()) {
            Toast.makeText(this, "No files or folders to share", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> uris = new ArrayList<>();
        for (Uri u : selectedFiles) uris.add(u.toString());

        Intent intent = new Intent(this, FileServerService.class);
        intent.putStringArrayListExtra("files", uris);
        intent.putStringArrayListExtra("folders", selectedFolders);
        intent.putExtra("port", SERVER_PORT);
        intent.putExtra("authToken", authToken);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        updateUI(selectedFiles.size());

        // FIX: The share request route is now installed inside FileServerService.onStartCommand()
        // where the server is actually created. No need to do it here anymore.
        // FileServerService.getInstance() can be used if we need access later.
    }

    private FileServerService getFileServerService() {
        // FIX: Use static instance instead of always returning null
        return FileServerService.getInstance();
    }

    private void updateUI(int count) {
        String ip = NetworkUtils.getLocalIpAddress(this);

        if (ip.equals("0.0.0.0")) {
            tvConnectionStatus.setText("No network connection\nConnect to WiFi or enable Hotspot");
            cardQr.setVisibility(MaterialCardView.GONE);
            tvServerUrl.setVisibility(TextView.GONE);
            return;
        }

        String url = "http://" + ip + ":" + SERVER_PORT;
        if (!authToken.isEmpty()) {
            url += "?token=" + authToken;
        }

        cardQr.setVisibility(MaterialCardView.VISIBLE);
        tvServerUrl.setVisibility(TextView.VISIBLE);

        tvConnectionStatus.setText(
                "Server Running!\n" +
                        "Shared: " + count + " files | " + selectedFolders.size() + " folders\n" +
                        "Received: " + receivedFilesCount + " files\n" +
                        "Auth: " + authToken
        );
        tvServerUrl.setText(url + "  (Tap to copy)");

        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            ivQr.setImageBitmap(encoder.encodeBitmap(url, BarcodeFormat.QR_CODE, 240, 240));
        } catch (Exception e) {
            ivQr.setImageResource(android.R.drawable.ic_dialog_alert);
        }
    }

    private void updateConnectionStatus() {
        String ip = NetworkUtils.getLocalIpAddress(this);
        String status;

        if (ip.equals("0.0.0.0")) {
            status = "No network — connect to WiFi or enable Hotspot";
            if (indicatorStatus != null) {
                indicatorStatus.setBackgroundResource(R.drawable.status_dot_off);
            }
        } else {
            status = "IP: " + ip + ":" + SERVER_PORT + "  |  Auth: " + authToken;
            if (indicatorStatus != null) {
                indicatorStatus.setBackgroundResource(R.drawable.status_dot_on);
            }
        }

        tvConnectionStatus.setText(status);
    }

    // ==========================================
    // SCREEN MIRROR
    // ==========================================

    private void toggleMirror() {
        if (isMirroring) {
            stopMirror();
        } else {
            requestScreenCapture();
        }
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager != null) {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        }
    }

    private void startMirror() {
        Intent intent = new Intent(this, ScreenMirrorService.class);
        intent.putExtra("resultCode", mediaProjectionResultCode);
        intent.putExtra("resultData", mediaProjectionResultData);
        intent.putExtra("authToken", authToken);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        isMirroring = true;
        btnStartMirror.setText("Stop Mirror");
        btnStartMirror.setBackgroundColor(0xFFDC3545);

        String ip = NetworkUtils.getLocalIpAddress(this);
        String mirrorUrl = "http://" + ip + ":" + SERVER_PORT + "/mirror";
        if (!authToken.isEmpty()) mirrorUrl += "?token=" + authToken;

        new AlertDialog.Builder(this)
                .setTitle("Screen Mirror Active")
                .setMessage("Your screen is being shared!\n\n" +
                        "Open on your computer:\n" + mirrorUrl + "\n\n" +
                        "Stream: H.264 via WebSocket\n" +
                        "Fallback: JPEG HTTP polling")
                .setPositiveButton("Got it", null)
                .show();
    }

    private void stopMirror() {
        stopService(new Intent(this, ScreenMirrorService.class));
        isMirroring = false;
        btnStartMirror.setText("Screen Mirror");
        btnStartMirror.setBackgroundColor(0xFF667EEA);
    }

    // ==========================================
    // FILE ACTIONS
    // ==========================================

    @Override
    public void onPreview(FileItem fileItem) {
        if (!fileItem.isPreviewable()) {
            Toast.makeText(this, "Cannot preview this file type", Toast.LENGTH_SHORT).show();
            return;
        }

        // FIX: Handle empty/missing URI — was causing crash in FilePreviewActivity
        String uriString = fileItem.getFileUri();
        if (uriString == null || uriString.trim().isEmpty()) {
            Toast.makeText(this, "Cannot preview: file path is missing", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, FilePreviewActivity.class);
        intent.putExtra("fileUri", uriString);
        intent.putExtra("fileName", fileItem.getFileName());
        intent.putExtra("fileExtension", fileItem.getFileExtension());
        // FIX: Grant read permission so FilePreviewActivity can open the content:// URI
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    @Override
    public void onDownload(FileItem fileItem) {
        new Thread(() -> {
            try {
                Uri sourceUri = Uri.parse(fileItem.getFileUri());
                InputStream is = getContentResolver().openInputStream(sourceUri);
                if (is == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Cannot read file", Toast.LENGTH_SHORT).show());
                    return;
                }

                boolean saved = saveToDownloads(fileItem.getFileName(), is, fileItem.getMimeType());
                is.close();

                runOnUiThread(() -> {
                    if (saved) {
                        Toast.makeText(this, "Saved: " + fileItem.getFileName(), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private boolean saveToDownloads(String filename, InputStream data, String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileShare");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                ContentResolver resolver = getContentResolver();
                Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri itemUri = resolver.insert(collection, values);
                if (itemUri != null) {
                    try (OutputStream os = resolver.openOutputStream(itemUri)) {
                        if (os != null) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = data.read(buffer)) != -1) os.write(buffer, 0, len);
                        }
                    }
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(itemUri, values, null, null);
                    return true;
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && (dir.exists() || dir.mkdirs())) {
                    File subDir = new File(dir, "FileShare");
                    if (!subDir.exists()) subDir.mkdirs();
                    File file = new File(subDir, filename);
                    try (OutputStream os = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = data.read(buffer)) != -1) os.write(buffer, 0, len);
                    }
                    android.media.MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void onPrint(FileItem fileItem) {
        if (!fileItem.isImage() && !fileItem.isPdf() && !fileItem.isText()) {
            Toast.makeText(this, "Only Images, PDFs, and Text can be printed", Toast.LENGTH_SHORT).show();
            return;
        }
        android.webkit.WebView webView = new android.webkit.WebView(this);
        android.webkit.WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);

        android.webkit.WebViewClient wvc = new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                android.print.PrintManager printManager = (android.print.PrintManager) getSystemService(Context.PRINT_SERVICE);
                String jobName = fileItem.getFileName();
                android.print.PrintDocumentAdapter printAdapter = view.createPrintDocumentAdapter(jobName);
                printManager.print(jobName, printAdapter, new android.print.PrintAttributes.Builder().build());
            }
        };
        webView.setWebViewClient(wvc);

        if (fileItem.isImage()) {
            String html = "<html><body style='display:flex;justify-content:center;align-items:center;height:100vh;margin:0;'>" +
                    "<img src=\"" + fileItem.getFileUri() + "\" style='max-width:100%;max-height:100%;'></body></html>";
            webView.loadDataWithBaseURL("content://", html, "text/html", "UTF-8", null);
        } else {
            webView.loadUrl(fileItem.getFileUri());
        }
    }

    @Override
    public void onDelete(FileItem fileItem) {
        String path = fileItem.getFilePath();

        if (path.startsWith("/data") || path.startsWith(getFilesDir().getAbsolutePath())) {
            File file = new File(path);
            if (file.exists()) {
                if (fileItem.isFolder()) {
                    deleteRecursive(file);
                } else {
                    file.delete();
                }
            }
        }

        managedFiles.remove(fileItem);
        fileAdapter.removeFile(fileItem);
        updateFileListUI();

        Uri fileUri = Uri.parse(fileItem.getFileUri());
        selectedFiles.remove(fileUri);

        if (fileItem.isFolder()) {
            selectedFolders.remove(path);
        }

        Toast.makeText(this, "Removed: " + fileItem.getFileName(), Toast.LENGTH_SHORT).show();

        if (managedFiles.isEmpty()) {
            stopService(new Intent(this, FileServerService.class));
            cardQr.setVisibility(MaterialCardView.GONE);
            tvServerUrl.setVisibility(TextView.GONE);
            tvConnectionStatus.setText("Server stopped. No files to share.");
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    // ==========================================
    // UI HELPERS
    // ==========================================

    private void updateFileListUI() {
        if (managedFiles.isEmpty()) {
            cardFiles.setVisibility(MaterialCardView.GONE);
        } else {
            cardFiles.setVisibility(MaterialCardView.VISIBLE);
            tvFileCount.setText(managedFiles.size() + " item(s)");
            emptyState.setVisibility(View.GONE);
            recyclerViewFiles.setVisibility(View.VISIBLE);
        }
    }

    private void showHotspotGuide() {
        String ip = NetworkUtils.getLocalIpAddress(this);
        String url = "http://" + ip + ":" + SERVER_PORT;
        if (!authToken.isEmpty()) url += "?token=" + authToken;

        new AlertDialog.Builder(this)
                .setTitle("Connection Guide")
                .setMessage("To share files:\n\n" +
                        "1. Connect both devices to same WiFi\n" +
                        "   OR enable Mobile Hotspot on this phone\n\n" +
                        "2. On your computer, open browser and go to:\n" +
                        "   " + url + "\n\n" +
                        "3. Download files, upload files, or mirror your screen\n\n" +
                        "Auth token: " + authToken + "\n" +
                        "Screen mirror: " + url + "/mirror")
                .setPositiveButton("Open WiFi Settings", (d, w) ->
                        startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)))
                .setNegativeButton("Got it", null)
                .show();
    }

    private String getFileNameFromUri(Uri uri) {
        String name = "Unknown File";
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        } else if ("file".equals(uri.getScheme())) {
            name = uri.getLastPathSegment();
        }
        return name != null ? name : "Unknown File";
    }

    private long getFileSizeFromUri(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) return cursor.getLong(idx);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void addFileToList(Uri uri) {
        String fileName = getFileNameFromUri(uri);
        long fileSize = getFileSizeFromUri(uri);

        for (FileItem item : managedFiles) {
            if (item.getFileName().equals(fileName)) return;
        }

        FileItem item = new FileItem(fileName, uri.toString(), fileSize, uri.toString());
        managedFiles.add(0, item);
        fileAdapter.addFile(item);
        updateFileListUI();
    }

    private String getFolderName(Uri treeUri) {
        String path = treeUri.getPath();
        if (path != null) {
            int colon = path.lastIndexOf(':');
            if (colon >= 0) return path.substring(colon + 1);
            int slash = path.lastIndexOf('/');
            if (slash >= 0) return path.substring(slash + 1);
        }
        return "Folder_" + System.currentTimeMillis();
    }

    private void copyFolderFromSaf(Uri treeUri, File destDir) throws Exception {
        String treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
        Uri docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
        copyDocumentRecursive(treeUri, docUri, destDir);
    }

    private void copyDocumentRecursive(Uri treeUri, Uri docUri, File destDir) throws Exception {
        ContentResolver resolver = getContentResolver();

        String displayName = null;
        String mimeType = null;
        String documentId = null;

        try (Cursor cursor = resolver.query(docUri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                displayName = cursor.getString(cursor.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                mimeType = cursor.getString(cursor.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE));
                documentId = cursor.getString(cursor.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID));
            }
        }

        if (displayName == null || mimeType == null || documentId == null) return;

        if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            File subDir = new File(destDir, displayName);
            if (!subDir.exists()) subDir.mkdirs();

            Uri childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
            try (Cursor cursor = resolver.query(childrenUri, null, null, null, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String childId = cursor.getString(cursor.getColumnIndex(
                                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                        Uri childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                        copyDocumentRecursive(treeUri, childUri, subDir);
                    }
                }
            }
        } else {
            File destFile = new File(destDir, displayName);
            try (InputStream is = resolver.openInputStream(docUri);
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
        }
    }

    // ==========================================
    // PERMISSIONS
    // ==========================================

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] permissions = {
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO
            };
            boolean needPermission = false;
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    needPermission = true;
                    break;
                }
            }
            if (needPermission) {
                ActivityCompat.requestPermissions(this, permissions, REQUEST_STORAGE_PERMISSION);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                new AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("Storage permission is needed to save uploaded files.")
                        .setPositiveButton("OK", null)
                        .setNegativeButton("Grant Permission", (d, w) -> checkStoragePermission())
                        .show();
            }
        }
    }
}
