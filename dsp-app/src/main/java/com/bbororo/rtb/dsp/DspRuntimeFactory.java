package com.bbororo.rtb.dsp;

import com.bbororo.rtb.dsp.application.DefaultDspOpenRtbApi;
import com.bbororo.rtb.dsp.bidding.api.BiddingComponentFactory;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignCandidateSource;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignComponentFactory;
import com.bbororo.rtb.dsp.lease.api.LeaseComponentFactory;
import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import com.bbororo.rtb.dsp.openrtb.DspOpenRtbHttpAdapter;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeProcessor;
import com.bbororo.rtb.dsp.outcome.api.OutcomeComponentFactory;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeIssuer;
import com.bbororo.rtb.dsp.proof.api.ProofComponentFactory;
import com.bbororo.rtb.dsp.spending.api.ReservationAuthority;
import com.bbororo.rtb.dsp.spending.api.SpendingComponentFactory;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 운영 설정과 컴포넌트 공개 포트로 DSP Hot Path 객체 그래프를 조립한다. */
public final class DspRuntimeFactory {

    private static final System.Logger LOGGER = System.getLogger(
            DspRuntimeFactory.class.getName());

    private DspRuntimeFactory() {
    }

    public static DspRuntime createFromEnvironment(Components components) {
        return create(
                DspRuntimeSettings.fromEnvironment(System.getenv()),
                components
        );
    }

    public static DspRuntime create(
            DspRuntimeSettings settings,
            Components components
    ) {
        return create(settings, components, Clock.systemUTC(), System::nanoTime, uuidBidIds());
    }

    static DspRuntime create(
            DspRuntimeSettings settings,
            Components components,
            Clock clock,
            LongSupplier monotonicNanos,
            Supplier<String> bidIds
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        Objects.requireNonNull(bidIds, "bidIds");

        return new DspRuntime(createServer(
                settings, components, clock, monotonicNanos, bidIds));
    }

    static DspRuntime createOperational(
            DspRuntimeSettings settings,
            DspOperationalSettings operations
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(operations, "operations");
        if (settings.reservationLifetime().compareTo(
                operations.leasePolicy().maximumReservationLifetime()) > 0) {
            throw new IllegalArgumentException(
                    "reservationLifetime exceeds maximumReservationLifetime");
        }

        var snapshotSettings = operations.campaignSnapshot();
        var snapshot = CampaignComponentFactory.loadJsonFile(
                snapshotSettings.path(),
                snapshotSettings.requiredVersion(),
                snapshotSettings.sha256Hex()
        );
        List<String> activeCampaignIds = snapshot.campaigns().stream()
                .filter(campaign -> campaign.active())
                .map(campaign -> campaign.id())
                .sorted()
                .toList();
        var spending = SpendingComponentFactory.create(activeCampaignIds);
        var campaigns = CampaignComponentFactory.create(
                snapshot,
                (campaignId, evaluatedAt) ->
                        spending.pacing().positionOf(campaignId).lagPpm()
        );
        var proof = ProofComponentFactory.create(
                operations.proofKeys().activeKeyId(),
                operations.proofKeys().keyRing(),
                operations.publicBaseUri()
        );

        DspDataSources stores = null;
        ExecutorService jdbcExecutor = null;
        OutcomeComponentFactory.Runtime outcomes = null;
        LeaseComponentFactory.Runtime leases = null;
        ArmeriaDspOpenRtbServer server = null;
        try {
            stores = DspDataSources.open(operations);
            jdbcExecutor = Executors.newFixedThreadPool(
                    operations.jdbcWorkers(),
                    Thread.ofPlatform().name("dsp-jdbc-", 0).factory()
            );
            outcomes = OutcomeComponentFactory.create(
                    stores.outcome(),
                    jdbcExecutor,
                    proof.verifier(),
                    spending.finalizer(),
                    spending.expirations(),
                    operations.expirationRetryDelay(),
                    DspRuntimeFactory::reportBackgroundFailure
            );
            var leasePolicy = operations.leasePolicy();
            leases = LeaseComponentFactory.create(
                    stores.ledger(),
                    jdbcExecutor,
                    new LeaseComponentFactory.Settings(
                            leasePolicy.leaseDuration(),
                            leasePolicy.pacingCoverage(),
                            leasePolicy.maximumReservationLifetime(),
                            leasePolicy.eventVisibilityMargin(),
                            leasePolicy.minimumLeaseMicros(),
                            leasePolicy.maximumLeaseMicros(),
                            leasePolicy.maintenanceInterval(),
                            leasePolicy.demandCoverage(),
                            leasePolicy.settlementBatchSize(),
                            leasePolicy.settlementClaimDuration()
                    ),
                    operations.instanceId(),
                    spending.supply(),
                    spending.leaseInstaller(),
                    outcomes.leaseOutcomeView(),
                    activeCampaignIds.size(),
                    DspRuntimeFactory::reportBackgroundFailure
            );
            server = createServer(
                    settings,
                    new Components(
                            campaigns.candidates(),
                            spending.reservations(),
                            proof.issuer(),
                            outcomes.processor()
                    ),
                    Clock.systemUTC(),
                    System::nanoTime,
                    uuidBidIds()
            );
            return new DspRuntime(
                    server,
                    List.of(
                            new DspRuntime.Service(outcomes::start, outcomes),
                            new DspRuntime.Service(leases::start, leases)
                    ),
                    List.of(stores, jdbcExecutor)
            );
        } catch (RuntimeException | Error failure) {
            closeAndSuppress(server, failure);
            closeAndSuppress(leases, failure);
            closeAndSuppress(outcomes, failure);
            closeAndSuppress(jdbcExecutor, failure);
            closeAndSuppress(stores, failure);
            throw failure;
        }
    }

