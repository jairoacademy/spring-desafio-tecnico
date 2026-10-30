# Cenário de análise

[← Voltar ao README](../../README.md)

Cenário do enunciado (item 10): o tempo médio da API foi de 300 ms para 2,5 s, a CPU está em 45%, a memória em 60%, algumas consultas foram de 50 ms para 1,2 s e o serviço externo foi de 400 ms para 3 s.

## 1. O que investigar primeiro

CPU e memória estão normais, então o sistema não está sobrecarregado: está **esperando** alguma coisa. Primeiro vejo o pool de conexões com o banco (métricas `hikaricp_connections_pending` e `hikaricp_connections_active`). Se há muitas conexões em espera, as requisições estão na fila. Também pergunto o que mudou: deploy, volume de requisições, dados.

## 2. Aplicação, banco ou integração externa

Meço o tempo gasto em cada parte:

- API inteira: `http_server_requests_seconds`.
- Chamada ao provedor: `http_client_requests_seconds` filtrada pela rota do provedor.
- Consultas: o tempo de cada consulta no banco.

A parte cujo tempo cresceu junto com a lentidão é a suspeita. Um thread dump mostra onde as threads da aplicação estão paradas: esperando o provedor, esperando uma conexão do banco ou esperando uma consulta.

## 3. Logs e métricas úteis

- Logs em JSON com `traceId`, para seguir uma requisição lenta até a chamada ao provedor.
- Tempo p95/p99 por rota e taxa de erro do provedor.
- Métricas do pool de conexões.
- Plano de execução (`EXPLAIN ANALYZE`) das consultas lentas.

## 4. Melhorias possíveis

- Índice ou ajuste na consulta, se o plano mostrar leitura de tabela inteira.
- Timeout, nova tentativa com espera crescente e circuit breaker na chamada ao provedor.
- Pool de conexões e timeouts dimensionados.

## 5. Causa comum (diferencial)

Hipótese: o provedor ficou lento (3 s) e a aplicação mantém uma conexão do banco aberta enquanto espera a resposta dele, por exemplo chamando o provedor dentro de uma transação. As conexões ficam presas, o pool esgota e as outras requisições esperam na fila. As consultas parecem lentas (1,2 s) sem ter problema próprio, e a API soma tudo (2,5 s).

Confirmo se `hikaricp_connections_pending` sobe junto com a lentidão do provedor e se o thread dump mostra threads esperando conexão.

Neste projeto o desenho evita isso: o envio ao provedor roda fora de transação e a API responde sem esperar por ele (ver [Decisões técnicas](decisoes-tecnicas.md#integração-com-o-provedor)).

## 6. Validar a correção (diferencial)

Comparo o tempo de resposta (p95/p99), a taxa de erro e `hikaricp_connections_pending` antes e depois da correção, no mesmo intervalo e com carga parecida. Deixo um alerta no p95 da API para perceber uma regressão.
