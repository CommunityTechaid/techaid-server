#!/bin/bash
# Polls GHCR for a new :latest image and deploys if changed.
# Intended to run as a cron job every minute.
#
# Install on server:
#   sudo cp scripts/poll-deploy-api.sh /usr/local/bin/dokku-poll-deploy.sh
#   sudo chmod +x /usr/local/bin/dokku-poll-deploy.sh
#   sudo crontab -e
#   # Add: * * * * * /usr/local/bin/dokku-poll-deploy.sh >> /var/log/dokku-poll-deploy.log 2>&1

APP="api"
IMAGE="ghcr.io/communitytechaid/techaid-server:latest"
STATE_FILE="/var/lib/dokku/data/poll-deploy-digest"

# Check the remote registry digest without pulling.
NEW_DIGEST=$(docker manifest inspect "$IMAGE" 2>/dev/null | sha256sum | awk '{print $1}')

if [ -z "$NEW_DIGEST" ]; then
  echo "$(date -u): could not read remote digest" >&2
  exit 1
fi

# Compare with the last deployed digest
OLD_DIGEST=""
[ -f "$STATE_FILE" ] && OLD_DIGEST=$(cat "$STATE_FILE")

if [ "$NEW_DIGEST" = "$OLD_DIGEST" ]; then
  exit 0  # no change
fi

echo "$(date -u): new image detected, deploying..."
echo "  old: ${OLD_DIGEST:-(none)}"
echo "  new: $NEW_DIGEST"

# Pull the image, then deploy by digest so Dokku always recognises it
# as new. Using :latest alone can cause "No changes detected".
docker pull "$IMAGE" --quiet > /dev/null
DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' "$IMAGE")

if dokku git:from-image "$APP" "$DIGEST" 2>&1; then
  echo "$NEW_DIGEST" > "$STATE_FILE"
  echo "$(date -u): deploy succeeded"
else
  echo "$(date -u): deploy failed" >&2
fi
