# Spec Delta

## ADDED Requirements

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
