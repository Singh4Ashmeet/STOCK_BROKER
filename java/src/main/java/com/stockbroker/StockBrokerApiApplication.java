package com.stockbroker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Main Spring Boot application for the Stock Broker API.
 *
 * <p>Modular monolith structure with packages by capability:
 * <ul>
 *   <li>{@code auth} - Authentication and authorization</li>
 *   <li>{@code market} - Market data gateway (gRPC client, state, fan-out)</li>
 *   <li>{@code portfolio} - Portfolio management</li>
 *   <li>{@code trading} - Order management and execution</li>
 *   <li>{@code alerts} - Price and metric alerts</li>
 *   <li>{@code research} - Backtesting and strategy research</li>
 *   <li>{@code admin} - Administrative endpoints</li>
 *   <li>{@code common} - Shared utilities, configuration, error handling</li>
 * </ul>
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableAsync
@EntityScan(basePackages = "com.stockbroker.*.domain")
@ComponentScan(basePackages = "com.stockbroker")
public class StockBrokerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockBrokerApiApplication.class, args);
    }
}