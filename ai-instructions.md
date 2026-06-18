# Skew Engine AI Development Rules

## 1. Core Stack & Constraints
- **Language:** Java 21 (Use pattern matching, records, virtual threads where applicable).
- **Framework:** Spring Boot 3+ / 4.0.x. 
- **Database:** PostgreSQL (via Spring Data JPA).
- **Messaging:** Apache Kafka.

## 2. Spring AI Implementation Rules
- **Do NOT use:** `RestTemplate`, `WebClient`, or manual JSON string building for LLM calls.
- **Dependency:** Use `spring-ai-starter-model-openai` (or relevant Gemini equivalent via Spring AI BOM).
- **Client:** Use the `ChatClient` fluent builder API (not the older `AiClient`).
- **Structured Output:** ALWAYS map LLM responses directly to Java Records using `BeanOutputConverter`.
- **Tool Calling:** Use the `@Tool` annotation for any external API or DB fetch the LLM needs to make.

## 3. Kafka Concurrency Rules
- **Ingestion:** High-throughput listeners must NEVER block.
- **Async Processing:** Heavy LLM logic must be pushed to a separate topic and consumed by a `@KafkaListener` utilizing a custom `ThreadPoolTaskExecutor` (or Java 21 Virtual Threads). 

## 4. Resilience4j Standards (When Applicable)
- **Dependency:** `io.github.resilience4j:resilience4j-spring-boot3`.
- **Usage:** Use `@CircuitBreaker` and `@Retry` annotations on external API calls (Alpha Vantage, Schwab, Alpaca).
- **Configuration:** Prefer `application.yml` for sliding window size, failure rate thresholds, and timeout bounds over programmatic Java config. Always implement a `fallbackMethod`.