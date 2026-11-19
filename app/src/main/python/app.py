from flask import Flask, render_template, request, send_from_directory, jsonify
import os
import re
import json
import time
import threading
import urllib.request
import yt_dlp
from pytubefix import YouTube
import pytubefix.extract as extract
try:
    from java import jclass
except ImportError:
    jclass = None

app = Flask(__name__)

DOWNLOAD_FOLDER = None

# Monkey-patch pytubefix extract.apply_signature so it does not crash when streams already have direct URLs
def safe_apply_signature(stream_manifest, vid_info, js, url_js):
    has_cipher = any(('signatureCipher' in s or 'cipher' in s) and 'url' not in s for s in stream_manifest)
    if not has_cipher:
        return
    try:
        from pytubefix.cipher import Cipher
        Cipher(js=js, js_url=url_js)
    except Exception:
        pass

extract.apply_signature = safe_apply_signature

def get_welcome_message(name):
    return f"Hello {name}, welcome to the Python-powered Android app!"

def extract_video_id(url):
    """Extract 11-character YouTube video ID from various URL patterns."""
    if not url:
        return ""
    patterns = [
        r'(?:v=|\/)([0-9A-Za-z_-]{11}).*',
        r'(?:youtu\.be\/|shorts\/|embed\/)([0-9A-Za-z_-]{11})',
    ]
    for pattern in patterns:
        match = re.search(pattern, str(url))
        if match:
            return match.group(1)
    return ""

def safe_filename(name, fallback="video"):
    """Sanitize video/audio title for valid Android and filesystem storage."""
    if not name:
        return fallback
    clean = re.sub(r'[\\/*?:"<>|]', "", name).strip()
    return clean if clean else fallback

def save_thumbnail(v_id, title):
    """Save video thumbnail so history can display high-res YouTube preview image."""
    try:
        if not v_id or not title or not DOWNLOAD_FOLDER:
            return
        thumb_dir = os.path.join(DOWNLOAD_FOLDER, ".thumbnails")
        os.makedirs(thumb_dir, exist_ok=True)
        thumb_path = os.path.join(thumb_dir, f"{title}.jpg")
        if not os.path.exists(thumb_path):
            thumb_url = f"https://i.ytimg.com/vi/{v_id}/hqdefault.jpg"
            req = urllib.request.Request(thumb_url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=4) as resp:
                with open(thumb_path, "wb") as f:
                    f.write(resp.read())
            print(f"Thumbnail saved: {thumb_path}")
    except Exception as te:
        print(f"Thumbnail save note: {te}")

def save_media_metadata(title, file_type, resolution, v_id=None):
    """Save download metadata so history accurately displays the downloaded resolution & bitrate."""
    try:
        if not title or not DOWNLOAD_FOLDER:
            return
        thumb_dir = os.path.join(DOWNLOAD_FOLDER, ".thumbnails")
        os.makedirs(thumb_dir, exist_ok=True)
        meta_path = os.path.join(thumb_dir, f"{title}.meta")
        data = {
            "title": str(title),
            "type": str(file_type),
            "resolution": str(resolution),
            "v_id": str(v_id or ""),
            "time": time.time()
        }
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(data, f)
        print(f"Media metadata saved: {meta_path} -> {resolution}")
    except Exception as me:
        print(f"Metadata save note: {me}")

def get_download_path():
    global DOWNLOAD_FOLDER
    if DOWNLOAD_FOLDER is None:
        init_storage()
    return str(DOWNLOAD_FOLDER) if DOWNLOAD_FOLDER else ""

def scan_media_file(file_path):
    """Trigger Android MediaScanner so downloaded files appear in Gallery & Music players."""
    try:
        Python = jclass("com.chaquo.python.Python")
        context = Python.getPlatform().getApplication()
        MediaScannerConnection = jclass("android.media.MediaScannerConnection")
        MediaScannerConnection.scanFile(context, [str(file_path)], None, None)
        print(f"Media scanned: {file_path}")
    except Exception as e:
        print(f"MediaScanner warning: {e}")

