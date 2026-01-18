package io.supernode.blockchain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BobcoinBridge Integration")
class BobcoinBridgeTest {

    private BobcoinBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new BobcoinBridge();
    }

    @AfterEach
    void tearDown() {
        if (bridge != null && bridge.isConnected()) {
            bridge.disconnect();
        }
    }

    @Nested
    @DisplayName("Construction & Configuration")
    class ConstructionTests {

        @Test
        @DisplayName("should create with default options")
        void shouldCreateWithDefaultOptions() {
            assertNotNull(bridge);
            assertFalse(bridge.isConnected());
            assertEquals("devnet", bridge.getNetwork());
            assertNotNull(bridge.getRpcEndpoint());
        }

        @Test
        @DisplayName("should create with custom options via builder")
        void shouldCreateWithCustomOptions() {
            byte[] key = new byte[32];
            BobcoinBridge.BobcoinOptions options = BobcoinBridge.BobcoinOptions.builder()
                .rpcEndpoint("https://custom-rpc.example.com")
                .network("mainnet")
                .walletKey(key)
                .contractAddress("contract-address")
                .build();

            BobcoinBridge customBridge = new BobcoinBridge(options);

            assertEquals("https://custom-rpc.example.com", customBridge.getRpcEndpoint());
            assertEquals("mainnet", customBridge.getNetwork());
        }
    }

    @Nested
    @DisplayName("Connection Lifecycle")
    class ConnectionTests {

        @Test
        @DisplayName("should connect successfully")
        void shouldConnectSuccessfully() throws Exception {
            boolean result = bridge.connect().get(5, TimeUnit.SECONDS);

            assertTrue(result);
            assertTrue(bridge.isConnected());
            assertNotNull(bridge.getPublicKey());
        }

        @Test
        @DisplayName("should emit connected event")
        void shouldEmitConnectedEvent() throws Exception {
            AtomicReference<BobcoinBridge.ConnectedEvent> eventRef = new AtomicReference<>();
            bridge.setOnConnected(eventRef::set);

            bridge.connect().get(5, TimeUnit.SECONDS);

            assertNotNull(eventRef.get());
            assertNotNull(eventRef.get().endpoint());
            assertNotNull(eventRef.get().network());
            assertNotNull(eventRef.get().publicKey());
        }

        @Test
        @DisplayName("should disconnect successfully")
        void shouldDisconnectSuccessfully() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);
            assertTrue(bridge.isConnected());

            AtomicBoolean disconnectedFired = new AtomicBoolean(false);
            bridge.setOnDisconnected(v -> disconnectedFired.set(true));

            bridge.disconnect();

            assertFalse(bridge.isConnected());
            assertTrue(disconnectedFired.get());
        }

        @Test
        @DisplayName("should use provided wallet key for derivation")
        void shouldUseProvidedWalletKey() throws Exception {
            byte[] walletKey = new byte[32];
            for (int i = 0; i < 32; i++) walletKey[i] = (byte) i;

            BobcoinBridge.BobcoinOptions options = BobcoinBridge.BobcoinOptions.builder()
                .walletKey(walletKey)
                .build();
            BobcoinBridge walletBridge = new BobcoinBridge(options);

            walletBridge.connect().get(5, TimeUnit.SECONDS);

            assertNotNull(walletBridge.getPublicKey());
            assertEquals(64, walletBridge.getPublicKey().length());
            walletBridge.disconnect();
        }
    }

    @Nested
    @DisplayName("Storage Provider Operations")
    class ProviderRegistrationTests {

        @Test
        @DisplayName("should register storage provider")
        void shouldRegisterStorageProvider() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.ProviderRegistration result = bridge
                .registerStorageProvider(1024 * 1024 * 1024L, 0.01)
                .get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertNotNull(result.providerId());
            assertNotNull(result.txHash());
            assertTrue(result.txHash().startsWith("tx_"));
        }

        @Test
        @DisplayName("should fail when not connected")
        void shouldFailWhenNotConnected() {
            assertThrows(IllegalStateException.class, () ->
                bridge.registerStorageProvider(1024L, 0.01)
            );
        }
    }

    @Nested
    @DisplayName("Storage Deals & Tracking")
    class StorageDealTests {

        @Test
        @DisplayName("should create storage deal")
        void shouldCreateStorageDeal() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.StorageDealParams params = new BobcoinBridge.StorageDealParams(
                "file-123", 1024 * 1024, 3600000L, 100.0, 3
            );

            BobcoinBridge.StorageDeal deal = bridge.createStorageDeal(params)
                .get(5, TimeUnit.SECONDS);

            assertNotNull(deal);
            assertNotNull(deal.dealId());
            assertNotNull(deal.txHash());
            assertTrue(deal.totalCost() > 0);
            assertTrue(deal.expiresAt() > System.currentTimeMillis());
        }

        @Test
        @DisplayName("should get deal status")
        void shouldGetDealStatus() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.DealStatus status = bridge.getDealStatus("deal-123")
                .get(5, TimeUnit.SECONDS);

            assertNotNull(status);
            assertEquals("deal-123", status.dealId());
            assertEquals("active", status.status());
        }
    }

    @Nested
    @DisplayName("Proofs & Rewards")
    class ProofAndRewardTests {

        @Test
        @DisplayName("should submit and verify storage proof")
        void submitAndVerifyProof() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.ProofSubmission submission = bridge.submitStorageProof(
                "deal-123",
                List.of("chunk-1", "chunk-2"),
                "merkle-root-abc"
            ).get(5, TimeUnit.SECONDS);

            assertNotNull(submission.proofId());

            BobcoinBridge.ProofVerification verification = bridge
                .verifyStorageProof(submission.proofId())
                .get(5, TimeUnit.SECONDS);

            assertTrue(verification.isValid());
            assertNotNull(verification.txHash());
        }

        @Test
        @DisplayName("should claim storage rewards")
        void claimRewards() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.RewardClaim claim = bridge.claimReward("deal-123")
                .get(5, TimeUnit.SECONDS);

            assertNotNull(claim);
            assertTrue(claim.reward() > 0);
            assertNotNull(claim.txHash());
        }
    }

    @Nested
    @DisplayName("Wallet & Balance")
    class WalletTests {

        @Test
        @DisplayName("should get account balance")
        void shouldGetBalance() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.Balance balance = bridge.getBalance().get(5, TimeUnit.SECONDS);

            assertNotNull(balance);
            assertTrue(balance.bob() >= 0);
            assertTrue(balance.staked() >= 0);
        }
    }

    @Nested
    @DisplayName("Records Verification")
    class RecordTests {

        @Test
        @DisplayName("BobcoinOptions should have defaults")
        void bobcoinOptionsDefaults() {
            BobcoinBridge.BobcoinOptions options = BobcoinBridge.BobcoinOptions.defaults();
            assertNotNull(options.rpcEndpoint());
            assertNotNull(options.network());
        }

        @Test
        @DisplayName("StorageDealParams should map correctly")
        void storageDealParamsMapping() {
            BobcoinBridge.StorageDealParams params = new BobcoinBridge.StorageDealParams(
                "id", 100, 1000, 5.0, 2
            );
            assertEquals("id", params.fileId());
            assertEquals(100, params.size());
            assertEquals(1000, params.durationMs());
            assertEquals(5.0, params.maxPrice());
            assertEquals(2, params.redundancy());
        }
    }
}
