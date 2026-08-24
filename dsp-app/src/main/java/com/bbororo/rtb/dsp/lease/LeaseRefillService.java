package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseInstallResult.ALREADY_INSTALLED;
import static com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseInstallResult.INSTALLED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejection.LOCAL_INSTALL_REJECTED;

import com.bbororo.rtb.dsp.spending.api.LeaseInstaller;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejected;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefilled;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** 원장에 멱등 발급된 리스를 같은 DSP 인스턴스의 로컬 권한에 설치한다. */
public final class LeaseRefillService implements LeaseRefill {

    private final RegionalBudgetLedger ledger;
    private final LeaseInstaller localBudget;
    private final LongSupplier monotonicNanos;

    public LeaseRefillService(RegionalBudgetLedger ledger, LeaseInstaller localBudget) {
        this(ledger, localBudget, System::nanoTime);
    }

    LeaseRefillService(
            RegionalBudgetLedger ledger,
            LeaseInstaller localBudget,
            LongSupplier monotonicNanos
    ) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.localBudget = Objects.requireNonNull(localBudget, "localBudget");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    @Override
    public CompletionStage<LeaseRefillResult> refill(RefillLease command) {
        Objects.requireNonNull(command, "command");
        long requestStartedNanos = monotonicNanos.getAsLong();
        return ledger.issue(command).thenApply(
                result -> installIfGranted(result, requestStartedNanos)
        );
    }

    private LeaseRefillResult installIfGranted(
            LeaseRefillResult result,
            long requestStartedNanos
    ) {
        if (!(result instanceof LeaseRefilled refilled)) {
            return result;
        }
        var installed = localBudget.install(refilled.lease(), requestStartedNanos);
        if (installed == INSTALLED || installed == ALREADY_INSTALLED) {
            return refilled;
        }
        return new LeaseRefillRejected(LOCAL_INSTALL_REJECTED);
    }
}
