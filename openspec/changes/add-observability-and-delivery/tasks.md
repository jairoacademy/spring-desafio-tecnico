# Tasks

## 1. Regras de camadas

- [x] 1.1 Adicionar `archunit-junit5` 1.5.1 em teste com `<archunit.version>` (design, decisões 10 e 12); criar `LayerDependencyRulesTest` com a regra de camadas da decisão 10 exposta como método estático e as fixtures em `src/test/java/.../archfixture/activation/{domain,infrastructure}` (classe de `domain` importando `infrastructure`); casos "Código atual respeita as regras" e "Violação quebra o build" (`AssertionError` citando a dependência); verificar com `./mvnw verify`

## 2. Logs JSON e tracing com OpenTelemetry

- [x] 2.1 Trocar `spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel` por `spring-boot-starter-opentelemetry` (decisão 4); propriedades `management.tracing.sampling.probability=${TRACING_SAMPLING_PROBABILITY:1.0}` e `management.opentelemetry.tracing.export.otlp.endpoint=${OTLP_TRACING_ENDPOINT:}`; desligar exportação OTLP de métricas se o starter a trouxer; criar `OtlpExportIntegrationTests`: sem endpoint o contexto sobe sem exportador de spans e os probes respondem `200`; com endpoint num WireMock, uma chamada à API faz chegar `POST /v1/traces` (Awaitility); verificar com `./mvnw verify` que os testes de `traceId` existentes continuam passando
- [x] 2.2 Ligar `logging.structured.format.console=logstash` (decisão 5); criar `StructuredLoggingIntegrationTests` com `OutputCaptureExtension`: cenário "Log de erro com traceId" (linha parseada como JSON com `level` `ERROR` e `traceId` igual ao do ProblemDetail) e "Token fora dos logs" (`Bearer` inválido, `401`, valor ausente do output); ajustar testes que leem o log como texto, se algum quebrar; atualizar o README (logs em JSON, campos `traceId`/`spanId`, dica de `jq` para leitura local); verificar com `./mvnw verify`

## 3. Correlação até o provedor

- [x] 3.1 Criar `V5__request_trace_parent.sql` (coluna `trace_parent varchar(55)` anulável, plano de migração); `ActivationRequest.create` recebe `@Nullable String traceParent` e a entidade ganha `getTraceParent()`; ajustar os chamadores; `ActivationRequestService.create` injeta o contexto do span atual com o `Propagator` e grava o `traceparent` só em registro novo (decisão 1); teste em `ActivationRequestRepositoryTests` gravando e relendo o valor e teste de API (`@AutoConfigureTracing`) com `POST` carregando `traceparent` conhecido e a coluna gravada com o mesmo `traceId`; verificar com `./mvnw verify`
- [x] 3.2 `ClaimedRequest` ganha `traceParent`; `ActivationDispatcher` recebe `Tracer` e `Propagator` e abre por item o span `activation.dispatch` filho do contexto gravado, ou novo quando ausente ou inválido, em escopo durante `gateway.activate` e `applyOutcome` (decisão 2); criar `TraceCorrelationIntegrationTests` (WireMock + Awaitility, worker chamado direto) com os quatro cenários de "Correlação da requisição até o provedor": header `traceparent` no WireMock com o `traceId` do `POST`, log JSON do envio com esse `traceId` após `500`, retry `500` → `200` no mesmo trace, linha sem contexto enviada com `traceparent` válido; verificar com `./mvnw verify`

## 4. Métricas para Prometheus

- [x] 4.1 Adicionar `micrometer-registry-prometheus` (runtime), `management.endpoints.web.exposure.include=health,prometheus`, histogramas de `http.server.requests` e `http.client.requests` e `permitAll` para `/actuator/prometheus` na `SecurityConfiguration` (decisões 6 e 7); acrescentar a `HealthProbesIntegrationTests` o cenário "Endpoint de métricas exposto" (`200` sem token) e manter o de `/actuator/env` `404`; criar `PrometheusMetricsIntegrationTests` com "Latência da API" e "Sem identificadores nos rótulos"; verificar com `./mvnw verify`
- [x] 4.2 Criar `ActivationMetrics` (contador `activation.requests.transitions{status}` com os quatro status pré-registrados) e incrementar depois do commit em `create`, `claimDue`, `applyOutcome` e `reconcileStale` (decisão 6); acrescentar a `PrometheusMetricsIntegrationTests` os cenários "Latência e erros do provedor" (`http_client_requests_seconds` com `uri="/provider/v1/activate"` e `status="500"`; se faltar a observação no cliente do provedor, aplicar a correção descrita em Riscos) e "Solicitações por status"; teste unitário do contador com `SimpleMeterRegistry` cobrindo que o resultado tardio descartado não conta; atualizar o README (endpoint, tabela de métricas da decisão 6 e exemplos de consulta PromQL para p95 e taxa de erro do provedor); verificar com `./mvnw verify`

## 5. Encerramento gracioso e entrega

- [ ] 5.1 Propriedades de encerramento gracioso da decisão 8 com `${SHUTDOWN_TIMEOUT:30s}`; criar `GracefulShutdownIntegrationTests` (contexto próprio via `SpringApplicationBuilder`, WireMock com atraso de 2 s, fechar o contexto com a chamada em andamento, conferir `COMPLETED` por JDBC no contêiner); atualizar o README (variável `SHUTDOWN_TIMEOUT` e o que acontece com itens não enviados quando o prazo estoura); verificar com `./mvnw verify`
- [ ] 5.2 `compose.yaml`: health check do `app` igual ao do `Dockerfile` e `stop_grace_period: 40s` (decisões 8 e 9); passo novo no job `compose-smoke` do CI conferindo `http_server_requests_seconds` em `/actuator/prometheus`; request `Métricas` na coleção Bruno; atualizar o README (variáveis `TRACING_SAMPLING_PROBABILITY` e `OTLP_TRACING_ENDPOINT`, como exportar traces para um coletor, `/actuator/prometheus` restrito pela rede em Produção); verificar com `docker compose up --build --wait` e os mesmos comandos do passo novo do CI

## 6. Decisões e verificação final

- [ ] 6.1 Escrever `docs/adr/0006-observabilidade.md` (`traceparent` gravado e span filho no worker, pai em vez de link, amostragem, logs Logstash, métricas e contador de transições, Prometheus público, OTLP opcional); atualizar a seção "Origem do `traceId`" do README e a lista de ADRs; rodar `./mvnw verify` e verificar que passa

## Workflow follow-up

- `openspec validate add-observability-and-delivery --strict` e commit `docs(spec): proposta add-observability-and-delivery`.
- Depois da implementação: `/opsx:archive`, commit `docs(spec): arquiva add-observability-and-delivery`, PR para `main` com merge commit e `[x]` na Etapa 6 de `docs/roteiro.md`.
