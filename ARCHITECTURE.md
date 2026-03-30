# Architektura systemu ksef-app

Diagramy opisujące działanie systemu. Renderuj przez VS Code (Mermaid Preview), GitHub lub https://mermaid.live.

---

## 1. Przegląd architektury (C4 – poziom kontenerów)

```mermaid
C4Container
    title ksef-app – kontenery systemu

    Person(user, "Użytkownik", "Pracownik księgowości")

    System_Boundary(app, "ksef-app") {
        Container(frontend, "Frontend", "Next.js 14 / TypeScript", "UI: formularze, lista faktur, konfiguracja XLSX")
        Container(backend, "Backend", "Spring Boot 3.3 / Java 21", "API REST, logika biznesowa, generowanie XML FA(3)")
        Container(db, "Baza danych", "PostgreSQL 16", "Faktury, pozycje, użytkownicy, konfiguracje XLSX")
        Container(mq, "Broker kolejek", "RabbitMQ 3.13", "Asynchroniczne wysyłanie faktur do KSeF")
    }

    System_Ext(ksef, "KSeF API", "api-test.ksef.mf.gov.pl – testowe środowisko MF")

    Rel(user, frontend, "Używa", "HTTPS")
    Rel(frontend, backend, "REST API", "HTTP /api/*  +  X-User-Id header")
    Rel(backend, db, "Odczyt / zapis", "JPA / JDBC")
    Rel(backend, mq, "Publikuje wiadomości", "AMQP")
    Rel(mq, backend, "Konsumuje wiadomości", "AMQP")
    Rel(backend, ksef, "Wysyła / pobiera faktury", "HTTPS / REST")
```

---

## 2. Warstwy backendu – zależności klas

```mermaid
graph TD
    subgraph Controllers
        IC[InvoiceController]
        UC[UserController]
        XC[XlsxConfigController]
    end

    subgraph Services
        IS[InvoiceService]
        XPS[XlsxParserService]
        XCS[XlsxConfigService]
        F3V[Fa3Validator]
        FXB[Fa2XmlBuilder\n<small>generuje XML FA 3</small>]
        KAC[KsefApiClient]
    end

    subgraph Queue
        IQP[InvoiceQueuePublisher]
        IQC[InvoiceQueueConsumer]
    end

    subgraph Repositories
        IR[InvoiceRepository]
        UR[UserRepository]
        XCR[XlsxConfigurationRepository]
    end

    subgraph DB["PostgreSQL"]
        T_INV[(invoices)]
        T_ITM[(invoice_items)]
        T_USR[(users)]
        T_CFG[(xlsx_configurations)]
    end

    IC --> IS
    IC --> XPS
    IC --> XCS
    IC --> IQP
    XC --> XCS
    XC --> XPS
    UC --> UR

    IS --> F3V
    IS --> FXB
    IS --> IR
    IS --> IQP

    XPS --> XCR
    XCS --> XCR

    IQC --> IS
    IQC --> UR
    IQC --> KAC

    IR --> T_INV
    IR --> T_ITM
    UR --> T_USR
    XCR --> T_CFG
```

---

## 3. Przepływ danych – formularz / XLSX → baza danych

```mermaid
flowchart LR
    A([Użytkownik]) -->|wypełnia formularz\nlub uploaduje XLSX| B[Frontend\nInvoiceFormPage\nXlsxUploadPage]
    B -->|POST /api/invoices\nlub /from-xlsx| C[InvoiceController]

    subgraph Parsowanie XLSX
        C -->|configId| D[XlsxConfigService\npobiera mapowania]
        D --> E[XlsxParserService\nApache POI\nordczyt komórek]
        E -->|InvoiceDto.CreateRequest| C
    end

    C --> F[InvoiceService\ncreateAndQueue]

    subgraph Tworzenie faktury
        F --> G[Fa3Validator\nwalidacja NIP, dat,\nkurencji, stawek VAT]
        G -->|błędy → 400| C
        G -->|OK| H[Oblicz kwoty\nnetto, VAT, brutto]
        H --> I[Fa2XmlBuilder\ngeneruj XML FA 3]
        I --> J[(DB: QUEUED\n+ fa2_xml)]
    end

    F -->|invoiceId| K[InvoiceQueuePublisher\npublikuj do RabbitMQ]
    J --> L([Odpowiedź 201\nInvoiceDto.Response])
    L --> B
```

