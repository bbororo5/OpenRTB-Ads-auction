package com.bbororo.rtb.dsp.acceptance;

import static com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeIssuanceFailure.TECHNICAL_FAILURE;
import static com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind.BILLING;
import static com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind.LOSS;
import static com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind.WIN;
import static com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseInstallResult.INSTALLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.bbororo.rtb.dsp.bidding.api.BidCoordinator;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.CoordinateBid;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.PreparedBid;
import com.bbororo.rtb.dsp.bidding.internal.BidTimePolicy;
import com.bbororo.rtb.dsp.bidding.internal.DefaultBidCoordinator;
import com.bbororo.rtb.dsp.bidding.internal.DefaultCandidateBidAttempt;
import com.bbororo.rtb.dsp.bidding.internal.DefaultCandidateContinuationPolicy;
import com.bbororo.rtb.dsp.bidding.internal.DefaultSlotBidWorkflow;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.Campaign;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.Creative;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.SnapshotInstallResult;
import com.bbororo.rtb.dsp.campaignruntime.internal.DefaultCampaignRuntime;
import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.OutcomeChosen;
import com.bbororo.rtb.dsp.outcome.internal.ReservationExpirationService;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeIssuanceFailed;
import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.NoticeToken;
import com.bbororo.rtb.dsp.proof.api.NoticeVerificationMessages.VerifiedReservationNotice;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeIssuer;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind;
import com.bbororo.rtb.dsp.proof.internal.AesGcmReservationNoticeSealer;
import com.bbororo.rtb.dsp.proof.internal.AesGcmReservationNoticeVerifier;
import com.bbororo.rtb.dsp.proof.internal.DefaultNoticeUrlFactory;
import com.bbororo.rtb.dsp.proof.internal.DefaultReservationNoticeIssuer;
import com.bbororo.rtb.dsp.proof.internal.ReservationNoticeClaimsFactory;
import com.bbororo.rtb.dsp.proof.spi.NoticeTokenKey;
import com.bbororo.rtb.dsp.proof.spi.NoticeTokenKeySource;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationExpiration;
import com.bbororo.rtb.dsp.spending.internal.InMemoryLocalSpendingAuthority;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** 실제 로컬 컴포넌트 조합이 Bidding의 외부 약속을 만족하는지 검증한다. */
class BidCoordinationAcceptanceTest {

    private static final String SSP_ID = "ssp-1";
    private static final String REGION_ID = "ap-northeast-2";
    private static final Duration RESERVATION_LIFETIME = Duration.ofSeconds(5);
    private static final long CAMPAIGN_ONE_CPM = 2_000;
    private static final long CAMPAIGN_TWO_CPM = 3_000;

    @Test
    void preparedBidHasARealReservationAndThreeVerifiableNoticeTokens() {
        var proof = actualProof();
        var fixture = fixture(proof.issuer());

        var decision = fixture.coordinator().coordinate(command(
                impression("imp-1", 300, 250)));

        assertEquals(1, decision.bids().size());
        assertEquals(1, fixture.expirations().size());
        PreparedBid bid = decision.bids().getFirst();
        ReservationExpiration expiration = fixture.expirations().getFirst();
        assertEquals("campaign-1", bid.campaignId());
        assertEquals(CAMPAIGN_ONE_CPM, bid.cpmMilliKrw());
        Instant noticeReceivedAt = expiration.expiresAt().minusNanos(1);

        var win = verify(
                proof, bid.notificationUrls().winNoticeUrl(), WIN, noticeReceivedAt);
        var loss = verify(
                proof, bid.notificationUrls().lossNoticeUrl(), LOSS, noticeReceivedAt);
        var billing = verify(
                proof, bid.notificationUrls().billingNoticeUrl(), BILLING, noticeReceivedAt);

        for (var verified : List.of(win, loss, billing)) {
            assertEquals(expiration.reservation().reservationId(), verified.reservationId());
            assertEquals(expiration.reservation().leaseId(), verified.leaseId());
            assertEquals(bid.campaignId(), verified.campaignId());
            assertEquals(bid.bidId(), verified.bidId());
            assertEquals(bid.cpmMilliKrw(), verified.impressionAmountMicros());
            assertFalse(verified.arrivedAfterDeadline());
        }
    }

    @Test
    void slotWithoutALeaseIsOmittedWithoutRollingBackAnotherSlotsBid() {
        var fixture = fixture(actualProof().issuer());

        var decision = fixture.coordinator().coordinate(command(
                impression("imp-with-lease", 300, 250),
                impression("imp-without-lease", 728, 90)
        ));

        assertEquals(List.of("imp-with-lease"), decision.bids().stream()
                .map(PreparedBid::impressionId)
                .toList());
        assertEquals(1, fixture.expirations().size());
    }

    @Test
    void reservationHiddenByProofFailureReturnsThroughTheExpirationPath() {
        ReservationNoticeIssuer failedProof = command ->
                new NoticeIssuanceFailed(TECHNICAL_FAILURE);
        var fixture = fixture(failedProof);

        var decision = fixture.coordinator().coordinate(command(
                impression("imp-1", 300, 250)));

        assertEquals(List.of(), decision.bids());
        assertEquals(1, fixture.expirations().size());
        assertEquals(CAMPAIGN_ONE_CPM, reservedMicros(fixture.spending()));

        var expirationService = new ReservationExpirationService(
                candidate -> CompletableFuture.completedFuture(
                        new OutcomeChosen(candidate, true)),
                fixture.spending(),
                fixture.spending()
        );
        expirationService.expire(fixture.expirations().getFirst())
                .toCompletableFuture().join();

        assertEquals(0, reservedMicros(fixture.spending()));
        assertEquals(10_000, reusableMicros(fixture.spending()));
    }