def get_video_info(url):
    """
    Lightning-fast video info extraction (sub-200ms) using lightweight oEmbed API.
    Does not download heavy watch page HTML.
    """
    try:
        v_id = extract_video_id(url)
        if not v_id:
            return {"error": "Invalid YouTube URL"}

        print(f"Fetching video info fast for ID: {v_id}")

        title = None
        thumb = f"https://i.ytimg.com/vi/{v_id}/hqdefault.jpg"

        # 1. Fast official YouTube oEmbed API (~150ms response, tiny 200-byte JSON)
        try:
            oembed_url = f"https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v={v_id}&format=json"
            req = urllib.request.Request(oembed_url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=1.8) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                title = data.get("title")
                if data.get("thumbnail_url"):
                    thumb = data.get("thumbnail_url")
        except Exception as oe:
            print(f"oEmbed fetch note: {oe}")

        # 2. Fast NoEmbed fallback if official endpoint timed out
        if not title:
            try:
                noembed_url = f"https://noembed.com/embed?url=https://www.youtube.com/watch?v={v_id}"
                req = urllib.request.Request(noembed_url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=1.5) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    title = data.get("title")
                    if data.get("thumbnail_url"):
                        thumb = data.get("thumbnail_url")
            except Exception as ne:
                print(f"NoEmbed fallback note: {ne}")

        if not title:
            title = f"YouTube Video ({v_id})"

        res_list = [
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
        ]

        return {
            "title": title,
            "thumbnail": thumb,
            "resolutions": res_list,
            "video_id": v_id
        }
    except Exception as e:
        print(f"Error fetching video info: {str(e)}")
        v_id = extract_video_id(url)
        return {
            "title": f"YouTube Video ({v_id})" if v_id else "YouTube Video",
            "thumbnail": f"https://i.ytimg.com/vi/{v_id}/hqdefault.jpg" if v_id else "",
            "resolutions": [
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
            ],
            "video_id": v_id or ""
        }

def init_storage():
    global DOWNLOAD_FOLDER
    try:
        Python = jclass("com.chaquo.python.Python")
        context = Python.getPlatform().getApplication()
        Environment = jclass("android.os.Environment")

        base_dir = None
        # Try standard public Downloads folder first (accessible via MANAGE_EXTERNAL_STORAGE)
        try:
            pub_download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if pub_download and os.path.exists(str(pub_download)):
                target_base = os.path.join(str(pub_download), "YTDownloader")
                os.makedirs(target_base, exist_ok=True)
                test_file = os.path.join(target_base, ".write_test")
                with open(test_file, "w") as f:
                    f.write("ok")
                os.remove(test_file)
                base_dir = target_base
        except Exception as pe:
            print(f"Public storage check fallback: {pe}")

        if not base_dir:
            ext_files = context.getExternalFilesDir(None)
            if ext_files:
                base_dir = os.path.join(str(ext_files), "downloads")
            else:
                base_dir = os.path.join(str(context.getFilesDir()), "downloads")

        DOWNLOAD_FOLDER = base_dir
        os.makedirs(DOWNLOAD_FOLDER, exist_ok=True)
        os.makedirs(os.path.join(DOWNLOAD_FOLDER, "video"), exist_ok=True)
        os.makedirs(os.path.join(DOWNLOAD_FOLDER, "audio"), exist_ok=True)
        print(f"Storage Initialized at: {DOWNLOAD_FOLDER}")
        return True
    except Exception as e:
        print(f"Storage Init Error: {e}")
        return False

def check_return_code_success(ret):
    """Safely check if FFmpegKit ReturnCode is success without raising static/instance exceptions."""
    if ret is None:
        return False
    try:
        if hasattr(ret, "isValueSuccess") and ret.isValueSuccess():
            return True
    except Exception:
        pass
    try:
        if hasattr(ret, "getValue") and ret.getValue() == 0:
            return True
    except Exception:
        pass
    try:
        ReturnCode = jclass("com.arthenica.ffmpegkit.ReturnCode")
        if ReturnCode.isSuccess(ret):
            return True
    except Exception:
        pass
    return False