---

## 4. Asynchroniczny przepływ – wysyłka do KSeF

```mermaid
sequenceDiagram
    participant MQ as RabbitMQ<br/>invoice.send.queue
    participant C as InvoiceQueueConsumer
    participant DB as PostgreSQL
    participant K as KSeF API

    MQ->>C: SendInvoiceMessage {invoiceId, userId}
    C->>DB: pobierz Invoice + AppUser
    C->>DB: status = SENDING

    C->>K: POST /Session/AuthorisationChallenge {nip}
    K-->>C: challenge {timestamp}

    C->>K: POST /Session/InitToken {nip, token, challenge}
    K-->>C: sessionToken

    C->>K: PUT /Invoice/Send {Base64(fa2Xml)}
    K-->>C: elementReferenceNumber

    loop Polling (maks 5× / backoff 2s, 4s, 6s...)
        C->>K: GET /Invoice/Status/{elementRef}
        K-->>C: status + ksefReferenceNumber
    end

    alt Sukces
        C->>DB: status = SENT, ksefNumber = ...
    else Błąd
        C->>DB: status = FAILED, errorMessage = ...
    end

    C->>K: GET /Session/Terminate
```

---

## 5. Automat stanów faktury

```mermaid
stateDiagram-v2
    [*] --> DRAFT : createDraft()
    [*] --> QUEUED : createAndQueue()\n[walidacja OK]

    DRAFT --> QUEUED : ręczne kolejkowanie\n(nie zaimplementowane)
    QUEUED --> SENDING : consumer pobierze z MQ
    SENDING --> SENT : KSeF potwierdził\n[status polling OK]
    SENDING --> FAILED : błąd API KSeF\nlub timeout pollingu
    FAILED --> QUEUED : retry\n(nie zaimplementowane)

    note right of SENT : ksefNumber uzupełniony
    note right of FAILED : errorMessage uzupełniony

    [*] --> RECEIVED_FROM_KSEF : pobieranie\n[nie zaimplementowane]
```

---

## 6. Konfiguracja XLSX i parsowanie komórek

```mermaid
flowchart TD
    A([Użytkownik]) -->|tworzy konfigurację| B[XlsxConfigModal\nedytor mapowań]

    B -->|pole: invoiceNumber\ntyp: CELL, ref: A1| C[(xlsx_configurations\nfield_mappings JSONB)]
    B -->|pole: sellerName\ntyp: VALUE, val: 'Firma Sp.zo.o'| C

    A -->|uploaduje plik .xlsx| D[XlsxUploadPage]
    D -->|POST /xlsx-preview\nlub /from-xlsx + configId| E[XlsxParserService]
    E -->|wczytaj konfigurację| C
    E -->|otwórz plik Apache POI\newaluuj formuły| F[Arkusz XLSX]

    F -->|CELL A1 → 'INV-001'| E
    F -->|VALUE → 'Firma Sp.zo.o'| E

    E -->|InvoiceDto.CreateRequest| G[InvoiceService]
    E -->|Map String-String| H[XlsxUploadPage\npodgląd pól]
```

---

## 7. Generowanie XML FA(3)

```mermaid
flowchart TD
    A[InvoiceService\ncreateAndQueue] --> B[Fa2XmlBuilder.build]

    B --> C[Nagłówek\nKodFormularza, WariantFormularza\nDataWytworzeniaFa UTC/Z]
    B --> D[Podmiot1 – Sprzedawca\nNIP, Nazwa, KodKraju, Adres]
    B --> E[Podmiot2 – Nabywca\nNIP, Nazwa, KodKraju, Adres\nJST=false, GV=false]
    B --> F[Fa – dane faktury]

    F --> G[Waluta, DataWystawienia\nNrFa, P1=dataSprzedazy]
    F --> H[Sumy VAT per stawka\nP_13_1/P_14_1 → 23%\nP_13_2/P_14_2 → 8%\nP_13_5/P_14_5 → 0%\nP_13_6 → zw ...]
    F --> I[P_15 = kwota brutto]
    F --> J[Adnotacje\nP_16..P_23 flagi\nmetodaKasowa, samofakturowanie\nodwrotneObciazenie, MPP]
    F --> K[RodzajFaktury\nVAT / KOR / ZAL ...]
    F --> L[FaWiersz × N pozycji\nLP, P_7=nazwa, P_8A=jednostka\nP_8B=ilość, P_9A=cena netto\nP_11=wartość netto, P_12=stawka VAT]

    B --> M[(fa2_xml w DB)]

    style B fill:#f0f4ff,stroke:#4a6cf7
    style M fill:#e8f5e9,stroke:#43a047
```

