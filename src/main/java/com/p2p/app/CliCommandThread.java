package com.p2p.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class CliCommandThread extends Thread {
    private VideoSendThread videoSendThread;
    private AudioManager audioManager;
    private Runnable shutdownHook;
    private Socket controlSocket;
    private PrintWriter writer;
    private AtomicBoolean running = new AtomicBoolean(true);
    private String remoteIp;
    private int remoteControlPort;

    public CliCommandThread(VideoSendThread videoSendThread, AudioManager audioManager, Runnable shutdownHook, String remoteIp, int remoteControlPort) {
        this.videoSendThread = videoSendThread;
        this.audioManager = audioManager;
        this.shutdownHook = shutdownHook;
        this.remoteIp = remoteIp;
        this.remoteControlPort = remoteControlPort;
        // Start connection attempts in the background so the CLI prompt appears immediately
        Thread connector = new Thread(() -> tryConnectControl(remoteIp, remoteControlPort), "CliControlConnector");
        connector.setDaemon(true);
        connector.start();
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

    // accept logic removed; CLI only initiates outgoing control connection

    @Override
    public void run() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("\n=== CLI Commands Available ===\n/audio - Enable/disable audio system\n/mute  - Toggle audio mute/unmute\n/pause - Toggle video pause/resume\n/end   - End the call\n==============================");
        try {
            while (running.get()) {
                System.out.print("\n> Enter command: ");
                System.out.flush(); // Ensure prompt is shown
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
                        if (audioManager != null) {
                            if (!audioManager.isEnabled()) {
                                System.out.println("⚠ Audio is disabled. Use /audio to enable first.");
                            } else {
                                audioManager.toggleMute();
                                System.out.println("✓ Local audio " + (audioManager.isMuted() ? "MUTED" : "UNMUTED"));
                            }
                        } else {
                            System.out.println("⚠ Audio manager not initialized.");
                        }
                        break;
                    case "/pause":
                        if (videoSendThread != null) {
                            videoSendThread.togglePause();
                            System.out.println("✓ Local video " + (videoSendThread.isPaused() ? "PAUSED" : "RESUMED"));
                        } else {
                            System.out.println("⚠ Video sending not active.");
                        }
                        break;
                    case "/audio":
                        if (audioManager != null) {
                            if (!audioManager.isEnabled()) {
                                audioManager.enableAudio();
                            } else {
                                audioManager.disableAudio();
                            }
                        } else {
                            System.out.println("⚠ Audio manager not initialized.");
                        }
                        break;
                    case "/end":
                        System.out.println("✓ Ending call...");
                        if (writer != null) writer.println("/end"); // Notify remote even if not connected for other commands
                        shutdownHook.run();
                        running.set(false);
                        break;
                    default:
                        System.out.println("❌ Unknown command: " + command + ". Use /audio, /mute, /pause, or /end");
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
                if (audioManager != null) {
                    if (!audioManager.isEnabled()) {
                        System.out.println("Received remote MUTE command, but audio is disabled locally. Use /audio to enable.");
                    } else {
                        audioManager.toggleMute();
                        System.out.println("Remote peer toggled audio " + (audioManager.isMuted() ? "MUTE" : "UNMUTE"));
                    }
                } else {
                    System.out.println("Received remote MUTE command, but audio manager is not initialized.");
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
            case "/audio":
                if (audioManager != null) {
                    if (!audioManager.isEnabled()) {
                        System.out.println("Remote peer requested to ENABLE audio.");
                        audioManager.enableAudio();
                    } else {
                        System.out.println("Remote peer requested to DISABLE audio.");
                        audioManager.disableAudio();
                    }
                } else {
                    System.out.println("Received remote AUDIO command, but audio manager is not initialized.");
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
        } catch (IOException e) {
            System.err.println("Error closing control sockets: " + e.getMessage());
        }
    }
}
