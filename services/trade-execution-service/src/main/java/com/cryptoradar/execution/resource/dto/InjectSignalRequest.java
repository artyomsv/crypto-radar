package com.cryptoradar.execution.resource.dto;

import java.math.BigDecimal;

public record InjectSignalRequest(
        String symbol, String direction, String strategy,
        BigDecimal entryPrice, BigDecimal stopPrice, BigDecimal targetPrice
) {}
