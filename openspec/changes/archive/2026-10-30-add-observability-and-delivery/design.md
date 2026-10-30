# Design

## Context

Estado atual (ver proposal.md, Why):

- `traceId` já nasce em toda requisição HTTP: Micrometer Tracing com bridge OpenTelemetry (`spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel`), sem exportador. O `traceId` vai para o MDC e para o ProblemDetail (`ProblemDetails`, decisão 9 da change `add-activation-request-api`).
- O envio ao provedor é assíncrono: `ActivationDispatchScheduler` (`@Scheduled`) chama `ActivationDispatcher.dispatchDue()`, que reserva um lote em transação curta, chama `ProviderGateway` fora de transação e grava o desfecho item a item. O contexto de trace do `POST` não chega a esse thread.
- `ProviderClient` é uma interface `@HttpExchange` registrada por `@ImportHttpServices`; o `RestClient` dela vem do `RestClient.Builder` do Boot, que já recebe a observação HTTP do cliente.
- Actuator expõe só `health`; `/actuator/health/**` é público na `SecurityConfiguration`; o resto exige token.
- Dependências entre pacotes hoje (levantadas no código): `api → application, domain`; `application → domain, infrastructure`; `infrastructure → domain`; `domain` → nada; `shared.web → activation.application` (exceções no handler).
- O `Dockerfile` já tem `HEALTHCHECK`; o serviço `app` do `compose.yaml` não declara o seu.

## Goals / Non-Goals

**Goals:**
- Um único `traceId` do `POST` até a chamada ao provedor, inclusive nas novas tentativas.
- Observabilidade só com o que o Boot 4 já auto-configura, mais um contador de domínio.
- Nenhuma mudança na API `/api/v1/**`.

**Non-Goals:**
- Coletor de traces, Prometheus ou Grafana no `compose.yaml` (exportação só configurável).
- Exportação de logs e de métricas por OTLP.
- Gauge do estoque por status (consulta ao banco); só contador de transições.
- Trace próprio para a reconciliação de solicitações presas: ela continua no trace do ciclo agendado e loga o `id` da solicitação.
- Devolver a `PENDING` as solicitações reservadas e ainda não enviadas quando o prazo de parada estoura: a reconciliação existente cuida delas.

## Decisions

**1. Contexto W3C `traceparent` gravado com a solicitação (migração `V5`).**
Na criação (`ActivationRequestService.create`, só quando o registro é novo), o serviço injeta o contexto do span atual em um `Map` com o `Propagator` do Micrometer Tracing e grava o valor da chave `traceparent` (`00-<traceId>-<spanId>-<flags>`, 55 caracteres) na coluna `trace_parent varchar(55) NULL`. A fábrica `ActivationRequest.create` recebe o valor como `@Nullable String`; o domínio só guarda o texto, sem depender de tracing. O retry idempotente (`200`) não altera o valor: vale o contexto da criação.
Alternativas:
- Trace novo no worker, correlação só pelo `id` no log. Rejeitada: não cumpre "propagado da requisição até a chamada ao provedor".
- Guardar só o `traceId`. Rejeitada: o `traceparent` completo traz também o span pai e a decisão de amostragem, e o `Propagator` lê e escreve o formato sem código próprio.
- Span link em vez de pai. Rejeitada para este caso: com pai, logs e header do provedor saem com o mesmo `traceId`, que é o que o enunciado pede; o span do envio fica no mesmo trace, horas depois se houver backoff (aceito, ver Riscos).

