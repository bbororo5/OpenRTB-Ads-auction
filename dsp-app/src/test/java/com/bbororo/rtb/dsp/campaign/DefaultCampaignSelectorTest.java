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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("캠페인 선택기(CampaignSelector) 스냅샷 적재 및 역색인 구축 테스트")
class DefaultCampaignSelectorTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Instant STARTS_AT = NOW.minusSeconds(3600);
    private static final Instant ENDS_AT = NOW.plusSeconds(3600);

    private DefaultCampaignSelector selector;

    @BeforeEach
    void setUp() {
        selector = new DefaultCampaignSelector();
    }

    // =========================================================================
    // 1. 비압축형 세부 단위 테스트 (Granular / Decompressed Tests)
    // - 특징: 입력 공간의 각 파티션을 1:1로 격리하여 개별 메서드로 검증
    // - 장점: 실패 시 정확히 어떤 조건/경계가 깨졌는지 즉시 파악 가능
    // - 단점: 테스트 메서드 수가 많고 중복된 setup 코드가 증가함
    // =========================================================================
    @Nested
    @DisplayName("1. 비압축형 세부 단위 테스트 (개별 입력 파티션 1:1 검증)")
    class GranularTests {

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
    }

    // =========================================================================
    // 2. 실무 압축형 고밀도 테스트 (High-Density / Consolidated Tests)
    // - 특징: 상태 전이의 시간 흐름과 복합 도메인 시나리오를 묶어 테스트 수 최소화
    // - 장점: 코드 라인 수가 대폭 감소하고, 상태 전이의 연쇄 작용(Side-effect)까지 한 번에 검증
    // - 단점: 중간 단계 실패 시 실패 원인 분석을 위해 디버깅 로그를 확인해야 함
    // =========================================================================
    @Nested
    @DisplayName("2. 실무 압축형 고밀도 테스트 (라이프사이클 & 복합 시나리오 체인)")
    class HighDensityTests {

        @Test
        @DisplayName("생명주기 통합 체인: [설치 -> 멱등 -> 변조 거부 -> 업그레이드 -> 롤백 거절 및 상태보존] 전 과정을 단일 체인으로 검증한다")
        void snapshotLifecycleTransitionsAndPreservesState() {
            var v1Original = sampleSnapshot("v1", "chk-1", List.of(activeCampaign("camp-v1", 300, 250)));
            var v1Tampered = sampleSnapshot("v1", "chk-TAMPERED", List.of(activeCampaign("camp-v1", 300, 250)));
            var v2Original = sampleSnapshot("v2", "chk-2", List.of(activeCampaign("camp-v2", 300, 250)));

            // 1. 최초 설치 -> INSTALLED
            assertEquals(SnapshotInstallResult.INSTALLED, selector.install(v1Original));

            // 2. 동일 버전 재설치 -> ALREADY_INSTALLED (멱등성)
            assertEquals(SnapshotInstallResult.ALREADY_INSTALLED, selector.install(v1Original));

            // 3. 동일 버전 체크섬 변조 -> CHECKSUM_MISMATCH (오염 방어)
            assertEquals(SnapshotInstallResult.CHECKSUM_MISMATCH, selector.install(v1Tampered));

            // 4. 정상 상위 버전 업그레이드 -> INSTALLED
            assertEquals(SnapshotInstallResult.INSTALLED, selector.install(v2Original));

            // 5. 과거 버전 롤백 시도 -> VERSION_CONFLICT (롤백 방어)
            assertEquals(SnapshotInstallResult.VERSION_CONFLICT, selector.install(v1Original));

            // 6. [불변 보존 오라클] 거절 후에도 메모리에 v2 스냅샷(camp-v2)이 온전히 살아있는지 최종 검증!
            List<CampaignCandidate> candidates = selector.rankCandidates(rankRequest(300, 250));
            assertEquals(1, candidates.size());
            assertEquals("camp-v2", candidates.get(0).campaignId());
        }

        @Test
        @DisplayName("역색인 복합 빌드: [다중 규격 등록, 버킷 누적, 비활성 배제]를 단 1개의 복합 스냅샷으로 동시에 검증한다")
        void invertedIndexConsolidatedVerification() {
            // 복합 시나리오 구성:
            // - campA (활성): 300x250, 728x90 (다중 규격)
            // - campB (활성): 300x250 (버킷 누적 대상)
            // - campC (비활성): 300x250 (배제 대상)
            var campA = new Campaign(
                    "camp-A",
                    true,
                    1_000,
                    STARTS_AT,
                    ENDS_AT,
                    List.of(new Creative("cr-A1", 300, 250), new Creative("cr-A2", 728, 90))
            );
            var campB = activeCampaign("camp-B", 300, 250);
            var campC = new Campaign(
                    "camp-C",
                    false,
                    3_000,
                    STARTS_AT,
                    ENDS_AT,
                    List.of(new Creative("cr-C", 300, 250))
            );

            selector.install(sampleSnapshot("v1", "chk-1", List.of(campA, campB, campC)));

            // 1. 300x250 버킷 검증: campA와 campB만 들어있고, 비활성 campC는 완전히 빠져있어야 함
            List<CampaignCandidate> bannerCandidates = selector.rankCandidates(rankRequest(300, 250));
            assertEquals(2, bannerCandidates.size());
            List<String> bannerCampIds = bannerCandidates.stream().map(CampaignCandidate::campaignId).toList();
            assertTrue(bannerCampIds.contains("camp-A"));
            assertTrue(bannerCampIds.contains("camp-B"));

            // 2. 728x90 버킷 검증: campA의 두 번째 소재(cr-A2)만 정확히 매핑되어 있어야 함
            List<CampaignCandidate> leaderboardCandidates = selector.rankCandidates(rankRequest(728, 90));
            assertEquals(1, leaderboardCandidates.size());
            assertEquals("cr-A2", leaderboardCandidates.get(0).creativeId());
        }

        @Test
        @DisplayName("Cold-Start 방어: 미설치 상태 조회 시 크래시 없이 빈 목록 반환")
        void coldStartReturnsEmptyList() {
            assertTrue(selector.rankCandidates(rankRequest(300, 250)).isEmpty());
        }
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
