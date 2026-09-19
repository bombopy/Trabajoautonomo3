package org.example.fleet.delivery.routes;

import org.apache.camel.builder.RouteBuilder;
import org.example.fleet.model.VehiclePosition;

/** Correlation and state-transition route, driven exclusively by the GPS topic. */
public class DeliveryPositionRoute extends RouteBuilder {
    @Override
    public void configure() {
        errorHandler(deadLetterChannel("direct:delivery-dlq")
            .useOriginalMessage()
            .maximumRedeliveries(2)
            .redeliveryDelay(250));

        from("amqp:topic:vehicle.positions"
            + "?subscriptionDurable=true"
            + "&durableSubscriptionName=delivery-position-processor"
            + "&clientId=delivery-position-processor")
            .routeId("delivery-position-process-manager")
            .setProperty("delivery.original.payload", body())
            .unmarshal().json(VehiclePosition.class)
            .setProperty("delivery.original.payload", body())
            // Message Filter: invalid GPS reports do not enter the order lifecycle.
            .filter(simple("${body.valid}"))
                .bean("orderService", "processPosition")
                .choice()
                    .when(body().isNotNull())
                        .marshal().json()
                        .to("amqp:topic:delivery.orders")
                        .log("[Delivery] Published order milestone from GPS")
                    .otherwise()
                        .log("[Delivery] Position saved; no lifecycle milestone")
                .end()
            .end();
    }
}
