-- Unicidade global do externalId (ADR 0001); o índice único também atende a busca por externalId.
ALTER TABLE activation_request
    ADD CONSTRAINT uq_activation_request_external_id UNIQUE (external_id);
