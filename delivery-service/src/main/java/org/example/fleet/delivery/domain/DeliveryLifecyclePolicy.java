package org.example.fleet.delivery.domain;

import java.util.Optional;

/**
 * Business rule for automatic milestones.  Delivery is intentionally confirmed
 * only by a second, slow GPS report inside the arrival radius: the first one
 * changes EN_CAMINO to CERCA and the following stopped report changes to ENTREGADO.
 */
public final class DeliveryLifecyclePolicy {
    public static final double DEPARTURE_SPEED_KMH = 1.0d;
    public static final double DELIVERY_SPEED_KMH = 3.0d;

    public Optional<String> nextState(
        String currentState,
        double distanceMetres,
        int arrivalRadiusMetres,
        double speedKmh,
        boolean motionOrIgnition
    ) {
        return switch (currentState) {
            case "RECIBIDO" -> speedKmh >= DEPARTURE_SPEED_KMH || motionOrIgnition
                ? Optional.of("EN_CAMINO") : Optional.empty();
            case "EN_CAMINO" -> distanceMetres <= arrivalRadiusMetres
                ? Optional.of("CERCA") : Optional.empty();
            case "CERCA" -> distanceMetres <= arrivalRadiusMetres && speedKmh <= DELIVERY_SPEED_KMH
                ? Optional.of("ENTREGADO") : Optional.empty();
            default -> Optional.empty();
        };
    }
}
