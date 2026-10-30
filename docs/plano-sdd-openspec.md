# Plano de execução — Desafio Java com SDD (OpenSpec 1.15.0 + Spring Boot 4.1.2 + Claude Code + IntelliJ)

> Guia de trabalho para conduzir o desafio de ativação de produtos com Spec-Driven Development.
> Coloque em `docs/plano-sdd-openspec.md`. O enunciado original deve ficar ao lado, em `docs/desafio-tecnico-java.md`, para servir de fonte de verdade para o agente.

---

## 0. Ideia geral

O OpenSpec separa **o que o sistema deve fazer** (specs) de **como e em que ordem construir** (change → proposal, design, tasks). Para este desafio, isso resolve três coisas:

1. **Rastreabilidade:** cada item do enunciado vira um requisito com cenários testáveis. O avaliador vê de onde veio cada decisão.
2. **Controle do Claude Code:** o agente implementa uma change pequena por vez, seguindo `tasks.md`, em vez de "gerar o projeto todo".
3. **README quase pronto:** as seções `design.md` de cada change já são as "decisões técnicas" e alimentam as ADRs em `docs/adr/`.

Regra de ouro: **uma change por fatia vertical, um commit por task (ou grupo pequeno de tasks), contexto limpo antes de cada `/opsx:apply`.**

### Onde digitar cada comando

| Onde | Comandos |
|---|---|
| Terminal do IntelliJ | `openspec init`, `openspec list`, `openspec show <change>`, `openspec validate <change> --strict`, `openspec view`, `./mvnw verify`, `git` |
| Chat do Claude Code | `/opsx:explore`, `/opsx:propose`, `/opsx:apply`, `/opsx:archive` (e, no perfil expandido, `/opsx:new`, `/opsx:continue`, `/opsx:ff`, `/opsx:verify`, `/opsx:sync`) |

> Confirme os comandos da sua versão com `openspec --help`. Se quiser o passo a passo mais controlado (`/opsx:continue`, `/opsx:verify`), ative o perfil expandido com `openspec config profile` e depois `openspec update`.

---

## 1. Preparação (≈30 min)

### 1.1 Instalar e inicializar

```bash
node -v                      # precisa ser >= 20.19
npm install -g @fission-ai/openspec@1.15.0
cd <seu-projeto>
openspec init                # selecione Claude Code
git add . && git commit -m "chore: inicializa OpenSpec"
```

Isso cria a pasta `openspec/` (`specs/`, `changes/`, arquivo de configuração/contexto do projeto) e os comandos `/opsx:*` dentro de `.claude/`.

### 1.2 Preencher o contexto do projeto

No arquivo de contexto gerado pelo `init` (em 1.x, `openspec/config.yaml`; se a sua versão gerou `openspec/project.md`, use esse), registre as convenções. Sugestão:

```yaml
context: |
  Serviço de ativação de produtos (desafio técnico Java Pleno/Sênior).
  Enunciado completo: docs/desafio-tecnico-java.md — é a fonte de verdade.
  Stack: Java 21, Spring Boot 4.1.2 (Spring Framework 7, Spring Security 7, Hibernate 7,
  Jakarta EE 11, Jackson 3), Maven (mvnw), Spring MVC, Spring Data JPA,
  PostgreSQL, Flyway, Spring Security Resource Server (JWT), RestClient,
  JUnit, Mockito, AssertJ, MockMvc, Testcontainers 2, WireMock, Awaitility, ArchUnit.
  ATENÇÃO: é Spring Boot 4, não 3. Siga a seção 1.4 de docs/plano-sdd-openspec.md
  (starters modulares, @MockitoBean, Jackson 3 em tools.jackson, Testcontainers 2).
  Princípios: solução simples e bem estruturada; nada de camadas ou padrões sem necessidade.
  Pacotes por feature: api / domain / application / infrastructure.
  DTOs como records, separados da entidade JPA. Datas Instant em UTC (ISO-8601).
  Erros sempre em ProblemDetail (RFC 9457) com traceId.
  Toda decisão relevante vira ADR em docs/adr/ (formato curto: contexto, decisão, consequências).

rules:
  specs: |
    Todo requisito tem cenários WHEN/THEN verificáveis por teste automatizado.
    Cite o item do enunciado (ex.: "Item 3") em cada requisito.
  tasks: |
    Tasks pequenas (até ~30 min), cada uma com o teste correspondente.
    Última task de toda change: atualizar README/ADR e rodar ./mvnw verify.
```

