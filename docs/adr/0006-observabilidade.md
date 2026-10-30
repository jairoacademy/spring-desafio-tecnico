# ADR 0006 — Observabilidade: traceparent gravado, métricas Prometheus, logs JSON e OTLP opcional

**Status:** aceito

## Contexto

O envio ao provedor roda no worker, em outro thread e muito depois do `POST`. O enunciado pede correlação "da requisição até a chamada ao provedor" e, como itens avançados, métricas, logs estruturados e exportação de traces. O `traceId` já nascia em toda requisição (ADR 0000, Boot 4), mas se perdia na passagem para o worker.

## Decisão

- **`traceparent` gravado com a solicitação.** Na criação, o contexto W3C do span atual vai para a coluna `trace_parent` (migração `V5`, anulável, sem backfill). O retry idempotente não o altera. O worker abre um span `activation.dispatch` por item, filho desse contexto, e o mantém em escopo durante o envio e a gravação do desfecho: os logs e o header `traceparent` enviado ao provedor saem com o `traceId` do `POST`, inclusive nas novas tentativas. Sem contexto gravado, ou com valor inválido, o envio abre um trace novo. Descartados: trace novo no worker com correlação só pelo `id` no log (não cumpre a propagação); guardar só o `traceId` (perde o span pai e a decisão de amostragem, e o `Propagator` já lê e escreve o formato).
- **Pai em vez de link.** Um span link manteria traces separados, mas logs e header do provedor sairiam com outro `traceId`, que é o que o enunciado pede para ver igual. O custo: com backoff longo, o span do envio aparece no trace do `POST` horas depois; as ferramentas de trace mostram o intervalo.
- **Amostragem em 100% por padrão** (`TRACING_SAMPLING_PROBABILITY`). O desafio mostra traces completos; em produção a variável baixa a taxa. O envio segue a decisão de amostragem gravada.
- **Logs em JSON, formato Logstash do Boot.** Copia o MDC para o topo do objeto: `traceId` e `spanId`, o mesmo nome do ProblemDetail. ECS foi descartado porque renomeia os campos. Nenhum log recebe token nem payload completo.
- **Métricas.** Latência da API e do provedor vêm da observação automática do Spring (`http_server_requests_seconds`, `http_client_requests_seconds`), com histogramas para p95/p99. Solicitações por status vêm do contador `activation.requests.transitions{status}`, incrementado depois do commit (o resultado tardio descartado não conta). Contador em vez de gauge do banco: barato, soma entre instâncias e responde "quantas falharam nos últimos 5 min" com `rate()`. O rótulo `uri` é o template da rota, nunca id nem `externalId`.
- **`/actuator/prometheus` público.** O Prometheus faz scrape sem client credentials, e o conteúdo é agregado e sem dados de negócio. Os demais endpoints do Actuator continuam fechados. Descartado: exigir escopo de métricas (o Prometheus precisaria de token).
- **OTLP opcional e desligado por padrão.** `spring-boot-starter-opentelemetry` traz tracing e exportador. O Boot 4.1.2 cria o exportador mesmo com endpoint vazio e a subida falha, e o `spring.config.activate` não tem `on-property`; por isso a exportação tem chave própria (`OTLP_TRACING_ENABLED`) além do endpoint. Sem coletor, Prometheus nem Grafana no Compose, para não pesar o smoke test do CI.

## Consequências

- Nova coluna `trace_parent varchar(55)`. Rollback: a versão anterior ignora a coluna.
- Dependências novas: `spring-boot-starter-opentelemetry` (substitui duas de tracing), `micrometer-registry-prometheus` e `archunit-junit5` (teste).
- Logs ilegíveis a olho no terminal local; o README sugere `jq`, e o formato muda por uma propriedade.
- `/actuator/prometheus` precisa de restrição pela rede em produção (porta de gerenciamento separada ou regra no ingress); fica documentado no README, não implementado.
- Amostragem de 100% custa armazenamento no coletor em produção.
- O contador mede eventos, não estoque: "quantas `PENDING` agora" exigiria um gauge consultando o banco, fora desta change.
- A reconciliação de solicitações presas roda no trace do ciclo agendado, sem o contexto do `POST`.
- As regras de camadas passam a ser verificadas no build (`LayerDependencyRulesTest`, ArchUnit).
