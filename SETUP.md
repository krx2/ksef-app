# Instrukcja wdrożenia — ksef-app na serwerze biurowym

Przewodnik krok po kroku dla administratora instalującego aplikację w sieci lokalnej biura.

---

## Spis treści

1. [Wymagania systemowe](#1-wymagania-systemowe)
2. [Przygotowanie serwera](#2-przygotowanie-serwera)
3. [Pobranie kodu](#3-pobranie-kodu)
4. [Konfiguracja sekretów](#4-konfiguracja-sekretów)
5. [Uruchomienie infrastruktury (Docker)](#5-uruchomienie-infrastruktury-docker)
6. [Build i uruchomienie backendu](#6-build-i-uruchomienie-backendu)
7. [Build i uruchomienie frontendu](#7-build-i-uruchomienie-frontendu)
8. [Weryfikacja po wdrożeniu](#8-weryfikacja-po-wdrożeniu)
9. [Uruchamianie jako usługi systemowe](#9-uruchamianie-jako-usługi-systemowe)
10. [Konfiguracja e-mail](#10-konfiguracja-e-mail)
11. [Zmiana środowiska KSeF z testowego na produkcyjne](#11-zmiana-środowiska-ksef-z-testowego-na-produkcyjne)
12. [Aktualizacja aplikacji](#12-aktualizacja-aplikacji)
13. [Rozwiązywanie problemów](#13-rozwiązywanie-problemów)
14. [Checklist wdrożenia](#14-checklist-wdrożenia)

---

## 1. Wymagania systemowe

### Serwer (minimalne)

| Zasób | Minimum | Zalecane |
|-------|---------|---------|
| CPU | 2 rdzenie | 4 rdzenie |
| RAM | 4 GB | 8 GB |
| Dysk | 20 GB wolnego miejsca | 50 GB |
| System | Ubuntu 22.04 LTS / Debian 12 / Rocky Linux 9 | Ubuntu 22.04 LTS |

### Wymagane oprogramowanie

| Oprogramowanie | Wersja | Instalacja |
|---------------|--------|-----------|
| **Docker Engine** | 24+ | [docs.docker.com/engine/install](https://docs.docker.com/engine/install/) |
| **Docker Compose** | v2 (`docker compose`) | Wbudowany w Docker Desktop / plugin |
| **Java JDK** | 21 | Patrz sekcja 2 |
| **Node.js** | 20 LTS | Patrz sekcja 2 |
| **Git** | dowolna | `apt install git` |

### Sieć

- Serwer musi mieć dostęp do internetu (pobieranie zależności Maven/npm, komunikacja z KSeF)
- Stały adres IP w sieci LAN (lub przypisany DHCP reservation)
- Port `3000` (frontend) i `8080` (backend) otwarte w firewallu wewnętrznym

---

## 2. Przygotowanie serwera

### 2.1 Aktualizacja systemu

```bash
sudo apt update && sudo apt upgrade -y
```

### 2.2 Instalacja Java 21

```bash
sudo apt install -y openjdk-21-jdk
java -version
# Oczekiwane: openjdk version "21.x.x"
```

### 2.3 Instalacja Node.js 20 LTS

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
node --version   # v20.x.x
npm --version    # 10.x.x
```

### 2.4 Instalacja Docker

```bash
# Oficjalny skrypt instalacyjny Docker
curl -fsSL https://get.docker.com | sudo bash

# Dodaj aktualnego użytkownika do grupy docker (aby nie używać sudo)
sudo usermod -aG docker $USER

# Zastosuj zmianę grupy (lub wyloguj się i zaloguj ponownie)
newgrp docker

# Sprawdź
docker --version           # Docker version 24.x.x
docker compose version     # Docker Compose version v2.x.x
```

### 2.5 Utwórz dedykowanego użytkownika systemowego (opcjonalnie, zalecane)

```bash
sudo useradd -m -s /bin/bash ksef
sudo usermod -aG docker ksef
# Następne kroki wykonuj jako użytkownik ksef:
sudo -u ksef -i
```

---

## 3. Pobranie kodu

```bash
# Utwórz katalog aplikacji
sudo mkdir -p /opt/ksef-app
sudo chown ksef:ksef /opt/ksef-app

# Sklonuj repozytorium
git clone <URL_REPOZYTORIUM> /opt/ksef-app
cd /opt/ksef-app

# Sprawdź że masz właściwą gałąź
git status
```

> Jeśli nie masz repozytorium Git, skopiuj pliki projektu do `/opt/ksef-app` przez SCP lub inne narzędzie.

---

## 4. Konfiguracja sekretów

> ⚠️ Pliki `.env` i `docker-compose.env` zawierają hasła. Nigdy nie umieszczaj ich w repozytorium Git.

### 4.1 Sprawdź adres IP serwera

```bash
ip addr show | grep "inet " | grep -v 127.0.0.1
# Przykład: inet 192.168.1.100/24
# Zanotuj ten adres — użyjesz go w kilku miejscach
```

### 4.2 Skonfiguruj `docker-compose.env`

```bash
cp /dev/null /opt/ksef-app/docker-compose.env
nano /opt/ksef-app/docker-compose.env
```

Wpisz (zamień hasła na własne, silne):
```properties
DB_NAME=ksef_db
DB_USER=ksef_user
DB_PASS=TwojeHasloPostgres2024!
RABBITMQ_USER=ksef_user
RABBITMQ_PASS=TwojeHasloRabbit2024!
```

### 4.3 Skonfiguruj `backend/.env`

```bash
nano /opt/ksef-app/backend/.env
```

Wypełnij plik (szablon już istnieje, uzupełnij poniższe wartości):

```properties
# Baza danych — te same hasła co w docker-compose.env
DB_HOST=localhost
DB_PORT=5432
DB_NAME=ksef_db
DB_USER=ksef_user
DB_PASS=TwojeHasloPostgres2024!

# RabbitMQ — te same hasła co w docker-compose.env
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=ksef_user
RABBITMQ_PASS=TwojeHasloRabbit2024!

# CORS — adres IP serwera w sieci biurowej
# Jeśli frontend i backend są na tym samym serwerze:
CORS_ALLOWED_ORIGINS=http://192.168.1.100:3000

# KSeF — na początku zostaw testowe, zmień na produkcyjne gdy gotowy
KSEF_BASE_URL=https://api-test.ksef.mf.gov.pl/v2
KSEF_VIEWER_URL=https://ksef-test.mf.gov.pl/web/wizualizacja/FA

# E-mail — opcjonalnie, patrz sekcja 10
MAIL_ENABLED=false
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=noreply@ksef-faktury.pl
```

### 4.4 Skonfiguruj `frontend/.env.local`

```bash
nano /opt/ksef-app/frontend/.env.local
```

```properties
# Adres backendu — IP serwera widoczny z przeglądarki użytkownika
NEXT_PUBLIC_BACKEND_URL=http://192.168.1.100:8080
```

> Jeśli frontend działa na innym hoście niż backend, wpisz adres hosta backendu.

### 4.5 Zabezpiecz pliki z sekretami

```bash
chmod 600 /opt/ksef-app/backend/.env
chmod 600 /opt/ksef-app/docker-compose.env
chmod 600 /opt/ksef-app/frontend/.env.local
```

---

## 5. Uruchomienie infrastruktury (Docker)

```bash
cd /opt/ksef-app

# Uruchom PostgreSQL i RabbitMQ
docker compose --env-file docker-compose.env up -d

# Poczekaj ~15 sekund i sprawdź stan
docker compose ps
```

Oczekiwany wynik:
```
NAME              STATUS
ksef-postgres     Up (healthy)
ksef-rabbitmq     Up (healthy)
```

Jeśli status to `Up (health: starting)` — poczekaj jeszcze chwilę i powtórz.

### Weryfikacja połączeń (tylko lokalnie)

```bash
# PostgreSQL — test połączenia
docker exec ksef-postgres pg_isready -U ksef_user -d ksef_db
# Oczekiwane: /var/run/postgresql:5432 - accepting connections

# RabbitMQ — test pingu
docker exec ksef-rabbitmq rabbitmq-diagnostics ping
# Oczekiwane: Ping succeeded if node is not in recovery mode
```

> **Uwaga:** Porty 5432, 5672 i 15672 są teraz dostępne **wyłącznie z localhost** (serwera). Użytkownicy z sieci LAN nie mogą się połączyć bezpośrednio z bazą ani z RabbitMQ.

---

## 6. Build i uruchomienie backendu

### 6.1 Build

```bash
cd /opt/ksef-app/backend
./mvnw clean package -DskipTests
```

Pierwsze uruchomienie pobierze zależności Maven (~200 MB) — może potrwać 3–5 minut.

Oczekiwany wynik końcowy:
```
[INFO] BUILD SUCCESS
[INFO] Total time: X:XX min
```

Plik JAR będzie w: `backend/target/ksef-backend-*.jar`

### 6.2 Uruchomienie testowe (weryfikacja)

```bash
cd /opt/ksef-app/backend

# Załaduj zmienne środowiskowe i uruchom
set -a && source .env && set +a
java -jar target/ksef-backend-*.jar
```

Poczekaj na komunikat w logach:
```
Started KsefApplication in X.XXX seconds
```

Test API:
```bash
# W osobnym terminalu — powinien zwrócić 404 lub {}
curl -s http://localhost:8080/api/users/by-nip/0000000000
```

Zatrzymaj aplikację: `Ctrl+C` (uruchomisz ją jako usługę w sekcji 9).

---

## 7. Build i uruchomienie frontendu

### 7.1 Instalacja zależności i build

```bash
cd /opt/ksef-app/frontend
npm install
npm run build
```

Pierwsze `npm install` pobierze zależności (~500 MB) — może potrwać 2–3 minuty.

Oczekiwany wynik `npm run build`:
```
✓ Compiled successfully
✓ Collecting page data
✓ Generating static pages
Route (app) ...
```

### 7.2 Uruchomienie testowe

```bash
npm start
# Frontend uruchomiony na http://0.0.0.0:3000
```

Test w przeglądarce: otwórz `http://192.168.1.100:3000` (adres serwera) z dowolnego komputera w sieci biurowej.

Zatrzymaj: `Ctrl+C`.

---

## 8. Weryfikacja po wdrożeniu

Wykonaj poniższą checklistę przed przekazaniem aplikacji użytkownikom:

### 8.1 Testy infrastruktury

```bash
# Docker — stan kontenerów
docker compose ps
# Oba powinny być: Up (healthy)

# PostgreSQL dostępny tylko lokalnie
# Z serwera — powinno działać:
nc -zv localhost 5432 && echo "OK"
# Z innej maszyny w sieci — powinno się nie połączyć:
# nc -zv 192.168.1.100 5432   ← odmowa połączenia (oczekiwane)
```

### 8.2 Testy API backendu

```bash
# Health check — odpowiedź 200 lub 404
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/users/by-nip/test
# Oczekiwane: 404

# Rejestracja użytkownika testowego
curl -s -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.pl","nip":"1234563218","companyName":"Test Sp. z o.o."}' | python3 -m json.tool
# Oczekiwane: obiekt JSON z id użytkownika
```

### 8.3 Test frontendu z sieci LAN

1. Otwórz `http://192.168.1.100:3000` w przeglądarce na komputerze użytkownika
2. Wypełnij formularz rejestracji i kliknij Zaloguj
3. Sprawdź że dashboard się wczytuje
4. Spróbuj wystawić fakturę w trybie DRAFT

### 8.4 Sprawdź logi backendu

```bash
# Jeśli uruchomiony jako usługa systemd:
journalctl -u ksef-backend -n 50

# Sprawdź czy nie ma błędów połączenia z DB lub RabbitMQ
# Poprawne logi startowe wyglądają tak:
# INFO  - HikariPool-1 - Start completed.
# INFO  - Started KsefApplication in X seconds
```

---

## 9. Uruchamianie jako usługi systemowe

Po weryfikacji skonfiguruj automatyczny start przy restarcie serwera.

### 9.1 Usługa systemd dla backendu

```bash
sudo nano /etc/systemd/system/ksef-backend.service
```

```ini
[Unit]
Description=KSeF Backend — Spring Boot
Documentation=https://github.com/twoja-firma/ksef-app
After=network.target docker.service
Requires=docker.service

[Service]
Type=simple
User=ksef
WorkingDirectory=/opt/ksef-app/backend
EnvironmentFile=/opt/ksef-app/backend/.env
ExecStart=/usr/bin/java -Xmx512m -jar /opt/ksef-app/backend/target/ksef-backend-*.jar
Restart=on-failure
RestartSec=15
StandardOutput=journal
StandardError=journal
SyslogIdentifier=ksef-backend

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable ksef-backend
sudo systemctl start ksef-backend

# Sprawdź status
sudo systemctl status ksef-backend
```

### 9.2 Usługa systemd dla frontendu

```bash
sudo nano /etc/systemd/system/ksef-frontend.service
```

```ini
[Unit]
Description=KSeF Frontend — Next.js
After=network.target ksef-backend.service

[Service]
Type=simple
User=ksef
WorkingDirectory=/opt/ksef-app/frontend
EnvironmentFile=/opt/ksef-app/frontend/.env.local
ExecStart=/usr/bin/npm start
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=ksef-frontend

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable ksef-frontend
sudo systemctl start ksef-frontend

# Sprawdź status
sudo systemctl status ksef-frontend
```

### 9.3 Autostart Dockera przy restarcie

```bash
sudo systemctl enable docker
```

### 9.4 Weryfikacja autostartu

```bash
# Symulacja restartu usług
sudo systemctl restart ksef-backend ksef-frontend

# Po ~30 sekundach sprawdź
sudo systemctl status ksef-backend ksef-frontend
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/users/by-nip/test
# Oczekiwane: 404
```

### 9.5 Przeglądanie logów

```bash
# Logi backendu (ostatnie 100 linii, śledzenie na żywo)
journalctl -u ksef-backend -n 100 -f

# Logi frontendu
journalctl -u ksef-frontend -n 50 -f

# Logi Docker (PostgreSQL, RabbitMQ)
docker compose -f /opt/ksef-app/docker-compose.yml logs -f
```

---

## 10. Konfiguracja e-mail

Jeśli chcesz otrzymywać powiadomienia o nowych fakturach przychodzących z KSeF:

### 10.1 Odczytaj dane z `config.txt`

```bash
cat /opt/ksef-app/config.txt
```

### 10.2 Uzupełnij `backend/.env`

```bash
nano /opt/ksef-app/backend/.env
```

Zmień wartości:
```properties
MAIL_ENABLED=true
MAIL_HOST=<serwer SMTP z config.txt>
MAIL_PORT=<port SMTP z config.txt — 587 dla STARTTLS, 465 dla SSL>
MAIL_USERNAME=<użytkownik z config.txt>
MAIL_PASSWORD=<hasło z config.txt>
MAIL_FROM=faktury@twojaFirma.pl
```

### 10.3 Restart backendu po zmianie

```bash
sudo systemctl restart ksef-backend
journalctl -u ksef-backend -n 20
# Sprawdź czy brak błędów "Mail server connection failed"
```

### 10.4 Test wysyłki (opcjonalnie)

Aby przetestować konfigurację e-mail bez czekania na fakturę z KSeF, możesz tymczasowo wywołać ręczne pobranie faktur:

```bash
# Pobierz ID użytkownika testowego najpierw:
curl http://localhost:8080/api/users/by-nip/TWOJ_NIP

# Następnie wyzwól pobranie faktur z KSeF:
curl -X POST http://localhost:8080/api/invoices/fetch \
  -H "X-User-Id: <twoje-uuid>"
```

---

## 11. Zmiana środowiska KSeF z testowego na produkcyjne

> ⚠️ Wykonaj ten krok dopiero po pełnym przetestowaniu na środowisku testowym KSeF.

### 11.1 Zmień URL w `backend/.env`

```bash
nano /opt/ksef-app/backend/.env
```

```properties
# Zmień linie:
KSEF_BASE_URL=https://api.ksef.mf.gov.pl/v2
KSEF_VIEWER_URL=https://ksef.mf.gov.pl/web/wizualizacja/FA
```

### 11.2 Zaktualizuj tokeny KSeF użytkowników

Każdy użytkownik musi zaktualizować swój token KSeF na produkcyjny:
1. Zaloguj się do aplikacji
2. Przejdź do **Konfiguracja → Token KSeF**
3. Wklej token produkcyjny z https://ksef.mf.gov.pl
4. Kliknij Zapisz

### 11.3 Restart backendu

```bash
sudo systemctl restart ksef-backend
```

### 11.4 Test wysyłki do produkcji

Wyślij fakturę testową i sprawdź w KSeF (https://ksef.mf.gov.pl) czy pojawiła się poprawnie.

---

## 12. Aktualizacja aplikacji

Procedura aktualizacji do nowej wersji:

```bash
cd /opt/ksef-app

# 1. Pobierz nowy kod
git pull origin main

# 2. Zatrzymaj usługi aplikacji
sudo systemctl stop ksef-frontend ksef-backend

# 3. Zbuduj backend
cd backend
./mvnw clean package -DskipTests

# 4. Zbuduj frontend
cd ../frontend
npm install
npm run build

# 5. Uruchom usługi
sudo systemctl start ksef-backend
# Poczekaj ~15 sekund na start backendu
sleep 15
sudo systemctl start ksef-frontend

# 6. Sprawdź status
sudo systemctl status ksef-backend ksef-frontend
```

> Migracje bazy danych (Flyway) wykonują się automatycznie przy starcie backendu.

---

## 13. Rozwiązywanie problemów

### Backend nie startuje — błąd połączenia z bazą

```
HikariPool-1 - Exception during pool initialization: FATAL: password authentication failed
```

**Przyczyna:** Hasło w `backend/.env` różni się od hasła w `docker-compose.env`.

**Rozwiązanie:**
```bash
# Sprawdź czy hasła są identyczne
grep DB_PASS /opt/ksef-app/backend/.env
grep DB_PASS /opt/ksef-app/docker-compose.env
```

---

### Backend nie startuje — błąd Flyway

```
FlywayException: Validate failed: Migration checksum mismatch for migration
```

**Przyczyna:** Zmiana pliku migracji SQL po jego zastosowaniu.

**Rozwiązanie:** Nie modyfikuj plików `V*__*.sql` po wdrożeniu. Skontaktuj się z deweloperem.

---

### Frontend — błąd `ECONNREFUSED` w logach

```
Error: connect ECONNREFUSED 192.168.1.100:8080
```

**Przyczyna:** Frontend nie może połączyć się z backendem.

**Rozwiązanie:**
```bash
# Sprawdź czy backend działa
sudo systemctl status ksef-backend
curl -s http://localhost:8080/api/users/by-nip/test

# Sprawdź czy adres IP w frontend/.env.local jest poprawny
cat /opt/ksef-app/frontend/.env.local
```

---

### Aplikacja dostępna tylko z serwera, nie z sieci LAN

**Przyczyna:** Firewall blokuje porty 3000 i 8080.

**Rozwiązanie (Ubuntu/ufw):**
```bash
sudo ufw allow 3000/tcp comment "KSeF Frontend"
sudo ufw allow 8080/tcp comment "KSeF Backend"
sudo ufw status
```

---

### Docker — kontenery nie startują po restarcie serwera

```bash
# Sprawdź czy Docker działa
sudo systemctl status docker

# Ręcznie uruchom kontenery
cd /opt/ksef-app
docker compose --env-file docker-compose.env up -d
```

---

### RabbitMQ Management UI niedostępne

Management UI (`http://localhost:15672`) jest dostępne **tylko z serwera** (localhost).

Aby uzyskać dostęp zdalny (tymczasowo, dla diagnostyki):
```bash
# Tunel SSH z komputera administratora:
ssh -L 15672:localhost:15672 user@192.168.1.100
# Następnie otwórz: http://localhost:15672
```

---

### Faktura utknęła w statusie QUEUED

**Przyczyna:** RabbitMQ jest niedostępny lub consumer nie działa.

```bash
# Sprawdź stan RabbitMQ
docker compose ps ksef-rabbitmq

# Sprawdź logi backendu pod kątem błędów AMQP
journalctl -u ksef-backend -n 100 | grep -i "amqp\|rabbit\|queue"

# Restart backendu (consumer uruchomi się ponownie)
sudo systemctl restart ksef-backend
```

---

## 14. Checklist wdrożenia

Przejdź przez każdy punkt przed oddaniem aplikacji użytkownikom:

### Konfiguracja
- [ ] `docker-compose.env` — ustawione silne hasła (nie domyślne)
- [ ] `backend/.env` — ustawione silne hasła, poprawny adres IP w CORS
- [ ] `frontend/.env.local` — poprawny adres IP serwera w NEXT_PUBLIC_BACKEND_URL
- [ ] Pliki `.env` i `docker-compose.env` mają prawa `600` (`chmod 600`)
- [ ] Pliki z sekretami nie są w repozytorium Git (`git status` — brak tych plików)

### Infrastruktura
- [ ] `docker compose ps` — oba kontenery `Up (healthy)`
- [ ] Port 5432 **nie jest dostępny** z sieci LAN (test z innej maszyny)
- [ ] Port 15672 **nie jest dostępny** z sieci LAN
- [ ] Porty 3000 i 8080 **są dostępne** z sieci LAN

### Aplikacja
- [ ] Backend odpowiada na `http://SERWER:8080/api/users/by-nip/test`
- [ ] Frontend ładuje się na `http://SERWER:3000` z komputera użytkownika
- [ ] Rejestracja nowego użytkownika działa
- [ ] Wystawienie faktury DRAFT działa
- [ ] Logi backendu na poziomie INFO (nie DEBUG) — brak wrażliwych danych w logach

### Autostart
- [ ] `sudo systemctl is-enabled ksef-backend` → `enabled`
- [ ] `sudo systemctl is-enabled ksef-frontend` → `enabled`
- [ ] `sudo systemctl is-enabled docker` → `enabled`

### KSeF
- [ ] Środowisko KSeF ustawione zgodnie z etapem (testowe / produkcyjne)
- [ ] Użytkownicy mają wgrane właściwe tokeny KSeF

### E-mail (jeśli używany)
- [ ] `MAIL_ENABLED=true` w `backend/.env`
- [ ] Dane SMTP z `config.txt` wpisane poprawnie
- [ ] Test powiadomienia e-mail zakończony sukcesem

---

## Kontakt i wsparcie

W razie problemów sprawdź najpierw logi:
```bash
journalctl -u ksef-backend -n 200
journalctl -u ksef-frontend -n 50
docker compose logs --tail=50
```

Przed zgłoszeniem błędu dołącz:
- Wynik `docker compose ps`
- Fragment logów backendu z momentu błędu
- Wersję aplikacji (`git log --oneline -1`)
