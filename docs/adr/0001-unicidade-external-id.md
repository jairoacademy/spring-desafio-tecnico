# ADR 0001 — Unicidade do `externalId`

**Status:** aceito

## Contexto

O `externalId` identifica a solicitação no sistema de origem. O enunciado diz que o sistema não deve criar duas solicitações com o mesmo `externalId`, inclusive quando duas requisições iguais chegam quase ao mesmo tempo, sem qualificar a regra por cliente. Sistemas de origem fazem retry: o mesmo pedido pode chegar de novo depois de um timeout, ou em paralelo com a primeira tentativa.

Verificar se o `externalId` existe e depois inserir não basta: no `READ COMMITTED` do PostgreSQL, duas transações podem fazer o `SELECT` antes de qualquer uma confirmar o `INSERT`, e as duas inserem. Lock na aplicação não vale com mais de uma instância.

## Decisão

- A unicidade é **global**: no máximo uma solicitação por `externalId`, de qualquer cliente. A comparação diferencia maiúsculas de minúsculas.
- Quem garante é o banco: constraint `uq_activation_request_external_id UNIQUE (external_id)`, criada na migração `V2`. A busca prévia por `externalId` na aplicação é só um atalho para o retry sequencial.
- Na criação, uma violação da constraint leva a reler o registro existente e comparar o conteúdo (`customerId`, `productCode`):
  - mesmo conteúdo: `200 OK` com o registro existente, no estado atual. O retry é legítimo e o cliente recebe `id` e status sem tratamento especial;
  - conteúdo diferente: `409 Conflict` em ProblemDetail, com `conflictingFields`. A resposta não traz o `id` nem os valores do registro existente;
  - requisições idênticas em paralelo: uma recebe `201`, as outras caem na violação, releem e recebem `200` com o mesmo `id`.

## Consequências

- Funciona com várias instâncias da aplicação, sem lock distribuído nem nível de isolamento mais alto.
- A etapa de segurança (change `add-api-security`, [ADR 0004](0004-seguranca-da-api.md)) adicionou o dono da solicitação (`client_id`) sem mudar a constraint: dois clientes com o mesmo `externalId` continuam sendo duplicidade. As respostas acima (`200` no retry, `409` com `conflictingFields`) valem só para o mesmo cliente. Se o `externalId` é de outro cliente, a resposta é sempre `409` sem `conflictingFields`, com qualquer conteúdo, para que um cliente não leia nem deduza a solicitação de outro por esse caminho.
- Sistemas de origem diferentes podem colidir no mesmo `externalId`. Se o negócio confirmar que o `externalId` só é único por origem, a evolução é trocar a constraint por `UNIQUE (client_id, external_id)`.
- Uma base que já tenha `externalId` duplicado faz a `V2` falhar. Não há produção ainda; em uma base existente, seria preciso deduplicar antes da migração.
- Na corrida real, o Hibernate registra a violação no log (`duplicate key value violates unique constraint`). Isso só acontece em requisições simultâneas, graças à busca prévia.
