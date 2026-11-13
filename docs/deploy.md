# Deploying to the homelab

The server runs as the `mealplanner` container on the home server, deployed by the Ansible repo
(`docker/mealplanner.yml`, also imported by `deploy.yml`). It sits on the `proxy` network behind
Traefik at `https://mealplanner.<DOMAIN>`, without Authelia: the Android app can't do forward-auth,
so the server checks its own bearer token. Gatus checks `/health`.

## One-time steps

1. **Docker Hub secrets for CI.** `DOCKER_USERNAME` and `DOCKER_PASSWORD` in this repo's GitHub
   secrets. CI pushes `kick1999/mealplanner:latest` and `kick1999/mealplanner:<commit sha>`.
2. **Vault keys** in the Ansible repo's `vars/vault.yml`:
   - `mealplanner_api_token`: a long random string (`openssl rand -hex 32`). The app needs it.
   - `mealplanner_nevo_csv`: path on your machine to `NEVO2025_v9.0.csv`. The playbook copies it
     to `<docker_config_dir>/mealplanner/nevo/`. The NEVO data may not be redistributed, so it is
     never baked into the image or committed.
   - `mealplanner_anthropic_api_key` (optional): a Claude Console API key for recipe generation.
     Without it the app's Generate button explains that generation is off.
3. **DNS** for `mealplanner.<DOMAIN>`, as for `cooking.<DOMAIN>`.
4. Deploy: `ansible-playbook -Kk docker/mealplanner.yml`.

The database and the cooking repo checkout live next to the NEVO file in
`<docker_config_dir>/mealplanner`, owned by uid 1000 (the container runs as 1000:1000).

## Updating

Watchtower pulls the new `:latest` nightly, like every other container. Database migrations run when
the new container starts. To update right away, run the playbook again or `docker restart` after a
`docker pull`.

## Connecting the app

In the app, Progress → Server: `https://mealplanner.<DOMAIN>` and the token from step 2, then
"Test connection". The web client is served on the same address.

## Kubernetes

`homelab-gitops/workloads/mealplanner/` holds a k3s version of the same setup for when the stack
moves to the cluster. Don't run both: they would each use their own database.
