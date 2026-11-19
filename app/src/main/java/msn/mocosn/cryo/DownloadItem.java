package msn.mocosn.cryo;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DownloadItem {
    private final File file;
    private final String name;
    private final String formattedSize;
    private final String formattedDate;
    private final boolean isVideo;
    private final String displayPath;
    private File thumbnailFile;
    private String resolution;
    private String rawResolution;
    private int videoWidth = 0;
    private int videoHeight = 0;

    public DownloadItem(File file) {
        this.file = file;
        this.name = file.getName();
        this.formattedSize = formatFileSize(file.length());
        
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());
        this.formattedDate = sdf.format(new Date(file.lastModified()));
        
        String lower = name.toLowerCase(Locale.ROOT);
        this.isVideo = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".m4v");

        // Format clean display path for UI, e.g. "📁 Download/YTDownloader/video"
        String abs = file.getParent();
        if (abs != null) {
            int dIdx = abs.indexOf("Download");
            if (dIdx != -1) {
                this.displayPath = "📁 " + abs.substring(dIdx).replace('\\', '/');
            } else {
                this.displayPath = "📁 " + (file.getParentFile() != null ? file.getParentFile().getName() : "YTDownloader");
            }
        } else {
            this.displayPath = "📁 Download/YTDownloader";
        }
    }

    public File getFile() {
        return file;
    }

    public String getName() {
        return name;
    }

    public String getFormattedSize() {
        return formattedSize;
    }

    public String getFormattedDate() {
        return formattedDate;
    }

    public boolean isVideo() {
        return isVideo;
    }

    public String getDisplayPath() {
        return displayPath;
    }

    public File getThumbnailFile() {
        return thumbnailFile;
    }

    public void setThumbnailFile(File thumbnailFile) {
        this.thumbnailFile = thumbnailFile;
    }

    public String getResolution() {
        if (resolution == null || resolution.isEmpty()) {
            return isVideo ? "MP4 Video" : "MP3 Audio";
        }
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public String getRawResolution() {
        if (rawResolution == null || rawResolution.isEmpty()) {
            return isVideo ? "MP4" : "MP3";
        }
        return rawResolution;
    }

    public void setRawResolution(String rawResolution) {
        this.rawResolution = rawResolution;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public void resolveMediaResolution(Context context) {
        if (this.resolution != null && !this.resolution.isEmpty()) return;

        // TIER 1: Check SharedPreferences (stored by MainActivity when downloaded)
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences("download_history_meta", Context.MODE_PRIVATE);
                String saved = prefs.getString(file.getAbsolutePath(), null);
                if (saved == null) saved = prefs.getString(file.getName(), null);
                if (saved == null) saved = prefs.getString(getBaseName(), null);
                if (saved != null && !saved.isEmpty()) {
                    applyQualityString(saved);
                    return;
                }
            } catch (Exception ignored) {}
        }

        // TIER 2: Check .thumbnails/{baseName}.meta file (written by python downloader)
        File metaFile = findMetaFile();
        if (metaFile != null && metaFile.exists()) {
            try {
                String content = readFileContent(metaFile);
                JSONObject json = new JSONObject(content);
                String res = json.optString("resolution");
                if (res != null && !res.isEmpty()) {
                    applyQualityString(res);
                    return;
                }
            } catch (Exception ignored) {}
        }

        // TIER 3: Inspect actual video container (pure Java MP4 atom box parser)
        if (isVideo) {
            int[] dims = parseMp4Dimensions(file);
            if (dims != null && dims[0] > 0 && dims[1] > 0) {
                applyDimensions(dims[0], dims[1]);
                return;
            }
        }

        // TIER 4: Native MediaMetadataRetriever via open FileInputStream FileDescriptor
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (FileInputStream fis = new FileInputStream(file)) {
            retriever.setDataSource(fis.getFD());

            if (isVideo) {
                String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (widthStr != null && heightStr != null) {
                    int w = Integer.parseInt(widthStr);
                    int h = Integer.parseInt(heightStr);
                    if (w > 0 && h > 0) {
                        applyDimensions(w, h);
                        return;
                    }
                }
            } else {
                String bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                if (bitrateStr != null) {
                    int bps = Integer.parseInt(bitrateStr);
                    int kbps = Math.round(bps / 1000f);
                    if (kbps > 0) {
                        this.resolution = kbps + " kbps";
                        this.rawResolution = kbps + "k";
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }

        // TIER 5: MP3 frame header parsing (for audio files)
        if (!isVideo) {
            int mp3Kbps = parseMp3Bitrate(file);
            if (mp3Kbps > 0) {
                this.resolution = mp3Kbps + " kbps";
                this.rawResolution = mp3Kbps + "k";
                return;
            }
        }

        // TIER 6: Intelligent filename pattern matching
        parseResolutionFromFilename();

        // Cache the detected resolution into SharedPreferences so subsequent history scans are instantaneous (0 ms)
        if (context != null && this.resolution != null && !this.resolution.isEmpty()) {
            try {
                SharedPreferences prefs = context.getSharedPreferences("download_history_meta", Context.MODE_PRIVATE);
                prefs.edit()
                        .putString(file.getAbsolutePath(), this.resolution)
                        .putString(file.getName(), this.resolution)
                        .apply();
            } catch (Exception ignored) {}
        }
    }

    public void applyQualityString(String q) {
        if (q == null || q.isEmpty()) return;
        String lower = q.toLowerCase(Locale.ROOT);

        if (lower.contains("4320p") || lower.contains("8k")) {
            this.resolution = "8K Ultra HD";
            this.rawResolution = "8K";
        } else if (lower.contains("2160p") || lower.contains("4k")) {
            this.resolution = "4K Ultra HD";
            this.rawResolution = "4K";
        } else if (lower.contains("1440p") || lower.contains("2k")) {
            this.resolution = "1440p 2K";
            this.rawResolution = "1440p";
        } else if (lower.contains("1080p")) {
            this.resolution = "1080p FHD";
            this.rawResolution = "1080p";
        } else if (lower.contains("720p")) {
            this.resolution = "720p HD";
            this.rawResolution = "720p";
        } else if (lower.contains("480p")) {
            this.resolution = "480p SD";
            this.rawResolution = "480p";
        } else if (lower.contains("360p")) {
            this.resolution = "360p";
            this.rawResolution = "360p";
        } else if (lower.contains("240p")) {
            this.resolution = "240p";
            this.rawResolution = "240p";
        } else if (lower.contains("144p")) {
            this.resolution = "144p";
            this.rawResolution = "144p";
        } else if (lower.contains("320")) {
            this.resolution = "320 kbps";
            this.rawResolution = "320k";
        } else if (lower.contains("256")) {
            this.resolution = "256 kbps";
            this.rawResolution = "256k";
        } else if (lower.contains("192")) {
            this.resolution = "192 kbps";
            this.rawResolution = "192k";
        } else if (lower.contains("128")) {
            this.resolution = "128 kbps";
            this.rawResolution = "128k";
        } else if (lower.contains("64")) {
            this.resolution = "64 kbps";
            this.rawResolution = "64k";
        } else {
            this.resolution = isVideo ? "MP4 Video" : "MP3 Audio";
            this.rawResolution = isVideo ? "MP4" : "MP3";
        }
    }

    public void applyDimensions(int w, int h) {
        this.videoWidth = w;
        this.videoHeight = h;
        int minDim = Math.min(w, h);
        int maxDim = Math.max(w, h);

        if (maxDim >= 7000 || minDim >= 4000) {
            this.resolution = "8K Ultra HD";
            this.rawResolution = "8K";
        } else if (maxDim >= 3500 || minDim >= 2000) {
            this.resolution = "4K Ultra HD";
            this.rawResolution = "4K";
        } else if (maxDim >= 2400 || minDim >= 1350) {
            this.resolution = "1440p 2K";
            this.rawResolution = "1440p";
        } else if (maxDim >= 1800 || minDim >= 950) {
            this.resolution = "1080p FHD";
            this.rawResolution = "1080p";
        } else if (maxDim >= 1200 || minDim >= 650) {
            this.resolution = "720p HD";
            this.rawResolution = "720p";
        } else if (maxDim >= 800 || minDim >= 440) {
            this.resolution = "480p SD";
            this.rawResolution = "480p";
        } else if (maxDim >= 600 || minDim >= 320) {
            this.resolution = "360p";
            this.rawResolution = "360p";
        } else if (maxDim >= 400 || minDim >= 200) {
            this.resolution = "240p";
            this.rawResolution = "240p";
        } else if (minDim > 0) {
            this.resolution = minDim + "p";
            this.rawResolution = minDim + "p";
        } else {
            this.resolution = "MP4 Video";
            this.rawResolution = "MP4";
        }
    }

    private void parseResolutionFromFilename() {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("4320p") || lower.contains("8k")) {
            this.resolution = "8K Ultra HD";
            this.rawResolution = "8K";
        } else if (lower.contains("2160p") || lower.contains("4k")) {
            this.resolution = "4K Ultra HD";
            this.rawResolution = "4K";
        } else if (lower.contains("1440p") || lower.contains("2k")) {
            this.resolution = "1440p 2K";
            this.rawResolution = "1440p";
        } else if (lower.contains("1080p")) {
            this.resolution = "1080p FHD";
            this.rawResolution = "1080p";
        } else if (lower.contains("720p")) {
            this.resolution = "720p HD";
            this.rawResolution = "720p";
        } else if (lower.contains("480p")) {
            this.resolution = "480p SD";
            this.rawResolution = "480p";
        } else if (lower.contains("360p")) {
            this.resolution = "360p";
            this.rawResolution = "360p";
        } else if (lower.contains("240p")) {
            this.resolution = "240p";
            this.rawResolution = "240p";
        } else if (lower.contains("144p")) {
            this.resolution = "144p";
            this.rawResolution = "144p";
        } else if (lower.contains("320kbps") || lower.contains("320k")) {
            this.resolution = "320 kbps";
            this.rawResolution = "320k";
        } else if (lower.contains("256kbps") || lower.contains("256k")) {
            this.resolution = "256 kbps";
            this.rawResolution = "256k";
        } else if (lower.contains("192kbps") || lower.contains("192k")) {
            this.resolution = "192 kbps";
            this.rawResolution = "192k";
        } else if (lower.contains("128kbps") || lower.contains("128k")) {
            this.resolution = "128 kbps";
            this.rawResolution = "128k";
        } else if (lower.contains("64kbps") || lower.contains("64k")) {
            this.resolution = "64 kbps";
            this.rawResolution = "64k";
        } else {
            this.resolution = isVideo ? "MP4 Video" : "MP3 Audio";
            this.rawResolution = isVideo ? "MP4" : "MP3";
        }
    }

    private String getBaseName() {
        String fName = file.getName();
        int dot = fName.lastIndexOf('.');
        return (dot > 0) ? fName.substring(0, dot) : fName;
    }

    private File findMetaFile() {
        String baseName = getBaseName();
        if (file.getParentFile() != null) {
            File meta1 = new File(file.getParentFile(), ".thumbnails/" + baseName + ".meta");
            if (meta1.exists()) return meta1;
            File grandParent = file.getParentFile().getParentFile();
            if (grandParent != null) {
                File meta2 = new File(grandParent, ".thumbnails/" + baseName + ".meta");
                if (meta2.exists()) return meta2;
            }
        }
        return null;
    }

    private String readFileContent(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private static int[] parseMp4Dimensions(File f) {
        if (f == null || !f.exists() || f.length() < 32) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long length = raf.length();
            long pos = 0;
            int boxCount = 0;
            while (pos + 8 <= length && boxCount < 60) {
                boxCount++;
                raf.seek(pos);
                long boxSize = raf.readInt() & 0xFFFFFFFFL;
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes, "ISO-8859-1");

                if (boxSize == 1) {
                    boxSize = raf.readLong();
                } else if (boxSize == 0) {
                    boxSize = length - pos;
                }
                if (boxSize < 8) break;

                if ("moov".equals(type) || "trak".equals(type) || "mdia".equals(type)) {
                    // Enter container box
                    pos += 8;
                    continue;
                }

                if ("tkhd".equals(type)) {
                    int version = raf.readUnsignedByte();
                    raf.skipBytes(3); // flags
                    if (version == 1) {
                        raf.skipBytes(8 + 8 + 4 + 4 + 8); // ctime(8), mtime(8), id(4), res(4), duration(8)
                    } else {
                        raf.skipBytes(4 + 4 + 4 + 4 + 4);
                    }
                    raf.skipBytes(8 + 2 + 2 + 2 + 2 + 36); // reserved(8), layer(2), alt(2), vol(2), res(2), matrix(36)
                    int w = raf.readInt() >> 16;
                    int h = raf.readInt() >> 16;
                    if (w > 0 && h > 0) {
                        return new int[]{w, h};
                    }
                }

                pos += boxSize;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int parseMp3Bitrate(File f) {
        if (f == null || !f.exists() || f.length() < 128) return 0;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buffer = new byte[8192];
            int read = fis.read(buffer);
            int offset = 0;
            if (read >= 10 && buffer[0] == 'I' && buffer[1] == 'D' && buffer[2] == '3') {
                int tagSize = ((buffer[6] & 0x7F) << 21) | ((buffer[7] & 0x7F) << 14) | ((buffer[8] & 0x7F) << 7) | (buffer[9] & 0x7F);
                offset = 10 + tagSize;
                if (offset >= read) {
                    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                        raf.seek(offset);
                        read = raf.read(buffer);
                        offset = 0;
                    }
                }
            }

            final int[] bitratesV1L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
            final int[] bitratesV2L3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};

            for (int i = offset; i < read - 3; i++) {
                if ((buffer[i] & 0xFF) == 0xFF && (buffer[i + 1] & 0xE0) == 0xE0) {
                    int ver = (buffer[i + 1] >> 3) & 0x03;
                    int layer = (buffer[i + 1] >> 1) & 0x03;
                    int brIndex = (buffer[i + 2] >> 4) & 0x0F;
                    if (brIndex > 0 && brIndex < 15) {
                        if (ver == 3 && layer == 1) {
                            return bitratesV1L3[brIndex];
                        } else if (layer == 1) {
                            return bitratesV2L3[brIndex];
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
