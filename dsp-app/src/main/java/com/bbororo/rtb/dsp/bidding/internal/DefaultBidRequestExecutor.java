package com.bbororo.rtb.dsp.bidding.internal;

import com.bbororo.rtb.dsp.bidding.api.BidCoordinator;
import com.bbororo.rtb.dsp.bidding.api.BidRequestExecutor;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidExecutionResult;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidRequestKey;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.CoordinateBid;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.ExecuteBidOnce;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import java.util.Objects;

/** 요청 정체성을 계산하고 최초 실행권을 획득한 호출만 입찰 조정으로 보낸다. */
public final class DefaultBidRequestExecutor implements BidRequestExecutor {

    private final BidRequestFingerprintCalculator fingerprints;
    private final BidExecutionGate executionGate;
    private final BidCoordinator coordinator;

    public DefaultBidRequestExecutor(
            BidRequestFingerprintCalculator fingerprints,
            BidExecutionGate executionGate,
            BidCoordinator coordinator
    ) {
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.executionGate = Objects.requireNonNull(executionGate, "executionGate");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public BidExecutionResult execute(AuthenticatedBidRequest request) {
        Objects.requireNonNull(request, "request");
        var bidRequest = request.request();
        var coordinate = new CoordinateBid(request, request.deadline());
        var executeOnce = new ExecuteBidOnce(
                new BidRequestKey(request.sspId(), bidRequest.id()),
                fingerprints.calculate(bidRequest),
                coordinate
        );
        return executionGate.tryExecute(
                executeOnce,
                () -> coordinator.coordinate(coordinate)
        );
    }
}
