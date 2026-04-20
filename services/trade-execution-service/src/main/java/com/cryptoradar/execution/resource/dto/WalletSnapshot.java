package com.cryptoradar.execution.resource.dto;

import java.math.BigDecimal;

public record WalletSnapshot(
        BigDecimal equity,
        BigDecimal available,
        BigDecimal openPnl,
        BigDecimal todayRealized,
        int positionsOpen
) {}
