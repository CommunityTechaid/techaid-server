# SITE IS DOWN — first 10 minutes

Before anything: **is it just a cold start?** Production runs 0 replicas outside Mon–Fri
08:00–20:00 Europe/London (KEDA cron), and UAT scales to 0 when idle. The first request after
scale-to-zero takes **40–90 s** (JVM boot). Wait, then:

```bash
time curl -s -o /dev/null -w "%{http_code}\n" --max-time 90 https://api.communitytechaid.org.uk/actuator/health
# {"status":"UP"} after a pause  =  healthy cold start, NOT an outage.
```

A 21:00 production "outage" is expected state (scaled to zero). A UAT that *never* wakes is a real
failure.

If it does not come up, work down this list. **Full decision tree, traps, and history live in the
`techaid-debugging-playbook` skill — load it.**

### 1. What is actually serving traffic? (the wedged-revision trap)
A crash-looped revision goes Unhealthy with 0 replicas, and Container Apps **silently keeps
routing to the previous healthy revision** — so the site looks "up" while running old code, and
the CI deploy reports success.

```bash
az containerapp show -g tada-2026 -n api-production \
  --query "{image:properties.template.containers[0].image, latestReady:properties.latestReadyRevisionName, latest:properties.latestRevisionName}" -o json
```

`latestReady` ≠ `latest` → the new revision never became healthy → find why it crash-looped
(steps 3–4), then deploy a **fresh** revision (do not fight the wedged one).

### 2. Crash-loop vs Azure platform fault?
```bash
az containerapp revision list -g tada-2026 -n api-production \
  --query "[].{name:name,active:properties.active,health:properties.healthState,replicas:properties.replicas}" -o table
az containerapp logs show -g tada-2026 -n api-production --type system --tail 50
```

`ContainerCreateFailure` (overlay-mount error, repeated) = the pod is stuck on a **broken Azure
host node** (this caused the 2026-07-01 outage — no app/DB/deploy change was involved). Recovery
reschedules onto a healthy node:

```bash
az containerapp stop -g tada-2026 -n api-production && az containerapp start -g tada-2026 -n api-production
```

### 3. Startup crash — Flyway ownership
`must be owner of table <x>` in console logs = a DB restore/import broke the ownership invariant
(tables must be owned by the app role `api_prod`, not `techaid_admin`). → `techaid-database-operations`.

### 4. Startup crash — schema validation
`ddl-auto` is `validate` by default (`none` in production) → a missing table/column means a
**Flyway migration is missing**. Do NOT set `DDL_AUTO=update`. → `techaid-database-operations`.

### 5. Console logs for the actual error
```bash
az containerapp logs show -g tada-2026 -n api-production --type console --tail 100
# historical: query AppTraces / ContainerAppConsoleLogs_CL in Log Analytics (techaid-diagnostics-and-observability)
```

---

## Things to know while firefighting
- **All mutating recovery commands normally require maintainer sign-off** (change-control rule 5).
  As the on-call maintainer you may act — then record what you did.
- **Downstream watchers will also be alarming** (don't be surprised, and check them after
  recovery): the ctawatch Azure Function (`cta-monitor-rg`) and the Azure Monitor alert
  `api-production-availability`.
- **Verify recovery** with all three: `/actuator/health` (UP), `/actuator/info` (`git.commit`
  correct), and `az containerapp revision list` (active revision Healthy/Running).
- For UAT substitute `api-testing` / `https://api-testing.communitytechaid.org.uk`.
