package com.p2p.app;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.WindowConstants;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.opencv.core.CvType;

public class VideoReceiveThread extends Thread {
    private DatagramSocket udpSocket;
    private CanvasFrame remoteVideoFrame;
    private OpenCVFrameConverter.ToMat converter;
    private AtomicBoolean running = new AtomicBoolean(true);
    private final int listenPort; // New field for dynamic port

    public VideoReceiveThread(int listenPort) {
        this.listenPort = listenPort;
    }

    @Override
    public void run() {
        try {
            udpSocket = new DatagramSocket(listenPort);
            System.out.println("VideoReceiveThread: Listening for video on UDP port " + listenPort + "...");
            byte[] buffer = new byte[Constants.MAX_VIDEO_PACKET_SIZE];

            remoteVideoFrame = new CanvasFrame("Remote Video");
            remoteVideoFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            remoteVideoFrame.setCanvasSize(Constants.FRAME_WIDTH, Constants.FRAME_HEIGHT);

            converter = new OpenCVFrameConverter.ToMat();

            String tag = "Video calling App";
            Scalar tagColor = new Scalar(0, 255, 0, 0); // Green color
            int font = opencv_imgproc.FONT_HERSHEY_SIMPLEX;
            double fontScale = 0.5;
            int thickness = 1;

            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    udpSocket.receive(packet);

                    byte[] jpegData = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), 0, jpegData, 0, packet.getLength());

                    Mat mat = new Mat(1, packet.getLength(), CvType.CV_8UC1, new BytePointer(jpegData));
                    Mat decodedMat = opencv_imgcodecs.imdecode(mat, opencv_imgcodecs.IMREAD_COLOR);
                    if (decodedMat != null) {
                        // Add tag to the video frame
                        opencv_imgproc.putText(decodedMat, tag, new org.bytedeco.opencv.opencv_core.Point(10, Constants.FRAME_HEIGHT - 10), font, fontScale, tagColor, thickness, opencv_imgproc.LINE_AA, false);

                        Frame frame = converter.convert(decodedMat);
                        remoteVideoFrame.showImage(frame);
                        PerformanceLogger.logVideoFrameReceived();
                        decodedMat.release(); // Release native memory
                    } else {
                        System.err.println("VideoReceiveThread: Failed to decode video frame.");
                    }
                    mat.release(); // Release native memory for the temporary mat
                } catch (java.io.IOException e) {
                    if (running.get()) { // Only log if not intentionally shutting down
                        System.err.println("VideoReceiveThread: Error receiving video packet on port " + listenPort + ": " + e.getMessage());
                        // UDP is connectionless, no explicit reconnect, but log the error
                    }
                } catch (Exception e) {
                    System.err.println("VideoReceiveThread: Error during video processing: " + e.getMessage());
                }
            }
        } catch (java.net.SocketException e) {
            System.err.println("VideoReceiveThread: Socket error during initialization on port " + listenPort + ": " + e.getMessage());
            running.set(false); // Stop if socket cannot be created
        } catch (Exception e) {
            System.err.println("VideoReceiveThread: Fatal error during setup: " + e.getMessage());
        } finally {
            stopReception();
        }
    }

    public void stopReception() {
        running.set(false);
        if (udpSocket != null) {
            udpSocket.close();
            System.out.println("VideoReceiveThread: UDP socket closed.");
        }
        if (remoteVideoFrame != null) {
            remoteVideoFrame.dispose();
            System.out.println("VideoReceiveThread: Remote video frame disposed.");
        }
        System.out.println("VideoReceiveThread stopped.");
    }
}
