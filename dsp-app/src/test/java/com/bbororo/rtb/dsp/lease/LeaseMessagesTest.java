package com.bbororo.rtb.dsp.lease;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseSettlement;
import com.bbororo.rtb.dsp.lease.LeaseMessages.LeaseUsageSummary;
import org.junit.jupiter.api.Test;

class LeaseMessagesTest {

    @Test
    void usageAndSettlementBothPreserveLeaseFaceValue() {
        assertDoesNotThrow(() -> new LeaseUsageSummary("lease-1", 1_000, 600, 300, 100, true));
        assertDoesNotThrow(() -> new LeaseSettlement("lease-1", 1, 1_000, 600, 300, 100));

        assertThrows(
                IllegalArgumentException.class,
                () -> new LeaseUsageSummary("lease-1", 1_000, 600, 300, 99, true)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new LeaseSettlement("lease-1", 1, 1_000, 600, 300, 99)
        );
    }
}
