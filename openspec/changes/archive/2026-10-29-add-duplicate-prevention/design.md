# Design

## Context

Estado atual (motivação em proposal.md, Why):

- `V1__create_activation_request.sql` cria `external_id varchar(64) NOT NULL`, sem unicidade. Índices só para a listagem.
- `ActivationRequestService.create` é `@Transactional` e chama `repository.save(...)`. Um `externalId` repetido gera um registro novo.
- `ActivationRequest` recebe o `id` (UUID) no construtor e tem `@Version` `Long`. O Spring Data decide "novo" pela versão nula, então `save` faz `persist`, não `merge`.
- `ApiExceptionHandler` centraliza os ProblemDetail. Qualquer `DataIntegrityViolationException` hoje cai no handler genérico e vira `500`.
- Requisitos em `specs/duplicate-prevention/spec.md` e no requisito modificado de `specs/activation-requests/spec.md`.

## Goals / Non-Goals

**Goals:**
- Unicidade garantida pelo banco, correta com várias instâncias da aplicação, sem lock de aplicação.
- Retry idêntico e corrida entre requisições iguais nunca resultam em `5xx`.
- Comparação de conteúdo testável sem Spring.

**Non-Goals:**
- Header `Idempotency-Key` na API de entrada: o `externalId` já cumpre esse papel.
- Unicidade por cliente (ver decisão 1 e ADR 0001).
- Dono da solicitação e autorização (change `add-api-security`).
- Normalizar `externalId` (trim, caixa): a validação já recusa espaços, e a comparação é exata.

## Decisions

**1. Unicidade global de `externalId`, registrada no ADR 0001.**
O enunciado diz "o sistema não deve criar duas solicitações com o mesmo `externalId`", sem qualificar por cliente. A change 5 vai adicionar `client_id` (dono) sem mudar a constraint: dois clientes diferentes com o mesmo `externalId` continuam sendo duplicidade (`409` se o conteúdo divergir). Alternativa descartada: `UNIQUE (customer_id, external_id)` ou `UNIQUE (client_id, external_id)`. Ela evita colisão entre sistemas de origem distintos, mas contradiz o enunciado. Fica como evolução no ADR, caso o negócio confirme que `externalId` só é único por origem.

**2. Constraint `UNIQUE` via Flyway, em migração nova.**
`V2__unique_external_id.sql`:
```sql
ALTER TABLE activation_request
    ADD CONSTRAINT uq_activation_request_external_id UNIQUE (external_id);
```
Migração nova, porque `V1` já foi aplicada e o Flyway valida checksum. A constraint cria o índice único, que também atende a busca por `externalId`. A entidade ganha `@Column(unique = true)` só como documentação; quem cria o schema é o Flyway (`ddl-auto=validate`).

**3. Por que check-then-insert não basta.**
"Buscar por `externalId`; se não existir, inserir" tem uma janela entre o `SELECT` e o `INSERT`:
```
T1: SELECT ... WHERE external_id='X'  → vazio
T2: SELECT ... WHERE external_id='X'  → vazio
T1: INSERT 'X' ; COMMIT
T2: INSERT 'X' ; COMMIT               → segundo registro
```
No `READ COMMITTED` (padrão do PostgreSQL), T2 não vê a linha de T1, que ainda não foi confirmada. Subir para `SERIALIZABLE` resolveria com falha de serialização, mas troca um erro por outro e afeta toda a transação. Um lock na aplicação (`synchronized`, mutex) não vale com mais de uma instância. Só o banco enxerga todas as transações de todas as instâncias. Por isso a constraint é a garantia, e a busca prévia é só um atalho.

**4. Fluxo da criação: busca prévia, insert, e releitura na violação.**
```
create(externalId, customerId, productCode):
  existente = findByExternalId(externalId)
  se existe → resolver(existente)                     # caminho comum do retry
  try:
     salvo = saveAndFlush(novo)                       # transação própria
     → Criado(salvo)                                  # 201
  catch DataIntegrityViolationException:
     existente = findByExternalId(externalId)          # nova transação
     se não existe → relança                          # outra violação: vira 500
     → resolver(existente)

resolver(existente):
  campos = existente.conflictingFields(customerId, productCode)
  campos vazio → Existente(existente)                 # 200
  senão → ActivationRequestConflictException(externalId, campos)   # 409
```
- A busca prévia evita exceção e log de erro do driver no retry sequencial, que é o caso mais comum. A violação só acontece na corrida real.
- `create` deixa de ser `@Transactional`. O `saveAndFlush` roda na transação do próprio repositório, e a releitura roda em outra. Motivo: no PostgreSQL, depois de um erro a transação fica abortada (`current transaction is aborted`), e o Spring a marca como rollback-only. Reler dentro dela falharia. `saveAndFlush` força o `INSERT` dentro da chamada, e a exceção sai ali, não no commit.
- Para reconhecer a violação, a releitura basta: se a linha com o `externalId` existe, a violação foi de unicidade. Isso evita depender do nome da constraint ou de classes do Hibernate. Se não existe, a violação era outra e segue para o `500` genérico.
- Alternativa descartada: `INSERT ... ON CONFLICT (external_id) DO NOTHING RETURNING` em SQL nativo. Elimina a exceção, mas tira a inserção do JPA (versão, mapeamento) e acopla o serviço a SQL específico. A exceção traduzida pelo Spring é suficiente.