**2. Um span por solicitação no worker, filho do contexto gravado.**
`ClaimedRequest` ganha `traceParent`. Em `dispatchDue`, para cada item: se houver `traceParent`, `propagator.extract(Map.of("traceparent", valor), Map::get)` devolve um `Span.Builder` com o pai; senão, `tracer.nextSpan()`. O span (`activation.dispatch`, tag `activation.id`) é iniciado e colocado em escopo (`tracer.withSpan`) durante `gateway.activate` e `applyOutcome`, e encerrado no `finally`. Dentro do escopo:
- o MDC tem o `traceId` gravado, então os logs do `ProviderGateway` e do `applyOutcome` saem com ele;
- a observação HTTP do `RestClient` cria um span filho e injeta `traceparent` no header ao provedor.
Valor gravado inválido: o `extract` devolve um builder sem pai e o envio segue em trace novo (cenário "Solicitação sem contexto guardado"). O `Tracer` e o `Propagator` entram por construtor no `ActivationDispatcher`, que fica em `application`.
Alternativa: `Observation` do Micrometer com contexto pai. Rejeitada: a API de `Observation` não recebe um contexto remoto de forma direta; `Tracer` + `Propagator` é o caminho documentado para continuar um trace a partir de um carrier.

**3. Amostragem em 100% por padrão, configurável.**
O Boot amostra 10% por padrão. Fora da amostra o `traceId` continua existindo (MDC, ProblemDetail, header), mas o span não é exportado. `management.tracing.sampling.probability=${TRACING_SAMPLING_PROBABILITY:1.0}`: o desafio mostra traces completos; em produção a variável baixa a taxa. O `extract` respeita a flag gravada, então o envio segue a decisão do `POST`.

**4. Starter `spring-boot-starter-opentelemetry` com OTLP opcional.**
Substitui `spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel` (o starter traz os dois e o exportador OTLP; nome confirmado na documentação do Boot 4.1 e no Maven Central, versão gerida pelo BOM). Configuração:
- `management.tracing.export.otlp.enabled=${OTLP_TRACING_ENABLED:false}` e `management.opentelemetry.tracing.export.otlp.endpoint=${OTLP_TRACING_ENDPOINT:http://localhost:4318/v1/traces}`: o Boot 4.1.2 cria o exportador mesmo com endpoint vazio e a subida falha (`Invalid endpoint, must start with http://`), e o `spring.config.activate` não tem `on-property`; por isso a exportação tem chave própria, desligada por padrão. Confirmado por `OtlpExportIntegrationTests` (contexto sem bean `SpanExporter`).
- Se o starter trouxer o registro OTLP de métricas, `management.otlp.metrics.export.enabled=false`: métricas saem só pelo Prometheus.
Alternativa: Jaeger no Compose. Rejeitada pelo usuário nesta change: mais um serviço no smoke test do CI; o README explica como apontar para um coletor.

**5. Logs JSON nativos do Boot no formato Logstash.**
`logging.structured.format.console=logstash`. O formato Logstash copia as chaves do MDC para o nível de cima do JSON: os campos saem como `traceId` e `spanId`, o mesmo nome do ProblemDetail. ECS foi descartado porque renomeia campos e o nome igual ao do ProblemDetail facilita a busca por quem recebe o erro. Vale para todos os perfis e para os testes (o `OutputCaptureExtension` passa a ver JSON; os testes que buscam `traceId` no texto continuam válidos). Nenhum log novo recebe token, header `Authorization` nem payload completo.

**6. Métricas.**

| Pedido no enunciado | Métrica Prometheus | Origem |
|---|---|---|
| Latência da API | `http_server_requests_seconds` (`uri`, `method`, `status`, `outcome`) | Auto (observação do Spring MVC) |
| Latência e taxa de erro do provedor | `http_client_requests_seconds` (`uri="/provider/v1/activate"`, `status`, `outcome`, `exception`, `client_name`) | Auto (observação do `RestClient`); timeout aparece com `status="CLIENT_ERROR"` e `exception` |
| Solicitações por status | `activation_requests_transitions_total{status}` | Contador próprio |

