#!/bin/sh
# Mona startup script
# 1. Restore database from Litestream replica if this is a fresh volume
# 2. Start Litestream replication and launch the app process
set -e

DB_PATH="${DATABASE_PATH:-/data/mona.db}"

# Restore from Litestream backup on first deploy (empty volume)
if [ ! -f "$DB_PATH" ]; then
    echo "[start] Database not found at $DB_PATH — attempting restore from Litestream..."
    litestream restore \
        -config /etc/litestream.yml \
        -if-replica-exists \
        "$DB_PATH" \
    && echo "[start] Restore complete." \
    || echo "[start] No replica found or restore skipped — starting fresh."
fi

echo "[start] Starting Litestream replication and Mona..."

# Litestream wraps the app: replication starts first, then the app is launched.
# When the app exits, Litestream stops too (and vice versa).
exec litestream replicate \
    -config /etc/litestream.yml \
    -exec "java -jar /app/mona.jar"
