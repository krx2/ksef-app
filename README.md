# KSeF Faktury — System zarządzania fakturami

Aplikacja do wystawiania i odbierania faktur przez API KSeF (środowisko testowe).

## Stack

| Warstwa  | Technologia            |
|----------|------------------------|
| Frontend | Next.js 14, TypeScript, Tailwind CSS, React Query |
| Backend  | Spring Boot 3.3, Java 21 |
| Kolejka  | RabbitMQ 3.13          |
| Baza     | PostgreSQL 16          |
| API      | KSeF test (api-test.ksef.mf.gov.pl) |

---

## Szybki start

### 1. Wymagania

- Java 21
- Node.js 20+
- Docker Desktop

### 2. Uruchom infrastrukturę (PostgreSQL + RabbitMQ)

```bash
docker compose up -d
```

Sprawdź czy działa:
- PostgreSQL: `localhost:5432`
- RabbitMQ Management UI: http://localhost:15672 (login: `ksef_user` / `ksef_pass`)

### 3. Backend — IntelliJ IDEA

1. Otwórz IntelliJ IDEA
2. **File → Open** → wskaż folder `backend/`
3. IntelliJ wykryje projekt Maven — kliknij **Load Maven Project**
4. Poczekaj na pobranie zależności (pierwsze uruchomienie ~2 min)
5. Upewnij się, że SDK ustawiony na **Java 21** (`File → Project Structure → Project SDK`)
6. Uruchom `pl.ksef.KsefApplication` (kliknij zielony trójkąt przy klasie lub `Shift+F10`)

Backend startuje na `http://localhost:8080`.

> **Wskazówka dla IntelliJ**: Włącz adnotacje Lombok i MapStruct:
> `Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing`

### 4. Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend dostępny na http://localhost:3000.

---

## Pierwsze uruchomienie

1. Otwórz http://localhost:3000
2. Wypełnij formularz rejestracji (email, NIP, nazwa firmy)
3. Opcjonalnie: wklej token KSeF w **Konfiguracja → Token KSeF**
   - Token testowy uzyskasz na https://ksef-test.mf.gov.pl
4. Utwórz konfigurację XLSX w **Konfiguracja → Nowa konfiguracja**
5. Wystawiaj faktury!

---

## Konfiguracja XLSX — jak działa

Dla każdego pola faktury możesz ustawić:

| Tryb     | Opis                                         | Przykład         |
|----------|----------------------------------------------|------------------|
| **Stała** | Wartość wpisana raz, zawsze ta sama         | `Firma XYZ sp. z o.o.` |
| **Komórka** | Adres komórki w Excelu (arkusz + komórka) | `B5` na arkuszu `0` |

Dzięki temu możesz mieć wiele szablonów — np. jeden dla faktur sprzedaży, drugi dla usług.

**Testowanie komórek**: W oknie konfiguracji możesz wgrać przykładowy plik XLSX i kliknąć **Testuj** przy każdym polu komórkowym — aplikacja pokaże co aktualnie jest w tej komórce.

---

## Architektura

```
[Next.js :3000]
       │  REST /api/backend/*
       ▼
[Spring Boot :8080]
    ├── POST /api/invoices          ← formularz
    ├── POST /api/invoices/from-xlsx ← plik XLSX
    ├── GET  /api/invoices          ← lista
    ├── POST /api/xlsx-configs      ← konfiguracje
    └── POST /api/xlsx-configs/test-cell
           │
           ├── RabbitMQ → invoice.send.queue → consumer
           │                   │
           │                   ▼
           │              KSeF API (test)
           │              authByToken → session → sendInvoice
           │
           └── PostgreSQL (faktury, konfiguracje, użytkownicy)
```

---

## Endpoints API

### Faktury
```
GET    /api/invoices?direction=ISSUED|RECEIVED&page=0&size=20   lista
GET    /api/invoices/{id}                                        szczegóły
POST   /api/invoices                                             nowa z formularza
POST   /api/invoices/from-xlsx?configId=...  + plik             nowa z XLSX
POST   /api/invoices/xlsx-preview?configId=... + plik           podgląd bez wysyłki
```

### Konfiguracje XLSX
```
GET    /api/xlsx-configs
POST   /api/xlsx-configs
PUT    /api/xlsx-configs/{id}
DELETE /api/xlsx-configs/{id}
POST   /api/xlsx-configs/test-cell?cellRef=A1&sheetIndex=0 + plik
```

### Użytkownicy
```
POST   /api/users
GET    /api/users/{id}
PUT    /api/users/{id}/ksef-token
```

> Wszystkie endpointy wymagają nagłówka `X-User-Id: <uuid>` (poza `/api/users`).

---

## Zmienne środowiskowe

Domyślna konfiguracja w `backend/src/main/resources/application.yml`.

Aby nadpisać bez edycji pliku, ustaw w IntelliJ:
`Run → Edit Configurations → Environment variables`:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ksef_db
SPRING_DATASOURCE_USERNAME=ksef_user
SPRING_DATASOURCE_PASSWORD=ksef_pass
SPRING_RABBITMQ_HOST=localhost
KSEF_API_BASE_URL=https://api-test.ksef.mf.gov.pl
```

---

## Produkcja — KSeF właściwy

Zmień w `application.yml`:
```yaml
ksef:
  api:
    base-url: https://ksef.mf.gov.pl
```
