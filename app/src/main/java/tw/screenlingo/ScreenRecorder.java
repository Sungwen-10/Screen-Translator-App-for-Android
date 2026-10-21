package tw.screenlingo;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

/** Encoder/muxer confined to ScreenCapture's worker; no second projection or virtual display. */
final class ScreenRecorder {
    private static final int RATE = 44100, CHANNELS = 2;
    private final Context context;
    private Uri destination;
    private ParcelFileDescriptor descriptor;
    private MediaMuxer muxer;
    private MediaCodec video, audio;
    private AudioRecord playback;
    private Surface surface;
    private int videoTrack = -1, audioTrack = -1;
    private boolean started, videoEos, audioEos, audioInputEnded, finished;
    private long videoOrigin = -1, videoLast = -1, audioLast = -1, samples;
    private int videoSamples;
    private int videoWidth, videoHeight;
    private final List<Sample> waiting = new ArrayList<>();
    private int waitingBytes;
    private record Sample(boolean video, byte[] bytes, long time, int flags) { }

    ScreenRecorder(Context context) { this.context = context.getApplicationContext(); }
    Surface surface() { return surface; }
    int width() { return videoWidth; }
    int height() { return videoHeight; }
    boolean hasAudio() { return audio != null; }

    void start(MediaProjection projection, int width, int height, boolean sound) throws Exception {
        try {
            if (sound && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new IOException("請先在設定允許遊戲音訊錄製，或關閉「錄製遊戲聲音」。");
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenLingo_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date()) + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenLingo");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            destination = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IOException("無法建立錄影檔，請確認儲存空間。");
            descriptor = context.getContentResolver().openFileDescriptor(destination, "rw");
            if (descriptor == null) throw new IOException("無法開啟錄影檔。");
            muxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            float scale = Math.min(1, 1920f / Math.max(width, height));
            int w = Math.max(2, (int) (width * scale) / 2 * 2), h = Math.max(2, (int) (height * scale) / 2 * 2);
            videoWidth = w; videoHeight = h;
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30); format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30);
            video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = video.createInputSurface(); video.start();
            if (sound) {
                MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, 128000); af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
                audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                audio.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); audio.start();
                AudioFormat pcm = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
                AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
                int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                playback = new AudioRecord.Builder().setAudioFormat(pcm).setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(Math.max(32768, minimum * 4)).build();
                if (playback.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("遊戲音訊擷取無法啟用，可改用靜音錄影。");
                playback.startRecording();
            }
        } catch (Exception e) { release(false); throw e; }
    }
    void pump() throws Exception {
        if (finished) return;
        if (audio != null) {
            // Drain all available playback data, but never block OCR or projection callbacks.
            for (int i = 0; i < 8; i++) if (!feedAudio(false)) break;
            drain(audio, false);
        }
        drain(video, true);
    }
    private boolean feedAudio(boolean end) throws Exception {
        int index = audio.dequeueInputBuffer(0); if (index < 0) return false;
        ByteBuffer buffer = audio.getInputBuffer(index); if (buffer == null) throw new IOException("音訊編碼器無法取得緩衝區。");
        buffer.clear();
        int count = end ? 0 : playback.read(buffer, Math.min(buffer.capacity(), 8192), AudioRecord.READ_NON_BLOCKING);
        if (count < 0) throw new IOException("遊戲音訊擷取已中斷。");
        long time = samples * 1_000_000L / RATE;
        audio.queueInputBuffer(index, 0, count, time, end ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
        samples += count / (CHANNELS * 2); if (end) audioInputEnded = true;
        return count > 0 || end;
    }
    private void drain(MediaCodec codec, boolean isVideo) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int i = 0; i < 100; i++) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isVideo) videoTrack = muxer.addTrack(codec.getOutputFormat()); else audioTrack = muxer.addTrack(codec.getOutputFormat());
                if (!started && videoTrack != -1 && (audio == null || audioTrack != -1)) {
                    muxer.start(); started = true;
                    for (Sample sample : waiting) write(sample); waiting.clear(); waitingBytes = 0;
                }
                continue;
            }
            if (index < 0) continue;
            try {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer output = codec.getOutputBuffer(index);
                    if (output == null) throw new IOException("錄影編碼器未提供輸出。");
                    output.position(info.offset); output.limit(info.offset + info.size);
                    byte[] bytes = new byte[info.size]; output.get(bytes);
                    long time = info.presentationTimeUs;
                    if (isVideo) { if (videoOrigin < 0) videoOrigin = time; time = Math.max(0, time - videoOrigin); }
                    Sample sample = new Sample(isVideo, bytes, time, info.flags);
                    if (started) write(sample);
                    else {
                        waitingBytes += bytes.length;
                        if (waitingBytes > 16 * 1024 * 1024) throw new IOException("錄影編碼器未就緒，請停止後重試。");
                        waiting.add(sample);
                    }
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { if (isVideo) videoEos = true; else audioEos = true; }
            } finally { codec.releaseOutputBuffer(index, false); }
        }
    }
    private void write(Sample sample) {
        long last = sample.video ? videoLast : audioLast;
        long time = Math.max(last + 1, sample.time);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); info.set(0, sample.bytes.length, time, sample.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        muxer.writeSampleData(sample.video ? videoTrack : audioTrack, ByteBuffer.wrap(sample.bytes), info);
        if (sample.video) { videoLast = time; videoSamples++; } else audioLast = time;
    }
    Uri finish() throws Exception {
        if (finished) return null;
        boolean valid = false; Uri saved = destination;
        try {
            video.signalEndOfInputStream();
            long deadline = SystemClock.elapsedRealtime() + 4000;
            while ((!videoEos || (audio != null && !audioEos)) && SystemClock.elapsedRealtime() < deadline) {
                if (audio != null && !audioInputEnded) feedAudio(true);
                if (audio != null) drain(audio, false); drain(video, true);
                if (!videoEos || (audio != null && !audioEos)) SystemClock.sleep(5);
            }
            if (!started || videoSamples == 0 || !videoEos || (audio != null && !audioEos)) throw new IOException("錄影太短或未完整結束，未保留不完整影片。");
            muxer.stop(); started = false;
            ContentValues values = new ContentValues(); values.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (context.getContentResolver().update(destination, values, null, null) != 1) throw new IOException("無法完成影片儲存。");
            valid = true;
            new Prefs(context).put("lastVideo", saved.toString());
            return saved;
        } finally { release(valid); }
    }
    void abort() { release(false); }
    private void release(boolean keep) {
        if (finished) return; finished = true;
        if (playback != null) { try { playback.stop(); } catch (Exception ignored) { } playback.release(); playback = null; }
        for (MediaCodec codec : new MediaCodec[]{audio, video}) if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } try { codec.release(); } catch (Exception ignored) { } }
        audio = video = null;
        if (surface != null) { surface.release(); surface = null; }
        if (muxer != null) { try { muxer.release(); } catch (Exception ignored) { } muxer = null; }
        if (descriptor != null) { try { descriptor.close(); } catch (Exception ignored) { } descriptor = null; }
        if (!keep && destination != null) { try { context.getContentResolver().delete(destination, null, null); } catch (Exception ignored) { } }
        waiting.clear();
    }
}
