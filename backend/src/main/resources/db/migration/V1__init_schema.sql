-- =============================================================
-- V1__init_schema.sql — pełny schemat bazy danych ksef-app
-- =============================================================

CREATE TABLE users (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                           VARCHAR(255) NOT NULL UNIQUE,
    nip                             VARCHAR(10)  NOT NULL,
    company_name                    VARCHAR(255) NOT NULL,
    -- Token KSeF użytkownika używany do inicjalizacji auth
    ksef_token                      TEXT,
    -- Krótkotrwały JWT Bearer do wywołań API KSeF v2
    ksef_access_token               TEXT,
    ksef_access_token_valid_until   TIMESTAMP,
    -- Długotrwały JWT do odnawiania access tokena bez pełnego re-auth
    ksef_refresh_token              TEXT,
    ksef_refresh_token_valid_until  TIMESTAMP,
    created_at                      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE xlsx_configurations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    -- JSON: {"fieldName": {"type": "CELL"|"VALUE", "cellRef": "A1", "value": "..."}}
    field_mappings JSONB        NOT NULL DEFAULT '{}',
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE invoices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Numery KSeF
    ksef_number           VARCHAR(255),
    ksef_reference_number VARCHAR(255),
    -- Skrót SHA-256 faktury (Base64) z SessionInvoiceStatusResponse.invoiceHash
    -- i QueryMetadataResponse.InvoiceMetadata.invoiceHash.
    -- Używany jako ostatni segment URL wizualizacji KSeF v2:
    --   https://qr[-test].ksef.mf.gov.pl/invoice/{nip}/{DD-MM-YYYY}/{invoiceHash}
    invoice_hash          VARCHAR(255),

    -- Metadane faktury
    direction       VARCHAR(10)   NOT NULL CHECK (direction IN ('ISSUED', 'RECEIVED')),
    status          VARCHAR(30)   NOT NULL DEFAULT 'DRAFT',
    -- status: DRAFT | QUEUED | SENDING | SENT | FAILED | RECEIVED_FROM_KSEF
    source          VARCHAR(10)   NOT NULL DEFAULT 'FORM' CHECK (source IN ('FORM', 'XLSX', 'KSEF')),
    invoice_number  VARCHAR(255),
    issue_date      DATE          NOT NULL,
    sale_date       DATE,

    -- Sprzedawca
    seller_name         VARCHAR(500) NOT NULL,
    seller_nip          VARCHAR(10)  NOT NULL,
    seller_address      TEXT,
    seller_country_code VARCHAR(2)   NOT NULL DEFAULT 'PL',

    -- Nabywca
    buyer_name         VARCHAR(500) NOT NULL,
    buyer_nip          VARCHAR(10)  NOT NULL,
    buyer_address      TEXT,
    buyer_country_code VARCHAR(2)   NOT NULL DEFAULT 'PL',

    -- Kwoty
    net_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    vat_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    gross_amount    NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency        VARCHAR(3)    NOT NULL DEFAULT 'PLN',

    -- Treść i błędy
    fa2_xml         TEXT,
    error_message   TEXT,

    -- Pola FA(3)
    rodzaj_faktury                  VARCHAR(10)  NOT NULL DEFAULT 'VAT',
    metoda_kasowa                   BOOLEAN      NOT NULL DEFAULT FALSE,
    samofakturowanie                BOOLEAN      NOT NULL DEFAULT FALSE,
    odwrotne_obciazenie             BOOLEAN      NOT NULL DEFAULT FALSE,
    mechanizm_podzielonej_platnosci BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Adnotacje FA(3)
    -- Podstawa prawna zwolnienia (P_19) — wymagana gdy pozycje mają stawkę "zw"
    zwolnienie_podatkowe TEXT,
    -- Podmiot2.JST: czy faktura dotyczy jednostki podrzędnej JST
    jst                  BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Podmiot2.GV: czy faktura dotyczy członka grupy VAT
    gv                   BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE invoice_items (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id     UUID          NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    name           VARCHAR(500)  NOT NULL,
    unit           VARCHAR(50),
    quantity       NUMERIC(18,4) NOT NULL DEFAULT 1,
    net_unit_price NUMERIC(18,2) NOT NULL,
    -- FA(3) VAT rate code (TStawkaPodatku): "23","8","5","0 KR","0 WDT","0 EX","zw","oo","np I","np II"
    vat_rate_code  VARCHAR(10),
    vat_rate       NUMERIC(5,2)  NOT NULL DEFAULT 23,
    net_amount     NUMERIC(18,2) NOT NULL,
    vat_amount     NUMERIC(18,2) NOT NULL,
    gross_amount   NUMERIC(18,2) NOT NULL,
    position       INT           NOT NULL DEFAULT 1
);

-- Indeksy
CREATE INDEX idx_invoices_user_id      ON invoices(user_id);
CREATE INDEX idx_invoices_status       ON invoices(status);
CREATE INDEX idx_invoices_direction    ON invoices(direction);
CREATE INDEX idx_invoices_ksef_number  ON invoices(ksef_number);
CREATE INDEX idx_invoice_items_invoice ON invoice_items(invoice_id);
CREATE INDEX idx_xlsx_config_user      ON xlsx_configurations(user_id);
