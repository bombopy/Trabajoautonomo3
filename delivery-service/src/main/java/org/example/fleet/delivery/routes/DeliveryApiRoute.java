package org.example.fleet.delivery.routes;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.RouteBuilder;
import org.example.fleet.delivery.exception.DuplicateOrderException;
import org.example.fleet.delivery.exception.OrderInputException;
import org.example.fleet.delivery.exception.UnknownCourierException;
import org.example.fleet.delivery.model.OrderCreateResult;
import org.example.fleet.model.OrderEvent;

import java.util.Map;

/** REST adapters for orders, tracking and canonical external order events. */
public class DeliveryApiRoute extends RouteBuilder {
    @Override
    public void configure() {
        restConfiguration()
            .component("platform-http")
            .host("0.0.0.0")
            .port(8081);

        rest("/pedidos")
            .post()
                .consumes("application/json")
                .produces("application/json")
                .to("direct:create-order")
            .get("/{id}/tracking")
                .produces("application/json")
                .to("direct:get-tracking");

        rest("/eventos/pedido")
            .post()
                .consumes("application/json")
                .produces("application/json")
                .to("direct:publish-external-order-event");

        from("direct:create-order")
            .routeId("delivery-create-order-api")
            .convertBodyTo(String.class)
            .setProperty("delivery.original.payload", body())
            .doTry()
                .bean("orderService", "createOrder")
                .process(exchange -> {
                    OrderCreateResult result = exchange.getMessage().getBody(OrderCreateResult.class);
                    exchange.setProperty("delivery.create.result", result);
                    exchange.getMessage().setBody(result.event());
                })
                .marshal().json()
                .to("amqp:topic:delivery.orders")
                .process(exchange -> {
                    OrderCreateResult result = exchange.getProperty("delivery.create.result", OrderCreateResult.class);
                    exchange.getMessage().setBody(result.response());
                })
                .marshal().json()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .doCatch(OrderInputException.class)
                .setProperty("delivery.failure.reason", simple("${exception.message}"))
                .to("direct:delivery-dlq")
                .process(exchange -> exchange.getMessage().setBody(Map.of("error", "PAYLOAD_INVALIDO")))
                .marshal().json()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .doCatch(UnknownCourierException.class)
                .setProperty("delivery.failure.reason", simple("${exception.message}"))
                .to("direct:delivery-dlq")
                .process(exchange -> exchange.getMessage().setBody(Map.of("error", "REPARTIDOR_DESCONOCIDO")))
                .marshal().json()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(422))
            .doCatch(DuplicateOrderException.class)
                .setProperty("delivery.failure.reason", simple("${exception.message}"))
                .to("direct:delivery-dlq")
                .process(exchange -> exchange.getMessage().setBody(Map.of("error", "PEDIDO_DUPLICADO")))
                .marshal().json()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(409))
            .doCatch(Exception.class)
                .setProperty("delivery.failure.reason", simple("${exception.message}"))
                .to("direct:delivery-dlq")
                .process(exchange -> exchange.getMessage().setBody(Map.of("error", "ERROR_DE_INTEGRACION")))
                .marshal().json()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
            .end();

        from("direct:publish-external-order-event")
            .routeId("delivery-external-order-event-gateway")
            .convertBodyTo(String.class)
            .setProperty("delivery.original.payload", body())
            .doTry()
                .unmarshal().json(OrderEvent.class)
                .setProperty("delivery.original.payload", body())
                .marshal().json()
                .setExchangePattern(ExchangePattern.InOnly)
                .to("amqp:topic:delivery.orders")
                .process(exchange -> exchange.getMessage().setBody(Map.of("status", "ACEPTADO")))
                .marshal().json()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .doCatch(Exception.class)
                .setProperty("delivery.failure.reason", simple("${exception.message}"))
                .to("direct:delivery-dlq")
                .process(exchange -> exchange.getMessage().setBody(Map.of("error", "EVENTO_INVALIDO")))
                .marshal().json()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .end();

        from("direct:get-tracking")
            .routeId("delivery-tracking-api")
            .setProperty("delivery.original.payload", header("id"))
            .doTry()
                .bean("orderService", "tracking(${header.id})")
                .process(exchange -> {
                    Object tracking = exchange.getMessage().getBody();
                    if (tracking == null) {
                        exchange.getMessage().setBody(Map.of("error", "PEDIDO_NO_ENCONTRADO"));
                        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                    } else {
                        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                    }
                })
                .marshal().json()
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .doCatch(Exception.class)
                .setProperty("delivery.failure.reason", simple("${exception.message}"))
                .to("direct:delivery-dlq")
                .process(exchange -> exchange.getMessage().setBody(Map.of("error", "ERROR_DE_INTEGRACION")))
                .marshal().json()
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
            .end();
    }
}
