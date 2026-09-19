# Evidencia de pruebas — Desafío 3

Fecha de ejecución: 18 de septiembre de 2026.

Entorno verificado:

- Docker Compose con Traccar, Artemis, PostgreSQL, broker Camel, Process Manager y consumidores activos.
- Artemis saludable con AMQP en el puerto `5672`.
- Pruebas Java 21 disponibles mediante `docker compose --profile test run --rm tests`.

## Ejecución integrada

Comando ejecutado:

```powershell
.\scripts\verify-delivery.ps1
```

Pedido creado para la evidencia: `PED-VERIFY-20260918212146`.

Resultado capturado:

```text
[OK] Artemis está activo
[OK] Traccar está activo
[OK] Delivery Process Manager está activo
[OK] 1. El pedido entra como RECIBIDO
[OK] 1. El pedido queda persistido
[OK] 3. EN_CAMINO genera un único PUSH
[OK] 4. CERCA genera un único PUSH
[OK] 2/10. Tracking conserva última posición y distancia
[OK] 5. ENTREGADO queda en la bitácora
[OK] 5. ENTREGADO genera el PUSH final
[OK] 6. Reproceso no duplica el PUSH
[OK] 7. Posición sin pedido activo se deriva a DLQ sin detener el flujo
[OK] 8. Repartidor desconocido es rechazado con 422
[OK] 9. Evento de pedido inexistente llega a DLQ
[OK] Payload inválido es rechazado con 400

Todas las verificaciones del Desafío 3 finalizaron correctamente.
```

El camino feliz pasó por Traccar con el protocolo OsmAnd (`5055`), luego por el broker Camel, el tópico AMQP `vehicle.positions`, el Process Manager y PostgreSQL. El replay y el evento de pedido inexistente se enviaron por `POST /eventos/pedido`, que publica el `OrderEvent` canónico al tópico AMQP `delivery.orders`.

## Consultas de respaldo

```powershell
docker compose exec postgres psql -U delivery -d delivery -c "SELECT hito, COUNT(*) FROM notificaciones_push WHERE pedido_id = 'PED-VERIFY-20260918212146' GROUP BY hito ORDER BY hito;"
docker compose exec postgres psql -U delivery -d delivery -c "SELECT motivo, correlation_id, timestamp FROM errores_integracion ORDER BY id DESC LIMIT 10;"
```

La primera consulta debe devolver una sola fila para cada hito `EN_CAMINO`, `CERCA` y `ENTREGADO`. La segunda muestra los descartes trazables, entre ellos `REPARTIDOR_SIN_PEDIDO_ACTIVO`, `REPARTIDOR_DESCONOCIDO`, `PEDIDO_INEXISTENTE` y `CAMPOS_REQUERIDOS_AUSENTES`.

## Suite Java 21

Comando ejecutado:

```powershell
docker compose --profile test run --rm tests
```

Resultado observado:

```text
> Task :delivery-service:test
> Task :events-consumer:test NO-SOURCE
> Task :mediator:test NO-SOURCE
> Task :positions-consumer:test NO-SOURCE

BUILD SUCCESSFUL in 53s
14 actionable tasks: 14 executed
```

El contenedor de prueba compiló los módulos `common`, `broker`, `delivery-service`, `events-consumer`, `mediator` y `positions-consumer` con Java 21. Las pruebas de `delivery-service` se ejecutaron correctamente.
