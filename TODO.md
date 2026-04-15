# TODO — KSeF App

## Zestawienie funkcjonalności

### Backend — kontrolery / API

| Endpoint | Opis |
|---|---|
| `GET/POST /api/invoices` | Lista faktur z filtrami + tworzenie |
| `POST /api/invoices/xlsx-preview` | Parsowanie XLSX bez zapisu |
| `POST /api/invoices/{id}/queue` | Kolejkowanie faktury do KSeF |
| `POST /api/invoices/fetch-from-ksef` | Pobieranie faktur z KSeF |
| `GET/POST /api/users` | Rejestracja / logowanie po NIP |
| `PUT /api/users/{id}/ksef-token` | Aktualizacja tokenu KSeF |
| `PUT /api/users/{id}/invoice-prefix-mode` | Tryb prefiksu numeru faktury |
| `GET/POST/PUT/DELETE /api/xlsx-configs` | Konfiguracje mapowań XLSX |
| `GET/POST/PUT/DELETE /api/notification-emails` | Adresy email do powiadomień |
| `GET /api/reports?year=&month=` | Lista faktur miesiąca |
| `POST /api/reports/generate-pdf` | Generowanie raportu miesięcznego PDF |
| `GET /api/config/environment` | Środowisko KSeF (test/prod) |

### Backend — serwisy

| Serwis | Opis |
|---|---|
| `InvoiceService` | Tworzenie, walidacja, szkice, kolejkowanie |
| `XlsxParserService` | Parsowanie Excela z ewaluacją formuł, MULTI_CELL |
| `Fa3XmlBuilder` | Budowa XML FA(3) z encji faktury |
| `Fa3Validator` | Walidacja kompletności i poprawności FA(3) |
| `KsefApiClient` | Klient HTTP do KSeF API v2 (szyfrowanie, sesja, polling) |
| `KsefTokenManager` | Zarządzanie cyklem życia tokenu (cache → refresh → re-auth) |
| `MonthlyReportService` | Generowanie PDF raportu (OpenPDF, tabela, podsumowanie) |
| `EmailService` | Powiadomienia email (nowa faktura, potwierdzenie wysyłki) |
| `KsefPdfService` | Placeholder — oficjalne PDF z KSeF _(F6/F7 zablokowane)_ |
| `InvoiceQueueConsumer` | RabbitMQ — asynchroniczne wysyłanie do KSeF |
| `InvoiceDlqConsumer` | Dead-letter queue — obsługa failed invoices |

### Frontend — strony

| Strona | Opis |
|---|---|
| `/` | Dashboard: liczniki faktur, lista ostatnich, przycisk pobierania z KSeF |
| `/faktury` | Filtrowana lista faktur (kierunek, status, daty, wyszukiwarka) |
| `/faktury/nowa` | Formularz tworzenia faktury |
| `/faktury/nowa/xlsx` | Upload XLSX z podglądem i mapowaniem pól |
| `/raporty` | Generowanie raportu miesięcznego PDF |
| `/konfiguracja` | Ustawienia: token KSeF, prefiks numeru, adresy email powiadomień, konfiguracje XLSX |

### Baza danych (Flyway)

| Tabela | Opis |
|---|---|
| `users` | Konta użytkowników, tokeny KSeF, tryb prefiksu |
| `invoices` | Pełne dane faktury FA(3), status lifecycle, XML |
| `invoice_items` | Pozycje faktury z obliczeniami VAT |
| `xlsx_configurations` | Mapowania XLSX jako JSONB |
| `user_notification_emails` | Wiele adresów email per użytkownik |

---

## Planowane funkcjonalności

| ID | Opis | Status |
|---|---|---|
| F6 | Integracja `ksef-pdf-generator` — generowanie oficjalnego PDF KSeF + załącznik w emailu | Zablokowane (brak biblioteki w Maven Central) |
| F7 | `GET /api/invoices/{id}/pdf` + przycisk pobierania PDF w `/faktury` | Zablokowane (wymaga F6) |
| JWT | Zastąpienie nagłówka `X-User-Id` pełnym Spring Security + JWT | Do zaplanowania |
| TOKEN_ENC | Szyfrowanie tokenu KSeF w bazie (AES-256 / JPA AttributeConverter) | Do zaplanowania |

---

## Refaktor i poprawa jakości kodu

### A. Nadmiarowy / martwy kod

- [ ] **A1** `EmailService.java` ~166-177 i ~277-288 — `buildHtml()` i `buildSentConfirmationHtml()` mają identyczny szkielet HTML. Wyciągnąć do `buildEmailHtml(title, body)`.
- [ ] **A2** `MonthlyReportService.java` ~87 — metoda `capitalize()` reimplementuje `StringUtils.capitalize()` z Apache Commons (dostępnego na classpath). Usunąć własną implementację.
- [ ] **A3** `InvoiceQueueConsumer.java` ~34-37 — stałe `POLL_INTERVAL_MS`, `MAX_POLL_ATTEMPTS` zakodowane na sztywno. Przenieść do `@ConfigurationProperties(prefix = "ksef.polling")` (patrz E3).
- [ ] **A4** `Fa3Validator.java` — hardcoded `Set.of(...)` z kodami walut i krajów. Przenieść do pliku `.properties` lub `EnumSet`, żeby aktualizacja nie wymagała rekompilacji.
- [ ] **A5** `frontend/src/lib/api.ts` ~101-116 — zakomentowany blok `downloadPdf()`. Zaimplementować razem z F7 albo usunąć.

### B. Niespójności

