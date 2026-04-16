-- =============================================================
-- V6__user_notification_emails.sql
-- Feature: Wiele adresów email do powiadomień per użytkownik
-- =============================================================

-- TODO(FMAIL): Tabela adresów email do powiadomień.
--   Oddzielona od users.email, który służy wyłącznie do logowania (NIP → email, unique).
--   Użytkownik może skonfigurować dowolną liczbę odbiorców powiadomień, np.:
--     - własna skrzynka
--     - biuro rachunkowe
--     - współwłaściciel firmy
--
--   FALLBACK: jeśli tabela jest pusta dla danego użytkownika, EmailService
--   powinien wysłać na users.email (dotychczasowe zachowanie).
--   Dzięki temu użytkownicy którzy nie konfigurują tej listy nie tracą powiadomień.
CREATE TABLE user_notification_emails (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Adres email odbiorcy powiadomień
    email      VARCHAR(255) NOT NULL,

    -- Opcjonalna etykieta ułatwiająca identyfikację, np. "Biuro rachunkowe", "Właściciel"
    label      VARCHAR(100),

    -- Kolejność wyświetlania na liście w UI (ASC)
    sort_order INT          NOT NULL DEFAULT 0,

    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    -- Ten sam email może być dodany tylko raz per użytkownik
    CONSTRAINT uq_user_notification_email UNIQUE (user_id, email)
);

CREATE INDEX idx_user_notification_emails_user ON user_notification_emails(user_id);
