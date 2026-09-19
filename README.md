# Seguimiento de pedidos delivery por GPS

Implementación del **Desafío 3** de Integración de APIs REST mediante una arquitectura orientada a eventos. Extiende la base Traccar → Camel → Artemis y agrega ciclo de vida de pedidos, PostgreSQL, seguimiento REST y un adaptador PUSH/SNS simulado.

## Solución y alcance

El pedido llega con repartidor asignado; su `repartidor_device_id` coincide con el `uniqueId` de Traccar. Las coordenadas provienen de un dispositivo real de Traccar mediante el protocolo OsmAnd, son normalizadas por Camel a `VehiclePosition` y publicadas al bus. El Process Manager correlaciona posición, repartidor y pedido activo, persiste el seguimiento y publica cada cambio como `OrderEvent`.

```text
Sistema de pedidos ──POST /pedidos──> delivery-service ──topic delivery.orders──┐
                                      │ PostgreSQL                              │
Traccar/OsmAnd ─> broker Camel ─topic vehicle.positions──> Process Manager ─────┤
       (GPS real)        (Artemis)          │                                    │
                                             └──queue delivery.dlq                v
                                                                   PUSH simulado / notificaciones_push

Cliente móvil <──GET /pedidos/{id}/tracking── delivery-service
```

Se eligió **orquestación**: `delivery-service` es el Process Manager dueño de `RECIBIDO → EN_CAMINO → CERCA → ENTREGADO`. Centraliza las transiciones, la posición y la bitácora dentro de una transacción PostgreSQL. La notificación se resuelve por coreografía: un suscriptor durable reacciona a `delivery.orders`, sin acoplar el Process Manager con el proveedor PUSH.

No se implementa ABM de repartidores ni asignación: `database/02-seed.sql` precarga los repartidores y el pedido es el único disparador de negocio, tal como pide el desafío.

## Stack y ejecución reproducible

- Java 21 y Gradle 9.6.0 (wrapper incluido).
- Apache Camel 4.8, ActiveMQ Artemis por AMQP 1.0, Traccar y PostgreSQL 16.
- Docker Compose para todo el entorno y para ejecutar pruebas Java 21 sin instalar un JDK local.

La entrega selecciona Java/Gradle y Docker como modalidad de ejecución (`Java, JBang y/o Docker`): Docker contiene el JDK 21, Gradle y los servicios. Traccar utiliza H2 efímero solo para la demostración; PostgreSQL es la fuente de verdad de pedidos, seguimiento, eventos, notificaciones y errores.

## Canales y contratos

| Canal Artemis | Tipo | Productor → consumidor | Responsabilidad |
|---|---|---|---|
| `vehicle.positions` | Topic | Broker Camel → Process Manager | Posición canónica `VehiclePosition`. |
| `vehicle.events` | Topic | Broker Camel → consumidores existentes | Evento normalizado de Traccar. |
| `delivery.orders` | Topic | API/Process Manager → adaptador PUSH | `OrderEvent`: recepción o cambio de estado. |
| `delivery.dlq` | Queue | Rutas con fallo → operación | Sobre con motivo, correlación, payload y timestamp. |

Los contratos llevan `messageId`, `pedidoId` y `deviceId` cuando corresponda. El traductor usa `device.uniqueId` de Traccar, no su identificador numérico interno, para que coincida con `repartidores.device_id`.

## Patrones EIP aplicados

| Patrón | Dónde | Problema que resuelve |
|---|---|---|
| Messaging Gateway | `POST /pedidos`, `POST /eventos/pedido` y REST del broker | Aísla sistemas externos del bus. |
| Content-Based Router | `broker/IngestRoute` | Separa posiciones de eventos Traccar. |
| Message Translator y Canonical Data Model | Traductores Traccar, `VehiclePosition`, `OrderEvent` | El dominio no depende de JSON ni IDs internos de Traccar. |
| Publish-Subscribe Channel y Durable Subscriber | Topics Artemis | Permite incorporar consumidores sin cambiar productores. |
| Process Manager | `DeliveryPositionRoute` y `OrderRepository` | Orquesta una transición consistente por posición. |
| Correlation Identifier | `pedido_id`, `device_id`, `message_id` | Une GPS, pedido, evento, PUSH y DLQ. |
| Idempotent Receiver | `UNIQUE(pedido_id, hito)` | Suprime hitos y PUSH repetidos ante reintentos o replay. |
| Dead Letter Channel | `delivery.dlq` y `errores_integracion` | Conserva fallos procesables sin detener el flujo. |

