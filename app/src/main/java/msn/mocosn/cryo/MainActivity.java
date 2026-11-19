package msn.mocosn.cryo;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebView;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements UpdateHelper.OnUpdateCheckListerner {

    // Views for navigation pages
    private View pageHome;
    private View pageHistory;
    private View pageAbout;

    // Floating pill bottom navigation views
    private View navItemHome, navItemHistory, navItemAbout;
    private View navPillHome, navPillHistory, navPillAbout;
    private ImageView navIconHome, navIconHistory, navIconAbout;
    private TextView navTextHome, navTextHistory, navTextAbout;

    // Home Downloader views
    private LinearLayout panel;
    private MaterialCardView previewLayout;
    private LinearLayout progressLayout;
    private TextView optionLabel;
    private Spinner optionSpinner;
    private ImageView videoThumbnail;
    private WebView videoPreview;
    private TextView videoTitle, progressStatus;
    private MaterialButton fetchInfoBtn, downloadBtn;
    private TextInputEditText urlInput;
    private LinearProgressIndicator downloadProgressBar;
    private RadioGroup formatGroup;

    // Quality options
    private String[] videoResolutions = new String[]{
            "highest (Best Available)",
            "4320p (8K Ultra HD)",
            "2160p (4K Ultra HD)",
            "1440p (2K Quad HD)",
            "1080p (Full HD)",
            "720p (HD)",
            "480p",
            "360p",
            "240p",
            "144p"
    };
    private final String[] audioBitrates = new String[]{
            "320 kbps (High Quality)",
            "256 kbps (Very Good)",
            "192 kbps (Standard)",
            "128 kbps (Medium)",
            "64 kbps (Low)"
    };

    // History views
    private RecyclerView historyRecyclerView;
    private View emptyHistoryView;
    private MaterialButton btnRefreshHistory;
    private TextView txtStoragePath;
    private DownloadHistoryAdapter historyAdapter;
    private final List<DownloadItem> historyItems = new ArrayList<>();

    // About Us views
    private TextView aboutAppVersion;
    private MaterialButton btnCheckUpdate;
    private MaterialButton profile, github, linkedin, instagram, website;

    private PyObject pyModule;

    private static final int STORAGE_PERMISSION_CODE = 101;
    private static final int NOTIFICATION_PERMISSION_CODE = 102;
    private static final String CHANNEL_ID = "yt_download_channel";
    private static final int NOTIFICATION_ID = 2001;
    private static final int NOTIFICATION_COMPLETE_ID = 2002;
    public static final String ACTION_SHOW_PROGRESS = "msn.mocosn.cryo.ACTION_SHOW_PROGRESS";

    // Download Progress Dialog & Notification Views/State
    private AlertDialog downloadProgressDialog;
    private LinearProgressIndicator dialogProgressBar;
    private TextView dialogProgressPercent;
    private TextView dialogProgressStatus;
    private TextView dialogVideoTitle;
    private TextView dialogFormatBadge;
    private MaterialButton btnHideProgress;

    private boolean isDownloading = false;
    private boolean isProgressHidden = false;
    private int currentDownloadProgress = 0;
    private String currentDownloadTitle = "Downloading...";
    private String currentDownloadFormat = "Video (MP4)";

    // Interface for Python progress update
    public interface DownloadCallback {
        void onProgress(int progress);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UpdateHelper.with(this)
                .onUpdateCheck((newVersion, urlApp, whatsNew, isForce) -> {
                    UpdateDialog.show(this, urlApp, newVersion, whatsNew, isForce);
                })
                .check();

        setContentView(R.layout.activity_main);

        // Check manage storage permission for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:" + getPackageName())));
        }

        // Initialize Python / Chaquopy
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        Python py = Python.getInstance();
        pyModule = py.getModule("app");

        initFloatingNavigation();
        initHomeViews(py);
        initHistoryViews();
        initAboutViews();

        initNotificationChannel();
        checkAndRequestNotificationPermission();

        // Process incoming share or notification intent
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        // 1. Notification tap: re-open download progress dialog
        if (ACTION_SHOW_PROGRESS.equals(action)) {
            if (isDownloading) {
                isProgressHidden = false;
                showDownloadProgressDialog();
            }
            return;
        }

        // 2. Share from YouTube or another app (ACTION_SEND)
        if (Intent.ACTION_SEND.equals(action)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText == null) {
                CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (cs != null) sharedText = cs.toString();
            }
            processSharedInput(sharedText);
            return;
        }

        // 3. Direct YouTube Link tap (ACTION_VIEW)
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            processSharedInput(intent.getData().toString());
        }
    }

    private void processSharedInput(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String cleanUrl = extractYouTubeUrlFromSharedText(text);
        if (!cleanUrl.isEmpty()) {
            setActiveTab(0); // Switch to Home tab
            if (urlInput != null) {
                urlInput.setText(cleanUrl);
                urlInput.setSelection(cleanUrl.length());
            }
            Toast.makeText(this, "YouTube link shared! Fetching info...", Toast.LENGTH_SHORT).show();
            fetchVideoInformation(cleanUrl);
        }
    }

    private String extractYouTubeUrlFromSharedText(String text) {
        if (text == null) return "";
        // Match youtube.com or youtu.be link inside any text
        Pattern pattern = Pattern.compile(
                "(https?://(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?[^\\s]*v=|shorts/|embed/)|youtu\\.be/)[a-zA-Z0-9_-]{11}[^\\s]*)"
        );
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // Fallback: look for any http(s) URL in text
        Pattern genericUrl = Pattern.compile("(https?://[^\\s]+)");
        Matcher genMatcher = genericUrl.matcher(text);
        if (genMatcher.find()) {
            return genMatcher.group(1);
        }

        return text.trim();
    }

    private void initFloatingNavigation() {
        pageHome = findViewById(R.id.pageHome);
        pageHistory = findViewById(R.id.pageHistory);
        pageAbout = findViewById(R.id.pageAbout);

        navItemHome = findViewById(R.id.navItemHome);
        navItemHistory = findViewById(R.id.navItemHistory);
        navItemAbout = findViewById(R.id.navItemAbout);

        navPillHome = findViewById(R.id.navPillHome);
        navPillHistory = findViewById(R.id.navPillHistory);
        navPillAbout = findViewById(R.id.navPillAbout);

        navIconHome = findViewById(R.id.navIconHome);
        navIconHistory = findViewById(R.id.navIconHistory);
        navIconAbout = findViewById(R.id.navIconAbout);

        navTextHome = findViewById(R.id.navTextHome);
        navTextHistory = findViewById(R.id.navTextHistory);
        navTextAbout = findViewById(R.id.navTextAbout);

        navItemHome.setOnClickListener(v -> setActiveTab(0));
        navItemHistory.setOnClickListener(v -> setActiveTab(1));
        navItemAbout.setOnClickListener(v -> setActiveTab(2));

        // Default to Home tab
        setActiveTab(0);
    }

    private void setActiveTab(int index) {
        final int activeColor = Color.parseColor("#0F2B48");
        final int inactiveColor = Color.parseColor("#5E6C62");

        // Tab 0: Home
        navPillHome.setBackgroundResource(index == 0 ? R.drawable.bg_nav_active_pill : 0);
        navIconHome.setColorFilter(index == 0 ? activeColor : inactiveColor);
        navTextHome.setTextColor(index == 0 ? activeColor : inactiveColor);
        navTextHome.setTypeface(null, index == 0 ? Typeface.BOLD : Typeface.NORMAL);

        // Tab 1: History
        navPillHistory.setBackgroundResource(index == 1 ? R.drawable.bg_nav_active_pill : 0);
        navIconHistory.setColorFilter(index == 1 ? activeColor : inactiveColor);
        navTextHistory.setTextColor(index == 1 ? activeColor : inactiveColor);
        navTextHistory.setTypeface(null, index == 1 ? Typeface.BOLD : Typeface.NORMAL);

        // Tab 2: About Us
        navPillAbout.setBackgroundResource(index == 2 ? R.drawable.bg_nav_active_pill : 0);
        navIconAbout.setColorFilter(index == 2 ? activeColor : inactiveColor);
        navTextAbout.setTextColor(index == 2 ? activeColor : inactiveColor);
        navTextAbout.setTypeface(null, index == 2 ? Typeface.BOLD : Typeface.NORMAL);

        pageHome.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        pageHistory.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        if (index == 1) {
            loadDownloadHistory();
        }
    }

    private void initHomeViews(Python py) {
        panel = findViewById(R.id.mainPanel);
        urlInput = findViewById(R.id.urlInput);
        fetchInfoBtn = findViewById(R.id.fetchResBtn);
        downloadBtn = findViewById(R.id.downloadBtn);
        optionLabel = findViewById(R.id.optionLabel);
        optionSpinner = findViewById(R.id.optionSpinner);
        previewLayout = findViewById(R.id.previewLayout);
        videoThumbnail = findViewById(R.id.videoThumbnail);
        videoPreview = findViewById(R.id.videoPreview);
        videoTitle = findViewById(R.id.videoTitle);
        formatGroup = findViewById(R.id.formatGroup);
        progressLayout = findViewById(R.id.progressLayout);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        progressStatus = findViewById(R.id.progressStatus);

        // WebView is hidden; thumbnail is loaded via Glide
        videoPreview.setVisibility(View.GONE);

        // Initially without choosing format, don't show resolution/bitrate option
        optionLabel.setVisibility(View.GONE);
        optionSpinner.setVisibility(View.GONE);
        formatGroup.clearCheck();

        formatGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioMp4) {
                // Show resolution option for video
                optionLabel.setText("Video Resolution");
                optionLabel.setVisibility(View.VISIBLE);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        videoResolutions);
                optionSpinner.setAdapter(adapter);
                optionSpinner.setVisibility(View.VISIBLE);
            } else if (checkedId == R.id.radioMp3) {
                // Show bitrate option for audio (e.g. 128kbps, 192kbps, 320kbps)
                optionLabel.setText("Audio Bitrate");
                optionLabel.setVisibility(View.VISIBLE);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        audioBitrates);
                optionSpinner.setAdapter(adapter);
                optionSpinner.setSelection(2); // default 192 kbps
                optionSpinner.setVisibility(View.VISIBLE);
            } else {
                optionLabel.setVisibility(View.GONE);
                optionSpinner.setVisibility(View.GONE);
            }
        });

        fetchInfoBtn.setOnClickListener(v -> {
            String url = "";
            if (urlInput.getText() != null) {
                url = urlInput.getText().toString().trim();
            }
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show();
                return;
            }
            fetchVideoInformation(url);
        });

        downloadBtn.setOnClickListener(v -> {
            int selectedId = formatGroup.getCheckedRadioButtonId();
            if (selectedId == -1) {
                Toast.makeText(this, "Please choose a format (Video or Audio)", Toast.LENGTH_SHORT).show();
                return;
            }

            if (checkStoragePermission()) {
                pyModule.callAttr("init_storage");
                startDownload();
            } else {
                requestStoragePermission();
            }
        });
    }

    private void fetchVideoInformation(String url) {
        if (url == null || url.trim().isEmpty()) return;
        final String finalUrl = url.trim();

        if (fetchInfoBtn != null) {
            fetchInfoBtn.setEnabled(false);
            fetchInfoBtn.setText("Fetching...");
        }

        // Instant thumbnail & preview display (0 ms response)
        final String fastVId = extractVideoId(finalUrl);
        if (!fastVId.isEmpty()) {
            final String fastThumb = "https://i.ytimg.com/vi/" + fastVId + "/hqdefault.jpg";
            if (videoThumbnail != null) {
                videoThumbnail.setVisibility(View.VISIBLE);
                Glide.with(this)
                        .load(fastThumb)
                        .centerCrop()
                        .placeholder(R.drawable.ic_thumbnail_placeholder)
                        .error(R.drawable.ic_thumbnail_placeholder)
                        .into(videoThumbnail);
            }
            if (videoPreview != null) videoPreview.setVisibility(View.GONE);
            if (previewLayout != null) {
                previewLayout.setVisibility(View.VISIBLE);
                previewLayout.setAlpha(1f);
            }
            if (videoTitle != null) {
                videoTitle.setText("Loading title...");
            }
        }

        new Thread(() -> {
            try {
                if (pyModule == null && Python.isStarted()) {
                    pyModule = Python.getInstance().getModule("app");
                }
                if (pyModule == null) return;

                Python py = Python.getInstance();
                PyObject infoObj = pyModule.callAttr("get_video_info", finalUrl);
                Map<PyObject, PyObject> info = infoObj.asMap();

                PyObject errKey = py.getBuiltins().get("str").call("error");
                if (info.containsKey(errKey) && info.get(errKey) != null) {
                    String error = info.get(errKey).toString();
                    runOnUiThread(() -> {
                        if (fetchInfoBtn != null) {
                            fetchInfoBtn.setEnabled(true);
                            fetchInfoBtn.setText("Fetch Information");
                        }
                        Toast.makeText(this, "Error: " + error, Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                PyObject titleKey = py.getBuiltins().get("str").call("title");
                PyObject videoIdKey = py.getBuiltins().get("str").call("video_id");
                PyObject resKey = py.getBuiltins().get("str").call("resolutions");
                PyObject thumbKey = py.getBuiltins().get("str").call("thumbnail");

                String title = (info.containsKey(titleKey) && info.get(titleKey) != null) ? info.get(titleKey).toString() : "YouTube Video";
                String rawVideoId = (info.containsKey(videoIdKey) && info.get(videoIdKey) != null) ? info.get(videoIdKey).toString() : "";
                final String videoId = rawVideoId.isEmpty() ? extractVideoId(finalUrl) : rawVideoId;
                String[] resList = (info.containsKey(resKey) && info.get(resKey) != null)
                        ? info.get(resKey).toJava(String[].class)
                        : videoResolutions;
                final String thumbnailUrl = (info.containsKey(thumbKey) && info.get(thumbKey) != null)
                        ? info.get(thumbKey).toString()
                        : ("https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");

                runOnUiThread(() -> {
                    if (fetchInfoBtn != null) {
                        fetchInfoBtn.setEnabled(true);
                        fetchInfoBtn.setText("Fetch Information");
                    }

                    if (videoTitle != null) {
                        videoTitle.setText(title);
                    }

                    // Only show thumbnail in preview
                    if (videoPreview != null) videoPreview.setVisibility(View.GONE);
                    if (videoThumbnail != null) {
                        videoThumbnail.setVisibility(View.VISIBLE);
                        Glide.with(MainActivity.this)
                                .load(thumbnailUrl)
                                .centerCrop()
                                .placeholder(R.drawable.ic_thumbnail_placeholder)
                                .error(R.drawable.ic_thumbnail_placeholder)
                                .into(videoThumbnail);
                    }

                    if (previewLayout != null) {
                        previewLayout.setAlpha(0f);
                        previewLayout.setVisibility(View.VISIBLE);
                        previewLayout.animate()
                                .alpha(1f)
                                .setDuration(350)
                                .start();
                    }

                    videoResolutions = resList;
                    // If MP4 was already selected, update the spinner with fetched resolutions
                    if (formatGroup != null && formatGroup.getCheckedRadioButtonId() == R.id.radioMp4 && optionSpinner != null) {
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                this,
                                android.R.layout.simple_spinner_dropdown_item,
                                videoResolutions);
                        optionSpinner.setAdapter(adapter);
                    }

                    Toast.makeText(this, "Video info fetched", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (fetchInfoBtn != null) {
                        fetchInfoBtn.setEnabled(true);
                        fetchInfoBtn.setText("Fetch Information");
                    }
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void initHistoryViews() {
        historyRecyclerView = findViewById(R.id.historyRecyclerView);
        emptyHistoryView = findViewById(R.id.emptyHistoryView);
        btnRefreshHistory = findViewById(R.id.btnRefreshHistory);
        txtStoragePath = findViewById(R.id.txtStoragePath);

        updateStoragePathDisplay();

        historyRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        historyAdapter = new DownloadHistoryAdapter(historyItems, new DownloadHistoryAdapter.OnItemActionListener() {
            @Override
            public void onPlay(DownloadItem item) {
                playMediaFile(item);
            }

            @Override
            public void onShare(DownloadItem item) {
                shareMediaFile(item);
            }

            @Override
            public void onDelete(DownloadItem item) {
                confirmDeleteFile(item);
            }
        });
        historyRecyclerView.setAdapter(historyAdapter);

        btnRefreshHistory.setOnClickListener(v -> loadDownloadHistory());
    }

    private void updateStoragePathDisplay() {
        if (txtStoragePath == null) return;
        try {
            PyObject p = pyModule.callAttr("get_download_path");
            if (p != null && !p.toString().isEmpty()) {
                String fullPath = p.toString();
                txtStoragePath.setText("📁 " + fullPath);
                return;
            }
        } catch (Exception ignored) {}

        try {
            File pubDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            txtStoragePath.setText("📁 " + pubDownload.getAbsolutePath() + "/YTDownloader");
        } catch (Exception ignored) {
            txtStoragePath.setText("📁 Internal Storage > Download > YTDownloader");
        }
    }

    private void initAboutViews() {
        aboutAppVersion = findViewById(R.id.aboutAppVersion);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        profile = findViewById(R.id.profile);
        github = findViewById(R.id.github);
        instagram = findViewById(R.id.instagram);
        linkedin = findViewById(R.id.linkedin);
        website = findViewById(R.id.software);

        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            aboutAppVersion.setText("Version " + versionName);
        } catch (Exception ignored) {
            aboutAppVersion.setText("Version 2026.11");
        }

        btnCheckUpdate.setOnClickListener(v -> {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show();
            UpdateHelper.with(this)
                    .setManual(true)
                    .onUpdateCheck((newVersion, urlApp, whatsNew, isForce) -> {
                        UpdateDialog.show(this, urlApp, newVersion, whatsNew, isForce);
                    })
                    .check();
        });

        profile.setOnClickListener(v -> openUrl("https://www.mocosn.in/"));
        github.setOnClickListener(v -> openUrl("https://github.com/MOCO-SN/"));
        instagram.setOnClickListener(v -> openUrl("https://www.instagram.com/_sachin_2006_01/"));
        linkedin.setOnClickListener(v -> openUrl("https://www.linkedin.com/in/sachin-patel-b5106a295/"));
        website.setOnClickListener(v -> openUrl("https://software.mocosn.in"));
    }

    private void loadDownloadHistory() {
        new Thread(() -> {
            List<DownloadItem> loaded = new ArrayList<>();
            List<File> searchDirs = new ArrayList<>();

            // 1. Standard public Downloads/YTDownloader folders
            try {
                File pubDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (pubDownload != null && pubDownload.exists()) {
                    searchDirs.add(new File(pubDownload, "YTDownloader/video"));
                    searchDirs.add(new File(pubDownload, "YTDownloader/audio"));
                    searchDirs.add(new File(pubDownload, "YTDownloader"));
                }
            } catch (Exception ignored) {}

            // 2. App-specific external storage folders
            try {
                File extDir = getExternalFilesDir(null);
                if (extDir != null && extDir.exists()) {
                    searchDirs.add(new File(extDir, "downloads/video"));
                    searchDirs.add(new File(extDir, "downloads/audio"));
                    searchDirs.add(new File(extDir, "downloads"));
                }
            } catch (Exception ignored) {}

            // 3. App-specific internal storage folders
            try {
                File filesDir = getFilesDir();
                if (filesDir != null && filesDir.exists()) {
                    searchDirs.add(new File(filesDir, "downloads/video"));
                    searchDirs.add(new File(filesDir, "downloads/audio"));
                }
            } catch (Exception ignored) {}

            for (File dir : searchDirs) {
                if (dir != null && dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && !f.getName().startsWith(".")) {
                                String name = f.getName().toLowerCase();
                                if (name.endsWith(".mp4") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".webm")) {
                                    boolean exists = false;
                                    for (DownloadItem existing : loaded) {
                                        if (existing.getFile().getAbsolutePath().equals(f.getAbsolutePath())) {
                                            exists = true;
                                            break;
                                        }
                                    }
                                    if (!exists) {
                                        DownloadItem item = new DownloadItem(f);

                                        // Locate thumbnail saved for this item if available
                                        String fName = f.getName();
                                        int dot = fName.lastIndexOf('.');
                                        String baseName = (dot > 0) ? fName.substring(0, dot) : fName;
                                        File thumbFile = null;

                                        if (f.getParentFile() != null) {
                                            File grandParent = f.getParentFile().getParentFile();
                                            if (grandParent != null) {
                                                File check1 = new File(grandParent, ".thumbnails/" + baseName + ".jpg");
                                                if (check1.exists()) thumbFile = check1;
                                            }
                                            if (thumbFile == null) {
                                                File check2 = new File(f.getParentFile(), ".thumbnails/" + baseName + ".jpg");
                                                if (check2.exists()) thumbFile = check2;
                                            }
                                        }

                                        if (thumbFile == null) {
                                            try {
                                                File pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                                                File checkPub = new File(pub, "YTDownloader/.thumbnails/" + baseName + ".jpg");
                                                if (checkPub.exists()) thumbFile = checkPub;
                                            } catch (Exception ignored) {}
                                        }

                                        if (thumbFile == null) {
                                            try {
                                                File ext = getExternalFilesDir(null);
                                                if (ext != null) {
                                                    File checkExt = new File(ext, "downloads/.thumbnails/" + baseName + ".jpg");
                                                    if (checkExt.exists()) thumbFile = checkExt;
                                                }
                                            } catch (Exception ignored) {}
                                        }

                                        if (thumbFile != null && thumbFile.exists()) {
                                            item.setThumbnailFile(thumbFile);
                                        }

                                        // Extract resolution/bitrate in background thread
                                        item.resolveMediaResolution(this);

                                        loaded.add(item);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sort newest first
            Collections.sort(loaded, (a, b) -> Long.compare(b.getFile().lastModified(), a.getFile().lastModified()));

            runOnUiThread(() -> {
                updateStoragePathDisplay();
                historyItems.clear();
                historyItems.addAll(loaded);
                historyAdapter.notifyDataSetChanged();

                if (historyItems.isEmpty()) {
                    emptyHistoryView.setVisibility(View.VISIBLE);
                    historyRecyclerView.setVisibility(View.GONE);
                } else {
                    emptyHistoryView.setVisibility(View.GONE);
                    historyRecyclerView.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private void playMediaFile(DownloadItem item) {
        try {
            File file = item.getFile();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            String mime = item.isVideo() ? "video/*" : "audio/*";

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Play with"));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareMediaFile(DownloadItem item) {
        try {
            File file = item.getFile();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            String mime = item.isVideo() ? "video/*" : "audio/*";

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share via"));
        } catch (Exception e) {
            Toast.makeText(this, "Could not share file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteFile(DownloadItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Delete File")
                .setMessage("Are you sure you want to delete '" + item.getName() + "'?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    try {
                        if (item.getFile().delete()) {
                            Toast.makeText(this, "Deleted: " + item.getName(), Toast.LENGTH_SHORT).show();
                            loadDownloadHistory();
                        } else {
                            Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Error deleting: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveDownloadMetadata(String title, String fileType, String quality) {
        if (title == null || title.isEmpty() || quality == null) return;
        try {
            SharedPreferences prefs = getSharedPreferences("download_history_meta", MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(title, quality);
            editor.putString(title + "." + fileType, quality);
            String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            editor.putString(safe, quality);
            editor.putString(safe + "." + fileType, quality);
            String safeNoUnderscore = title.replaceAll("[\\\\/:*?\"<>|]", "").trim();
            editor.putString(safeNoUnderscore, quality);
            editor.putString(safeNoUnderscore + "." + fileType, quality);
            editor.apply();
        } catch (Exception ignored) {}
    }

    @Override
    public void onUpdateCheckLister(String urlApp) {
        if (urlApp != null && !urlApp.isEmpty()) {
            UpdateDialog.show(this, urlApp);
        }
    }

    private String extractVideoId(String url) {
        if (url == null) return "";
        Matcher m = Pattern.compile("(?:v=|/|youtu\\.be/|shorts/|embed/)([0-9A-Za-z_-]{11})").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open URL: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO},
                    STORAGE_PERMISSION_CODE);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pyModule.callAttr("init_storage");
                startDownload();
            } else {
                Toast.makeText(this, "Permission denied to write to storage", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (isProgressHidden && isDownloading) {
                    showOrUpdateNotification(currentDownloadProgress);
                }
            }
        }
    }

    private void initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Download Progress";
            String description = "Shows download progress for videos and music";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    private void showDownloadProgressDialog() {
        if (isFinishing() || isDestroyed()) return;

        if (downloadProgressDialog == null) {
            View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_download_progress, null);
            dialogProgressBar = dialogView.findViewById(R.id.dialogProgressBar);
            dialogProgressPercent = dialogView.findViewById(R.id.dialogProgressPercent);
            dialogProgressStatus = dialogView.findViewById(R.id.dialogProgressStatus);
            dialogVideoTitle = dialogView.findViewById(R.id.dialogVideoTitle);
            dialogFormatBadge = dialogView.findViewById(R.id.dialogFormatBadge);
            btnHideProgress = dialogView.findViewById(R.id.btnHideProgress);

            btnHideProgress.setOnClickListener(v -> {
                isProgressHidden = true;
                if (downloadProgressDialog != null && downloadProgressDialog.isShowing()) {
                    downloadProgressDialog.dismiss();
                }
                showOrUpdateNotification(currentDownloadProgress);
                Toast.makeText(this, "Download running in background. Tap notification to view progress.", Toast.LENGTH_SHORT).show();
            });

            downloadProgressDialog = new AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setCancelable(true)
                    .setOnCancelListener(dialogInterface -> {
                        isProgressHidden = true;
                        showOrUpdateNotification(currentDownloadProgress);
                        Toast.makeText(this, "Download running in background. Tap notification to view progress.", Toast.LENGTH_SHORT).show();
                    })
                    .create();

            if (downloadProgressDialog.getWindow() != null) {
                downloadProgressDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
        }

        if (dialogVideoTitle != null) {
            dialogVideoTitle.setText(currentDownloadTitle);
        }
        if (dialogFormatBadge != null) {
            dialogFormatBadge.setText(currentDownloadFormat);
        }
        if (dialogProgressPercent != null) {
            dialogProgressPercent.setText(currentDownloadProgress + "%");
        }
        if (dialogProgressBar != null) {
            dialogProgressBar.setProgressCompat(currentDownloadProgress, false);
        }
        if (dialogProgressStatus != null) {
            dialogProgressStatus.setText(currentDownloadProgress < 100 ? "Downloading streams... " + currentDownloadProgress + "%" : "Finalizing download...");
        }

        if (!downloadProgressDialog.isShowing()) {
            downloadProgressDialog.show();
        }

        // Cancel the ongoing notification while dialog is active
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
    }

    private void showOrUpdateNotification(int progress) {
        if (!isDownloading) return;

        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(ACTION_SHOW_PROGRESS);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_download)
                .setContentTitle(currentDownloadTitle)
                .setContentText(progress + "% • " + currentDownloadFormat)
                .setProgress(100, progress, progress <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
            } catch (SecurityException ignored) {}
        }
    }

    private void showDownloadCompleteNotification(boolean isSuccess, String message) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_download)
                .setContentTitle(isSuccess ? "Download Complete" : "Download Failed")
                .setContentText(isSuccess ? currentDownloadTitle : message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(pendingIntent);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_COMPLETE_ID, builder.build());
            } catch (SecurityException ignored) {}
        }
    }

    private void startDownload() {
        String url = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        int selectedId = formatGroup.getCheckedRadioButtonId();

        if (selectedId == -1) {
            Toast.makeText(this, "Please choose a format (Video or Audio)", Toast.LENGTH_SHORT).show();
            return;
        }

        String fileType = (selectedId == R.id.radioMp3) ? "mp3" : "mp4";
        String qualityChoice = optionSpinner.getSelectedItem() != null ? optionSpinner.getSelectedItem().toString() : "highest";

        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show();
            return;
        }

        checkAndRequestNotificationPermission();

        String rawTitle = videoTitle.getText() != null ? videoTitle.getText().toString().trim() : "";
        if (rawTitle.isEmpty()) {
            rawTitle = "YouTube Media (" + (fileType.equalsIgnoreCase("mp3") ? "Audio" : "Video") + ")";
        }

        currentDownloadTitle = rawTitle;
        currentDownloadFormat = (fileType.equalsIgnoreCase("mp3") ? "Audio (MP3)" : "Video (MP4)") + " • " + qualityChoice;
        currentDownloadProgress = 0;
        isDownloading = true;
        isProgressHidden = false;

        saveDownloadMetadata(currentDownloadTitle, fileType, qualityChoice);

        runOnUiThread(() -> {
            downloadBtn.setEnabled(false);
            downloadBtn.setText("Downloading...");
            downloadBtn.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            urlInput.setEnabled(false);
            fetchInfoBtn.setEnabled(false);
            formatGroup.setEnabled(false);
            optionSpinner.setEnabled(false);

            showDownloadProgressDialog();
        });

        new Thread(() -> {
            try {
                DownloadCallback callback = progress -> runOnUiThread(() -> {
                    currentDownloadProgress = progress;
                    if (downloadProgressDialog != null && downloadProgressDialog.isShowing()) {
                        if (dialogProgressBar != null) {
                            dialogProgressBar.setProgressCompat(progress, true);
                        }
                        if (dialogProgressPercent != null) {
                            dialogProgressPercent.setText(progress + "%");
                        }
                        if (dialogProgressStatus != null) {
                            dialogProgressStatus.setText(progress < 100 ? "Downloading streams... " + progress + "%" : "Finalizing download...");
                        }
                    }
                    if (isProgressHidden) {
                        showOrUpdateNotification(progress);
                    }
                });

                PyObject result = pyModule.callAttr("download_video", url, fileType, qualityChoice, callback, currentDownloadTitle);
                String message = result.toString();

                runOnUiThread(() -> {
                    isDownloading = false;
                    isProgressHidden = false;

                    if (downloadProgressDialog != null && downloadProgressDialog.isShowing()) {
                        downloadProgressDialog.dismiss();
                    }

                    NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
                    showDownloadCompleteNotification(true, message);
                    saveDownloadMetadata(currentDownloadTitle, fileType, qualityChoice);

                    Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
                    downloadBtn.setEnabled(true);
                    downloadBtn.setText("Download");
                    urlInput.setEnabled(true);
                    fetchInfoBtn.setEnabled(true);
                    formatGroup.setEnabled(true);
                    optionSpinner.setEnabled(true);

                    // Refresh history in the background so it is ready
                    loadDownloadHistory();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    isDownloading = false;
                    isProgressHidden = false;

                    if (downloadProgressDialog != null && downloadProgressDialog.isShowing()) {
                        downloadProgressDialog.dismiss();
                    }

                    NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
                    showDownloadCompleteNotification(false, "Download Error: " + e.getMessage());

                    Toast.makeText(this, "Download Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    downloadBtn.setEnabled(true);
                    downloadBtn.setText("Download");
                    urlInput.setEnabled(true);
                    fetchInfoBtn.setEnabled(true);
                    formatGroup.setEnabled(true);
                    optionSpinner.setEnabled(true);
                });
            }
        }).start();
    }
}
