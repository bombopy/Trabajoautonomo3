package org.example.fleet.delivery.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** HTTP contract of POST /pedidos. */
public record NewOrderRequest(
    String id,
    @JsonProperty("cliente_nombre") String clientName,
    @JsonProperty("cliente_msisdn") String clientMsisdn,
    @JsonProperty("cliente_fcm_id") String clientFcmId,
    @JsonProperty("direccion_texto") String deliveryAddress,
    @JsonProperty("lat_destino") Double destinationLatitude,
    @JsonProperty("lon_destino") Double destinationLongitude,
    @JsonProperty("radio_llegada_m") Integer arrivalRadiusMetres,
    @JsonProperty("repartidor_device_id") String courierDeviceId
) {}
