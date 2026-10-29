# ADR 0005 — Falha temporária volta para `PENDING`

**Status:** aceito

## Contexto

O diagrama do enunciado só tem `PENDING → PROCESSING → COMPLETED | FAILED` e pergunta o que acontece com uma falha temporária: volta para `PENDING` ou permanece em `PROCESSING` com contador de tentativas?

## Decisão

Uma falha temporária leva `PROCESSING → PENDING`, incrementa `attempts` e agenda `next_attempt_at`. Ao atingir o limite de tentativas, a falha temporária leva a `FAILED` com `failureReason = MAX_ATTEMPTS_EXCEEDED`.

```
PENDING ──► PROCESSING ──┬──► COMPLETED
   ▲                     ├──► FAILED  (PROVIDER_REJECTED ou MAX_ATTEMPTS_EXCEEDED)
   └─────────────────────┘
   falha temporária (attempts++, next_attempt_at agendado)
```

Cada estado tem um único significado:

- `PENDING`: falta enviar, a partir de `next_attempt_at`;
- `PROCESSING`: chamada ao provedor em andamento, reservada por uma instância;
- `COMPLETED` e `FAILED`: finais.

Alternativa descartada: permanecer em `PROCESSING` com contador. `PROCESSING` passaria a misturar "chamada em andamento" com "esperando nova tentativa". O worker precisaria buscar `PROCESSING` vencido além de `PENDING`, e a reconciliação não conseguiria distinguir uma instância que caiu no meio da chamada de uma solicitação que só está esperando o backoff.

## Consequências

- O worker busca só `PENDING` vencido, e a reconciliação só `PROCESSING` antigo; cada consulta tem seu índice parcial.
- `PROCESSING` parado além de `stale-after` significa, sem ambiguidade, que a chamada se perdeu. A reconciliação trata como falha temporária (conta tentativa), porque o desfecho real é desconhecido.
- A transição `PROCESSING → PENDING` já estava em `ActivationStatus` desde a primeira change; esta decisão a documenta e acrescenta o limite de tentativas no domínio (`registerTemporaryFailure`).
- O cliente pode ver a solicitação voltar de `PROCESSING` para `PENDING` no `GET`. O número de tentativas e a próxima tentativa continuam internos e não aparecem na API.
