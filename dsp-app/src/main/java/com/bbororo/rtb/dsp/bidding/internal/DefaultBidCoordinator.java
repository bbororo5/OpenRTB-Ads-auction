package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.bidding.api.BidCoordinator;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidDecision;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.CoordinateBid;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.PreparedBid;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 슬롯별 워크플로우를 순차 실행하고 정상 실패를 부분 성공으로 격리한다. */
public final class DefaultBidCoordinator implements BidCoordinator {

    private final SlotBidWorkflow slotWorkflow;
    private final BidTimePolicy timePolicy;

    public DefaultBidCoordinator(
            SlotBidWorkflow slotWorkflow,
            BidTimePolicy timePolicy
    ) {
        this.slotWorkflow = Objects.requireNonNull(slotWorkflow, "slotWorkflow");
        this.timePolicy = Objects.requireNonNull(timePolicy, "timePolicy");
    }

    @Override
    public BidDecision coordinate(CoordinateBid command) {
        Objects.requireNonNull(command, "command");
        var impressions = command.request().request().impressions();
        var bids = new ArrayList<PreparedBid>(impressions.size());

        for (var impression : impressions) {
            if (!timePolicy.canStartSlot(command.deadline())) {
                break;
            }

            var outcome = slotWorkflow.prepare(new SlotBidWorkflow.Command(command, impression));
            switch (outcome) {
                case SlotBidWorkflow.Prepared prepared -> bids.add(prepared.bid());
                case SlotBidWorkflow.NoBid ignored -> {
                    // 정상적인 슬롯 실패는 다른 슬롯의 진행을 막지 않는다.
                }
                case SlotBidWorkflow.HaltRequest ignored -> {
                    return finish(command, bids);
                }
            }
        }

        return finish(command, bids);
    }

    private BidDecision finish(CoordinateBid command, List<PreparedBid> bids) {
        List<PreparedBid> publishable = timePolicy.canPublish(command.deadline())
                ? bids
                : List.of();
        return new BidDecision(command.request().request().id(), publishable);
    }
}
