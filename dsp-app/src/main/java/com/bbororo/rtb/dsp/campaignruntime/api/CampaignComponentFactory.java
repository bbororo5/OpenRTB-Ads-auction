package com.bbororo.rtb.dsp.campaignruntime.api;

import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.SnapshotInstallResult;
import com.bbororo.rtb.dsp.campaignruntime.internal.DefaultCampaignRuntime;
import com.bbororo.rtb.dsp.campaignruntime.internal.JsonFileCampaignSnapshotSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.ToLongBiFunction;

/** 검증된 배포 파일을 로드해 캠페인 Hot Path 포트를 조립한다. */
public final class CampaignComponentFactory {

    private CampaignComponentFactory() {
    }

    public static Components createFromJsonFile(
            Path path,
            String requiredVersion,
            String expectedSha256,
            ToLongBiFunction<String, Instant> pacingLagPpm
    ) {
        Objects.requireNonNull(pacingLagPpm, "pacingLagPpm");
        var runtime = new DefaultCampaignRuntime(pacingLagPpm::applyAsLong);
        var snapshot = load(path, requiredVersion, expectedSha256);
        install(runtime, snapshot);
        List<String> activeCampaignIds = snapshot.campaigns().stream()
                .filter(CampaignRuntimeMessages.Campaign::active)
                .map(CampaignRuntimeMessages.Campaign::id)
                .sorted()
                .toList();
        return new Components(runtime, runtime, activeCampaignIds);
    }

    public static SnapshotInstallResult installFromJsonFile(
            CampaignSnapshotInstaller installer,
            Path path,
            String requiredVersion,
            String expectedSha256
    ) {
        Objects.requireNonNull(installer, "installer");
        return install(installer, load(path, requiredVersion, expectedSha256));
    }

    private static CampaignRuntimeMessages.CampaignSnapshot load(
            Path path,
            String requiredVersion,
            String expectedSha256
    ) {
        try {
            return new JsonFileCampaignSnapshotSource(path, expectedSha256)
                    .load(requiredVersion)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException("campaign snapshot load failed", cause);
        }
    }

    private static SnapshotInstallResult install(
            CampaignSnapshotInstaller installer,
            CampaignRuntimeMessages.CampaignSnapshot snapshot
    ) {
        SnapshotInstallResult result = installer.install(snapshot);
        if (result == SnapshotInstallResult.CHECKSUM_MISMATCH
                || result == SnapshotInstallResult.VERSION_CONFLICT) {
            throw new IllegalStateException("campaign snapshot install rejected: " + result);
        }
        return result;
    }

    public record Components(
            CampaignCandidateSource candidates,
            CampaignSnapshotInstaller installer,
            List<String> activeCampaignIds
    ) {
        public Components {
            Objects.requireNonNull(candidates, "candidates");
            Objects.requireNonNull(installer, "installer");
            activeCampaignIds = List.copyOf(
                    Objects.requireNonNull(activeCampaignIds, "activeCampaignIds"));
        }
    }
}
