#!/bin/sh
# Invites someone to the Worker source: makes them a random code, stores only its SHA-256 as the
# Worker secret CODE_<name>, and prints the invite link to send them. Run after `npx wrangler login`.
#   source/cloudflare/add-friend.sh <name> <worker-url>
# To take someone's access away: npx wrangler secret delete CODE_<name>
set -eu
[ $# -eq 2 ] || { sed -n '4p' "$0"; exit 2; }
name=$1 url=${2%/}
echo "$name" | grep -Eq '^[a-z0-9_-]{1,32}$' || { echo "name: up to 32 lowercase letters, digits, - or _"; exit 2; }
case "$url" in https://*) ;; *) echo "worker-url must start with https://"; exit 2 ;; esac
code=$(openssl rand -base64 18 | tr -d '/+=\n')
hash=$(printf %s "$code" | shasum -a 256 | cut -d' ' -f1)
cd "$(dirname "$0")"
printf %s "$hash" | npx --yes wrangler secret put "CODE_$name" >/dev/null
echo "Send $name this invite (it only works for them; anyone with it can download):"
echo "$url/index.json#$name.$code"
