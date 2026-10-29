# activation-requests Specification

## Purpose

Define como sistemas de origem registram, consultam e listam solicitações de ativação de produtos, quais dados de entrada são aceitos e como o status de uma solicitação evolui ao longo do seu ciclo de vida.

## Requirements

### Requirement: Criação de solicitação
O serviço SHALL expor `POST /api/v1/requests`, que recebe `externalId`, `customerId` e `productCode` em JSON e cria uma solicitação com status `PENDING` quando o `externalId` ainda não está registrado. Nesse caso, a resposta MUST ser `201 Created`, com header `Location` apontando para `/api/v1/requests/{id}` e corpo contendo `id`, `externalId`, `customerId`, `productCode`, `status`, `createdAt` e `updatedAt`. A solicitação MUST ficar registrada com o cliente autenticado como dono (capability `api-security`); o dono não aparece no corpo. Uma criação com `externalId` já registrado MUST seguir as regras da capability `duplicate-prevention`. (Itens 2, 3, 4 e 7)

#### Scenario: Criação com dados válidos
- **WHEN** um cliente envia `POST /api/v1/requests` com `{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}`
- **THEN** a resposta é `201`, o header `Location` termina com `/api/v1/requests/{id}` e o corpo traz os três campos enviados, `status` igual a `PENDING` e `id` em formato UUID

#### Scenario: Solicitação criada fica persistida
- **WHEN** um cliente cria uma solicitação e em seguida chama `GET` na URL do header `Location`
- **THEN** a resposta é `200` com os mesmos `id`, `externalId`, `customerId`, `productCode`, `status`, `createdAt` e `updatedAt` da criação

#### Scenario: externalId repetido não gera 201
- **WHEN** um cliente envia duas vezes `POST /api/v1/requests` com o mesmo `externalId`
- **THEN** só a primeira resposta é `201`; a segunda é `200` ou `409`, conforme `duplicate-prevention`

#### Scenario: Dono gravado a partir do token
- **WHEN** o cliente `sistema-a` cria uma solicitação
- **THEN** a solicitação fica gravada com dono `sistema-a` e o corpo da resposta não traz o dono

### Requirement: Datas em UTC no formato ISO-8601
Os campos `createdAt` e `updatedAt` SHALL ser serializados como texto ISO-8601 em UTC, com sufixo `Z`. Na criação, `createdAt` e `updatedAt` MUST ser iguais. (Itens 2 e 4)

#### Scenario: Formato das datas na criação
- **WHEN** um cliente cria uma solicitação válida
- **THEN** `createdAt` e `updatedAt` são textos ISO-8601 terminados em `Z` e têm o mesmo valor

### Requirement: Validação dos dados de entrada
O serviço SHALL rejeitar com `400` uma criação em que `externalId`, `customerId` ou `productCode` esteja ausente, vazio ou fora do formato. Regras: `externalId` e `customerId` com 1 a 64 caracteres entre letras, dígitos, `-` e `_`; `productCode` com 1 a 50 caracteres entre letras maiúsculas, dígitos, `-` e `_`. Nenhuma solicitação MUST ser criada quando a validação falha. (Itens 2 e 6)

#### Scenario: Campo obrigatório ausente
- **WHEN** um cliente envia `POST /api/v1/requests` sem `customerId`
- **THEN** a resposta é `400` e a lista `errors` contém um item com `field` igual a `customerId`

#### Scenario: Campo acima do tamanho máximo
- **WHEN** um cliente envia `externalId` com 65 caracteres válidos
- **THEN** a resposta é `400` e a lista `errors` contém um item com `field` igual a `externalId`

#### Scenario: Campo fora do formato
- **WHEN** um cliente envia `productCode` igual a `internet 500`
- **THEN** a resposta é `400` e a lista `errors` contém um item com `field` igual a `productCode`

#### Scenario: Vários campos inválidos de uma vez
- **WHEN** um cliente envia um corpo `{}`
- **THEN** a resposta é `400` e a lista `errors` contém um item para cada um dos três campos