**5. Tipos do resultado.**
- `ActivationRequestService.create` retorna `CreationResult(ActivationRequest request, boolean created)` (record em `application`). O controller escolhe `201` com `Location` ou `200` sem `Location`. Um booleano basta; uma hierarquia selada seria padrão sem necessidade.
- `ActivationRequest.conflictingFields(String customerId, String productCode)` no domínio devolve a lista dos nomes divergentes (`customerId`, `productCode`), na ordem do JSON. Regra pura, testada sem Spring.
- `ActivationRequestConflictException(String externalId, List<String> conflictingFields)` em `application`, ao lado de `ActivationRequestNotFoundException`.

**6. Resposta `409`.**
`ApiExceptionHandler` ganha um handler para a exceção de conflito, com `type` `https://problems.ativacao.example/duplicate-external-id` (constante nova em `ProblemTypes`), `title` `Solicitação duplicada`, `detail` `Já existe solicitação com externalId EXT-X e conteúdo diferente.` e a propriedade `conflictingFields`. O `409` não traz o `id` nem os valores do registro existente. Quando a change 5 chegar, outro cliente não pode ler a solicitação alheia por esse caminho (BOLA).

**7. `200` para retry idêntico, e não `409`.**
O retry é legítimo: o sistema de origem não sabe se a primeira chamada chegou (timeout, queda de conexão). `200` com o registro atual dá a ele o `id` e o status sem tratamento especial. `409` para retry idêntico obrigaria o cliente a fazer um `GET` extra e a distinguir "conflito real" de "já criado". O `409` fica reservado para o caso que exige ação humana: o mesmo `externalId` com outro pedido.

**8. Teste de concorrência.**
`@SpringBootTest` com `TestcontainersConfiguration` (PostgreSQL real). N = 10 threads, um `CountDownLatch` de largada para todas chamarem `service.create` ao mesmo tempo com o mesmo `externalId` e o mesmo conteúdo. Verifica: 1 linha no banco para o `externalId`, exatamente um `created = true`, todas com o mesmo `id` e nenhuma exceção. O teste chama o serviço, e não o HTTP, para isolar a corrida no banco. O mapeamento `201`/`200`/`409` é coberto por testes MockMvc sequenciais. O pool padrão do Hikari (10 conexões) comporta as 10 threads.

## Risks / Trade-offs

- [A corrida é não determinística: as threads podem não colidir de fato e o caminho da violação não rodar] → O teste de repositório grava duas linhas com o mesmo `externalId` e prova que o banco rejeita. Somado ao teste de serviço com a releitura, os dois caminhos ficam cobertos.
- [No caminho da violação, o Hibernate registra o erro SQL em log (`SqlExceptionHelper`)] → Acontece só na corrida real, graças à busca prévia. Não vale silenciar esse logger, porque ele também registra erros legítimos.
- [Base existente com `externalId` duplicado faz a `V2` falhar] → Não há produção ainda. Em produção, seria preciso deduplicar antes; registro no ADR.
- [Unicidade global pode colidir entre sistemas de origem diferentes] → Decisão do enunciado, registrada no ADR 0001 com a alternativa por origem.
- [Testes existentes que reusam o mesmo `externalId` entre execuções no mesmo banco] → Hoje só criações inválidas reusam `EXT-1`. Uma task revisa os testes e usa `externalId` único onde houver gravação.

## Migration Plan

- Deploy: o Flyway aplica a `V2` na subida. A tabela é pequena, então o `ALTER TABLE` com lock breve é aceitável. Para tabela grande, a alternativa seria `CREATE UNIQUE INDEX CONCURRENTLY` e depois `ADD CONSTRAINT ... USING INDEX`.
- Rollback: `ALTER TABLE activation_request DROP CONSTRAINT uq_activation_request_external_id;` numa migração nova, junto com a versão anterior da aplicação.
