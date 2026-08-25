package com.bbororo.rtb.dsp.application;

import com.bbororo.rtb.dsp.bidding.api.BidRequestExecutor;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidExecuted;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.BidExecutionRejected;
import com.bbororo.rtb.dsp.bidding.api.BiddingMessages.PreparedBid;
import com.bbororo.rtb.dsp.openrtb.DspOpenRtbApi;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Bid;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidHttpResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidResponse;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoContent;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeHttpResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.SeatBid;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessed;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeRejected;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.ProcessReservationNotice;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeProcessor;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** OpenRTB 입력을 Bidding·Outcome 컴포넌트의 공개 계약으로 조정하는 애플리케이션 서비스다. */
public final class DefaultDspOpenRtbApi implements DspOpenRtbApi {

    private static final int NBR_TECHNICAL_ERROR = 1;
    private static final int NBR_INVALID_REQUEST = 2;

    private final BidRequestExecutor bidRequests;
    private final ReservationOutcomeProcessor outcomes;

    public DefaultDspOpenRtbApi(
            BidRequestExecutor bidRequests,
            ReservationOutcomeProcessor outcomes
    ) {
        this.bidRequests = Objects.requireNonNull(bidRequests, "bidRequests");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    @Override
    public BidHttpResult handleBid(AuthenticatedBidRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (bidRequests.execute(request)) {
            case BidExecuted executed -> toHttpResult(executed, request.request().id());
            case BidExecutionRejected rejected -> BidResponse.noBid(
                    request.request().id(),
                    switch (rejected.reason()) {
                        case DUPLICATE_REQUEST, REQUEST_CONFLICT -> NBR_INVALID_REQUEST;
                        case OWNERSHIP_UNKNOWN, CAPACITY_EXCEEDED -> NBR_TECHNICAL_ERROR;
                    }
            );
        };
    }

    @Override
    public CompletionStage<NoticeHttpResult> handleNotice(AuctionNotice notice) {
        Objects.requireNonNull(notice, "notice");
        return outcomes.process(new ProcessReservationNotice(
                notice.sspId(),
                ReservationNoticeKind.valueOf(notice.kind().name()),
                notice.opaqueToken(),
                notice.receivedAt()
        )).thenApply(result -> switch (result) {
            case NoticeProcessed ignored -> NoticeHttpResult.ACCEPTED;
            case NoticeRejected rejected -> switch (rejected.reason()) {
                case INVALID -> NoticeHttpResult.INVALID;
                case TEMPORARILY_UNAVAILABLE -> NoticeHttpResult.TEMPORARILY_UNAVAILABLE;
            };
        });
    }

    private static BidHttpResult toHttpResult(BidExecuted executed, String requestId) {
        var prepared = executed.decision().bids();
        if (prepared.isEmpty()) {
            return NoContent.INSTANCE;
        }
        List<Bid> bids = prepared.stream()
                .map(DefaultDspOpenRtbApi::toBid)
                .toList();
        return BidResponse.withBids(requestId, List.of(new SeatBid(bids)));
    }

    private static Bid toBid(PreparedBid prepared) {
        var urls = prepared.notificationUrls();
        return new Bid(
                prepared.bidId(),
                prepared.impressionId(),
                prepared.campaignId(),
                prepared.creativeId(),
                prepared.cpmMilliKrw(),
                urls.winNoticeUrl(),
                urls.lossNoticeUrl(),
                urls.billingNoticeUrl(),
                2
        );
    }
}
