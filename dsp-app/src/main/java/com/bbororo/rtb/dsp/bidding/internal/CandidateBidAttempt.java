package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.CoordinateBid;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.PreparedBid;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationGranted;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationRejection;
import java.util.Objects;

/** 후보 하나를 예약과 완전한 통지 URL이 결합된 입찰로 준비한다. */
public interface CandidateBidAttempt {

    Outcome prepare(Command command);

    record Command(
            CoordinateBid bid,
            Impression impression,
            CampaignCandidate candidate
    ) {
        public Command {
            Objects.requireNonNull(bid, "bid");
            Objects.requireNonNull(impression, "impression");
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    sealed interface Outcome permits AttemptPrepared, AttemptRejected, AttemptAbandoned {
    }

    record AttemptPrepared(PreparedBid bid) implements Outcome {
        public AttemptPrepared {
            Objects.requireNonNull(bid, "bid");
        }
    }

    record AttemptRejected(ReservationRejection reason) implements Outcome {
        public AttemptRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 예약은 존재하지만 외부 입찰로 공개할 수 없어 예약 만료를 기다리는 상태다. */
    record AttemptAbandoned(
            ReservationGranted reservation,
            AbandonmentReason reason
    ) implements Outcome {
        public AttemptAbandoned {
            Objects.requireNonNull(reservation, "reservation");
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum AbandonmentReason {
        NOTICE_ISSUANCE_FAILED,
        RESERVATION_CONTRACT_MISMATCH
    }
}
