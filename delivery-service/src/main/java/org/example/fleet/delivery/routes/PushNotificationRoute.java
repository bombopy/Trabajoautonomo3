package org.example.fleet.delivery.routes;

import org.apache.camel.builder.RouteBuilder;
import org.example.fleet.model.OrderEvent;

/** Dedicated durable subscriber / adapter for the simulated PUSH provider. */
public class PushNotificationRoute extends RouteBuilder {
    @Override
    public void configure() {
        from("amqp:topic:delivery.orders"
            + "?subscriptionDurable=true"
            + "&durableSubscriptionName=delivery-push-adapter"
            + "&clientId=delivery-push-adapter")
            .routeId("delivery-push-notification-adapter")
            .setProperty("delivery.original.payload", body())
            .doTry()
                .unmarshal().json(OrderEvent.class)
                .setProperty("delivery.original.payload", body())
                .bean("pushNotificationService", "notifyIfMilestone")
            .doCatch(Exception.class)
                .setProperty("delivery.failure.reason", simple("${exception.message}"))
                .to("direct:delivery-dlq")
            .end();
    }
}
