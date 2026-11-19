package msn.mocosn.cryo;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class DownloadHistoryAdapter extends RecyclerView.Adapter<DownloadHistoryAdapter.ViewHolder> {

    public interface OnItemActionListener {
        void onPlay(DownloadItem item);
        void onShare(DownloadItem item);
        void onDelete(DownloadItem item);
    }

    private final List<DownloadItem> items;
    private final OnItemActionListener listener;

    public DownloadHistoryAdapter(List<DownloadItem> items, OnItemActionListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_download_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadItem item = items.get(position);
        holder.bind(item, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView itemThumbnail;
        final TextView itemFormatBadge;
        final TextView itemFileName;
        final TextView itemFilePath;
        final TextView itemFileSize;
        final TextView itemDetailResolution;
        final TextView itemFileDate;
        final View resolutionBadgeContainer;
        final ImageView itemResolutionIcon;
        final TextView itemResolution;
        final View btnPlay;
        final View btnShare;
        final View btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            itemThumbnail = itemView.findViewById(R.id.itemThumbnail);
            itemFormatBadge = itemView.findViewById(R.id.itemFormatBadge);
            itemFileName = itemView.findViewById(R.id.itemFileName);
            itemFilePath = itemView.findViewById(R.id.itemFilePath);
            itemFileSize = itemView.findViewById(R.id.itemFileSize);
            itemDetailResolution = itemView.findViewById(R.id.itemDetailResolution);
            itemFileDate = itemView.findViewById(R.id.itemFileDate);
            resolutionBadgeContainer = itemView.findViewById(R.id.resolutionBadgeContainer);
            itemResolutionIcon = itemView.findViewById(R.id.itemResolutionIcon);
            itemResolution = itemView.findViewById(R.id.itemResolution);
            btnPlay = itemView.findViewById(R.id.btnPlay);
            btnShare = itemView.findViewById(R.id.btnShare);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }

        void bind(DownloadItem item, OnItemActionListener listener) {
            itemFileName.setText(item.getName());
            itemFileSize.setText(item.getFormattedSize());
            itemFileDate.setText(item.getFormattedDate());
            itemFilePath.setText(item.getDisplayPath());

            String res = item.getResolution();
            String rawRes = item.getRawResolution();

            if (itemDetailResolution != null) {
                itemDetailResolution.setText(res);
            }
            if (itemResolution != null) {
                itemResolution.setText(res);
            }
            if (itemFormatBadge != null) {
                itemFormatBadge.setText(rawRes);
            }

            // Style the resolution badge in the actions bar
            if (item.isVideo()) {
                if (itemResolutionIcon != null) {
                    itemResolutionIcon.setImageResource(R.drawable.ic_video);
                }

                if (rawRes.contains("8K") || rawRes.contains("4K")) {
                    // Ultra HD (4K / 8K) -> Gold/Amber style
                    int textColor = Color.parseColor("#B45309");
                    int bgColor = Color.parseColor("#FEF3C7");
                    int strokeColor = Color.parseColor("#FDE68A");

                    applyChipStyle(bgColor, strokeColor);
                    if (itemResolutionIcon != null) itemResolutionIcon.setColorFilter(textColor);
                    if (itemResolution != null) itemResolution.setTextColor(textColor);
                    if (itemDetailResolution != null) itemDetailResolution.setTextColor(Color.parseColor("#D97706"));
                    if (itemFormatBadge != null) itemFormatBadge.setBackgroundColor(Color.parseColor("#E6D97706"));

                } else if (rawRes.contains("1080p") || rawRes.contains("1440p") || rawRes.contains("2K")) {
                    // Full HD (1080p / 1440p) -> Navy Blue style
                    int textColor = Color.parseColor("#0F2B48");
                    int bgColor = Color.parseColor("#EBF3FB");
                    int strokeColor = Color.parseColor("#CCE0F5");

                    applyChipStyle(bgColor, strokeColor);
                    if (itemResolutionIcon != null) itemResolutionIcon.setColorFilter(textColor);
                    if (itemResolution != null) itemResolution.setTextColor(textColor);
                    if (itemDetailResolution != null) itemDetailResolution.setTextColor(Color.parseColor("#1A73E8"));
                    if (itemFormatBadge != null) itemFormatBadge.setBackgroundColor(Color.parseColor("#CC0F2B48"));

                } else {
                    // Standard HD (720p, 480p, 360p) -> Clean Mint/Slate style
                    int textColor = Color.parseColor("#0F2B48");
                    int bgColor = Color.parseColor("#F1F4ED");
                    int strokeColor = Color.parseColor("#D2E5CE");

                    applyChipStyle(bgColor, strokeColor);
                    if (itemResolutionIcon != null) itemResolutionIcon.setColorFilter(textColor);
                    if (itemResolution != null) itemResolution.setTextColor(textColor);
                    if (itemDetailResolution != null) itemDetailResolution.setTextColor(Color.parseColor("#00B894"));
                    if (itemFormatBadge != null) itemFormatBadge.setBackgroundColor(Color.parseColor("#CC0F2B48"));
                }
            } else {
                // Audio MP3 -> Emerald / Green style
                int textColor = Color.parseColor("#00896F");
                int bgColor = Color.parseColor("#E6F8F3");
                int strokeColor = Color.parseColor("#B8EDDF");

                applyChipStyle(bgColor, strokeColor);
                if (itemResolutionIcon != null) {
                    itemResolutionIcon.setImageResource(R.drawable.ic_music);
                    itemResolutionIcon.setColorFilter(Color.parseColor("#00B894"));
                }
                if (itemResolution != null) itemResolution.setTextColor(textColor);
                if (itemDetailResolution != null) itemDetailResolution.setTextColor(Color.parseColor("#00B894"));
                if (itemFormatBadge != null) itemFormatBadge.setBackgroundColor(Color.parseColor("#CC00B894"));
            }

            // Thumbnail loading:
            // 1. Saved YouTube thumbnail file if available
            // 2. Video frame via Glide if video file
            // 3. Audio/music placeholder if audio
            if (item.getThumbnailFile() != null && item.getThumbnailFile().exists()) {
                itemThumbnail.clearColorFilter();
                itemThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Glide.with(itemView.getContext())
                        .load(item.getThumbnailFile())
                        .centerCrop()
                        .placeholder(R.drawable.ic_thumbnail_placeholder)
                        .error(item.isVideo() ? R.drawable.ic_video : R.drawable.ic_music)
                        .into(itemThumbnail);
            } else if (item.isVideo()) {
                itemThumbnail.clearColorFilter();
                itemThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Glide.with(itemView.getContext())
                        .load(item.getFile())
                        .centerCrop()
                        .placeholder(R.drawable.ic_thumbnail_placeholder)
                        .error(R.drawable.ic_video)
                        .into(itemThumbnail);
            } else {
                Glide.with(itemView.getContext()).clear(itemThumbnail);
                itemThumbnail.setImageResource(R.drawable.ic_music);
                itemThumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                itemThumbnail.setColorFilter(Color.parseColor("#00B894"));
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onPlay(item);
            });

            if (resolutionBadgeContainer != null) {
                resolutionBadgeContainer.setOnClickListener(v -> {
                    Toast.makeText(itemView.getContext(),
                            "Format: " + (item.isVideo() ? "Video" : "Audio") + " • Resolution: " + item.getResolution() + " • Size: " + item.getFormattedSize(),
                            Toast.LENGTH_SHORT).show();
                });
            }

            btnPlay.setOnClickListener(v -> {
                if (listener != null) listener.onPlay(item);
            });

            btnShare.setOnClickListener(v -> {
                if (listener != null) listener.onShare(item);
            });

            btnDelete.setOnClickListener(v -> {
                if (listener != null) listener.onDelete(item);
            });
        }

        private void applyChipStyle(int bgColor, int strokeColor) {
            if (resolutionBadgeContainer == null) return;
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(dpToPx(8));
            drawable.setColor(bgColor);
            drawable.setStroke(dpToPx(1), strokeColor);
            resolutionBadgeContainer.setBackground(drawable);
        }

        private int dpToPx(int dp) {
            float density = itemView.getResources().getDisplayMetrics().density;
            return Math.round(dp * density);
        }
    }
}
