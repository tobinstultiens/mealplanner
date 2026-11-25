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
   The app's login in a Custom Tab (since #184) uses the same redirects, so the client needs no change.
   How long a login lasts is the refresh token's lifespan there: when Authelia refuses to renew it, the
   app ends on its login screen saying the login ran out.
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

## Who may use it (the group `mealplanner`)

> **Not run on the real server.** Nothing below was run against the homelab's Authelia or LLDAP. The server side
> was tested with signed tokens in `:server:test`, and end to end against the stand-in
> (`scripts/dev-oidc-provider.py --users`, see README "Run the server locally"). What is said about Authelia comes
> from its documentation and source (the `master` branch, read on 2026-11-20), not from the version that runs here:
> check each step as it says.

Today every account in Authelia (LLDAP) may use the meal planner. `MEALPLANNER_OIDC_GROUP` (issue 314) lets in only
the members of one group, which the request-and-approve registration of issue 314 needs: approving a person is
putting them in `mealplanner`. Two places check it: Authelia, when someone logs in to the client, and the server, on
every request (Authelia's check applies to logins only, not to renewals). The server's check is unset, and changes
nothing, until step 4.

**The Authelia lines**, for `local/authelia/configuration.yml` in the Ansible repo. Keep what the client has; add the
`groups` scope, the claims policy and the authorization policy:

```yaml
identity_providers:
  oidc:
    claims_policies:
      mealplanner:
        # Authelia gives the groups at the userinfo endpoint only; the server reads them from the access token.
        access_token: ['groups']
    authorization_policies:
      mealplanner:
        default_policy: 'deny'
        rules:
          - policy: 'two_factor'          # what the client asks for now
            subject: 'group:mealplanner'
    clients:
      - client_id: 'mealplanner'
        # ... everything it has now, and:
        scopes: ['openid', 'profile', 'email', 'offline_access', 'groups']
        claims_policy: 'mealplanner'
        authorization_policy: 'mealplanner'   # step 3; until then keep the one it has
```

The scope is `groups`; the claim is `groups`, an array of the account's group names, which the server reads from the
access token (`access_token_signed_response_alg: RS256`, as now). Authelia reads the groups from LLDAP again for every
token it issues, a renewal too.

**Switching it on, in this order.** Each step can stay as it is for days; nobody is locked out by steps 1 and 2.

1. **Everyone who uses it, in the group.** In LLDAP, add every person who uses the meal planner to `mealplanner` (the
   group exists). Who has an account in the meal planner: on the server, as the database's owner (see "Which user"),
   `sqlite3 -readonly <docker_config_dir>/mealplanner/mealplanner.db "select username from appUser"`. Check: each of
   those user names is a member of `mealplanner` in LLDAP's web page (Groups, `mealplanner`).
2. **The scope and the claims policy.** Add the `groups` scope and `claims_policy: 'mealplanner'` to the client, and
   the `claims_policies` block; deploy Authelia (`ansible-playbook -Kk docker/authelia.yml`). Nothing changes for the
   apps: they ask for the `groups` scope only once the server names it (step 4). Check: logging in on the web client
   still works; `docker logs authelia` shows no error about the client or the claims policy.
3. **The authorization policy.** Add the `authorization_policies` block and `authorization_policy: 'mealplanner'` to the
   client; deploy Authelia. From now on a person outside the group is refused on Authelia's page when logging in
   (`access_denied`); sessions from before keep renewing. Check: log in as a member on the web client (works), and as
   an account outside the group, if you have one to test with (refused by Authelia).
4. **The server.** Add `MEALPLANNER_OIDC_GROUP: mealplanner` to the container's `env` in `docker/mealplanner.yml`;
   deploy (`ansible-playbook -Kk docker/mealplanner.yml`). Every session from before ends once: its tokens were made
   without the `groups` scope, so the server answers them `no_groups`. The app (from the release after this change)
   ends the session and asks to log in again; an installed v0.1.9 shows the server's sentence ("This login does not
   say which groups the account is in. Log out and log in again; ...") where a screen fails to load, until the user
   chooses Settings, Log out, then Log in. Logging in again works: every app asks for the scopes the server names.
   Check: `curl -s https://mealplanner.<DOMAIN>/api/auth/config` lists `"groups"`; log out and in on the web client
   and the app, and the screens load. If a member still gets "does not say which groups" after logging in again,
   the access token has no `groups` claim: step 2 is not in effect (go back, below, and look at the claims policy).
   `docker logs mealplanner 2>&1 | grep '403 Forbidden'` shows the requests refused.

**Going back.** Remove `MEALPLANNER_OIDC_GROUP` from the container's `env` and deploy: at once every account of
Authelia may use the server again, with or without the claim, and no session needs a new login. To open the login
to everyone at Authelia too, remove `authorization_policy: 'mealplanner'` from the client (or put back the one it had)
and deploy Authelia. The scope and the claims policy can stay; they change nothing without the server setting.

**Adding and removing a person** by hand (registration, below, adds people on request): add them to `mealplanner` in LLDAP;
they can log in at once. Taking them out of the group stops them at their next renewal, at most an access token's life
later (Authelia's `lifespans.access_token`, an hour by default); they then see "This account may not use the meal
planner yet." Their data stays, and is theirs again if they are put back. Deleting the LLDAP account ends their
renewals as well. Do not give a deleted person's user name to someone else: Authelia keeps one `sub` per user name (its
table of opaque identifiers), and the meal planner knows an account by its `sub`, so the new person would log in to
the old one's data, which stays (not checked against the running Authelia).

**Other services behind Authelia.** Once people can ask for an account themselves ("Registration", below), an
account exists in LLDAP before anyone approved it. That account must open nothing. Check in
`local/authelia/configuration.yml` before registration is switched on:

- `access_control.default_policy` is `deny`, and every `access_control.rules` entry for a service is limited to a group
  (`subject: 'group:...'`). A rule with only a `domain` and `policy: one_factor` or `two_factor` lets any account of
  LLDAP in, a waiting one too.
- Every other OpenID Connect client has an `authorization_policy` limited to a group. A client without one uses
  `two_factor` for every account.
- The services that check groups themselves (with the groups from Authelia's headers or tokens) do not let in an
  account in no group.

`grep -n -E "default_policy|subject|authorization_policy|client_id|domain" local/authelia/configuration.yml` lists
the lines to read.

## Registration: people ask for an account

> **Not run on the real server.** No command in this section was run against the homelab's LLDAP, Authelia, Docker or
> Ansible; each is marked *(not run)*. The helper was tested against a fake LLDAP
> (`python3 -I scripts/test_registration_helper.py`): LLDAP's GraphQL API and its LDAP password change as LLDAP's
> documentation and source describe them (v0.6.3 and the default branch, read on 2026-11-20), not a running LLDAP. The
> server was tested against an in-memory helper (`:server:test`), and both together on a development machine with the
> fake LLDAP. Check each step as it says.

What it does (issue 314): a person asks for an account (user name, name, e-mail address, password). The form on the
app's login screen and the owner's Requests list in Settings come with part 3 of the issue, which is not built yet: until
that app is released, a request is made and decided on only through the API (`POST /api/registration`, and the owner's
`/api/registrations` routes, README "Accounts and login"). The server asks the **registration helper** to make the
account in LLDAP at once, in no group, with that password; the server keeps who asked, never the password. The person
can log in to Authelia but not use the meal planner: the server answers "Your request is waiting for approval." The
owner's account approves the request (the helper puts the account in `mealplanner`: the person can log in and gets an
empty account of their own) or rejects it (the helper deletes the account). A request not decided on in 14 days is
rejected by itself, its LLDAP account deleted.

**The owner's account** is account 1 (the one with the data from before accounts), and with `MEALPLANNER_OWNER` set only
under that user name. No other account can see or decide on requests.

**The limits**, fixed in the server: 5 requests an hour from one address, 30 a day from everyone, 20 waiting at once, 14
days to decide. The address is the last one in `X-Forwarded-For`, which Traefik writes for the connection it took. With
another proxy in front of Traefik (Cloudflare, say) every request comes from that proxy's address, and the limit per
address becomes one for everyone; nothing else breaks. The answer to "user name taken", "e-mail address in use" and "the
user name of a meal planner account" is one and the same, so the form tells nobody which.

**Why a helper.** Making users, deleting them and adding them to a group all need LLDAP's `lldap_admin`, and LLDAP cannot
narrow that right. `scripts/registration-helper.py` (plain Python 3, no packages) holds that credential so the meal
planner does not. It answers three calls (register, approve, reject), only with the shared secret, only for user names it
made itself that are still in no group (it keeps that list in its state file), and only into the group of its own
configuration. It never logs a request body; a user whose password cannot be set is deleted at once. Someone who has the
secret can still make accounts and approve them into `mealplanner`, so keep it in the vault and the helper's port off
every network but the two it needs.

**Before switching on:**

1. "Who may use it", above, done up to step 4: the server refuses to take requests without `MEALPLANNER_OIDC_GROUP`,
   since a waiting account in no group would otherwise get in.
2. "Other services behind Authelia", above, checked: a waiting account must open nothing anywhere.
3. A new person has to set up Authelia's second factor at the first login (the client is `two_factor`), which Authelia
   confirms by e-mail: check that `notifier` in `local/authelia/configuration.yml` sends e-mail *(not run)*.
4. Best after the app release that has the form and the Requests list (issue 314, part 3, not built yet). Before it,
   nobody can ask from the app, and the owner can decide only with `curl` and an access token.

**Steps, in this order:**

1. **The helper's LLDAP user** *(not run)*. In LLDAP's web page: a new user `mealplanner-registration` (any e-mail
   address of yours), a long random password, member of `lldap_admin`. Check: logging in to LLDAP's web page as it works.
2. **Vault keys** in the Ansible repo's `vars/vault.yml` *(not run)*:
   - `mealplanner_registration_secret`: from `openssl rand -hex 32`. The helper and the server share it.
   - `lldap_registration_password`: the password of step 1.
3. **The helper container**, in `docker/mealplanner.yml` of the Ansible repo *(not run)*. Copy
   `scripts/registration-helper.py` of this repository to `files/mealplanner/registration-helper.py` there, and again when
   it changes here. A network of its own joins the helper and the meal planner; the helper also joins the network LLDAP is
   on. No `ports`, no Traefik labels: nothing outside those networks reaches it. Fill in the three `<...>`:

   ```yaml
   - name: The registration helper's network
     community.docker.docker_network:
       name: mealplanner-registration
       internal: true

   - name: The registration helper's folder
     ansible.builtin.file:
       path: "{{ docker_config_dir }}/mealplanner-registration/state"
       state: directory
       owner: "10002"
       mode: "0700"

   - name: The registration helper's script
     ansible.builtin.copy:
       src: files/mealplanner/registration-helper.py
       dest: "{{ docker_config_dir }}/mealplanner-registration/registration-helper.py"
       mode: "0444"

   - name: The registration helper
     community.docker.docker_container:
       name: mealplanner-registration
       image: python:3.13-alpine
       user: "10002:10002"
       read_only: true
       command: ["python3", "-I", "/app/registration-helper.py"]
       restart_policy: unless-stopped
       volumes:
         - "{{ docker_config_dir }}/mealplanner-registration/registration-helper.py:/app/registration-helper.py:ro"
         - "{{ docker_config_dir }}/mealplanner-registration/state:/state"
       networks:
         - name: mealplanner-registration
         - name: <the network LLDAP is on>
       env:
         HELPER_SECRET: "{{ mealplanner_registration_secret }}"
         HELPER_GROUP: mealplanner
         HELPER_STATE: /state/waiting.json
         LLDAP_URL: http://<LLDAP's container>:17170
         LLDAP_LDAP_HOST: <LLDAP's container>
         LLDAP_LDAP_PORT: "3890"
         LLDAP_BASE_DN: <LLDAP's base DN, its LLDAP_LDAP_BASE_DN>
         LLDAP_ADMIN_USER: mealplanner-registration
         LLDAP_ADMIN_PASSWORD: "{{ lldap_registration_password }}"
   ```

   Add `mealplanner-registration` to the `networks` of the `mealplanner` container. Deploy with
   `ansible-playbook -Kk docker/mealplanner.yml`. Check: `docker logs mealplanner-registration` says "Registration helper
   on port 8091, approving into mealplanner"; it stops at once, saying what, when a setting is missing, the secret is
   shorter than 32 characters or the group is one of LLDAP's own. A call without the secret is refused:
   `docker run --rm --network mealplanner-registration curlimages/curl -s -X POST http://mealplanner-registration:8091/register`
   prints `{"error": "unauthorised"}`.
4. **The server.** Add to the `mealplanner` container's `env` *(not run)*:

   ```yaml
   MEALPLANNER_REGISTRATION_HELPER_URL: http://mealplanner-registration:8091
   MEALPLANNER_REGISTRATION_HELPER_SECRET: "{{ mealplanner_registration_secret }}"
   MEALPLANNER_REGISTRATION: "on"
   ```

   Deploy. Check: `curl -s https://mealplanner.<DOMAIN>/api/registration` prints `{"open":true}`. If it says `false`,
   `docker logs mealplanner 2>&1 | grep MEALPLANNER_REGISTRATION` says what is missing.
5. **One request to try it** *(not run)*: ask for `test-request` with
   `curl -s -X POST https://mealplanner.<DOMAIN>/api/registration -H 'Content-Type: application/json' -d '{"username":
   "test-request", "displayName": "Test", "email": "<an address of yours>", "password": "<ten characters or more>"}'`,
   which answers `{"username":"test-request"}` (from part 3's app release on, the login screen's form does the same).
   Check: LLDAP's web page has `test-request` in no group; logging in to the meal planner as it says the request waits.
   Reject it as the owner: with part 3's app, in its Requests list; before it,
   `curl -s -X POST -H 'Authorization: Bearer <an access token of the owner>' https://mealplanner.<DOMAIN>/api/registrations/test-request/reject`
   (logged in to the web client as the owner, the access token is in the browser's local storage, key `auth.session`).
   Check: LLDAP no longer has it.
   `docker logs mealplanner-registration` shows each step with the user name, and neither log shows the password.

**Going back.** Remove `MEALPLANNER_REGISTRATION` and deploy: `GET /api/registration` answers `{"open":false}` (so part 3's
login screen no longer offers the form), and new requests are refused; those waiting can still be decided on and still expire. To remove the helper too, first decide on every
waiting request, then remove the two `MEALPLANNER_REGISTRATION_HELPER_*` settings, the helper's container and network,
and the LLDAP user `mealplanner-registration`.

**Errors.** With the helper or LLDAP down, a request is answered "The account directory cannot be reached; try again
later" and nothing is kept; approving or rejecting answers the same and the request stays. A request whose outcome is
not known (the helper took too long) is rejected again at once, which deletes the account if it was made; if even that
fails, it stays in the owner's list until decided on or expired. Neither the server nor the helper logs a password.

## The cooking site

`COOKING_REPO_URL` (set in the container's `env`, with `COOKING_REPO_PATH` for where its clone goes) is the
server's cooking site. Since issue 208 it is the recipe bank of every account that has not chosen another site
or none in the app, not only the owner's: each account finds its recipes in its bank and adds the ones it cooks.
The server fetches it once every six hours into one clone and imports it into each account; the owner's data and
clone stay where they were. Any account's Sync fetches it too, but not within five minutes of the last fetch (then
it imports the clone as it is), so that nobody's taps keep the others waiting on the fetch: an edit pushed to the
site arrives with the first Sync at least five minutes after the last fetch. `COOKING_REPO_FETCH_INTERVAL_MINUTES` changes
the five minutes (`0`: every Sync fetches, as before issue 215); nothing needs setting. An account that had set a repository of its own keeps it. Every account sees the
site's address in the app, without a user name or password in it; the owner sees it as configured. To keep the
site to the owner, as before, set `COOKING_REPO_SHARED=0` (in the Ansible repo's `docker/mealplanner.yml`); other
accounts then set a site of their own on the recipe bank screen, or have none.

The database and the cooking repo checkout live next to the NEVO file in
`<docker_config_dir>/mealplanner`, owned by the uid the container runs as (see "Which user" under "Backing up the database"). So do the
recipe pictures, in `recipe-images/` next to the database (`MEALPLANNER_IMAGE_DIR` moves them): that
folder must be on the same persistent volume as the database, since the database records which
pictures exist. With `MEALPLANNER_DB_URL=jdbc:sqlite:/data/mealplanner.db` and `/data` mounted, as now,
nothing needs setting. Lost pictures come back at the next cooking-site sync. A copy is about 130 KB
(the one photo on the site today, 800 × 600).

## Updating

Watchtower pulls the new `:latest` nightly, like every other container. Database migrations run when
the new container starts (take a backup first, see below). To update right away, run the playbook again or `docker restart` after a
`docker pull`.

## Backing up the database

> **Not yet run on the real server.** The commands below were run against a throwaway WAL database on a
> development machine (a live writer with an uncheckpointed write, `.backup`, the restore steps on a
> copy), not on the server and not in the container. Everything marked "unverified" waits for the owner's
> one real backup and restore (issue 166); remove the marks then.

The container applies pending migrations when it starts, all of them and the new schema version in one
transaction (SQLDelight's `JdbcSqliteDriver`), and SQLite's DDL is transactional. A migration that fails
(an error in its SQL) is rolled back as a whole and the file stays as it was; `MigrationTest` forces
such a failure after all migrations ran and shows the file unchanged. The server then does not start, and
the old image can run on the old file. So a backup does not guard against a migration that stops
half-way. It guards against what the transaction cannot see: a migration that succeeds and moves data
wrongly (`13.sqm` moved rows), a release that writes wrong data, a disk that fails. Without a copy, the
old state cannot be got back.

Take it with SQLite's own copy, not with `cp`. The database is in WAL mode, so the newest writes live in
`mealplanner.db-wal` until a checkpoint, and `cp mealplanner.db` of a running server can give a file
that lacks them or that is torn. `.backup` copies a consistent state while the server runs, including
what is still only in the log; the container has no `sqlite3`, so run it on the host, on the file the
container's `/data` is mounted from (a local disk, not a network share). Run it as the user that owns the
database (see "Which user" below): a reader needs write access to the `-shm` file of a live database.
Create the folder once, with the same owner:

    sudo -u '#<uid>' mkdir <docker_config_dir>/mealplanner/backup
    sudo -u '#<uid>' sqlite3 <docker_config_dir>/mealplanner/mealplanner.db ".backup '<docker_config_dir>/mealplanner/backup/mealplanner-$(date +%F).db'"

(`VACUUM INTO 'file'` does the same, to a file that does not exist yet.) In a crontab every `%` must be
written `\%`, or cron takes it as a line end, so there the line is

    0 3 * * * sqlite3 <docker_config_dir>/mealplanner/mealplanner.db ".backup '<docker_config_dir>/mealplanner/backup/mealplanner-$(date +\%F).db'"

in the crontab of that user (`sudo crontab -u '#<uid>' -e`; unverified: that cron accepts a numeric user
here and the line runs). Or use a systemd timer, which has no such rule. Run it before Watchtower's
nightly pull, and delete the copies older than a week
(`find <docker_config_dir>/mealplanner/backup -name 'mealplanner-*.db' -mtime +7 -delete`). Or stop the
container first (`docker stop mealplanner`) and copy `mealplanner.db` together with `mealplanner.db-wal`
and `mealplanner.db-shm`, if there are any. A copy on the same disk does not guard against the disk:
keep one on another machine as well. The pictures need none (see above).

**Which user.** The Dockerfile creates `mealplanner` with uid **10001** and runs the container as it; the
compose file or playbook is in the Ansible repo, not here. This document used to say 1000:1000, which is
right only if that file sets `user: 1000:1000` (or the folder was created as 1000 and the container is
run as 1000). Unverified: which one the deployed volume has. Find out with
`docker exec mealplanner id` and `ls -ln <docker_config_dir>/mealplanner`: the uid the container
runs as must own `mealplanner.db`, and that is the `<uid>` above and in the `chown` below. A container
that cannot write the file fails to start or to write ("readonly database").

**Before an update**, note the commit that runs now: `curl -s https://mealplanner.<DOMAIN>/health`
shows `commit`. After the update `/health` shows the new one, so the old one is only to be found in what
you wrote down, or among the `kick1999/mealplanner:<commit sha>` tags on Docker Hub (CI pushes one per
commit; find the one of the commit before the merge in `git log`). Writing it down can be left to the
backup job: `curl -s https://mealplanner.<DOMAIN>/health > <docker_config_dir>/mealplanner/backup/health-$(date +%F).json`
(unverified; in a crontab mind the `\%`).

**Going back** after a bad update:

1. `docker stop mealplanner`.
2. Put the copy in place of `mealplanner.db` and delete `mealplanner.db-wal` and `mealplanner.db-shm`
   next to it (a leftover log of the new state would be replayed into the old file). Done as root, the
   file belongs to root and the container cannot write it: `chown <uid>:<uid> mealplanner.db` (the uid
   of "Which user"), or copy as that user.
3. Start the image of before, not `:latest`: `kick1999/mealplanner:<commit sha>`, the commit you noted
   before the update. The old image does not know the new schema, and Watchtower would pull `:latest`
   again, so pin the tag in the container's `image` until a fix is merged.

Everything written since the copy is lost, so check the data of a release that has migrations (`git log`
shows the new `.sqm` files) soon after the nightly update.

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

A release built with the server's address (README, "Releases") opens Authelia's login page at once.
Otherwise the app's login screen asks for the server: `https://mealplanner.<DOMAIN>`. The web client
is served on the same address and goes to the same login page. Each account's recipe bank is the
server's cooking site unless it chooses a repository of its own, or none, on the recipe bank screen.

## Kubernetes

`homelab-gitops/workloads/mealplanner/` holds a k3s version of the same setup for when the stack
moves to the cluster. Don't run both: they would each use their own database.