- [ ] **B1** `api.ts` — nazewnictwo akcji: `add`/`remove` w `notificationEmailsApi`, ale `create`/`delete` gdzieindziej. Ujednolicić do `create`/`delete` we wszystkich API.
- [ ] **B2** Kontrolery — mix: część używa `@RequiredArgsConstructor` + `@Slf4j` z Lomboka, część deklaruje pola ręcznie. Ujednolicić przez Lombok.
- [ ] **B3** `InvoiceService.java` — metoda `applyPrefix()` zawiera logikę domenową numeru faktury wewnątrz serwisu. Rozważyć wydzielenie do `InvoiceNumberGenerator`.
- [ ] **B4** `UserController.java` — DTO (`CreateUserRequest`, `UpdatePrefixRequest`) zdefiniowane jako wewnętrzne rekordy w kontrolerze. Przenieść do osobnego pakietu `dto`.
- [ ] **B5** `frontend/src/app/konfiguracja/page.tsx` — stałe `MONTHS` i `YEARS` zdefiniowane inline; te same dane są potrzebne na stronie raportów. Wyciągnąć do `lib/dateUtils.ts`.

### C. Brakująca walidacja na granicach

- [ ] **C1** `InvoiceController.java` — brak weryfikacji `issueDateFrom <= issueDateTo` przy filtrowaniu. Dodać sprawdzenie z czytelnym błędem 400.
- [ ] **C2** `frontend/src/lib/api.ts` — przed uploadem XLSX sprawdzany jest tylko extension, nie rozmiar pliku. Dodać walidację rozmiaru po stronie klienta.
- [ ] **C3** `XlsxParserService.java` — `Double.parseDouble()` bez obsługi `NumberFormatException`. Owinąć try-catch i zwracać czytelny błąd walidacji.
- [ ] **C4** `UserController.java` — `GET /api/users/{id}` nie weryfikuje, że `id` z path == `X-User-Id` z nagłówka. Każdy może pobrać dane dowolnego użytkownika.
- [ ] **C5** `InvoiceController.java` — brak ograniczenia maksymalnego `size` przy paginacji. Klient może zażądać `size=10000`, co obciąży bazę. Dodać walidację `@Max(100)` lub podobną.
- [ ] **C6** `UserController.java` — `GET /api/users/by-nip/{nip}` zwraca pełne dane użytkownika (email, NIP, nazwa firmy) bez żadnego uwierzytelniania i bez rate limitingu. Ryzyko enumeracji użytkowników. Ograniczyć zakres zwracanych danych lub dodać rate limit.

### D. Wzorce do uproszczenia

- [ ] **D1** `InvoiceService.java` — powtarzające się null-checki na 20+ polach przy budowaniu encji (`req.getX() != null ? req.getX() : default`). Wyciągnąć do `Optional.ofNullable().orElse()` lub metody pomocniczej.
- [ ] **D2** `GlobalExceptionHandler.java` — ręczne budowanie `LinkedHashMap` z `put()` dla każdego błędu. Zastąpić `record ErrorResponse(String message, Object details)`.
- [ ] **D3** `MonthlyReportService.java` — sprawdzić czy `DATE_FORMAT` i `PLN_FORMAT` są `private static final` (bez tworzenia nowej instancji przy każdym wywołaniu).
- [ ] **D4** `frontend/src/app/konfiguracja/page.tsx` — trzy sekcje stanu (`prefixMode`, emaile, tokeny) zarządzane płaskim `useState`. Rozważyć podział na custom hooki (`useNotificationEmails`, `usePrefixMode`).
- [ ] **D5** `EmailService.java` — pętla wysyłania emaili nie zbiera błędów. Przy wielu adresatach caller nie wie, które adresy zawiodły. Zwracać `List<String>` nieudanych adresów.

### E. Konfiguracja / środowisko

- [ ] **E1** `application.yml` — `CORS_ALLOWED_ORIGIN_PATTERNS` domyślnie `*`. Usunąć domyślną wartość, wymagać jawnej konfiguracji w produkcji.
- [x] **E2** Migracje DB — luka w numeracji (brak V2, V3, V4; skacze do V5). Pliki przemianowane: V5→V2, V6→V3. Baza zaktualizowana ręcznie przez `flyway_schema_history`.
- [ ] **E3** `InvoiceQueueConsumer.java` — przenieść `POLL_INTERVAL_MS`, `MAX_POLL_ATTEMPTS` i okno czasowe pobierania do `@ConfigurationProperties(prefix = "ksef.polling")` i `application.yml`.
- [ ] **E4** Kolumna `fa2_xml` w tabeli `invoices` przechowuje XML FA(3) — nazwa kolumny jest myląca (sugeruje starszy format FA(2)). Rozważyć rename na `fa3_xml` w kolejnej migracji DB.
- [ ] **E5** Backend nie ustawia nagłówków bezpieczeństwa HTTP (HSTS, X-Content-Type-Options, X-Frame-Options). Dodać `SecurityHeaders` filter lub konfigurację Spring Security Headers, nawet przed pełnym wdrożeniem JWT.

---

## Priorytety

**Łatwe / szybkie (~1–2 h):**
A2, A3, B1, B4, B5, C5, D2, D3, E2, E4

**Średnie (refaktor, ~0.5 dnia):**
A1, B3, C1, C2, C3, C6, D1, D4, D5, E1, E3, E5

**Większe (wymagają planowania):**
A4 (konfiguracja zewnętrzna), B2 (pakiet DTO), C4 (autoryzacja), F6/F7 (PDF), JWT, TOKEN_ENC
