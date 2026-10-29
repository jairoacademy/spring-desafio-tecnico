CREATE TABLE activation_request (
    id              uuid         PRIMARY KEY,
    external_id     varchar(64)  NOT NULL,
    customer_id     varchar(64)  NOT NULL,
    product_code    varchar(50)  NOT NULL,
    status          varchar(20)  NOT NULL
        CONSTRAINT ck_activation_request_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL
);

-- Listagem por cliente (com ou sem status), já na ordem da paginação.
CREATE INDEX ix_activation_request_customer_created ON activation_request (customer_id, created_at DESC);

-- Listagem só por status, já na ordem da paginação.
CREATE INDEX ix_activation_request_status_created ON activation_request (status, created_at DESC);
