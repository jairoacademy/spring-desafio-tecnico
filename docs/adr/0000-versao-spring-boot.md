# ADR 0000 — Versão do Spring Boot

**Status:** aceito

## Contexto

O enunciado aponta a linha 3.x do Spring Boot como diferencial. O suporte open source do Spring Boot 3.5, última versão da linha 3.x, terminou em 30/06/2026. Começar um serviço novo em uma linha sem suporte seria dívida técnica desde o primeiro dia: sem correções de segurança gratuitas e com uma migração de versão maior já contratada.

## Decisão

Usar Spring Boot 4.1.2, com Java 21. A linha 4 traz Spring Framework 7, Spring Security 7, Hibernate 7, Jakarta EE 11 e Jackson 3.

## Consequências

- O código segue as convenções do Boot 4, que diferem das do 3.x:
  - starters modulares (`spring-boot-starter-webmvc` em vez de `spring-boot-starter-web`) e starters de teste por módulo (`spring-boot-starter-webmvc-test`, `spring-boot-starter-actuator-test` etc.);
  - `@MockitoBean` / `@MockitoSpyBean` no lugar de `@MockBean` / `@SpyBean`, que foram removidos;
  - `@AutoConfigureMockMvc` explícito com `@SpringBootTest`;
  - Jackson 3 em `tools.jackson.databind` (as anotações continuam em `com.fasterxml.jackson.annotation`);
  - Testcontainers 2 (`org.testcontainers.postgresql.PostgreSQLContainer`) com `@ServiceConnection`.
- Recursos novos ficam disponíveis sem dependência extra: null-safety com JSpecify, `@Retryable` nativo do Framework 7 e clientes HTTP declarativos com `@HttpExchange` e `@ImportHttpServices`.
- Há menos material de referência e exemplos do Boot 4 do que do 3.x. Nomes de starters e pacotes são confirmados na documentação oficial antes de serem usados.
- Atende ao requisito obrigatório do enunciado (Spring Boot) e abre mão do diferencial "versão 3.x" de forma justificada.
