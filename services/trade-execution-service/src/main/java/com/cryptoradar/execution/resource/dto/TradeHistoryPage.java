package com.cryptoradar.execution.resource.dto;

import java.util.List;

public record TradeHistoryPage(
        List<TradeView> items,
        long total,
        int page,
        int pageSize
) {}
