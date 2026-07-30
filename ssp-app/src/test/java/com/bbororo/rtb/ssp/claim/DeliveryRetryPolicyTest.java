package com.bbororo.rtb.ssp.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DeliveryRetryPolicyTest {

    @Test
    void doublesDelayUntilTheConfiguredCeiling() {
        DeliveryRetryPolicy policy = new DeliveryRetryPolicy(
                Duration.ofMillis(50),
                Duration.ofMillis(500)
        );

        assertEquals(Duration.ofMillis(50), policy.delayAfter(1));
        assertEquals(Duration.ofMillis(100), policy.delayAfter(2));
        assertEquals(Duration.ofMillis(200), policy.delayAfter(3));
        assertEquals(Duration.ofMillis(400), policy.delayAfter(4));
        assertEquals(Duration.ofMillis(500), policy.delayAfter(5));
        assertEquals(Duration.ofMillis(500), policy.delayAfter(100));
    }
}
