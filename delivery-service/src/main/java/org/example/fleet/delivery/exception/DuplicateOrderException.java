package org.example.fleet.delivery.exception;

public class DuplicateOrderException extends RuntimeException {
    public DuplicateOrderException(String orderId) {
        super("PEDIDO_DUPLICADO: " + orderId);
    }
}
