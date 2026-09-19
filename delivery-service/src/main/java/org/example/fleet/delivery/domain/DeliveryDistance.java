package org.example.fleet.delivery.domain;

/** Great-circle distance in metres, using the Haversine formula. */
public final class DeliveryDistance {
    private static final double EARTH_RADIUS_METRES = 6_371_000d;

    private DeliveryDistance() {
    }

    public static double metres(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double startLatitude = Math.toRadians(latitudeA);
        double endLatitude = Math.toRadians(latitudeB);

        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
            + Math.cos(startLatitude) * Math.cos(endLatitude)
            * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return EARTH_RADIUS_METRES * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