## Reglas de negocio y persistencia

| Estado actual | Condición GPS | Estado nuevo | PUSH |
|---|---|---|---|
| `RECIBIDO` | Velocidad ≥ 1 km/h, `motion=true` o `ignition=true` | `EN_CAMINO` | Sí |
| `EN_CAMINO` | Distancia Haversine ≤ `radio_llegada_m` | `CERCA` | Sí |
| `CERCA` | Nueva posición dentro del radio y velocidad ≤ 3 km/h | `ENTREGADO` | Sí |

La segunda posición lenta dentro del radio actúa como geocerca virtual de confirmación: evita declarar una entrega con un único punto de paso. La distancia geodésica se calcula con Haversine en `DeliveryDistance`.

Solo se persiste la última posición en `pedido_ultima_posicion`; el histórico GPS de alta frecuencia se descarta como decisión explícita de capacidad. `pedido_eventos` conserva los hitos de negocio. `database/01-schema.sql` define, además, `pedidos`, `repartidores`, `notificaciones_push` y `errores_integracion`.

## PUSH/SNS simulado y entrega única

Se seleccionó un **tópico SNS lógico único**, `notificaciones-push`, con atributos `canal=PUSH`, `pedido_id`, `hito` y `msisdn`, en vez de un tópico físico por canal. La topología permite sumar en el futuro SMS o auditoría como suscriptores sin multiplicar tópicos ni acoplar el Process Manager al proveedor.

No se necesita una cuenta AWS: el adaptador durable simula `Publish` de SNS y persiste el contrato (`TopicArn`, `Message`, `MessageAttributes`) en `notificaciones_push`. Esta tabla es el outbox auditable; un cliente SNS real podría reemplazar al bean sin alterar las rutas ni `OrderEvent`.

La llave `UNIQUE(pedido_id, hito)` existe tanto en `pedido_eventos` como en `notificaciones_push`. Así, un reintento, reconexión o replay nunca crea un segundo PUSH para el mismo hito. La prueba de aceptación reenvía `ENTREGADO` y verifica que el conteo se mantiene en uno.

## Manejo de errores

- Payload de pedido inválido, repartidor desconocido y pedido duplicado devuelven respectivamente `400`, `422` y `409`, y quedan en DLQ.
- Una posición de repartidor sin pedido activo se reintenta dos veces y después se publica a `delivery.dlq` y `errores_integracion`.
- Un `OrderEvent` de pedido inexistente falla en el adaptador PUSH y sigue la misma vía de DLQ.
- Los errores conservan motivo, timestamp, payload original y, si existe, el `messageId` como correlación.
- `GET /pedidos/{id}/tracking` devuelve `404` si el pedido no existe y `posicion: null` antes del primer GPS.

## Limitaciones conocidas

- La fuente GPS es real (Traccar/OsmAnd), pero SNS/FCM se simula con un outbox persistente; una aplicación móvil real está fuera de alcance.
- Traccar usa H2 efímero en la demostración. PostgreSQL contiene todo el estado de negocio durable.
- Se conserva solamente la última posición para evitar un histórico GPS de alta frecuencia.
- Se asume un pedido activo por repartidor en el flujo de demostración; una política de despacho para múltiples pedidos por repartidor no pertenece al desafío.

## Ejecutar con Docker

1. Inicie Docker Desktop y, opcionalmente, copie variables de ejemplo:

   ```powershell
   Copy-Item .env.example .env
   ```

2. Construya e inicie el entorno:

   ```powershell
   docker compose up --build -d
   docker compose ps
   ```

   Puertos: Traccar `8082`, OsmAnd `5055`, broker `8083`, API delivery `8081`, Artemis `8161`, AMQP `5672` y PostgreSQL `5432`.

