package io.supernode.network.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class IPFSTransport implements Transport {

    private static final Pattern CID_V0_PATTERN = Pattern.compile("^Qm[1-9A-HJ-NP-Za-km-z]{44}$");
    private static final Pattern CID_V1_PATTERN = Pattern.compile("^b[a-z2-7]{58}$");
    private static final Pattern PEER_ID_PATTERN = Pattern.compile("^(12D3|Qm)[A-Za-z0-9]{44,}$");
    private static final Pattern IPNS_KEY_PATTERN = Pattern.compile("^k[a-z2-7]{58,}$");
    
    private static final int DEFAULT_API_PORT = 5001;
    private static final int DEFAULT_GATEWAY_PORT = 8080;
    private static final int DEFAULT_SWARM_PORT = 4001;
    
    private final IPFSOptions options;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, IPFSConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IpnsEntry> ipnsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MfsPath> mfsIndex = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;
    private TransportConfig config = TransportConfig.defaults();
    
    private String peerId;
    private List<String> addresses;
    private TransportAddress localAddress;
    private Consumer<TransportConnection> onConnection;
    private Consumer<TransportError> onError;
    private volatile boolean running = false;
    private volatile boolean available = false;
    
    private final AtomicLong connectionsIn = new AtomicLong();
    private final AtomicLong connectionsOut = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong reconnections = new AtomicLong();
    private final AtomicLong healthCheckFailures = new AtomicLong();
    private final AtomicLong ipnsPublishes = new AtomicLong();
    private final AtomicLong ipnsResolves = new AtomicLong();
    private final AtomicLong mfsOperations = new AtomicLong();
    private final AtomicLong dagOperations = new AtomicLong();
    private volatile Instant startTime;
    private volatile Instant lastHealthCheck;
    private volatile HealthState healthState = HealthState.STOPPED;
    private volatile String healthMessage = "Not started";
    private final AtomicInteger consecutiveHealthFailures = new AtomicInteger(0);
    private volatile long lastHealthLatencyMs = 0;

    public IPFSTransport() {
        this(IPFSOptions.defaults());
    }

    public IPFSTransport(IPFSOptions options) {
        this.options = options;
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ipfs-worker");
            t.setDaemon(true);
            return t;
        });
        checkIPFSAvailability();
    }

    @Override
    public TransportType getType() {
        return TransportType.IPFS;
    }

    @Override
    public String getName() {
        return "IPFS (InterPlanetary File System)";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public CompletableFuture<TransportAddress> start() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!available) {
                    throw new IllegalStateException("IPFS daemon is not available");
                }
                
                JsonNode idInfo = apiCall("id", Map.of());
                peerId = idInfo.get("ID").asText();
                
                addresses = new ArrayList<>();
                JsonNode addrsNode = idInfo.get("Addresses");
                if (addrsNode != null && addrsNode.isArray()) {
                    for (JsonNode addr : addrsNode) {
                        addresses.add(addr.asText());
                    }
                }
                
                localAddress = TransportAddress.ipfs(peerId);
                running = true;
                startTime = Instant.now();
                healthState = HealthState.HEALTHY;
                healthMessage = "OK";
                
                startPubsubListener();
                startScheduledTasks();
                
                return localAddress;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    private void startScheduledTasks() {
        scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ipfs-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        if (options.enableHealthCheck) {
            scheduledExecutor.scheduleAtFixedRate(
                () -> performHealthCheck().exceptionally(e -> false),
                config.healthCheckInterval().toMillis(),
                config.healthCheckInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
        }
        
        if (options.enableIpnsRepublish) {
            scheduledExecutor.scheduleAtFixedRate(
                this::republishIpnsEntries,
                options.ipnsRepublishInterval.toMillis(),
                options.ipnsRepublishInterval.toMillis(),
                TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            running = false;
            healthState = HealthState.STOPPED;
            healthMessage = "Stopped";
            
            if (scheduledExecutor != null) {
                scheduledExecutor.shutdown();
                try {
                    if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduledExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduledExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            connections.values().forEach(conn -> conn.close().join());
            connections.clear();
            executor.shutdown();
        });
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address) {
        return connect(address, ConnectionOptions.defaults());
    }

    @Override
    public CompletableFuture<TransportConnection> connect(TransportAddress address, ConnectionOptions options) {
        CompletableFuture<TransportConnection> future = new CompletableFuture<>();
        
        if (address.type() != TransportType.IPFS) {
            future.completeExceptionally(new IllegalArgumentException("Not an IPFS address: " + address));
            return future;
        }
        
        executor.submit(() -> {
            try {
                String remotePeerId = address.host();
                
                apiCall("swarm/connect", Map.of("arg", "/p2p/" + remotePeerId));
                
                String id = UUID.randomUUID().toString();
                IPFSConnection connection = new IPFSConnection(
                    id, remotePeerId, address, localAddress, this, options,
                    bytesReceived, bytesSent, messagesReceived, messagesSent
                );
                connections.put(id, connection);
                connectionsOut.incrementAndGet();
                
                future.complete(connection);
            } catch (Exception e) {
                errors.incrementAndGet();
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }

    @Override
    public TransportAddress parseAddress(String address) {
        if (PEER_ID_PATTERN.matcher(address).matches()) {
            return TransportAddress.ipfs(address);
        }
        
        if (address.startsWith("/p2p/") || address.startsWith("/ipfs/")) {
            String peerId = address.substring(address.lastIndexOf('/') + 1);
            return TransportAddress.ipfs(peerId);
        }
        
        return null;
    }

    @Override
    public boolean canHandle(String address) {
        return PEER_ID_PATTERN.matcher(address).matches() ||
               address.startsWith("/p2p/") ||
               address.startsWith("/ipfs/");
    }

    @Override
    public TransportAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public void setOnConnection(Consumer<TransportConnection> handler) {
        this.onConnection = handler;
    }

    @Override
    public void setOnError(Consumer<TransportError> handler) {
        this.onError = handler;
    }

    @Override
    public TransportStats getStats() {
        Duration uptime = startTime != null ? Duration.between(startTime, Instant.now()) : Duration.ZERO;
        return new TransportStats(
            connectionsIn.get(),
            connectionsOut.get(),
            bytesReceived.get(),
            bytesSent.get(),
            messagesReceived.get(),
            messagesSent.get(),
            errors.get(),
            reconnections.get(),
            healthCheckFailures.get(),
            uptime
        );
    }
    
    @Override
    public HealthStatus getHealthStatus() {
        return new HealthStatus(
            healthState,
            healthMessage,
            lastHealthCheck != null ? lastHealthCheck : Instant.now(),
            consecutiveHealthFailures.get(),
            lastHealthLatencyMs
        );
    }
    
    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return performHealthCheck();
    }
    
    @Override
    public void configure(TransportConfig config) {
        this.config = config;
    }
    
    @Override
    public TransportConfig getConfig() {
        return config;
    }
    
    public IPFSStats getIPFSStats() {
        return new IPFSStats(
            ipnsPublishes.get(),
            ipnsResolves.get(),
            mfsOperations.get(),
            dagOperations.get(),
            ipnsCache.size(),
            mfsIndex.size(),
            connections.size()
        );
    }

    public String getPeerId() {
        return peerId;
    }

    public List<String> getAddresses() {
        return addresses != null ? List.copyOf(addresses) : List.of();
    }

    public CompletableFuture<String> add(byte[] data) {
        return add(data, AddOptions.defaults());
    }
    
    public CompletableFuture<String> add(byte[] data, AddOptions addOptions) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String boundary = "----SupernodeBoundary" + System.currentTimeMillis();
                
                StringBuilder urlBuilder = new StringBuilder();
                urlBuilder.append("http://").append(options.apiHost).append(":").append(options.apiPort);
                urlBuilder.append("/api/v0/add?");
                urlBuilder.append("pin=").append(addOptions.pin);
                urlBuilder.append("&raw-leaves=").append(addOptions.rawLeaves);
                urlBuilder.append("&cid-version=").append(addOptions.cidVersion);
                if (addOptions.onlyHash) {
                    urlBuilder.append("&only-hash=true");
                }
                if (addOptions.wrapWithDirectory) {
                    urlBuilder.append("&wrap-with-directory=true");
                }
                
                URL url = URI.create(urlBuilder.toString()).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(("--" + boundary + "\r\n").getBytes());
                    os.write("Content-Disposition: form-data; name=\"file\"; filename=\"data\"\r\n".getBytes());
                    os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes());
                    os.write(data);
                    os.write(("\r\n--" + boundary + "--\r\n").getBytes());
                }
                
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                    String response = reader.readLine();
                    JsonNode json = objectMapper.readTree(response);
                    bytesSent.addAndGet(data.length);
                    return json.get("Hash").asText();
                }
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }

    public CompletableFuture<byte[]> cat(String cid) {
        return cat(cid, CatOptions.defaults());
    }
    
    public CompletableFuture<byte[]> cat(String cid, CatOptions catOptions) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder urlBuilder = new StringBuilder();
                urlBuilder.append("http://").append(options.apiHost).append(":").append(options.apiPort);
                urlBuilder.append("/api/v0/cat?arg=").append(cid);
                if (catOptions.offset > 0) {
                    urlBuilder.append("&offset=").append(catOptions.offset);
                }
                if (catOptions.length > 0) {
                    urlBuilder.append("&length=").append(catOptions.length);
                }
                
                URL url = URI.create(urlBuilder.toString()).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout((int) options.connectionTimeout.toMillis());
                conn.setReadTimeout((int) options.readTimeout.toMillis());
                
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                    byte[] data = baos.toByteArray();
                    bytesReceived.addAndGet(data.length);
                    return data;
                }
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }

    public CompletableFuture<Void> pin(String cid) {
        return pin(cid, false);
    }
    
    public CompletableFuture<Void> pin(String cid, boolean recursive) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                apiCall("pin/add", Map.of("arg", cid, "recursive", String.valueOf(recursive)));
                return null;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }

    public CompletableFuture<Void> unpin(String cid) {
        return unpin(cid, true);
    }
    
    public CompletableFuture<Void> unpin(String cid, boolean recursive) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                apiCall("pin/rm", Map.of("arg", cid, "recursive", String.valueOf(recursive)));
                return null;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }

    public CompletableFuture<List<String>> listPins() {
        return listPins("recursive");
    }
    
    public CompletableFuture<List<String>> listPins(String type) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode result = apiCall("pin/ls", Map.of("type", type));
                List<String> pins = new ArrayList<>();
                JsonNode keys = result.get("Keys");
                if (keys != null) {
                    keys.fieldNames().forEachRemaining(pins::add);
                }
                return pins;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }

    public CompletableFuture<List<PeerInfo>> swarmPeers() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode result = apiCall("swarm/peers", Map.of());
                List<PeerInfo> peers = new ArrayList<>();
                JsonNode peersNode = result.get("Peers");
                if (peersNode != null && peersNode.isArray()) {
                    for (JsonNode peer : peersNode) {
                        peers.add(new PeerInfo(
                            peer.get("Peer").asText(),
                            peer.get("Addr").asText(),
                            peer.has("Latency") ? peer.get("Latency").asText() : null
                        ));
                    }
                }
                return peers;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }

    public CompletableFuture<Void> pubsubPublish(String topic, byte[] data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(data);
                apiCall("pubsub/pub", Map.of("arg", topic, "data", encoded));
                messagesSent.incrementAndGet();
                bytesSent.addAndGet(data.length);
                return null;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    // ==================== IPNS Operations ====================
    
    public CompletableFuture<String> ipnsPublish(String cid) {
        return ipnsPublish(cid, IpnsPublishOptions.defaults());
    }
    
    public CompletableFuture<String> ipnsPublish(String cid, IpnsPublishOptions publishOptions) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("arg", "/ipfs/" + cid);
                if (publishOptions.key != null) {
                    params.put("key", publishOptions.key);
                }
                params.put("lifetime", publishOptions.lifetime);
                params.put("ttl", publishOptions.ttl);
                params.put("resolve", String.valueOf(publishOptions.resolve));
                
                JsonNode result = apiCall("name/publish", params);
                String name = result.get("Name").asText();
                
                ipnsCache.put(name, new IpnsEntry(name, cid, System.currentTimeMillis(), publishOptions.lifetime));
                ipnsPublishes.incrementAndGet();
                
                return name;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<String> ipnsResolve(String name) {
        return ipnsResolve(name, IpnsResolveOptions.defaults());
    }
    
    public CompletableFuture<String> ipnsResolve(String name, IpnsResolveOptions resolveOptions) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (resolveOptions.useCache) {
                    IpnsEntry cached = ipnsCache.get(name);
                    if (cached != null && !cached.isExpired()) {
                        return cached.cid;
                    }
                }
                
                Map<String, String> params = new LinkedHashMap<>();
                params.put("arg", name.startsWith("/ipns/") ? name : "/ipns/" + name);
                params.put("recursive", String.valueOf(resolveOptions.recursive));
                params.put("nocache", String.valueOf(resolveOptions.noCache));
                if (resolveOptions.dhtRecordCount > 0) {
                    params.put("dht-record-count", String.valueOf(resolveOptions.dhtRecordCount));
                }
                
                JsonNode result = apiCall("name/resolve", params);
                String path = result.get("Path").asText();
                String cid = path.replace("/ipfs/", "");
                
                ipnsCache.put(name, new IpnsEntry(name, cid, System.currentTimeMillis(), "24h"));
                ipnsResolves.incrementAndGet();
                
                return cid;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<IpnsKey> ipnsKeyGen(String name) {
        return ipnsKeyGen(name, "ed25519", 0);
    }
    
    public CompletableFuture<IpnsKey> ipnsKeyGen(String name, String type, int size) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("arg", name);
                params.put("type", type);
                if (size > 0) {
                    params.put("size", String.valueOf(size));
                }
                
                JsonNode result = apiCall("key/gen", params);
                return new IpnsKey(result.get("Name").asText(), result.get("Id").asText());
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<List<IpnsKey>> ipnsKeyList() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode result = apiCall("key/list", Map.of());
                List<IpnsKey> keys = new ArrayList<>();
                JsonNode keysNode = result.get("Keys");
                if (keysNode != null && keysNode.isArray()) {
                    for (JsonNode key : keysNode) {
                        keys.add(new IpnsKey(key.get("Name").asText(), key.get("Id").asText()));
                    }
                }
                return keys;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    private void republishIpnsEntries() {
        if (!running) return;
        
        for (IpnsEntry entry : ipnsCache.values()) {
            if (entry.shouldRepublish()) {
                ipnsPublish(entry.cid, IpnsPublishOptions.defaults())
                    .exceptionally(e -> {
                        errors.incrementAndGet();
                        return null;
                    });
            }
        }
    }
    
    // ==================== MFS (Mutable File System) Operations ====================
    
    public CompletableFuture<Void> mfsWrite(String path, byte[] data) {
        return mfsWrite(path, data, MfsWriteOptions.defaults());
    }
    
    public CompletableFuture<Void> mfsWrite(String path, byte[] data, MfsWriteOptions writeOptions) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String boundary = "----SupernodeMFSBoundary" + System.currentTimeMillis();
                
                StringBuilder urlBuilder = new StringBuilder();
                urlBuilder.append("http://").append(options.apiHost).append(":").append(options.apiPort);
                urlBuilder.append("/api/v0/files/write?arg=").append(java.net.URLEncoder.encode(path, StandardCharsets.UTF_8));
                urlBuilder.append("&create=").append(writeOptions.create);
                urlBuilder.append("&truncate=").append(writeOptions.truncate);
                urlBuilder.append("&parents=").append(writeOptions.parents);
                if (writeOptions.offset >= 0) {
                    urlBuilder.append("&offset=").append(writeOptions.offset);
                }
                
                URL url = URI.create(urlBuilder.toString()).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(("--" + boundary + "\r\n").getBytes());
                    os.write("Content-Disposition: form-data; name=\"file\"\r\n".getBytes());
                    os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes());
                    os.write(data);
                    os.write(("\r\n--" + boundary + "--\r\n").getBytes());
                }
                
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new IOException("MFS write failed with status: " + responseCode);
                }
                
                mfsIndex.put(path, new MfsPath(path, data.length, System.currentTimeMillis()));
                mfsOperations.incrementAndGet();
                bytesSent.addAndGet(data.length);
                return null;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<byte[]> mfsRead(String path) {
        return mfsRead(path, 0, -1);
    }
    
    public CompletableFuture<byte[]> mfsRead(String path, long offset, long count) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder urlBuilder = new StringBuilder();
                urlBuilder.append("http://").append(options.apiHost).append(":").append(options.apiPort);
                urlBuilder.append("/api/v0/files/read?arg=").append(java.net.URLEncoder.encode(path, StandardCharsets.UTF_8));
                if (offset > 0) {
                    urlBuilder.append("&offset=").append(offset);
                }
                if (count > 0) {
                    urlBuilder.append("&count=").append(count);
                }
                
                URL url = URI.create(urlBuilder.toString()).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                    byte[] data = baos.toByteArray();
                    bytesReceived.addAndGet(data.length);
                    mfsOperations.incrementAndGet();
                    return data;
                }
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<List<MfsEntry>> mfsList(String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode result = apiCall("files/ls", Map.of("arg", path, "long", "true"));
                List<MfsEntry> entries = new ArrayList<>();
                JsonNode entriesNode = result.get("Entries");
                if (entriesNode != null && entriesNode.isArray()) {
                    for (JsonNode entry : entriesNode) {
                        entries.add(new MfsEntry(
                            entry.get("Name").asText(),
                            entry.get("Type").asInt() == 1,
                            entry.has("Size") ? entry.get("Size").asLong() : 0,
                            entry.has("Hash") ? entry.get("Hash").asText() : null
                        ));
                    }
                }
                mfsOperations.incrementAndGet();
                return entries;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<Void> mfsMkdir(String path, boolean parents) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                apiCall("files/mkdir", Map.of("arg", path, "parents", String.valueOf(parents)));
                mfsOperations.incrementAndGet();
                return null;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<Void> mfsRm(String path, boolean recursive, boolean force) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                apiCall("files/rm", Map.of("arg", path, "recursive", String.valueOf(recursive), "force", String.valueOf(force)));
                mfsIndex.remove(path);
                mfsOperations.incrementAndGet();
                return null;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<Void> mfsCp(String source, String dest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                apiCall("files/cp", Map.of("arg", source, "arg2", dest));
                mfsOperations.incrementAndGet();
                return null;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<Void> mfsMv(String source, String dest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                apiCall("files/mv", Map.of("arg", source, "arg2", dest));
                MfsPath entry = mfsIndex.remove(source);
                if (entry != null) {
                    mfsIndex.put(dest, new MfsPath(dest, entry.size, System.currentTimeMillis()));
                }
                mfsOperations.incrementAndGet();
                return null;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<MfsStat> mfsStat(String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode result = apiCall("files/stat", Map.of("arg", path));
                mfsOperations.incrementAndGet();
                return new MfsStat(
                    result.get("Hash").asText(),
                    result.get("Size").asLong(),
                    result.get("CumulativeSize").asLong(),
                    result.get("Blocks").asInt(),
                    result.get("Type").asText()
                );
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<String> mfsFlush(String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode result = apiCall("files/flush", Map.of("arg", path));
                mfsOperations.incrementAndGet();
                return result.get("Cid").asText();
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    // ==================== DAG Operations ====================
    
    public CompletableFuture<String> dagPut(JsonNode data) {
        return dagPut(data, DagPutOptions.defaults());
    }
    
    public CompletableFuture<String> dagPut(JsonNode data, DagPutOptions putOptions) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String boundary = "----SupernodeDAGBoundary" + System.currentTimeMillis();
                
                StringBuilder urlBuilder = new StringBuilder();
                urlBuilder.append("http://").append(options.apiHost).append(":").append(options.apiPort);
                urlBuilder.append("/api/v0/dag/put?");
                urlBuilder.append("store-codec=").append(putOptions.storeCodec);
                urlBuilder.append("&input-codec=").append(putOptions.inputCodec);
                urlBuilder.append("&pin=").append(putOptions.pin);
                urlBuilder.append("&hash=").append(putOptions.hash);
                
                byte[] jsonBytes = objectMapper.writeValueAsBytes(data);
                
                URL url = URI.create(urlBuilder.toString()).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(("--" + boundary + "\r\n").getBytes());
                    os.write("Content-Disposition: form-data; name=\"file\"\r\n".getBytes());
                    os.write("Content-Type: application/json\r\n\r\n".getBytes());
                    os.write(jsonBytes);
                    os.write(("\r\n--" + boundary + "--\r\n").getBytes());
                }
                
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                    String response = reader.readLine();
                    JsonNode result = objectMapper.readTree(response);
                    dagOperations.incrementAndGet();
                    bytesSent.addAndGet(jsonBytes.length);
                    return result.path("Cid").path("/").asText();
                }
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<JsonNode> dagGet(String cid) {
        return dagGet(cid, null);
    }
    
    public CompletableFuture<JsonNode> dagGet(String cid, String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String arg = path != null ? cid + "/" + path : cid;
                JsonNode result = apiCall("dag/get", Map.of("arg", arg));
                dagOperations.incrementAndGet();
                return result;
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<DagStat> dagStat(String cid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode result = apiCall("dag/stat", Map.of("arg", cid));
                dagOperations.incrementAndGet();
                return new DagStat(
                    result.get("Size").asLong(),
                    result.get("NumBlocks").asInt()
                );
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<String> dagResolve(String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode result = apiCall("dag/resolve", Map.of("arg", path));
                dagOperations.incrementAndGet();
                return result.path("Cid").path("/").asText();
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<String> dagImport(byte[] carData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String boundary = "----SupernodeCAR" + System.currentTimeMillis();
                
                URL url = URI.create("http://" + options.apiHost + ":" + options.apiPort + 
                    "/api/v0/dag/import?pin-roots=true").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(("--" + boundary + "\r\n").getBytes());
                    os.write("Content-Disposition: form-data; name=\"file\"; filename=\"data.car\"\r\n".getBytes());
                    os.write("Content-Type: application/vnd.ipld.car\r\n\r\n".getBytes());
                    os.write(carData);
                    os.write(("\r\n--" + boundary + "--\r\n").getBytes());
                }
                
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                    String response = reader.readLine();
                    JsonNode result = objectMapper.readTree(response);
                    dagOperations.incrementAndGet();
                    bytesSent.addAndGet(carData.length);
                    return result.path("Root").path("Cid").path("/").asText();
                }
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    public CompletableFuture<byte[]> dagExport(String cid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URL url = URI.create("http://" + options.apiHost + ":" + options.apiPort + 
                    "/api/v0/dag/export?arg=" + cid).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                    byte[] data = baos.toByteArray();
                    bytesReceived.addAndGet(data.length);
                    dagOperations.incrementAndGet();
                    return data;
                }
            } catch (Exception e) {
                errors.incrementAndGet();
                throw new CompletionException(e);
            }
        }, executor);
    }
    
    private CompletableFuture<Boolean> performHealthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            long startTimeMs = System.currentTimeMillis();
            lastHealthCheck = Instant.now();
            
            try {
                JsonNode idInfo = apiCall("id", Map.of());
                String currentPeerId = idInfo.get("ID").asText();
                
                if (currentPeerId != null && currentPeerId.equals(peerId)) {
                    lastHealthLatencyMs = System.currentTimeMillis() - startTimeMs;
                    consecutiveHealthFailures.set(0);
                    healthState = HealthState.HEALTHY;
                    healthMessage = "OK";
                    return true;
                }
                
                throw new IllegalStateException("Peer ID mismatch");
                
            } catch (Exception e) {
                lastHealthLatencyMs = System.currentTimeMillis() - startTimeMs;
                int failures = consecutiveHealthFailures.incrementAndGet();
                healthCheckFailures.incrementAndGet();
                
                if (failures >= config.healthCheckFailureThreshold()) {
                    healthState = HealthState.UNHEALTHY;
                    healthMessage = "Health check failed: " + e.getMessage();
                } else {
                    healthState = HealthState.DEGRADED;
                    healthMessage = "Health check failed (" + failures + "/" + 
                                   config.healthCheckFailureThreshold() + "): " + e.getMessage();
                }
                return false;
            }
        }, executor);
    }

    private void checkIPFSAvailability() {
        try {
            URL url = URI.create("http://" + options.apiHost + ":" + options.apiPort + "/api/v0/version").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            
            if (conn.getResponseCode() == 200) {
                available = true;
            }
        } catch (Exception e) {
            available = false;
        }
    }

    private JsonNode apiCall(String endpoint, Map<String, String> params) throws IOException {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append("http://").append(options.apiHost).append(":").append(options.apiPort);
        urlBuilder.append("/api/v0/").append(endpoint);
        
        if (!params.isEmpty()) {
            urlBuilder.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) urlBuilder.append("&");
                urlBuilder.append(entry.getKey()).append("=")
                         .append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }
        
        URL url = URI.create(urlBuilder.toString()).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout((int) options.connectionTimeout.toMillis());
        conn.setReadTimeout((int) options.readTimeout.toMillis());
        
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return objectMapper.readTree(response.toString());
        }
    }

    private void startPubsubListener() {
        executor.submit(() -> {
            String topic = "supernode-" + peerId;
            try {
                URL url = URI.create("http://" + options.apiHost + ":" + options.apiPort + 
                                 "/api/v0/pubsub/sub?arg=" + topic).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        JsonNode msg = objectMapper.readTree(line);
                        handlePubsubMessage(msg);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    errors.incrementAndGet();
                    if (onError != null) {
                        onError.accept(new TransportError(TransportType.IPFS, e.getMessage(), e));
                    }
                }
            }
        });
    }

    private void handlePubsubMessage(JsonNode message) {
        String from = message.has("from") ? message.get("from").asText() : null;
        String dataStr = message.has("data") ? message.get("data").asText() : null;
        
        if (from != null && dataStr != null && !from.equals(peerId)) {
            byte[] data = Base64.getDecoder().decode(dataStr);
            messagesReceived.incrementAndGet();
            bytesReceived.addAndGet(data.length);
            
            IPFSConnection conn = findConnectionByPeer(from);
            if (conn != null) {
                conn.handleMessage(data);
            }
        }
    }

    private IPFSConnection findConnectionByPeer(String peerId) {
        return connections.values().stream()
            .filter(c -> c.remotePeerId.equals(peerId))
            .findFirst()
            .orElse(null);
    }

    private static class IPFSConnection implements TransportConnection {
        private final String id;
        private final String remotePeerId;
        private final TransportAddress remoteAddress;
        private final TransportAddress localAddress;
        private final IPFSTransport transport;
        private final ConnectionOptions options;
        private final AtomicLong bytesReceived;
        private final AtomicLong bytesSent;
        private final AtomicLong messagesReceived;
        private final AtomicLong messagesSent;
        private final AtomicLong framesIn = new AtomicLong();
        private final AtomicLong framesOut = new AtomicLong();
        private final AtomicLong pingsIn = new AtomicLong();
        private final AtomicLong pingsOut = new AtomicLong();
        private final long connectedAt;
        private volatile long lastActivityAt;
        private volatile boolean open = true;
        private volatile ConnectionState state = ConnectionState.OPEN;
        private volatile long latencyMs = 0;
        
        private Consumer<byte[]> onMessage;
        private Consumer<Void> onClose;
        private Consumer<Throwable> onError;

        IPFSConnection(String id, String remotePeerId,
                      TransportAddress remoteAddress, TransportAddress localAddress,
                      IPFSTransport transport, ConnectionOptions options,
                      AtomicLong bytesReceived, AtomicLong bytesSent,
                      AtomicLong messagesReceived, AtomicLong messagesSent) {
            this.id = id;
            this.remotePeerId = remotePeerId;
            this.remoteAddress = remoteAddress;
            this.localAddress = localAddress;
            this.transport = transport;
            this.options = options;
            this.bytesReceived = bytesReceived;
            this.bytesSent = bytesSent;
            this.messagesReceived = messagesReceived;
            this.messagesSent = messagesSent;
            this.connectedAt = System.currentTimeMillis();
            this.lastActivityAt = connectedAt;
        }

        @Override
        public String getId() { return id; }

        @Override
        public TransportType getTransportType() { return TransportType.IPFS; }

        @Override
        public TransportAddress getRemoteAddress() { return remoteAddress; }

        @Override
        public TransportAddress getLocalAddress() { return localAddress; }

        @Override
        public boolean isOpen() { return open && state == ConnectionState.OPEN; }
        
        @Override
        public ConnectionState getState() { return state; }

        @Override
        public CompletableFuture<Void> send(byte[] data) {
            String topic = "supernode-" + remotePeerId;
            framesOut.incrementAndGet();
            return transport.pubsubPublish(topic, data)
                .thenRun(() -> lastActivityAt = System.currentTimeMillis());
        }

        @Override
        public CompletableFuture<Void> send(String message) {
            return send(message.getBytes(StandardCharsets.UTF_8));
        }
        
        @Override
        public CompletableFuture<Void> sendFrame(Frame frame) {
            return send(frame.encode());
        }

        @Override
        public CompletableFuture<Void> close() {
            return CompletableFuture.runAsync(() -> {
                state = ConnectionState.CLOSING;
                open = false;
                state = ConnectionState.CLOSED;
                transport.connections.remove(id);
                if (onClose != null) onClose.accept(null);
            });
        }
        
        @Override
        public CompletableFuture<Duration> ping() {
            long start = System.currentTimeMillis();
            pingsOut.incrementAndGet();
            return transport.swarmPeers().thenApply(peers -> {
                long elapsed = System.currentTimeMillis() - start;
                latencyMs = elapsed;
                pingsIn.incrementAndGet();
                return Duration.ofMillis(elapsed);
            });
        }
        
        @Override
        public void pause() {
            // Not directly supported
        }
        
        @Override
        public void resume() {
            // Not directly supported
        }
        
        @Override
        public BackpressureStatus getBackpressureStatus() {
            return new BackpressureStatus(0, Long.MAX_VALUE, false);
        }

        @Override
        public void setOnMessage(Consumer<byte[]> handler) { this.onMessage = handler; }

        @Override
        public void setOnClose(Consumer<Void> handler) { this.onClose = handler; }

        @Override
        public void setOnError(Consumer<Throwable> handler) { this.onError = handler; }

        @Override
        public ConnectionStats getStats() {
            return new ConnectionStats(
                bytesReceived.get(), bytesSent.get(),
                messagesReceived.get(), messagesSent.get(),
                connectedAt, lastActivityAt,
                framesIn.get(), framesOut.get(),
                pingsOut.get(), pingsIn.get(),
                latencyMs, 0, 0, 0
            );
        }

        void handleMessage(byte[] data) {
            lastActivityAt = System.currentTimeMillis();
            framesIn.incrementAndGet();
            if (onMessage != null) onMessage.accept(data);
        }
    }
    
    // ==================== Records ====================

    public record PeerInfo(String peerId, String address, String latency) {}
    
    public record IpnsKey(String name, String id) {}
    
    public record IpnsEntry(String name, String cid, long publishedAt, String lifetime) {
        boolean isExpired() {
            long lifetimeMs = parseLifetime(lifetime);
            return System.currentTimeMillis() - publishedAt > lifetimeMs;
        }
        
        boolean shouldRepublish() {
            long lifetimeMs = parseLifetime(lifetime);
            return System.currentTimeMillis() - publishedAt > (lifetimeMs * 3 / 4);
        }
        
        private static long parseLifetime(String lifetime) {
            if (lifetime.endsWith("h")) {
                return Long.parseLong(lifetime.replace("h", "")) * 3600 * 1000;
            } else if (lifetime.endsWith("m")) {
                return Long.parseLong(lifetime.replace("m", "")) * 60 * 1000;
            } else if (lifetime.endsWith("s")) {
                return Long.parseLong(lifetime.replace("s", "")) * 1000;
            }
            return 24 * 3600 * 1000;
        }
    }
    
    public record MfsPath(String path, long size, long modifiedAt) {}
    
    public record MfsEntry(String name, boolean isDir, long size, String hash) {}
    
    public record MfsStat(String hash, long size, long cumulativeSize, int blocks, String type) {}
    
    public record DagStat(long size, int numBlocks) {}
    
    public record IPFSStats(
        long ipnsPublishes,
        long ipnsResolves,
        long mfsOperations,
        long dagOperations,
        int ipnsCacheSize,
        int mfsIndexSize,
        int activeConnections
    ) {}
    
    // ==================== Options Records ====================
    
    public record AddOptions(boolean pin, boolean rawLeaves, int cidVersion, boolean onlyHash, boolean wrapWithDirectory) {
        public static AddOptions defaults() {
            return new AddOptions(true, false, 0, false, false);
        }
        
        public static Builder builder() { return new Builder(); }
        
        public static class Builder {
            private boolean pin = true;
            private boolean rawLeaves = false;
            private int cidVersion = 0;
            private boolean onlyHash = false;
            private boolean wrapWithDirectory = false;
            
            public Builder pin(boolean p) { this.pin = p; return this; }
            public Builder rawLeaves(boolean r) { this.rawLeaves = r; return this; }
            public Builder cidVersion(int v) { this.cidVersion = v; return this; }
            public Builder onlyHash(boolean o) { this.onlyHash = o; return this; }
            public Builder wrapWithDirectory(boolean w) { this.wrapWithDirectory = w; return this; }
            public AddOptions build() { return new AddOptions(pin, rawLeaves, cidVersion, onlyHash, wrapWithDirectory); }
        }
    }
    
    public record CatOptions(long offset, long length) {
        public static CatOptions defaults() { return new CatOptions(0, -1); }
    }
    
    public record IpnsPublishOptions(String key, String lifetime, String ttl, boolean resolve) {
        public static IpnsPublishOptions defaults() {
            return new IpnsPublishOptions(null, "24h", "1h", true);
        }
        
        public static Builder builder() { return new Builder(); }
        
        public static class Builder {
            private String key = null;
            private String lifetime = "24h";
            private String ttl = "1h";
            private boolean resolve = true;
            
            public Builder key(String k) { this.key = k; return this; }
            public Builder lifetime(String l) { this.lifetime = l; return this; }
            public Builder ttl(String t) { this.ttl = t; return this; }
            public Builder resolve(boolean r) { this.resolve = r; return this; }
            public IpnsPublishOptions build() { return new IpnsPublishOptions(key, lifetime, ttl, resolve); }
        }
    }
    
    public record IpnsResolveOptions(boolean recursive, boolean noCache, boolean useCache, int dhtRecordCount) {
        public static IpnsResolveOptions defaults() {
            return new IpnsResolveOptions(true, false, true, 16);
        }
        
        public static Builder builder() { return new Builder(); }
        
        public static class Builder {
            private boolean recursive = true;
            private boolean noCache = false;
            private boolean useCache = true;
            private int dhtRecordCount = 16;
            
            public Builder recursive(boolean r) { this.recursive = r; return this; }
            public Builder noCache(boolean n) { this.noCache = n; return this; }
            public Builder useCache(boolean u) { this.useCache = u; return this; }
            public Builder dhtRecordCount(int d) { this.dhtRecordCount = d; return this; }
            public IpnsResolveOptions build() { return new IpnsResolveOptions(recursive, noCache, useCache, dhtRecordCount); }
        }
    }
    
    public record MfsWriteOptions(boolean create, boolean truncate, boolean parents, long offset) {
        public static MfsWriteOptions defaults() {
            return new MfsWriteOptions(true, true, true, -1);
        }
        
        public static Builder builder() { return new Builder(); }
        
        public static class Builder {
            private boolean create = true;
            private boolean truncate = true;
            private boolean parents = true;
            private long offset = -1;
            
            public Builder create(boolean c) { this.create = c; return this; }
            public Builder truncate(boolean t) { this.truncate = t; return this; }
            public Builder parents(boolean p) { this.parents = p; return this; }
            public Builder offset(long o) { this.offset = o; return this; }
            public MfsWriteOptions build() { return new MfsWriteOptions(create, truncate, parents, offset); }
        }
    }
    
    public record DagPutOptions(String storeCodec, String inputCodec, boolean pin, String hash) {
        public static DagPutOptions defaults() {
            return new DagPutOptions("dag-cbor", "dag-json", true, "sha2-256");
        }
        
        public static Builder builder() { return new Builder(); }
        
        public static class Builder {
            private String storeCodec = "dag-cbor";
            private String inputCodec = "dag-json";
            private boolean pin = true;
            private String hash = "sha2-256";
            
            public Builder storeCodec(String s) { this.storeCodec = s; return this; }
            public Builder inputCodec(String i) { this.inputCodec = i; return this; }
            public Builder pin(boolean p) { this.pin = p; return this; }
            public Builder hash(String h) { this.hash = h; return this; }
            public DagPutOptions build() { return new DagPutOptions(storeCodec, inputCodec, pin, hash); }
        }
    }

    public record IPFSOptions(
        String apiHost,
        int apiPort,
        int gatewayPort,
        Duration connectionTimeout,
        Duration readTimeout,
        boolean enableHealthCheck,
        boolean enableIpnsRepublish,
        Duration ipnsRepublishInterval
    ) {
        public static IPFSOptions defaults() {
            return new IPFSOptions(
                "127.0.0.1",
                DEFAULT_API_PORT,
                DEFAULT_GATEWAY_PORT,
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                true,
                true,
                Duration.ofHours(4)
            );
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private String apiHost = "127.0.0.1";
            private int apiPort = DEFAULT_API_PORT;
            private int gatewayPort = DEFAULT_GATEWAY_PORT;
            private Duration connectionTimeout = Duration.ofSeconds(30);
            private Duration readTimeout = Duration.ofMinutes(5);
            private boolean enableHealthCheck = true;
            private boolean enableIpnsRepublish = true;
            private Duration ipnsRepublishInterval = Duration.ofHours(4);
            
            public Builder apiHost(String host) { this.apiHost = host; return this; }
            public Builder apiPort(int port) { this.apiPort = port; return this; }
            public Builder gatewayPort(int port) { this.gatewayPort = port; return this; }
            public Builder connectionTimeout(Duration timeout) { this.connectionTimeout = timeout; return this; }
            public Builder readTimeout(Duration timeout) { this.readTimeout = timeout; return this; }
            public Builder enableHealthCheck(boolean enable) { this.enableHealthCheck = enable; return this; }
            public Builder enableIpnsRepublish(boolean enable) { this.enableIpnsRepublish = enable; return this; }
            public Builder ipnsRepublishInterval(Duration interval) { this.ipnsRepublishInterval = interval; return this; }
            
            public IPFSOptions build() {
                return new IPFSOptions(
                    apiHost, apiPort, gatewayPort, connectionTimeout, readTimeout,
                    enableHealthCheck, enableIpnsRepublish, ipnsRepublishInterval
                );
            }
        }
    }
}
