<#
.SYNOPSIS
    Ejecuta los escenarios obligatorios de aceptación del Desafío 3.

.DESCRIPTION
    El stack Docker debe estar iniciado. El guion manda el camino feliz por
    Traccar/OsmAnd real, reprocesa un evento por Artemis para probar
    idempotencia y consulta PostgreSQL como evidencia durable. No borra datos.
#>

$ErrorActionPreference = 'Stop'
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$orderId = "PED-VERIFY-$stamp"
$unknownOrderId = "PED-NO-EXISTE-$stamp"

function Assert-That([bool]$condition, [string]$message) {
    if (-not $condition) { throw "VERIFICACION_FALLIDA: $message" }
    Write-Host "[OK] $message" -ForegroundColor Green
}

function Get-HttpStatus([scriptblock]$request) {
    try {
        & $request | Out-Null
        return 200
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

function Send-Gps([string]$deviceId, [double]$lat, [double]$lon, [int]$speed) {
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $ignition = if ($speed -gt 0) { 'true' } else { 'false' }
    $uri = "http://localhost:5055/?id=$deviceId&lat=$lat&lon=$lon&timestamp=$timestamp&speed=$speed&bearing=90&ignition=$ignition"
    Invoke-WebRequest -Uri $uri -UseBasicParsing | Out-Null
    Start-Sleep -Seconds 3
}

function SqlScalar([string]$query) {
    $result = docker compose exec -T postgres psql -U delivery -d delivery -At -c $query
    return ($result | Select-Object -Last 1).Trim()
}

function Wait-For([scriptblock]$condition, [string]$description) {
    for ($attempt = 1; $attempt -le 12; $attempt++) {
        if (& $condition) { return }
        Start-Sleep -Seconds 1
    }
    throw "Tiempo agotado: $description"
}

Write-Host 'Verificando estado del entorno...'
$running = docker compose ps --status running --services
Assert-That ($running -contains 'artemis') 'Artemis está activo'
Assert-That ($running -contains 'traccar') 'Traccar está activo'
Assert-That ($running -contains 'delivery-service') 'Delivery Process Manager está activo'

# 1. Ingreso y persistencia del pedido.
$order = @{
    id = $orderId
    cliente_nombre = 'Cliente de verificación'
    cliente_msisdn = '+595971000000'
    cliente_fcm_id = 'fcm_verificacion'
    direccion_texto = 'Av. España 1234, Asunción'
    lat_destino = -25.2967
    lon_destino = -57.6359
    radio_llegada_m = 150
    repartidor_device_id = 'repartidor-01'
} | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/pedidos' -ContentType 'application/json' -Body $order
Assert-That ($created.estado -eq 'RECIBIDO') '1. El pedido entra como RECIBIDO'
Assert-That ((SqlScalar "SELECT estado FROM pedidos WHERE id = '$orderId'") -eq 'RECIBIDO') '1. El pedido queda persistido'

# 2, 3, 4, 5 y 10. Posiciones GPS reales por Traccar/OsmAnd.
Send-Gps 'repartidor-01' -25.3000 -57.6359 25
Wait-For { (Invoke-RestMethod -Uri "http://localhost:8081/pedidos/$orderId/tracking").estado -eq 'EN_CAMINO' } 'transición EN_CAMINO'
Assert-That ((SqlScalar "SELECT COUNT(*) FROM notificaciones_push WHERE pedido_id = '$orderId' AND hito = 'EN_CAMINO'") -eq '1') '3. EN_CAMINO genera un único PUSH'

Send-Gps 'repartidor-01' -25.2974 -57.6359 15
Wait-For { (Invoke-RestMethod -Uri "http://localhost:8081/pedidos/$orderId/tracking").estado -eq 'CERCA' } 'transición CERCA'
Assert-That ((SqlScalar "SELECT COUNT(*) FROM notificaciones_push WHERE pedido_id = '$orderId' AND hito = 'CERCA'") -eq '1') '4. CERCA genera un único PUSH'

Send-Gps 'repartidor-01' -25.29675 -57.6359 0
Wait-For { (Invoke-RestMethod -Uri "http://localhost:8081/pedidos/$orderId/tracking").estado -eq 'ENTREGADO' } 'transición ENTREGADO'
$tracking = Invoke-RestMethod -Uri "http://localhost:8081/pedidos/$orderId/tracking"
Assert-That ($null -ne $tracking.posicion -and $tracking.posicion.distancia_destino_m -lt 150) '2/10. Tracking conserva última posición y distancia'
Assert-That ((SqlScalar "SELECT COUNT(*) FROM pedido_eventos WHERE pedido_id = '$orderId' AND hito = 'ENTREGADO'") -eq '1') '5. ENTREGADO queda en la bitácora'
Assert-That ((SqlScalar "SELECT COUNT(*) FROM notificaciones_push WHERE pedido_id = '$orderId' AND hito = 'ENTREGADO'") -eq '1') '5. ENTREGADO genera el PUSH final'

# 6. Reproceso del mismo hito por Artemis: el conteo sigue en uno.
$beforeReplay = SqlScalar "SELECT COUNT(*) FROM notificaciones_push WHERE pedido_id = '$orderId' AND hito = 'ENTREGADO'"
$replay = "{`"schemaVersion`":`"1.0`",`"messageId`":`"replay-$stamp`",`"tipo`":`"pedido.estado-cambiado`",`"pedidoId`":`"$orderId`",`"deviceId`":`"repartidor-01`",`"estadoAnterior`":`"CERCA`",`"estadoNuevo`":`"ENTREGADO`",`"distanciaDestinoM`":5.56,`"timestamp`":`"$([DateTime]::UtcNow.ToString('o'))`"}"
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/eventos/pedido' -ContentType 'application/json' -Body $replay | Out-Null
Start-Sleep -Seconds 2
$afterReplay = SqlScalar "SELECT COUNT(*) FROM notificaciones_push WHERE pedido_id = '$orderId' AND hito = 'ENTREGADO'"
Assert-That ($beforeReplay -eq $afterReplay -and $afterReplay -eq '1') '6. Reproceso no duplica el PUSH'

# 7. Repartidor real de Traccar pero sin pedido activo.
Send-Gps 'repartidor-02' -25.3000 -57.6359 12
Wait-For { (SqlScalar "SELECT COUNT(*) FROM errores_integracion WHERE motivo LIKE 'REPARTIDOR_SIN_PEDIDO_ACTIVO:%repartidor-02%'") -ge 1 } 'DLQ de repartidor sin pedido activo'
Assert-That ($true) '7. Posición sin pedido activo se deriva a DLQ sin detener el flujo'

# 8. Repartidor desconocido al crear un pedido.
$unknownCourier = '{"id":"PED-COURIER-UNKNOWN-' + $stamp + '","cliente_nombre":"X","cliente_msisdn":"+595971","lat_destino":-25.29,"lon_destino":-57.63,"repartidor_device_id":"repartidor-inexistente"}'
$status = Get-HttpStatus { Invoke-WebRequest -Method Post -Uri 'http://localhost:8081/pedidos' -ContentType 'application/json' -Body $unknownCourier -UseBasicParsing }
Assert-That ($status -eq 422) '8. Repartidor desconocido es rechazado con 422'
Wait-For { (SqlScalar "SELECT COUNT(*) FROM errores_integracion WHERE motivo LIKE 'REPARTIDOR_DESCONOCIDO:%repartidor-inexistente%'") -ge 1 } 'DLQ de repartidor desconocido'

# 9. Evento de pedido inexistente: falla el adaptador PUSH y queda en DLQ.
$missingEvent = "{`"schemaVersion`":`"1.0`",`"messageId`":`"missing-$stamp`",`"tipo`":`"pedido.estado-cambiado`",`"pedidoId`":`"$unknownOrderId`",`"deviceId`":`"repartidor-01`",`"estadoAnterior`":`"CERCA`",`"estadoNuevo`":`"ENTREGADO`",`"distanciaDestinoM`":0,`"timestamp`":`"$([DateTime]::UtcNow.ToString('o'))`"}"
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/eventos/pedido' -ContentType 'application/json' -Body $missingEvent | Out-Null
Wait-For { (SqlScalar "SELECT COUNT(*) FROM errores_integracion WHERE motivo LIKE 'PEDIDO_INEXISTENTE:%$unknownOrderId%'") -ge 1 } 'DLQ de pedido inexistente'
Assert-That ($true) '9. Evento de pedido inexistente llega a DLQ'

# Error de payload para dejar evidencia del manejo 400 + DLQ.
$status = Get-HttpStatus { Invoke-WebRequest -Method Post -Uri 'http://localhost:8081/pedidos' -ContentType 'application/json' -Body '{"id":""}' -UseBasicParsing }
Assert-That ($status -eq 400) 'Payload inválido es rechazado con 400'
Wait-For { (SqlScalar "SELECT COUNT(*) FROM errores_integracion WHERE motivo = 'CAMPOS_REQUERIDOS_AUSENTES'") -ge 1 } 'DLQ de payload inválido'

Write-Host "`nTodas las verificaciones del Desafío 3 finalizaron correctamente." -ForegroundColor Cyan
Write-Host "Pedido de evidencia: $orderId"
