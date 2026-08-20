package com.bbororo.rtb.dsp.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.campaign.CampaignMessages.Campaign;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignCandidate;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.Creative;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.RankCampaigns;
import com.bbororo.rtb.dsp.campaign.CampaignMessages.SnapshotInstallResult;
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

    // =========================================================================
    // 테스트 픽스처 헬퍼 메서드
    // =========================================================================
    private static CampaignSnapshot sampleSnapshot(String version, String checksum, List<Campaign> campaigns) {
        return new CampaignSnapshot(version, checksum, campaigns);
    }

    private static Campaign activeCampaign(String campaignId, int width, int height) {
        return new Campaign(
                campaignId,
                true,
                1_000,
                STARTS_AT,
                ENDS_AT,
                List.of(new Creative("cr-" + campaignId, width, height))
        );
    }

    private static RankCampaigns rankRequest(int width, int height) {
        return new RankCampaigns(
                "auction-1",
                new Impression("imp-1", width, height, 500, 2),
                NOW
        );
    }
}
