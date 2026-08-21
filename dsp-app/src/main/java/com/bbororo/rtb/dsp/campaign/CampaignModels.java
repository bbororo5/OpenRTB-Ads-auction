package com.bbororo.rtb.dsp.campaign;

import static com.bbororo.rtb.dsp.contract.ContractChecks.immutableList;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireAfter;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requireNonBlank;
import static com.bbororo.rtb.dsp.contract.ContractChecks.requirePositive;

import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * DSP 캠페인 인메모리 타겟팅 및 후보 순위화 서브시스템에서 사용하는 핵심 불변 도메인 모델과 DTO 모음이다.
 */
public final class CampaignModels {

    private CampaignModels() {
    }

    /**
     * 특정 경매의 단일 슬롯(Impression)에 대해 적격 캠페인 후보 선별 및 순위화를 요청하는 쿼리 DTO다.
     *
     * @param auctionId    OpenRTB 경매 식별자 (공백 불가)
     * @param impression   경매 대상 광고 지면 및 슬롯 규격, 바닥가 정보 (null 불가)
     * @param evaluatedAt  실시간 적격성 판정 기준 시각 (null 불가)
     */
    public record RankCampaigns(String auctionId, Impression impression, Instant evaluatedAt) {
        public RankCampaigns {
            auctionId = requireNonBlank(auctionId, "auctionId");
            Objects.requireNonNull(impression, "impression");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        }
    }

    /**
     * 슬롯 규격, 유효 기간, 바닥가 필터링을 통과하고 페이싱 지연율이 매겨진 최종 입찰 후보 모델이다.
     *
     * @param campaignId    후보 캠페인 식별자
     * @param creativeId    노출할 광고 소재 식별자
     * @param cpmMilliKrw   입찰 CPM 단가 (1 CPM = 0.001 KRW, 양수)
     * @param pacingLagPpm  목표 예산 소진 대비 지연율 (PPM 단위, 양수일수록 목표 대비 소진 지연)
     */
    public record CampaignCandidate(
            String campaignId,
            String creativeId,
            long cpmMilliKrw,
            long pacingLagPpm
    ) {
        public CampaignCandidate {
            campaignId = requireNonBlank(campaignId, "campaignId");
            creativeId = requireNonBlank(creativeId, "creativeId");
            requirePositive(cpmMilliKrw, "cpmMilliKrw");
        }

        /** 0.001 KRW CPM 한 단위는 노출 1건당 0.000001 KRW(마이크로원) 한 단위와 수치가 같다. */
        public long impressionAmountMicros() {
            return cpmMilliKrw;
        }
    }

    /**
     * 외부 제어 평면(Control-Plane)에서 발행하여 DSP 인스턴스에 배포되는 불변 캠페인 스냅샷이다.
     *
     * @param version    스냅샷 버전 식별자 (단조 증가 자연수/시맨틱 버전)
     * @param checksum   스냅샷 무결성 검증용 SHA-256 체크섬
     * @param campaigns  전체 캠페인 목록 (캠페인 ID 중복 불가)
     */
    public record CampaignSnapshot(String version, String checksum, List<Campaign> campaigns) {
        public CampaignSnapshot {
            version = requireNonBlank(version, "version");
            checksum = requireNonBlank(checksum, "checksum");
            campaigns = immutableList(campaigns, "campaigns");
            var ids = new HashSet<String>();
            for (Campaign campaign : campaigns) {
                if (!ids.add(campaign.id())) {
                    throw new IllegalArgumentException("campaigns must not repeat an id");
                }
            }
        }
    }

    /**
     * 광고주가 집행하는 개별 캠페인 정보 모델이다.
     *
     * @param id              캠페인 고유 식별자
     * @param active          활성화 여부 (false일 경우 인메모리 역색인에서 사전 제외)
     * @param bidCpmMilliKrw  기본 입찰 CPM 단가 (0.001 KRW 단위, 양수)
     * @param startsAt        캠페인 집행 시작 시각 (포함)
     * @param endsAt          캠페인 집행 종료 시각 (반열린 구간, startsAt보다 미래 시점)
     * @param creatives       캠페인에 등록된 광고 소재 목록 (최소 1개 이상)
     */
    public record Campaign(
            String id,
            boolean active,
            long bidCpmMilliKrw,
            Instant startsAt,
            Instant endsAt,
            List<Creative> creatives
    ) {
        public Campaign {
            id = requireNonBlank(id, "id");
            requirePositive(bidCpmMilliKrw, "bidCpmMilliKrw");
            requireAfter(startsAt, endsAt, "endsAt");
            creatives = immutableList(creatives, "creatives");
            if (creatives.isEmpty()) {
                throw new IllegalArgumentException("creatives must not be empty");
            }
        }
    }

    /**
     * 캠페인에 속한 실제 배너/소재의 규격 정보 모델이다.
     *
     * @param id      소재 고유 식별자
     * @param width   가로 픽셀 너비 (양수)
     * @param height  세로 픽셀 높이 (양수)
     */
    public record Creative(String id, int width, int height) {
        public Creative {
            id = requireNonBlank(id, "id");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be positive");
            }
        }
    }

    /** 스냅샷 설치 시 이전 버전 및 체크섬 상태 전이 결과다. */
    public enum SnapshotInstallResult {
        /** 새 상위 버전의 스냅샷이 정상적으로 인메모리 역색인으로 적재됨. */
        INSTALLED,
        /** 동일한 버전과 동일한 체크섬의 스냅샷이 이미 적재되어 있어 설치를 건너뜀 (멱등). */
        ALREADY_INSTALLED,
        /** 현재 적재된 버전보다 하위 버전이 인입되어 롤백 충돌로 설치 거절됨. */
        VERSION_CONFLICT,
        /** 동일 버전이지만 체크섬이 달라 스냅샷 데이터 위변조/오염으로 감지되어 설치 거절됨. */
        CHECKSUM_MISMATCH
    }
}
