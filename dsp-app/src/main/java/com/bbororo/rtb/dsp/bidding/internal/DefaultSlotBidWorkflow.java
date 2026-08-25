package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.bidding.internal.CandidateBidAttempt.AttemptAbandoned;
import com.bbororo.rtb.dsp.bidding.internal.CandidateBidAttempt.AttemptPrepared;
import com.bbororo.rtb.dsp.bidding.internal.CandidateBidAttempt.AttemptRejected;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignCandidateSource;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.RankCampaigns;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejection;
import java.time.Clock;
import java.util.Objects;

/** 순위가 정해진 후보를 시간·거절 정책에 따라 순차 시도한다. */
public final class DefaultSlotBidWorkflow implements SlotBidWorkflow {

    private final CampaignCandidateSource candidateSource;
    private final CandidateBidAttempt candidateAttempt;
    private final BidTimePolicy timePolicy;
    private final CandidateContinuationPolicy continuationPolicy;
    private final Clock clock;

    public DefaultSlotBidWorkflow(
            CampaignCandidateSource candidateSource,
            CandidateBidAttempt candidateAttempt,
            BidTimePolicy timePolicy,
            CandidateContinuationPolicy continuationPolicy,
            Clock clock
    ) {
        this.candidateSource = Objects.requireNonNull(candidateSource, "candidateSource");
        this.candidateAttempt = Objects.requireNonNull(candidateAttempt, "candidateAttempt");
        this.timePolicy = Objects.requireNonNull(timePolicy, "timePolicy");
        this.continuationPolicy = Objects.requireNonNull(
                continuationPolicy, "continuationPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Outcome prepare(Command command) {
        Objects.requireNonNull(command, "command");
        if (!timePolicy.canStartSlot(command.bid().deadline())) {
            return new NoBid(NoBidReason.DEADLINE_BUDGET_EXHAUSTED);
        }

        var request = command.bid().request().request();
        var candidates = candidateSource.rankCandidates(new RankCampaigns(
                request.id(), command.impression(), clock.instant()));
        if (candidates.isEmpty()) {
            return new NoBid(NoBidReason.NO_ELIGIBLE_CANDIDATE);
        }

        for (var candidate : candidates) {
            if (!timePolicy.canStartCandidate(command.bid().deadline())) {
                return new NoBid(NoBidReason.DEADLINE_BUDGET_EXHAUSTED);
            }

            var outcome = candidateAttempt.prepare(new CandidateBidAttempt.Command(
                    command.bid(), command.impression(), candidate));
            switch (outcome) {
                case AttemptPrepared prepared -> {
                    return new SlotBidWorkflow.Prepared(prepared.bid());
                }
                case AttemptAbandoned abandoned -> {
                    return haltFor(abandoned);
                }
                case AttemptRejected rejected -> {
                    switch (continuationPolicy.after(rejected.reason())) {
                        case TRY_NEXT_CANDIDATE -> {
                            continue;
                        }
                        case STOP_SLOT -> {
                            return new NoBid(NoBidReason.RESERVATION_REJECTED);
                        }
                        case STOP_REQUEST -> {
                            return new HaltRequest(haltFor(rejected.reason()));
                        }
                    }
                }
            }
        }
        return new NoBid(NoBidReason.CANDIDATES_EXHAUSTED);
    }

    private static HaltRequest haltFor(AttemptAbandoned abandoned) {
        return switch (abandoned.reason()) {
            case NOTICE_ISSUANCE_FAILED ->
                    new HaltRequest(RequestHaltReason.RESERVATION_ABANDONED);
            case RESERVATION_CONTRACT_MISMATCH ->
                    new HaltRequest(RequestHaltReason.RESERVATION_CONTRACT_CONFLICT);
        };
    }

    private static RequestHaltReason haltFor(
            ReservationRejection rejection
    ) {
        return switch (rejection) {
            case INSTANCE_CAPACITY_EXCEEDED -> RequestHaltReason.LOCAL_CAPACITY_EXHAUSTED;
            case DUPLICATE_CONFLICT -> RequestHaltReason.RESERVATION_CONTRACT_CONFLICT;
            default -> throw new IllegalArgumentException(
                    "rejection does not halt the request: " + rejection);
        };
    }
}
