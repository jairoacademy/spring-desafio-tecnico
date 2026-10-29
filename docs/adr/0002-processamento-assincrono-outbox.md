# ADR 0002 — Processamento assíncrono com a tabela como outbox

**Status:** aceito

## Contexto

Cada solicitação precisa ser enviada ao provedor externo (`POST /provider/v1/activate`), que pode estar lento ou fora do ar. Chamar o provedor dentro do `POST /api/v1/requests` faria o tempo de resposta depender dele e prenderia threads do Tomcat durante uma queda. Pior: uma queda da aplicação entre o commit da criação e a chamada deixaria a solicitação gravada e nunca enviada.

Também não pode haver transação de banco aberta durante a chamada HTTP (conexão do pool presa por segundos), e várias instâncias da aplicação não podem enviar a mesma solicitação ao mesmo tempo.

## Decisão

- O `POST` só grava a solicitação `PENDING` e responde `201`. O envio é feito depois por um worker `@Scheduled(fixedDelay)` (`ActivationDispatcher.dispatchDue`).
- A própria tabela `activation_request` é o outbox: `status = 'PENDING' AND next_attempt_at <= now` já significa "falta enviar". O commit da criação é, ao mesmo tempo, o registro do envio pendente.
- Cada ciclo do worker tem três passos:
  1. **Reserva** (transação curta): `SELECT ... FOR UPDATE SKIP LOCKED` com `LIMIT batch-size`, `startProcessing` em cada linha e commit.
  2. **Chamada** ao provedor, sem transação aberta.
  3. **Resultado** (uma transação curta por item): relê a solicitação e aplica o desfecho só se a versão (`@Version`) for a mesma da reserva.
- `SKIP LOCKED` faz cada instância pular as linhas reservadas por outra, sem esperar: várias instâncias dividem o trabalho sem lock distribuído.
- Uma reconciliação periódica (`reconcileStale`) devolve para `PENDING` as solicitações presas em `PROCESSING` além de `stale-after`, caso de uma instância que caiu entre a reserva e o resultado.
- Índices parciais (migração `V3`) atendem as duas consultas: `next_attempt_at WHERE status = 'PENDING'` e `updated_at WHERE status = 'PROCESSING'`.

Alternativas descartadas:
- Tabela de outbox separada: duplicaria o estado da solicitação sem ganho, já que o único consumidor é o worker.
- `@TransactionalEventListener(AFTER_COMMIT)` ou `@Async`: o evento vive em memória e se perde se a aplicação cair.
- Só `@Version` na reserva: duas instâncias leriam as mesmas linhas e uma perderia o lote inteiro no commit.

## Consequências

- A criação responde no mesmo tempo com o provedor disponível, lento ou fora do ar.
- Nenhuma solicitação gravada fica sem envio: após uma queda ou um deploy, a próxima rodada encontra as linhas `PENDING`.
- O `status` muda sozinho depois da criação; o cliente acompanha pelo `GET`.
- Há latência entre a criação e o envio, de até `activation.dispatch.interval` (padrão 1 s). Evolução possível: acordar o worker logo após o commit, mantendo a varredura como rede de segurança.
- As chamadas de um lote são em sequência; a vazão por instância é limitada a `batch-size` por rodada. Paralelizar por item não muda o modelo.
- A reconciliação e a reserva dependem de `FOR UPDATE SKIP LOCKED`, que o PostgreSQL tem e o H2 não: os testes rodam contra PostgreSQL real (Testcontainers).
- `activation.dispatch.enabled=false` desliga o envio sem novo deploy.
