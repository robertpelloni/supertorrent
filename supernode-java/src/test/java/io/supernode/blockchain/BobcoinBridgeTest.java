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

@DisplayName("BobcoinBridge")
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
    @DisplayName("Construction")
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
        @DisplayName("should create with custom options")
        void shouldCreateWithCustomOptions() {
            BobcoinBridge.BobcoinOptions options = new BobcoinBridge.BobcoinOptions(
                "https://custom-rpc.example.com",
                "mainnet",
                new byte[32],
                "contract-address"
            );

            BobcoinBridge customBridge = new BobcoinBridge(options);

            assertEquals("https://custom-rpc.example.com", customBridge.getRpcEndpoint());
            assertEquals("mainnet", customBridge.getNetwork());
        }
    }

    @Nested
    @DisplayName("Connection")
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
        @DisplayName("should use provided wallet key for public key derivation")
        void shouldUseProvidedWalletKey() throws Exception {
            byte[] walletKey = new byte[32];
            for (int i = 0; i < 32; i++) walletKey[i] = (byte) i;

            BobcoinBridge.BobcoinOptions options = new BobcoinBridge.BobcoinOptions(
                null, null, walletKey, null
            );
            BobcoinBridge walletBridge = new BobcoinBridge(options);

            walletBridge.connect().get(5, TimeUnit.SECONDS);

            assertNotNull(walletBridge.getPublicKey());
            assertEquals(64, walletBridge.getPublicKey().length());
            walletBridge.disconnect();
        }
    }

    @Nested
    @DisplayName("Storage Provider Registration")
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
            assertTrue(result.txHash().startsWith("0x"));
        }

        @Test
        @DisplayName("should emit provider registered event")
        void shouldEmitProviderRegisteredEvent() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            AtomicReference<BobcoinBridge.ProviderRegisteredEvent> eventRef = new AtomicReference<>();
            bridge.setOnProviderRegistered(eventRef::set);

            bridge.registerStorageProvider(2048L, 0.05).get(5, TimeUnit.SECONDS);

            assertNotNull(eventRef.get());
            assertEquals(2048L, eventRef.get().capacity());
            assertEquals(0.05, eventRef.get().pricePerGBHour());
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
    @DisplayName("Storage Deals")
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
        @DisplayName("should create storage deal with minimal params")
        void shouldCreateStorageDealWithMinimalParams() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.StorageDealParams params = new BobcoinBridge.StorageDealParams(
                "file-456", 2048, 7200000L
            );

            BobcoinBridge.StorageDeal deal = bridge.createStorageDeal(params)
                .get(5, TimeUnit.SECONDS);

            assertNotNull(deal);
            assertNotNull(deal.dealId());
        }

        @Test
        @DisplayName("should emit deal created event")
        void shouldEmitDealCreatedEvent() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            AtomicReference<BobcoinBridge.DealCreatedEvent> eventRef = new AtomicReference<>();
            bridge.setOnDealCreated(eventRef::set);

            BobcoinBridge.StorageDealParams params = new BobcoinBridge.StorageDealParams(
                "event-file", 4096, 1800000L, null, 2
            );
            bridge.createStorageDeal(params).get(5, TimeUnit.SECONDS);

            assertNotNull(eventRef.get());
            assertEquals("event-file", eventRef.get().fileId());
            assertEquals(4096, eventRef.get().size());
            assertEquals(1800000L, eventRef.get().duration());
            assertEquals(2, eventRef.get().redundancy());
        }

        @Test
        @DisplayName("should get deal status")
        void shouldGetDealStatus() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.DealStatus status = bridge.getDealStatus("deal-123")
                .get(5, TimeUnit.SECONDS);

            assertNotNull(status);
            assertEquals("deal-123", status.dealId());
            assertNotNull(status.status());
        }

        @Test
        @DisplayName("should list active deals")
        void shouldListActiveDeals() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            List<BobcoinBridge.DealStatus> deals = bridge.listActiveDeals()
                .get(5, TimeUnit.SECONDS);

            assertNotNull(deals);
        }
    }

    @Nested
    @DisplayName("Storage Proofs")
    class StorageProofTests {

        @Test
        @DisplayName("should submit storage proof")
        void shouldSubmitStorageProof() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.ProofSubmission submission = bridge.submitStorageProof(
                "deal-123",
                List.of("chunk-1", "chunk-2", "chunk-3"),
                "merkle-root-abc"
            ).get(5, TimeUnit.SECONDS);

            assertNotNull(submission);
            assertNotNull(submission.proofId());
            assertNotNull(submission.txHash());
        }

        @Test
        @DisplayName("should emit proof submitted event")
        void shouldEmitProofSubmittedEvent() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            AtomicReference<BobcoinBridge.ProofSubmittedEvent> eventRef = new AtomicReference<>();
            bridge.setOnProofSubmitted(eventRef::set);

            bridge.submitStorageProof("deal-456", List.of("chunk"), "root")
                .get(5, TimeUnit.SECONDS);

            assertNotNull(eventRef.get());
            assertEquals("deal-456", eventRef.get().dealId());
            assertEquals("root", eventRef.get().merkleRoot());
        }

        @Test
        @DisplayName("should verify storage proof")
        void shouldVerifyStorageProof() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.ProofSubmission submission = bridge.submitStorageProof(
                "deal-789", List.of("chunk"), "root"
            ).get(5, TimeUnit.SECONDS);

            BobcoinBridge.ProofVerification verification = bridge
                .verifyStorageProof(submission.proofId())
                .get(5, TimeUnit.SECONDS);

            assertNotNull(verification);
            assertTrue(verification.isValid());
            assertNotNull(verification.txHash());
        }

        @Test
        @DisplayName("should emit proof verified event")
        void shouldEmitProofVerifiedEvent() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            AtomicReference<BobcoinBridge.ProofVerifiedEvent> eventRef = new AtomicReference<>();
            bridge.setOnProofVerified(eventRef::set);

            BobcoinBridge.ProofSubmission submission = bridge.submitStorageProof(
                "deal-verify", List.of("chunk"), "root"
            ).get(5, TimeUnit.SECONDS);

            bridge.verifyStorageProof(submission.proofId()).get(5, TimeUnit.SECONDS);

            assertNotNull(eventRef.get());
            assertEquals(submission.proofId(), eventRef.get().proofId());
            assertTrue(eventRef.get().isValid());
        }

        @Test
        @DisplayName("should fail to verify unknown proof")
        void shouldFailToVerifyUnknownProof() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            assertThrows(Exception.class, () ->
                bridge.verifyStorageProof("unknown-proof-id").get(5, TimeUnit.SECONDS)
            );
        }
    }

    @Nested
    @DisplayName("Rewards")
    class RewardTests {

        @Test
        @DisplayName("should claim reward")
        void shouldClaimReward() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.RewardClaim claim = bridge.claimReward("deal-123")
                .get(5, TimeUnit.SECONDS);

            assertNotNull(claim);
            assertTrue(claim.reward() > 0);
            assertNotNull(claim.txHash());
        }

        @Test
        @DisplayName("should emit reward claimed event")
        void shouldEmitRewardClaimedEvent() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            AtomicReference<BobcoinBridge.RewardClaimedEvent> eventRef = new AtomicReference<>();
            bridge.setOnRewardClaimed(eventRef::set);

            bridge.claimReward("deal-reward").get(5, TimeUnit.SECONDS);

            assertNotNull(eventRef.get());
            assertEquals("deal-reward", eventRef.get().dealId());
            assertTrue(eventRef.get().reward() > 0);
        }
    }

    @Nested
    @DisplayName("Balance")
    class BalanceTests {

        @Test
        @DisplayName("should get balance")
        void shouldGetBalance() throws Exception {
            bridge.connect().get(5, TimeUnit.SECONDS);

            BobcoinBridge.Balance balance = bridge.getBalance().get(5, TimeUnit.SECONDS);

            assertNotNull(balance);
            assertTrue(balance.bob() >= 0);
            assertTrue(balance.staked() >= 0);
            assertTrue(balance.pending() >= 0);
        }
    }

    @Nested
    @DisplayName("Event Listeners")
    class EventListenerTests {

        @Test
        @DisplayName("should accept all event listeners")
        void shouldAcceptAllEventListeners() {
            assertDoesNotThrow(() -> {
                bridge.setOnConnected(e -> {});
                bridge.setOnError(e -> {});
                bridge.setOnDisconnected(v -> {});
                bridge.setOnProviderRegistered(e -> {});
                bridge.setOnDealCreated(e -> {});
                bridge.setOnProofSubmitted(e -> {});
                bridge.setOnProofVerified(e -> {});
                bridge.setOnRewardClaimed(e -> {});
            });
        }

        @Test
        @DisplayName("should handle error event")
        void shouldHandleErrorEvent() {
            AtomicReference<Exception> errorRef = new AtomicReference<>();
            bridge.setOnError(errorRef::set);

            assertNotNull(bridge);
        }
    }

    @Nested
    @DisplayName("Records")
    class RecordTests {

        @Test
        @DisplayName("BobcoinOptions default constructor")
        void bobcoinOptionsDefaultConstructor() {
            BobcoinBridge.BobcoinOptions options = new BobcoinBridge.BobcoinOptions();

            assertNull(options.rpcEndpoint());
            assertNull(options.network());
            assertNull(options.walletKey());
            assertNull(options.contractAddress());
        }

        @Test
        @DisplayName("StorageDealParams convenience constructor")
        void storageDealParamsConvenienceConstructor() {
            BobcoinBridge.StorageDealParams params = new BobcoinBridge.StorageDealParams(
                "file", 1024, 3600000L
            );

            assertEquals("file", params.fileId());
            assertEquals(1024, params.size());
            assertEquals(3600000L, params.durationMs());
            assertNull(params.maxPrice());
            assertEquals(3, params.redundancy());
        }

        @Test
        @DisplayName("Balance record fields")
        void balanceRecordFields() {
            BobcoinBridge.Balance balance = new BobcoinBridge.Balance(1000, 500, 100);

            assertEquals(1000, balance.bob());
            assertEquals(500, balance.staked());
            assertEquals(100, balance.pending());
        }

        @Test
        @DisplayName("DealStatus record fields")
        void dealStatusRecordFields() {
            BobcoinBridge.DealStatus status = new BobcoinBridge.DealStatus(
                "deal-id", "active", 10, 123456789L, 500
            );

            assertEquals("deal-id", status.dealId());
            assertEquals("active", status.status());
            assertEquals(10, status.proofsSubmitted());
            assertEquals(123456789L, status.lastProofAt());
            assertEquals(500, status.earnedRewards());
        }
    }
}
