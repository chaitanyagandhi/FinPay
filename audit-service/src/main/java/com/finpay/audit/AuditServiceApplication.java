package com.finpay.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the FinPay audit-service.
 *
 * <p>Immutable, searchable audit events ingested from Kafka.
 */
@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
