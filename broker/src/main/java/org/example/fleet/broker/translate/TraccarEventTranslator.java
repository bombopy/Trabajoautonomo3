package org.example.fleet.broker.translate;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.fleet.model.VehicleEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Translator from Traccar event JSON to canonical VehicleEvent record.
 * EIP Pattern: Message Translator — converts from Traccar's wire format to internal canonical model.
 */
public class TraccarEventTranslator {

    public VehicleEvent translate(JsonNode traccarJson) throws Exception {
        JsonNode event = traccarJson.get("event");

        String deviceId = externalDeviceId(traccarJson, event);
        String eventType = event.get("type").asText();
        JsonNode eventTime = event.get("eventTime");
        long eventTimeMs = eventTime == null ? 0 : eventTime.asLong();
        String timestamp = eventTimeMs > 0 ? Instant.ofEpochMilli(eventTimeMs).toString()
            : eventTime != null && !eventTime.isNull() ? eventTime.asText() : Instant.now().toString();

        String positionId = null;
        if (event.has("positionId") && !event.get("positionId").isNull()) {
            positionId = String.valueOf(event.get("positionId").asInt());
        }

        String geofenceId = null;
        if (event.has("geofenceId") && !event.get("geofenceId").isNull()) {
            geofenceId = String.valueOf(event.get("geofenceId").asInt());
        }

        // Passthrough attributes
        Map<String, Object> attributes = new HashMap<>();
        JsonNode attrsNode = event.get("attributes");
        if (attrsNode != null && attrsNode.isObject()) {
            attrsNode.fields().forEachRemaining(entry ->
                attributes.put(entry.getKey(), entry.getValue().asText())
            );
        }

        return new VehicleEvent(
            "1.0",
            event.hasNonNull("id") ? "traccar-event-" + event.get("id").asText() : UUID.randomUUID().toString(),
            deviceId,
            eventType,
            timestamp,
            positionId,
            geofenceId,
            attributes
        );
    }

    private String externalDeviceId(JsonNode payload, JsonNode event) {
        JsonNode device = payload.path("device");
        if (device.hasNonNull("uniqueId") && !device.get("uniqueId").asText().isBlank()) {
            return device.get("uniqueId").asText();
        }
        if (event.hasNonNull("deviceId")) {
            return event.get("deviceId").asText();
        }
        throw new IllegalArgumentException("EVENTO_SIN_DEVICE_ID");
    }
}