- Histogramas: `management.metrics.distribution.percentiles-histogram.http.server.requests=true` e `...http.client.requests=true`, para p95/p99 no Prometheus (`histogram_quantile`).
- O rótulo `uri` é o template da rota (`/api/v1/requests/{id}`), nunca o valor: sem id nem `externalId` nos rótulos.
- Contador: classe `ActivationMetrics` em `activation.application`, com `MeterRegistry` por construtor. Registra os quatro status com zero na subida (séries existem antes do primeiro evento) e expõe `transitionedTo(ActivationStatus)`. Incrementado só depois do commit: no `create` quando o registro é novo (`PENDING`); no `claimDue` por item reservado (`PROCESSING`); no `applyOutcome` pelo status final gravado (`COMPLETED`, `FAILED` ou `PENDING`); no `reconcileStale` pelo status final de cada item. O resultado tardio descartado não conta.
- Por que contador e não gauge do banco: barato, soma certo entre instâncias e responde "quantas falharam nos últimos 5 min" com `rate()`. O estoque atual (ex.: `PENDING` acumulando) fica como melhoria futura no README.
- Dependência `io.micrometer:micrometer-registry-prometheus` (runtime, versão do BOM): é a que o start.spring.io adiciona para "Prometheus" no Boot 4.

**7. `/actuator/prometheus` público.**
`management.endpoints.web.exposure.include=health,prometheus` e `requestMatchers("/actuator/prometheus").permitAll()`. O Prometheus faz scrape sem token, e o conteúdo é agregado e sem dados de negócio (decisão 6). Em produção o acesso fica restrito pela rede (porta de gerenciamento separada com `management.server.port`, só na rede interna); isso vai para o README em Produção, sem implementar agora para não mudar portas do Compose e do CI.
Alternativa: exigir token com escopo de métricas. Rejeitada: o Prometheus precisaria de client credentials; complexidade sem ganho real para métricas agregadas.

**8. Encerramento gracioso.**
- `server.shutdown=graceful` (explícito, mesmo sendo o padrão) e `spring.lifecycle.timeout-per-shutdown-phase=${SHUTDOWN_TIMEOUT:30s}`.
- `spring.task.scheduling.shutdown.await-termination=true` e `spring.task.scheduling.shutdown.await-termination-period=${SHUTDOWN_TIMEOUT:30s}`: o agendador para de disparar ciclos e espera o ciclo em curso antes de os beans de dados serem destruídos.
- `compose.yaml`: `stop_grace_period: 40s` no `app` (acima do prazo da aplicação, para o Docker não matar o processo antes).
- Teste: contexto próprio criado com `SpringApplicationBuilder` (agendamento ligado, intervalo curto), WireMock com atraso de 2 s; ao chegar a requisição no WireMock, o teste fecha o contexto e verifica por JDBC direto no contêiner que a solicitação está `COMPLETED`.

**9. Health check do `app` no `compose.yaml`.**
Mesmo comando do `HEALTHCHECK` do `Dockerfile` (`wget` na readiness), declarado no serviço para ficar visível ao lado dos outros e independente da imagem. O smoke test do CI ganha um passo `curl -fsS localhost:8080/actuator/prometheus | grep http_server_requests_seconds`, depois da chamada autenticada.

**10. ArchUnit.**
`com.tngtech.archunit:archunit-junit5:1.5.1` (teste; fora do BOM do Boot, versão em `<archunit.version>`; 1.5.1 é a última no Maven Central). Teste `LayerDependencyRulesTest` em `src/test/java/academy/jairo/springdesafiotecnico/`, com `@AnalyzeClasses(packages = "academy.jairo.springdesafiotecnico", importOptions = DoNotIncludeTests.class)` e `layeredArchitecture().consideringOnlyDependenciesInLayers()`:
- `Api` (`..activation.api..`): não acessada por nenhuma camada;
- `Application` (`..activation.application..`): acessada só por `Api` (o `shared.web` fica fora das camadas e pode ler as exceções);
- `Infrastructure` (`..activation.infrastructure..`): acessada só por `Application`;
- `Domain` (`..activation.domain..`): acessada por todas, não acessa nenhuma.
O cenário "Violação quebra o build" é coberto aplicando a mesma regra (método estático reutilizável) a classes de fixture em `src/test/java/.../archfixture/activation/{domain,infrastructure}`, onde `domain` importa `infrastructure`, e verificando que `check` lança `AssertionError` citando a dependência. As fixtures ficam fora do `@AnalyzeClasses` principal pelo `DoNotIncludeTests`.
Alternativa: Spring Modulith. Rejeitada: verifica módulos, não camadas dentro do módulo, e traz runtime próprio.

