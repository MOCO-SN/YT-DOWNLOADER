package msn.mocosn.cryo;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.Map;

public class MainActivity extends AppCompatActivity {

    LinearLayout panel, previewLayout, progressLayout;
    EditText urlInput;
    Button fetchInfoBtn, downloadBtn;
    Spinner resSpinner;
    ImageView videoThumbnail;
    TextView videoTitle, progressStatus;
    ProgressBar downloadProgressBar;
    RadioGroup formatGroup;
    PyObject pyModule;
    ImageView profile, github, linkedin, instagram, website;

    private static final int STORAGE_PERMISSION_CODE = 101;

    // Interface for Python to call
    public interface DownloadCallback {
        void onProgress(int progress);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        profile = findViewById(R.id.profile);
        github = findViewById(R.id.github);
        linkedin = findViewById(R.id.linkedin);
        instagram = findViewById(R.id.instagram);
        website = findViewById(R.id.software);

        profile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = "https://www.mocosn.in/";
                openUrl(url);
            }
        });

        github.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = "https://github.com/MOCO-SN/";
                openUrl(url);
            }
        });

        instagram.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = "https://www.instagram.com/_sachin_2006_01/";
                openUrl(url);
            }
        });

        linkedin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = "https://www.linkedin.com/in/sachin-patel-b5106a295/";
                openUrl(url);
            }
        });

        website.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = "https://software.mocosn.in";
                openUrl(url);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager())
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:" + getPackageName())));

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        Python py = Python.getInstance();
        pyModule = py.getModule("app");

        panel = findViewById(R.id.mainPanel);
        urlInput = findViewById(R.id.urlInput);
        fetchInfoBtn = findViewById(R.id.fetchResBtn);
        downloadBtn = findViewById(R.id.downloadBtn);
        resSpinner = findViewById(R.id.spinner);
        previewLayout = findViewById(R.id.previewLayout);
        videoThumbnail = findViewById(R.id.videoThumbnail);
        videoTitle = findViewById(R.id.videoTitle);
        formatGroup = findViewById(R.id.formatGroup);
        
        progressLayout = findViewById(R.id.progressLayout);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        progressStatus = findViewById(R.id.progressStatus);

        Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
        panel.startAnimation(pulse);

        fetchInfoBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString();
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show();
                return;
            }
            
            new Thread(() -> {
                try {
                    PyObject infoObj = pyModule.callAttr("get_video_info", url);
                    Map<PyObject, PyObject> info = infoObj.asMap();
                    
                    if (info.containsKey(py.getBuiltins().get("str").call("error"))) {
                        String error = info.get(py.getBuiltins().get("str").call("error")).toString();
                        runOnUiThread(() -> Toast.makeText(this, "Error: " + error, Toast.LENGTH_LONG).show());
                        return;
                    }

                    String title = info.get(py.getBuiltins().get("str").call("title")).toString();
                    String thumbnailUrl = info.get(py.getBuiltins().get("str").call("thumbnail")).toString();
                    String[] resList = info.get(py.getBuiltins().get("str").call("resolutions")).toJava(String[].class);
                    
                    runOnUiThread(() -> {
                        videoTitle.setText(title);
                        Glide.with(this).load(thumbnailUrl).into(videoThumbnail);
                        previewLayout.setVisibility(View.VISIBLE);
                        
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                this,
                                android.R.layout.simple_spinner_dropdown_item,
                                resList);
                        resSpinner.setAdapter(adapter);
                        Toast.makeText(this, "Video info fetched", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });

        downloadBtn.setOnClickListener(v -> {
            if (checkStoragePermission()) {
                pyModule.callAttr("init_storage");
                startDownload();
            } else {
                requestStoragePermission();
            }
        });
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        startActivity(intent);
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
        }
    }

    private void startDownload() {
        String url = urlInput.getText().toString();
        int selectedId = formatGroup.getCheckedRadioButtonId();
        String fileType = (selectedId == R.id.radioMp3) ? "mp3" : "mp4";
        String res = resSpinner.getSelectedItem() != null ? resSpinner.getSelectedItem().toString() : "highest";
        
        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show();
            return;
        }

        runOnUiThread(() -> {
            progressLayout.setVisibility(View.VISIBLE);
            downloadProgressBar.setProgress(0);
            progressStatus.setText("0%");
            downloadBtn.setEnabled(false);
        });
        
        new Thread(() -> {
            try {
                // Create callback object for Python to update
                DownloadCallback callback = progress -> runOnUiThread(() -> {
                    downloadProgressBar.setProgress(progress);
                    progressStatus.setText(progress + "%");
                });

                PyObject result = pyModule.callAttr("download_video", url, fileType, res, callback);
                String message = result.toString();
                
                runOnUiThread(() -> {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    progressLayout.setVisibility(View.GONE);
                    downloadBtn.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Download Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    progressLayout.setVisibility(View.GONE);
                    downloadBtn.setEnabled(true);
                });
            }
        }).start();
    }
}
