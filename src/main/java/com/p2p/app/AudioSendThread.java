package com.p2p.app;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ArrayBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

public class AudioSendThread extends Thread {
    private final String remoteIp;
    private final int remoteAudioPort;
    private TargetDataLine targetDataLine;
    private DatagramSocket udpSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean muted = new AtomicBoolean(true); // Start muted to prevent feedback
    private ArrayBlockingQueue<byte[]> audioQueue;
    private long packetCount = 0;

    public AudioSendThread(String remoteIp, int remoteAudioPort) {
        this.remoteIp = remoteIp;
        this.remoteAudioPort = remoteAudioPort;
        this.audioQueue = new ArrayBlockingQueue<>(10); // Small queue for low latency
    }

    @Override
    public void run() {
        AudioFormat format = new AudioFormat(
                Constants.AUDIO_SAMPLE_RATE,
                Constants.AUDIO_SAMPLE_SIZE_IN_BITS,
                Constants.AUDIO_CHANNELS,
                Constants.AUDIO_SIGNED,
                Constants.AUDIO_BIG_ENDIAN
        );

        try {
            // Set thread priority for real-time audio processing
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            
            // Initialize UDP socket
            udpSocket = new DatagramSocket();
            udpSocket.setSendBufferSize(Constants.AUDIO_BUFFER_SIZE * 4);
            System.out.println("AudioSendThread: UDP socket created for audio streaming to " + remoteIp + ":" + remoteAudioPort);
            
            // Wait a moment and check if this is a test scenario
            System.out.println("AudioSendThread: Starting MUTED to prevent feedback. Use /mute to unmute when client connects.");
            Thread.sleep(1000);

            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("AudioSendThread: Audio line not supported.");
                running.set(false);
                return;
            }
            
            targetDataLine = (TargetDataLine) AudioSystem.getLine(info);
            targetDataLine.open(format, Constants.AUDIO_BUFFER_SIZE);
            targetDataLine.start();
            System.out.println("AudioSendThread: Audio capture started with buffer size: " + Constants.AUDIO_BUFFER_SIZE);

            byte[] buffer = new byte[Constants.AUDIO_PACKET_SIZE];
            InetAddress remoteAddress = InetAddress.getByName(remoteIp);

            while (running.get()) {
                if (!muted.get()) {
                    try {
                        int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            // Send audio data via UDP (no buffering for lowest latency)
                            DatagramPacket packet = new DatagramPacket(
                                buffer, 0, bytesRead, remoteAddress, remoteAudioPort);
                            udpSocket.send(packet);
                            packetCount++;
                            PerformanceLogger.logAudioPacketSent();
                            
                            // Optional: Queue for potential future use
                            byte[] audioData = new byte[bytesRead];
                            System.arraycopy(buffer, 0, audioData, 0, bytesRead);
                            if (!audioQueue.offer(audioData)) {
                                // Queue full, just continue (prioritize real-time over buffering)
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("AudioSendThread: Error during audio capture/send: " + e.getMessage());
                        // Continue trying - UDP is connectionless
                    }
                } else {
                    // Send silence when muted to maintain timing
                    byte[] silence = new byte[Constants.AUDIO_PACKET_SIZE];
                    DatagramPacket packet = new DatagramPacket(
                        silence, silence.length, remoteAddress, remoteAudioPort);
                    try {
                        udpSocket.send(packet);
                        Thread.sleep(20); // Brief pause when muted
                    } catch (Exception e) {
                        // Continue
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("AudioSendThread: Fatal error: " + e.getMessage());
        } finally {
            stopCapture();
        }
    }

    public void toggleMute() {
        muted.set(!muted.get());
        System.out.println("Audio " + (muted.get() ? "muted" : "unmuted"));
    }

    public boolean isMuted() {
        return muted.get();
    }

    public void stopCapture() {
        running.set(false);
        if (targetDataLine != null) {
            targetDataLine.stop();
            targetDataLine.close();
            System.out.println("AudioSendThread: TargetDataLine stopped and closed.");
        }
        if (udpSocket != null) {
            udpSocket.close();
            System.out.println("AudioSendThread: UDP socket closed.");
        }
        if (audioQueue != null) {
            audioQueue.clear();
        }
        System.out.println("AudioSendThread stopped. Sent " + packetCount + " audio packets.");
    }
}
