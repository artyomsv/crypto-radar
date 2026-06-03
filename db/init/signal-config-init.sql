-- =============================================================================
-- Signal config versioning + backtest results
-- =============================================================================
-- Runtime-tunable signal-engine parameters with full version history. Replaces
-- ~75 hardcoded constants in SignalEngine.java. Each version is immutable;
-- "rollback" is a one-statement is_active flip.
--
-- Backtest runs are anchored to a config_version_id so any reported metric
-- can always be reproduced by the exact config that produced it.
-- =============================================================================

CREATE TABLE IF NOT EXISTS signal_config_versions (
    id                 BIGSERIAL PRIMARY KEY,
    version            INTEGER          NOT NULL UNIQUE,
    config             JSONB            NOT NULL,
    description        TEXT             NOT NULL DEFAULT '',
    parent_version_id  BIGINT           REFERENCES signal_config_versions(id),
    is_active          BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(64)      NOT NULL DEFAULT 'system'
);

-- Exactly one active version at any time. The partial unique index makes the
-- invariant a database-level guarantee rather than an application contract.
CREATE UNIQUE INDEX IF NOT EXISTS idx_signal_config_one_active
    ON signal_config_versions (is_active) WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_signal_config_created
    ON signal_config_versions (created_at DESC);

-- =============================================================================
-- Backtest run aggregates
-- =============================================================================
-- One row per backtest invocation. tier=1 replays stored signal_outcomes
-- against a proposed config (cheap, no candle reconstruction). tier=2 (future)
-- runs a full historical replay with reconstructed MarketContexts.

