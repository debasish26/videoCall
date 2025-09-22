package com.p2p.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class CliCommandThread extends Thread {
    private VideoSendThread videoSendThread;
    private AudioSendThread audioSendThread;
    private Runnable shutdownHook;
    private Socket controlSocket;
    private PrintWriter writer;
    private AtomicBoolean running = new AtomicBoolean(true);
    private final boolean isClient;
    private ServerSocket controlServerSocket = null; // Only for server mode
    private String remoteIp; // Added for client-side control connection

    public CliCommandThread(VideoSendThread videoSendThread, AudioSendThread audioSendThread, Runnable shutdownHook, String remoteIp, ServerSocket controlServerSocket) {
        this.videoSendThread = videoSendThread;
        this.audioSendThread = audioSendThread;
        this.shutdownHook = shutdownHook;
        this.remoteIp = remoteIp;
        this.controlServerSocket = controlServerSocket;

        if (controlServerSocket != null) {
            this.isClient = false;
            tryAcceptControl();
        } else {
            this.isClient = true;
            tryConnectControl(remoteIp, Constants.CONTROL_TCP_PORT);
        }
    }

    private void tryConnectControl(String remoteIp, int port) {
        while (running.get()) {
            try {
                System.out.println("CliCommandThread: Attempting to connect to remote control at " + remoteIp + ":" + port + "...");
                controlSocket = new Socket(remoteIp, port);
                writer = new PrintWriter(controlSocket.getOutputStream(), true);
                System.out.println("CliCommandThread: Connected to remote control.");
                break;
            } catch (Exception e) {
                System.err.println("CliCommandThread: Connection to remote control failed: " + e.getMessage() + ". Retrying in 2 seconds...");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                    break;
                }
            }
        }
    }

    private void tryAcceptControl() {
        while (running.get()) {
            try {
                System.out.println("CliCommandThread: Waiting for control connection on port " + controlServerSocket.getLocalPort() + "...");
                controlSocket = controlServerSocket.accept();
                writer = new PrintWriter(controlSocket.getOutputStream(), true);
                System.out.println("CliCommandThread: Control client connected from " + controlSocket.getInetAddress());
                break;
            } catch (Exception e) {
                System.err.println("CliCommandThread: Error accepting control connection: " + e.getMessage() + ". Retrying in 2 seconds...");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                    break;
                }
            }
        }
    }

    @Override
    public void run() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            while (running.get()) {
                System.out.print("Enter command (/mute, /pause, /end): ");
                String command = reader.readLine();

                if (command == null) {
                    System.out.println("CLI input stream closed. Initiating shutdown.");
                    shutdownHook.run();
                    break;
                }

                // Send command to remote peer if connected and not an /end command
                if (writer != null && controlSocket != null && controlSocket.isConnected() && !command.trim().toLowerCase().equals("/end")) {
                    try {
                        writer.println(command);
                    } catch (Exception e) {
                        System.err.println("Error sending command to remote: " + e.getMessage());
                        // Continue without complex reconnection logic for better stability
                    }
                }

                switch (command.trim().toLowerCase()) {
                    case "/mute":
                        if (audioSendThread != null) {
                            audioSendThread.toggleMute();
                            System.out.println("Local audio " + (audioSendThread.isMuted() ? "muted." : "unmuted."));
                        } else {
                            System.out.println("Audio sending not active.");
                        }
                        break;
                    case "/pause":
                        if (videoSendThread != null) {
                            videoSendThread.togglePause();
                            System.out.println("Local video " + (videoSendThread.isPaused() ? "paused." : "resumed."));
                        } else {
                            System.out.println("Video sending not active.");
                        }
                        break;
                    case "/end":
                        System.out.println("Ending call...");
                        if (writer != null) writer.println("/end"); // Notify remote even if not connected for other commands
                        shutdownHook.run();
                        running.set(false);
                        break;
                    default:
                        System.out.println("Unknown command: " + command);
                }
            }
        } catch (IOException e) {
            System.err.println("Error in CLI command thread: " + e.getMessage());
        } finally {
            stopCli();
        }
    }

    public void processRemoteCommand(String command) {
        switch (command.trim().toLowerCase()) {
            case "/mute":
                if (audioSendThread != null) {
                    audioSendThread.toggleMute();
                    System.out.println("Remote peer toggled audio " + (audioSendThread.isMuted() ? "MUTE" : "UNMUTE"));
                } else {
                    System.out.println("Received remote MUTE command, but audio sending is not active locally.");
                }
                break;
            case "/pause":
                if (videoSendThread != null) {
                    videoSendThread.togglePause();
                    System.out.println("Remote peer toggled video " + (videoSendThread.isPaused() ? "PAUSE" : "RESUME"));
                } else {
                    System.out.println("Received remote PAUSE command, but video sending is not active locally.");
                }
                break;
            case "/end":
                System.out.println("Remote peer ended the call. Initiating local shutdown.");
                shutdownHook.run();
                running.set(false);
                break;
            default:
                System.out.println("Received unknown remote command: " + command);
        }
    }

    public void stopCli() {
        running.set(false);
        try {
            if (controlSocket != null) {
                controlSocket.close();
            }
            if (controlServerSocket != null) {
                controlServerSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing control sockets: " + e.getMessage());
        }
    }
}
