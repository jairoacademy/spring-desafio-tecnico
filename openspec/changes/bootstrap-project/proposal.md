# Proposal

## Why

O projeto já compila e sobe com Spring Boot 4.1.2, PostgreSQL, Flyway e Testcontainers, mas ainda não atende ao item 11 do enunciado (Dockerfile) nem aos diferenciais de ambiente, CI e observabilidade básica. Fechar essa base agora dá a todas as changes seguintes um pipeline verde, um ambiente local de um comando e um mock do provedor pronto para a integração (item 5).

## What Changes

- Adicionar Spring Boot Actuator com os grupos de health `liveness` e `readiness` habilitados explicitamente, expondo apenas o endpoint `health`.
- Adicionar `package-info.java` com `@NullMarked` (JSpecify) no pacote raiz da aplicação.
- Criar o ADR `docs/adr/0000-versao-spring-boot.md`, justificando Spring Boot 4.1.2 em vez da linha 3.x sugerida pelo enunciado.
- Criar `Dockerfile` multi-stage: build com JDK, imagem final só com JRE, camadas extraídas com `jarmode=tools`, usuário não-root, `MaxRAMPercentage`, configuração por variáveis de ambiente e `HEALTHCHECK` na readiness.
- Ampliar o `compose.yaml` (o nome é mantido) com os serviços `app` e `wiremock`, ao lado do `postgres`, todos com health check. O WireMock já vem com um stub de sucesso para `POST /provider/v1/activate`.
- Criar workflow do GitHub Actions que roda `./mvnw verify` e um smoke test com `docker compose up --wait`.
- Atualizar o README: como subir tudo com Docker Compose, por que o arquivo se chama `compose.yaml` (e cumpre o `docker-compose.yml` da seção Entrega), endpoints de health e como executar em produção.

Fora do escopo: migração Flyway V1 (a primeira tabela nasce na change `add-activation-request-api`), métricas Prometheus e logs JSON (change `add-observability-and-delivery`) e o uso do WireMock pela aplicação (change `add-provider-integration`).

## Capabilities

### New Capabilities
- `operations`: como o serviço é verificado, empacotado e executado: probes de saúde, imagem de contêiner, ambiente local com Docker Compose e pipeline de CI.

### Modified Capabilities

Nenhuma.

## Impact

- `pom.xml`: novo `spring-boot-starter-actuator` e o starter de teste correspondente. O JSpecify já chega de forma transitiva pelo `spring-core` (`org.jspecify:jspecify:1.0.1`), sem dependência nova.
- `src/main/resources/application.properties`: configuração do Actuator.
- `src/main/java/.../package-info.java` e um teste de integração para as probes.
- Novos arquivos: `Dockerfile`, `.dockerignore`, `wiremock/mappings/`, `.github/workflows/ci.yml`, `docs/adr/0000-versao-spring-boot.md`.
- `compose.yaml` e `README.md` alterados.
