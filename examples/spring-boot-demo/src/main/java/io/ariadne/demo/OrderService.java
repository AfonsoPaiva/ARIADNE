package io.ariadne.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CompletableFuture;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final PaymentService paymentService;

    public OrderService(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Demonstrates multi-hop asynchronous execution via Spring @Async and CompletableFuture.
     */
    @Async
    public CompletableFuture<String> placeOrderAsync(String orderId) {
        log.info("OrderService.placeOrderAsync initiated on thread: {}", Thread.currentThread().getName());
        return paymentService.processPayment(orderId)
                .thenApply(paymentRef -> "Order Confirmed: " + paymentRef);
    }

    @Async
    public CompletableFuture<String> placeOrderAsyncSuccess(String orderId) {
        return paymentService.processPaymentSuccess(orderId)
                .thenApply(paymentRef -> "Order Confirmed: " + paymentRef);
    }

    /**
     * Demonstrates reactive pipeline execution via Project Reactor / WebFlux.
     */
    public Mono<String> processOrderReactive(String orderId) {
        return Mono.just(orderId)
                .publishOn(Schedulers.boundedElastic())
                .map(id -> {
                    log.info("Validating order {} in reactive pipeline", id);
                    return "validated-" + id;
                })
                .publishOn(Schedulers.parallel())
                .map(validatedId -> {
                    // Simulated async processing error in parallel scheduler
                    throw new RuntimeException("Inventory deduction failed for: " + validatedId);
                });
    }
}
