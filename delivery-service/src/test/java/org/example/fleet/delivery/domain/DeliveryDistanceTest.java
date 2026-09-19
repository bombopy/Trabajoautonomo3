package org.example.fleet.delivery.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryDistanceTest {
    @Test
    void zeroDistanceForSameCoordinates() {
        assertEquals(0, DeliveryDistance.metres(-25.2967, -57.6359, -25.2967, -57.6359), 0.001);
    }

    @Test
    void calculatesARealisticShortDistance() {
        double metres = DeliveryDistance.metres(-25.2967, -57.6359, -25.2957, -57.6359);
        assertTrue(metres > 100 && metres < 120, "one thousandth of latitude is about 111 metres");
    }
}
