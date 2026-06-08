-- =============================================================================
-- Short-vol opportunity log — the inverse of option_opportunities.
-- =============================================================================
-- Fires when IV substantially exceeds RV (vol risk premium regime). Tier 1
-- of the vol-strategy plan (docs/knowledge-base/10-projectr-x-mapping/
-- 05-vol-strategy-plan.md). Defined-risk structures only — schema carries
-- both legs of the credit spread AND the long-side hedge legs that cap loss.
--
-- This file is idempotent (CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT
-- EXISTS) so re-applying after partial migrations is safe.
-- =============================================================================

CREATE TABLE IF NOT EXISTS option_short_vol_opportunities (
    id                  BIGSERIAL PRIMARY KEY,
    detected_at         TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    underlying          VARCHAR(16)      NOT NULL,
    expiry              DATE             NOT NULL,

    -- Defined-risk structure descriptor — Tier 2 ships iron condor + credit spread.
    structure_type      VARCHAR(24)      NOT NULL,    -- 'IRON_CONDOR' | 'CREDIT_SPREAD_CALL' | 'CREDIT_SPREAD_PUT'

    -- Short legs (what we sell — premium-collecting side).
    short_call_symbol   VARCHAR(48),
    short_call_strike   DOUBLE PRECISION,
    short_call_delta    DOUBLE PRECISION,
    short_put_symbol    VARCHAR(48),
    short_put_strike    DOUBLE PRECISION,
    short_put_delta     DOUBLE PRECISION,

    -- Long legs (hedges — cap the loss). CREDIT_SPREAD_CALL has no long_put_*;
    -- CREDIT_SPREAD_PUT has no long_call_*. IRON_CONDOR has all four.
    long_call_symbol    VARCHAR(48),
    long_call_strike    DOUBLE PRECISION,
    long_put_symbol     VARCHAR(48),
    long_put_strike     DOUBLE PRECISION,

    -- Economics — pre-computed at detection time.
    net_credit          DOUBLE PRECISION NOT NULL,    -- premium received
    max_loss_usd        DOUBLE PRECISION NOT NULL,    -- bounded; sizer's input
    pop_pct             DOUBLE PRECISION,             -- probability-of-profit at entry
    break_even_low      DOUBLE PRECISION,
    break_even_high     DOUBLE PRECISION,

    -- Score decomposition (mirrors OpportunityScorer.Diagnostic).
    implied_vol_atm     DOUBLE PRECISION,
    realized_vol_14d    DOUBLE PRECISION,
    iv_rv_premium_pct   DOUBLE PRECISION,             -- (iv - rv) / rv * 100
    term_structure_score DOUBLE PRECISION,
    signal_quiet_score  DOUBLE PRECISION,
    iv_percentile_score DOUBLE PRECISION,
    confidence          DOUBLE PRECISION NOT NULL,

    metadata            JSONB,

    -- Triple-barrier outcome fields (Tier 3 backfill job populates).
    outcome_label       VARCHAR(16),                  -- 'WIN' | 'LOSS' | 'EXPIRED'
    outcome_pnl_usd     DOUBLE PRECISION,
    outcome_pnl_pct     DOUBLE PRECISION,
    outcome_resolved_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_short_vol_underlying_detected
    ON option_short_vol_opportunities (underlying, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_short_vol_open
    ON option_short_vol_opportunities (outcome_resolved_at NULLS FIRST, detected_at DESC);

-- Tier 3: backfill the triple-barrier columns on the existing long-vol table
-- so we can score both strategies through the same evaluation pipeline.
ALTER TABLE option_opportunities
    ADD COLUMN IF NOT EXISTS outcome_label VARCHAR(16);
