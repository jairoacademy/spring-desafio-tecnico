# ADR 0003 — Política de retry e idempotência com o provedor

**Status:** aceito

## Contexto

O provedor pode responder sucesso, recusar o pedido, falhar temporariamente, pedir para esperar (`429`/`503` com `Retry-After`) ou não responder (timeout, conexão encerrada). Cada caso precisa de um comportamento definido. Repetir a chamada pode entregar a mesma ativação duas vezes: depois de um timeout não se sabe se o provedor processou o pedido.

## Decisão

- Classificação do retorno (`ProviderGateway`, interface selada `ProviderOutcome`):

  | Retorno do provedor | Efeito |
  |---|---|
  | `2xx` | `COMPLETED` |
  | `400` e demais `4xx`, exceto `408` e `429` | `FAILED` com `PROVIDER_REJECTED`, sem nova chamada |
  | `5xx`, `408`, `429` sem `Retry-After`, timeout, erro de conexão | volta a `PENDING` com backoff |
  | `429` ou `503` com `Retry-After` válido (segundos ou data HTTP) | volta a `PENDING` no instante pedido pelo provedor |
  | falha temporária na última tentativa permitida | `FAILED` com `MAX_ATTEMPTS_EXCEEDED` |

- Backoff exponencial com "equal jitter": para a tentativa n, `D = min(max, base × 2^(n-1))` e o atraso é sorteado em `[D/2, D]`. A metade fixa evita atraso zero; a sorteada espalha as novas tentativas de solicitações que falharam juntas. Padrões: `base=2s`, `max=5m`, `max-attempts=5`.
- O retry é **persistido**: `attempts` e `next_attempt_at` na própria solicitação. O worker libera a solicitação entre tentativas, e qualquer instância pode fazer a próxima.
- Toda chamada leva o header `Idempotency-Key` com o `id` da solicitação, igual em todas as tentativas, inclusive após reconciliação.
- `@Retryable` (nativo no Spring Framework 7) não é usado. Ele repete em memória, dentro da mesma execução: o estado se perde se a instância cair, a thread do worker fica parada durante o backoff (com `Retry-After: 120`, dois minutos) e outras instâncias não sabem que a solicitação está em retry.

## Consequências

- **Premissa:** o provedor deduplica pela `Idempotency-Key`. Timeout, reconciliação e resultado tardio descartado podem gerar mais de uma chamada para a mesma solicitação. Se o provedor não deduplicar, ou guardar a chave por pouco tempo, `max-attempts` e `backoff.max` precisam ser revistos.
- Um `4xx` causado por erro nosso (por exemplo `401` por configuração) também vira `PROVIDER_REJECTED`, com log `WARN` do status. Reprocessar manualmente uma solicitação `FAILED` fica fora do escopo; o `failureReason` e a listagem por `status=FAILED` permitem encontrá-las.
- `Retry-After` muito longo é respeitado sem teto; a solicitação fica `PENDING` até lá.
- **Circuit breaker como evolução.** Com o provedor fora do ar, cada rodada envia no máximo `batch-size` chamadas, e o backoff afasta as próximas tentativas de cada solicitação; isso já limita a pressão. Um circuit breaker (Resilience4j) evitaria até essas chamadas: depois de X falhas seguidas, o worker pularia rodadas por um tempo e testaria com uma única chamada antes de voltar. Não foi implementado porque traz uma dependência e mais um estado para explicar, sem mudar o resultado final.
