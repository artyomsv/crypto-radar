import { useState } from 'react';
import type { SignalConfig } from '@/types';
import { NumberField, Section, TabButton, ValidationPanel } from './ConfigEditorFields';

interface ConfigEditorProps {
  config: SignalConfig;
  onChange: (next: SignalConfig) => void;
}

type LabelRegimeTab = 'chop' | 'bull' | 'bear';

export function ConfigEditor({ config, onChange }: ConfigEditorProps) {
  const [labelTab, setLabelTab] = useState<LabelRegimeTab>('chop');

  const weightSum = Object.values(config.weights).reduce((a, b) => a + b, 0);
  const weightsValid = Math.abs(weightSum - 1.0) <= 0.001;

  function set<K extends keyof SignalConfig>(section: K, key: keyof SignalConfig[K], value: number) {
    onChange({
      ...config,
      [section]: { ...(config[section] as object), [key]: value },
    } as SignalConfig);
  }

  function setNested<K extends keyof SignalConfig>(
    section: K,
    sub: keyof SignalConfig[K],
    key: string,
    value: number,
  ) {
    const parent = config[section] as unknown as Record<string, unknown>;
    const child = (parent[sub as string] ?? {}) as Record<string, unknown>;
    onChange({
      ...config,
      [section]: { ...parent, [sub as string]: { ...child, [key]: value } },
    } as SignalConfig);
  }

  const w = config.weights;
  const tl = config.tradeLevels;
  const rsi = config.rsi;
  const macd = config.macd;
  const sma = config.sma200;
  const bb = config.bollinger;
  const vol = config.volumeConfirmation;
  const sr = config.supportResistance;
  const whale = config.whale;
  const df = config.derivativesFunding;
  const ls = config.longShortRatio;
  const fg = config.fearGreed;
  const ns = config.newsSentiment;
  const ob = config.orderBook;
  const mac = config.macroBtcDominance;
  const aln = config.alignment;
  const lbl = config.signalLabels;
  const tr = config.trail;

  return (
    <div className="space-y-6">
      <ValidationPanel weightSum={weightSum} isValid={weightsValid} />

      <Section title="Weights (must sum to 1.0)">
        {(Object.keys(w) as Array<keyof typeof w>).map((k) => (
          <NumberField key={k} label={k} tooltip={`Weight for the ${k} dimension`} value={w[k]} min={0} max={1} step={0.01} onChange={(v) => set('weights', k, v)} />
        ))}
      </Section>

      <Section title="Trade Levels">
        <NumberField label="Min risk %" tooltip="Minimum stop distance as fraction of entry (e.g. 0.015 = 1.5%)" value={tl.minRiskPct} min={0.001} max={0.1} step={0.001} onChange={(v) => set('tradeLevels', 'minRiskPct', v)} />
        <NumberField label="Min R:R" tooltip="Minimum risk-reward ratio for a valid trade level" value={tl.minRr} min={0.5} max={10} step={0.1} onChange={(v) => set('tradeLevels', 'minRr', v)} />
        <NumberField label="ATR stop multiple" tooltip="ATR multiple used to set stop distance" value={tl.atrStopMultiple} min={0.5} max={5} step={0.1} onChange={(v) => set('tradeLevels', 'atrStopMultiple', v)} />
        <NumberField label="Support stop ATR buffer" tooltip="Extra ATR buffer added when anchoring stop to support/resistance" value={tl.supportStopAtrBuffer} min={0} max={2} step={0.05} onChange={(v) => set('tradeLevels', 'supportStopAtrBuffer', v)} />
      </Section>

      <Section title="RSI Thresholds">
        <NumberField label="Oversold extreme" tooltip="RSI level considered extremely oversold — strong bullish score" value={rsi.oversoldExtreme} min={1} max={50} step={1} onChange={(v) => set('rsi', 'oversoldExtreme', v)} />
        <NumberField label="Oversold approaching" tooltip="RSI level approaching oversold — moderate bullish score" value={rsi.oversoldApproaching} min={1} max={50} step={1} onChange={(v) => set('rsi', 'oversoldApproaching', v)} />
        <NumberField label="Overbought approaching" tooltip="RSI level approaching overbought — moderate bearish score" value={rsi.overboughtApproaching} min={50} max={99} step={1} onChange={(v) => set('rsi', 'overboughtApproaching', v)} />
        <NumberField label="Overbought extreme" tooltip="RSI level considered extremely overbought — strong bearish score" value={rsi.overboughtExtreme} min={50} max={99} step={1} onChange={(v) => set('rsi', 'overboughtExtreme', v)} />
        <NumberField label="Score: oversold extreme" tooltip="Score awarded when RSI is at extreme oversold level" value={rsi.scoreOversoldExtreme} min={-100} max={100} step={1} onChange={(v) => set('rsi', 'scoreOversoldExtreme', v)} />
        <NumberField label="Score: oversold approaching" tooltip="Score awarded when RSI is approaching oversold" value={rsi.scoreOversoldApproaching} min={-100} max={100} step={1} onChange={(v) => set('rsi', 'scoreOversoldApproaching', v)} />
        <NumberField label="Score: overbought approaching" tooltip="Score awarded when RSI is approaching overbought" value={rsi.scoreOverboughtApproaching} min={-100} max={100} step={1} onChange={(v) => set('rsi', 'scoreOverboughtApproaching', v)} />
        <NumberField label="Score: overbought extreme" tooltip="Score awarded when RSI is at extreme overbought level" value={rsi.scoreOverboughtExtreme} min={-100} max={100} step={1} onChange={(v) => set('rsi', 'scoreOverboughtExtreme', v)} />
      </Section>

      <Section title="MACD">
        <NumberField label="Score: bullish" tooltip="Score when MACD line crosses above signal line" value={macd.scoreBullish} min={-100} max={100} step={1} onChange={(v) => set('macd', 'scoreBullish', v)} />
        <NumberField label="Score: bearish" tooltip="Score when MACD line crosses below signal line" value={macd.scoreBearish} min={-100} max={100} step={1} onChange={(v) => set('macd', 'scoreBearish', v)} />
      </Section>

      <Section title="SMA 200">
        <NumberField label="Score: price above" tooltip="Score when price is above the 200-day SMA (bullish)" value={sma.scoreAbove} min={-100} max={100} step={1} onChange={(v) => set('sma200', 'scoreAbove', v)} />
        <NumberField label="Score: price below" tooltip="Score when price is below the 200-day SMA (bearish)" value={sma.scoreBelow} min={-100} max={100} step={1} onChange={(v) => set('sma200', 'scoreBelow', v)} />
      </Section>

      <Section title="Bollinger Bands">
        <NumberField label="Lower band position" tooltip="Price/band ratio defining the lower band proximity zone" value={bb.lowerPosition} min={0} max={1} step={0.01} onChange={(v) => set('bollinger', 'lowerPosition', v)} />
        <NumberField label="Upper band position" tooltip="Price/band ratio defining the upper band proximity zone" value={bb.upperPosition} min={0} max={1} step={0.01} onChange={(v) => set('bollinger', 'upperPosition', v)} />
        <NumberField label="Score: near lower band" tooltip="Score when price is near or below the lower Bollinger band" value={bb.scoreLower} min={-100} max={100} step={1} onChange={(v) => set('bollinger', 'scoreLower', v)} />
        <NumberField label="Score: near upper band" tooltip="Score when price is near or above the upper Bollinger band" value={bb.scoreUpper} min={-100} max={100} step={1} onChange={(v) => set('bollinger', 'scoreUpper', v)} />
      </Section>

      <Section title="Volume Confirmation">
        <NumberField label="Score: decreasing" tooltip="Score when volume is declining (weakening momentum)" value={vol.scoreDecreasing} min={-100} max={100} step={1} onChange={(v) => set('volumeConfirmation', 'scoreDecreasing', v)} />
        <NumberField label="Score: increasing" tooltip="Score when volume is increasing (confirming momentum)" value={vol.scoreIncreasing} min={-100} max={100} step={1} onChange={(v) => set('volumeConfirmation', 'scoreIncreasing', v)} />
      </Section>

      <Section title="Support / Resistance">
        <NumberField label="Lower zone position" tooltip="Distance ratio defining proximity to support" value={sr.lowerPosition} min={0} max={0.1} step={0.001} onChange={(v) => set('supportResistance', 'lowerPosition', v)} />
        <NumberField label="Upper zone position" tooltip="Distance ratio defining proximity to resistance" value={sr.upperPosition} min={0} max={0.1} step={0.001} onChange={(v) => set('supportResistance', 'upperPosition', v)} />
        <NumberField label="Score: near support" tooltip="Score when price is near support level" value={sr.scoreNearSupport} min={-100} max={100} step={1} onChange={(v) => set('supportResistance', 'scoreNearSupport', v)} />
        <NumberField label="Score: near resistance" tooltip="Score when price is near resistance level" value={sr.scoreNearResistance} min={-100} max={100} step={1} onChange={(v) => set('supportResistance', 'scoreNearResistance', v)} />
      </Section>

      <Section title="Whale">
        <NumberField label="Min sample size" tooltip="Minimum number of whale transactions required before scoring" value={whale.minSampleSize} min={1} max={100} step={1} onChange={(v) => set('whale', 'minSampleSize', v)} />
        <NumberField label="Amplify threshold" tooltip="Whale pressure value above which the score is amplified" value={whale.amplifyThreshold} min={0} max={100} step={1} onChange={(v) => set('whale', 'amplifyThreshold', v)} />
        <NumberField label="Amplify factor" tooltip="Multiplier applied when whale pressure exceeds the amplify threshold" value={whale.amplifyFactor} min={1} max={5} step={0.1} onChange={(v) => set('whale', 'amplifyFactor', v)} />
      </Section>

      <Section title="Derivatives Funding">
        <NumberField label="Neutral threshold" tooltip="Funding rate below which market is considered neutral" value={df.neutralThreshold} min={0} max={0.5} step={0.001} onChange={(v) => set('derivativesFunding', 'neutralThreshold', v)} />
        <NumberField label="Moderate threshold" tooltip="Funding rate above which market sentiment is moderately biased" value={df.moderateThreshold} min={0} max={0.5} step={0.001} onChange={(v) => set('derivativesFunding', 'moderateThreshold', v)} />
        <NumberField label="Extreme threshold" tooltip="Funding rate above which market sentiment is extreme" value={df.extremeThreshold} min={0} max={1} step={0.001} onChange={(v) => set('derivativesFunding', 'extremeThreshold', v)} />
        <NumberField label="Score: moderate" tooltip="Score applied for moderate funding rate condition" value={df.scoreModerate} min={-100} max={100} step={1} onChange={(v) => set('derivativesFunding', 'scoreModerate', v)} />
        <NumberField label="Score: strong" tooltip="Score applied for strong funding rate condition" value={df.scoreStrong} min={-100} max={100} step={1} onChange={(v) => set('derivativesFunding', 'scoreStrong', v)} />
        <NumberField label="Score: extreme" tooltip="Score applied for extreme funding rate condition" value={df.scoreExtreme} min={-100} max={100} step={1} onChange={(v) => set('derivativesFunding', 'scoreExtreme', v)} />
      </Section>

      <Section title="Long/Short Ratio">
        <NumberField label="Extremely crowded shorts %" tooltip="L/S ratio below which shorts are considered extremely crowded" value={ls.extremelyCrowdedShortsPct} min={0} max={50} step={0.5} onChange={(v) => set('longShortRatio', 'extremelyCrowdedShortsPct', v)} />
        <NumberField label="Crowded shorts %" tooltip="L/S ratio below which shorts are crowded" value={ls.crowdedShortsPct} min={0} max={50} step={0.5} onChange={(v) => set('longShortRatio', 'crowdedShortsPct', v)} />
        <NumberField label="Crowded longs %" tooltip="L/S ratio above which longs are crowded" value={ls.crowdedLongsPct} min={50} max={100} step={0.5} onChange={(v) => set('longShortRatio', 'crowdedLongsPct', v)} />
        <NumberField label="Extremely crowded longs %" tooltip="L/S ratio above which longs are extremely crowded" value={ls.extremelyCrowdedLongsPct} min={50} max={100} step={0.5} onChange={(v) => set('longShortRatio', 'extremelyCrowdedLongsPct', v)} />
        <NumberField label="Score: moderate" tooltip="Score for moderately crowded positioning" value={ls.scoreModerate} min={-100} max={100} step={1} onChange={(v) => set('longShortRatio', 'scoreModerate', v)} />
        <NumberField label="Score: extreme" tooltip="Score for extremely crowded positioning" value={ls.scoreExtreme} min={-100} max={100} step={1} onChange={(v) => set('longShortRatio', 'scoreExtreme', v)} />
      </Section>

      <Section title="Fear &amp; Greed">
        <NumberField label="Extreme fear max" tooltip="Index value ≤ this is 'extreme fear'" value={fg.extremeFearMax} min={0} max={50} step={1} onChange={(v) => set('fearGreed', 'extremeFearMax', v)} />
        <NumberField label="Fear max" tooltip="Index value ≤ this is 'fear'" value={fg.fearMax} min={0} max={50} step={1} onChange={(v) => set('fearGreed', 'fearMax', v)} />
        <NumberField label="Greed min" tooltip="Index value ≥ this is 'greed'" value={fg.greedMin} min={50} max={100} step={1} onChange={(v) => set('fearGreed', 'greedMin', v)} />
        <NumberField label="Extreme greed min" tooltip="Index value ≥ this is 'extreme greed'" value={fg.extremeGreedMin} min={50} max={100} step={1} onChange={(v) => set('fearGreed', 'extremeGreedMin', v)} />
        <NumberField label="Score: moderate" tooltip="Score for moderate fear/greed signal" value={fg.scoreModerate} min={-100} max={100} step={1} onChange={(v) => set('fearGreed', 'scoreModerate', v)} />
        <NumberField label="Score: extreme" tooltip="Score for extreme fear/greed signal" value={fg.scoreExtreme} min={-100} max={100} step={1} onChange={(v) => set('fearGreed', 'scoreExtreme', v)} />
      </Section>

      <Section title="News Sentiment">
        <NumberField label="Score multiplier" tooltip="Multiplier applied to the raw sentiment score from news articles" value={ns.scoreMultiplier} min={0} max={5} step={0.1} onChange={(v) => set('newsSentiment', 'scoreMultiplier', v)} />
      </Section>

      <Section title="Order Book">
        <NumberField label="High liquidation ratio" tooltip="Bid/ask imbalance ratio above which order book signals high volatility risk" value={ob.highLiquidationRatio} min={0} max={5} step={0.01} onChange={(v) => set('orderBook', 'highLiquidationRatio', v)} />
        <NumberField label="Moderate liquidation ratio" tooltip="Bid/ask imbalance ratio above which order book signals moderate pressure" value={ob.moderateLiquidationRatio} min={0} max={5} step={0.01} onChange={(v) => set('orderBook', 'moderateLiquidationRatio', v)} />
        <NumberField label="Score: high volatility" tooltip="Score when order book imbalance indicates high liquidation risk" value={ob.scoreHighVolatility} min={-100} max={100} step={1} onChange={(v) => set('orderBook', 'scoreHighVolatility', v)} />
      </Section>

      <Section title="Macro BTC Dominance">
        <NumberField label="Dominance threshold %" tooltip="BTC dominance % above which BTC-favoring scores are applied" value={mac.threshold} min={0} max={100} step={0.5} onChange={(v) => set('macroBtcDominance', 'threshold', v)} />
        <NumberField label="Score: BTC when high dom" tooltip="Score applied to BTC when dominance is high" value={mac.scoreBtcWhenHigh} min={-100} max={100} step={1} onChange={(v) => set('macroBtcDominance', 'scoreBtcWhenHigh', v)} />
        <NumberField label="Score: alts when high dom" tooltip="Score applied to alt coins when BTC dominance is high" value={mac.scoreAltWhenHigh} min={-100} max={100} step={1} onChange={(v) => set('macroBtcDominance', 'scoreAltWhenHigh', v)} />
        <NumberField label="Score: BTC when low dom" tooltip="Score applied to BTC when dominance is low" value={mac.scoreBtcWhenLow} min={-100} max={100} step={1} onChange={(v) => set('macroBtcDominance', 'scoreBtcWhenLow', v)} />
        <NumberField label="Score: alts when low dom" tooltip="Score applied to alt coins when BTC dominance is low" value={mac.scoreAltWhenLow} min={-100} max={100} step={1} onChange={(v) => set('macroBtcDominance', 'scoreAltWhenLow', v)} />
      </Section>

      <Section title="Trailing Stop (R-units)">
        <NumberField label="Activation R" tooltip="MFE in R-units at which the trail first ratchets from the initial stop" value={tr.activationR} min={0.1} max={5} step={0.1} onChange={(v) => set('trail', 'activationR', v)} />
        <NumberField label="Step R" tooltip="Rung size: trail advances one rung per this much extra MFE" value={tr.stepR} min={0.1} max={2} step={0.1} onChange={(v) => set('trail', 'stepR', v)} />
        <NumberField label="Offset R" tooltip="Distance behind the current rung the trail sits while MFE is below the wider-offset threshold" value={tr.offsetR} min={0} max={2} step={0.1} onChange={(v) => set('trail', 'offsetR', v)} />
        <NumberField label="Wider offset activation R" tooltip="MFE threshold at which the wider offset kicks in (0 disables the second rung)" value={tr.widerOffsetActivationR} min={0} max={10} step={0.1} onChange={(v) => set('trail', 'widerOffsetActivationR', v)} />
        <NumberField label="Wider offset R" tooltip="Offset used once MFE crosses the wider-offset activation; larger = right-tail runners get more room" value={tr.widerOffsetR} min={0} max={3} step={0.1} onChange={(v) => set('trail', 'widerOffsetR', v)} />
      </Section>

      <Section title="Alignment">
        <NumberField label="Min score for non-zero" tooltip="Minimum absolute overall score before alignment can exceed zero" value={aln.minScoreForNonZero} min={0} max={50} step={1} onChange={(v) => set('alignment', 'minScoreForNonZero', v)} />
        <NumberField label="Contradiction threshold" tooltip="Score delta above which two dimensions are considered contradictory" value={aln.contradictionScoreThreshold} min={0} max={100} step={1} onChange={(v) => set('alignment', 'contradictionScoreThreshold', v)} />
        <NumberField label="Contradiction penalty mult" tooltip="Multiplier on the base penalty per contradicting dimension pair" value={aln.contradictionPenaltyMultiplier} min={0} max={5} step={0.01} onChange={(v) => set('alignment', 'contradictionPenaltyMultiplier', v)} />
        <NumberField label="Two-contradiction penalty" tooltip="Flat penalty applied when exactly two dimensions contradict" value={aln.twoContradictionPenalty} min={0} max={100} step={1} onChange={(v) => set('alignment', 'twoContradictionPenalty', v)} />
        <NumberField label="One-contradiction penalty" tooltip="Flat penalty applied when exactly one dimension contradicts" value={aln.oneContradictionPenalty} min={0} max={100} step={1} onChange={(v) => set('alignment', 'oneContradictionPenalty', v)} />
        <NumberField label="Output scale" tooltip="Scale factor applied to the final computed alignment value" value={aln.outputScale} min={0} max={5} step={0.01} onChange={(v) => set('alignment', 'outputScale', v)} />
        <NumberField label="Min output" tooltip="Minimum clamped alignment output (0–100)" value={aln.minOutput} min={0} max={100} step={1} onChange={(v) => set('alignment', 'minOutput', v)} />
        <NumberField label="Max output" tooltip="Maximum clamped alignment output (0–100)" value={aln.maxOutput} min={0} max={100} step={1} onChange={(v) => set('alignment', 'maxOutput', v)} />
      </Section>

      {/* Signal Labels — sub-tabs */}
      <div className="space-y-3">
        <h3 className="text-xs font-semibold text-text-secondary uppercase tracking-wider border-b border-surface-border pb-1">
          Signal Labels
        </h3>
        <div className="flex gap-2">
          {(['chop', 'bull', 'bear'] as LabelRegimeTab[]).map((t) => (
            <TabButton key={t} label={t.toUpperCase()} isActive={labelTab === t} onClick={() => setLabelTab(t)} />
          ))}
        </div>

        {labelTab === 'chop' && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-3">
            <NumberField label="Strong buy min score" tooltip="Minimum score to issue STRONG_BUY in CHOP/UNKNOWN regime" value={lbl.chop.strongBuyMinScore} min={0} max={100} step={1} onChange={(v) => setNested('signalLabels', 'chop', 'strongBuyMinScore', v)} />
            <NumberField label="Buy min score" tooltip="Minimum score to issue BUY in CHOP/UNKNOWN regime" value={lbl.chop.buyMinScore} min={0} max={100} step={1} onChange={(v) => setNested('signalLabels', 'chop', 'buyMinScore', v)} />
            <NumberField label="Strong sell max score" tooltip="Maximum score to issue STRONG_SELL in CHOP/UNKNOWN regime" value={lbl.chop.strongSellMaxScore} min={-100} max={0} step={1} onChange={(v) => setNested('signalLabels', 'chop', 'strongSellMaxScore', v)} />
            <NumberField label="Sell max score" tooltip="Maximum score to issue SELL in CHOP/UNKNOWN regime" value={lbl.chop.sellMaxScore} min={-100} max={0} step={1} onChange={(v) => setNested('signalLabels', 'chop', 'sellMaxScore', v)} />
            <NumberField label="Strong alignment min" tooltip="Minimum alignment for a strong signal" value={lbl.chop.strongAlignmentMin} min={0} max={100} step={1} onChange={(v) => setNested('signalLabels', 'chop', 'strongAlignmentMin', v)} />
            <NumberField label="Alignment min" tooltip="Minimum alignment for any directional signal" value={lbl.chop.alignmentMin} min={0} max={100} step={1} onChange={(v) => setNested('signalLabels', 'chop', 'alignmentMin', v)} />
          </div>
        )}

        {labelTab === 'bull' && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-3">
            <NumberField label="Strong sell max score" tooltip="BULL: raised SELL bar — counter-trend needs stronger evidence" value={lbl.bull.strongSellMaxScore} min={-100} max={0} step={1} onChange={(v) => setNested('signalLabels', 'bull', 'strongSellMaxScore', v)} />
            <NumberField label="Sell max score" tooltip="BULL: raised SELL bar — counter-trend needs stronger evidence" value={lbl.bull.sellMaxScore} min={-100} max={0} step={1} onChange={(v) => setNested('signalLabels', 'bull', 'sellMaxScore', v)} />
          </div>
        )}

        {labelTab === 'bear' && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-8 gap-y-3">
            <NumberField label="Strong buy min score" tooltip="BEAR: raised BUY bar — don't catch falling knives" value={lbl.bear.strongBuyMinScore} min={0} max={100} step={1} onChange={(v) => setNested('signalLabels', 'bear', 'strongBuyMinScore', v)} />
            <NumberField label="Buy min score" tooltip="BEAR: raised BUY bar — don't catch falling knives" value={lbl.bear.buyMinScore} min={0} max={100} step={1} onChange={(v) => setNested('signalLabels', 'bear', 'buyMinScore', v)} />
          </div>
        )}
      </div>
    </div>
  );
}
