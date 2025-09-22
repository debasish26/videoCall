# P2P Video Calling App

A simple CLI-controlled peer-to-peer (1-to-1) video calling application built in Java.

## Requirements

- Java 11+
- Maven

## Build Instructions

To build the project, navigate to the project root directory (where `pom.xml` is located) and run:

```bash
mvn clean package
```

This will create a `target/p2p-video-call-1.0-SNAPSHOT.jar` file along with its dependencies in `target/lib`.

## Run Instructions

### Server Mode

To run the application in server mode, execute:

```bash
java -jar target/p2p-video-call-1.0-SNAPSHOT.jar server <your-ip-address>
```

Replace `<your-ip-address>` with the IP address of the machine running in server mode. This IP will be used by the client to connect for sending audio and control commands.

### Client Mode

To run the application in client mode, execute:

```bash
java -jar target/p2p-video-call-1.0-SNAPSHOT.jar client <server-ip>
```

Replace `<server-ip>` with the IP address of the machine running in server mode.

## CLI Commands

Once the call is established, you can use the following commands in the terminal:

- `/mute`: Toggle audio mute/unmute. This command is sent to the remote peer, and also affects local audio capture.
- `/pause`: Toggle video pause/unpause. This command is sent to the remote peer, and also affects local video capture.
- `/end`: Gracefully end the call. This command notifies the remote peer to also shut down.

## Auto-reconnect

The application includes basic auto-reconnect logic for TCP sockets (audio and control) to handle transient network disconnections. If a connection drops, the application will attempt to re-establish it every 2 seconds until successful or the application is explicitly shut down.