def merge_video_audio(v_path, a_path, out_path):
    """Mux video stream and audio stream into final mp4 file using FFmpegKit."""
    if os.path.exists(out_path):
        try:
            os.remove(out_path)
        except Exception:
            pass

    success = False
    try:
        FFmpegKit = jclass("com.arthenica.ffmpegkit.FFmpegKit")
        cmd = f'-y -i "{v_path}" -i "{a_path}" -c:v copy -c:a aac -strict -2 "{out_path}"'
        print(f"Running FFmpeg merge: {cmd}")
        session = FFmpegKit.execute(cmd)
        ret = session.getReturnCode()
        if check_return_code_success(ret):
            success = True
        elif os.path.exists(out_path) and os.path.getsize(out_path) > 1000:
            success = True
        else:
            try:
                print(f"FFmpeg session logs: {session.getLogsAsString()}")
            except Exception:
                pass
    except Exception as e:
        print(f"FFmpegKit execution error: {e}")

    # Fallback if FFmpegKit failed: keep video file
    if not success:
        if os.path.exists(out_path):
            try:
                os.remove(out_path)
            except Exception:
                pass
        try:
            os.rename(v_path, out_path)
            success = True
        except Exception:
            pass
    else:
        # Clean up temporary streams
        if os.path.exists(v_path):
            try:
                os.remove(v_path)
            except Exception:
                pass
        if os.path.exists(a_path):
            try:
                os.remove(a_path)
            except Exception:
                pass

    return success

def convert_to_mp3(in_path, out_path, bitrate="192k"):
    """Convert downloaded audio stream to MP3 using FFmpegKit."""
    if os.path.exists(out_path):
        try:
            os.remove(out_path)
        except Exception:
            pass

    success = False
    try:
        FFmpegKit = jclass("com.arthenica.ffmpegkit.FFmpegKit")
        cmd = f'-y -i "{in_path}" -vn -b:a {bitrate} "{out_path}"'
        print(f"Running FFmpeg MP3 conversion: {cmd}")
        session = FFmpegKit.execute(cmd)
        ret = session.getReturnCode()
        if check_return_code_success(ret):
            success = True
        elif os.path.exists(out_path) and os.path.getsize(out_path) > 1000:
            success = True
        else:
            try:
                print(f"FFmpeg MP3 session logs: {session.getLogsAsString()}")
            except Exception:
                pass
    except Exception as e:
        print(f"FFmpegKit MP3 conversion error: {e}")

    if success:
        if os.path.exists(in_path):
            try:
                os.remove(in_path)
            except Exception:
                pass
    else:
        if os.path.exists(in_path):
            try:
                os.rename(in_path, out_path)
                success = True
            except Exception:
                pass

    return success

