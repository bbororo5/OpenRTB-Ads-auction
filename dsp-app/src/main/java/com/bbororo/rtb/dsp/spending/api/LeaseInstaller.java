package com.bbororo.rtb.dsp.spending.api;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseInstallResult;

/** Lease Lifecycle이 원장에서 확인한 위임 권한을 로컬 상태에 설치한다. */
public interface LeaseInstaller {

    LeaseInstallResult install(InstallLease command, long requestStartedNanos);
}