CREATE TABLE IF NOT EXISTS backtest_runs (
    id                  BIGSERIAL PRIMARY KEY,
    config_version_id   BIGINT           NOT NULL REFERENCES signal_config_versions(id),
    period_start        TIMESTAMPTZ      NOT NULL,
    period_end          TIMESTAMPTZ      NOT NULL,
    tier                SMALLINT         NOT NULL DEFAULT 1,
    trade_count         INTEGER          NOT NULL DEFAULT 0,
    win_count           INTEGER          NOT NULL DEFAULT 0,
    loss_count          INTEGER          NOT NULL DEFAULT 0,
    total_r             DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_r               DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_winner_r        DOUBLE PRECISION,
    avg_loser_r         DOUBLE PRECISION,
    -- Original metrics (computed from active config at run time) for side-by-side comparison
    original_trade_count INTEGER         NOT NULL DEFAULT 0,
    original_total_r     DOUBLE PRECISION NOT NULL DEFAULT 0,
    metadata            JSONB,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    duration_ms         INTEGER          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_backtest_runs_version
    ON backtest_runs (config_version_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_backtest_runs_period
    ON backtest_runs (period_start, period_end);

-- =============================================================================
-- Per-trade backtest detail
-- =============================================================================
-- Lets the UI render side-by-side: "outcome X originally fired BUY at align 60,
-- proposed config would have fired NEUTRAL — and the trade lost 1R, so proposed
-- config saved 1R."
-- ON DELETE CASCADE so dropping an obsolete backtest_run cleans its trades too.

CREATE TABLE IF NOT EXISTS backtest_trades (
    id                    BIGSERIAL PRIMARY KEY,
    backtest_run_id       BIGINT           NOT NULL REFERENCES backtest_runs(id) ON DELETE CASCADE,
    outcome_signal_id     VARCHAR(64)      NOT NULL,
    outcome_fired_at      TIMESTAMPTZ      NOT NULL,
    symbol                VARCHAR(20)      NOT NULL,
    direction             VARCHAR(8)       NOT NULL,
    original_signal       VARCHAR(16)      NOT NULL,
    original_alignment    INTEGER          NOT NULL,
    backtest_signal       VARCHAR(16)      NOT NULL,
    backtest_alignment    INTEGER          NOT NULL,
    realized_r_multiple   DOUBLE PRECISION,
    backtest_would_issue  BOOLEAN          NOT NULL,
    contributed_r         DOUBLE PRECISION NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_backtest_trades_run
    ON backtest_trades (backtest_run_id);

CREATE INDEX IF NOT EXISTS idx_backtest_trades_diff
    ON backtest_trades (backtest_run_id, original_signal, backtest_signal);

-- =============================================================================
-- Seed v1 — current hardcoded values from SignalEngine.java
-- =============================================================================
-- This row reflects the constants in SignalEngine.java as of the migration.
-- Subsequent edits create new rows; v1 stays as the immutable baseline so
-- rollbacks always have a known-good fallback.

INSERT INTO signal_config_versions (version, config, description, is_active, created_by)
VALUES (
    1,
    '{
      "weights": {
        "technical": 0.35,
        "whale": 0.20,
        "orderBook": 0.10,
        "derivatives": 0.15,
        "sentiment": 0.10,
        "macro": 0.10
      },
      "tradeLevels": {
        "minRiskPct": 0.015,
        "minRr": 2.0,
        "atrStopMultiple": 1.5,
        "supportStopAtrBuffer": 0.5
      },
      "rsi": {
        "oversoldExtreme": 30,
        "oversoldApproaching": 40,
        "overboughtApproaching": 60,
        "overboughtExtreme": 70,
        "scoreOversoldExtreme": 80,
        "scoreOversoldApproaching": 40,
        "scoreOverboughtApproaching": -40,
        "scoreOverboughtExtreme": -80
      },
      "macd": {
        "scoreBullish": 30,
        "scoreBearish": -30
      },
      "sma200": {
        "scoreAbove": 35,
        "scoreBelow": -35
      },
      "bollinger": {
        "lowerPosition": 0.3,
        "upperPosition": 0.7,
        "scoreLower": 20,
        "scoreUpper": -20
      },
      "volumeConfirmation": {
        "scoreDecreasing": -10,
        "scoreIncreasing": 5
      },
      "supportResistance": {
        "lowerPosition": 0.3,
        "upperPosition": 0.7,
        "scoreNearSupport": 30,
        "scoreNearResistance": -30
      },
      "whale": {
        "minSampleSize": 15,
        "amplifyThreshold": 30,
        "amplifyFactor": 1.20
      },
      "derivativesFunding": {
        "neutralThreshold": 0.0003,
        "moderateThreshold": 0.0008,
        "extremeThreshold": 0.0015,
        "scoreModerate": 20,
        "scoreStrong": 35,
        "scoreExtreme": 50
      },
      "longShortRatio": {
        "extremelyCrowdedShortsPct": 30,
        "crowdedShortsPct": 40,
        "crowdedLongsPct": 60,
        "extremelyCrowdedLongsPct": 70,
        "scoreModerate": 20,
        "scoreExtreme": 35
      },
      "fearGreed": {
        "extremeFearMax": 10,
        "fearMax": 25,
        "greedMin": 75,
        "extremeGreedMin": 90,
        "scoreModerate": 15,
        "scoreExtreme": 30
      },
      "newsSentiment": {
        "scoreMultiplier": 50
      },
      "orderBook": {
        "highLiquidationRatio": 0.05,
        "moderateLiquidationRatio": 0.01,
        "scoreHighVolatility": -20
      },
      "macroBtcDominance": {
        "threshold": 50.0,
        "scoreBtcWhenHigh": 20,
        "scoreAltWhenHigh": -20,
        "scoreBtcWhenLow": -10,
        "scoreAltWhenLow": 20
      },
      "alignment": {
        "minScoreForNonZero": 5,
        "contradictionScoreThreshold": 30,
        "contradictionPenaltyMultiplier": 0.5,
        "twoContradictionPenalty": 0.6,
        "oneContradictionPenalty": 0.8,
        "outputScale": 0.9,
        "minOutput": 15,
        "maxOutput": 90
      },
      "signalLabels": {
        "chop": {
          "strongBuyMinScore": 55,
          "buyMinScore": 25,
          "strongSellMaxScore": -40,
          "sellMaxScore": -15,
          "strongAlignmentMin": 60,
          "alignmentMin": 35
        },
        "bull": {
          "strongSellMaxScore": -55,
          "sellMaxScore": -30
        },
        "bear": {
          "strongBuyMinScore": 70,
          "buyMinScore": 40
        }
      },
      "trail": {
        "activationR": 1.0,
        "stepR": 0.5,
        "offsetR": 0.5,
        "widerOffsetActivationR": 2.5,
        "widerOffsetR": 1.0
      }
    }'::jsonb,
    'v1 — baseline extracted from SignalEngine.java hardcoded constants',
    TRUE,
    'system'
)
ON CONFLICT (version) DO NOTHING;

-- Idempotent backfill: any pre-existing row that pre-dates the trail group
-- gets the canonical defaults injected. jsonb_set with create_missing=true is
-- a no-op on rows that already carry "trail".
UPDATE signal_config_versions
SET config = jsonb_set(
    config,
    '{trail}',
    '{"activationR": 1.0, "stepR": 0.5, "offsetR": 0.5, "widerOffsetActivationR": 2.5, "widerOffsetR": 1.0}'::jsonb,
    true)
WHERE NOT (config ? 'trail');
