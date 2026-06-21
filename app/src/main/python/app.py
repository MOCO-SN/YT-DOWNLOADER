from flask import Flask, render_template, request, send_from_directory, jsonify
from pytubefix import YouTube
import os
import threading
import time
import subprocess
import re
from java import jclass

app = Flask(__name__)

DOWNLOAD_FOLDER = None

def get_welcome_message(name):
    return f"Hello {name}, welcome to the Python-powered Android app!"

def get_video_info(url):
    try:
        yt = YouTube(url)
        resolutions = set()
        for stream in yt.streams.filter(file_extension="mp4"):
            if stream.resolution:
                resolutions.add(stream.resolution)
        
        res_list = sorted(list(resolutions), key=lambda x: int(x.replace("p", "")), reverse=True)
        res_list.insert(0, "highest")
        
        return {
            "title": yt.title,
            "thumbnail": yt.thumbnail_url,
            "resolutions": res_list
        }
    except Exception as e:
        return {"error": str(e)}

def init_storage():
    global DOWNLOAD_FOLDER
    try:
        Environment = jclass("android.os.Environment")
        public_storage = str(Environment.getExternalStorageDirectory())
        DOWNLOAD_FOLDER = os.path.join(public_storage, "cryo")
        os.makedirs(DOWNLOAD_FOLDER, exist_ok=True)
        os.makedirs(os.path.join(DOWNLOAD_FOLDER, "video"), exist_ok=True)
        os.makedirs(os.path.join(DOWNLOAD_FOLDER, "audio"), exist_ok=True)
        return True
    except Exception as e:
        print(f"Storage Init Error: {e}")
        return False

def download_video(url, file_type, resolution, progress_callback=None):
    global DOWNLOAD_FOLDER
    if DOWNLOAD_FOLDER is None:
        if not init_storage():
            return "Error: Could not initialize storage."

    def on_progress(stream, chunk, bytes_remaining):
        if progress_callback:
            total_size = stream.filesize
            bytes_downloaded = total_size - bytes_remaining
            percentage_of_completion = bytes_downloaded / total_size * 100
            progress_callback.onProgress(int(percentage_of_completion))

    try:
        yt = YouTube(url, on_progress_callback=on_progress)
        title = safe_filename(yt.title)
        
        if file_type == "mp3":
            target_dir = os.path.join(DOWNLOAD_FOLDER, "audio")
            os.makedirs(target_dir, exist_ok=True)
            stream = yt.streams.filter(only_audio=True).order_by("abr").desc().first()
            if not stream: return "Error: Audio stream not found"
            
            downloaded_file = stream.download(output_path=target_dir)
            final_file = os.path.join(target_dir, f"{title}.mp3")
            if os.path.exists(final_file): os.remove(final_file)
            os.rename(downloaded_file, final_file)
            return f"Downloaded: {title}.mp3"
        
        else:
            target_dir = os.path.join(DOWNLOAD_FOLDER, "video")
            os.makedirs(target_dir, exist_ok=True)
            
            # Use progressive for simpler progress tracking if available
            stream = None
            if resolution == "highest":
                stream = yt.streams.filter(progressive=True, file_extension="mp4").order_by("resolution").desc().first()
            else:
                stream = yt.streams.filter(progressive=True, res=resolution, file_extension="mp4").first()
            
            if stream:
                path = stream.download(output_path=target_dir)
                return f"Downloaded: {title}.mp4"
            
            # If high quality requested, download separate and merge (note: merging won't show progress for audio part here)
            if resolution == "highest":
                video_stream = yt.streams.filter(only_video=True, file_extension="mp4").order_by("resolution").desc().first()
            else:
                video_stream = yt.streams.filter(only_video=True, res=resolution, file_extension="mp4").first()
            
            audio_stream = yt.streams.filter(only_audio=True).order_by("abr").desc().first()
            
            if not video_stream or not audio_stream:
                return "Error: Could not find required streams."
            
            Python = jclass("com.chaquo.python.Python")
            context = Python.getPlatform().getApplication()
            temp_dir = str(context.getCacheDir())
            
            # Download video (will show progress via callback)
            v_temp = video_stream.download(output_path=temp_dir, filename="v_temp.mp4")
            
            # Download audio (will also show progress, but total percentage might jump)
            a_temp = audio_stream.download(output_path=temp_dir, filename="a_temp.m4a")
            
            output_path = os.path.join(target_dir, f"{title}.mp4")
            if os.path.exists(output_path): os.remove(output_path)
            
            FFmpegKit = jclass("com.arthenica.ffmpegkit.FFmpegKit")
            cmd = f'-i "{v_temp}" -i "{a_temp}" -c:v copy -c:a aac -shortest "{output_path}"'
            session = FFmpegKit.execute(cmd)
            
            if os.path.exists(v_temp): os.remove(v_temp)
            if os.path.exists(a_temp): os.remove(a_temp)
            
            if session.getReturnCode().isValueSuccess():
                return f"Downloaded and Merged: {title}.mp4"
            else:
                return "Merge Error."

    except Exception as e:
        return f"Error: {str(e)}"

def safe_filename(name):
    return re.sub(r'[\\/*?:"<>|]', "", name)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
