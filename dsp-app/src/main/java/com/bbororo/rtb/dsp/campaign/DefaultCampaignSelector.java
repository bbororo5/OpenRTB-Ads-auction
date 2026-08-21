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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 불변 캠페인 스냅숏을 정렬된 64비트 원시 long[] 배열로 구축해 L1 캐시 친화적 이진 탐색으로 조회하고,
 * 실시간 유효성 필터링 후 페이싱 지연율(pacingLagPpm DESC) 및 캠페인 ID(campaignId ASC) 순으로 순위화한다.
 */
public final class DefaultCampaignSelector implements CampaignSelector {

    private static final Comparator<CampaignCandidate> CANDIDATE_COMPARATOR =
            Comparator.comparingLong(CampaignCandidate::pacingLagPpm)
                    .reversed()
                    .thenComparing(CampaignCandidate::campaignId);

    private final CampaignPacingSource pacingSource;
    private final AtomicReference<SnapshotIndex> currentIndex = new AtomicReference<>();

    public DefaultCampaignSelector() {
        this((campaignId, evaluatedAt) -> 0L);
    }

    public DefaultCampaignSelector(CampaignPacingSource pacingSource) {
        this.pacingSource = Objects.requireNonNull(pacingSource, "pacingSource");
    }

    @Override
    public SnapshotInstallResult install(CampaignSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        while (true) {
            SnapshotIndex current = currentIndex.get();
            if (current != null) {
                if (current.version().equals(snapshot.version())) {
                    if (current.checksum().equals(snapshot.checksum())) {
                        return SnapshotInstallResult.ALREADY_INSTALLED;
                    }
                    return SnapshotInstallResult.CHECKSUM_MISMATCH;
                }
                if (compareVersions(snapshot.version(), current.version()) < 0) {
                    return SnapshotInstallResult.VERSION_CONFLICT;
                }
            }

            SnapshotIndex newIndex = buildSnapshotIndex(snapshot);
            if (currentIndex.compareAndSet(current, newIndex)) {
                return SnapshotInstallResult.INSTALLED;
            }
        }
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
            return List.of(); // 해당 규격에 매칭되는 소재가 없음 (Zero-Allocation)
        }

        IndexedCreative[] bucket = index.candidateBuckets()[bucketIndex];
        Instant evaluatedAt = request.evaluatedAt();
        long bidFloor = request.impression().bidFloorCpmMilliKrw();

        // 1. 적격 후보 수 카운팅 및 단일 후보 최적화
        int eligibleCount = 0;
        int lastEligibleIdx = -1;
        for (int i = 0; i < bucket.length; i++) {
            if (bucket[i].isEligible(evaluatedAt, bidFloor)) {
                eligibleCount++;
                lastEligibleIdx = i;
            }
        }

        if (eligibleCount == 0) {
            return List.of(); // 전원 탈락 시 불변 싱글톤 반환 (Zero-Allocation)
        }

        // 단 1건만 통과한 경우 정렬 없이 즉시 반환
        if (eligibleCount == 1) {
            IndexedCreative ic = bucket[lastEligibleIdx];
            long lag = pacingSource.pacingLagPpm(ic.campaignId(), evaluatedAt);
            return List.of(new CampaignCandidate(ic.campaignId(), ic.creativeId(), ic.bidCpmMilliKrw(), lag));
        }

        // 2. 복수 후보: 페이싱 지연율 계산 및 순위 정렬
        List<CampaignCandidate> candidates = new ArrayList<>(eligibleCount);
        for (IndexedCreative ic : bucket) {
            if (ic.isEligible(evaluatedAt, bidFloor)) {
                long lag = pacingSource.pacingLagPpm(ic.campaignId(), evaluatedAt);
                candidates.add(new CampaignCandidate(ic.campaignId(), ic.creativeId(), ic.bidCpmMilliKrw(), lag));
            }
        }

        // 1순위: pacingLagPpm 내림차순, 2순위: campaignId 오름차순
        candidates.sort(CANDIDATE_COMPARATOR);
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

    /** 자연수 버전("v10" > "v2")을 올바르게 비교한다. */
    static int compareVersions(String v1, String v2) {
        String num1 = v1.replaceAll("\\D", "");
        String num2 = v2.replaceAll("\\D", "");
        if (!num1.isEmpty() && !num2.isEmpty()) {
            try {
                long n1 = Long.parseLong(num1);
                long n2 = Long.parseLong(num2);
                return Long.compare(n1, n2);
            } catch (NumberFormatException ignored) {
                // fall back to string compare
            }
        }
        return v1.compareTo(v2);
    }

    record IndexedCreative(
            String campaignId,
            String creativeId,
            long bidCpmMilliKrw,
            Instant startsAt,
            Instant endsAt
    ) {
        /** 반열린 시간 구간 [startsAt, endsAt) 및 바닥가 적격성 검사 */
        boolean isEligible(Instant evaluatedAt, long bidFloorCpmMilliKrw) {
            return !evaluatedAt.isBefore(startsAt)
                    && evaluatedAt.isBefore(endsAt)
                    && bidCpmMilliKrw >= bidFloorCpmMilliKrw;
        }
    }

    record SnapshotIndex(
            String version,
            String checksum,
            long[] sortedDimensionKeys,
            IndexedCreative[][] candidateBuckets
    ) {
    }
}
