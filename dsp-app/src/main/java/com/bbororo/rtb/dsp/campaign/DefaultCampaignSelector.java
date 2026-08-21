package com.bbororo.rtb.dsp.campaign;

import com.bbororo.rtb.dsp.campaign.CampaignMessages.Campaign;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.Creative;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.RankCampaigns;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.SnapshotInstallResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 불변 캠페인 스냅숏을 정렬된 64비트 원시 long[] 배열과 인메모리 버킷으로 구축해
 * L1 캐시 친화적 이진 탐색(Binary Search)으로 무락 서빙한다.
 */
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

        SnapshotIndex newIndex = buildSnapshotIndex(snapshot);
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

        long targetKey = SlotDimensionPacker.pack(
                request.impression().width(),
                request.impression().height()
        );

        int bucketIndex = Arrays.binarySearch(index.sortedDimensionKeys(), targetKey);
        if (bucketIndex < 0) {
            return List.of(); // 해당 규격에 매칭되는 소재가 없음
        }

        IndexedCreative[] creatives = index.candidateBuckets()[bucketIndex];
        List<CampaignCandidate> candidates = new ArrayList<>(creatives.length);
        for (IndexedCreative creative : creatives) {
            candidates.add(new CampaignCandidate(
                    creative.campaignId(),
                    creative.creativeId(),
                    creative.bidCpmMilliKrw(),
                    0L
            ));
        }
        return Collections.unmodifiableList(candidates);
    }

    private static SnapshotIndex buildSnapshotIndex(CampaignSnapshot snapshot) {
        Map<Long, List<IndexedCreative>> tempMap = new HashMap<>();
        for (Campaign campaign : snapshot.campaigns()) {
            if (!campaign.active()) {
                continue; // 비활성 캠페인은 역색인 버킷에서 사전 배제
            }
            for (Creative creative : campaign.creatives()) {
                long packedKey = SlotDimensionPacker.pack(creative.width(), creative.height());
                tempMap.computeIfAbsent(packedKey, k -> new ArrayList<>())
                        .add(new IndexedCreative(
                                campaign.id(),
                                creative.id(),
                                campaign.bidCpmMilliKrw(),
                                campaign.startsAt(),
                                campaign.endsAt()
                        ));
            }
        }

        long[] sortedKeys = new long[tempMap.size()];
        int i = 0;
        for (Long key : tempMap.keySet()) {
            sortedKeys[i++] = key;
        }
        Arrays.sort(sortedKeys);

        IndexedCreative[][] buckets = new IndexedCreative[sortedKeys.length][];
        for (int b = 0; b < sortedKeys.length; b++) {
            List<IndexedCreative> list = tempMap.get(sortedKeys[b]);
            buckets[b] = list.toArray(new IndexedCreative[0]);
        }

        return new SnapshotIndex(snapshot.version(), snapshot.checksum(), sortedKeys, buckets);
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
            long[] sortedDimensionKeys,
            IndexedCreative[][] candidateBuckets
    ) {
    }
}
