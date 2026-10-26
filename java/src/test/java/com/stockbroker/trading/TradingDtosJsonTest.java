package com.stockbroker.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockbroker.trading.api.TradingDtos;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingDtosJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createOrderAcceptsCamelCaseLimitPriceAndRejectsLegacySnakeCaseRequest() throws Exception {
        var request = objectMapper.readValue("""
                {"symbol":"BINANCE:BTC-USDT","side":"BUY","type":"LIMIT","qty":"0.01",
                 "limitPrice":"50000","tif":"GTC","idempotency_key":"order-1"}
                """, TradingDtos.CreateOrderRequest.class);

        assertThat(request.limitPrice()).isEqualTo("50000");
        assertThatThrownBy(() -> objectMapper.readValue("""
                {"symbol":"BINANCE:BTC-USDT","side":"BUY","type":"LIMIT","qty":"0.01",
                 "limit_price":"50000","tif":"GTC","idempotency_key":"order-1"}
                """, TradingDtos.CreateOrderRequest.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void createOrderAllowsTheIdempotencyKeyToBeSuppliedOnlyInTheHttpHeader() throws Exception {
        var request = objectMapper.readValue("""
                {"symbol":"BINANCE:BTC-USDT","side":"BUY","type":"MARKET","qty":"0.01","tif":"GTC"}
                """, TradingDtos.CreateOrderRequest.class);

        assertThat(request.idempotencyKey()).isNull();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void orderResponseRemainsSnakeCaseAtTheWireBoundary() throws Exception {
        var response = new TradingDtos.OrderResponse("order-1", "BINANCE:BTC-USDT",
                TradingDtos.OrderSide.BUY, TradingDtos.OrderType.LIMIT, TradingDtos.OrderStatus.NEW,
                "0.01", "0", null, "50000", TradingDtos.TimeInForce.GTC,
                null, "2026-10-26T00:00:00Z", "2026-10-26T00:00:00Z", null);

        var json = objectMapper.readTree(objectMapper.writeValueAsString(response));
        assertThat(json.path("limit_price").asText()).isEqualTo("50000");
        assertThat(json.has("limitPrice")).isFalse();
    }
}
