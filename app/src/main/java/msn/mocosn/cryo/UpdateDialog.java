package msn.mocosn.cryo;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

public class UpdateDialog {

    public static void show(Context context, String urlApp) {
        show(context, urlApp, UpdateHelper.currentVersion, null, false);
    }

    public static void show(Context context, String urlApp, String newVersion, String whatsNewText, boolean isForce) {
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(context).inflate(R.layout.layout_dev, null);
        builder.setView(view);

        MaterialButton btnLater = view.findViewById(R.id.later);
        MaterialButton btnUpdateNow = view.findViewById(R.id.update);
        TextView txtVersion = view.findViewById(R.id.version);
        TextView txtWhatsNew = view.findViewById(R.id.whatsNew);
        ImageView logoImage = view.findViewById(R.id.logo);

        if (logoImage != null) {
            logoImage.setImageResource(R.drawable.ic_youtube_logo_full);
        }

        String displayVer = (newVersion != null && !newVersion.isEmpty()) ? newVersion : UpdateHelper.currentVersion;
        if (displayVer != null && !displayVer.isEmpty()) {
            if (!displayVer.toLowerCase().startsWith("v")) {
                displayVer = "v" + displayVer;
            }
            if (txtVersion != null) {
                txtVersion.setText("New Version " + displayVer);
            }
        }

        if (txtWhatsNew != null && whatsNewText != null && !whatsNewText.trim().isEmpty()) {
            txtWhatsNew.setText(whatsNewText);
        }

        AlertDialog dialog = builder.create();
        dialog.setCancelable(!isForce);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        if (isForce && btnLater != null) {
            btnLater.setVisibility(View.GONE);
        } else if (btnLater != null) {
            btnLater.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnUpdateNow != null) {
            btnUpdateNow.setOnClickListener(v -> {
                try {
                    String destination = (urlApp != null && !urlApp.isEmpty()) ? urlApp : "https://software.mocosn.in/";
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(destination));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    if (!isForce) {
                        dialog.dismiss();
                    }
                } catch (Exception e) {
                    Toast.makeText(context, "Could not open update link: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        dialog.show();
    }
}
