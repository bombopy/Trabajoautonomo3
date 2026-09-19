INSERT INTO repartidores (device_id, nombre, msisdn) VALUES
    ('repartidor-01', 'Ana Gómez', '+595971111111'),
    ('repartidor-02', 'Bruno Díaz', '+595972222222')
ON CONFLICT (device_id) DO NOTHING;
