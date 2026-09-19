package org.example.fleet.delivery.service;

import org.example.fleet.delivery.repository.OrderRepository;
import org.example.fleet.model.OrderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/** Simulated SNS/PUSH endpoint backed by a deduplicated PostgreSQL outbox table. */
public class PushNotificationService {
    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);
    private static final Set<String> NOTIFIABLE_MILESTONES = Set.of("EN_CAMINO", "CERCA", "ENTREGADO");
    private final OrderRepository repository;

    public PushNotificationService(OrderRepository repository) {
        this.repository = repository;
    }

    public void notifyIfMilestone(OrderEvent event) {
        if (!NOTIFIABLE_MILESTONES.contains(event.estadoNuevo())) {
            return;
        }
        if (repository.registerPushNotification(event)) {
            log.info("[PUSH simulado] pedido={}, hito={}, correlationId={}",
                event.pedidoId(), event.estadoNuevo(), event.messageId());
        } else {
            log.info("[PUSH suprimido] pedido={}, hito={} ya fue notificado", event.pedidoId(), event.estadoNuevo());
        }
    }
}
