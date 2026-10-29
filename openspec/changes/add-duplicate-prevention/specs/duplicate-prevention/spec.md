# Spec Delta

## Purpose

Garante que o serviço nunca registre duas solicitações com o mesmo `externalId`, inclusive sob requisições simultâneas, e define o que a API responde quando um sistema de origem repete uma criação.

## ADDED Requirements

### Requirement: externalId único em todo o serviço
O serviço SHALL manter no máximo uma solicitação por `externalId`, considerando todas as solicitações, de qualquer cliente. A unicidade MUST ser garantida pelo banco de dados, e não só por verificação prévia na aplicação. A comparação de `externalId` MUST diferenciar maiúsculas de minúsculas. (Item 3, Obrigatório)

#### Scenario: Segunda criação com o mesmo externalId não gera registro
- **WHEN** um cliente cria uma solicitação com `externalId` `EXT-DUP-1` e depois envia outra criação com o mesmo `externalId`
- **THEN** existe exatamente uma solicitação com `externalId` `EXT-DUP-1`

#### Scenario: Mesmo externalId com outro cliente também é duplicidade
- **WHEN** existe uma solicitação com `externalId` `EXT-DUP-2` e `customerId` `CUST-A` e um cliente envia criação com `externalId` `EXT-DUP-2` e `customerId` `CUST-B`
- **THEN** nenhuma solicitação nova é criada e continua existindo uma única solicitação com `externalId` `EXT-DUP-2`, de `CUST-A`

#### Scenario: Diferença só de maiúsculas e minúsculas não é duplicidade
- **WHEN** existe uma solicitação com `externalId` `EXT-CASE` e um cliente cria outra com `externalId` `ext-case`
- **THEN** a resposta é `201` e existem duas solicitações distintas

#### Scenario: Banco rejeita duplicidade gravada por fora da API
- **WHEN** duas solicitações com o mesmo `externalId` são gravadas direto pela camada de persistência
- **THEN** a segunda gravação falha por violação de unicidade e o banco mantém só a primeira

### Requirement: Retry idêntico devolve o registro existente
Quando um cliente envia criação com `externalId` já registrado e com `customerId` e `productCode` iguais aos do registro existente, o serviço SHALL responder `200 OK` com o registro existente no mesmo formato da criação. Nenhuma solicitação nova MUST ser criada, e o registro existente MUST NOT ser alterado. (Item 3, Diferencial)

#### Scenario: Retry com o mesmo conteúdo
- **WHEN** um cliente cria `{"externalId":"EXT-RETRY","customerId":"CUST-1","productCode":"INTERNET-500"}` e envia o mesmo corpo de novo
- **THEN** a segunda resposta é `200` com os mesmos `id`, `status`, `createdAt` e `updatedAt` da primeira

#### Scenario: Retry devolve o status atual
- **WHEN** a solicitação de `externalId` `EXT-RETRY-2` já saiu de `PENDING` e o cliente repete a criação com o mesmo conteúdo
- **THEN** a resposta é `200` com o `status` atual da solicitação, e não `PENDING`

### Requirement: Conteúdo divergente é conflito
Quando um cliente envia criação com `externalId` já registrado e `customerId` ou `productCode` diferente do registro existente, o serviço SHALL responder `409 Conflict` em ProblemDetail, com `type` de conflito de duplicidade e a lista `conflictingFields` com os nomes dos campos divergentes. O registro existente MUST NOT ser alterado. (Itens 3 e 6, Diferencial)

#### Scenario: productCode divergente
- **WHEN** existe solicitação `{"externalId":"EXT-CONF","customerId":"CUST-1","productCode":"INTERNET-500"}` e um cliente envia o mesmo `externalId` e `customerId` com `productCode` `INTERNET-900`
- **THEN** a resposta é `409` em `application/problem+json`, com `traceId`, `detail` citando `EXT-CONF` e `conflictingFields` igual a `["productCode"]`

#### Scenario: Vários campos divergentes
- **WHEN** existe solicitação com `externalId` `EXT-CONF-2` e um cliente envia o mesmo `externalId` com `customerId` e `productCode` diferentes
- **THEN** a resposta é `409` e `conflictingFields` contém `customerId` e `productCode`

#### Scenario: Registro existente não muda após conflito
- **WHEN** um cliente recebe `409` por conteúdo divergente e depois consulta a solicitação existente
- **THEN** `customerId`, `productCode` e `updatedAt` continuam iguais aos da criação original

### Requirement: Criações simultâneas com o mesmo externalId
Quando várias criações com o mesmo `externalId` e o mesmo conteúdo chegam ao mesmo tempo, o serviço SHALL gravar exatamente uma solicitação. Exatamente uma das requisições MUST receber `201`; as demais MUST receber `200` com o mesmo `id`. Nenhuma MUST receber `5xx`. (Itens 3 e 8, Diferencial)

#### Scenario: N requisições paralelas idênticas
- **WHEN** 10 requisições de criação com o mesmo `externalId` e o mesmo conteúdo são disparadas ao mesmo tempo contra o banco real
- **THEN** o banco tem exatamente 1 solicitação com esse `externalId`, uma resposta indica criação, as outras 9 indicam registro existente e todas trazem o mesmo `id`
