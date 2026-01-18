package io.supernode.network;

import io.supernode.network.transport.TransportType;
import io.supernode.storage.InMemoryBlobStore;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Network Layer Integration")
class NetworkIntegrationTest {

    private SupernodeNetwork node1;
    private SupernodeNetwork node2;

    @BeforeEach
    void setUp() throws Exception {
        // Node 1
        SupernodeNetwork.SupernodeNetworkOptions options1 = SupernodeNetwork.SupernodeNetworkOptions.builder()
            .blobStore(new InMemoryBlobStore())
            .port(0) // Auto port
            .build();
        node1 = new SupernodeNetwork(options1);
        node1.listen(0).get(5, TimeUnit.SECONDS);

        // Node 2
        SupernodeNetwork.SupernodeNetworkOptions options2 = SupernodeNetwork.SupernodeNetworkOptions.builder()
            .blobStore(new InMemoryBlobStore())
            .port(0)
            .build();
        node2 = new SupernodeNetwork(options2);
        node2.listen(0).get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (node1 != null) node1.destroy();
        if (node2 != null) node2.destroy();
    }

    @Test
    @DisplayName("should discover and transfer blob between two nodes")
    void discoverAndTransfer() throws Exception {
        String blobId = "test-blob-123";
        byte[] content = "Hello from Node 1".getBytes(StandardCharsets.UTF_8);

        // Node 1 has the blob and announces it
        node1.getBlobStore().put(blobId, content);
        node1.getBlobNetwork().announceBlob(blobId);
        node1.getDht().announce(blobId, node1.getPort());

        // Connect Node 2 to Node 1 manually (since DHT bootstrap is mocked)
        String node1Addr = "ws://127.0.0.1:" + node1.getPort();
        node2.connect(node1Addr).get(30, TimeUnit.SECONDS);

        // Trigger query to exchange have messages
        node2.getBlobNetwork().queryBlob(blobId);

        // Give time for have messages to be exchanged
        Thread.sleep(1000);

        // Node 2 should be able to fetch the blob
        byte[] retrieved = node2.waitForBlob(blobId, 20000).get(25, TimeUnit.SECONDS);

        assertNotNull(retrieved);
        assertArrayEquals(content, retrieved);
        assertTrue(node2.getBlobStore().has(blobId));
    }

    @Test
    @DisplayName("should track peer events across unified network")
    void peerEvents() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> connectedPeerId = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        node1.setOnPeer(e -> {
            connectedPeerId.set(e.peerId());
            latch.countDown();
        });

        String node1Addr = "ws://127.0.0.1:" + node1.getPort();
        System.out.println("Connecting to: " + node1Addr);
        node2.connect(node1Addr).get(30, TimeUnit.SECONDS);
        System.out.println("Connection successful");

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertNotNull(connectedPeerId.get());
        
        List<SupernodeNetwork.PeerEvent> peers = node1.getPeers();
        assertFalse(peers.isEmpty());
        assertEquals(TransportType.CLEARNET, peers.get(0).type());
    }

    @Test
    @DisplayName("should aggregate health status from all components")
    void healthAggregation() {
        io.supernode.network.transport.Transport.HealthStatus status = node1.getHealthStatus();
        
        assertNotNull(status);
        assertNotNull(status.state());
        assertNotNull(status.message());
        assertTrue(status.message().contains("DHT"));
        assertTrue(status.message().contains("Blob"));
    }
}