#### Scenario: Nada é gravado quando a validação falha
- **WHEN** um cliente envia uma criação inválida e depois lista solicitações filtrando pelo `customerId` enviado
- **THEN** a listagem não contém nenhuma solicitação

### Requirement: Corpo da requisição ilegível
O serviço SHALL responder `400` quando o corpo de `POST /api/v1/requests` não for um JSON válido ou tiver tipo incompatível em algum campo, sem criar solicitação. (Itens 2 e 6)

#### Scenario: JSON malformado
- **WHEN** um cliente envia `POST /api/v1/requests` com corpo `{"externalId":`
- **THEN** a resposta é `400` em ProblemDetail

### Requirement: Consulta por id
O serviço SHALL expor `GET /api/v1/requests/{id}`, que retorna `200` com a solicitação e seu status atual quando ela pertence ao cliente autenticado. Se não existir solicitação com o `id`, ou se ela pertencer a outro cliente, a resposta MUST ser `404`, sem distinguir os dois casos. Um `id` que não seja UUID MUST resultar em `400`. (Itens 2, 6 e 7)

#### Scenario: Solicitação existente
- **WHEN** um cliente chama `GET /api/v1/requests/{id}` com o `id` de uma solicitação criada por ele
- **THEN** a resposta é `200` com os dados e o `status` atual da solicitação

#### Scenario: Solicitação inexistente
- **WHEN** um cliente chama `GET /api/v1/requests/{id}` com um UUID que não pertence a nenhuma solicitação
- **THEN** a resposta é `404` em ProblemDetail

#### Scenario: Solicitação de outro cliente
- **WHEN** o cliente `sistema-a` cria uma solicitação e o cliente `sistema-b` chama `GET /api/v1/requests/{id}` com o `id` dela
- **THEN** a resposta para `sistema-b` é `404` em ProblemDetail, com o mesmo `type`, `title` e formato de `detail` de um id inexistente

#### Scenario: Id em formato inválido
- **WHEN** um cliente chama `GET /api/v1/requests/abc`
- **THEN** a resposta é `400` em ProblemDetail

### Requirement: Listagem paginada com filtros
O serviço SHALL expor `GET /api/v1/requests` com filtros opcionais `status` e `customerId` combináveis e paginação por `page` (a partir de 0, padrão 0) e `size` (1 a 100, padrão 20). A listagem MUST conter só solicitações do cliente autenticado, e os metadados de paginação MUST contar só essas. Os itens MUST vir do mais recente para o mais antigo por `createdAt`. A resposta MUST trazer `content` e os metadados `page`, `size`, `totalElements` e `totalPages`. (Itens 2 e 7, Diferencial)

#### Scenario: Filtro por cliente
- **WHEN** existem solicitações dos clientes `CUST-A` e `CUST-B` e um cliente chama `GET /api/v1/requests?customerId=CUST-A`
- **THEN** a resposta é `200` e `content` contém apenas solicitações de `CUST-A`

#### Scenario: Filtro combinado por status e cliente
- **WHEN** um cliente chama `GET /api/v1/requests?status=PENDING&customerId=CUST-A`
- **THEN** `content` contém apenas solicitações de `CUST-A` com status `PENDING`

#### Scenario: Paginação e ordenação
- **WHEN** existem 3 solicitações de `CUST-P` e um cliente chama `GET /api/v1/requests?customerId=CUST-P&page=0&size=2`
- **THEN** `content` tem 2 itens, o primeiro é a solicitação criada por último, `totalElements` é 3 e `totalPages` é 2

#### Scenario: Status desconhecido no filtro
- **WHEN** um cliente chama `GET /api/v1/requests?status=DONE`
- **THEN** a resposta é `400` em ProblemDetail

#### Scenario: Tamanho de página fora do limite
- **WHEN** um cliente chama `GET /api/v1/requests?size=101` ou `GET /api/v1/requests?page=-1`
- **THEN** a resposta é `400` em ProblemDetail

#### Scenario: Listagem isolada por dono
- **WHEN** `sistema-a` cria 2 solicitações e `sistema-b` cria 1, todas com `customerId` `CUST-SHARED`, e `sistema-b` chama `GET /api/v1/requests?customerId=CUST-SHARED`
- **THEN** `content` traz só a solicitação de `sistema-b` e `totalElements` é 1

