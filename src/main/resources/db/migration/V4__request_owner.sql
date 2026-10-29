-- Dono da solicitação: claim client_id do JWT (ADR 0004). O DEFAULT só preenche linhas de bases locais
-- anteriores; depois sai, para que toda inserção informe o dono.
ALTER TABLE activation_request ADD COLUMN client_id varchar(255) NOT NULL DEFAULT 'legacy';
ALTER TABLE activation_request ALTER COLUMN client_id DROP DEFAULT;

-- Toda listagem filtra pelo dono: os índices de V1 dão lugar a índices prefixados por client_id,
-- já na ordem da paginação (sem filtro, por cliente e por status).
DROP INDEX ix_activation_request_customer_created;
DROP INDEX ix_activation_request_status_created;
CREATE INDEX ix_activation_request_client_created ON activation_request (client_id, created_at DESC);
CREATE INDEX ix_activation_request_client_customer_created ON activation_request (client_id, customer_id, created_at DESC);
CREATE INDEX ix_activation_request_client_status_created ON activation_request (client_id, status, created_at DESC);
