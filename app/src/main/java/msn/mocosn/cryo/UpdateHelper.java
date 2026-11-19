package msn.mocosn.cryo;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.widget.Toast;

import com.google.firebase.remoteconfig.FirebaseRemoteConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateHelper {

    public static String currentVersion = "2026.11";
    public static final String KEY_UPDATE_ENABLE = "is_update";
    public static final String KEY_UPDATE_VERSION = "version";
    public static final String KEY_UPDATE_URL = "update_url";
    public static final String KEY_UPDATE_WHATS_NEW = "whats_new";
    public static final String KEY_UPDATE_FORCE = "force_update";

    public interface OnUpdateCheckListerner {
        void onUpdateCheckLister(String urlApp);
    }

    @FunctionalInterface
    public interface OnUpdateListener {
        void onUpdateAvailable(String newVersion, String urlApp, String whatsNew, boolean isForce);
        default void onAlreadyUpToDate() {}
        default void onCheckFailed(String error) {}
    }

    private final Context context;
    private OnUpdateCheckListerner legacyListener;
    private OnUpdateListener updateListener;
    private boolean isManualCheck = false;

    public UpdateHelper(Context context, OnUpdateCheckListerner legacyListener) {
        this.context = context;
        this.legacyListener = legacyListener;
    }

    public UpdateHelper(Context context, OnUpdateListener updateListener) {
        this.context = context;
        this.updateListener = updateListener;
    }

    public static Builder with(Context context) {
        return new Builder(context);
    }

    public void check() {
        performCheck(isManualCheck);
    }

    public void performCheck(boolean manual) {
        FirebaseRemoteConfig remoteConfig = FirebaseRemoteConfig.getInstance();

        // Fetch fresh config from Firebase
        remoteConfig.fetchAndActivate().addOnCompleteListener(task -> {
            if (context instanceof Activity) {
                Activity act = (Activity) context;
                if (act.isFinishing() || act.isDestroyed()) return;
            }

            evaluateConfig(remoteConfig, manual);
        });
    }

    private void evaluateConfig(FirebaseRemoteConfig remoteConfig, boolean manual) {
        try {
            // 1. Remote Version detection (checking multiple common keys)
            String remoteVersion = remoteConfig.getString(KEY_UPDATE_VERSION).trim();
            if (remoteVersion.isEmpty()) remoteVersion = remoteConfig.getString("latest_version").trim();
            if (remoteVersion.isEmpty()) remoteVersion = remoteConfig.getString("app_version").trim();

            // 2. Download URL detection
            String updateUrl = remoteConfig.getString(KEY_UPDATE_URL).trim();
            if (updateUrl.isEmpty()) updateUrl = remoteConfig.getString("app_url").trim();
            if (updateUrl.isEmpty()) updateUrl = remoteConfig.getString("download_url").trim();
            if (updateUrl.isEmpty()) updateUrl = "https://software.mocosn.in/";

            // 3. What's new notes
            String whatsNew = remoteConfig.getString(KEY_UPDATE_WHATS_NEW).trim();
            if (whatsNew.isEmpty()) whatsNew = remoteConfig.getString("changelog").trim();

            // 4. Force update flag
            boolean isForce = remoteConfig.getBoolean(KEY_UPDATE_FORCE);

            // 5. Update enable flag (true by default unless explicitly disabled as "false")
            boolean isEnabled = true;
            String isUpdateStr = remoteConfig.getString(KEY_UPDATE_ENABLE).trim();
            if (isUpdateStr.equalsIgnoreCase("false") || isUpdateStr.equals("0")) {
                isEnabled = false;
            }

            String localVersion = getAppVersion(context);
            if (!remoteVersion.isEmpty()) {
                currentVersion = remoteVersion;
            }

            boolean isNewer = !remoteVersion.isEmpty() && isVersionNewer(remoteVersion, localVersion);

            if (isEnabled && isNewer) {
                // Update is available!
                if (updateListener != null) {
                    updateListener.onUpdateAvailable(remoteVersion, updateUrl, whatsNew, isForce);
                } else if (legacyListener != null) {
                    legacyListener.onUpdateCheckLister(updateUrl);
                } else {
                    UpdateDialog.show(context, updateUrl, remoteVersion, whatsNew, isForce);
                }
            } else {
                // Already on latest version
                if (updateListener != null) {
                    updateListener.onAlreadyUpToDate();
                }
                if (manual) {
                    Toast.makeText(context, "You are using the latest version (v" + localVersion + ")", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (updateListener != null) {
                updateListener.onCheckFailed(e.getMessage());
            }
            if (manual) {
                Toast.makeText(context, "Could not check for updates. Please try again later.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static boolean isVersionNewer(String remoteVersion, String localVersion) {
        if (remoteVersion == null || localVersion == null) return false;
        try {
            String rClean = remoteVersion.replaceAll("(?i)^[v\\s]+", "").trim();
            String lClean = localVersion.replaceAll("(?i)^[v\\s]+", "").trim();

            if (rClean.equalsIgnoreCase(lClean)) return false;

            String[] remoteParts = rClean.split("[.-]");
            String[] localParts = lClean.split("[.-]");

            int length = Math.max(remoteParts.length, localParts.length);
            for (int i = 0; i < length; i++) {
                int remotePart = 0;
                int localPart = 0;

                if (i < remoteParts.length) {
                    Matcher m = Pattern.compile("\\d+").matcher(remoteParts[i]);
                    if (m.find()) {
                        remotePart = Integer.parseInt(m.group());
                    }
                }

                if (i < localParts.length) {
                    Matcher m = Pattern.compile("\\d+").matcher(localParts[i]);
                    if (m.find()) {
                        localPart = Integer.parseInt(m.group());
                    }
                }

                if (remotePart > localPart) return true;
                if (remotePart < localPart) return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static String getAppVersion(Context context) {
        if (context == null) return "2026.11";
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "2026.11";
        }
    }

    public static class Builder {
        private final Context context;
        private OnUpdateCheckListerner legacyListener;
        private OnUpdateListener updateListener;
        private boolean isManual = false;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder onUpdateCheck(OnUpdateCheckListerner legacyListener) {
            this.legacyListener = legacyListener;
            return this;
        }

        public Builder onUpdateCheck(OnUpdateListener updateListener) {
            this.updateListener = updateListener;
            return this;
        }

        public Builder setManual(boolean isManual) {
            this.isManual = isManual;
            return this;
        }

        public UpdateHelper build() {
            UpdateHelper helper;
            if (updateListener != null) {
                helper = new UpdateHelper(context, updateListener);
            } else {
                helper = new UpdateHelper(context, legacyListener);
            }
            helper.isManualCheck = this.isManual;
            return helper;
        }

        public UpdateHelper check() {
            UpdateHelper updateHelper = build();
            updateHelper.check();
            return updateHelper;
        }
    }
}
