# techaid-robots — edge-served robots.txt

Serves `robots.txt` for `api.communitytechaid.org.uk` from the Cloudflare edge so that
well-behaved crawlers never reach the origin and never wake the scale-to-zero
`api-production` Container App.

Deployed 2026-07-29. Cloudflare account **Community TechAid**
(`b54c483820627f3bc0532564ac159369`), zone `communitytechaid.org.uk`
(`b8c00bcfc33fa7ba5a1152fec7fada2a`).

## Why

Application Insights `AppRequests` (14d to 2026-07-29) showed `/robots.txt` reaching the
origin roughly **2x/day, every one a 404**. Before this Worker, the zone served a
`robots.txt` containing *only* Cloudflare's Content Signals comment block — no
`User-agent` or `Disallow` directives at all, so it restricted nothing, and Cloudflare
still did an origin fetch to produce it.

This does **not** stop malicious scanners (`.php` probe paths); those ignore
`robots.txt`. Blocking them is WAF / Transform-rule work at the zone level and is
deliberately not handled here — `wrangler` manages Workers, not WAF rules.

## Deploy

```bash
cd infra/cloudflare/robots-worker
npx wrangler deploy
```

`wrangler` is already a devDependency of the repo root, so no separate install is needed.
Requires an authenticated session (`npx wrangler whoami`); the token needs
`workers_scripts:write`, `workers_routes:write` and `zone:read`.

## Verify

```bash
# Should return 674 bytes with Cache-Control: public, max-age=86400
curl -sD - https://api.communitytechaid.org.uk/robots.txt | head

# Query string must NOT bypass the Worker (route pattern ends in *)
curl -s -o /dev/null -w '%{size_download}\n' 'https://api.communitytechaid.org.uk/robots.txt?x=1'

# Negative control — these must still reach the origin
curl -s -o /dev/null -w '%{http_code}\n' https://api.communitytechaid.org.uk/actuator/health  # 200
curl -s -o /dev/null -w '%{http_code}\n' https://api.communitytechaid.org.uk/robots.txt.bak   # 404
```

## Rollback

```bash
npx wrangler delete --name techaid-robots
```

This removes the Worker and its route; `robots.txt` reverts to the previous
Cloudflare-generated Content Signals body served via an origin fetch.

## Gotcha

Cloudflare route patterns match the **full URL including query string**. The pattern must
be `…/robots.txt*` — with a bare `…/robots.txt`, a request to `/robots.txt?x=1` does not
match the route and falls through to the origin (verified during deployment).
`src/index.js` re-checks `url.pathname`, so the trailing wildcard cannot blackhole
anything else: `/robots.txt.bak` and friends pass straight through.
