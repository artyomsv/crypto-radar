# Deep Learning & LLMs in Trading

> LSTMs, transformers, and GPT-4-class language models have been pitched as the next-generation prediction engines for financial markets. The empirical record after roughly a decade of academic attempts is consistent: they don't reliably beat well-designed simpler models for next-bar return prediction, and the gap between research-paper performance and live-trading performance is enormous.

## Definition

### LSTM (Long Short-Term Memory) networks

A recurrent neural network variant proposed by Hochreiter & Schmidhuber (1997). The architecture maintains a "cell state" that propagates through time, gated by learned input/forget/output gates. Theoretically capable of capturing long-range temporal dependencies that vanilla RNNs cannot.

Standard application to trading: feed `T` past bars of `K` features (returns, vol, technical indicators) into the LSTM, output a `next-bar direction` or `next-bar return` prediction. Loss: MSE for regression, cross-entropy for classification.

### Transformers / Attention-based models

Vaswani et al. (2017) proposed the Transformer, replacing recurrence with multi-head self-attention. Each output position attends over all input positions, with learned attention weights. Scales better than LSTM to long sequences and parallelizes during training.

Trading applications: same input/output structure as LSTM, just with attention instead of recurrence. Variants like the **Temporal Fusion Transformer** (Lim et al. 2021) combine attention with explicit gating for multi-horizon forecasting.

### LLMs / GPT-4-class models

Generative language models trained on internet-scale text. Three trading applications:

1. **News sentiment**: classify headlines / articles as bullish / bearish / neutral, optionally with magnitude. GPT-4-class models do this near-human-quality, much better than older keyword-based or fine-tuned BERT classifiers.
2. **Discretionary-analyst replication**: feed a model the full market context (technicals + flow + news) and ask "what would you do?" The LLM produces an analyst-style narrative that may include actionable views.
3. **Synthetic feature generation**: ask an LLM to extract structured features (e.g. "is the article about ETF flows? regulatory news? exchange hack?") from unstructured text.

## When it works

- **Sentiment classification with sparse labeled data.** LLM zero-shot classification of crypto news articles, post-2023, beats older models by 5–15 percentage points on standard benchmarks. The improvement is real and operationally usable.
- **Long-horizon forecasting with strong features.** TFT-class transformers do beat ARIMA / VAR baselines on multi-step forecasting when the feature set already encodes most of the variance. The model is squeezing residual signal from a well-prepped problem.
- **Specific anomaly detection.** LSTMs trained to predict next-bar's vol from historical vol features can flag regime changes earlier than threshold-based detectors. Used as a confidence input rather than a primary signal.
- **Cross-modal feature extraction.** Transformers that ingest order-book snapshots + trade tape + funding rate produce learned representations that downstream linear models can use. The neural net is doing feature engineering, not prediction.

## When it fails