### Requirement: Estados e transições controlados pelo domínio
Uma solicitação SHALL ter um dos estados `PENDING`, `PROCESSING`, `COMPLETED` ou `FAILED`. As únicas transições permitidas MUST ser `PENDING → PROCESSING`, `PROCESSING → COMPLETED`, `PROCESSING → FAILED` e `PROCESSING → PENDING` (falha temporária, com nova tentativa). `COMPLETED` e `FAILED` são finais. Qualquer outra transição MUST ser rejeitada sem alterar a solicitação. (Item 4, Diferencial)

#### Scenario: Caminho de sucesso
- **WHEN** uma solicitação `PENDING` passa para `PROCESSING` e depois para `COMPLETED`
- **THEN** o status final é `COMPLETED` e `updatedAt` reflete o instante da última transição

#### Scenario: Caminho de falha definitiva
- **WHEN** uma solicitação `PROCESSING` é marcada como falha
- **THEN** o status final é `FAILED`

#### Scenario: Estado final não volta a processar
- **WHEN** uma solicitação `COMPLETED` tenta passar para `PROCESSING`
- **THEN** a transição é rejeitada com erro de domínio e o status continua `COMPLETED`

#### Scenario: Pular etapa é rejeitado
- **WHEN** uma solicitação `PENDING` tenta passar direto para `COMPLETED` ou para `FAILED`
- **THEN** a transição é rejeitada com erro de domínio e o status continua `PENDING`

### Requirement: Falha temporária volta para PENDING com nova tentativa agendada
Quando uma solicitação em `PROCESSING` sofre falha temporária, ela SHALL voltar para `PENDING`, com o contador de tentativas incrementado em 1 e o instante da próxima tentativa registrado. Toda solicitação nova MUST começar com zero tentativas e elegível para processamento imediato. (Item 4, Diferencial)

#### Scenario: Nova tentativa agendada
- **WHEN** uma solicitação `PROCESSING` com 0 tentativas registra falha temporária com próxima tentativa em T
- **THEN** o status volta a `PENDING`, o contador de tentativas é 1 e a próxima tentativa é T

#### Scenario: Estado inicial
- **WHEN** uma solicitação é criada no instante T0
- **THEN** o contador de tentativas é 0 e a próxima tentativa é T0

### Requirement: Proteção contra atualização concorrente
O serviço SHALL rejeitar a gravação de uma solicitação feita a partir de uma cópia desatualizada, quando outra gravação da mesma solicitação já tiver sido confirmada (lock otimista). (Item 4, Diferencial)

#### Scenario: Duas atualizações a partir da mesma versão
- **WHEN** duas cópias da mesma solicitação são lidas, a primeira é alterada e gravada e depois a segunda é alterada e gravada
- **THEN** a segunda gravação falha com erro de conflito de versão e o banco mantém a alteração da primeira

### Requirement: Motivo da falha definitiva na resposta
Toda resposta com uma solicitação SHALL trazer o campo `failureReason`. Em `FAILED`, o valor MUST ser `PROVIDER_REJECTED` (provedor recusou o pedido) ou `MAX_ATTEMPTS_EXCEEDED` (limite de tentativas atingido). Nos demais status, o valor MUST ser `null`. (Itens 2 e 5)

#### Scenario: Solicitação recém-criada
- **WHEN** um cliente cria uma solicitação válida
- **THEN** o corpo da resposta `201` traz `failureReason` igual a `null`

#### Scenario: Solicitação recusada pelo provedor
- **WHEN** o provedor responde `400` ao envio de uma solicitação e um cliente chama `GET /api/v1/requests/{id}`
- **THEN** a resposta traz `status` `FAILED` e `failureReason` `PROVIDER_REJECTED`

#### Scenario: Motivo na listagem
- **WHEN** uma solicitação atingiu o limite de tentativas e um cliente chama `GET /api/v1/requests?status=FAILED`
- **THEN** o item dessa solicitação traz `failureReason` `MAX_ATTEMPTS_EXCEEDED`
