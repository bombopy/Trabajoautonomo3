package org.example.fleet.delivery.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryLifecyclePolicyTest {
    private final DeliveryLifecyclePolicy policy = new DeliveryLifecyclePolicy();

    @Test
    void aMovingCourierStartsTheTrip() {
        assertEquals("EN_CAMINO", policy.nextState("RECIBIDO", 900, 150, 18, true).orElseThrow());
    }

    @Test
    void enteringTheRadiusMarksTheOrderAsNear() {
        assertEquals("CERCA", policy.nextState("EN_CAMINO", 120, 150, 16, true).orElseThrow());
    }

    @Test
    void aSecondSlowReportInsideTheRadiusConfirmsDelivery() {
        assertEquals("ENTREGADO", policy.nextState("CERCA", 30, 150, 0, false).orElseThrow());
    }

    @Test
    void aStoppedCourierOutsideTheRadiusDoesNotAdvanceTheOrder() {
        assertTrue(policy.nextState("CERCA", 240, 150, 0, false).isEmpty());
    }
}
