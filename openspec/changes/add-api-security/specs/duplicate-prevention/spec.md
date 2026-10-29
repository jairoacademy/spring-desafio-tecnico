# Spec Delta

## MODIFIED Requirements

### Requirement: Retry idêntico devolve o registro existente
Quando um cliente envia criação com `externalId` já registrado por ele mesmo e com `customerId` e `productCode` iguais aos do registro existente, o serviço SHALL responder `200 OK` com o registro existente no mesmo formato da criação. Nenhuma solicitação nova MUST ser criada, e o registro existente MUST NOT ser alterado. (Itens 3 e 7, Diferencial)

#### Scenario: Retry com o mesmo conteúdo
- **WHEN** um cliente cria `{"externalId":"EXT-RETRY","customerId":"CUST-1","productCode":"INTERNET-500"}` e envia o mesmo corpo de novo
- **THEN** a segunda resposta é `200` com os mesmos `id`, `status`, `createdAt` e `updatedAt` da primeira

#### Scenario: Retry devolve o status atual
- **WHEN** a solicitação de `externalId` `EXT-RETRY-2` já saiu de `PENDING` e o cliente repete a criação com o mesmo conteúdo
- **THEN** a resposta é `200` com o `status` atual da solicitação, e não `PENDING`

### Requirement: Conteúdo divergente é conflito
Quando um cliente envia criação com `externalId` já registrado por ele mesmo e `customerId` ou `productCode` diferente do registro existente, o serviço SHALL responder `409 Conflict` em ProblemDetail, com `type` de conflito de duplicidade e a lista `conflictingFields` com os nomes dos campos divergentes. O registro existente MUST NOT ser alterado. (Itens 3, 6 e 7, Diferencial)

#### Scenario: productCode divergente
- **WHEN** existe solicitação `{"externalId":"EXT-CONF","customerId":"CUST-1","productCode":"INTERNET-500"}` e um cliente envia o mesmo `externalId` e `customerId` com `productCode` `INTERNET-900`
- **THEN** a resposta é `409` em `application/problem+json`, com `traceId`, `detail` citando `EXT-CONF` e `conflictingFields` igual a `["productCode"]`

#### Scenario: Vários campos divergentes
- **WHEN** existe solicitação com `externalId` `EXT-CONF-2` e um cliente envia o mesmo `externalId` com `customerId` e `productCode` diferentes
- **THEN** a resposta é `409` e `conflictingFields` contém `customerId` e `productCode`

#### Scenario: Registro existente não muda após conflito
- **WHEN** um cliente recebe `409` por conteúdo divergente e depois consulta a solicitação existente
- **THEN** `customerId`, `productCode` e `updatedAt` continuam iguais aos da criação original

## ADDED Requirements

### Requirement: externalId de outro cliente é conflito sem detalhes
Quando um cliente envia criação com `externalId` já registrado por outro cliente, o serviço SHALL responder `409 Conflict` em ProblemDetail com `type` de conflito de duplicidade, sem `conflictingFields`, qualquer que seja o conteúdo enviado. A resposta MUST NOT trazer `id`, status ou valores do registro existente. O registro existente MUST NOT ser alterado. (Itens 3 e 7, Diferencial)

#### Scenario: Mesmo conteúdo de outro cliente
- **WHEN** `sistema-a` cria `{"externalId":"EXT-OWNER","customerId":"CUST-1","productCode":"INTERNET-500"}` e `sistema-b` envia o mesmo corpo
- **THEN** a resposta para `sistema-b` é `409` em `application/problem+json`, sem `conflictingFields` e sem `id`

#### Scenario: Conteúdo diferente de outro cliente
- **WHEN** `sistema-a` cria uma solicitação com `externalId` `EXT-OWNER-2` e `sistema-b` envia o mesmo `externalId` com `productCode` diferente
- **THEN** a resposta para `sistema-b` é `409` sem `conflictingFields`, igual ao caso de conteúdo idêntico

#### Scenario: Registro de outro cliente não muda
- **WHEN** `sistema-b` recebe `409` por `externalId` de `sistema-a` e `sistema-a` consulta a própria solicitação
- **THEN** a solicitação continua com o mesmo dono, `customerId`, `productCode` e `updatedAt` da criação original