3. Ejecute el escenario breve:

   ```powershell
   .\scripts\demo-delivery.ps1
   ```

   El script crea un pedido para `repartidor-01` y manda tres posiciones por OsmAnd a Traccar. `database.registerUnknown` limita el registro automático a `repartidor-01` y `repartidor-02`; `registration.enable` corresponde al registro de usuarios de Traccar.

4. Consulte el estado y la DLQ:

   ```powershell
   docker compose logs --tail=100 delivery-service broker
   docker compose exec postgres psql -U delivery -d delivery -c "TABLE pedidos; TABLE pedido_eventos; TABLE notificaciones_push;"
   docker compose exec postgres psql -U delivery -d delivery -c "TABLE errores_integracion;"
   ```

5. Para detener el entorno:

   ```powershell
   docker compose down
   # También borra los datos de demostración:
   docker compose down -v
   ```

## API

Crear pedido:

```http
POST http://localhost:8081/pedidos
Content-Type: application/json

{
  "id": "PED-20260902-001",
  "cliente_nombre": "Juan Pérez",
  "cliente_msisdn": "+595972222222",
  "cliente_fcm_id": "fcm_token_demo",
  "direccion_texto": "Av. España 1234, Asunción",
  "lat_destino": -25.2967,
  "lon_destino": -57.6359,
  "radio_llegada_m": 150,
  "repartidor_device_id": "repartidor-01"
}
```

Respuesta `202`:

```json
{"id":"PED-20260902-001","estado":"RECIBIDO","fecha_creacion":"2026-09-02T12:05:00Z"}
```

Consultar seguimiento:

```http
GET http://localhost:8081/pedidos/PED-20260902-001/tracking
```

```json
{
  "pedido_id": "PED-20260902-001",
  "estado": "CERCA",
  "posicion": {
    "lat": -25.2971,
    "lon": -57.6362,
    "velocidad_kmh": 18.4,
    "distancia_destino_m": 120.0,
    "timestamp": "2026-09-02T12:24:30Z"
  }
}
```

Ejemplo de error:

```http
POST http://localhost:8081/pedidos
Content-Type: application/json

{"id":"PED-ERR-01","cliente_nombre":"Ana","cliente_msisdn":"+595971","lat_destino":-25.29,"lon_destino":-57.63,"repartidor_device_id":"repartidor-inexistente"}
```

Respuesta `422`:

```json
{"error":"REPARTIDOR_DESCONOCIDO"}
```

El gateway de integración `POST /eventos/pedido` acepta un `OrderEvent` canónico y lo publica en `delivery.orders`. Se usa para probar replay y pedido inexistente; el ciclo principal se origina en GPS y no por polling de base de datos.

## Pruebas y evidencia

Pruebas unitarias con Java 21 local:

```powershell
.\gradlew.bat test
```

Alternativa reproducible sin JDK local:

```powershell
docker compose --profile test run --rm tests
```

Con los servicios activos, ejecute la aceptación integral:

```powershell
docker compose restart traccar
.\scripts\verify-delivery.ps1
```

El guion comprueba los escenarios mínimos: ingreso y persistencia, GPS canónico y tracking, `EN_CAMINO`, `CERCA`, `ENTREGADO`, un PUSH por hito, replay idempotente, repartidor sin pedido activo, repartidor desconocido, pedido inexistente y payload inválido. El camino feliz viaja por Traccar/OsmAnd; los replay se validan y publican por AMQP a través del gateway REST.

La evidencia de una ejecución exitosa se conserva en [docs/evidencias.md](docs/evidencias.md).



## Estructura añadida

```text
delivery-service/           API REST, Process Manager, adaptador PUSH y DLQ
database/                   esquema PostgreSQL y seed de repartidores
scripts/demo-delivery.ps1   demo breve por GPS real
scripts/verify-delivery.ps1 aceptación de los escenarios obligatorios
common/OrderEvent.java      evento de dominio canónico
Dockerfile.tests            ejecución Gradle con Java 21 en Docker
docs/evidencias.md          salida y consultas de evidencia
```

El código base de Traccar, broker y consumidores se conserva; el traductor del broker fue reforzado para preservar el `uniqueId` externo de Traccar y su `messageId` cuando está disponible.
