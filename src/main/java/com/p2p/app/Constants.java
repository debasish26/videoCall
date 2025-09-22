package com.p2p.app;

public class Constants {
    // Port Numbers - Unified UDP approach for better performance
    public static final int VIDEO_UDP_PORT = 6000;
    public static final int AUDIO_UDP_PORT = 6001;  // Changed from TCP to UDP
    public static final int CONTROL_TCP_PORT = 6002; // Keep TCP for control messages

    // Bidirectional Video Ports
    public static final int VIDEO_SERVER_RECEIVE_PORT = VIDEO_UDP_PORT;
    public static final int VIDEO_CLIENT_RECEIVE_PORT = VIDEO_UDP_PORT + 1;

    // Bidirectional Audio Ports - Now UDP for low latency
    public static final int AUDIO_SERVER_RECEIVE_PORT = AUDIO_UDP_PORT;
    public static final int AUDIO_CLIENT_RECEIVE_PORT = AUDIO_UDP_PORT + 1;

    // Video Settings - Optimized for better performance
    public static final int FRAME_WIDTH = 640;  // Increased resolution for better quality
    public static final int FRAME_HEIGHT = 480;
    public static final int FRAME_RATE = 30;    // Increased frame rate
    public static final double JPEG_QUALITY = 0.75; // Better quality for improved visual experience
    public static final int MAX_VIDEO_PACKET_SIZE = 120 * 1024; // Increased for better quality
    public static final int FRAME_BUFFER_COUNT = 3; // Pre-allocate frame buffers
    public static final long TARGET_FRAME_TIME_NANOS = 1000000000L / FRAME_RATE; // Precise timing

    // OpenCV Constants (Moved from VideoSendThread for centralized config)
    public static final int IMWRITE_JPEG_QUALITY = 1; // Used with IntPointer for JPEG quality setting

    // Audio Settings - Optimized for low latency
    public static final float AUDIO_SAMPLE_RATE = 44100; // CD quality for better audio
    public static final int AUDIO_SAMPLE_SIZE_IN_BITS = 16;
    public static final int AUDIO_CHANNELS = 1; // Mono for bandwidth efficiency
    public static final boolean AUDIO_SIGNED = true;
    public static final boolean AUDIO_BIG_ENDIAN = false;
    public static final int AUDIO_BUFFER_SIZE = 2048; // Fixed buffer size for consistent latency
    public static final int AUDIO_PACKET_SIZE = 1024; // Optimized packet size
}
