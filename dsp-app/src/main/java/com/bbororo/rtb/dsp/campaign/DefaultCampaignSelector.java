package com.bbororo.rtb.dsp.campaign;

import com.bbororo.rtb.dsp.campaign.CampaignMessages.Campaign;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.Creative;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.RankCampaigns;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.SnapshotInstallResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 불변 캠페인 스냅숏을 정렬된 64비트 원시 long[] 배열과 사전 생성된 불변 후보 리스트로 구축해
 * Hot-Path에서 힙 메모리 할당 없이(Zero-Allocation) L1 캐시 친화적 이진 탐색으로 서빙한다.
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
            return List.of(); // 해당 규격에 매칭되는 소재가 없음
        }

        // Zero-Allocation: install() 시점에 미리 만들어둔 불변 리스트를 포인터로 즉시 반환
        return index.candidateBuckets()[bucketIndex];
    }

    @SuppressWarnings("unchecked")
    private static SnapshotIndex buildSnapshotIndex(CampaignSnapshot snapshot) {
        Map<Long, List<CampaignCandidate>> tempMap = new HashMap<>();
        for (Campaign campaign : snapshot.campaigns()) {
            if (!campaign.active()) {
                continue; // 비활성 캠페인은 역색인 버킷에서 사전 배제
            }
            for (Creative creative : campaign.creatives()) {
                long packedKey = SlotDimensionPacker.pack(creative.width(), creative.height());
                tempMap.computeIfAbsent(packedKey, k -> new ArrayList<>())
                        .add(new CampaignCandidate(
                                campaign.id(),
                                creative.id(),
                                campaign.bidCpmMilliKrw(),
                                0L
                        ));
            }
        }

        long[] sortedKeys = new long[tempMap.size()];
        int i = 0;
        for (Long key : tempMap.keySet()) {
            sortedKeys[i++] = key;
        }
        Arrays.sort(sortedKeys);

        List<CampaignCandidate>[] buckets = new List[sortedKeys.length];
        for (int b = 0; b < sortedKeys.length; b++) {
            List<CampaignCandidate> list = tempMap.get(sortedKeys[b]);
            buckets[b] = List.copyOf(list); // 불변 리스트로 고정
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

    record SnapshotIndex(
            String version,
            String checksum,
            long[] sortedDimensionKeys,
            List<CampaignCandidate>[] candidateBuckets
    ) {
    }
}
