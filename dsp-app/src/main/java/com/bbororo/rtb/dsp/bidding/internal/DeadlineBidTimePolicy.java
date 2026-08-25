package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.contract.AuctionDeadline;
import java.time.Duration;
import java.util.Objects;

/** 측정 가능한 작업 비용과 응답 여유를 절대 기한의 남은 시간과 비교한다. */
public final class DeadlineBidTimePolicy implements BidTimePolicy {

    private final Duration publicationReserve;
    private final Duration workStartThreshold;

    public DeadlineBidTimePolicy(
            Duration candidateAttemptCost,
            Duration publicationReserve
    ) {
        candidateAttemptCost = requireNonNegative(
                candidateAttemptCost, "candidateAttemptCost");
        this.publicationReserve = requireNonNegative(
                publicationReserve, "publicationReserve");
        this.workStartThreshold = candidateAttemptCost.plus(publicationReserve);
    }

    @Override
    public boolean canStartSlot(AuctionDeadline deadline) {
        return hasMoreThan(deadline, workStartThreshold);
    }

    @Override
    public boolean canStartCandidate(AuctionDeadline deadline) {
        return hasMoreThan(deadline, workStartThreshold);
    }

    @Override
    public boolean canPublish(AuctionDeadline deadline) {
        return hasMoreThan(deadline, publicationReserve);
    }

    private static boolean hasMoreThan(AuctionDeadline deadline, Duration required) {
        Objects.requireNonNull(deadline, "deadline");
        return deadline.remaining().compareTo(required) > 0;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
