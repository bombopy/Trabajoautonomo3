package org.example.fleet.delivery.routes;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.ExchangePattern;

/** Dead Letter Channel endpoint for invalid payloads and domain/infrastructure failures. */
public class DeadLetterRoute extends RouteBuilder {
    @Override
    public void configure() {
        from("direct:delivery-dlq")
            .routeId("delivery-dead-letter-channel")
            .bean("deadLetterService", "sendToDeadLetter")
            .marshal().json()
            .setExchangePattern(ExchangePattern.InOnly)
            .to("amqp:queue:delivery.dlq")
            .log("[Delivery DLQ] ${body}");
    }
}
