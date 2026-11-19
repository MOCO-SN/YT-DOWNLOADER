package com.mocosn.mocoplayer;


import android.content.Context;
import android.content.pm.PackageManager;

import com.google.firebase.remoteconfig.FirebaseRemoteConfig;

public class UpdateHelper {
    public static String KEY_UPDATE_ENABLE = "is_update";
    public static String KEY_UPDATE_VERSION = "version";
    public static String KEY_UPDATE_URL = "update_url";

    public interface OnUpdateCheckListerner{
        void onUpdateCheckLister(String urlApp);
    }

    public static Builder with(Context context){
        return new Builder(context);
    }
    private Context context;
    private OnUpdateCheckListerner onUpdateCheckListener;

    public UpdateHelper(Context context,OnUpdateCheckListerner onUpdateCheckListerner)
    {
        this.onUpdateCheckListener = onUpdateCheckListerner;
        this.context = context;
    }

    public void check() {
        FirebaseRemoteConfig remoteConfig = FirebaseRemoteConfig.getInstance();

        if (remoteConfig.getBoolean(KEY_UPDATE_ENABLE)) {
            String currentVersion = remoteConfig.getString(KEY_UPDATE_VERSION);
            String appVersion = getAppVersion(context);
            String updateURL = remoteConfig.getString(KEY_UPDATE_URL);

            if (isVersionNewer(currentVersion, appVersion) && onUpdateCheckListener != null) {
                onUpdateCheckListener.onUpdateCheckLister(updateURL);
            }
        }
    }
    private boolean isVersionNewer(String remoteVersion, String localVersion) {
        try {
            String[] remoteParts = remoteVersion.split("\\.");
            String[] localParts = localVersion.split("\\.");

            int length = Math.max(remoteParts.length, localParts.length);
            for (int i = 0; i < length; i++) {
                int remotePart = i < remoteParts.length ? Integer.parseInt(remoteParts[i]) : 0;
                int localPart = i < localParts.length ? Integer.parseInt(localParts[i]) : 0;

                if (remotePart > localPart) return true;
                if (remotePart < localPart) return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }


    private String getAppVersion(Context context) {
        String result = "";
        try {
            result = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return result;
    }


    public static class Builder{
        private Context context;
        private OnUpdateCheckListerner onUpdateCheckListener;

        public Builder(Context context){
            this.context = context;
        }
        public Builder onUpdateCheck(OnUpdateCheckListerner onUpdateCheckListerner)
        {
            this.onUpdateCheckListener = onUpdateCheckListerner;
            return this;
        }
        public UpdateHelper build(){
            return new UpdateHelper(context,onUpdateCheckListener);
        }

        public UpdateHelper check(){
            UpdateHelper updateHelper = build();
            updateHelper.check();

            return updateHelper;
        }

    }
}
