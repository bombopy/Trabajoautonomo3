package org.example.fleet.model;

/**
 * Canonical event emitted by the delivery process manager.  The same envelope is
 * used on the business-event channel by the API, GPS processor and PUSH adapter.
 */
public record OrderEvent(
    String schemaVersion,
    String messageId,
    String tipo,
    String pedidoId,
    String deviceId,
    String estadoAnterior,
    String estadoNuevo,
    Double distanciaDestinoM,
    String timestamp
) {}
