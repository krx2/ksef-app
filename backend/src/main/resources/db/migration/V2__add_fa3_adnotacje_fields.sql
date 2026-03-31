-- V2__add_fa3_adnotacje_fields.sql
-- Dodaje pola potrzebne do poprawnego budowania sekcji Adnotacje w FA(3):
--   zwolnienie_podatkowe  — podstawa prawna zwolnienia (P_19), wymagana gdy pozycje mają stawkę "zw"
--   jst                  — Podmiot2.JST: czy faktura dotyczy jednostki podrzędnej JST (1=tak, 2=nie)
--   gv                   — Podmiot2.GV:  czy faktura dotyczy członka grupy VAT (1=tak, 2=nie)

ALTER TABLE invoices
    ADD COLUMN zwolnienie_podatkowe TEXT,
    ADD COLUMN jst BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN gv  BOOLEAN NOT NULL DEFAULT FALSE;
