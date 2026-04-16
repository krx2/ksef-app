-- Kod PIN użytkownika (hash BCrypt). NULL = brak PIN-u (konto przed migracją).
ALTER TABLE users ADD COLUMN pin_hash VARCHAR(255);