---

## 8. Frontend – hierarchia komponentów i nawigacja

```mermaid
graph TD
    ROOT[layout.tsx\nQueryProvider + UserProvider]

    ROOT --> DASH[/ Dashboard\npage.tsx]
    ROOT --> LIST[/faktury\nInvoice List]
    ROOT --> NEW[/faktury/nowa\nNowy formularz]
    ROOT --> CFG[/konfiguracja\nUstawienia]

    DASH --> SB[StatusBadge]
    DASH --> NAV[Nav]

    LIST --> SB2[StatusBadge]
    LIST --> FMT[formatCurrency\nformatDate utils]

    NEW --> IFP[InvoiceFormPage\nformularz ręczny]
    NEW --> XUP[XlsxUploadPage\nupload pliku]
    IFP --> API1[invoicesApi\ncreateFromForm]
    XUP --> API2[invoicesApi\ncreateFromXlsx\npreviewXlsx]

    CFG --> UST[UserSetup\nrejestracja]
    CFG --> XCM[XlsxConfigModal\nedytor konfiguracji]
    CFG --> API3[usersApi\nupdateToken]
    CFG --> API4[xlsxConfigsApi\ncreate / update / delete]

    subgraph "Wspólny stan"
        UC[useUser hook\nlocalStorage → userId]
        RQ[React Query\ncache + refetch]
    end

    IFP --> UC
    XUP --> UC
    API1 --> RQ
    API2 --> RQ
    API3 --> RQ
    API4 --> RQ
```

---

## 9. Schemat bazy danych (ERD)

```mermaid
erDiagram
    users {
        UUID id PK
        VARCHAR email UK
        VARCHAR nip
        VARCHAR company_name
        TEXT ksef_token
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    invoices {
        UUID id PK
        UUID user_id FK
        VARCHAR invoice_number
        VARCHAR ksef_number
        VARCHAR ksef_reference_number
        VARCHAR direction "ISSUED|RECEIVED"
        VARCHAR status "DRAFT|QUEUED|SENDING|SENT|FAILED|RECEIVED_FROM_KSEF"
        VARCHAR source "FORM|XLSX"
        DATE issue_date
        DATE sale_date
        VARCHAR seller_name
        VARCHAR seller_nip
        TEXT seller_address
        VARCHAR seller_country_code
        VARCHAR buyer_name
        VARCHAR buyer_nip
        TEXT buyer_address
        VARCHAR buyer_country_code
        NUMERIC net_amount
        NUMERIC vat_amount
        NUMERIC gross_amount
        VARCHAR currency
        TEXT fa2_xml
        TEXT error_message
        VARCHAR rodzaj_faktury "VAT|KOR|ZAL|ROZ|UPR|KOR_ZAL|KOR_ROZ"
        BOOLEAN metoda_kasowa
        BOOLEAN samofakturowanie
        BOOLEAN odwrotne_obciazenie
        BOOLEAN mechanizm_podzielonej_platnosci
    }

    invoice_items {
        UUID id PK
        UUID invoice_id FK
        VARCHAR name
        VARCHAR unit
        NUMERIC quantity
        NUMERIC net_unit_price
        NUMERIC vat_rate
        VARCHAR vat_rate_code "23|8|5|zw|oo|np I..."
        NUMERIC net_amount
        NUMERIC vat_amount
        NUMERIC gross_amount
        INT position
    }

    xlsx_configurations {
        UUID id PK
        UUID user_id FK
        VARCHAR name
        TEXT description
        JSONB field_mappings
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    users ||--o{ invoices : "posiada"
    users ||--o{ xlsx_configurations : "posiada"
    invoices ||--|{ invoice_items : "zawiera"
```
