# ksef-app — Setup & Deployment

## Wymagania

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows/Mac/Linux)

Nic więcej nie jest potrzebne — Java, Maven ani Node.js nie muszą być zainstalowane lokalnie.

---

## Struktura projektu

```
ksef-app/
├── backend/          # Spring Boot 3 (Java 21) — REST API, logika KSeF
├── frontend/         # Next.js 14 (React, TypeScript, Tailwind)
├── docker-compose.yml
└── docker-compose.env   # ← tu wpisujesz hasła i konfigurację
```

---

## Pierwsze uruchomienie

### 1. Skonfiguruj zmienne środowiskowe

Edytuj plik `docker-compose.env`:

```env
# Hasła do bazy i kolejki (zmień na własne)
DB_PASS=twoje_haslo_db
RABBITMQ_PASS=twoje_haslo_rabbit

# Środowisko KSeF: ksef-test (domyślne) lub ksef-prod
SPRING_PROFILES_ACTIVE=ksef-test
```

Opcjonalnie włącz wysyłkę e-mail:

```env
MAIL_ENABLED=true
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=twoj@gmail.com
MAIL_PASSWORD=haslo-aplikacji-google
MAIL_FROM=faktury@twojafirma.pl
```

### 2. Uruchom

```bash
docker-compose --env-file docker-compose.env up -d --build
```

Pierwsze uruchomienie pobiera obrazy i buduje aplikację — może zająć kilka minut.

### 3. Otwórz aplikację

```
http://localhost:3000
```

---

## Kolejne uruchomienia

```bash
# Start (bez rebuildu — szybki)
docker-compose --env-file docker-compose.env up -d

# Stop
docker-compose --env-file docker-compose.env down

# Rebuild po zmianach w kodzie
docker-compose --env-file docker-compose.env up -d --build
```

---

## Logi

```bash
# Wszystkie serwisy
docker-compose --env-file docker-compose.env logs -f

# Tylko backend
docker-compose --env-file docker-compose.env logs -f backend

# Tylko frontend
docker-compose --env-file docker-compose.env logs -f frontend
```

---

## Serwisy i porty

| Serwis      | Port lokalny | Opis                            |
|-------------|------------|----------------------------------|
| `frontend`  | 3000       | Interfejs użytkownika            |
| `backend`   | —          | REST API (tylko wewnętrznie)     |
| `postgres`  | —          | Baza danych (tylko wewnętrznie)  |
| `rabbitmq`  | —          | Kolejka (tylko wewnętrznie)      |

Tylko port 3000 jest dostępny z zewnątrz. Pozostałe serwisy komunikują się wewnętrznie w sieci Docker.

---

## Zmiana portu frontendu

W `docker-compose.env` dodaj:

```env
FRONTEND_PORT=8090
```

---

## Rozwój lokalny (bez Dockera)

Wymagania: Java 21, Maven, Node.js 20, PostgreSQL 16, RabbitMQ 3.13.

```bash
# Infrastruktura (tylko baza i kolejka)
docker-compose --env-file docker-compose.env up -d postgres rabbitmq

# Backend
cd backend
mvn spring-boot:run

# Frontend (osobny terminal)
cd frontend
npm install
npm run dev
```

Frontend dostępny na `http://localhost:3000`, backend na `http://localhost:8080`.

---

## Uwagi

- `docker-compose.env` zawiera dane wrażliwe — **nie dodawać do git**
- Baza danych jest persystowana w wolumenie Docker (`postgres_data`) — dane przeżywają restart kontenerów
- Migracje bazy (Flyway) uruchamiają się automatycznie przy starcie backendu
- Domyślny profil KSeF to `ksef-test` (środowisko testowe MF) — zmień na `ksef-prod` dopiero po uzyskaniu dostępu produkcyjnego
