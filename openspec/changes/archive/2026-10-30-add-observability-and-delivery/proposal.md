# Proposal

## Why

O serviço já tem `traceId` por requisição (Micrometer Tracing com bridge OpenTelemetry), mas o envio ao provedor roda no worker, em outro thread e outro momento: hoje o `traceId` do `POST` se perde ali, e o enunciado pede correlação "da requisição até a chamada ao provedor" (Observabilidade básica). Também faltam os itens avançados de observabilidade (métricas para Prometheus, logs JSON, exportação de traces), o encerramento gracioso do worker e a verificação automática das regras de camadas (Item 8, diferencial ArchUnit). É a última change antes do fechamento.

## What Changes

- Correlação ponta a ponta: o contexto de trace W3C (`traceparent`) do `POST` é gravado com a solicitação (migração Flyway `V5`). O worker continua esse trace ao enviar: os logs do envio têm o mesmo `traceId` do `POST`, e o provedor recebe o header `traceparent` com esse `traceId`.
- Logs estruturados em JSON pelo suporte nativo do Boot (`logging.structured.format.console`), com `traceId` e `spanId` em todo log emitido dentro de um trace.
- Métricas Micrometer em `/actuator/prometheus`: latência da API (`http.server.requests`), latência e erros do provedor (`http.client.requests` do cliente do provedor) e solicitações por status (contador de transições `activation.requests.transitions{status}`).
- Endpoint `/actuator/prometheus` exposto sem token (só métricas agregadas, sem dados de negócio); os demais endpoints sensíveis continuam fechados.
- Tracing com OpenTelemetry pelo starter do Boot 4 (`spring-boot-starter-opentelemetry`), com exportação OTLP configurável por variável de ambiente e desligada por padrão. Sem coletor no Compose.
- Encerramento gracioso: o servidor HTTP termina as requisições em andamento e o agendador espera o ciclo do worker em curso antes de parar.
- `compose.yaml`: health check explícito do serviço `app` e `stop_grace_period` coerente com o encerramento gracioso. Smoke test do CI passa a verificar `/actuator/prometheus`.
- Teste ArchUnit com as regras de dependência entre `api`, `application`, `domain` e `infrastructure`.
- README e ADR atualizados (nova ADR 0006 de observabilidade).

## Capabilities

### New Capabilities
<!-- nenhuma: observabilidade e entrega pertencem a `operations` (docs/plano-sdd-openspec.md, seção 2) -->

### Modified Capabilities
- `operations`: a exposição do Actuator passa a incluir `prometheus`; o ambiente Compose e o CI passam a verificar métricas e health check do `app`. Novos requisitos: correlação até o provedor, logs JSON com `traceId`, métricas pedidas, exportação OTLP configurável, encerramento gracioso e regras de camadas verificadas por ArchUnit.

## Impact

- **Código**: `ActivationRequest` ganha o campo `traceParent`; `ActivationRequestService` grava o contexto na criação; `ActivationDispatcher` abre um span filho desse contexto por solicitação e conta as transições; `SecurityConfiguration` libera `/actuator/prometheus`.
- **Banco**: migração `V5__request_trace_parent.sql` com coluna `trace_parent` anulável (linhas antigas ficam sem contexto e o worker abre um trace novo para elas).
- **Dependências**: `spring-boot-starter-opentelemetry` substitui `spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel`; `micrometer-registry-prometheus` (runtime); `archunit-junit5` (teste).
- **Configuração**: `application.properties` (logs JSON, exposição, histogramas, shutdown, OTLP), `compose.yaml`, `.github/workflows/ci.yml`.
- **API pública**: sem mudança nos endpoints `/api/v1/**`.
- **Docs**: README (observabilidade, variáveis, como ver métricas e exportar traces), `docs/adr/0006-observabilidade.md`.
