package org.example.fleet.delivery;

import org.apache.camel.main.Main;
import org.example.fleet.delivery.config.PostgresDataSourceFactory;
import org.example.fleet.delivery.domain.DeliveryLifecyclePolicy;
import org.example.fleet.delivery.repository.OrderRepository;
import org.example.fleet.delivery.service.DeadLetterService;
import org.example.fleet.delivery.service.OrderService;
import org.example.fleet.delivery.service.PushNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainApp {
    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting Delivery Process Manager: REST + GPS correlation + PUSH simulation");
        OrderRepository repository = new OrderRepository(PostgresDataSourceFactory.create(), new DeliveryLifecyclePolicy());
        Main main = new Main();
        main.bind("orderService", new OrderService(repository));
        main.bind("pushNotificationService", new PushNotificationService(repository));
        main.bind("deadLetterService", new DeadLetterService(repository));
        main.configure().withBasePackageScan("org.example.fleet.delivery.routes");
        main.run(args);
    }
}
