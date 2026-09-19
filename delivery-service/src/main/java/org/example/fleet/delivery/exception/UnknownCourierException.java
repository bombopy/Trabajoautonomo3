package org.example.fleet.delivery.exception;

public class UnknownCourierException extends RuntimeException {
    public UnknownCourierException(String deviceId) {
        super("REPARTIDOR_DESCONOCIDO: " + deviceId);
    }
}
