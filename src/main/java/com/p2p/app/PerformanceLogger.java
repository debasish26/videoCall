package com.p2p.app;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Performance monitoring and logging utility for P2P video calling app
 */
public class PerformanceLogger {
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final AtomicLong videoFramesSent = new AtomicLong(0);
    private static final AtomicLong videoFramesReceived = new AtomicLong(0);
    private static final AtomicLong audioPacketsSent = new AtomicLong(0);
    private static final AtomicLong audioPacketsReceived = new AtomicLong(0);
    private static final AtomicLong videoDroppedFrames = new AtomicLong(0);
    private static final AtomicLong audioDroppedPackets = new AtomicLong(0);
    
    private static volatile boolean started = false;
    private static long startTime = 0;
    
    public static void start() {
        if (started) return;
        started = true;
        startTime = System.currentTimeMillis();
        
        // Log performance statistics every 10 seconds
        scheduler.scheduleAtFixedRate(() -> {
            long uptime = (System.currentTimeMillis() - startTime) / 1000;
            long vSent = videoFramesSent.get();
            long vReceived = videoFramesReceived.get();
            long aSent = audioPacketsSent.get();
            long aReceived = audioPacketsReceived.get();
            long vDropped = videoDroppedFrames.get();
            long aDropped = audioDroppedPackets.get();
            
            System.out.println("=== PERFORMANCE STATS (Uptime: " + uptime + "s) ===");
            System.out.println("Video - Sent: " + vSent + " (" + (vSent/Math.max(1, uptime)) + " fps), " +
                             "Received: " + vReceived + " (" + (vReceived/Math.max(1, uptime)) + " fps), " +
                             "Dropped: " + vDropped);
            System.out.println("Audio - Sent: " + aSent + " (" + (aSent/Math.max(1, uptime)) + " pps), " +
                             "Received: " + aReceived + " (" + (aReceived/Math.max(1, uptime)) + " pps), " +
                             "Dropped: " + aDropped);
            System.out.println("Memory - Used: " + 
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + " MB");
            System.out.println("=============================================");
        }, 10, 10, TimeUnit.SECONDS);
    }
    
    public static void stop() {
        if (!started) return;
        started = false;
        scheduler.shutdown();
    }
    
    public static void logVideoFrameSent() {
        videoFramesSent.incrementAndGet();
    }
    
    public static void logVideoFrameReceived() {
        videoFramesReceived.incrementAndGet();
    }
    
    public static void logAudioPacketSent() {
        audioPacketsSent.incrementAndGet();
    }
    
    public static void logAudioPacketReceived() {
        audioPacketsReceived.incrementAndGet();
    }
    
    public static void logVideoFrameDropped() {
        videoDroppedFrames.incrementAndGet();
    }
    
    public static void logAudioPacketDropped() {
        audioDroppedPackets.incrementAndGet();
    }
    
    public static void logError(String component, String message, Exception e) {
        System.err.println("[ERROR] " + component + ": " + message);
        if (e != null) {
            System.err.println("  Exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
    
    public static void logWarning(String component, String message) {
        System.err.println("[WARN] " + component + ": " + message);
    }
    
    public static void logInfo(String component, String message) {
        System.out.println("[INFO] " + component + ": " + message);
    }
}