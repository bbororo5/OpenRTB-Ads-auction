package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.CoordinateBid;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.PreparedBid;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.util.Objects;

/** 슬롯 하나의 후보들을 탐색해 최대 하나의 입찰 또는 진행 제어 결과를 만든다. */
public interface SlotBidWorkflow {

    Outcome prepare(Command command);

    record Command(CoordinateBid bid, Impression impression) {
        public Command {
            Objects.requireNonNull(bid, "bid");
            Objects.requireNonNull(impression, "impression");
        }
    }

    sealed interface Outcome permits Prepared, NoBid, HaltRequest {
    }

    record Prepared(PreparedBid bid) implements Outcome {
        public Prepared {
            Objects.requireNonNull(bid, "bid");
        }
    }

    record NoBid(NoBidReason reason) implements Outcome {
        public NoBid {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record HaltRequest(RequestHaltReason reason) implements Outcome {
        public HaltRequest {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum NoBidReason {
        DEADLINE_BUDGET_EXHAUSTED,
        NO_ELIGIBLE_CANDIDATE,
        CANDIDATES_EXHAUSTED,
        RESERVATION_REJECTED
    }

    enum RequestHaltReason {
        LOCAL_CAPACITY_EXHAUSTED,
        RESERVATION_CONTRACT_CONFLICT,
        RESERVATION_ABANDONED
    }
}
