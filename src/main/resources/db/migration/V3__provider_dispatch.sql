ALTER TABLE activation_request
    ADD COLUMN failure_reason varchar(30)
        CONSTRAINT ck_activation_request_failure_reason
        CHECK (failure_reason IN ('PROVIDER_REJECTED', 'MAX_ATTEMPTS_EXCEEDED'));

-- Reserva do worker: só PENDING, na ordem de next_attempt_at.
CREATE INDEX ix_activation_request_pending_due
    ON activation_request (next_attempt_at) WHERE status = 'PENDING';

-- Reconciliação: só PROCESSING, por updated_at.
CREATE INDEX ix_activation_request_processing_updated
    ON activation_request (updated_at) WHERE status = 'PROCESSING';
