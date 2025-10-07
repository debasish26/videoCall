package com.p2p.app;

import javax.sound.sampled.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production-ready Audio Manager - NO WEIRD NOISES!
 * Starts disabled by default, only activates when explicitly enabled
 */
public class AudioManager {
    private static final int SAMPLE_RATE = 16000;
    private static final int SAMPLE_SIZE = 16;
    private static final int CHANNELS = 1;
    // 10ms frame at 16kHz, 16-bit mono => 160 samples => 320 bytes
    private static final int FRAME_BYTES = (SAMPLE_RATE / 100) * (SAMPLE_SIZE / 8) * CHANNELS; // 320
    private static final int BUFFER_SIZE = FRAME_BYTES; // use fixed frame size for stable latency
    // VAD parameters
    private static final double VAD_TARGET_RMS = 2000.0; // target RMS for soft limiter scaling
    private static final double VAD_THRESHOLD = 300.0;   // minimum RMS to consider voice present
    private static final double VAD_DECAY = 0.9;         // moving average decay
    
    private final String remoteIp;
    private final int sendPort;
    private final int receivePort;
    
    private DatagramSocket sendSocket;
    private DatagramSocket receiveSocket;
    private SourceDataLine speakers;
    private TargetDataLine microphone;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(true);
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    
    private Thread captureThread;
    private Thread playbackThread;

    // High-pass filter state (simple DC-blocking IIR): y[n] = x[n] - x[n-1] + a*y[n-1]
    private short hpPrevInCapture = 0;
    private short hpPrevOutCapture = 0;
    private short hpPrevInPlayback = 0;
    private short hpPrevOutPlayback = 0;
    private static final double HP_A = 0.995; // pole close to 1 for low cutoff

    // VAD moving average state
    private double vadAvgCapture = 0.0;
    private double vadAvgPlayback = 0.0;
    
    public AudioManager(String remoteIp, int sendPort, int receivePort) {
        this.remoteIp = remoteIp;
        this.sendPort = sendPort;
        this.receivePort = receivePort;
    }
    
    public boolean initialize() {
        try {
            sendSocket = new DatagramSocket();
            receiveSocket = new DatagramSocket(receivePort);
            receiveSocket.setSoTimeout(1000);
            
            System.out.println("AudioManager: Initialized (DISABLED - use /audio to enable)");
            return true;
            
        } catch (Exception e) {
            System.err.println("AudioManager: Failed to initialize - " + e.getMessage());
            return false;
        }
    }
    
    public void enableAudio() {
        if (enabled.get()) {
            System.out.println("AudioManager: Already enabled");
            return;
        }
        
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE, CHANNELS, true, false);
            
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
            
            if (!AudioSystem.isLineSupported(micInfo) || !AudioSystem.isLineSupported(speakerInfo)) {
                System.err.println("AudioManager: Audio lines not supported");
                return;
            }
            
            microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
            speakers = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            
            microphone.open(format, BUFFER_SIZE * 4); // slightly larger internal buffer
            speakers.open(format, BUFFER_SIZE * 8);   // extra headroom to avoid underruns
            
            microphone.start();
            speakers.start();
            
            enabled.set(true);
            running.set(true);
            
            captureThread = new Thread(this::captureLoop, "AudioCapture");
            playbackThread = new Thread(this::playbackLoop, "AudioPlayback");
            
            captureThread.start();
            playbackThread.start();
            
