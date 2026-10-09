# 0002 - Kotlin on Spring Boot 4.1

Status: accepted - 2026-09-26

## Context
The owner requires Java or Kotlin with Spring Boot. Spring Boot 3.5 reached OSS end of life in June
2026; Spring AI 2.x and the current Spring MCP client require the Boot 4 baseline.

## Decision
Kotlin 2.3 on JDK 21, Spring Boot 4.1.1, Spring MVC with virtual threads rather than WebFlux.

## Alternatives
- Java 21: fine, but Kotlin data classes, sealed hierarchies and value classes make the domain model
  materially shorter and safer.
- WebFlux: LangGraph4j and gitlab4j are blocking; virtual threads give the throughput needed without a
  reactive programming model.

## Consequences
- Jackson 3 (`tools.jackson`) and renamed starters (`spring-boot-starter-webmvc`) - see conventions.
- `kotlin-spring` and `kotlin-jpa` compiler plugins open classes for Spring/Hibernate in adapters only.
