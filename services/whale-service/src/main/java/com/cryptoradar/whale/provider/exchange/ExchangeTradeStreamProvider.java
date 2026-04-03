package com.cryptoradar.whale.provider.exchange;

/**
 * Abstract contract for all exchange WebSocket trade stream providers.
 * Each implementation connects to a specific exchange's public WebSocket API
 * and emits large trades above the configured threshold.
 */
public interface ExchangeTradeStreamProvider {

    /** Exchange name for display and logging */
    String getExchangeName();

    /** Connect to the exchange WebSocket */
    void connect();

    /** Disconnect from the exchange WebSocket */
    void disconnect();

    /** Whether the WebSocket is currently connected */
    boolean isConnected();
}