    private static Fixture fixture(ReservationNoticeIssuer noticeIssuer) {
        Clock clock = Clock.systemUTC();
        Instant now = clock.instant();
        var expirations = new CopyOnWriteArrayList<ReservationExpiration>();
        var spending = new InMemoryLocalSpendingAuthority(expirations::add);

        assertEquals(INSTALLED, spending.install(new InstallLease(
                "lease-1",
                "campaign-1",
                10_000,
                1,
                now.minusSeconds(1),
                now.plusSeconds(60)
        ), System.nanoTime()));

        var campaignRuntime = new DefaultCampaignRuntime(
                (campaignId, evaluatedAt) -> spending.positionOf(campaignId).lagPpm());
        assertEquals(SnapshotInstallResult.INSTALLED, campaignRuntime.install(snapshot(now)));

        var timePolicy = alwaysHasTime();
        var bidIds = new AtomicInteger();
        var candidateAttempt = new DefaultCandidateBidAttempt(
                spending,
                noticeIssuer,
                clock,
                () -> "bid-" + bidIds.incrementAndGet(),
                REGION_ID,
                RESERVATION_LIFETIME
        );
        var slotWorkflow = new DefaultSlotBidWorkflow(
                campaignRuntime,
                candidateAttempt,
                timePolicy,
                new DefaultCandidateContinuationPolicy(),
                clock
        );
        BidCoordinator coordinator = new DefaultBidCoordinator(slotWorkflow, timePolicy);
        return new Fixture(coordinator, spending, expirations);
    }

    private static CampaignSnapshot snapshot(Instant now) {
        return new CampaignSnapshot(
                "v1",
                "acceptance-checksum",
                List.of(
                        campaign(
                                "campaign-1", CAMPAIGN_ONE_CPM,
                                new Creative("creative-1", 300, 250), now),
                        campaign(
                                "campaign-2", CAMPAIGN_TWO_CPM,
                                new Creative("creative-2", 728, 90), now)
                )
        );
    }

    private static Campaign campaign(
            String id,
            long cpm,
            Creative creative,
            Instant now
    ) {
        return new Campaign(
                id,
                true,
                cpm,
                now.minusSeconds(60),
                now.plusSeconds(3_600),
                List.of(creative)
        );
    }

    private static CoordinateBid command(Impression... impressions) {
        var request = new BidRequest("auction-1", 180, List.of(impressions));
        return new CoordinateBid(
                new AuthenticatedBidRequest(SSP_ID, request, Instant.now()),
                AuctionDeadline.start(180, System::nanoTime)
        );
    }

    private static Impression impression(String id, int width, int height) {
        return new Impression(id, width, height, 1_000, 2);
    }

    private static ProofFixture actualProof() {
        byte[] keyBytes = new byte[32];
        java.util.Arrays.fill(keyBytes, (byte) 7);
        var key = new NoticeTokenKey(
                "acceptance-key",
                new SecretKeySpec(keyBytes, "AES")
        );
        NoticeTokenKeySource keySource = () -> key;
        var issuer = new DefaultReservationNoticeIssuer(
                new ReservationNoticeClaimsFactory(),
                new AesGcmReservationNoticeSealer(keySource),
                new DefaultNoticeUrlFactory(URI.create("https://dsp.example/"))
        );
        return new ProofFixture(issuer, new AesGcmReservationNoticeVerifier(keySource));
    }

    private static VerifiedReservationNotice verify(
            ProofFixture proof,
            URI url,
            ReservationNoticeKind kind,
            Instant receivedAt
    ) {
        String token = URLDecoder.decode(
                url.getRawQuery().substring("token=".length()), StandardCharsets.UTF_8);
        return assertInstanceOf(VerifiedReservationNotice.class, proof.verifier().verify(
                new NoticeToken(SSP_ID, kind, token, receivedAt)
        ));
    }

    private static BidTimePolicy alwaysHasTime() {
        return new BidTimePolicy() {
            @Override
            public boolean canStartSlot(AuctionDeadline deadline) {
                return true;
            }

            @Override
            public boolean canStartCandidate(AuctionDeadline deadline) {
                return true;
            }

            @Override
            public boolean canPublish(AuctionDeadline deadline) {
                return true;
            }
        };
    }

    private static long reservedMicros(InMemoryLocalSpendingAuthority spending) {
        return spending.supplySnapshots().getFirst().reservedMicros();
    }

    private static long reusableMicros(InMemoryLocalSpendingAuthority spending) {
        return spending.supplySnapshots().getFirst().reusableMicros();
    }

    private record Fixture(
            BidCoordinator coordinator,
            InMemoryLocalSpendingAuthority spending,
            List<ReservationExpiration> expirations
    ) {
    }

    private record ProofFixture(
            ReservationNoticeIssuer issuer,
            AesGcmReservationNoticeVerifier verifier
    ) {
    }
}
