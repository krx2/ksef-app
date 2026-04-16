-- =============================================================
-- V5__user_invoice_prefix.sql
-- Feature 2: Prefiks daty RRRR/MM/ w numerze faktury
-- =============================================================

-- TODO(F2): Dodaje tryb prefiksu numeru faktury dla użytkownika.
-- Wartości: NONE (bez prefiksu, domyślnie) | YEAR_MONTH (format 2026/04/1)
-- Obsługa w AppUser.java (pole invoicePrefixMode) i InvoiceService.java (metoda applyPrefix).
ALTER TABLE users
    ADD COLUMN invoice_number_prefix_mode VARCHAR(20) NOT NULL DEFAULT 'NONE';
