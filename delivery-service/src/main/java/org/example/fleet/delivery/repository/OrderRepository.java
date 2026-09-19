package org.example.fleet.delivery.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fleet.delivery.domain.DeliveryDistance;
import org.example.fleet.delivery.domain.DeliveryLifecyclePolicy;
import org.example.fleet.delivery.exception.DuplicateOrderException;
import org.example.fleet.delivery.exception.NoActiveOrderException;
import org.example.fleet.delivery.exception.UnknownCourierException;
import org.example.fleet.delivery.model.NewOrderRequest;
import org.example.fleet.model.OrderEvent;
import org.example.fleet.model.VehiclePosition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional PostgreSQL adapter.  The row lock on the active order serializes
 * concurrent GPS messages for one courier; the unique milestone key is the
 * durable Idempotent Receiver key.
 */
public class OrderRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource dataSource;
    private final DeliveryLifecyclePolicy lifecyclePolicy;

    public OrderRepository(DataSource dataSource, DeliveryLifecyclePolicy lifecyclePolicy) {
        this.dataSource = dataSource;
        this.lifecyclePolicy = lifecyclePolicy;
    }

    public String createOrder(NewOrderRequest request) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!courierExists(connection, request.courierDeviceId())) {
                    throw new UnknownCourierException(request.courierDeviceId());
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pedidos (
                        id, cliente_nombre, cliente_msisdn, cliente_fcm_id, direccion_texto,
                        lat_destino, lon_destino, radio_llegada_m, repartidor_device_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                    statement.setString(1, request.id());
                    statement.setString(2, request.clientName());
                    statement.setString(3, request.clientMsisdn());
                    statement.setString(4, request.clientFcmId());
                    statement.setString(5, request.deliveryAddress());
                    statement.setDouble(6, request.destinationLatitude());
                    statement.setDouble(7, request.destinationLongitude());
                    statement.setInt(8, request.arrivalRadiusMetres());
                    statement.setString(9, request.courierDeviceId());
                    statement.executeUpdate();
                }
                connection.commit();
                return Instant.now().toString();
            } catch (SQLException exception) {
                rollback(connection);
                if ("23505".equals(exception.getSQLState())) {
                    throw new DuplicateOrderException(request.id());
                }
                throw exception;
            } catch (RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo persistir el pedido", exception);
        }
    }

    public Optional<OrderEvent> processPosition(VehiclePosition position) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ActiveOrder order = findActiveOrderForUpdate(connection, position.deviceId())
                    .orElseThrow(() -> new NoActiveOrderException(position.deviceId()));
                double distance = DeliveryDistance.metres(
                    position.latitude(), position.longitude(), order.destinationLatitude(), order.destinationLongitude());
                upsertLastPosition(connection, order.id(), position, distance);

                Optional<String> nextState = lifecyclePolicy.nextState(
                    order.state(), distance, order.arrivalRadiusMetres(), position.speedKmh(), hasMotion(position));
                Optional<OrderEvent> event = Optional.empty();
                if (nextState.isPresent()) {
                    event = transitionOrder(connection, order, nextState.get(), position, distance);
                }
                connection.commit();
                return event;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo procesar la posición GPS", exception);
        }
    }

    public Map<String, Object> findTracking(String orderId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT p.id, p.estado, u.lat, u.lon, u.velocidad_kmh,
                        u.distancia_destino_m, u.timestamp
                 FROM pedidos p
                 LEFT JOIN pedido_ultima_posicion u ON u.pedido_id = p.id
                 WHERE p.id = ?
                 """)) {
            statement.setString(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Map<String, Object> tracking = new LinkedHashMap<>();
                tracking.put("pedido_id", result.getString("id"));
                tracking.put("estado", result.getString("estado"));
                if (result.getObject("lat") == null) {
                    tracking.put("posicion", null);
                } else {
                    Map<String, Object> location = new LinkedHashMap<>();
                    location.put("lat", result.getDouble("lat"));
                    location.put("lon", result.getDouble("lon"));
                    location.put("velocidad_kmh", result.getDouble("velocidad_kmh"));
                    location.put("distancia_destino_m", result.getDouble("distancia_destino_m"));
                    location.put("timestamp", String.valueOf(result.getObject("timestamp")));
                    tracking.put("posicion", location);
                }
                return tracking;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo obtener el seguimiento", exception);
        }
    }

    public boolean registerPushNotification(OrderEvent event) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PushTarget target = findPushTarget(connection, event.pedidoId());
                if (target == null) {
                    throw new IllegalArgumentException("PEDIDO_INEXISTENTE: " + event.pedidoId());
                }
                String payload = pushPayload(event, target);
                boolean inserted;
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO notificaciones_push (pedido_id, hito, correlation_id, payload)
                    VALUES (?, ?, ?, ?::jsonb)
                    ON CONFLICT (pedido_id, hito) DO NOTHING
                    """)) {
                    statement.setString(1, event.pedidoId());
                    statement.setString(2, event.estadoNuevo());
                    statement.setString(3, event.messageId());
                    statement.setString(4, payload);
                    inserted = statement.executeUpdate() == 1;
                }
                if (inserted) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE pedido_eventos SET notificado = TRUE
                        WHERE pedido_id = ? AND hito = ?
                        """)) {
                        statement.setString(1, event.pedidoId());
                        statement.setString(2, event.estadoNuevo());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return inserted;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo registrar la notificación PUSH", exception);
        }
    }

    public void recordIntegrationError(String reason, String correlationId, Object originalPayload) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO errores_integracion (motivo, correlation_id, payload_original)
                 VALUES (?, ?, ?::jsonb)
                 """)) {
            statement.setString(1, reason);
            statement.setString(2, correlationId);
            statement.setString(3, asJson(originalPayload));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo registrar el error de integración", exception);
        }
    }

    private boolean courierExists(Connection connection, String deviceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM repartidores WHERE device_id = ?")) {
            statement.setString(1, deviceId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private Optional<ActiveOrder> findActiveOrderForUpdate(Connection connection, String deviceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, estado, lat_destino, lon_destino, radio_llegada_m
            FROM pedidos
            WHERE repartidor_device_id = ? AND estado NOT IN ('ENTREGADO', 'CANCELADO')
            ORDER BY fecha_creacion DESC
            LIMIT 1 FOR UPDATE
            """)) {
            statement.setString(1, deviceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ActiveOrder(
                    result.getString("id"), result.getString("estado"),
                    result.getDouble("lat_destino"), result.getDouble("lon_destino"),
                    result.getInt("radio_llegada_m")));
            }
        }
    }

    private void upsertLastPosition(
        Connection connection, String orderId, VehiclePosition position, double distance
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO pedido_ultima_posicion (
                pedido_id, device_id, lat, lon, velocidad_kmh, distancia_destino_m, timestamp
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (pedido_id) DO UPDATE SET
                device_id = EXCLUDED.device_id,
                lat = EXCLUDED.lat,
                lon = EXCLUDED.lon,
                velocidad_kmh = EXCLUDED.velocidad_kmh,
                distancia_destino_m = EXCLUDED.distancia_destino_m,
                timestamp = EXCLUDED.timestamp
            """)) {
            statement.setString(1, orderId);
            statement.setString(2, position.deviceId());
            statement.setDouble(3, position.latitude());
            statement.setDouble(4, position.longitude());
            statement.setDouble(5, position.speedKmh());
            statement.setDouble(6, distance);
            // PostgreSQL JDBC supports OffsetDateTime explicitly for TIMESTAMPTZ.
            statement.setObject(7, OffsetDateTime.ofInstant(Instant.parse(position.timestamp()), ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private Optional<OrderEvent> transitionOrder(
        Connection connection, ActiveOrder order, String nextState, VehiclePosition position, double distance
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE pedidos SET estado = ?, fecha_actualizacion = now()
            WHERE id = ? AND estado = ?
            """)) {
            statement.setString(1, nextState);
            statement.setString(2, order.id());
            statement.setString(3, order.state());
            if (statement.executeUpdate() != 1) {
                return Optional.empty();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO pedido_eventos (pedido_id, hito, detalle)
            VALUES (?, ?, ?::jsonb)
            ON CONFLICT (pedido_id, hito) DO NOTHING
            """)) {
            statement.setString(1, order.id());
            statement.setString(2, nextState);
            statement.setString(3, asJson(Map.of(
                "device_id", position.deviceId(),
                "source_message_id", position.messageId(),
                "distancia_destino_m", distance,
                "velocidad_kmh", position.speedKmh())));
            if (statement.executeUpdate() != 1) {
                return Optional.empty();
            }
        }
        return Optional.of(new OrderEvent(
            "1.0", UUID.randomUUID().toString(), "pedido.estado-cambiado", order.id(), position.deviceId(),
            order.state(), nextState, distance, position.timestamp()));
    }

    private PushTarget findPushTarget(Connection connection, String orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT cliente_fcm_id, cliente_msisdn FROM pedidos WHERE id = ?
            """)) {
            statement.setString(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                    ? new PushTarget(result.getString("cliente_fcm_id"), result.getString("cliente_msisdn"))
                    : null;
            }
        }
    }

    private String pushPayload(OrderEvent event, PushTarget target) {
        String title = switch (event.estadoNuevo()) {
            case "EN_CAMINO" -> "Tu pedido está en camino";
            case "CERCA" -> "Tu pedido está por llegar";
            case "ENTREGADO" -> "Tu pedido fue entregado";
            default -> "Actualización de tu pedido";
        };
        return asJson(Map.of(
            "TopicArn", "arn:aws:sns:us-east-1:000000000000:notificaciones-push",
            "Subject", title,
            "Message", Map.of(
                "pedido_id", event.pedidoId(), "hito", event.estadoNuevo(), "titulo", title,
                "destino_fcm_id", target.fcmId() == null ? "" : target.fcmId()),
            "MessageAttributes", Map.of(
                "canal", "PUSH", "pedido_id", event.pedidoId(), "hito", event.estadoNuevo(),
                "msisdn", target.msisdn())));
    }

    private boolean hasMotion(VehiclePosition position) {
        if (position.attributes() == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(position.attributes().get("motion")))
            || Boolean.parseBoolean(String.valueOf(position.attributes().get("ignition")));
    }

    private String asJson(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            return "{\"unserializable\":true}";
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original exception carries the actionable error.
        }
    }

    private record ActiveOrder(
        String id, String state, double destinationLatitude, double destinationLongitude, int arrivalRadiusMetres
    ) {}

    private record PushTarget(String fcmId, String msisdn) {}
}
