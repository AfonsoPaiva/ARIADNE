package io.ariadne.demo;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public CompletableFuture<String> processPayment(String orderId) {
        log.info("Processing payment asynchronously for order: {}", orderId);
        return CompletableFuture.supplyAsync(() -> {
            // Simulated downstream payment gateway crash on background worker thread
            throw new IllegalStateException("Payment gateway connection timeout [orderId=" + orderId + "]");
        });
    }

    public CompletableFuture<String> processPaymentSuccess(String orderId) {
        return CompletableFuture.supplyAsync(() -> "TX-" + orderId);
    }
}
