package org.example.fleet.delivery.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fleet.delivery.exception.OrderInputException;
import org.example.fleet.delivery.model.NewOrderRequest;
import org.example.fleet.delivery.model.OrderCreateResult;
import org.example.fleet.delivery.repository.OrderRepository;
import org.example.fleet.model.OrderEvent;
import org.example.fleet.model.VehiclePosition;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Application service used by the Camel routes. */
public class OrderService {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    public OrderCreateResult createOrder(String body) {
        NewOrderRequest request;
        try {
            request = objectMapper.readValue(body, NewOrderRequest.class);
        } catch (JsonProcessingException exception) {
            throw new OrderInputException("PAYLOAD_INVALIDO");
        }
        request = validate(request);
        String createdAt = repository.createOrder(request);
        OrderEvent event = new OrderEvent(
            "1.0", UUID.randomUUID().toString(), "pedido.recibido", request.id(), request.courierDeviceId(),
            null, "RECIBIDO", null, createdAt);
        return new OrderCreateResult(Map.of(
            "id", request.id(), "estado", "RECIBIDO", "fecha_creacion", createdAt), event);
    }

    public OrderEvent processPosition(VehiclePosition position) {
        return repository.processPosition(position).orElse(null);
    }

    public Map<String, Object> tracking(String orderId) {
        return repository.findTracking(orderId);
    }

    private NewOrderRequest validate(NewOrderRequest request) {
        if (request == null || blank(request.id()) || blank(request.clientName()) || blank(request.clientMsisdn())
            || blank(request.courierDeviceId())) {
            throw new OrderInputException("CAMPOS_REQUERIDOS_AUSENTES");
        }
        if (request.destinationLatitude() == null || request.destinationLatitude() < -90 || request.destinationLatitude() > 90) {
            throw new OrderInputException("COORDENADAS_INVALIDAS: lat_destino");
        }
        if (request.destinationLongitude() == null || request.destinationLongitude() < -180 || request.destinationLongitude() > 180) {
            throw new OrderInputException("COORDENADAS_INVALIDAS: lon_destino");
        }
        int radius = request.arrivalRadiusMetres() == null ? 150 : request.arrivalRadiusMetres();
        if (radius <= 0 || radius > 10_000) {
            throw new OrderInputException("RADIO_LLEGADA_INVALIDO");
        }
        return new NewOrderRequest(
            request.id(), request.clientName(), request.clientMsisdn(), request.clientFcmId(), request.deliveryAddress(),
            request.destinationLatitude(), request.destinationLongitude(), radius, request.courierDeviceId());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
