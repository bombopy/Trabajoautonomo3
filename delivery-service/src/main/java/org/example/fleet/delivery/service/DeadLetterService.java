package org.example.fleet.delivery.service;

import org.apache.camel.Exchange;
import org.example.fleet.delivery.repository.OrderRepository;
import org.example.fleet.model.OrderEvent;
import org.example.fleet.model.VehiclePosition;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates the standard error envelope and persists the same audit record. */
public class DeadLetterService {
    private final OrderRepository repository;

    public DeadLetterService(OrderRepository repository) {
        this.repository = repository;
    }

    public void sendToDeadLetter(Exchange exchange) {
        Exception caught = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        String reason = exchange.getProperty("delivery.failure.reason", String.class);
        if (reason == null || reason.isBlank()) {
            reason = caught == null ? "ERROR_DE_INTEGRACION" : normalize(caught.getMessage());
        }
        Object original = exchange.getProperty("delivery.original.payload");
        if (original == null) {
            original = exchange.getMessage().getBody();
        }
        String correlationId = correlationId(original);
        repository.recordIntegrationError(reason, correlationId, original);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("motivo", reason);
        envelope.put("correlation_id", correlationId);
        envelope.put("payload_original", original);
        envelope.put("timestamp", Instant.now().toString());
        exchange.getMessage().setBody(envelope);
    }

    private String normalize(String message) {
        return message == null || message.isBlank() ? "ERROR_DE_INTEGRACION" : message;
    }

    private String correlationId(Object payload) {
        if (payload instanceof VehiclePosition position) {
            return position.messageId();
        }
        if (payload instanceof OrderEvent event) {
            return event.messageId();
        }
        return null;
    }
}
