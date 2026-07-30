package com.bbororo.rtb.ssp.auction;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.NoticeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.dspbid.DspBidExecutor;
import com.bbororo.rtb.ssp.winner.WinnerSelector;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 절대 기한 안에서 DSP 실행 결과를 낙찰 결정으로 연결한다. */
public final class DeadlineBoundAuctionCoordinator implements AuctionCoordinator {

    private final DspBidExecutor bidExecutor;
    private final WinnerSelector winnerSelector;
    private final List<String> dspIds;
    private final Set<String> dspIdSet;

    public DeadlineBoundAuctionCoordinator(
            DspBidExecutor bidExecutor,
            WinnerSelector winnerSelector,
            List<String> dspIds
    ) {
        this.bidExecutor = Objects.requireNonNull(bidExecutor);
        this.winnerSelector = Objects.requireNonNull(winnerSelector);
        this.dspIds = List.copyOf(dspIds);
        if (this.dspIds.isEmpty()) {
            throw new IllegalArgumentException("dspIds must not be empty");
        }
        this.dspIdSet = Set.copyOf(new LinkedHashSet<>(this.dspIds));
        if (dspIdSet.size() != this.dspIds.size()) {
            throw new IllegalArgumentException("dspIds must not contain duplicates");
        }
    }

    @Override
    public AuctionOutcome runAuction(StartAuction command) {
        Objects.requireNonNull(command);
        String auctionId = UUID.randomUUID().toString();
        requireWithinDeadline(command);

        BidResponses responses = bidExecutor.requestBids(new BidRequestBatch(
                auctionId,
                command.request(),
                dspIds,
                command.deadline()
        ));
        requireWithinDeadline(command);
        validateParticipants(responses);

        AuctionWinners winners = winnerSelector.selectWinners(auctionId, command.request(), responses);
        requireWithinDeadline(command);

        List<AuctionNotice> notices = notices(responses, winners);
        requireWithinDeadline(command);
        return new AuctionOutcome(auctionId, winners, notices);
    }

    private void validateParticipants(BidResponses responses) {
        Objects.requireNonNull(responses, "DspBidExecutor must return responses");
        boolean containsUnknownDsp = responses.outcomes().stream()
                .anyMatch(outcome -> !dspIdSet.contains(outcome.dspId()));
        if (containsUnknownDsp) {
            throw new IllegalStateException("DSP responses must belong to requested participants");
        }
    }

    private static void requireWithinDeadline(StartAuction command) {
        if (command.deadline().isExpired()) {
            throw new AuctionDeadlineExceededException();
        }
    }

    private static List<AuctionNotice> notices(BidResponses responses, AuctionWinners winners) {
        Set<String> winningBids = winners.winners().stream()
                .map(winner -> bidKey(winner.dspId(), winner.bidId(), winner.impId()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return responses.outcomes().stream()
                .filter(outcome -> outcome.kind() == DspCallOutcomeKind.VALID_BID)
                .flatMap(outcome -> outcome.bids().stream())
                .map(bid -> winningBids.contains(bidKey(bid.dspId(), bid.bidId(), bid.impId()))
                        ? new AuctionNotice(NoticeKind.WIN, bid.nurl())
                        : new AuctionNotice(NoticeKind.LOSS, bid.lurl()))
                .toList();
    }

    private static String bidKey(String dspId, String bidId, String impId) {
        return dspId + '\u0000' + bidId + '\u0000' + impId;
    }
}
