package org.example.fleet.delivery.exception;

public class NoActiveOrderException extends RuntimeException {
    public NoActiveOrderException(String deviceId) {
        super("REPARTIDOR_SIN_PEDIDO_ACTIVO: " + deviceId);
    }
}
