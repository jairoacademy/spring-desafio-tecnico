-- Contexto de trace W3C (traceparent, 55 caracteres) da requisição de criação: o worker continua esse trace
-- ao enviar ao provedor. Anulável e sem backfill: linhas antigas são enviadas em trace novo.
-- Rollback: a versão anterior ignora a coluna (ddl-auto=validate não reclama de coluna extra).
ALTER TABLE activation_request ADD COLUMN trace_parent varchar(55);
