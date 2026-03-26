-- V1__init_schema.sql

CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    nip         VARCHAR(10)  NOT NULL,
    company_name VARCHAR(255) NOT NULL,
    ksef_token  TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE xlsx_configurations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    -- JSON column storing field mappings: {"fieldName": {"type": "CELL"|"VALUE", "cellRef": "A1", "value": "..."}}
    field_mappings JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE invoices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ksef_number     VARCHAR(255),
    ksef_reference_number VARCHAR(255),
    direction       VARCHAR(10)  NOT NULL CHECK (direction IN ('ISSUED', 'RECEIVED')),
    status          VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    -- status: DRAFT | QUEUED | SENDING | SENT | FAILED | RECEIVED_FROM_KSEF
    invoice_number  VARCHAR(255),
    issue_date      DATE         NOT NULL,
    sale_date       DATE,
    seller_name     VARCHAR(500) NOT NULL,
    seller_nip      VARCHAR(10)  NOT NULL,
    seller_address  TEXT,
    buyer_name      VARCHAR(500) NOT NULL,
    buyer_nip       VARCHAR(10)  NOT NULL,
    buyer_address   TEXT,
    net_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    vat_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    gross_amount    NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'PLN',
    -- raw FA(2) XML sent/received
    fa2_xml         TEXT,
    error_message   TEXT,
    source          VARCHAR(10)  NOT NULL DEFAULT 'FORM' CHECK (source IN ('FORM', 'XLSX')),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE invoice_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id      UUID         NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    name            VARCHAR(500) NOT NULL,
    unit            VARCHAR(50),
    quantity        NUMERIC(18,4) NOT NULL DEFAULT 1,
    net_unit_price  NUMERIC(18,2) NOT NULL,
    vat_rate        NUMERIC(5,2)  NOT NULL DEFAULT 23,
    net_amount      NUMERIC(18,2) NOT NULL,
    vat_amount      NUMERIC(18,2) NOT NULL,
    gross_amount    NUMERIC(18,2) NOT NULL,
    position        INT          NOT NULL DEFAULT 1
);

CREATE INDEX idx_invoices_user_id        ON invoices(user_id);
CREATE INDEX idx_invoices_status         ON invoices(status);
CREATE INDEX idx_invoices_direction      ON invoices(direction);
CREATE INDEX idx_invoices_ksef_number    ON invoices(ksef_number);
CREATE INDEX idx_invoice_items_invoice   ON invoice_items(invoice_id);
CREATE INDEX idx_xlsx_config_user        ON xlsx_configurations(user_id);
