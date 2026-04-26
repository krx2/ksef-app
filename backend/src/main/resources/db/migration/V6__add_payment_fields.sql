ALTER TABLE invoices
    ADD COLUMN payment_due_date  DATE,
    ADD COLUMN bank_account_number VARCHAR(35),
    ADD COLUMN bank_name           VARCHAR(255);
