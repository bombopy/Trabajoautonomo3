<#!
Runs the three GPS milestones for the sample order. Start the Docker stack first:
    docker compose up --build -d
#>

$ErrorActionPreference = 'Stop'
$orderId = "PED-DEMO-$(Get-Date -Format 'yyyyMMddHHmmss')"

$order = @{
    id = $orderId
    cliente_nombre = 'Juan Pérez'
    cliente_msisdn = '+595972222222'
    cliente_fcm_id = 'fcm_token_demo'
    direccion_texto = 'Av. España 1234, Asunción'
    lat_destino = -25.2967
    lon_destino = -57.6359
    radio_llegada_m = 150
    repartidor_device_id = 'repartidor-01'
} | ConvertTo-Json

Write-Host "Creando pedido $orderId"
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/pedidos' -ContentType 'application/json' -Body $order

function Send-Gps([double]$lat, [double]$lon, [int]$speed) {
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $ignition = if ($speed -gt 0) { 'true' } else { 'false' }
    $uri = "http://localhost:5055/?id=repartidor-01&lat=$lat&lon=$lon&timestamp=$timestamp&speed=$speed&bearing=90&ignition=$ignition"
    Invoke-WebRequest -Uri $uri -UseBasicParsing | Out-Null
    Start-Sleep -Seconds 3
}

# 1) Starts the trip (> 150 m from destination); 2) reaches its radius;
# 3) stopped confirmation inside the radius.
Send-Gps -25.3000 -57.6359 25
Send-Gps -25.2974 -57.6359 15
Send-Gps -25.29675 -57.6359 0

Write-Host "Esperando confirmación de entrega..."
$trackingUri = "http://localhost:8081/pedidos/$orderId/tracking"
$tracking = $null
for ($attempt = 1; $attempt -le 10; $attempt++) {
    $tracking = Invoke-RestMethod -Uri $trackingUri
    if ($tracking.estado -eq 'ENTREGADO') {
        break
    }
    Start-Sleep -Seconds 1
}

Write-Host "Seguimiento final:"
$tracking | ConvertTo-Json -Depth 5
if ($tracking.estado -ne 'ENTREGADO') {
    throw "El pedido no alcanzó ENTREGADO dentro del tiempo esperado. Estado actual: $($tracking.estado)"
}

Write-Host "Verificar bitácora: docker compose exec postgres psql -U delivery -d delivery -c `"TABLE pedido_eventos;`""
