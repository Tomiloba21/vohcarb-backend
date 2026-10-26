# Deploying Vohcarb to a VPS

This puts the backend, MariaDB and Caddy (automatic HTTPS) on one small server with Docker Compose.
The steps are written for **Hetzner Cloud**; Contabo or any Ubuntu VPS works the same from step 2.

What it costs: a Hetzner **CX22** (2 vCPU, 4 GB RAM, 40 GB disk) is about €4–5/month and is plenty for a
group of friends. A domain is about $10/year (optional at first, but see step 6).

---

## 1. Create the server

1. Sign up at [console.hetzner.cloud](https://console.hetzner.cloud), create a project, then **Add Server**.
2. Location: whichever is closest to you and your friends. Image: **Ubuntu 24.04**. Type: **CX22**.
3. **SSH key:** add your public key (`cat ~/.ssh/id_ed25519.pub` on your laptop). Don't use password login.
4. **Firewall:** create one allowing inbound **22** (SSH), **80** and **443** (TCP), plus **443 UDP** (HTTP/3).
   Nothing else needs to be open: MariaDB and the backend are only reachable inside Docker.
5. Optional: tick **Backups** (+20%) for whole-server snapshots, in addition to the nightly database dumps below.

Note the server's IPv4 address.

## 2. Prepare the server

```bash
ssh root@YOUR_SERVER_IP

# A normal user to run things as
adduser vohcarb
usermod -aG sudo vohcarb
rsync --archive --chown=vohcarb:vohcarb ~/.ssh /home/vohcarb

# Docker (official install script; includes the compose plugin)
curl -fsSL https://get.docker.com | sh
usermod -aG docker vohcarb
exit
```

Log back in as the new user: `ssh vohcarb@YOUR_SERVER_IP`.

## 3. Get the code and the word data

```bash
git clone https://github.com/Tomiloba21/vohcarb-backend.git
cd vohcarb-backend

mkdir -p data/raw
curl -L -o data/raw/english-wordnet-2025.xml.gz \
  https://github.com/globalwordnet/english-wordnet/releases/download/2025-edition/english-wordnet-2025.xml.gz
curl -L -o data/raw/en_full.txt \
  https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_full.txt
```

## 4. Configure

```bash
cd deploy
cp .env.example .env
openssl rand -base64 48   # run three times: DB_PASSWORD, DB_ROOT_PASSWORD, JWT_SECRET
nano .env
```

- `DOMAIN`: your API's domain, e.g. `api.yourdomain.com` (step 6), or `:80` for plain HTTP until you have one.
- Keep `.env` private: it holds every password. It's already in `.gitignore`.

## 5. Start the database and load the words

```bash
docker compose up -d db
docker compose run --rm backend \
  --spring.profiles.active=mariadb,import \
  --vohcarb.import.file=data/raw/english-wordnet-2025.xml.gz \
  --vohcarb.import.frequency-file=data/raw/en_full.txt
```

The first run builds the image (about 5 minutes), then the import takes under a minute. You should see
`Imported ImportStats(words=69667, …)` and `Ranked 55445 of 69667 words`.

## 6. Domain and HTTPS

Release builds of Android apps refuse plain `http://`, so the app needs HTTPS: that means a domain.

1. At your registrar (or Cloudflare DNS), add an **A record**: `api` → `YOUR_SERVER_IP`.
2. Put `DOMAIN=api.yourdomain.com` in `.env`.

Caddy requests and renews a Let's Encrypt certificate by itself the first time it starts. No domain yet?
A free subdomain from [DuckDNS](https://www.duckdns.org) works too.

## 7. Start everything

```bash
docker compose up -d --build
docker compose ps                     # all three "running" (db "healthy")
curl https://api.yourdomain.com/actuator/health
```

`{"status":"UP"}` means you're live. The API docs are at `https://api.yourdomain.com/swagger-ui.html`.

In production **dev login is off** (it answers 404), so nobody can sign in until Google Sign-In is added.
The public endpoints (words, categories, levels, word of the day) work straight away.

## 8. Backups

`deploy/backup.sh` dumps the database to `deploy/backups/` and keeps 14 days. Run it nightly:

```bash
chmod +x ~/vohcarb-backend/deploy/backup.sh
crontab -e
# add this line (03:15 every night):
15 3 * * * /home/vohcarb/vohcarb-backend/deploy/backup.sh >> /home/vohcarb/backup.log 2>&1
```

Backups on the same server don't survive losing the server: copy them off now and then
(`rsync -av vohcarb@YOUR_SERVER_IP:vohcarb-backend/deploy/backups/ ./vohcarb-backups/`), or enable Hetzner backups.

Restore: `gunzip -c backups/vohcarb-2026-10-26.sql.gz | docker compose exec -T db mariadb -uroot -p"$DB_ROOT_PASSWORD" vohcarb`

## 9. Updating

```bash
cd ~/vohcarb-backend && git pull
cd deploy && docker compose up -d --build
```

Database migrations run automatically when the backend starts.

## 10. Push notifications (when you're ready)

Until this is set up, reminders are written to the log instead of being sent
(`docker compose logs backend | grep Push`).

1. Create a project at [console.firebase.google.com](https://console.firebase.google.com) (free) and add your
   Android app to it. The app gets `google-services.json` from there.
2. **Project settings → Service accounts → Generate new private key.** This downloads a JSON key: treat it like a password.
3. Copy it to the server as `deploy/secrets/firebase.json`, readable by the container:
   `chmod 644 deploy/secrets/firebase.json`.
4. In `.env`: `FCM_CREDENTIALS_FILE=/app/secrets/firebase.json`, then `docker compose up -d`.
5. From the app (or Swagger), register a device and call `POST /api/v1/me/devices/test`.

iOS notifications go through the same FCM project once an APNs key is uploaded to Firebase.

## Useful commands

```bash
docker compose logs -f backend           # follow the backend's log
docker compose restart backend
docker compose exec db mariadb -uroot -p # SQL shell (password: DB_ROOT_PASSWORD)
docker system df                         # disk used by Docker
```
