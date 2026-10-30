# Roteiro de execução

Versão curta e operacional de [plano-sdd-openspec.md](plano-sdd-openspec.md). O plano continua sendo a referência para detalhes: tabela Boot 3 × Boot 4 (§1.4), decisões técnicas (§3) e prompts de cada change (§5). Este arquivo diz **o que fazer agora** e **quando a etapa está pronta**.

Marque `[x]` ao concluir cada etapa.

## Ciclo padrão de uma change

1. `git switch main && git pull`, depois `git switch -c <tipo>/<change>` (ex.: `feat/add-duplicate-prevention`; na Etapa 1, `chore/bootstrap-project`).
2. `/clear` no Claude Code.
3. `/opsx:propose <change>` com o prompt do plano §5 (ajustes abaixo).
4. Revisar `proposal.md`, `specs/`, `design.md` e `tasks.md` no IntelliJ.
5. `openspec validate <change> --strict` e commit `docs(spec): proposta <change>`.
6. `/clear` e `/opsx:apply`. Revisar cada diff, rodar `./mvnw verify` e fazer um commit por task.
7. `/opsx:archive` e commit `docs(spec): arquiva <change>`.
8. `git push -u origin <branch>` e abrir PR para `main`. Descrição: resumo da `proposal.md` e o "Pronto quando" da etapa, marcado.
9. Com CI verde, merge com **merge commit** (não usar squash: ele perde os commits por task). Apagar a branch.

Etapas 0 e 7 não têm change e vão direto na `main`.

---

## Etapa 0 — Preparar OpenSpec e regras do agente

- [x] `openspec init --tools claude --language pt-BR`
- [x] Preencher `openspec/config.yaml` com o bloco do plano §1.2.
- [x] Criar `CLAUDE.md` na raiz com o bloco do plano §1.3.
- [x] Commit: `chore: inicializa OpenSpec e regras do agente`

**Pronto quando:** `openspec list` roda sem erro e os comandos `/opsx:*` aparecem no Claude Code.

## Etapa 1 — `bootstrap-project`

Já feito: projeto Boot 4.1.2, PostgreSQL no compose, Flyway, Testcontainers 2 com `@ServiceConnection`, perfil `local`.

Ajuste o prompt do plano §5 para pedir **apenas o que falta**:

- Actuator com liveness/readiness.
- `package-info.java` com `@NullMarked` (JSpecify).
- ADR `docs/adr/0000-versao-spring-boot.md`.
- `Dockerfile` multi-stage (JRE enxuto, usuário não-root, `MaxRAMPercentage`).
- Manter o nome `compose.yaml` (nome padrão e preferido da Compose Specification; `docker-compose.yml` só existe por compatibilidade) e adicionar app + WireMock. Registrar no README que o `compose.yaml` cumpre o `docker-compose.yml` pedido na seção Entrega do enunciado.
- GitHub Actions rodando `./mvnw verify`.

- [x] Change `bootstrap-project` aplicada, arquivada e com CI verde.

**Pronto quando:** `./mvnw verify` passa e `docker compose up` sobe app, banco e WireMock com health check verde.

## Etapa 2 — `add-activation-request-api` (itens 2, 4, 6)

Prompt: plano §5, change 2. Acrescente ao prompt:

- O `traceId` do ProblemDetail precisa de uma origem já nesta change. Opções: Micrometer Tracing, ou um filtro que grava o id no MDC. Registre a escolha no `design.md`. A change 6 só amplia a propagação até o provedor.

- [x] Change `add-activation-request-api` aplicada, arquivada e com CI verde.

**Pronto quando:** testes cobrem criação válida, validação inválida (400 ProblemDetail), consulta existente e inexistente (404), e transições inválidas (teste unitário sem Spring).

## Etapa 3 — `add-duplicate-prevention` (item 3)

Prompt: plano §5, change 3.

Decisão a registrar no ADR 0001: `externalId` é **único global**, como diz o enunciado, e não único por cliente. A change 5 adiciona o dono da solicitação sem alterar essa constraint.

- [x] Change `add-duplicate-prevention` aplicada, arquivada e com CI verde.

**Pronto quando:** o teste de concorrência com Testcontainers (N threads, mesmo `externalId`) resulta em exatamente 1 registro. Retry idêntico retorna 200. Conteúdo divergente retorna 409.

## Etapa 4 — `add-provider-integration` (item 5)

Prompt: plano §5, change 4. Antes do propose, confirme a decisão `PROCESSING → PENDING` (plano §3).

**Pronto quando:** testes com WireMock cobrem 2xx, 400, 500, timeout e 429 com `Retry-After`, com Awaitility. ADRs 0002, 0003 e 0005 estão escritos.

- [x] Change `add-provider-integration` aplicada, arquivada e com CI verde.

## Etapa 5 — `add-api-security` (item 7)

Prompt: plano §5, change 5. Acrescente ao prompt: uma migração Flyway nova com a coluna do dono (`client_id`), preenchida na criação a partir do JWT.

**Pronto quando:** sem token retorna 401, escopo errado retorna 403 e consulta de outro cliente retorna 404. ADR 0004 está escrito.

- [x] Change `add-api-security` aplicada, arquivada e com CI verde.

## Etapa 6 — `add-observability-and-delivery`

Prompt: plano §5, change 6.

**Pronto quando:** `/actuator/prometheus` expõe as métricas pedidas, os logs saem em JSON com `traceId` e o teste ArchUnit passa.

- [x] Change `add-observability-and-delivery` aplicada, arquivada e com CI verde.

## Etapa 7 — Fechamento (sem change)

Siga o plano §6 e o checklist do plano §7.

**Pronto quando:** um clone limpo em outra pasta passa em `./mvnw verify` e `docker compose up` funciona. `openspec list` não mostra changes pendentes. README tem todas as seções obrigatórias.
