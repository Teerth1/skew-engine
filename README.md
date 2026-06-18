# Skew Engine

**Skew Engine** is a robust Spring Boot 4 / Java 21 application specifically designed for **Options Trading & Volatility Analysis**. It leverages the Spring AI framework (Gemini) to provide intelligent market commentary, combining quantitative option metrics with qualitative news sentiment.

## Core Architecture

- **Core Application:** `SkewEngineApplication.java`
- **Data Ingestion:** Kafka-based option tick processing (`OptionTickConsumer.java`)
- **Broker Integrations:** `SchwabApiService.java` for market data, `AlpacaService.java` for execution
- **Simulation & Testing:** `BacktesterService.java`
- **AI & Insights:** `CommentaryService.java` providing Gemini-based market commentary

## Build & Run

Ensure you have Java 21 installed. 

- **Build Command:** `./mvnw clean install -DskipTests`
- **Test Command:** `./mvnw test`

## Feature Roadmap

### Phase 1: Java-Native News Layer
Implementing a native `NewsService` to fetch structured market news and sentiment. By caching news asynchronously, the engine avoids blocking live tick ingestion while enriching AI inputs.

### Phase 2: Sentiment and Agent Decision Layer
Integrating an `AgentDecisionService` that synthesizes option metrics (skew signal, IV, spot return) with recent news and current open positions. The AI layer outputs structured metrics (like `SentimentBand` and `AgentRating`) rather than raw text.

### Phase 3: Risk-Gated Trading Flow
Creating a strict barrier between signals and market execution. The `OptionTickConsumer` generates a `TradeIntent`, which is evaluated by a deterministic `RiskManagerService`. Risk rules include checking max open positions, maximum daily loss, minimum confidence, and maximum bid/ask spread before `OrderManagerService` places a trade.

### Phase 4: Audit and Learning Loop
Building comprehensive decision logging (`StrategyDecisionLog` / `TradeDecisionLog`). Every signal, AI decision, risk check, and outcome will be persistently logged to create a learning loop for future AI reflections.

### Phase 5: Deep Multi-Agent Integration
(Optional) Integrating external Python/LangGraph agents as standalone services via a narrow API. The Java application will provide market states, and the multi-agent system will return deeper, structured analyses while Java retains full control over risk and execution.

## Design Philosophy & Coding Standards
- **Risk First:** An LLM or agent must **never** directly execute a live order. All execution must pass deterministic risk checks.
- **Safety by Default:** All new trading features default to simulator or paper mode.
- **Testability:** Core trading decisions must be testable without requiring Kafka, JPA, or active broker connections.
- **Architecture:** Standard Spring Boot structure using constructor injection, keeping external API clients strictly isolated behind services.

---
*Built with Spring Boot 4.0.6 and Spring AI 2.0.0-RC1.*
