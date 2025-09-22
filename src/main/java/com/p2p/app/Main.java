package com.p2p.app;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class Main {
    private static VideoSendThread videoSendThread;
    private static VideoReceiveThread videoReceiveThread;
    private static AudioSendThread audioSendThread;
    private static AudioReceiveThread audioReceiveThread;
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

        // Server sends audio to clientIp (on client's receive port)
        audioSendThread = new AudioSendThread(clientIp, Constants.AUDIO_CLIENT_RECEIVE_PORT);
        executorService.submit(audioSendThread);

        // Server receives audio from client (on its own receive port)
        audioReceiveThread = new AudioReceiveThread(Constants.AUDIO_SERVER_RECEIVE_PORT);
        executorService.submit(audioReceiveThread);

        // Control server socket for CLI commands from client
        ServerSocket controlServerSocket;
        try {
            controlServerSocket = new ServerSocket(Constants.CONTROL_TCP_PORT);
        } catch (IOException e) {
            System.err.println("Error creating server control socket: " + e.getMessage());
            shutdown();
            return;
        }

        cliCommandThread = new CliCommandThread(videoSendThread, audioSendThread, Main::shutdown, clientIp, controlServerSocket); // Server passes its control server socket
        executorService.submit(cliCommandThread);

        controlReceiveThread = new ControlReceiveThread(cliCommandThread, Constants.CONTROL_TCP_PORT); // Server listens for control on CONTROL_TCP_PORT
        executorService.submit(controlReceiveThread);
    }

    private static void startClient(String serverIp) {
        // Client sends video to serverIp (on server's receive port)
        videoSendThread = new VideoSendThread(serverIp, Constants.VIDEO_SERVER_RECEIVE_PORT);
        executorService.submit(videoSendThread);

        // Client receives video from server (on its own receive port)
        videoReceiveThread = new VideoReceiveThread(Constants.VIDEO_CLIENT_RECEIVE_PORT);
        executorService.submit(videoReceiveThread);

        // Client sends audio to server (on server's receive port)
        audioSendThread = new AudioSendThread(serverIp, Constants.AUDIO_SERVER_RECEIVE_PORT);
        executorService.submit(audioSendThread);

        // Client receives audio from server (on its own receive port)
        audioReceiveThread = new AudioReceiveThread(Constants.AUDIO_CLIENT_RECEIVE_PORT);
        executorService.submit(audioReceiveThread);

        cliCommandThread = new CliCommandThread(videoSendThread, audioSendThread, Main::shutdown, serverIp, null); // Client does not create a server socket for control
        executorService.submit(cliCommandThread);

        // Client listens on a different port for incoming control commands from server
        controlReceiveThread = new ControlReceiveThread(cliCommandThread, Constants.CONTROL_TCP_PORT + 1); // Client listens on +1 port for server commands
        executorService.submit(controlReceiveThread);
    }

    private static void shutdown() {
        System.out.println("Shutting down application...");

        // Stop performance monitoring
        PerformanceLogger.stop();
        
        if (cliCommandThread != null) cliCommandThread.stopCli();
        if (videoSendThread != null) videoSendThread.stopCapture();
        if (videoReceiveThread != null) videoReceiveThread.stopReception();
        if (audioSendThread != null) audioSendThread.stopCapture();
        if (audioReceiveThread != null) audioReceiveThread.stopReception();
        if (controlReceiveThread != null) controlReceiveThread.stopReception();

        executorService.shutdownNow(); // Immediately shut down all running tasks
        System.out.println("Application shutdown complete.");
    }
}
