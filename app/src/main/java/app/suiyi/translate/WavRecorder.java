package app.suiyi.translate;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;

public final class WavRecorder {
    private static final int SAMPLE_RATE = 16000;
    private volatile boolean recording;
    private volatile AudioRecord recorder;
    private CompletableFuture<byte[]> result;
    @SuppressLint("MissingPermission")
    public CompletableFuture<byte[]> start() {
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("这台手机不支持所需的录音格式。");
        AudioRecord audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 2, 8192));
        if (audio.getState() != AudioRecord.STATE_INITIALIZED) { audio.release(); throw new IllegalStateException("无法初始化麦克风。"); }
        recorder = audio; result = new CompletableFuture<>(); recording = true;
        CompletableFuture<byte[]> completion = result;
        try { audio.startRecording(); }
        catch (RuntimeException e) { recording = false; recorder = null; audio.release(); throw e; }
        new Thread(() -> {
            try (ByteArrayOutputStream pcm = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int maximum = SAMPLE_RATE * 2 * 30;
                while (recording && pcm.size() < maximum) {
                    int count = audio.read(buffer, 0, Math.min(buffer.length, maximum - pcm.size()));
                    if (count < 0 && recording) throw new IllegalStateException("录音中断，请重试。");
                    if (count > 0) pcm.write(buffer, 0, count);
                }
                recording = false;
                completion.complete(wav(pcm.toByteArray()));
            } catch (Exception e) { recording = false; completion.completeExceptionally(e); }
            finally {
                try { audio.stop(); } catch (Exception ignored) {}
                audio.release(); if (recorder == audio) recorder = null;
            }
        }, "suiyi-recorder").start();
        return completion;
    }
    public CompletableFuture<byte[]> stop() {
        recording = false;
        AudioRecord audio = recorder;
        if (audio != null) try { audio.stop(); } catch (Exception ignored) {}
        return result;
    }
    public void cancel() { stop(); }
    static byte[] wav(byte[] pcm) {
        ByteBuffer buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
            .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
            .putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
            .putShort((short) 2).putShort((short) 16)
            .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return buffer.array();
    }
}