def download_video(url, file_type, resolution, progress_callback=None, video_title=None):
    global DOWNLOAD_FOLDER
    if DOWNLOAD_FOLDER is None:
        if not init_storage():
            return "Error: Could not initialize storage."

    v_id = extract_video_id(url)
    clean_url = f"https://www.youtube.com/watch?v={v_id}" if v_id else url

    # If title is provided, use it directly without re-fetching info over the network
    if video_title and str(video_title).strip() and not str(video_title).startswith("YouTube Media ("):
        title = safe_filename(str(video_title).strip())
    else:
        info = get_video_info(clean_url)
        title = safe_filename(info.get("title", f"video_{v_id}"))

    # Save thumbnail in background thread so it doesn't delay starting the download
    threading.Thread(target=save_thumbnail, args=(v_id, title), daemon=True).start()

    # Progress hook adapter for yt-dlp single stream
    def ydl_progress_hook(d):
        if progress_callback and d.get("status") == "downloading":
            try:
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                downloaded = d.get("downloaded_bytes") or 0
                if total > 0:
                    pct = int(downloaded / total * 100)
                    progress_callback.onProgress(min(100, max(0, pct)))
            except Exception:
                pass

    # METHOD 1: yt-dlp (Primary robust downloader)
    try:
        print(f"Attempting download via yt-dlp for {clean_url} (type={file_type}, res={resolution})")
        if file_type == "mp3":
            # Parse audio bitrate from user choice (e.g. "128 kbps (Medium)" -> "128k")
            bitrate_match = re.search(r'(\d+)\s*k', str(resolution), re.IGNORECASE)
            audio_bitrate = f"{bitrate_match.group(1)}k" if bitrate_match else "192k"
            print(f"Downloading audio with bitrate: {audio_bitrate}")

            target_dir = os.path.join(DOWNLOAD_FOLDER, "audio")
            os.makedirs(target_dir, exist_ok=True)
            temp_out = os.path.join(target_dir, f"temp_{v_id}.%(ext)s")
            final_file = os.path.join(target_dir, f"{title}.mp3")

            # Clean any old temp files for this video ID
            for ext in ["m4a", "webm", "opus", "mp3", "aac", "mp4", "part"]:
                p = os.path.join(target_dir, f"temp_{v_id}.{ext}")
                if os.path.exists(p):
                    try:
                        os.remove(p)
                    except Exception:
                        pass

            ydl_opts = {
                "format": "bestaudio[ext=m4a]/bestaudio/ba/b",
                "outtmpl": temp_out,
                "progress_hooks": [ydl_progress_hook],
                "quiet": True,
                "no_warnings": True,
            }

            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                ydl.download([clean_url])

            downloaded_temp = None
            for ext in ["m4a", "webm", "opus", "mp3", "aac", "mp4"]:
                check_path = os.path.join(target_dir, f"temp_{v_id}.{ext}")
                if os.path.exists(check_path):
                    downloaded_temp = check_path
                    break

            if downloaded_temp:
                convert_to_mp3(downloaded_temp, final_file, audio_bitrate)

            if os.path.exists(final_file):
                scan_media_file(final_file)
                save_media_metadata(title, "mp3", resolution, v_id)
                return f"Downloaded: {title}.mp3"

        else:
            # Video MP4 download
            target_dir = os.path.join(DOWNLOAD_FOLDER, "video")
            os.makedirs(target_dir, exist_ok=True)
            final_file = os.path.join(target_dir, f"{title}.mp4")

            # Extract target height from user choice (e.g. "4320p (8K Ultra HD)" -> 4320, "1080p (Full HD)" -> 1080)
            m_h = re.search(r'(\d+)', str(resolution))
            target_h = int(m_h.group(1)) if m_h else None

            # Clean any leftover temp files for this video ID
            for ext in ["mp4", "webm", "mkv", "m4a", "opus", "aac", "part"]:
                for prefix in [f"temp_v_{v_id}", f"temp_a_{v_id}", f"temp_{v_id}"]:
                    p = os.path.join(target_dir, f"{prefix}.{ext}")
                    if os.path.exists(p):
                        try:
                            os.remove(p)
                        except Exception:
                            pass

            temp_v_tmpl = os.path.join(target_dir, f"temp_v_{v_id}.%(ext)s")
            temp_a_tmpl = os.path.join(target_dir, f"temp_a_{v_id}.%(ext)s")

            if target_h:
                fmt_v = (
                    f"bestvideo[height<={target_h}][ext=mp4]/"
                    f"bestvideo[height<={target_h}]/"
                    f"bestvideo[ext=mp4]/"
                    f"bestvideo/"
                    f"best"
                )
            else:
                fmt_v = "bestvideo[ext=mp4]/bestvideo/best"

            fmt_a = "bestaudio[ext=m4a]/bestaudio/ba/b"

            # 1. Download video stream (progress 0% - 85%)
            def v_progress_hook(d):
                if progress_callback and d.get("status") == "downloading":
                    try:
                        total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                        downloaded = d.get("downloaded_bytes") or 0
                        if total > 0:
                            pct = int(downloaded / total * 85)
                            progress_callback.onProgress(min(85, max(0, pct)))
                    except Exception:
                        pass

            ydl_v_opts = {
                "format": fmt_v,
                "outtmpl": temp_v_tmpl,
                "progress_hooks": [v_progress_hook],
                "quiet": True,
                "no_warnings": True,
            }

            with yt_dlp.YoutubeDL(ydl_v_opts) as ydl:
                ydl.download([clean_url])

            dl_v = None
            for ext in ["mp4", "webm", "mkv"]:
                p = os.path.join(target_dir, f"temp_v_{v_id}.{ext}")
                if os.path.exists(p):
                    dl_v = p
                    break

            if dl_v:
                # 2. Download audio stream (progress 85% - 100%)
                def a_progress_hook(d):
                    if progress_callback and d.get("status") == "downloading":
                        try:
                            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                            downloaded = d.get("downloaded_bytes") or 0
                            if total > 0:
                                pct = int(downloaded / total * 15)
                                progress_callback.onProgress(min(99, max(85, 85 + pct)))
                        except Exception:
                            pass

                dl_a = None
                try:
                    ydl_a_opts = {
                        "format": fmt_a,
                        "outtmpl": temp_a_tmpl,
                        "progress_hooks": [a_progress_hook],
                        "quiet": True,
                        "no_warnings": True,
                    }
                    with yt_dlp.YoutubeDL(ydl_a_opts) as ydl:
                        ydl.download([clean_url])

                    for ext in ["m4a", "webm", "opus", "mp3", "aac"]:
                        p = os.path.join(target_dir, f"temp_a_{v_id}.{ext}")
                        if os.path.exists(p):
                            dl_a = p
                            break
                except Exception as a_err:
                    print(f"Audio download note: {a_err}")

                # 3. Merge video + audio using FFmpegKit
                if dl_a:
                    merge_video_audio(dl_v, dl_a, final_file)
                else:
                    if os.path.exists(final_file):
                        try:
                            os.remove(final_file)
                        except Exception:
                            pass
                    os.rename(dl_v, final_file)

                if progress_callback:
                    progress_callback.onProgress(100)

            # If final_file still doesn't exist, try single-stream fallback
            if not os.path.exists(final_file):
                print("Separate stream download did not produce final file, trying fallback...")
                fmt_fallback = f"best[height<={target_h}]/22/18/b/best" if target_h else "22/18/best/b"
                ydl_fallback_opts = {
                    "format": fmt_fallback,
                    "outtmpl": final_file,
                    "progress_hooks": [ydl_progress_hook],
                    "quiet": True,
                    "no_warnings": True,
                }
                with yt_dlp.YoutubeDL(ydl_fallback_opts) as ydl:
                    ydl.download([clean_url])

            if os.path.exists(final_file):
                scan_media_file(final_file)
                save_media_metadata(title, "mp4", resolution, v_id)
                return f"Downloaded: {title}.mp4"

    except Exception as ydl_err:
        print(f"yt-dlp download failed: {ydl_err}. Falling back to pytubefix...")

    # METHOD 2: pytubefix fallback
    def pytube_on_progress(stream, chunk, bytes_remaining):
        if progress_callback:
            try:
                total_size = stream.filesize
                if total_size and total_size > 0:
                    bytes_downloaded = total_size - bytes_remaining
                    pct = int(bytes_downloaded / total_size * 100)
                    progress_callback.onProgress(min(100, max(0, pct)))
            except Exception:
                pass

    # Exclude 'ANDROID' and 'IOS' as their mobile Innertube endpoints return 400 Bad Request
    clients_to_try = ['MWEB', 'ANDROID_VR', 'ANDROID_KIDS', 'TV']
    last_error = None

    for client_name in clients_to_try:
        try:
            print(f"Pytubefix attempt with client: {client_name}")
            yt = YouTube(clean_url, on_progress_callback=pytube_on_progress, client=client_name)

            if file_type == "mp3":
                target_dir = os.path.join(DOWNLOAD_FOLDER, "audio")
                os.makedirs(target_dir, exist_ok=True)
                final_file = os.path.join(target_dir, f"{title}.mp3")

                stream = yt.streams.filter(only_audio=True).order_by("abr").desc().first()
                if not stream:
                    stream = yt.streams.filter(progressive=True).order_by("resolution").desc().first()

                if stream:
                    temp_name = f"temp_pytube_{v_id}.{stream.subtype}"
                    dl_file = stream.download(output_path=target_dir, filename=temp_name)
                    convert_to_mp3(dl_file, final_file, audio_bitrate if 'audio_bitrate' in locals() else "192k")

                    if os.path.exists(final_file):
                        scan_media_file(final_file)
                        save_media_metadata(title, "mp3", resolution, v_id)
                        return f"Downloaded: {title}.mp3"
            else:
                target_dir = os.path.join(DOWNLOAD_FOLDER, "video")
                os.makedirs(target_dir, exist_ok=True)
                final_file = os.path.join(target_dir, f"{title}.mp4")

                m_h = re.search(r'(\d+)', str(resolution))
                target_h = int(m_h.group(1)) if m_h else None

                stream = None
                if target_h:
                    target_res = f"{target_h}p"
                    stream = yt.streams.filter(res=target_res, type="video").first()
                    if not stream:
                        video_streams = [s for s in yt.streams.filter(type="video") if s.resolution]
                        def parse_h(s):
                            m = re.search(r'(\d+)', s.resolution)
                            return int(m.group(1)) if m else 0
                        matching = [s for s in video_streams if parse_h(s) <= target_h]
                        matching.sort(key=parse_h, reverse=True)
                        if matching:
                            stream = matching[0]

                if not stream:
                    stream = yt.streams.filter(type="video").order_by("resolution").desc().first()

                if stream:
                    if stream.is_progressive:
                        dl_file = stream.download(output_path=target_dir, filename=f"temp_pytube_{v_id}.mp4")
                        if os.path.exists(final_file):
                            os.remove(final_file)
                        os.rename(dl_file, final_file)
                    else:
                        # High resolution adaptive stream (1080p, 1440p, 4K, 8K):
                        # Download video stream + audio stream and merge with FFmpegKit!
                        v_ext = stream.subtype or "mp4"
                        temp_v = os.path.join(target_dir, f"temp_v_{v_id}.{v_ext}")
                        temp_a = os.path.join(target_dir, f"temp_a_{v_id}.m4a")
                        if os.path.exists(temp_v):
                            try:
                                os.remove(temp_v)
                            except Exception:
                                pass
                        if os.path.exists(temp_a):
                            try:
                                os.remove(temp_a)
                            except Exception:
                                pass

                        stream.download(output_path=target_dir, filename=f"temp_v_{v_id}.{v_ext}")
                        a_stream = yt.streams.filter(only_audio=True).order_by("abr").desc().first()
                        if a_stream:
                            a_stream.download(output_path=target_dir, filename=f"temp_a_{v_id}.m4a")

                        if os.path.exists(temp_v) and os.path.exists(temp_a):
                            merge_video_audio(temp_v, temp_a, final_file)
                        elif os.path.exists(temp_v):
                            if os.path.exists(final_file):
                                os.remove(final_file)
                            os.rename(temp_v, final_file)

                    if os.path.exists(final_file):
                        scan_media_file(final_file)
                        save_media_metadata(title, "mp4", resolution, v_id)
                        return f"Downloaded: {title}.mp4"

        except Exception as pe:
            print(f"Pytubefix client '{client_name}' failed: {pe}")
            last_error = pe

    return f"Error: {str(last_error) if last_error else 'Download failed'}"

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
