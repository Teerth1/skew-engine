# Codex Agents Configuration

## Project Metadata
- **Name:** Skew Engine
- **Type:** Spring Boot / Java 21
- **Domain:** Options Trading & Volatility Analysis
- **Framework:** Spring Boot 4.0.6, Spring AI 2.0.0-RC1

## Build & Test
- **Build Command:** `./mvnw clean install -DskipTests`
- **Test Command:** `./mvnw test`
- **Runtime:** Java 21

## Architecture
- **Core:** `SkewEngineApplication.java`
- **Consumer:** `OptionTickConsumer.java` (Kafka-based option tick processing)
- **Service:** `SchwabApiService.java`, `AlpacaService.java`, `BacktesterService.java`
- **AI:** `CommentaryService.java` (Gemini-based market commentary)

## Planned Feature Roadmap

### Phase 1: Java-Native News Layer
- Add `NewsService` to fetch recent ticker-specific and global macro news.
- Prefer Java-native integrations before adding a Python dependency.
- Initial providers to consider:
  - Alpha Vantage News Sentiment API for structured market news and sentiment.
  - Yahoo Finance-style news only if a reliable Java integration or controlled HTTP parser is used.
- Add `NewsArticle` entity with fields:
  - `symbol`
  - `title`
  - `source`
  - `url`
  - `publishedAt`
  - `summary`
  - `provider`
  - `rawSentiment`
- Add `NewsArticleRepository`.
- Cache news responses to avoid repeated API calls during live tick ingestion.
- Never block Kafka ingestion on slow news APIs; use async refresh or precomputed cached news.

### Phase 2: Sentiment And Agent Decision Layer
- Add Java enums inspired by TradingAgents structured outputs:
  - `SentimentBand`: `BULLISH`, `MILDLY_BULLISH`, `NEUTRAL`, `MIXED`, `MILDLY_BEARISH`, `BEARISH`
  - `AgentRating`: `BUY`, `OVERWEIGHT`, `HOLD`, `UNDERWEIGHT`, `SELL`
- Add `NewsSentimentService` to convert recent news into a structured sentiment report.
- Add `AgentDecisionService` that combines:
  - skew signal type
  - spot return
  - skew change
  - put/call IV
  - recent news
  - current open positions
  - backtest context when available
- AI prompts must include only real market/news inputs and should return structured JSON when possible.
- LLM output may advise, explain, or score a trade, but must not directly place orders.

### Phase 3: Risk-Gated Trading Flow
- Add `TradeIntent` as the handoff object between signal detection and execution.
- Add `RiskManagerService` to approve or reject every trade intent using deterministic Java rules.
- Required risk checks before any paper or live order:
  - max open positions
  - max daily loss
  - max trades per day
  - market-hours check
  - stale-data check
  - max bid/ask spread
  - minimum confidence threshold
  - emergency kill switch
- Add `OrderManagerService` as the only component allowed to call Alpaca order endpoints.
- `OptionTickConsumer` should eventually create trade intents, not submit orders directly.

### Phase 4: Audit And Learning Loop
- Add persistent decision logging for every signal:
  - signal inputs
  - news snapshot
  - AI/agent decision
  - risk approval or rejection
  - broker response
  - entry and exit prices
  - realized P&L
- Add a `StrategyDecisionLog` or `TradeDecisionLog` table.
- Add outcome reflection after trades close so future commentary can reference actual prior results.
- Backtests should write to a run-scoped model instead of deleting all prior trade logs.

### Phase 5: Optional TradingAgents Integration
- Do not copy the full Python `agents` folder into this Java repo.
- TradingAgents is Apache-2.0 licensed, but it is a Python/LangGraph research framework with a separate runtime model.
- If deeper multi-agent analysis is needed, run TradingAgents as a separate Python service or Docker container.
- Spring Boot should call that service through a narrow API:
  - request: market state, skew signal, recent news, current positions
  - response: structured rating, confidence, rationale, risk notes
- Java remains responsible for risk checks, order execution, and audit logging.

## Coding Standards
- Use constructor injection for all Spring components.
- Follow standard Spring Boot project structure.
- Maintain high test coverage for trading logic.
- Ensure all AI prompts are grounded in real market data.
- Keep external API clients isolated behind services.
- Keep trading decisions testable without Kafka, JPA, or broker APIs.
- Never let an LLM or agent directly execute live orders.
- Default all new trading features to simulator or paper mode.

## Agent Skills
- `trading-expert`: Specialized in options Greeks and volatility skew.
- `spring-boot-pro`: Expert in Spring Boot 4 and Spring AI.
