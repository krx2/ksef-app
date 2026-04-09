-- Migracja V3: dodanie pola invoice_hash do tabeli invoices
--
-- Skrót SHA-256 faktury (Base64) zwracany przez KSeF API v2 w:
--   - SessionInvoiceStatusResponse.invoiceHash (po wysyłce faktury)
--   - QueryMetadataResponse.InvoiceMetadata.invoiceHash (po pobraniu z KSeF)
--
-- Używany jako ostatni segment URL wizualizacji KSeF v2:
--   https://qr[-test].ksef.mf.gov.pl/invoice/{nip}/{DD-MM-YYYY}/{invoiceHash}

ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS invoice_hash VARCHAR(255);
