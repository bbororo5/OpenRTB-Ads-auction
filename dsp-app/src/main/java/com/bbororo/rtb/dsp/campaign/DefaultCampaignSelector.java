package com.bbororo.rtb.dsp.campaign;

import static com.bbororo.rtb.dsp.contract.ContractChecks.immutableList;

import com.bbororo.rtb.dsp.campaign.CampaignMessages.Campaign;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.Creative;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.RankCampaigns;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.SnapshotInstallResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 불변 캠페인 스냅숏을 소재 규격별 역색인으로 사전 구축해 무락(Lock-Free)으로 서빙한다. */
public final class DefaultCampaignSelector implements CampaignSelector {

    private final AtomicReference<SnapshotIndex> currentIndex = new AtomicReference<>();

    @Override
    public SnapshotInstallResult install(CampaignSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        SnapshotIndex current = currentIndex.get();
        if (current != null) {
            if (current.version().equals(snapshot.version())) {
                if (current.checksum().equals(snapshot.checksum())) {
                    return SnapshotInstallResult.ALREADY_INSTALLED;
                }
                return SnapshotInstallResult.CHECKSUM_MISMATCH;
            }
            if (snapshot.version().compareTo(current.version()) < 0) {
                return SnapshotInstallResult.VERSION_CONFLICT;
            }
        }

        Map<SlotDimension, List<IndexedCreative>> indexMap = buildInvertedIndex(snapshot.campaigns());
        SnapshotIndex newIndex = new SnapshotIndex(snapshot.version(), snapshot.checksum(), indexMap);
        currentIndex.set(newIndex);
        return SnapshotInstallResult.INSTALLED;
    }

    @Override
    public List<CampaignCandidate> rankCandidates(RankCampaigns request) {
        Objects.requireNonNull(request, "request");
        SnapshotIndex index = currentIndex.get();
        if (index == null) {
            return List.of();
        }

        SlotDimension requestedDim = new SlotDimension(
                request.impression().width(),
                request.impression().height()
        );
        List<IndexedCreative> indexedCreatives = index.invertedIndex().get(requestedDim);
        if (indexedCreatives == null || indexedCreatives.isEmpty()) {
            return List.of();
        }

        List<CampaignCandidate> candidates = new ArrayList<>(indexedCreatives.size());
        for (IndexedCreative creative : indexedCreatives) {
            candidates.add(new CampaignCandidate(
                    creative.campaignId(),
                    creative.creativeId(),
                    creative.bidCpmMilliKrw(),
                    0L
            ));
        }
        return Collections.unmodifiableList(candidates);
    }

    private static Map<SlotDimension, List<IndexedCreative>> buildInvertedIndex(List<Campaign> campaigns) {
        Map<SlotDimension, List<IndexedCreative>> builder = new HashMap<>();
        for (Campaign campaign : campaigns) {
            if (!campaign.active()) {
                continue; // 비활성 캠페인은 역색인 버킷에서 사전 제외
            }
            for (Creative creative : campaign.creatives()) {
                SlotDimension dim = new SlotDimension(creative.width(), creative.height());
                builder.computeIfAbsent(dim, k -> new ArrayList<>())
                        .add(new IndexedCreative(
                                campaign.id(),
                                creative.id(),
                                campaign.bidCpmMilliKrw(),
                                campaign.startsAt(),
                                campaign.endsAt()
                        ));
            }
        }

        Map<SlotDimension, List<IndexedCreative>> frozenMap = new HashMap<>();
        builder.forEach((dim, list) -> frozenMap.put(dim, List.copyOf(list)));
        return Map.copyOf(frozenMap);
    }

    record SlotDimension(int width, int height) {
    }

    record IndexedCreative(
            String campaignId,
            String creativeId,
            long bidCpmMilliKrw,
            Instant startsAt,
            Instant endsAt
    ) {
    }

    record SnapshotIndex(
            String version,
            String checksum,
            Map<SlotDimension, List<IndexedCreative>> invertedIndex
    ) {
    }
}
