package org.example.fleet.broker.translate;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.fleet.model.VehiclePosition;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Translator from Traccar position JSON to canonical VehiclePosition record.
 * EIP Pattern: Message Translator — converts from Traccar's wire format to internal canonical model.
 */
public class TraccarPositionTranslator {

    public VehiclePosition translate(JsonNode traccarJson) throws Exception {
        JsonNode position = traccarJson.get("position");
        String deviceId = externalDeviceId(traccarJson, position);
        double latitude = position.get("latitude").asDouble();
        double longitude = position.get("longitude").asDouble();
        double speedKnots = position.get("speed").asDouble(0);
        double speedKmh = speedKnots * 1.852; // conversion: knots to km/h
        double course = position.get("course").asDouble(0);
        boolean valid = position.get("valid").asBoolean(true);
        JsonNode fixTime = position.get("fixTime");
        long fixTimeMs = fixTime == null ? 0 : fixTime.asLong();
        String timestamp = fixTimeMs > 0 ? Instant.ofEpochMilli(fixTimeMs).toString()
            : fixTime != null && !fixTime.isNull() ? fixTime.asText() : Instant.now().toString();

        // Passthrough attributes (battery, ignition, motion, etc.)
        Map<String, Object> attributes = new HashMap<>();
        JsonNode attrsNode = position.get("attributes");
        if (attrsNode != null && attrsNode.isObject()) {
            attrsNode.fields().forEachRemaining(entry ->
                attributes.put(entry.getKey(), entry.getValue().asText())
            );
        }

        return new VehiclePosition(
            "1.0",
            position.hasNonNull("id") ? "traccar-position-" + position.get("id").asText() : UUID.randomUUID().toString(),
            deviceId,
            timestamp,
            latitude,
            longitude,
            speedKmh,
            course,
            valid,
            attributes
        );
    }

    /**
     * Traccar forwards the complete device object in normal JSON-forward mode.
     * Its internal numeric deviceId is not stable across environments, so the
     * delivery domain always prefers the configured uniqueId.
     */
    private String externalDeviceId(JsonNode payload, JsonNode position) {
        JsonNode device = payload.path("device");
        if (device.hasNonNull("uniqueId") && !device.get("uniqueId").asText().isBlank()) {
            return device.get("uniqueId").asText();
        }
        if (position.hasNonNull("deviceId")) {
            return position.get("deviceId").asText();
        }
        throw new IllegalArgumentException("POSICION_SIN_DEVICE_ID");
    }
}
