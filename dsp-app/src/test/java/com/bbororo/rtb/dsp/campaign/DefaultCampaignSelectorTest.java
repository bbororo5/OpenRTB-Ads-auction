package com.bbororo.rtb.dsp.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.campaign.CampaignModels.Campaign;
import com.bbororo.rtb.dsp.campaign.CampaignModels.CampaignCandidate;
import com.bbororo.rtb.dsp.campaign.CampaignModels.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaign.CampaignModels.Creative;
import com.bbororo.rtb.dsp.campaign.CampaignModels.RankCampaigns;
import com.bbororo.rtb.dsp.campaign.CampaignModels.SnapshotInstallResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("캠페인 선택기(CampaignSelector) 스냅샷 적재 및 역색인 구축 단위 테스트")
class DefaultCampaignSelectorTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Instant STARTS_AT = NOW.minusSeconds(3600);
    private static final Instant ENDS_AT = NOW.plusSeconds(3600);

    private DefaultCampaignSelector selector;

    @BeforeEach
    void setUp() {
        selector = new DefaultCampaignSelector();
    }

    @Test
    @DisplayName("[V0] 최초 설치: Cold-Start 상태에서 첫 스냅샷이 INSTALLED로 정상 적재된다")
    void initialInstallReturnsInstalled() {
        var snapshot = sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250)));

        SnapshotInstallResult result = selector.install(snapshot);

        assertEquals(SnapshotInstallResult.INSTALLED, result);
    }

    @Test
    @DisplayName("[V2 x C1] 멱등 재설치: 동일 버전과 동일 체크섬 인입 시 ALREADY_INSTALLED를 반환한다")
    void identicalSnapshotReturnsAlreadyInstalled() {
        var snapshot = sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250)));
        selector.install(snapshot);

        SnapshotInstallResult result = selector.install(snapshot);

        assertEquals(SnapshotInstallResult.ALREADY_INSTALLED, result);
    }

    @Test
    @DisplayName("[V2 x C2] 오염 감지: 동일 버전이지만 체크섬이 다른 스냅샷 인입 시 CHECKSUM_MISMATCH로 거절된다")
    void sameVersionDifferentChecksumReturnsChecksumMismatch() {
        var original = sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250)));
        var tampered = sampleSnapshot("v1", "chk-TAMPERED", List.of(activeCampaign("camp-1", 300, 250)));
        selector.install(original);

        SnapshotInstallResult result = selector.install(tampered);

        assertEquals(SnapshotInstallResult.CHECKSUM_MISMATCH, result);
    }

    @Test
    @DisplayName("[V3] 롤백 방어: 현재 버전(v2)보다 하위 버전(v1) 인입 시 VERSION_CONFLICT로 거절된다")
    void lowerVersionReturnsVersionConflict() {
        var v2 = sampleSnapshot("v2", "chk-2", List.of(activeCampaign("camp-1", 300, 250)));
        var v1 = sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250)));
        selector.install(v2);

        SnapshotInstallResult result = selector.install(v1);

        assertEquals(SnapshotInstallResult.VERSION_CONFLICT, result);
    }

    @Test
    @DisplayName("[버전] 자연수 비교: v10 스냅샷은 사전순(v10 < v2)이 아닌 자연수(v10 > v2)로 평가되어 정상 설치된다")
    void numericVersionComparisonAllowsV10AfterV2() {
        var v2 = sampleSnapshot("v2", "chk-2", List.of(activeCampaign("camp-v2", 300, 250)));
        var v10 = sampleSnapshot("v10", "chk-10", List.of(activeCampaign("camp-v10", 300, 250)));
        selector.install(v2);

        SnapshotInstallResult result = selector.install(v10);

        assertEquals(SnapshotInstallResult.INSTALLED, result);
        List<CampaignCandidate> candidates = selector.rankCandidates(rankRequest(300, 250));
        assertEquals("camp-v10", candidates.get(0).campaignId());
    }

    @Test
    @DisplayName("[불변 보존] 거절 후 상태 유지: 설치가 거절되어도 기존에 적재된 정상 스냅샷이 손상 없이 유지된다")
    void rejectedInstallPreservesExistingSnapshotState() {
        var v2 = sampleSnapshot("v2", "chk-2", List.of(activeCampaign("camp-v2", 300, 250)));
        var v1 = sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-v1", 300, 250)));
        selector.install(v2);

        selector.install(v1); // VERSION_CONFLICT 발생

        List<CampaignCandidate> candidates = selector.rankCandidates(rankRequest(300, 250));
        assertEquals(1, candidates.size());
        assertEquals("camp-v2", candidates.get(0).campaignId());
    }

    @Test
    @DisplayName("[인덱스] 비활성 제외: active=false인 캠페인은 역색인 버킷에 등록되지 않는다")
    void inactiveCampaignIsExcludedFromInvertedIndex() {
        var inactive = new Campaign(
                "inactive-camp",
                false,
                1_000,
                STARTS_AT,
                ENDS_AT,
                List.of(new Creative("cr-inactive", 300, 250))
        );
        selector.install(sampleSnapshot("v1", "chk-1", List.of(inactive)));

        List<CampaignCandidate> candidates = selector.rankCandidates(rankRequest(300, 250));

        assertTrue(candidates.isEmpty());
    }

    @Test
    @DisplayName("[인덱스] 다중 규격 매핑: 1개 캠페인이 2개 소재(300x250, 728x90)를 가지면 양쪽 버킷에 각각 등록된다")
    void multiCreativeCampaignIsIndexedInBothDimensionBuckets() {
        var multiCreativeCamp = new Campaign(
                "camp-multi",
                true,
                2_000,
                STARTS_AT,
                ENDS_AT,
                List.of(
                        new Creative("cr-banner", 300, 250),
                        new Creative("cr-leaderboard", 728, 90)
                )
        );
        selector.install(sampleSnapshot("v1", "chk-1", List.of(multiCreativeCamp)));

        List<CampaignCandidate> bannerCandidates = selector.rankCandidates(rankRequest(300, 250));
        List<CampaignCandidate> leaderboardCandidates = selector.rankCandidates(rankRequest(728, 90));

        assertEquals(1, bannerCandidates.size());
        assertEquals("cr-banner", bannerCandidates.get(0).creativeId());

        assertEquals(1, leaderboardCandidates.size());
        assertEquals("cr-leaderboard", leaderboardCandidates.get(0).creativeId());
    }

    @Test
    @DisplayName("[인덱스] 버킷 누적: 동일 규격(300x250)을 가진 서로 다른 캠페인들이 하나의 버킷에 함께 누적된다")
    void multipleCampaignsWithSameDimensionAreAggregatedInSameBucket() {
        var campA = activeCampaign("camp-A", 300, 250);
        var campB = activeCampaign("camp-B", 300, 250);
        selector.install(sampleSnapshot("v1", "chk-1", List.of(campA, campB)));

        List<CampaignCandidate> candidates = selector.rankCandidates(rankRequest(300, 250));

        assertEquals(2, candidates.size());
    }

    @Test
    @DisplayName("[Cold-Start] 미설치 방어: 스냅샷이 설치되지 않은 상태에서 조회 시 NPE 없이 빈 목록을 반환한다")
    void uninstalledColdStartReturnsEmptyListSafely() {
        List<CampaignCandidate> candidates = selector.rankCandidates(rankRequest(300, 250));

        assertTrue(candidates.isEmpty());
    }

    @Test
    @DisplayName("[이진 탐색] 미등록 규격 조회: 스냅샷에 등록되지 않은 규격(999x999) 요청 시 이진 탐색이 즉시 빈 목록을 반환한다")
    void unregisteredDimensionReturnsEmptyList() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250))));

        List<CampaignCandidate> candidates = selector.rankCandidates(rankRequest(999, 999));

        assertTrue(candidates.isEmpty());
    }

    // =========================================================================
    // 2단계: 실시간 적격성 필터링 (시간 및 바닥가 경계값 테스트)
    // =========================================================================

    @Test
    @DisplayName("[시간 경계 1] 시작 전 배제: evaluatedAt이 startsAt - 1ms이면 적격 후보에서 배제된다")
    void evaluatedAtBeforeStartsAtIsExcluded() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250))));

        var request = rankRequest(300, 250, 500, STARTS_AT.minusMillis(1));
        List<CampaignCandidate> candidates = selector.rankCandidates(request);

        assertTrue(candidates.isEmpty());
    }

    @Test
    @DisplayName("[시간 경계 2] 시작 정각 포함: evaluatedAt이 정확히 startsAt이면 적격 후보로 포함된다")
    void evaluatedAtExactStartsAtIsEligible() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250))));

        var request = rankRequest(300, 250, 500, STARTS_AT);
        List<CampaignCandidate> candidates = selector.rankCandidates(request);

        assertEquals(1, candidates.size());
        assertEquals("camp-1", candidates.get(0).campaignId());
    }

    @Test
    @DisplayName("[시간 경계 3] 집행 기간 중 포함: evaluatedAt이 startsAt < evaluatedAt < endsAt이면 적격 후보로 포함된다")
    void evaluatedAtDuringActiveWindowIsEligible() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250))));

        var request = rankRequest(300, 250, 500, NOW);
        List<CampaignCandidate> candidates = selector.rankCandidates(request);

        assertEquals(1, candidates.size());
        assertEquals("camp-1", candidates.get(0).campaignId());
    }

    @Test
    @DisplayName("[시간 경계 4] 종료 정각 배제: evaluatedAt이 정확히 endsAt이면 반열린 구간 [startsAt, endsAt)에 따라 배제된다")
    void evaluatedAtExactEndsAtIsExcluded() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250))));

        var request = rankRequest(300, 250, 500, ENDS_AT);
        List<CampaignCandidate> candidates = selector.rankCandidates(request);

        assertTrue(candidates.isEmpty());
    }

    @Test
    @DisplayName("[시간 경계 5] 종료 후 배제: evaluatedAt이 endsAt + 1ms이면 적격 후보에서 배제된다")
    void evaluatedAtAfterEndsAtIsExcluded() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-1", 300, 250))));

        var request = rankRequest(300, 250, 500, ENDS_AT.plusMillis(1));
        List<CampaignCandidate> candidates = selector.rankCandidates(request);

        assertTrue(candidates.isEmpty());
    }

    @Test
    @DisplayName("[바닥가 경계 1] 바닥가 초과 포함: 입찰가(1000) > 바닥가(500)이면 적격 후보로 포함된다")
    void bidCpmGreaterThanBidFloorIsEligible() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(activeCampaignWithBid("camp-1", 300, 250, 1_000))));

        var request = rankRequest(300, 250, 500, NOW);
        List<CampaignCandidate> candidates = selector.rankCandidates(request);

        assertEquals(1, candidates.size());
        assertEquals("camp-1", candidates.get(0).campaignId());
    }

    @Test
    @DisplayName("[바닥가 경계 2] 바닥가 일치 포함: 입찰가(1000) == 바닥가(1000)이면 적격 후보로 포함된다")
    void bidCpmEqualToBidFloorIsEligible() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(activeCampaignWithBid("camp-1", 300, 250, 1_000))));

        var request = rankRequest(300, 250, 1_000, NOW);
        List<CampaignCandidate> candidates = selector.rankCandidates(request);

        assertEquals(1, candidates.size());
        assertEquals("camp-1", candidates.get(0).campaignId());
    }

    @Test
    @DisplayName("[바닥가 경계 3] 바닥가 미달 배제: 입찰가(1000) < 바닥가(1001)이면 적격 후보에서 배제된다")
    void bidCpmLessThanBidFloorIsExcluded() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(activeCampaignWithBid("camp-1", 300, 250, 1_000))));

        var request = rankRequest(300, 250, 1_001, NOW);
        List<CampaignCandidate> candidates = selector.rankCandidates(request);

        assertTrue(candidates.isEmpty());
    }

    // =========================================================================
    // 3단계: 페이싱 지연 순위화 및 동점 처리 (Ranking & Pacing Lag Ordering)
    // =========================================================================

    @Test
    @DisplayName("[순위 1] 지연율 내림차순 정렬: pacingLagPpm이 큰(목표 대비 소진이 뒤처진) 캠페인이 최우선 순위로 정렬된다")
    void pacingLagDescendingOrdering() {
        CampaignPacingSource pacingSource = (campId, now) -> switch (campId) {
            case "camp-A" -> 500L;
            case "camp-B" -> 1_000L;
            case "camp-C" -> 100L;
            default -> 0L;
        };
        var pacingSelector = new DefaultCampaignSelector(pacingSource);
        pacingSelector.install(sampleSnapshot("v1", "chk-1", List.of(
                activeCampaign("camp-A", 300, 250),
                activeCampaign("camp-B", 300, 250),
                activeCampaign("camp-C", 300, 250)
        )));

        List<CampaignCandidate> candidates = pacingSelector.rankCandidates(rankRequest(300, 250));

        assertEquals(3, candidates.size());
        assertEquals("camp-B", candidates.get(0).campaignId());
        assertEquals(1_000L, candidates.get(0).pacingLagPpm());
        assertEquals("camp-A", candidates.get(1).campaignId());
        assertEquals(500L, candidates.get(1).pacingLagPpm());
        assertEquals("camp-C", candidates.get(2).campaignId());
        assertEquals(100L, candidates.get(2).pacingLagPpm());
    }

    @Test
    @DisplayName("[순위 2] 양수 및 음수 지연율 정렬: 초과 소진된 음수 지연율(-200)보다 지연 소진된 양수 지연율(+300)이 앞선다")
    void positiveAndNegativePacingLagOrdering() {
        CampaignPacingSource pacingSource = (campId, now) -> switch (campId) {
            case "camp-A" -> -200L; // 목표보다 빠름 (과소진)
            case "camp-B" -> 300L;  // 목표보다 느림 (지연)
            default -> 0L;
        };
        var pacingSelector = new DefaultCampaignSelector(pacingSource);
        pacingSelector.install(sampleSnapshot("v1", "chk-1", List.of(
                activeCampaign("camp-A", 300, 250),
                activeCampaign("camp-B", 300, 250)
        )));

        List<CampaignCandidate> candidates = pacingSelector.rankCandidates(rankRequest(300, 250));

        assertEquals(2, candidates.size());
        assertEquals("camp-B", candidates.get(0).campaignId());
        assertEquals("camp-A", candidates.get(1).campaignId());
    }

    @Test
    @DisplayName("[순위 3] 동일 지연율 동점 처리: pacingLagPpm이 동일(500)하면 campaignId 오름차순(사전순)으로 정렬된다")
    void pacingLagTieBreaksByCampaignIdAscending() {
        CampaignPacingSource pacingSource = (campId, now) -> 500L; // 전원 500ppm 동점
        var pacingSelector = new DefaultCampaignSelector(pacingSource);
        pacingSelector.install(sampleSnapshot("v1", "chk-1", List.of(
                activeCampaign("camp-Z", 300, 250),
                activeCampaign("camp-A", 300, 250),
                activeCampaign("camp-M", 300, 250)
        )));

        List<CampaignCandidate> candidates = pacingSelector.rankCandidates(rankRequest(300, 250));

        assertEquals(3, candidates.size());
        assertEquals("camp-A", candidates.get(0).campaignId());
        assertEquals("camp-M", candidates.get(1).campaignId());
        assertEquals("camp-Z", candidates.get(2).campaignId());
    }

    @Test
    @DisplayName("[순위 4] 복합 정렬: 1차 지연율 내림차순 후 2차 동점 ID 오름차순 정렬이 정확히 복합 적용된다")
    void compositePacingLagAndIdTieBreaking() {
        CampaignPacingSource pacingSource = (campId, now) -> switch (campId) {
            case "camp-Z" -> 1_000L;
            case "camp-A" -> 1_000L;
            case "camp-B" -> 500L;
            default -> 0L;
        };
        var pacingSelector = new DefaultCampaignSelector(pacingSource);
        pacingSelector.install(sampleSnapshot("v1", "chk-1", List.of(
                activeCampaign("camp-Z", 300, 250),
                activeCampaign("camp-A", 300, 250),
                activeCampaign("camp-B", 300, 250)
        )));

        List<CampaignCandidate> candidates = pacingSelector.rankCandidates(rankRequest(300, 250));

        assertEquals(3, candidates.size());
        assertEquals("camp-A", candidates.get(0).campaignId()); // 1000ppm 동점 중 ID 빠른 순
        assertEquals("camp-Z", candidates.get(1).campaignId()); // 1000ppm
        assertEquals("camp-B", candidates.get(2).campaignId()); // 500ppm
    }

    @Test
    @DisplayName("[순위 5] 기본 페이싱 소스: PacingSource를 주입하지 않은 기본 선택기는 campaignId 오름차순으로 정렬된다")
    void defaultPacingSourceOrdersByCampaignIdAscending() {
        selector.install(sampleSnapshot("v1", "chk-1", List.of(
                activeCampaign("camp-B", 300, 250),
                activeCampaign("camp-A", 300, 250)
        )));

        List<CampaignCandidate> candidates = selector.rankCandidates(rankRequest(300, 250));

        assertEquals(2, candidates.size());
        assertEquals("camp-A", candidates.get(0).campaignId());
        assertEquals("camp-B", candidates.get(1).campaignId());
    }

    // =========================================================================
    // 테스트 픽스처 헬퍼 메서드
    // =========================================================================
    private static CampaignSnapshot sampleSnapshot(String version, String checksum, List<Campaign> campaigns) {
        return new CampaignSnapshot(version, checksum, campaigns);
    }

    private static Campaign activeCampaign(String campaignId, int width, int height) {
        return activeCampaignWithBid(campaignId, width, height, 1_000);
    }

    private static Campaign activeCampaignWithBid(String campaignId, int width, int height, long bidCpmMilliKrw) {
        return new Campaign(
                campaignId,
                true,
                bidCpmMilliKrw,
                STARTS_AT,
                ENDS_AT,
                List.of(new Creative("cr-" + campaignId, width, height))
        );
    }

    private static RankCampaigns rankRequest(int width, int height) {
        return rankRequest(width, height, 500, NOW);
    }

    private static RankCampaigns rankRequest(int width, int height, long bidFloor, Instant evaluatedAt) {
        return new RankCampaigns(
                "auction-1",
                new Impression("imp-1", width, height, bidFloor, 2),
                evaluatedAt
        );
    }
}
