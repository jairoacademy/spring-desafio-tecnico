# provider-integration Specification

## Purpose

Define como o serviço envia cada solicitação de ativação ao provedor externo, de forma assíncrona e sem perder envios, e como cada retorno do provedor decide o novo status, as novas tentativas e a falha definitiva.

## Requirements

### Requirement: Criação não espera o provedor
O `POST /api/v1/requests` SHALL responder sem chamar o provedor externo. O tempo de resposta e o resultado da criação MUST ser os mesmos com o provedor disponível, lento ou fora do ar. O envio ao provedor acontece depois, de forma assíncrona. (Item 5, Diferencial)

#### Scenario: Provedor fora do ar na criação
- **WHEN** o provedor responde `500` a toda chamada e um cliente cria uma solicitação válida
- **THEN** a resposta é `201` com `status` igual a `PENDING`

#### Scenario: Provedor lento na criação
- **WHEN** o provedor demora mais que o read timeout configurado para responder e um cliente cria uma solicitação válida
- **THEN** a resposta é `201` sem esperar o provedor

### Requirement: Envio garantido depois do commit
Toda solicitação `PENDING` cuja próxima tentativa já venceu SHALL ser enviada ao provedor por um processamento periódico, inclusive solicitações gravadas antes de uma parada da aplicação. A chamada ao provedor MUST acontecer fora de transação de banco aberta. (Item 5, Diferencial)

#### Scenario: Processamento assíncrono até a conclusão
- **WHEN** o processamento periódico está ativo, o provedor responde `200` e um cliente cria uma solicitação
- **THEN** em poucos segundos `GET /api/v1/requests/{id}` devolve `status` igual a `COMPLETED`

#### Scenario: Solicitação gravada antes de uma parada
- **WHEN** existe no banco uma solicitação `PENDING` vencida que nunca foi enviada e o processamento periódico roda
- **THEN** o provedor recebe uma chamada para essa solicitação

#### Scenario: Próxima tentativa no futuro
- **WHEN** uma solicitação `PENDING` tem a próxima tentativa daqui a 1 minuto e o processamento periódico roda
- **THEN** o provedor não recebe chamada para essa solicitação e ela continua `PENDING`

### Requirement: Contrato da chamada ao provedor
Cada envio SHALL ser um `POST /provider/v1/activate` com corpo JSON contendo `externalId`, `customerId` e `productCode` da solicitação e o header `Idempotency-Key` igual ao `id` da solicitação. Toda nova tentativa da mesma solicitação MUST repetir a mesma `Idempotency-Key`. (Item 5)

#### Scenario: Corpo e header da chamada
- **WHEN** a solicitação com `id` X, `externalId` `EXT-1`, `customerId` `CUST-1` e `productCode` `INTERNET-500` é enviada
- **THEN** o provedor recebe `POST /provider/v1/activate` com esses três campos no corpo JSON e `Idempotency-Key: X`

#### Scenario: Mesma chave na nova tentativa
- **WHEN** a primeira chamada de uma solicitação termina em timeout e a solicitação é enviada de novo
- **THEN** as duas chamadas recebidas pelo provedor têm o mesmo valor de `Idempotency-Key`

### Requirement: Sucesso do provedor conclui a solicitação
Uma resposta `2xx` do provedor SHALL levar a solicitação a `COMPLETED`, sem nova chamada. (Item 5)

#### Scenario: Provedor responde 200
- **WHEN** o provedor responde `200` ao envio de uma solicitação
- **THEN** a solicitação fica `COMPLETED`, `failureReason` é nulo e o provedor recebeu exatamente uma chamada

### Requirement: Rejeição do provedor é falha definitiva
Uma resposta `400`, ou outro `4xx` que não seja `408` nem `429`, SHALL levar a solicitação a `FAILED` com `failureReason` igual a `PROVIDER_REJECTED`, sem nova chamada. Repetir o mesmo pedido não muda a resposta. (Item 5)

#### Scenario: Provedor responde 400
- **WHEN** o provedor responde `400` ao envio de uma solicitação
- **THEN** a solicitação fica `FAILED` com `failureReason` `PROVIDER_REJECTED` e o contador de tentativas não muda

#### Scenario: Nenhuma nova chamada após rejeição
- **WHEN** o provedor responde `400` a uma solicitação e o processamento periódico roda de novo
- **THEN** o provedor recebeu exatamente uma chamada para essa solicitação

### Requirement: Falha temporária agenda nova tentativa com backoff
Uma resposta `5xx`, `408` ou `429`, um timeout de leitura ou um erro de conexão SHALL devolver a solicitação a `PENDING`, com o contador de tentativas incrementado e a próxima tentativa agendada pelo backoff, enquanto o limite de tentativas não for atingido. (Item 5)

#### Scenario: Provedor responde 500
- **WHEN** o provedor responde `500` à primeira chamada de uma solicitação
- **THEN** a solicitação volta a `PENDING`, o contador de tentativas é 1 e a próxima tentativa fica no futuro, dentro da faixa do backoff para a tentativa 1

