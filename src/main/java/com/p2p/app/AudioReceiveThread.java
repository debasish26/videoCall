package com.p2p.app;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

public class AudioReceiveThread extends Thread {
    private DatagramSocket udpSocket;
    private SourceDataLine sourceDataLine;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int listenPort;
    private long packetCount = 0;
    private boolean hasClientConnected = false;
    private long lastPacketTime = 0;
    private AudioFormat audioFormat;
    private DataLine.Info audioInfo;

    public AudioReceiveThread(int listenPort) {
        this.listenPort = listenPort;
    }

    @Override
    public void run() {
        audioFormat = new AudioFormat(
                Constants.AUDIO_SAMPLE_RATE,
                Constants.AUDIO_SAMPLE_SIZE_IN_BITS,
                Constants.AUDIO_CHANNELS,
                Constants.AUDIO_SIGNED,
                Constants.AUDIO_BIG_ENDIAN
        );

        try {
            // Set thread priority for real-time audio playback
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            
            // Initialize UDP socket
            udpSocket = new DatagramSocket(listenPort);
            udpSocket.setReceiveBufferSize(Constants.AUDIO_BUFFER_SIZE * 4);
            udpSocket.setSoTimeout(1000); // 1 second timeout to prevent blocking
            System.out.println("AudioReceiveThread: Listening for UDP audio on port " + listenPort + " (muted until client connects)");

            // Don't initialize audio playback until we receive actual data
            audioInfo = new DataLine.Info(SourceDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(audioInfo)) {
                System.err.println("AudioReceiveThread: Audio playback line not supported.");
                running.set(false);
                return;
            }
            
            // sourceDataLine will be initialized when first audio packet arrives
            System.out.println("AudioReceiveThread: Audio system ready (playback will start when client connects)");

            byte[] buffer = new byte[Constants.AUDIO_PACKET_SIZE * 2]; // Larger receive buffer
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            while (running.get()) {
                try {
                    udpSocket.receive(packet);
                    if (packet.getLength() > 0) {
                        // Only play audio if we've detected actual audio data (not silence)
                        byte[] data = packet.getData();
                        boolean hasAudioData = false;
                        
                        // Check if packet contains actual audio data (not just silence)
                        for (int i = packet.getOffset(); i < packet.getOffset() + packet.getLength(); i++) {
                            if (data[i] != 0) {
                                hasAudioData = true;
                                break;
                            }
                        }
                        
                        if (hasAudioData) {
                            if (!hasClientConnected) {
                                hasClientConnected = true;
                                System.out.println("AudioReceiveThread: Client audio detected - initializing playback");
                                
                                // Initialize audio playback now
                                sourceDataLine = (SourceDataLine) AudioSystem.getLine(audioInfo);
                                sourceDataLine.open(audioFormat, Constants.AUDIO_BUFFER_SIZE);
                                sourceDataLine.start();
                                System.out.println("AudioReceiveThread: Audio playback started");
                            }
                            
                            if (sourceDataLine != null) {
                                sourceDataLine.write(packet.getData(), packet.getOffset(), packet.getLength());
                            }
                            lastPacketTime = System.currentTimeMillis();
                        }
                        
                        packetCount++;
                        PerformanceLogger.logAudioPacketReceived();
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // Check if client has disconnected (no packets for 5 seconds)
                    if (hasClientConnected && (System.currentTimeMillis() - lastPacketTime > 5000)) {
                        hasClientConnected = false;
                        System.out.println("AudioReceiveThread: No audio data for 5s - client may have disconnected");
                    }
                    // Continue waiting for packets
                } catch (Exception e) {
                    if (running.get()) {
                        System.err.println("AudioReceiveThread: Error receiving audio: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("AudioReceiveThread: Fatal error: " + e.getMessage());
        } finally {
            stopReception();
        }
    }

    public void stopReception() {
        running.set(false);
        if (sourceDataLine != null) {
            sourceDataLine.stop();
            sourceDataLine.close();
            System.out.println("AudioReceiveThread: SourceDataLine stopped and closed.");
        }
        if (udpSocket != null) {
            udpSocket.close();
            System.out.println("AudioReceiveThread: UDP socket closed.");
        }
        System.out.println("AudioReceiveThread stopped. Received " + packetCount + " audio packets.");
    }
}
