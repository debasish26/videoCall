package com.p2p.app;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

public class VideoSendThread extends Thread {
    private final String remoteIp;
    private final int remoteVideoPort;
    private DatagramSocket udpSocket;
    private OpenCVFrameGrabber grabber;
    private OpenCVFrameConverter.ToMat converter;
    private CanvasFrame localVideoFrame;
    private AtomicBoolean running = new AtomicBoolean(true);
    private AtomicBoolean paused = new AtomicBoolean(false);
    
    // Performance optimizations
    private ArrayBlockingQueue<Mat> frameQueue;
    private ExecutorService compressionExecutor;
    private long frameCount = 0;
    private long lastFrameTime = 0;
    private final IntPointer jpegParams = new IntPointer(Constants.IMWRITE_JPEG_QUALITY, (int) (Constants.JPEG_QUALITY * 100));

    public VideoSendThread(String remoteIp, int remoteVideoPort) {
        this.remoteIp = remoteIp;
        this.remoteVideoPort = remoteVideoPort;
        this.frameQueue = new ArrayBlockingQueue<>(Constants.FRAME_BUFFER_COUNT);
        this.compressionExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void run() {
        try {
            // Initialize networking
            udpSocket = new DatagramSocket();
            udpSocket.setSendBufferSize(Constants.MAX_VIDEO_PACKET_SIZE * 4);
            System.out.println("VideoSendThread: Initializing webcam...");
            
            // Initialize camera
            grabber = new OpenCVFrameGrabber(0);
            grabber.setImageWidth(Constants.FRAME_WIDTH);
            grabber.setImageHeight(Constants.FRAME_HEIGHT);
            grabber.setFrameRate(Constants.FRAME_RATE);
            
            // Set thread priority for real-time processing
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 1);

            try {
                grabber.start();
                System.out.println("VideoSendThread: Webcam started. Streaming to " + remoteIp + ":" + remoteVideoPort);
            } catch (FrameGrabber.Exception e) {
                System.err.println("VideoSendThread: Error starting webcam: " + e.getMessage());
                running.set(false);
                return;
            }

            converter = new OpenCVFrameConverter.ToMat();
            localVideoFrame = new CanvasFrame("Local Video", CanvasFrame.getDefaultGamma() / grabber.getGamma());
            localVideoFrame.setDefaultCloseOperation(javax.swing.JFrame.EXIT_ON_CLOSE);
            localVideoFrame.setCanvasSize(Constants.FRAME_WIDTH, Constants.FRAME_HEIGHT);

            // Start async compression thread
            startCompressionProcessor();

            // Main capture loop with precise timing
            long nextFrameTime = System.nanoTime();
            String tag = "Video calling app";
            Scalar tagColor = new Scalar(0, 255, 0, 0);
            int font = opencv_imgproc.FONT_HERSHEY_SIMPLEX;
            double fontScale = 0.7;
            int thickness = 2;

            while (running.get()) {
                if (paused.get()) {
                    Thread.sleep(50);
                    nextFrameTime = System.nanoTime(); // Reset timing
                    continue;
                }

                long currentTime = System.nanoTime();
                if (currentTime < nextFrameTime) {
                    long sleepTime = (nextFrameTime - currentTime) / 1000000; // Convert to milliseconds
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                }
                nextFrameTime += Constants.TARGET_FRAME_TIME_NANOS;

                Frame frame = grabber.grab();
                if (frame != null) {
                    Mat mat = converter.convert(frame);
                    if (mat != null) {
                        // Add overlay
                        opencv_imgproc.putText(mat, tag, 
                            new org.bytedeco.opencv.opencv_core.Point(10, Constants.FRAME_HEIGHT - 20), 
                            font, fontScale, tagColor, thickness, opencv_imgproc.LINE_AA, false);
                        
                        // Show local video
                        localVideoFrame.showImage(converter.convert(mat));
                        
                        // Queue for async compression (non-blocking)
                        Mat matCopy = mat.clone(); // Clone for async processing
                        if (!frameQueue.offer(matCopy)) {
                            // Queue full, skip this frame and release memory
                            matCopy.release();
                            PerformanceLogger.logVideoFrameDropped();
                            PerformanceLogger.logWarning("VideoSend", "Frame queue full, skipping frame");
                        }
                        
                        mat.release();
                        frameCount++;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("VideoSendThread: Interrupted during operation. Shutting down.");
        } catch (Exception e) {
            System.err.println("VideoSendThread: Fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            stopCapture();
        }
    }
    
    private void startCompressionProcessor() {
        compressionExecutor.submit(() -> {
            while (running.get()) {
                try {
                    Mat mat = frameQueue.take(); // Blocking wait for next frame
                    if (mat == null) continue;
                    
                    // Compress to JPEG in background thread
                    byte[] jpegData;
                    try (BytePointer outputBuffer = new BytePointer()) {
                        boolean success = opencv_imgcodecs.imencode(".jpg", mat, outputBuffer, jpegParams);
                        if (success && outputBuffer.limit() > 0 && outputBuffer.limit() <= Constants.MAX_VIDEO_PACKET_SIZE) {
                            jpegData = new byte[(int) outputBuffer.limit()];
                            outputBuffer.get(jpegData);
                            
                            // Send frame
                            sendFrame(jpegData);
                            PerformanceLogger.logVideoFrameSent();
                        } else if (outputBuffer.limit() > Constants.MAX_VIDEO_PACKET_SIZE) {
                            System.out.println("VideoSendThread: Frame too large: " + outputBuffer.limit() + " bytes");
                        }
                    } catch (Exception e) {
                        System.err.println("VideoSendThread: Compression error: " + e.getMessage());
                    } finally {
                        mat.release(); // Always release
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("VideoSendThread: Compression thread error: " + e.getMessage());
                }
            }
        });
    }
    
    private void sendFrame(byte[] jpegData) {
        try {
            DatagramPacket packet = new DatagramPacket(jpegData, jpegData.length, 
                InetAddress.getByName(remoteIp), remoteVideoPort);
            udpSocket.send(packet);
        } catch (Exception e) {
            System.err.println("VideoSendThread: Error sending frame: " + e.getMessage());
        }
    }

    public void togglePause() {
        paused.set(!paused.get());
        System.out.println("Video sending " + (paused.get() ? "paused." : "resumed."));
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void stopCapture() {
        running.set(false);
        
        // Shutdown compression executor
        if (compressionExecutor != null) {
            compressionExecutor.shutdownNow();
            try {
                compressionExecutor.awaitTermination(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Clear frame queue and release any remaining frames
        if (frameQueue != null) {
            Mat frame;
            while ((frame = frameQueue.poll()) != null) {
                frame.release();
            }
        }
        
        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
                System.out.println("VideoSendThread: Webcam grabber stopped and released.");
            } catch (FrameGrabber.Exception e) {
                System.err.println("VideoSendThread: Error stopping grabber: " + e.getMessage());
            }
        }
        if (localVideoFrame != null) {
            localVideoFrame.dispose();
            System.out.println("VideoSendThread: Local video frame disposed.");
        }
        if (udpSocket != null) {
            udpSocket.close();
            System.out.println("VideoSendThread: UDP socket closed.");
        }
        
        // Clean up JPEG parameters
        if (jpegParams != null) {
            jpegParams.deallocate();
        }
        
        System.out.println("VideoSendThread stopped. Processed " + frameCount + " frames.");
    }
}
