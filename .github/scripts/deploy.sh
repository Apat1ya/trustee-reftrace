#!/usr/bin/env bash
# Puts the stand in .github/deploy on the host and restarts it with the given image.
#
# Environment: TARGET (user@host), IMAGE (full reference), SITE_ADDRESS, REPORTS_PASSWORD,
# TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID (where Alertmanager sends the alerts),
# GHCR_USER and GHCR_TOKEN (to pull the image), PARALLEL_BROWSERS (optional, 2 by default).
set -euo pipefail

: "${TARGET:?}" "${IMAGE:?}" "${SITE_ADDRESS:?set the SITE_ADDRESS variable}" "${REPORTS_PASSWORD:?}"
: "${TELEGRAM_BOT_TOKEN:?set the TELEGRAM_BOT_TOKEN secret}" "${TELEGRAM_CHAT_ID:?set the TELEGRAM_CHAT_ID variable}"
: "${GHCR_USER:?}" "${GHCR_TOKEN:?}"
here="$(dirname "$0")/../deploy"

ssh "$TARGET" 'mkdir -p ~/reftrace'
scp -r "$here/compose.yml" "$here/Caddyfile" "$here/site" "$here/tempo" "$here/grafana" \
  "$here/prometheus" "$here/alertmanager" "$TARGET":reftrace/

# Two browsers do not fit in 2 GB of memory without it. A night's run swaps out well under
# 1 GB; more would only take space from the small disk.
ssh "$TARGET" 'swapon --show | grep -q /swapfile || {
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile &&
  sudo mkswap /swapfile && sudo swapon /swapfile &&
  echo "/swapfile none swap sw 0 0" | sudo tee -a /etc/fstab; }'

hash=$(printf '%s' "$REPORTS_PASSWORD" | ssh "$TARGET" \
  'docker run --rm -i caddy:2 sh -c "caddy hash-password --plaintext \"\$(cat)\""')
ssh "$TARGET" 'cat > reftrace/.env' <<ENV
REFTRACE_IMAGE=${IMAGE}
REFTRACE_PARALLEL_BROWSERS=${PARALLEL_BROWSERS:-2}
SITE_ADDRESS=${SITE_ADDRESS}
REPORTS_PASSWORD_HASH='${hash}'
ENV

# Alertmanager reads the bot token and the chat id from files; they go over stdin, so they appear
# in no command line, and only its user (nobody, 65534) may read them.
put_secret() {
  printf '%s' "$2" | ssh "$TARGET" "sudo sh -c 'umask 077 && cat > reftrace/secrets/$1 &&
    chown 65534:65534 reftrace/secrets/$1 && chmod 400 reftrace/secrets/$1'"
}
ssh "$TARGET" 'install -d -m 700 reftrace/secrets'
put_secret telegram-bot-token "$TELEGRAM_BOT_TOKEN"
put_secret telegram-chat-id "$TELEGRAM_CHAT_ID"

printf '%s' "$GHCR_TOKEN" | ssh "$TARGET" "docker login ghcr.io -u '$GHCR_USER' --password-stdin"
# A container is recreated only when its compose config changes; the ones that read the copied
# config files are restarted so that a changed file takes effect too. The images of earlier
# deploys stay tagged and fill the small disk, so every image no container uses is removed.
ssh "$TARGET" 'cd reftrace && docker compose pull && docker compose up -d --remove-orphans &&
  docker compose restart caddy tempo grafana prometheus alertmanager; rc=$?
  docker logout ghcr.io; docker image prune -af; exit $rc'
ssh "$TARGET" 'cd reftrace && docker compose ps'
