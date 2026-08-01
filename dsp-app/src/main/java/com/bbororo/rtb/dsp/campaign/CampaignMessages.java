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

/** 캠페인 적재와 슬롯별 후보 순위에 사용하는 메시지다. */
public final class CampaignMessages {

    private CampaignMessages() {
    }

    public record RankCampaigns(String auctionId, Impression impression, Instant evaluatedAt) {
        public RankCampaigns {
            auctionId = requireNonBlank(auctionId, "auctionId");
            Objects.requireNonNull(impression, "impression");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        }
    }

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

        /** 0.001 KRW CPM 한 단위는 노출 1건당 0.000001 KRW 한 단위와 수치가 같다. */
        public long impressionAmountMicros() {
            return cpmMilliKrw;
        }
    }

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

    public record Creative(String id, int width, int height) {
        public Creative {
            id = requireNonBlank(id, "id");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be positive");
            }
        }
    }

    public enum SnapshotInstallResult {
        INSTALLED,
        ALREADY_INSTALLED,
        VERSION_CONFLICT,
        CHECKSUM_MISMATCH
    }
}
