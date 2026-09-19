package org.example.fleet.delivery.model;

import org.example.fleet.model.OrderEvent;

import java.util.Map;

/** Keeps the HTTP acknowledgement and its corresponding business event together. */
public record OrderCreateResult(Map<String, Object> response, OrderEvent event) {}
