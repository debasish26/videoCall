package com.p2p.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class ControlReceiveThread extends Thread {
    private CliCommandThread cliCommandThread;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private BufferedReader reader;
    private AtomicBoolean running = new AtomicBoolean(true);
    private int listenPort;

    public ControlReceiveThread(CliCommandThread cliCommandThread, int listenPort) {
        this.cliCommandThread = cliCommandThread;
        this.listenPort = listenPort;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(listenPort); // Use the provided listenPort
            System.out.println("ControlReceiveThread: Waiting for control connection on port " + listenPort + "...");
            clientSocket = serverSocket.accept();
            System.out.println("ControlReceiveThread: Control client connected from " + clientSocket.getInetAddress());
            reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            String line;
            while (running.get()) {
                try {
                    if ((line = reader.readLine()) != null) {
                        cliCommandThread.processRemoteCommand(line);
                    } else {
                        // Stream closed, remote end disconnected
                        System.err.println("ControlReceiveThread: Remote end disconnected. Attempting to re-accept connection...");
                        reconnect();
                    }
                } catch (java.io.IOException e) {
                    System.err.println("ControlReceiveThread: Connection dropped or error reading stream: " + e.getMessage() + ". Attempting to re-accept...");
                    reconnect();
                } catch (Exception e) {
                    System.err.println("ControlReceiveThread: Error during control command processing: " + e.getMessage());
                    running.set(false); // Stop if a non-IO error occurs
                }
            }
        } catch (java.net.SocketException e) {
            System.err.println("ControlReceiveThread: Socket error during initialization on port " + listenPort + ": " + e.getMessage());
            running.set(false); // Stop if socket cannot be created
        } catch (Exception e) {
            System.err.println("ControlReceiveThread: Fatal error during setup or main loop: " + e.getMessage());
        } finally {
            closeResources();
        }
    }

    private void reconnect() {
        System.out.println("ControlReceiveThread: Initiating reconnection logic...");
        // Close existing client socket if any
        if (clientSocket != null) {
            try {
                clientSocket.close();
                System.out.println("ControlReceiveThread: Closed old client socket.");
            } catch (IOException e) {
                System.err.println("ControlReceiveThread: Error closing old client socket: " + e.getMessage());
            }
        }
        if (reader != null) {
            try {
                reader.close();
                System.out.println("ControlReceiveThread: Closed old reader.");
            } catch (IOException e) {
                System.err.println("ControlReceiveThread: Error closing old reader: " + e.getMessage());
            }
        }

        while (running.get()) {
            try {
                System.out.println("ControlReceiveThread: Waiting for control connection on port " + listenPort + " (reconnecting)...");
                clientSocket = serverSocket.accept(); // Use the existing serverSocket
                reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                System.out.println("ControlReceiveThread: Reconnected control client from " + clientSocket.getInetAddress());
                break; // Exit loop on successful reconnection
            } catch (Exception e) {
                System.err.println("ControlReceiveThread: Reconnection attempt failed: " + e.getMessage() + ". Retrying in 2 seconds...");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    running.set(false); // Stop trying to reconnect if interrupted
                    System.err.println("ControlReceiveThread: Reconnection interrupted. Aborting reconnection attempts.");
                    break;
                }
            }
        }
    }

    public void stopReception() {
        running.set(false);
        closeResources();
        System.out.println("ControlReceiveThread stopped.");
    }

    private void closeResources() {
        if (reader != null) {
            try {
                reader.close();
                System.out.println("ControlReceiveThread: Reader closed.");
            } catch (IOException e) {
                System.err.println("ControlReceiveThread: Error closing control reader: " + e.getMessage());
            }
        }
        if (clientSocket != null) {
            try {
                clientSocket.close();
                System.out.println("ControlReceiveThread: Client socket closed.");
            } catch (IOException e) {
                System.err.println("ControlReceiveThread: Error closing control client socket: " + e.getMessage());
            }
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
                System.out.println("ControlReceiveThread: Server socket closed.");
            } catch (IOException e) {
                System.err.println("ControlReceiveThread: Error closing control server socket: " + e.getMessage());
            }
        }
    }
}
