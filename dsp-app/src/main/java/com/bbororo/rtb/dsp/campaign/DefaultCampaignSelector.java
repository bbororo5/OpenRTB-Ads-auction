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
 * 불변 캠페인 스냅숏을 정렬된 64비트 원시 long[] 배열과 사전 생성된 불변 후보 리스트로 구축해
 * Hot-Path에서 힙 메모리 할당을 극소화(Zero/Sub-Allocation)하며 L1 캐시 친화적 이진 탐색 및 적격성 필터링을 수행한다.
 */
public final class DefaultCampaignSelector implements CampaignSelector {

    private final AtomicReference<SnapshotIndex> currentIndex = new AtomicReference<>();

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

        // 1. 적격 후보 수 카운팅 (메모리 할당 없는 빠른 스캔)
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

        // Fast-Path: 버킷 내 모든 후보가 적격인 경우 사전 빌드된 불변 리스트 그대로 반환 (Zero-Allocation!)
        if (eligibleCount == bucket.length) {
            return index.fullCandidateLists()[bucketIndex];
        }

        // 단 1건만 통과한 경우 싱글톤 불변 리스트 반환
        if (eligibleCount == 1) {
            return List.of(bucket[lastEligibleIdx].candidate());
        }

        // 부분 통과: 이미 사전 생성된 Candidate 인스턴스를 재사용해 리스트 구성
        List<CampaignCandidate> result = new ArrayList<>(eligibleCount);
        for (IndexedCreative creative : bucket) {
            if (creative.isEligible(evaluatedAt, bidFloor)) {
                result.add(creative.candidate());
            }
        }
        return Collections.unmodifiableList(result);
    }

    @SuppressWarnings("unchecked")
    private static SnapshotIndex buildSnapshotIndex(CampaignSnapshot snapshot) {
        Map<Long, List<IndexedCreative>> tempMap = new HashMap<>();
        for (Campaign campaign : snapshot.campaigns()) {
            if (!campaign.active()) {
                continue; // 비활성 캠페인은 역색인 버킷에서 사전 배제
            }
            for (Creative creative : campaign.creatives()) {
                long packedKey = SlotDimensionPacker.pack(creative.width(), creative.height());
                CampaignCandidate preallocatedCandidate = new CampaignCandidate(
                        campaign.id(),
                        creative.id(),
                        campaign.bidCpmMilliKrw(),
                        0L
                );
                tempMap.computeIfAbsent(packedKey, k -> new ArrayList<>())
                        .add(new IndexedCreative(
                                campaign.id(),
                                creative.id(),
                                campaign.bidCpmMilliKrw(),
                                campaign.startsAt(),
                                campaign.endsAt(),
                                preallocatedCandidate
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
        List<CampaignCandidate>[] fullLists = new List[sortedKeys.length];

        for (int b = 0; b < sortedKeys.length; b++) {
            List<IndexedCreative> list = tempMap.get(sortedKeys[b]);
            buckets[b] = list.toArray(new IndexedCreative[0]);

            List<CampaignCandidate> candidateList = new ArrayList<>(list.size());
            for (IndexedCreative ic : list) {
                candidateList.add(ic.candidate());
            }
            fullLists[b] = List.copyOf(candidateList); // Fast-Path용 불변 리스트 사전 생성
        }

        return new SnapshotIndex(snapshot.version(), snapshot.checksum(), sortedKeys, buckets, fullLists);
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
            Instant endsAt,
            CampaignCandidate candidate
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
            IndexedCreative[][] candidateBuckets,
            List<CampaignCandidate>[] fullCandidateLists
    ) {
    }
}
