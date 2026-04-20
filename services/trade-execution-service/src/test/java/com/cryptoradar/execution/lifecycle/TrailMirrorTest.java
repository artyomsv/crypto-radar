package com.cryptoradar.execution.lifecycle;

import com.cryptoradar.execution.client.MarketDataClient;
import com.cryptoradar.execution.client.bybit.BybitV5RestClient;
import com.cryptoradar.execution.client.bybit.dto.BybitResponse;
import com.cryptoradar.execution.client.bybit.dto.TradingStopRequest;
import com.cryptoradar.execution.model.ExchangeAccount;
import com.cryptoradar.execution.model.ExecutedTrade;
import com.cryptoradar.execution.model.TradeStatus;
import com.cryptoradar.execution.repository.ExchangeAccountRepository;
import com.cryptoradar.execution.repository.ExecutedTradeRepository;
import com.cryptoradar.execution.repository.ExecutionEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrailMirrorTest {

    ExecutedTradeRepository tradeRepo;
    ExchangeAccountRepository accountRepo;
    ExecutionEventRepository eventRepo;
    BybitV5RestClient bybit;
    MarketDataClient marketData;
    TrailMirror mirror;

    ExchangeAccount account;

    @BeforeEach
    void setup() {
        tradeRepo = mock(ExecutedTradeRepository.class);
        accountRepo = mock(ExchangeAccountRepository.class);
        eventRepo = mock(ExecutionEventRepository.class);
        bybit = mock(BybitV5RestClient.class);
        marketData = mock(MarketDataClient.class);
        mirror = new TrailMirror(tradeRepo, accountRepo, eventRepo, bybit, marketData);

        account = new ExchangeAccount();
        account.setExchange("BYBIT");
        account.setEnvironment("DEMO");
        account.setApiKeyEncrypted("k");
        account.setApiSecretEncrypted("s");
    }

    private ExecutedTrade openLongTrade(BigDecimal entry, BigDecimal stop, double trailHighestR) {
        ExecutedTrade t = new ExecutedTrade();
        t.setExchangeAccountId(1L);
        t.setSymbol("BTCUSDT");
        t.setDirection("LONG");
        t.setStatus(TradeStatus.OPEN);
        t.setEntryPrice(entry);
        t.setStopPrice(stop);
        t.setTrailHighestR(BigDecimal.valueOf(trailHighestR));
        return t;
    }

    @Test
    void noPriceNoAction() {
        ExecutedTrade t = openLongTrade(new BigDecimal("50000"), new BigDecimal("49500"), 0);
        BigDecimal initialTrail = t.getTrailHighestR();
        when(marketData.getLastPrice("BTCUSDT")).thenReturn(null);
        mirror.processTrade(account, t);
        assertEquals(0, initialTrail.compareTo(t.getTrailHighestR()));
    }

    @Test
    void belowActivationNoAction() {
        // entry 50000, stop 49500, risk=500 → 1% riskPct
        // price 50400 → MFE 0.8% → mfeR = 0.8 (below activation 1.0)
        ExecutedTrade t = openLongTrade(new BigDecimal("50000"), new BigDecimal("49500"), 0);
        BigDecimal initialTrail = t.getTrailHighestR();
        when(marketData.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("50400"));
        mirror.processTrade(account, t);
        assertEquals(0, initialTrail.compareTo(t.getTrailHighestR()));
    }

    @Test
    void aboveActivationAdvancesTrailAndCallsBybit() {
        ExecutedTrade t = openLongTrade(new BigDecimal("50000"), new BigDecimal("49500"), 0);
        // price 51000 → MFE 2% → mfeR 2.0 → trail rung 1.5
        // newTrailR = 1.0 + 2*0.5 - 0.5 = 1.5
        when(marketData.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("51000"));
        when(bybit.setTradingStop(anyString(), anyString(), anyString(), any(TradingStopRequest.class)))
                .thenReturn(new BybitResponse<Map<String, Object>>(0, "OK", Map.of(), 0L));

        mirror.processTrade(account, t);

        assertEquals(new BigDecimal("1.5"), t.getTrailHighestR().stripTrailingZeros().setScale(1));
        // new stop = 50000 + 500 * 1.5 = 50750
        assertEquals(new BigDecimal("50750"), t.getDynamicStopPrice().setScale(0));
        assertNotNull(t.getTrailTriggeredAt());

        ArgumentCaptor<TradingStopRequest> captor = ArgumentCaptor.forClass(TradingStopRequest.class);
        verify(bybit).setTradingStop(anyString(), anyString(), anyString(), captor.capture());
        assertEquals("linear", captor.getValue().category());
    }

    @Test
    void shortDirectionReversesStopMath() {
        // SHORT, entry 5000, stop 5050 → risk 50, price 4900 → MFE 100/5000 = 2.0% exactly
        // riskPct = 50/5000 = 1.0% exactly (clean division avoids rounding drift)
        // mfeR = 2.0/1.0 = 2.0 → newTrailR = 1.0 + floor((2-1)/0.5)*0.5 - 0.5 = 1.5
        // SHORT new stop = entry - risk * newR = 5000 - 50*1.5 = 4925
        ExecutedTrade t = openLongTrade(new BigDecimal("5000"), new BigDecimal("5050"), 0);
        t.setDirection("SHORT");
        when(marketData.getLastPrice("BTCUSDT")).thenReturn(new BigDecimal("4900"));
        when(bybit.setTradingStop(anyString(), anyString(), anyString(), any(TradingStopRequest.class)))
                .thenReturn(new BybitResponse<Map<String, Object>>(0, "OK", Map.of(), 0L));

        mirror.processTrade(account, t);
        assertEquals(0, new BigDecimal("4925").compareTo(t.getDynamicStopPrice()));
    }
}