- **Next-bar direction prediction.** The most-attempted application; consistently disappoints. Multiple surveys (Sezer et al. 2020, Jiang 2021, Kumbure et al. 2022) document that LSTM/Transformer direction classifiers achieve test-set accuracy 50–55% on equity returns — barely above chance, and often below simple TA-based baselines after transaction costs.
- **Out-of-sample collapse.** Models that show R² > 0.3 on validation set show R² < 0.05 on a held-out future period. This is the **non-stationarity tax**: the data-generating process changes faster than the model can adapt.
- **Look-ahead bias in feature construction.** Many published "deep learning for trading" results use features that include subtle look-ahead (e.g. day's high computed from the full day at minute 5). Reproducing without the leak destroys the apparent edge.
- **No causal-explanation guarantee.** A model that achieves 60% accuracy on a test set is selecting for some signal in the data, but the signal may be a market-microstructure artifact, a particular venue's order-routing pattern, or a property of the backtest framework. Live-trading the model exposes all of these.
- **Cost dominance.** Even when a model achieves a small statistical edge (say, 53% direction accuracy with 0.5R/0.5R risk-reward), transaction costs at typical crypto perp fee levels (~0.06% taker round-trip on Bybit) eat the edge. The model is profitable in zero-fee simulation and unprofitable in reality.
- **LLM hallucination on numerical reasoning.** GPT-4-class models hallucinate confidently when asked to compute returns, ratios, or compare specific numbers. They cannot reliably parse a technical-indicator table and produce correct numerical conclusions. Use them for unstructured-text tasks (sentiment, summary), not for math.
- **Cost of inference.** Per-call cost of GPT-4-tier models is non-trivial for high-frequency systems. A model called per signal at $0.05 / call across 1000 signals/day is $50/day — not catastrophic for a high-value product, prohibitive for a research experiment.
- **Generation stochasticity.** LLMs produce different outputs on identical inputs across calls (temperature > 0). Reproducibility requires either temperature=0 (which still has some variation due to floating-point non-determinism) or full input-output logging.

## What we do today (in projectr-x)

Two limited uses of LLMs, no LSTM/Transformer for primary signal generation:

### 1. News sentiment via `news-service`

`news-service` ingests RSS feeds (CoinDesk + others) and applies sentiment scoring. The scoring uses a smaller, deterministic model (not GPT-4-class) tuned for crypto headlines. The output feeds the `Sentiment` dimension in `SignalEngine`'s 6-dimension scorer.

We deliberately chose a smaller, faster, deterministic model over GPT-4-class for sentiment because:
- The cadence requires sub-second classification per article (we ingest 100+ articles/day).
- Reproducibility matters — the same article processed twice should yield the same sentiment.
- The classification task is well-defined enough that a smaller model achieves adequate quality.

### 2. On-demand analyst pass via `/api/signals/{symbol}/ai-analysis`

`SignalResource.fetchAiAnalysis` calls Gemini (Google's GPT-4-class model) with the full raw-data context for a signal. Returns a narrative analyst-style verdict. This is **on-demand, user-triggered, not used in signal generation** — purely a "what would a human analyst say?" feature for the dashboard.

The response is stored on the `signal_outcomes.ai_analysis` column for the symbol's most recent signal. Not surfaced as a feature into subsequent signals — we deliberately keep the LLM out of the scoring stack to maintain interpretability and reproducibility of the primary signal.

### Why no LSTM / Transformer for primary signals

Same three reasons as VAR (`04-vector-autoregression.md`):

1. **Data sparsity per regime.** We don't have enough per-regime training data to fit a transformer without massive overfitting risk.
2. **Better-validated alternatives.** The dimension scoring stack + outcome-driven gates (`SymbolPerformanceGate`, `DetectorConfluenceCheck`) is interpretable, debuggable, and producing measurable improvements with each version. Switching to a neural-net pipeline would substitute uncertainty for clarity.
3. **Operational complexity.** A production neural model requires model registry, A/B comparison infrastructure, drift detection, retraining pipelines — none of which the project has. Adding that infrastructure pays off only if the model is significantly better than the linear baseline, which the literature suggests it usually isn't for next-bar prediction.

If/when we add neural models, the natural first place is **anomaly detection on order-book microstructure** — a problem where the input data is rich and well-structured, the loss function is clear (detect manipulation / liquidity events), and the output is a confidence input rather than a primary signal.

## Sources

1. **Hochreiter, Schmidhuber (1997), "Long Short-Term Memory."** *Neural Computation* 9(8). https://www.bioinf.jku.at/publications/older/2604.pdf — The original LSTM paper.
2. **Vaswani et al. (2017), "Attention Is All You Need."** *NeurIPS*. https://arxiv.org/abs/1706.03762 — The Transformer paper that defined the architecture behind modern LLMs.
3. **Sezer, Gudelek, Ozbayoglu (2020), "Financial time series forecasting with deep learning: A systematic literature review: 2005–2019."** *Applied Soft Computing* 90. https://doi.org/10.1016/j.asoc.2020.106181 — Survey of 100+ papers; concludes deep-learning gains over simpler baselines are modest and often vanish after costs.
4. **Jiang (2021), "Applications of deep learning in stock market prediction: Recent progress."** *Expert Systems with Applications* 184. https://doi.org/10.1016/j.eswa.2021.115537 — Newer survey; similar conclusion about modest real-world gains.
5. **Lopez de Prado, *Advances in Financial Machine Learning* (2018), Chapter 8.** Critique of naive ML application to finance; sample-size, leakage, and overfitting risks. The standard reference for "why most ML-for-trading papers don't replicate."
6. **Lim et al. (2021), "Temporal Fusion Transformers for interpretable multi-horizon time series forecasting."** *International Journal of Forecasting* 37(4). https://doi.org/10.1016/j.ijforecast.2021.03.012 — TFT architecture; the state-of-the-art for principled deep forecasting.
7. **Krishnamurthy, Mishra (2024), "Can ChatGPT Predict Stock Returns? Evidence from a Large Language Model."** Working paper. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4412788 — Empirical study of GPT-class models for return prediction. Findings: zero alpha for direct prediction, modest signal for sentiment derivation.
8. **Lopez-Lira, Tang (2023), "Can ChatGPT Forecast Stock Price Movements? Return Predictability and Large Language Models."** https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4412788 — Companion paper showing LLM news-sentiment scores have predictive content above traditional sentiment models.
