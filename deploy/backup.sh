#!/usr/bin/env bash
# Dumps the database to deploy/backups/ and keeps the last 14 days.
# Run nightly from cron, e.g.:  15 3 * * * /home/vohcarb/vohcarb-backend/deploy/backup.sh
set -euo pipefail

cd "$(dirname "$0")"
set -a; source .env; set +a

mkdir -p backups
file="backups/vohcarb-$(date +%F).sql.gz"
docker compose exec -T db mariadb-dump --single-transaction --user=root --password="$DB_ROOT_PASSWORD" vohcarb | gzip > "$file.tmp"
mv "$file.tmp" "$file"
find backups -name 'vohcarb-*.sql.gz' -mtime +14 -delete
echo "Backed up to $file"
