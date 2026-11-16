# Deploying to the homelab

The server runs as the `mealplanner` container on the home server, deployed by the Ansible repo
(`docker/mealplanner.yml`, also imported by `deploy.yml`). It sits on the `proxy` network behind
Traefik at `https://mealplanner.<DOMAIN>`, without Authelia's forward-auth: the app and the web
client log in with Authelia themselves (OpenID Connect) and the server checks their access tokens.
Gatus checks `/health`.

## One-time steps

1. **Docker Hub secrets for CI.** `DOCKER_USERNAME` and `DOCKER_PASSWORD` in this repo's GitHub
   secrets. CI pushes `kick1999/mealplanner:latest` and `kick1999/mealplanner:<commit sha>`.
2. **Authelia client.** `local/authelia/configuration.yml` in the Ansible repo has the client
   `mealplanner`: public, PKCE, two-factor, JWT access tokens, refresh tokens, redirects to
   `https://mealplanner.<DOMAIN>/app/login` (Android) and `https://mealplanner.<DOMAIN>/` (web), and
   CORS for the web client's token requests. Deploy it with `ansible-playbook -Kk docker/authelia.yml`.
3. **Vault keys** in the Ansible repo's `vars/vault.yml`:
   - `mealplanner_owner`: your LLDAP username, so your existing data goes to your account. Empty:
     whoever logs in first gets it.
   - `mealplanner_nevo_csv`: path on your machine to `NEVO2025_v9.0.csv`. The playbook copies it
     to `<docker_config_dir>/mealplanner/nevo/`. The NEVO data may not be redistributed, so it is
     never baked into the image or committed.
4. **DNS** for `mealplanner.<DOMAIN>`, as for `cooking.<DOMAIN>`.
5. Deploy: `ansible-playbook -Kk docker/mealplanner.yml`. It sets `MEALPLANNER_OIDC_ISSUER`,
   `MEALPLANNER_OWNER` and `MEALPLANNER_ANDROID_CERTS` (the release key's certificate fingerprint, for
   the App Link).

The database and the cooking repo checkout live next to the NEVO file in
`<docker_config_dir>/mealplanner`, owned by uid 1000 (the container runs as 1000:1000). So do the
recipe pictures, in `recipe-images/` next to the database (`MEALPLANNER_IMAGE_DIR` moves them): that
folder must be on the same persistent volume as the database, since the database records which
pictures exist. With `MEALPLANNER_DB_URL=jdbc:sqlite:/data/mealplanner.db` and `/data` mounted, as now,
nothing needs setting. Lost pictures come back at the next cooking-site sync. A copy is about 130 KB
(the one photo on the site today, 800 × 600).

## Updating

Watchtower pulls the new `:latest` nightly, like every other container. Database migrations run when
the new container starts. To update right away, run the playbook again or `docker restart` after a
`docker pull`.

## What runs, and whether its data is fresh

Both answers are public and say nothing about any account.

- **What runs.** `curl -s https://mealplanner.<DOMAIN>/health` answers
  `{"status":"ok","version":"0.1.3","commit":"<sha>"}`: the latest `v*` tag the build can see (without
  tags, such as a shallow checkout, `mealplanner.version` in `gradle.properties`, which may lag the
  latest tag) and the commit it was built from (`GITHUB_SHA` in CI). After Watchtower's nightly update, compare `commit` with
  `main`'s head. A local build says `dev` for what it cannot know.
- **Whether the store data is fresh.** `curl -s -o /dev/null -w '%{http_code}\n' https://mealplanner.<DOMAIN>/health/data`
  prints 200 when it is, and 503 with the reasons when it is not (`curl -s .../health/data` shows
  `{"status":"stale","problems":["..."]}`). It is 503 when offer fetching is on and a store's last
  successful fetch is more than 48 hours ago (or it never succeeded and keeps failing), when the Lidl
  check warns that its page looks stale, or when the last complete regular-price lookup is more than 14
  days ago. The texts are the ones the apps show and the server logs, except that a store's error is cut to its first line and about 200 characters here (the Offers screen has all of it). With `MEALPLANNER_FETCH_OFFERS=off`
  it is always 200. Nothing recorded yet (a new server) is not a problem.

To be told about a broken scraper, add a Gatus check on `/health/data` next to the one on `/health`
(in the Ansible repo), e.g. condition `[STATUS] == 200` with an interval of an hour. Keep `/health`
for the readiness probe: it stays "ok" whatever the data looks like, so a stale store never makes
Traefik or Docker treat the container as down.

## Recipe generation (postponed)

Claude-based generation is built but switched off: the container gets no `ANTHROPIC_API_KEY`, and
the app's Generate button says generation is off. To turn it on later, add the key to the vault and
to the container's `env` in `docker/mealplanner.yml`.

## Connecting the app

In the app, Progress → Settings → Log in, with `https://mealplanner.<DOMAIN>` as the server. The web
client is served on the same address and logs in the same way. Each account sets its own cooking
site on the Recipes tab.

## Kubernetes

`homelab-gitops/workloads/mealplanner/` holds a k3s version of the same setup for when the stack
moves to the cluster. Don't run both: they would each use their own database.
