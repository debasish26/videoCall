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

    public AudioReceiveThread(int listenPort) {
        this.listenPort = listenPort;
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
            // Set thread priority for real-time audio playback
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            
            // Initialize UDP socket
            udpSocket = new DatagramSocket(listenPort);
            udpSocket.setReceiveBufferSize(Constants.AUDIO_BUFFER_SIZE * 4);
            System.out.println("AudioReceiveThread: Listening for UDP audio on port " + listenPort);

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                System.err.println("AudioReceiveThread: Audio playback line not supported.");
                running.set(false);
                return;
            }
            
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
            sourceDataLine.open(format, Constants.AUDIO_BUFFER_SIZE);
            sourceDataLine.start();
            System.out.println("AudioReceiveThread: Audio playback started with buffer size: " + Constants.AUDIO_BUFFER_SIZE);

            byte[] buffer = new byte[Constants.AUDIO_PACKET_SIZE * 2]; // Larger receive buffer
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            while (running.get()) {
                try {
                    udpSocket.receive(packet);
                    if (packet.getLength() > 0) {
                        sourceDataLine.write(packet.getData(), packet.getOffset(), packet.getLength());
                        packetCount++;
                        PerformanceLogger.logAudioPacketReceived();
                    }
                } catch (java.net.SocketTimeoutException e) {
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
