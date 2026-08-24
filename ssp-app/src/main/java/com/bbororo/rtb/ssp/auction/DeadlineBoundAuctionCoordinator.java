package com.bbororo.rtb.ssp.auction;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionNotice;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.BidResponses;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.NoticeKind;
import com.bbororo.rtb.ssp.contract.SspMessages.StartAuction;
import com.bbororo.rtb.ssp.contract.NoticeUrlTemplate.Context;
import com.bbororo.rtb.ssp.dspbid.DspBidExecutor;
import com.bbororo.rtb.ssp.winner.WinnerSelector;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 절대 기한 안에서 DSP 실행 결과를 낙찰 결정으로 연결한다. */
public final class DeadlineBoundAuctionCoordinator implements AuctionCoordinator {

    private static final int LOST_TO_HIGHER_BID = 102;
    private static final int BID_BELOW_FLOOR = 100;

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

        List<AuctionNotice> notices = notices(
                auctionId, command.request(), responses, winners);
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

    private static List<AuctionNotice> notices(
            String auctionId,
            AuctionRequest request,
            BidResponses responses,
            AuctionWinners winners
    ) {
        Set<String> winningBids = winners.winners().stream()
                .map(winner -> bidKey(winner.dspId(), winner.bidId(), winner.impId()))
                .collect(Collectors.toUnmodifiableSet());
        var winningPrices = winners.winners().stream().collect(Collectors.toUnmodifiableMap(
                winner -> winner.impId(),
                winner -> winner.cpmMilliKrw()
        ));
        Map<String, Long> floors = request.slots().stream().collect(Collectors.toUnmodifiableMap(
                slot -> slot.impId(),
                slot -> slot.floorCpmMilliKrw()
        ));
        return responses.outcomes().stream()
                .filter(outcome -> outcome.kind() == DspCallOutcomeKind.VALID_BID)
                .flatMap(outcome -> outcome.bids().stream())
                .map(bid -> {
                    boolean won = winningBids.contains(bidKey(
                            bid.dspId(), bid.bidId(), bid.impId()));
                    var context = new Context(
                            auctionId,
                            bid.impId(),
                            winningPrices.get(bid.impId()),
                            lossReason(won, bid.cpmMilliKrw(), floors.get(bid.impId())),
                            null
                    );
                    return won
                            ? new AuctionNotice(NoticeKind.WIN, bid.nurl().render(context))
                            : new AuctionNotice(NoticeKind.LOSS, bid.lurl().render(context));
                })
                .toList();
    }

    private static Integer lossReason(boolean won, long bidCpm, long floorCpm) {
        if (won) {
            return null;
        }
        return bidCpm < floorCpm ? BID_BELOW_FLOOR : LOST_TO_HIGHER_BID;
    }

    private static String bidKey(String dspId, String bidId, String impId) {
        return dspId + '\u0000' + bidId + '\u0000' + impId;
    }
}