            System.out.println("✓ AudioManager: ENABLED (muted - use /mute to unmute)");
            
        } catch (Exception e) {
            System.err.println("AudioManager: Failed to enable - " + e.getMessage());
        }
    }
    
    public void disableAudio() {
        running.set(false);
        enabled.set(false);
        
        try {
            if (captureThread != null) captureThread.interrupt();
            if (playbackThread != null) playbackThread.interrupt();
            
            if (microphone != null) {
                microphone.stop();
                microphone.close();
            }
            
            if (speakers != null) {
                speakers.stop();
                speakers.close();
            }
            
            System.out.println("✓ AudioManager: DISABLED");
            
        } catch (Exception e) {
            System.err.println("AudioManager: Error disabling - " + e.getMessage());
        }
    }
    
    public void toggleMute() {
        if (!enabled.get()) {
            System.out.println("⚠ Enable audio first with /audio command");
            return;
        }
        
        muted.set(!muted.get());
        System.out.println("✓ Audio " + (muted.get() ? "MUTED" : "UNMUTED"));
    }
    
    public boolean isMuted() {
        return muted.get();
    }
    
    public boolean isEnabled() {
        return enabled.get();
    }
    
    private void captureLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        
        try {
            InetAddress remoteAddress = InetAddress.getByName(remoteIp);
            
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                if (muted.get()) {
                    Thread.sleep(50);
                    continue;
                }
                
                int bytesRead = microphone.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    // process: high-pass + soft limiter + VAD
                    processHighPassCapture(buffer, bytesRead);
                    double rms = computeRms(buffer, bytesRead);
                    vadAvgCapture = VAD_DECAY * vadAvgCapture + (1.0 - VAD_DECAY) * rms;
                    if (vadAvgCapture > VAD_THRESHOLD) {
                        applySoftLimiter(buffer, bytesRead, VAD_TARGET_RMS);
                        DatagramPacket packet = new DatagramPacket(buffer, 0, bytesRead, remoteAddress, sendPort);
                        sendSocket.send(packet);
                    }
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                System.err.println("AudioManager: Capture error - " + e.getMessage());
            }
        }
    }
    
    private void playbackLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiveSocket.receive(packet);
                
                if (packet.getLength() > 0) {
                    processHighPassPlayback(packet.getData(), packet.getLength());
                    double rms = computeRms(packet.getData(), packet.getLength());
                    vadAvgPlayback = VAD_DECAY * vadAvgPlayback + (1.0 - VAD_DECAY) * rms;
                    if (vadAvgPlayback > VAD_THRESHOLD) {
                        applySoftLimiter(packet.getData(), packet.getLength(), VAD_TARGET_RMS);
                        speakers.write(packet.getData(), packet.getOffset(), packet.getLength());
                    }
                }
                
            } catch (java.net.SocketTimeoutException e) {
                // Normal timeout, continue
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("AudioManager: Playback error - " + e.getMessage());
                }
            }
        }
    }
    
    private double computeRms(byte[] data, int length) {
        long sumSq = 0;
        int count = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short s = (short) ((data[i + 1] << 8) | (data[i] & 0xFF));
            sumSq += (long) s * (long) s;
            count++;
        }
        if (count == 0) return 0.0;
        return Math.sqrt(sumSq / (double) count);
    }

    private void processHighPassCapture(byte[] data, int length) {
        for (int i = 0; i + 1 < length; i += 2) {
            short x = (short) ((data[i + 1] << 8) | (data[i] & 0xFF));
            int y = (int) (x - hpPrevInCapture + HP_A * hpPrevOutCapture);
            // clamp
            if (y > Short.MAX_VALUE) y = Short.MAX_VALUE;
            if (y < Short.MIN_VALUE) y = Short.MIN_VALUE;
            hpPrevInCapture = x;
            hpPrevOutCapture = (short) y;
            data[i] = (byte) (y & 0xFF);
            data[i + 1] = (byte) ((y >>> 8) & 0xFF);
        }
    }

    private void processHighPassPlayback(byte[] data, int length) {
        for (int i = 0; i + 1 < length; i += 2) {
            short x = (short) ((data[i + 1] << 8) | (data[i] & 0xFF));
            int y = (int) (x - hpPrevInPlayback + HP_A * hpPrevOutPlayback);
            if (y > Short.MAX_VALUE) y = Short.MAX_VALUE;
            if (y < Short.MIN_VALUE) y = Short.MIN_VALUE;
            hpPrevInPlayback = x;
            hpPrevOutPlayback = (short) y;
            data[i] = (byte) (y & 0xFF);
            data[i + 1] = (byte) ((y >>> 8) & 0xFF);
        }
    }

    private void applySoftLimiter(byte[] data, int length, double targetRms) {
        double rms = computeRms(data, length);
        if (rms <= 1e-6) return;
        double scale = targetRms / rms;
        // limit maximum boost to avoid amplifying noise too much
        if (scale > 2.0) scale = 2.0;
        // slight attenuation if too loud
        if (scale < 0.5) scale = 0.5;
        for (int i = 0; i + 1 < length; i += 2) {
            short x = (short) ((data[i + 1] << 8) | (data[i] & 0xFF));
            int y = (int) Math.round(x * scale);
            if (y > Short.MAX_VALUE) y = Short.MAX_VALUE;
            if (y < Short.MIN_VALUE) y = Short.MIN_VALUE;
            data[i] = (byte) (y & 0xFF);
            data[i + 1] = (byte) ((y >>> 8) & 0xFF);
        }
    }
    
    public void shutdown() {
        System.out.println("AudioManager: Shutting down...");
        disableAudio();
        
        try {
            if (sendSocket != null) sendSocket.close();
            if (receiveSocket != null) receiveSocket.close();
        } catch (Exception e) {
            System.err.println("AudioManager: Shutdown error - " + e.getMessage());
        }
    }
}