### 1.3 `CLAUDE.md` na raiz

Arquivo curto para o Claude Code respeitar em toda sessão:

```markdown
# Regras do projeto
- Leia openspec/ e docs/desafio-tecnico-java.md antes de implementar.
- Só implemente o que estiver em tasks.md da change ativa. Não antecipe changes futuras.
- Ao concluir cada task: marque [x], rode ./mvnw verify e sugira a mensagem de commit (Conventional Commits, em português).
- Nunca exponha stack trace na API. Nunca logue tokens/credenciais.
- Não adicione dependência sem justificar no design.md.
- O projeto usa Spring Boot 4.1.2. Não use APIs, starters ou anotações do Boot 3 (ver docs/plano-sdd-openspec.md, seção 1.4).
  Na dúvida sobre nome de starter ou pacote, consulte a documentação oficial do Boot 4 em vez de supor.
```

### 1.4 Spring Boot 4.1.2 — o que muda em relação ao 3.x

O agente tem muito mais exemplos de Boot 3 na memória do que de Boot 4. Esta tabela evita os erros mais comuns. Gere o projeto pelo [start.spring.io](https://start.spring.io) escolhendo 4.1.2: o `pom.xml` gerado já vem com os starters certos e serve de referência.

| Tema | Boot 3 (não usar) | Boot 4.1.2 (usar) |
|---|---|---|
| Web MVC | `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| Testes de web | `spring-boot-starter-test` genérico | `spring-boot-starter-webmvc-test` (já traz o `spring-boot-starter-test`); idem `-data-jpa-test`, `-flyway-test`, etc. |
| Flyway | só `flyway-core` | `spring-boot-starter-flyway` + `flyway-database-postgresql` |
| Mocks em testes Spring | `@MockBean` / `@SpyBean` (removidos) | `@MockitoBean` / `@MockitoSpyBean` (`org.springframework.test.context.bean.override.mockito`) |
| MockMvc com `@SpringBootTest` | configurado automaticamente | exige `@AutoConfigureMockMvc` explícito; alternativa: `RestTestClient` |
| Pacotes de test slices | `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | pacotes por módulo, ex.: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| JSON | Jackson 2 (`com.fasterxml.jackson.databind`) | Jackson 3 (`tools.jackson.databind`); as anotações (`@JsonProperty` etc.) continuam em `com.fasterxml.jackson.annotation` |
| Testcontainers | 1.x, `org.testcontainers.containers.PostgreSQLContainer` | 2.x, artefato `testcontainers-postgresql`, classe `org.testcontainers.postgresql.PostgreSQLContainer`; usar `@ServiceConnection` |
| Null-safety | anotações do Spring | JSpecify (`@NullMarked` em `package-info.java`, `@Nullable`) — diferencial barato |
| Retry simples | Spring Retry (dependência externa) | `@Retryable` / `@ConcurrencyLimit` nativos do Framework 7 (`@EnableResilientMethods`) |
| Cliente HTTP declarativo | configuração manual | interfaces `@HttpExchange` registradas com `@ImportHttpServices` |
| Servlet | Undertow suportado | Undertow removido (Servlet 6.1); fique no Tomcat padrão |

Notas para o design e a conversa técnica:
- **Por que Boot 4 se o enunciado sugere 3.x como diferencial:** o suporte open source do 3.5 terminou em 30/06/2026; começar um serviço novo em uma linha sem suporte seria dívida técnica desde o primeiro dia. Registre isso em ADR (`0000-versao-spring-boot.md`).
- **Retry nativo não substitui o retry persistido.** `@Retryable` repete dentro da mesma execução e se perde se a instância cair. Para o provedor, o retry continua sendo por `attempts` + `nextAttemptAt` no banco (seção 3). O `@Retryable` pode ficar, no máximo, para falhas de conexão muito curtas — ou não ser usado. Bom ponto para explicar no code review.
- **Versionamento de API:** o Framework 7 tem versionamento nativo, mas o enunciado fixa `/api/v1` no path. Mantenha o path simples e cite a alternativa no README, sem implementar.
- Confirme nomes exatos de starters de segurança, de teste e de observabilidade no `pom.xml` gerado pelo start.spring.io antes de aceitar o que o agente sugerir.

---

## 2. Mapa: capabilities (specs) × changes

As **specs** descrevem capacidades estáveis do sistema; as **changes** são as entregas incrementais que criam essas specs.

| Capability (`openspec/specs/…`) | Itens do enunciado |
|---|---|
| `activation-requests` | 2, 4 (API, domínio, estados) |
| `duplicate-prevention` | 3 |
| `provider-integration` | 5 |
| `error-handling` | 6 |
| `api-security` | 7 |
| `operations` | 10, 11, observabilidade, CI |

| # | Change | Capabilities afetadas | Tempo estimado |
|---|---|---|---|
| 1 | `bootstrap-project` | `operations` | 45 min |
| 2 | `add-activation-request-api` | `activation-requests`, `error-handling` | 1h30 |
| 3 | `add-duplicate-prevention` | `duplicate-prevention` | 1h |
| 4 | `add-provider-integration` | `provider-integration`, `activation-requests` | 2h |
| 5 | `add-api-security` | `api-security` | 1h |
| 6 | `add-observability-and-delivery` | `operations` | 1h |
| 7 | Documentação final (sem change) | README, ADRs, cenário de análise | 1h |

Com prazo de 3 dias: **dia 1** changes 1–3, **dia 2** changes 4–5, **dia 3** change 6, documentação e revisão do repositório.

---

## 3. Decisões para fechar ANTES do primeiro `/opsx:propose`

Use `/opsx:explore` para discutir isso com o agente, mas a decisão final é sua (você vai defendê-la no code review). Sugestões iniciais:

**Duplicidade (item 3)**
- `UNIQUE` em `external_id` no banco + captura de `DataIntegrityViolationException` (o "check-then-insert" sofre race condition entre o SELECT e o INSERT).
- Mesmo `externalId` + mesmo conteúdo → `200 OK` com o registro existente (retry idempotente).
- Mesmo `externalId` + conteúdo diferente → `409 Conflict` com ProblemDetail explicando o campo divergente.
- Duas idênticas em paralelo → uma recebe `201`, a outra cai na violação, relê o registro e recebe `200`.

**Integração (item 5)**
- `POST` retorna `201` com `PENDING` imediatamente; processamento assíncrono.
- Garantia de envio após commit: a própria tabela é o outbox. Um worker `@Scheduled` busca `PENDING` com `SELECT … FOR UPDATE SKIP LOCKED` (seguro com várias instâncias), muda para `PROCESSING`, faz commit e só então chama o provedor, fora da transação.
- `Idempotency-Key = id da solicitação` enviado ao provedor (resolve o timeout ambíguo).
- `@Version` na entidade; transições só por métodos de domínio.

| Retorno do provedor | Novo status | Retry? | Justificativa |
|---|---|---|---|
| 2xx | `COMPLETED` | Não | Ativação confirmada. |
| 400 | `FAILED` | Não | Erro do nosso payload; repetir não muda o resultado. |
| 500 | volta a `PENDING`, `attempts++`, `nextAttemptAt` com backoff | Sim, até N | Falha transitória provável. |
| Timeout | idem 500 | Sim, mesma `Idempotency-Key` | Pode ter ativado; a chave evita duplicar. |
| 429 / 503 + `Retry-After` | idem, `nextAttemptAt = Retry-After` | Sim | Respeitar o provedor. |
| Limite de tentativas | `FAILED` com motivo | Não | Vai para reconciliação/manual. |

> Decida e documente: falha temporária volta para `PENDING` (sugestão acima) ou fica em `PROCESSING` com contador. Isso implica adicionar a transição `PROCESSING → PENDING` ao diagrama do enunciado — registre em ADR.

**Segurança (item 7)**
- OAuth 2.0 Client Credentials (máquina a máquina). Resource Server validando assinatura (JWKS), `iss`, `aud`, `exp`.
- Escopos `activation:write` e `activation:read`. Dono da solicitação = claim `client_id`/`azp` → consulta de outro cliente devolve `404` (BOLA).
- Testes com `jwt()` do `spring-security-test`, sem servidor de autenticação.

---

## 4. Ciclo de cada change

```
/opsx:explore  (opcional: discutir dúvidas)
/opsx:propose <nome-da-change> + prompt da seção 5
   → revise proposal.md, specs/**/spec.md, design.md, tasks.md NO INTELLIJ
   → ajuste o que discordar (edite à mão ou peça ajuste no chat)
openspec validate <nome-da-change> --strict      (terminal)
git commit -m "docs(spec): proposta <nome-da-change>"
/clear                                          (contexto limpo)
/opsx:apply                                     (implementa task a task)
   → revise cada diff, rode ./mvnw verify, commit por task
/opsx:archive                                   (move para archive e mescla as specs)
git commit -m "docs(spec): arquiva <nome-da-change>"
```

Pontos de atenção:
- **Revise as specs com mais cuidado que o código.** Erro na spec vira erro em todos os testes.
- Se o agente "inventar" algo fora da change, interrompa e aponte o `tasks.md`.
- Commits da proposta separados dos commits de implementação deixam um histórico ótimo para o avaliador (o enunciado valoriza isso).

---

## 5. Prompts sugeridos para cada `/opsx:propose`

### Change 1 — `bootstrap-project`
```
/opsx:propose bootstrap-project
Estrutura base conforme docs/desafio-tecnico-java.md (item 1 e 11):
Spring Boot 4.1.2 com starters modulares (seção 1.4 de docs/plano-sdd-openspec.md),
Java 21, Maven wrapper, PostgreSQL + spring-boot-starter-flyway (migração V1),
Actuator com liveness/readiness, testes com Testcontainers 2 + @ServiceConnection,
JSpecify @NullMarked nos pacotes, ADR 0000 justificando a escolha do Boot 4,
Dockerfile multi-stage (JRE enxuto, usuário não-root, MaxRAMPercentage),
docker-compose.yml com app + postgres + WireMock (mock do provedor),
GitHub Actions rodando ./mvnw verify.
Um teste de contexto deve subir com Testcontainers.
```

### Change 2 — `add-activation-request-api`
```
/opsx:propose add-activation-request-api
Itens 2, 4 e 6 de docs/desafio-tecnico-java.md.
POST /api/v1/requests (201 + Location) e GET /api/v1/requests/{id} (404 se não existir).
Entidade ActivationRequest com id UUID, externalId, customerId, productCode, status,
createdAt, updatedAt (Instant/UTC), @Version, attempts, nextAttemptAt.
Máquina de estados no domínio (PENDING→PROCESSING→COMPLETED|FAILED, PROCESSING→PENDING para retry),
transições inválidas lançam exceção de domínio — teste unitário sem Spring.
DTOs record; validação com Bean Validation documentada (tamanho máximo e regex).
Erros em ProblemDetail com traceId, sem stack trace.
Diferencial opcional: GET paginado com filtros status/customerId e índices justificados.
NÃO incluir duplicidade nem integração ainda.
```

### Change 3 — `add-duplicate-prevention`
```
/opsx:propose add-duplicate-prevention
Item 3 de docs/desafio-tecnico-java.md, com as decisões da seção 3 de docs/plano-sdd-openspec.md:
constraint UNIQUE em external_id via Flyway, tratamento da violação,
200 para retry idêntico, 409 para conteúdo divergente.
Teste de concorrência com Testcontainers: N threads com o mesmo externalId → exatamente 1 registro.
Explique no design.md por que check-then-insert não basta.
```

### Change 4 — `add-provider-integration`
```
/opsx:propose add-provider-integration
Item 5 de docs/desafio-tecnico-java.md, com a tabela de decisões da seção 3 de docs/plano-sdd-openspec.md.
Worker @Scheduled com SELECT ... FOR UPDATE SKIP LOCKED, chamada HTTP fora da transação,
RestClient com connect/read timeout configuráveis, header Idempotency-Key,
backoff exponencial com jitter e limite de tentativas, reconciliação de PROCESSING antigo.
Cliente do provedor como interface @HttpExchange registrada com @ImportHttpServices (Spring Framework 7).
Retry persistido no banco; explicar no design.md por que @Retryable nativo não basta para isso.
Testes com WireMock: 2xx, 400, 500, timeout, 429 com Retry-After; Awaitility para o assíncrono.
Circuit breaker apenas descrito no design.md (não implementar sem necessidade).
```

### Change 5 — `add-api-security`
```
/opsx:propose add-api-security
Item 7 de docs/desafio-tecnico-java.md: Spring Security Resource Server com JWT,
escopos activation:write e activation:read, isolamento por client_id (BOLA → 404),
validação de iss/aud/exp, JWKS por configuração.
Spring Security 7 com SecurityFilterChain em DSL lambda (sem APIs removidas do 6.x).
Testes 401 e 403 com o suporte de teste do Spring Security (jwt() do MockMvc), sem servidor de autenticação.
Descrever no design.md: fluxo client credentials e armazenamento/rotação da credencial do provedor.
```

### Change 6 — `add-observability-and-delivery`
```
/opsx:propose add-observability-and-delivery
Observabilidade básica de docs/desafio-tecnico-java.md: correlation id (traceId) via MDC
propagado até a chamada do provedor, logs JSON, métricas Micrometer/Prometheus
(latência da API, latência e erros do provedor, solicitações por status),
logs estruturados nativos do Boot (logging.structured.format.console), tracing com OpenTelemetry
usando o suporte do Boot 4 (confirmar o starter no start.spring.io),
graceful shutdown, healthcheck no compose. Teste ArchUnit das regras de camadas.
```

---

## 6. Fechamento (sem change OpenSpec)

Peça ao Claude Code um rascunho, mas escreva as respostas de análise com a sua voz — é o que vai ser cobrado na conversa técnica.

- `README.md` com as seções obrigatórias: Como executar, Decisões técnicas (link para `docs/adr/` e `openspec/specs/`), Cenário de análise (item 10), Produção, Pontos pendentes, Melhorias futuras, **Uso de IA** (explique o fluxo OpenSpec + Claude Code: você escreveu/revisou as specs, o agente implementou sob revisão).
- `docs/adr/`: 0000 versão do Spring Boot (4.1.2), 0001 duplicidade, 0002 processamento assíncrono/outbox, 0003 política de retry e idempotência com provedor, 0004 segurança, 0005 transição PROCESSING→PENDING.
- Clone limpo em outra pasta → `./mvnw verify` e `docker compose up` funcionando.
- `openspec list` sem changes pendentes; `openspec/changes/archive/` mostrando o histórico.

### Ideia para o item 10 (rascunho para você desenvolver)
Os três sintomas sobem juntos com CPU e memória normais → suspeita de **espera, não processamento**: pool de conexões do Hikari esgotado porque threads seguram conexão enquanto aguardam o provedor (chamada HTTP dentro de transação) ou falta de índice causando lock/espera. Confirmar com métricas `hikaricp_connections_pending`, `http_client_requests` por destino, thread dump, `pg_stat_activity` e `EXPLAIN ANALYZE` das consultas lentas. Validar a correção comparando p95/p99 e taxa de erro antes/depois no mesmo intervalo de carga.

---

## 7. Checklist rápido

- [ ] Projeto gerado no start.spring.io com Boot 4.1.2 e starters modulares
- [ ] `openspec init` + contexto do projeto + `CLAUDE.md` commitados
- [ ] Nenhum `@MockBean`, `spring-boot-starter-web` ou `com.fasterxml.jackson.databind` no código
- [ ] Decisões da seção 3 revisadas por mim
- [ ] Changes 1 → 6 propostas, validadas, aplicadas e arquivadas
- [ ] `./mvnw verify` verde a partir de clone limpo
- [ ] `docker compose up` sobe app + banco + mock
- [ ] README completo + ADRs
- [ ] Histórico de commits pequenos e com intenção clara
- [ ] Repositório enviado para junio@deroyque.com dentro do prazo
