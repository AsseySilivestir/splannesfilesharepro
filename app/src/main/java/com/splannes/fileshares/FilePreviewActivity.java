package com.splannes.fileshares;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * File preview activity supporting images, video, audio, PDF, and text.
 *
 * Crash fixes:
 * - Null-safe view access (layout ID mismatch won't crash)
 * - Handles file:// URIs by converting to content:// via FileProvider
 * - Handles empty/invalid URI strings gracefully
 * - SecurityException caught when opening content:// URIs
 * - All loader methods have comprehensive try-catch
 * - MediaPlayer lifecycle hardened
 * - PdfRenderer parcel file descriptor properly closed
 */
public class FilePreviewActivity extends AppCompatActivity {

    private static final String TAG = "FilePreviewActivity";
    private static final int MAX_TEXT_SIZE = 500_000; // ~500KB preview limit

    private ImageView imageView;
    private VideoView videoView;
    private LinearLayout audioContainer;
    private WebView pdfWebView;
    private LinearLayout textContainer;
    private TextView textViewer;
    private LinearLayout unsupportedContainer;
    private TextView tvUnsupportedInfo;
    private ProgressBar progressBar;
    private TextView tvTitle;
    private TextView tvAudioTitle;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private SeekBar seekBar;
    private ImageButton btnPlayPause;
    private ImageButton btnRewind;
    private ImageButton btnForward;
    private ImageButton btnBack;
    private ImageButton btnShare;

    private MediaPlayer mediaPlayer;
    private Handler handler;
    private boolean isDestroyed = false;
    private boolean wasVideoPlaying = false;
    private Uri fileUri;
    private String fileName;
    private String fileExtension;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_file_preview);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set content view", e);
            Toast.makeText(this, "Error loading preview layout", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initViews();
        handleIntent();
    }

    /**
     * Initialize views with null-safety.
     * If a view ID doesn't exist in the layout, we just skip it instead of crashing.
     */
    private void initViews() {
        imageView = findViewById(R.id.imageView);
        videoView = findViewById(R.id.videoView);
        audioContainer = findViewById(R.id.audioContainer);
        pdfWebView = findViewById(R.id.pdfWebView);
        textContainer = findViewById(R.id.textContainer);
        textViewer = findViewById(R.id.textViewer);
        unsupportedContainer = findViewById(R.id.unsupportedContainer);
        tvUnsupportedInfo = findViewById(R.id.tvUnsupportedInfo);
        progressBar = findViewById(R.id.progressBar);
        tvTitle = findViewById(R.id.tvTitle);
        tvAudioTitle = findViewById(R.id.tvAudioTitle);
        tvCurrentTime = findViewById(R.id.tvCurrentTime);
        tvTotalTime = findViewById(R.id.tvTotalTime);
        seekBar = findViewById(R.id.seekBar);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnRewind = findViewById(R.id.btnRewind);
        btnForward = findViewById(R.id.btnForward);
        btnBack = findViewById(R.id.btnBack);
        btnShare = findViewById(R.id.btnShare);

        handler = new Handler();

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareFile());
        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());

        if (btnRewind != null) {
            btnRewind.setOnClickListener(v -> {
                if (mediaPlayer != null) {
                    try {
                        int newPos = Math.max(0, mediaPlayer.getCurrentPosition() - 10000);
                        mediaPlayer.seekTo(newPos);
                    } catch (IllegalStateException ignored) {}
                }
            });
        }

        if (btnForward != null) {
            btnForward.setOnClickListener(v -> {
                if (mediaPlayer != null) {
                    try {
                        int newPos = Math.min(mediaPlayer.getDuration(), mediaPlayer.getCurrentPosition() + 10000);
                        mediaPlayer.seekTo(newPos);
                    } catch (IllegalStateException ignored) {}
                }
            });
        }

        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && mediaPlayer != null) {
                        try {
                            mediaPlayer.seekTo(progress);
                        } catch (IllegalStateException ignored) {}
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {
                    if (handler != null) handler.removeCallbacks(updateRunnable);
                }
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    updateSeekBar();
                }
            });
        }
    }

    private void handleIntent() {
        Intent intent = getIntent();
        if (intent == null) { finish(); return; }

        String uriString = intent.getStringExtra("fileUri");
        fileName = intent.getStringExtra("fileName");
        fileExtension = intent.getStringExtra("fileExtension");

        // Case-insensitive extension
        if (fileExtension != null) fileExtension = fileExtension.toLowerCase();

        // FIX: Handle empty or null URI
        if (uriString == null || uriString.trim().isEmpty()) {
            Log.e(TAG, "fileUri is null or empty, cannot preview");
            Toast.makeText(this, "Cannot preview: file path is missing", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // FIX: Handle file:// URIs by converting to content:// via FileProvider
        // ContentResolver.openInputStream() fails with file:// on Android 7+
        try {
            fileUri = Uri.parse(uriString);
            if ("file".equals(fileUri.getScheme())) {
                File file = new File(fileUri.getPath());
                if (file.exists()) {
                    try {
                        fileUri = FileProvider.getUriForFile(this,
                                getPackageName() + ".fileprovider", file);
                        Log.d(TAG, "Converted file:// to content://: " + fileUri);
                    } catch (Exception e) {
                        Log.w(TAG, "FileProvider conversion failed, trying direct file access", e);
                        // Keep the original file:// URI, some methods still work with it
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse URI: " + uriString, e);
            Toast.makeText(this, "Invalid file path", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // FIX: Null-safe title update
        if (tvTitle != null) {
            tvTitle.setText(fileName != null ? fileName : "Preview");
        }

        hideAllViews();
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

        try {
            if (isImageExtension(fileExtension)) loadImage();
            else if (isVideoExtension(fileExtension)) loadVideo();
            else if (isAudioExtension(fileExtension)) loadAudio();
            else if (isPdfExtension(fileExtension)) loadPdfWithRenderer();
            else if (isTextExtension(fileExtension)) loadText();
            else showUnsupported();
        } catch (Exception e) {
            Log.e(TAG, "Error loading file", e);
            showError("Error: " + e.getMessage());
        }
    }

    private void hideAllViews() {
        if (imageView != null) imageView.setVisibility(View.GONE);
        if (videoView != null) videoView.setVisibility(View.GONE);
        if (audioContainer != null) audioContainer.setVisibility(View.GONE);
        if (pdfWebView != null) pdfWebView.setVisibility(View.GONE);
        if (textContainer != null) textContainer.setVisibility(View.GONE);
        if (unsupportedContainer != null) unsupportedContainer.setVisibility(View.GONE);
    }

    // ==========================================
    // LOADERS
    // ==========================================

    private void loadImage() {
        new Thread(() -> {
            try {
                ContentResolver resolver = getContentResolver();

                // Two-pass decode to prevent OOM
                // Pass 1: Get dimensions only
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                InputStream is = resolver.openInputStream(fileUri);
                if (is == null) {
                    runOnUiThread(() -> showError("Cannot open file"));
                    return;
                }
                BitmapFactory.decodeStream(is, null, options);
                is.close();

                // Calculate inSampleSize to fit screen
                DisplayMetrics metrics = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(metrics);
                int targetW = metrics.widthPixels;
                int targetH = metrics.heightPixels;

                options.inSampleSize = calculateInSampleSize(options, targetW, targetH);
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.RGB_565;

                // Pass 2: Decode at reduced size
                is = resolver.openInputStream(fileUri);
                if (is == null) {
                    runOnUiThread(() -> showError("Cannot open file"));
                    return;
                }
                Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
                is.close();

                if (!isDestroyed) {
                    runOnUiThread(() -> {
                        if (bitmap != null && imageView != null) {
                            imageView.setImageBitmap(bitmap);
                            imageView.setVisibility(View.VISIBLE);
                        } else {
                            showError("Failed to decode image");
                        }
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                    });
                }

            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException opening image", e);
                runOnUiThread(() -> showError("Permission denied — cannot read this file"));
            } catch (Exception e) {
                Log.e(TAG, "Image load error", e);
                runOnUiThread(() -> showError(e.getMessage()));
            }
        }).start();
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqW, int reqH) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqH || width > reqW) {
            final int halfH = height / 2;
            final int halfW = width / 2;
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void loadVideo() {
        try {
            if (videoView == null) {
                showError("Video player not available");
                return;
            }
            videoView.setVideoURI(fileUri);
            videoView.setVisibility(View.VISIBLE);

            videoView.setOnPreparedListener(mp -> {
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                mp.start();
            });

            videoView.setOnErrorListener((mp, what, extra) -> {
                showError("Video playback error");
                return true;
            });

        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    private void loadAudio() {
        try {
            if (audioContainer == null) {
                showError("Audio player not available");
                return;
            }
            if (tvAudioTitle != null) tvAudioTitle.setText(fileName);
            audioContainer.setVisibility(View.VISIBLE);
            if (progressBar != null) progressBar.setVisibility(View.GONE);

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, fileUri);
            mediaPlayer.prepareAsync();

            mediaPlayer.setOnPreparedListener(mp -> {
                if (seekBar != null) seekBar.setMax(mp.getDuration());
                if (tvTotalTime != null) tvTotalTime.setText(formatTime(mp.getDuration()));
                if (tvCurrentTime != null) tvCurrentTime.setText("00:00");
                if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
                if (handler != null) handler.removeCallbacks(updateRunnable);
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                try { mp.release(); } catch (Exception ignored) {}
                mediaPlayer = null;
                showError("Audio playback error");
                return true;
            });

        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    /**
     * PDF rendering using PdfRenderer (offline, no Google Docs leak).
     * Falls back to WebView if PdfRenderer fails.
     */
    private void loadPdfWithRenderer() {
        new Thread(() -> {
            ParcelFileDescriptor pfd = null;
            PdfRenderer renderer = null;
            PdfRenderer.Page page = null;
            try {
                pfd = getContentResolver().openFileDescriptor(fileUri, "r");
                if (pfd == null) {
                    runOnUiThread(() -> showError("Cannot open PDF"));
                    return;
                }

                renderer = new PdfRenderer(pfd);

                // Render first page
                page = renderer.openPage(0);

                // Scale to fit screen
                DisplayMetrics metrics = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(metrics);
                int targetW = metrics.widthPixels;
                float scale = (float) targetW / page.getWidth();
                int bmpW = Math.round(page.getWidth() * scale);
                int bmpH = Math.round(page.getHeight() * scale);

                // Prevent 0-size bitmap crash
                bmpW = Math.max(1, bmpW);
                bmpH = Math.max(1, bmpH);

                Bitmap bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                final Bitmap finalBitmap = bitmap;
                final int pageCount = renderer.getPageCount();

                if (!isDestroyed) {
                    runOnUiThread(() -> {
                        if (imageView != null) {
                            imageView.setImageBitmap(finalBitmap);
                            imageView.setVisibility(View.VISIBLE);
                        }
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        if (pageCount > 1) {
                            Toast.makeText(this, "Page 1 of " + pageCount + " (first page preview)", Toast.LENGTH_LONG).show();
                        }
                    });
                }

            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException opening PDF", e);
                runOnUiThread(() -> showError("Permission denied — cannot read this PDF"));
            } catch (Exception e) {
                Log.e(TAG, "PDF render error, trying fallback", e);
                runOnUiThread(this::loadPdfFallback);
            } finally {
                // FIX: Properly close all resources to prevent leaks and crashes
                if (page != null) page.close();
                if (renderer != null) renderer.close();
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void loadPdfFallback() {
        try {
            if (pdfWebView == null) {
                showError("PDF viewer not available");
                return;
            }
            pdfWebView.setVisibility(View.VISIBLE);
            WebSettings settings = pdfWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(true);

            pdfWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                }
            });

            // For content:// URIs, WebView needs a special approach
            String url = fileUri.toString();
            if ("content".equals(fileUri.getScheme())) {
                // Try loading via file:// if it exists locally, otherwise just load the content URI
                pdfWebView.loadUrl(url);
            } else {
                pdfWebView.loadUrl(url);
            }
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    private void loadText() {
        new Thread(() -> {
            try {
                ContentResolver resolver = getContentResolver();
                InputStream is = resolver.openInputStream(fileUri);
                if (is == null) {
                    runOnUiThread(() -> showError("Cannot open file"));
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                int totalChars = 0;

                while ((line = reader.readLine()) != null) {
                    totalChars += line.length() + 1;
                    if (totalChars > MAX_TEXT_SIZE) {
                        sb.append("\n\n--- File too large. Showing first ~500KB ---");
                        break;
                    }
                    sb.append(line).append("\n");
                }
                reader.close();
                is.close();

                final String textContent = sb.toString();
                if (!isDestroyed) {
                    runOnUiThread(() -> {
                        if (textViewer != null) textViewer.setText(textContent);
                        if (textContainer != null) textContainer.setVisibility(View.VISIBLE);
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                    });
                }

            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException opening text file", e);
                runOnUiThread(() -> showError("Permission denied — cannot read this file"));
            } catch (Exception e) {
                Log.e(TAG, "Text load error", e);
                runOnUiThread(() -> showError(e.getMessage()));
            }
        }).start();
    }

    // ==========================================
    // PLAYBACK CONTROLS
    // ==========================================

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_play);
                if (handler != null) handler.removeCallbacks(updateRunnable);
            } else {
                mediaPlayer.start();
                if (btnPlayPause != null) btnPlayPause.setImageResource(R.drawable.ic_pause);
                updateSeekBar();
            }
        } catch (IllegalStateException e) {
            showError("Player error");
        }
    }

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed || mediaPlayer == null) return;
            try {
                if (mediaPlayer.isPlaying()) {
                    int pos = mediaPlayer.getCurrentPosition();
                    if (seekBar != null) seekBar.setProgress(pos);
                    if (tvCurrentTime != null) tvCurrentTime.setText(formatTime(pos));
                    if (handler != null) handler.postDelayed(this, 100);
                }
            } catch (IllegalStateException ignored) {}
        }
    };

    private void updateSeekBar() {
        if (handler != null) handler.post(updateRunnable);
    }

    private String formatTime(long millis) {
        int hours = (int) TimeUnit.MILLISECONDS.toHours(millis);
        int minutes = (int) (TimeUnit.MILLISECONDS.toMinutes(millis) % 60);
        int seconds = (int) (TimeUnit.MILLISECONDS.toSeconds(millis) % 60);
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    // ==========================================
    // SHARING
    // ==========================================

    private void shareFile() {
        try {
            if (fileUri == null) {
                Toast.makeText(this, "No file to share", Toast.LENGTH_SHORT).show();
                return;
            }

            // Ensure URI is content:// (not file:// which crashes on Android 7+)
            Uri shareUri = fileUri;
            if ("file".equals(fileUri.getScheme())) {
                File file = new File(fileUri.getPath());
                shareUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            }

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(new FileItem(fileName != null ? fileName : "", "", 0, "").getMimeType());
            shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share " + (fileName != null ? fileName : "file")));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot share this file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private void showUnsupported() {
        if (tvUnsupportedInfo != null) {
            String ext = (fileExtension != null && !fileExtension.isEmpty()) ? fileExtension : "Unknown";
            tvUnsupportedInfo.setText("." + ext + " files cannot be previewed\nTry downloading to open with another app");
        }
        if (unsupportedContainer != null) unsupportedContainer.setVisibility(View.VISIBLE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    private void showError(String message) {
        if (isDestroyed) return; // Don't touch UI if activity is destroyed
        if (tvUnsupportedInfo != null) {
            tvUnsupportedInfo.setText("Error: " + (message != null ? message : "Unknown error"));
        }
        if (unsupportedContainer != null) unsupportedContainer.setVisibility(View.VISIBLE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    private boolean isImageExtension(String ext) {
        return ext != null && ext.matches("jpg|jpeg|png|gif|bmp|webp|svg");
    }
    private boolean isVideoExtension(String ext) {
        return ext != null && ext.matches("mp4|avi|mkv|mov|3gp|webm");
    }
    private boolean isAudioExtension(String ext) {
        return ext != null && ext.matches("mp3|wav|ogg|flac|aac|m4a");
    }
    private boolean isPdfExtension(String ext) {
        return "pdf".equals(ext);
    }
    private boolean isTextExtension(String ext) {
        return ext != null && ext.matches("txt|csv|xml|json|html|htm|log|md|java|c|cpp|py|js|css|ts|sh|yaml|yml|sql|gradle|kt|go|rs|rb|php");
    }

    // ==========================================
    // LIFECYCLE
    // ==========================================

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) {
            wasVideoPlaying = true;
            videoView.pause();
        } else {
            wasVideoPlaying = false;
        }
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            } catch (IllegalStateException ignored) {}
            if (handler != null) handler.removeCallbacks(updateRunnable);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoView != null && wasVideoPlaying) {
            videoView.resume();
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed = true;
        if (handler != null) handler.removeCallbacks(updateRunnable);

        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (pdfWebView != null) {
            try { pdfWebView.destroy(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