    private static ArmeriaDspOpenRtbServer createServer(
            DspRuntimeSettings settings,
            Components components,
            Clock clock,
            LongSupplier monotonicNanos,
            Supplier<String> bidIds
    ) {
        var bidRequests = BiddingComponentFactory.create(
                new BiddingComponentFactory.Settings(
                        settings.regionId(),
                        settings.reservationLifetime(),
                        settings.candidateAttemptCost(),
                        settings.publicationReserve(),
                        settings.executionRetention(),
                        settings.executionMaximumEntries()
                ),
                components.campaigns(),
                components.reservations(),
                components.noticeIssuer(),
                clock,
                monotonicNanos,
                bidIds
        );
        var api = new DefaultDspOpenRtbApi(bidRequests, components.outcomes());
        var adapter = new DspOpenRtbHttpAdapter(api, monotonicNanos);
        return new ArmeriaDspOpenRtbServer(
                settings.server(), settings.notices(), adapter, clock, monotonicNanos);
    }

    private static Supplier<String> uuidBidIds() {
        return () -> UUID.randomUUID().toString();
    }

    private static void reportBackgroundFailure(Throwable failure) {
        LOGGER.log(System.Logger.Level.ERROR, "DSP background work failed", failure);
    }

    private static void closeAndSuppress(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * Stage 8 조립 이전에 이미 구현된 컴포넌트의 공개 포트다.
     * 설정·DB·컨트롤 플레인 소유권은 이 팩토리 밖에 남긴다.
     */
    public record Components(
            CampaignCandidateSource campaigns,
            ReservationAuthority reservations,
            ReservationNoticeIssuer noticeIssuer,
            ReservationOutcomeProcessor outcomes
    ) {
        public Components {
            Objects.requireNonNull(campaigns, "campaigns");
            Objects.requireNonNull(reservations, "reservations");
            Objects.requireNonNull(noticeIssuer, "noticeIssuer");
            Objects.requireNonNull(outcomes, "outcomes");
        }
    }
}
