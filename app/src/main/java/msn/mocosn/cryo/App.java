package msn.mocosn.cryo;

import android.content.pm.ApplicationInfo;

import com.chaquo.python.android.PyApplication;
import com.google.android.gms.ads.MobileAds;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

import java.util.HashMap;
import java.util.Map;

public class App extends PyApplication {

    @Override
    public void onCreate() {
        super.onCreate();

        MobileAds.initialize(this, initializationStatus -> {});

        initRemoteConfig();
    }

    private void initRemoteConfig() {
        try {
            FirebaseRemoteConfig remoteConfig = FirebaseRemoteConfig.getInstance();

            boolean isDebug = false;
            try {
                isDebug = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            } catch (Exception ignored) {}

            FirebaseRemoteConfigSettings configSettings = new FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(isDebug ? 0 : 3600)
                    .build();
            remoteConfig.setConfigSettingsAsync(configSettings);

            Map<String, Object> defaultValue = new HashMap<>();
            defaultValue.put(UpdateHelper.KEY_UPDATE_ENABLE, true);
            defaultValue.put(UpdateHelper.KEY_UPDATE_VERSION, "2026.11");
            defaultValue.put(UpdateHelper.KEY_UPDATE_URL, "https://software.mocosn.in/");
            defaultValue.put(UpdateHelper.KEY_UPDATE_WHATS_NEW, "• Fast video info extraction\n• High-resolution video download (4K & 8K)\n• Background notification progress with hide option\n• YouTube share listener");
            defaultValue.put(UpdateHelper.KEY_UPDATE_FORCE, false);

            remoteConfig.setDefaultsAsync(defaultValue);
            remoteConfig.fetchAndActivate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
