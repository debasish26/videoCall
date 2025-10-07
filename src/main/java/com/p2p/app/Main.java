package com.p2p.app;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class Main {
    private static VideoSendThread videoSendThread;
    private static VideoReceiveThread videoReceiveThread;
    private static AudioManager audioManager; // New production-ready audio system
    private static ControlReceiveThread controlReceiveThread;
    private static CliCommandThread cliCommandThread;
    private static ThreadPoolExecutor executorService = (ThreadPoolExecutor) Executors.newCachedThreadPool();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java -jar call.jar [server|client] <ip-address>");
            return;
        }

        String mode = args[0];
        String remoteIp = null;

        if ("client".equalsIgnoreCase(mode)) {
            if (args.length < 2) {
                System.out.println("Usage: java -jar call.jar client <server-ip>");
                return;
            }
            remoteIp = args[1];
        } else if (!"server".equalsIgnoreCase(mode)) {
            System.out.println("Invalid mode. Use 'server' or 'client'.");
            return;
        }

        // Start performance monitoring
        PerformanceLogger.start();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(Main::shutdown));

        if ("server".equalsIgnoreCase(mode)) {
            System.out.println("Starting in Server mode...");
            startServer(args[1]); // Server also needs a remote IP for sending
        } else {
            System.out.println("Starting in Client mode, connecting to " + remoteIp + "...");
            startClient(remoteIp);
        }
    }

    private static void startServer(String clientIp) {
        // Server sends video to clientIp (on client's receive port)
        videoSendThread = new VideoSendThread(clientIp, Constants.VIDEO_CLIENT_RECEIVE_PORT);
        executorService.submit(videoSendThread);

        // Server receives video from client (on its own receive port)
        videoReceiveThread = new VideoReceiveThread(Constants.VIDEO_SERVER_RECEIVE_PORT);
        executorService.submit(videoReceiveThread);

        // Initialize new AudioManager (no weird noises!)
        audioManager = new AudioManager(clientIp, Constants.AUDIO_CLIENT_RECEIVE_PORT, Constants.AUDIO_SERVER_RECEIVE_PORT);
        if (!audioManager.initialize()) {
            System.err.println("Failed to initialize audio system");
        }

        // Start CLI which connects to the client's control port (+1)
        cliCommandThread = new CliCommandThread(videoSendThread, audioManager, Main::shutdown, clientIp, Constants.CONTROL_TCP_PORT + 1);
        executorService.submit(cliCommandThread);

        // Server listens for incoming control commands on CONTROL_TCP_PORT
        controlReceiveThread = new ControlReceiveThread(cliCommandThread, Constants.CONTROL_TCP_PORT);
        executorService.submit(controlReceiveThread);
    }

    private static void startClient(String serverIp) {
        // Client sends video to serverIp (on server's receive port)
        videoSendThread = new VideoSendThread(serverIp, Constants.VIDEO_SERVER_RECEIVE_PORT);
        executorService.submit(videoSendThread);

        // Client receives video from server (on its own receive port)
        videoReceiveThread = new VideoReceiveThread(Constants.VIDEO_CLIENT_RECEIVE_PORT);
        executorService.submit(videoReceiveThread);

        // Initialize AudioManager for client
        audioManager = new AudioManager(serverIp, Constants.AUDIO_SERVER_RECEIVE_PORT, Constants.AUDIO_CLIENT_RECEIVE_PORT);
        if (!audioManager.initialize()) {
            System.err.println("Failed to initialize audio system");
        }

        // Start CLI which connects to the server's control port
        cliCommandThread = new CliCommandThread(videoSendThread, audioManager, Main::shutdown, serverIp, Constants.CONTROL_TCP_PORT);
        executorService.submit(cliCommandThread);

        // Client listens on a different port for incoming control commands from server
        controlReceiveThread = new ControlReceiveThread(cliCommandThread, Constants.CONTROL_TCP_PORT + 1);
        executorService.submit(controlReceiveThread);
    }

    private static void shutdown() {
        System.out.println("Shutting down application...");

        // Stop performance monitoring
        PerformanceLogger.stop();
        
        if (cliCommandThread != null) cliCommandThread.stopCli();
        if (videoSendThread != null) videoSendThread.stopCapture();
        if (videoReceiveThread != null) videoReceiveThread.stopReception();
        if (audioManager != null) audioManager.shutdown();
        if (controlReceiveThread != null) controlReceiveThread.stopReception();

        executorService.shutdownNow(); // Immediately shut down all running tasks
        System.out.println("Application shutdown complete.");
    }
}