#### Scenario: Timeout de leitura
- **WHEN** o provedor demora mais que o read timeout configurado para responder à primeira chamada
- **THEN** a solicitação volta a `PENDING`, o contador de tentativas é 1 e a próxima tentativa fica no futuro

#### Scenario: Falha de conexão
- **WHEN** a conexão com o provedor é encerrada sem resposta na primeira chamada
- **THEN** a solicitação volta a `PENDING` e o contador de tentativas é 1

#### Scenario: Nova tentativa conclui depois da falha
- **WHEN** o provedor responde `500` à primeira chamada, a próxima tentativa vence e o provedor responde `200` à segunda
- **THEN** a solicitação fica `COMPLETED` e o contador de tentativas é 1

### Requirement: Backoff exponencial com jitter
O atraso da tentativa n (contador já incrementado) SHALL ser `D = min(max, base × 2^(n-1))`, com jitter que sorteia o atraso efetivo entre `D/2` e `D`. `base` e `max` MUST ser configuráveis. (Item 5, Diferencial)

#### Scenario: Atraso cresce a cada tentativa
- **WHEN** `base` é 2 s e `max` é 5 min, e o backoff é calculado para as tentativas 1, 2 e 3
- **THEN** os atrasos ficam entre 1 s e 2 s, entre 2 s e 4 s e entre 4 s e 8 s

#### Scenario: Atraso limitado ao máximo
- **WHEN** `base` é 2 s e `max` é 5 min, e o backoff é calculado para a tentativa 20
- **THEN** o atraso fica entre 2,5 min e 5 min

### Requirement: Retry-After do provedor é respeitado
Em uma resposta `429` ou `503` com header `Retry-After` válido, em segundos ou em data HTTP, a próxima tentativa SHALL ser agendada para o instante indicado pelo provedor, no lugar do backoff. Sem header, ou com header inválido, vale o backoff. (Item 5, Diferencial)

#### Scenario: 429 com Retry-After em segundos
- **WHEN** o provedor responde `429` com `Retry-After: 120` no instante T
- **THEN** a solicitação volta a `PENDING`, o contador de tentativas é 1 e a próxima tentativa é T + 120 s

#### Scenario: 503 com Retry-After em data HTTP
- **WHEN** o provedor responde `503` com `Retry-After` igual a uma data HTTP 10 minutos à frente
- **THEN** a próxima tentativa é essa data

#### Scenario: 429 sem Retry-After
- **WHEN** o provedor responde `429` sem header `Retry-After`
- **THEN** a próxima tentativa segue o backoff para a tentativa 1

### Requirement: Limite de tentativas
Quando uma falha temporária acontece na última chamada permitida pelo limite configurado de tentativas, a solicitação SHALL ir para `FAILED` com `failureReason` igual a `MAX_ATTEMPTS_EXCEEDED`, sem nova chamada. (Item 5, Diferencial)

#### Scenario: Limite atingido
- **WHEN** o limite é 3 chamadas e o provedor responde `500` às três chamadas de uma solicitação
- **THEN** a solicitação fica `FAILED` com `failureReason` `MAX_ATTEMPTS_EXCEEDED` e o provedor recebeu exatamente 3 chamadas

### Requirement: Uma solicitação por vez em cada envio
Duas execuções do processamento, na mesma instância ou em instâncias diferentes, SHALL NOT reservar a mesma solicitação ao mesmo tempo. Uma execução MUST pular as solicitações já reservadas por outra, sem esperar por elas. (Item 5, Diferencial)

#### Scenario: Reservas simultâneas não se sobrepõem
- **WHEN** existem 4 solicitações `PENDING` vencidas e duas execuções reservam lotes de até 2 solicitações ao mesmo tempo, cada uma em sua transação
- **THEN** cada solicitação fica em exatamente um dos lotes e nenhuma das execuções espera a outra terminar

### Requirement: Reconciliação de PROCESSING antigo
Uma solicitação em `PROCESSING` há mais tempo que o limite configurado SHALL voltar a `PENDING` como falha temporária, ou ir para `FAILED` com `MAX_ATTEMPTS_EXCEEDED` se o limite de tentativas já foi atingido. O limite de tempo MUST ser maior que o read timeout. (Item 5, Diferencial)

#### Scenario: Solicitação presa em PROCESSING
- **WHEN** uma solicitação está em `PROCESSING` há mais tempo que o limite e a reconciliação roda
- **THEN** a solicitação volta a `PENDING` com o contador de tentativas incrementado em 1

#### Scenario: PROCESSING recente não é tocado
- **WHEN** uma solicitação está em `PROCESSING` há menos tempo que o limite e a reconciliação roda
- **THEN** a solicitação continua `PROCESSING` com o mesmo contador de tentativas

#### Scenario: Resposta tardia depois da reconciliação
- **WHEN** a reconciliação devolve uma solicitação a `PENDING` e depois chega a resposta `200` da chamada antiga
- **THEN** a resposta tardia é descartada sem erro e a solicitação continua `PENDING`, para ser reenviada com a mesma `Idempotency-Key`