**11. Testes por requisito.**

| Requisito | Teste |
|---|---|
| Correlação até o provedor | `TraceCorrelationIntegrationTests` (`@AutoConfigureTracing`, MockMvc + WireMock + Awaitility, worker chamado direto): `POST` com `traceparent` conhecido; `traceId` comparado ao header `traceparent` recebido pelo WireMock e ao log JSON do envio; retry com `500` depois `200`; linha gravada sem contexto |
| Logs JSON | `StructuredLoggingIntegrationTests` com `OutputCaptureExtension`: linha do erro `500` parseada como JSON com `traceId` igual ao do ProblemDetail; token inválido ausente do output |
| Métricas | `PrometheusMetricsIntegrationTests`, com a anotação de teste do Boot 4 que liga a exportação de métricas (o Boot a desliga em testes; nome confirmado na documentação na task) |
| OTLP | `OtlpExportIntegrationTests`: WireMock em `/v1/traces` recebe POST; contexto sem endpoint não tem exportador |
| Encerramento gracioso | `GracefulShutdownIntegrationTests` (decisão 8) |
| Camadas | `LayerDependencyRulesTest` (decisão 10) |
| Actuator | `HealthProbesIntegrationTests` ganha o caso de `/actuator/prometheus` sem token |

Como a resposta de criação não carrega `traceId` (só o ProblemDetail de erro tem), o teste de correlação lê o `traceId` gravado em `trace_parent` e também gera a requisição com um `traceparent` conhecido no header do `POST` (o Boot continua o trace do chamador), o que permite comparar com um valor fixo.

**12. Dependências (resumo para a regra do projeto).**

| Dependência | Escopo | Motivo |
|---|---|---|
| `spring-boot-starter-opentelemetry` | compile | Starter oficial do Boot 4 para tracing OTel com exportação OTLP; substitui as duas dependências atuais de tracing |
| `micrometer-registry-prometheus` | runtime | Formato e endpoint `/actuator/prometheus` |
| `archunit-junit5` 1.5.1 | test | Regras de camadas (Item 8, diferencial) |

## Risks / Trade-offs

- [Span do envio horas depois do `POST`, no mesmo trace, quando há backoff longo] → aceito; ferramentas de trace mostram o intervalo. O ADR 0006 registra a escolha de pai em vez de link.
- [Observação do `RestClient` do HTTP service client não registrar `http.client.requests` ou o `uri` sair com o host] → o teste de métricas falha; correção é aplicar `ObservationRestClientCustomizer` ao grupo `provider` por `RestClientHttpServiceGroupConfigurer`.
- [Boot criar exportador OTLP sem endpoint e logar erros de conexão] → teste do contexto sem endpoint (decisão 4) pega isso na task da dependência.
- [Lote de 10 × timeout de leitura de 5 s passa do prazo de parada de 30 s] → itens não enviados ficam `PROCESSING`; a reconciliação os devolve depois de `stale-after` (conta uma tentativa, mesma `Idempotency-Key`). Documentado no README.
- [Logs JSON pioram a leitura no terminal local] → README sugere `jq`; trocar o formato fica a uma propriedade.
- [`/actuator/prometheus` público] → só agregados sem dados de negócio; restrição por rede descrita em Produção.
- [Amostragem 100% em produção custa armazenamento no coletor] → variável `TRACING_SAMPLING_PROBABILITY`.

## Migration Plan

1. `V5__request_trace_parent.sql`: `ALTER TABLE activation_request ADD COLUMN trace_parent varchar(55);` — anulável, sem backfill; linhas antigas são enviadas em trace novo.
2. Deploy normal. Rollback: a versão anterior ignora a coluna (o Hibernate com `ddl-auto=validate` não reclama de coluna extra); a coluna pode ficar.
