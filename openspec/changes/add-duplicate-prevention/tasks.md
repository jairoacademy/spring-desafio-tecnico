# Tasks

## 1. Constraint no banco

- [ ] 1.1 Criar migração `V2__unique_external_id.sql` com `uq_activation_request_external_id UNIQUE (external_id)` e marcar `@Column(unique = true)` em `externalId`; revisar os testes existentes para que só usem um `externalId` fixo quando a criação grava uma única vez; teste em `ActivationRequestRepositoryTests` gravando duas solicitações com o mesmo `externalId` via `saveAndFlush`: a segunda lança `DataIntegrityViolationException` e o banco mantém só a primeira; verificar com `./mvnw verify`
- [ ] 1.2 Adicionar `findByExternalId(String)` ao `ActivationRequestRepository`; teste `@DataJpaTest` cobrindo encontrado, não encontrado e diferença só de caixa (`EXT-CASE` × `ext-case` são registros distintos)

## 2. Regra de conteúdo no domínio

- [ ] 2.1 Adicionar `ActivationRequest.conflictingFields(customerId, productCode)`, que devolve os nomes divergentes na ordem `customerId`, `productCode`; teste unitário em `ActivationRequestTest` (sem Spring) cobrindo conteúdo igual (lista vazia), só `productCode`, só `customerId` e os dois

## 3. Criação idempotente no serviço

- [ ] 3.1 Criar `CreationResult(ActivationRequest request, boolean created)` e `ActivationRequestConflictException(externalId, conflictingFields)` em `application`; reescrever `ActivationRequestService.create` conforme o design (decisão 4): sem `@Transactional`, busca prévia, `saveAndFlush`, releitura na `DataIntegrityViolationException` e relançamento quando não houver registro; coberto pelos testes da 3.2 e 4.1
- [ ] 3.2 Teste de concorrência `ActivationRequestConcurrencyIntegrationTests` (`@SpringBootTest` + `TestcontainersConfiguration`): 10 threads com `CountDownLatch` de largada chamam `service.create` com o mesmo `externalId` e o mesmo conteúdo; verificar exatamente 1 linha no banco para o `externalId`, exatamente um `created = true`, o mesmo `id` em todos os resultados e nenhuma exceção

## 4. Respostas HTTP

- [ ] 4.1 Controller devolve `201` + `Location` quando `created`, e `200` sem `Location` caso contrário; adicionar `ProblemTypes.DUPLICATE_EXTERNAL_ID` e handler de `ActivationRequestConflictException` no `ApiExceptionHandler` (`409`, `title` `Solicitação duplicada`, `conflictingFields`, sem `id` nem valores do registro existente); testes em `ActivationRequestApiIntegrationTests` cobrindo: retry idêntico (`200`, mesmos `id`, `createdAt` e `updatedAt`), retry depois de sair de `PENDING` (`200` com status atual), `productCode` divergente (`409` em `application/problem+json` com `traceId`, `detail` citando o `externalId` e `conflictingFields` `["productCode"]`), dois campos divergentes, outro `customerId` com mesmo `externalId` (`409`, continua um único registro), registro inalterado após `409` e `externalId` com caixa diferente (`201`)

## 5. Documentação e verificação final

- [ ] 5.1 Escrever `docs/adr/0001-unicidade-external-id.md` (contexto, decisão: unicidade global, `UNIQUE` no banco, `200`/`409`; consequências: change 5 adiciona o dono sem mudar a constraint, alternativa por origem, deduplicação antes da `V2` em base existente); atualizar o README: tabela e exemplos da API (`200` e `409` no `POST`, exemplo do ProblemDetail de conflito) e seção "Duplicidade" em Decisões técnicas (estratégia, por que check-then-insert não basta, resposta para cada cenário do enunciado); rodar `./mvnw verify` e verificar que passa

## Workflow follow-up

- `openspec validate add-duplicate-prevention --strict` e commit `docs(spec): proposta add-duplicate-prevention`.
- Depois da implementação: `/opsx:archive`, commit `docs(spec): arquiva add-duplicate-prevention`, PR para `main` com merge commit e `[x]` na Etapa 3 de `docs/roteiro.md`.
