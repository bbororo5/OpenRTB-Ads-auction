package com.bbororo.rtb.dsp.lease;

import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.ALREADY_INSTALLED;
import static com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseInstallResult.INSTALLED;
import static com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejection.LOCAL_INSTALL_REJECTED;

import com.bbororo.rtb.dsp.budget.LocalBudgetAuthority;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillResult;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefillRejected;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseRefilled;
import com.bbororo.rtb.dsp.lease.LeaseMessages.RefillLease;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 원장에 멱등 발급된 리스를 같은 DSP 인스턴스의 로컬 권한에 설치한다. */
public final class LeaseRefillService {

    private final RegionalBudgetLedger ledger;
    private final LocalBudgetAuthority localBudget;

    public LeaseRefillService(RegionalBudgetLedger ledger, LocalBudgetAuthority localBudget) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.localBudget = Objects.requireNonNull(localBudget, "localBudget");
    }

    public CompletionStage<LeaseRefillResult> refill(RefillLease command) {
        Objects.requireNonNull(command, "command");
        return ledger.issue(command).thenApply(result -> installIfGranted(result));
    }

    private LeaseRefillResult installIfGranted(LeaseRefillResult result) {
        if (!(result instanceof LeaseRefilled refilled)) {
            return result;
        }
        var installed = localBudget.install(refilled.lease());
        if (installed == INSTALLED || installed == ALREADY_INSTALLED) {
            return refilled;
        }
        return new LeaseRefillRejected(LOCAL_INSTALL_REJECTED);
    }
}
