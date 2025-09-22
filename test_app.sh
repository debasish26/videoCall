#!/bin/bash

echo "=== P2P Video Call App Test ==="
echo "Testing server startup..."
echo ""

cd /home/queue/Documents/Github/p2p_cp/p2p

# Start server in background and capture output
timeout 5s java -jar target/p2p-video-call-1.0-SNAPSHOT.jar server 127.0.0.1 2>&1 &
SERVER_PID=$!

# Give it a moment to start
sleep 2

# Show what processes are running
echo "Checking if server started:"
ps aux | grep "p2p-video-call" | grep -v grep || echo "Server process not found (may have exited due to camera access)"

# Clean up
kill $SERVER_PID 2>/dev/null || true

echo ""
echo "=== How to Test Manually ==="
echo "1. Open first terminal:"
echo "   java -jar target/p2p-video-call-1.0-SNAPSHOT.jar server 127.0.0.1"
echo ""
echo "2. Open second terminal:"  
echo "   java -jar target/p2p-video-call-1.0-SNAPSHOT.jar client 127.0.0.1"
echo ""
echo "3. Expected output should include:"
echo "   - 'VideoSendThread: Initializing webcam...'"
echo "   - 'AudioSendThread: UDP socket created...'"
echo "   - Performance stats every 10 seconds"
echo "   - Video and audio windows opening"