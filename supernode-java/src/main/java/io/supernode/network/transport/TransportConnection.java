package io.supernode.network.transport;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface TransportConnection {

    String getId();
    
    TransportType getTransportType();
    
    TransportAddress getRemoteAddress();
    
    TransportAddress getLocalAddress();
    
    boolean isOpen();
    
    CompletableFuture<Void> send(byte[] data);
    
    CompletableFuture<Void> send(String message);

    default CompletableFuture<Void> send(ByteBuffer buffer) {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return send(data);
    }

    default CompletableFuture<Void> sendFrame(Frame frame) {
        return send(frame.encode());
    }
    
    CompletableFuture<Void> close();

    default CompletableFuture<Void> close(int code, String reason) {
        return close();
    }
    
    void setOnMessage(Consumer<byte[]> handler);

    default void setOnFrame(Consumer<Frame> handler) {
        setOnMessage(data -> handler.accept(Frame.decode(data)));
    }
    
    void setOnClose(Consumer<Void> handler);

    default void setOnCloseWithReason(Consumer<CloseReason> handler) {
        setOnClose(v -> handler.accept(new CloseReason(1000, "Normal closure")));
    }
    
    void setOnError(Consumer<Throwable> handler);
    
    ConnectionStats getStats();

    default ConnectionState getState() {
        return isOpen() ? ConnectionState.OPEN : ConnectionState.CLOSED;
    }

    default CompletableFuture<Duration> ping() {
        long start = System.nanoTime();
        return sendFrame(Frame.ping(new byte[0]))
            .thenApply(v -> Duration.ofNanos(System.nanoTime() - start));
    }

    default void pause() {}

    default void resume() {}

    default boolean isPaused() { return false; }

    default BackpressureStatus getBackpressureStatus() {
        return new BackpressureStatus(0, Long.MAX_VALUE, false);
    }

    default void setWriteBufferWatermarks(long low, long high) {}

    default Transport.ConnectionOptions getOptions() {
        return Transport.ConnectionOptions.defaults();
    }

    default int getReconnectCount() { return 0; }

    default Instant getConnectedAt() {
        return Instant.ofEpochMilli(getStats().connectedAt());
    }

    default Instant getLastActivityAt() {
        return Instant.ofEpochMilli(getStats().lastActivityAt());
    }

    default Duration getIdleTime() {
        return Duration.between(getLastActivityAt(), Instant.now());
    }
    
    record ConnectionStats(
        long bytesReceived,
        long bytesSent,
        long messagesReceived,
        long messagesSent,
        long connectedAt,
        long lastActivityAt,
        long framesReceived,
        long framesSent,
        long pingsSent,
        long pongsReceived,
        long avgLatencyMs,
        long reconnections,
        long writeQueueSize,
        long writeQueueBytes
    ) {
        public ConnectionStats(
            long bytesReceived,
            long bytesSent,
            long messagesReceived,
            long messagesSent,
            long connectedAt,
            long lastActivityAt
        ) {
            this(bytesReceived, bytesSent, messagesReceived, messagesSent,
                 connectedAt, lastActivityAt, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public static ConnectionStats empty() {
            return new ConnectionStats(0, 0, 0, 0, System.currentTimeMillis(), System.currentTimeMillis());
        }
    }

    enum ConnectionState {
        CONNECTING,
        OPEN,
        CLOSING,
        CLOSED,
        RECONNECTING
    }

    record CloseReason(int code, String reason) {
        public static final CloseReason NORMAL = new CloseReason(1000, "Normal closure");
        public static final CloseReason GOING_AWAY = new CloseReason(1001, "Going away");
        public static final CloseReason PROTOCOL_ERROR = new CloseReason(1002, "Protocol error");
        public static final CloseReason INVALID_DATA = new CloseReason(1003, "Invalid data");
        public static final CloseReason POLICY_VIOLATION = new CloseReason(1008, "Policy violation");
        public static final CloseReason MESSAGE_TOO_BIG = new CloseReason(1009, "Message too big");
        public static final CloseReason INTERNAL_ERROR = new CloseReason(1011, "Internal error");
    }

    record BackpressureStatus(long queuedBytes, long maxBytes, boolean isPressured) {
        public double pressure() {
            return maxBytes > 0 ? (double) queuedBytes / maxBytes : 0.0;
        }
    }

    record Frame(FrameType type, byte[] payload, int flags) {
        public static final int FLAG_FIN = 0x80;
        public static final int FLAG_COMPRESSED = 0x40;
        public static final int FLAG_PRIORITY = 0x20;

        public static Frame data(byte[] payload) {
            return new Frame(FrameType.DATA, payload, FLAG_FIN);
        }

        public static Frame text(String text) {
            return new Frame(FrameType.TEXT, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), FLAG_FIN);
        }

        public static Frame binary(byte[] data) {
            return new Frame(FrameType.BINARY, data, FLAG_FIN);
        }

        public static Frame ping(byte[] payload) {
            return new Frame(FrameType.PING, payload, FLAG_FIN);
        }

        public static Frame pong(byte[] payload) {
            return new Frame(FrameType.PONG, payload, FLAG_FIN);
        }

        public static Frame close(int code, String reason) {
            ByteBuffer buf = ByteBuffer.allocate(2 + reason.length());
            buf.putShort((short) code);
            buf.put(reason.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new Frame(FrameType.CLOSE, buf.array(), FLAG_FIN);
        }

        public byte[] encode() {
            ByteBuffer buf = ByteBuffer.allocate(1 + 4 + payload.length);
            buf.put((byte) ((type.ordinal() & 0x0F) | (flags & 0xF0)));
            buf.putInt(payload.length);
            buf.put(payload);
            return buf.array();
        }

        public static Frame decode(byte[] data) {
            if (data.length < 5) {
                return Frame.data(data);
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            byte header = buf.get();
            FrameType type = FrameType.values()[header & 0x0F];
            int flags = header & 0xF0;
            int length = buf.getInt();
            byte[] payload = new byte[Math.min(length, buf.remaining())];
            buf.get(payload);
            return new Frame(type, payload, flags);
        }

        public boolean isFinal() { return (flags & FLAG_FIN) != 0; }
        public boolean isCompressed() { return (flags & FLAG_COMPRESSED) != 0; }
        public boolean isPriority() { return (flags & FLAG_PRIORITY) != 0; }

        public String payloadAsString() {
            return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    enum FrameType {
        DATA,
        TEXT,
        BINARY,
        PING,
        PONG,
        CLOSE,
        CONTINUATION
    }
}
