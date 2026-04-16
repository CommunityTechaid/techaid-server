#!/bin/bash
# Manual deploy of latest API image from GHCR.
# Usage: sudo ./deploy-api.sh
#
# Install on server:
#   sudo cp scripts/deploy-api.sh /usr/local/bin/deploy-api.sh
#   sudo chmod +x /usr/local/bin/deploy-api.sh

set -e

APP="api"
IMAGE="ghcr.io/communitytechaid/techaid-server:latest"

echo "Pulling latest image..."
docker pull "$IMAGE"

# Resolve the unique digest so Dokku treats it as a genuinely new image.
# Using :latest alone can cause "No changes detected" if the tag was
# already pulled locally before Dokku had a chance to deploy it.
DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' "$IMAGE")
echo "Resolved digest: $DIGEST"

echo "Deploying to Dokku..."
dokku git:from-image "$APP" "$DIGEST"

echo "Done. Check with: dokku ps:report $APP"
