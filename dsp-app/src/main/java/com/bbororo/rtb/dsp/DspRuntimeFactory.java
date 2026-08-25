package com.bbororo.rtb.dsp;

import com.bbororo.rtb.dsp.application.DefaultDspOpenRtbApi;
import com.bbororo.rtb.dsp.bidding.api.BiddingComponentFactory;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignCandidateSource;
import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import com.bbororo.rtb.dsp.openrtb.DspOpenRtbHttpAdapter;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeProcessor;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeIssuer;
import com.bbororo.rtb.dsp.spending.api.ReservationAuthority;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 운영 설정과 컴포넌트 공개 포트로 DSP Hot Path 객체 그래프를 조립한다. */
public final class DspRuntimeFactory {

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
        var server = new ArmeriaDspOpenRtbServer(
                settings.server(), adapter, clock, monotonicNanos);
        return new DspRuntime(server);
    }

    private static Supplier<String> uuidBidIds() {
        return () -> UUID.randomUUID().toString();
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
