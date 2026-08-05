package com.finpay.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the FinPay fraud-service.
 *
 * <p>Velocity checks, rule-based risk scoring and manual-review flagging.
 */
@SpringBootApplication
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
