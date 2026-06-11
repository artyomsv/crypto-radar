package com.cryptoradar.execution.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketDataClientDailyBarsTest {

    private final MarketDataClient client = new MarketDataClient();

    @Test
    void parseDailyBars_reversesToOldestFirst_andMapsOHLC() {
        // upstream returns newest-first
        String json = """
            [
              {"time":"2026-06-10T00:00:00Z","open":2,"high":12,"low":1,"close":10,"volume":5},
              {"time":"2026-06-09T00:00:00Z","open":3,"high":9,"low":2,"close":8,"volume":7}
            ]
            """;
        List<MarketDataClient.DailyBar> bars = client.parseDailyBars(json);
        assertEquals(2, bars.size());
        // oldest-first: 2026-06-09 first
        assertEquals(3.0, bars.get(0).open());
        assertEquals(9.0, bars.get(0).high());
        assertEquals(2.0, bars.get(0).low());
        assertEquals(12.0, bars.get(1).high());
        assertEquals(10.0, bars.get(1).close());
    }

    @Test
    void parseDailyBars_emptyOrMalformed_returnsEmpty() {
        assertTrue(client.parseDailyBars("[]").isEmpty());
        assertTrue(client.parseDailyBars("not json").isEmpty());
    }
}
