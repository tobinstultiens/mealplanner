# Deploying to the homelab

The server runs as the `mealplanner` workload in `homelab-gitops` (`workloads/mealplanner/`),
picked up by the ArgoCD ApplicationSet once it is on that repo's `main` branch. It follows the
grocy workload: one pod, `Recreate`, data on a host directory.

## One-time steps

1. **Docker Hub secrets for CI.** Add `DOCKER_USERNAME` and `DOCKER_PASSWORD` to this repo's
   GitHub secrets (the same ones the cooking repo uses), then re-run the latest CI run. CI pushes
   `kick1999/mealplanner:latest` and `kick1999/mealplanner:<commit sha>`.
2. **API token.** Create a Bitwarden item `mealplanner-api-token` holding a long random string,
   e.g. from `openssl rand -hex 32`. The ExternalSecret turns it into the pod's
   `MEALPLANNER_API_TOKEN`.
3. **Data directory** on the node, owned by uid 1000 (the pod runs as 1000:1000):
   ```bash
   mkdir -p ~/config/mealplanner/nevo
   cp data/nevo/NEVO2025_v9.0.csv ~/config/mealplanner/nevo/
   ```
   The database, the cooking repo checkout and the NEVO file all live there. The NEVO data may not
   be redistributed, so it is copied by hand and never baked into the image.
4. **Recipe generation (optional).** Create a Claude Console API key, store it in Bitwarden as
   `mealplanner-anthropic-api-key`, and add it to the ExternalSecret and the deployment as
   `ANTHROPIC_API_KEY`. Without it the app's Generate button explains that generation is off.
5. **DNS** for `mealplanner.tstultiens.com`, as for `cooking.tstultiens.com`.
6. Commit and push `workloads/mealplanner` in homelab-gitops.

## Updating

Set the image tag in `workloads/mealplanner/deployment.yaml` to the commit sha CI pushed, and push
homelab-gitops. Database migrations run when the new pod starts.

## Connecting the app

In the app, Progress → Server: `https://mealplanner.tstultiens.com` and the token from step 2, then
"Test connection".
