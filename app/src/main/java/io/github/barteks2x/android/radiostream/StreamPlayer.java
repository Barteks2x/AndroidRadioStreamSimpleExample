package io.github.barteks2x.android.radiostream;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

public class StreamPlayer {

    private static final String TAG = StreamPlayer.class.getSimpleName();
    private final PowerManager.WakeLock wakeLock;

    private Handler bgHandler;
    private final WifiManager.WifiLock wifiLock;
    private final String streamUrl;

    private boolean active = false;
    private boolean paused;

    private AudioTrack audioTrack;
    private MediaFormat format;

    private MediaCodec decoder;
    private MediaCodecCallback codecCallback;

    public StreamPlayer(PowerManager.WakeLock wakeLock, WifiManager.WifiLock wifiLock, String url) {
        this.wifiLock = wifiLock;
        streamUrl = url;
        this.wakeLock = wakeLock;
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Looper.prepare();
                    bgHandler = new Handler(Looper.myLooper());
                    Looper.loop();
                } catch (Throwable t) {
                    Log.e("StreamPlayer thread", "Error", t);
                }
            }
        }, "StreamPlayer thread");
        thread.start();
    }

    public void play() {
        bgHandler.post(() -> {
            try {
                Log.i(TAG, "PLAY, active=" + active + " streamSource=" + streamUrl);
                if (active) {
                    if (paused) {
                        paused = false;
                        audioTrack.play();
                        startDecoder();
                    }
                    return;
                }
                active = true;
                wakeLock.acquire(Long.MAX_VALUE);
                wifiLock.acquire();
                try {
                    startStream();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error", t);
            }
        });
    }

    public void stop() {
        bgHandler.post(() -> {
            if (!active) {
                return;
            }
            active = false;
            decoder.stop();
            audioTrack.stop();
            audioTrack.release();
            decoder = null;
            audioTrack = null;
            wakeLock.release();
            wifiLock.release();
        });
    }

    private void startDecoder() {
        decoder.setCallback(codecCallback, bgHandler);
        decoder.configure(this.format, null, null, 0);
        decoder.start();
    }

    private void startStream() throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(streamUrl);
        extractor.selectTrack(0);
        this.format = extractor.getTrackFormat(0);

        Log.i(TAG, "Stream media format " + this.format);

        this.codecCallback = new MediaCodecCallback(extractor);
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        startDecoder();
    }

    private class MediaCodecCallback extends MediaCodec.Callback {

        private MediaExtractor extractor;
        private int initialWrittenData;

        public MediaCodecCallback(MediaExtractor extractor) {
            this.extractor = extractor;
            initialWrittenData = 0;
        }

        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            ByteBuffer inputBuffer = codec.getInputBuffer(index);
            inputBuffer.clear();
            int i = extractor.readSampleData(inputBuffer, 0);
            if (i < 0) {
                try {
                    extractor.release();
                    extractor = new MediaExtractor();
                    extractor.setDataSource(streamUrl);
                    extractor.selectTrack(0);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                i = 1;
            }
            extractor.advance();
            codec.queueInputBuffer(index, 0, i, 0, 0);
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            audioTrack.write(codec.getOutputBuffer(index), info.size, AudioTrack.WRITE_BLOCKING);
            if (initialWrittenData != -1) {
                initialWrittenData += info.size;
                if (initialWrittenData > 16*1024) {
                    audioTrack.play();
                    initialWrittenData = -1;
                }
            }
            codec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            // TODO: onError
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
            }
            audioTrack = new AudioTrack.Builder()
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build())
                    .setBufferSizeInBytes(1024 * 1024)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(format.getInteger("sample-rate"))
                            .build())
                    .build();
            Log.i(TAG, "Out format: " + format);
        }

    }
}