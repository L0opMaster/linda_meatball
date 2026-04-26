# Production Deployment on VPS — Spring Boot + MySQL + Docker + GitHub Actions

A step-by-step guide adapted from the YouTube tutorial
"Production Deployment On VPS Using Docker & Github Actions pipeline"
(`https://www.youtube.com/watch?v=QI7ZAlwJ2rY`),
**rewritten for MySQL instead of PostgreSQL** and tailored to this project
(`backend-spring-boot`).

---

## 0. What you will have at the end

- A Hostinger KVM VPS running Docker.
- Your Spring Boot app deployed as a Docker image on Docker Hub.
- A MySQL container next to the app, both managed by Docker Compose.
- A GitHub Actions workflow that, on every push to `main`:
  1. Runs the unit tests.
  2. Builds a Docker image.
  3. Pushes it to Docker Hub.
  4. SSHs into the VPS, pulls the new image, and restarts the containers.
- App reachable at `http://<VPS_IP>:8080` (or your domain on port 80/443
  once you add nginx + Let's Encrypt — outside the scope of this doc).

---

## 1. Prerequisites

- A Hostinger account (or any VPS provider — DigitalOcean, Hetzner, etc.).
- A Docker Hub account (free tier is fine; max 1 private repo, unlimited public).
- A GitHub account with this repo pushed up.
- Local tools: `git`, `ssh`, `ssh-keygen`, Docker Desktop or `docker` CLI for
  testing the image locally before pushing.

---

## 2. Buy and configure the VPS

1. Go to <https://www.hostinger.com/> → **Services → VPS Hosting**.
2. Pick **KVM 2** (2 vCPU, 8 GB RAM, 100 GB NVMe). 24-month plan is the
   cheapest. Optional coupon `BUALI` for 10% off (from the video).
3. On the OS / Application step:
   - Choose **OS with Docker pre-installed** (under "Application" → search
     "Docker"). This saves you from manually installing Docker.
4. Set a **strong root password** when asked. Save it in a password manager.
5. Pick the closest datacenter region for low latency.
6. Complete the purchase. The VPS will be provisioned in a few minutes.
7. In the Hostinger dashboard (`hpanel.hostinger.com` → VPS), copy:
   - **IP address** (e.g., `123.45.67.89`)
   - **SSH username** (always `root` on a fresh VPS)
   - The **password** you just set.

---

## 3. First SSH connection (password-based)

From your local terminal:

```bash
ssh root@<VPS_IP>
```

Type `yes` to trust the host fingerprint, then enter the root password.
You should land in `/root`. Verify Docker is installed:

```bash
docker --version
docker compose version
```

If those work, the VPS is ready. Type `exit` to disconnect.

---

## 4. Generate an SSH key for CI/CD (passwordless deploy)

GitHub Actions can't type a password every deploy, so generate a dedicated
SSH key pair and put the **public** half on the VPS, keep the **private**
half in GitHub Secrets.

```bash
ssh-keygen -t rsa -b 4096 -C "ci-cd" -f ~/.ssh/ci-cd
```

- A passphrase is **recommended** (the workflow can supply it).
- Two files appear: `~/.ssh/ci-cd` (private) and `~/.ssh/ci-cd.pub` (public).

Copy the public key to the VPS:

```bash
ssh-copy-id -i ~/.ssh/ci-cd.pub root@<VPS_IP>
```

Verify keyless login works:

```bash
ssh -i ~/.ssh/ci-cd root@<VPS_IP>
```

If that logs you in (after typing the passphrase, if you set one), you're done.

---

## 5. Dockerize the Spring Boot app — `Dockerfile`

Create `Dockerfile` at the project root (this project already has one — diff
against this and update if needed).

```dockerfile
# ---------- Build stage ----------
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# Copy the Maven wrapper + pom first to leverage Docker layer caching
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Pre-download dependencies (cached unless pom.xml changes)
RUN chmod +x ./mvnw && ./mvnw -B dependency:go-offline

# Copy sources and build
COPY src src
RUN ./mvnw clean package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre-jammy
ARG PROFILE=prod
ARG APP_VERSION=1.0.0
WORKDIR /app

# Copy only the built jar from the build stage
COPY --from=build /app/target/*.jar /app/app.jar

EXPOSE 8081

ENV ACTIVE_PROFILE=${PROFILE}
ENV JAR_VERSION=${APP_VERSION}

ENTRYPOINT ["sh", "-c", "java -Dspring.profiles.active=${ACTIVE_PROFILE} -jar /app/app.jar"]
```

Notes vs the video:
- Uses `eclipse-temurin` (the official Adoptium image). The video used the
  same family.
- The runtime stage uses **JRE** (smaller) instead of JDK.
- Server port is **8081** to match `application.yml` (the video used 8080).
- Profile is `prod` by default (we'll create `application-prod.yml` below).
- The jar is renamed to `app.jar` so the entrypoint doesn't depend on the
  Maven `artifactId`/version. This avoids the "unable to access jar file"
  bug the YouTuber hit.

Test it locally first:

```bash
docker build -t backend-spring-boot:local .
docker run --rm -p 8081:8081 \
  -e DB_URL=host.docker.internal -e DB_PORT=3306 \
  -e DB_NAME=kaknnea -e DB_USERNAME=root -e DB_PASSWORD=mypassword \
  backend-spring-boot:local
```

If it boots, the Dockerfile is correct.

---

## 6. Spring profiles — local vs VPS

You already have:
- `application.yml` — defaults, profile-agnostic (JWT, CORS, server port).
- `application-local.yml` — local dev datasource using `${DB_*}` from `.env`.
- `application-dev.yml` — local convenience overrides (permit-all, wildcard CORS).

For VPS deployment **create a new `application-prod.yml`** (don't reuse `dev`,
because `dev` has security relaxed for local work).

`src/main/resources/application-prod.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_URL}:${DB_PORT}/${DB_NAME}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: none      # Flyway owns the schema
    show-sql: false
    properties:
      hibernate:
        format_sql: false
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8081
  address: 0.0.0.0

app:
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:https://yourdomain.com}
```

Then in the Dockerfile, the default `ARG PROFILE=prod` makes the container
load `application.yml` + `application-prod.yml` automatically.

---

## 7. Docker Compose for the VPS — `docker-compose.prod.yml`

Create `docker-compose.prod.yml` at the project root.

```yaml
services:
  db:
    image: mysql:8.0
    container_name: mysql-backend
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${DB_NAME}
      MYSQL_USER: ${DB_USERNAME}
      MYSQL_PASSWORD: ${DB_PASSWORD}
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p${MYSQL_ROOT_PASSWORD}"]
      interval: 10s
      timeout: 5s
      retries: 10

  app:
    image: ${DOCKER_USERNAME}/${APP_NAME}:latest
    container_name: ${APP_NAME}
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy
    ports:
      - "8081:8081"
    environment:
      DB_URL: db                # <- container name of the MySQL service
      DB_PORT: "3306"
      DB_NAME: ${DB_NAME}
      DB_USERNAME: ${DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      CORS_ALLOWED_ORIGINS: ${CORS_ALLOWED_ORIGINS}

volumes:
  mysql_data:

networks:
  default:
    name: backend-default
```

Key MySQL-specific differences vs the video's PostgreSQL compose:

| Aspect              | PostgreSQL (video)                  | MySQL (here)                                          |
|---------------------|-------------------------------------|-------------------------------------------------------|
| Image               | `postgres:17.5`                     | `mysql:8.0`                                           |
| Default port        | `5432`                              | `3306`                                                |
| Root password env   | n/a                                 | `MYSQL_ROOT_PASSWORD` (required)                      |
| Database name env   | `POSTGRES_DB`                       | `MYSQL_DATABASE`                                      |
| User env            | `POSTGRES_USER`                     | `MYSQL_USER`                                          |
| Password env        | `POSTGRES_PASSWORD`                 | `MYSQL_PASSWORD`                                      |
| Data dir env        | `PGDATA`                            | (none — `/var/lib/mysql` by default)                  |
| Volume path         | `/var/lib/postgresql/data`          | `/var/lib/mysql`                                      |
| Healthcheck         | `pg_isready -U ...`                 | `mysqladmin ping ...`                                 |
| JDBC URL prefix     | `jdbc:postgresql://`                | `jdbc:mysql://`                                       |
| JDBC driver         | `org.postgresql.Driver`             | `com.mysql.cj.jdbc.Driver` (auto-detected)            |

---

## 8. The `.env` file on the VPS

The VPS-side `.env` lives at `/opt/<APP_NAME>/.env` and is **created by the
deploy workflow from GitHub Secrets** (so credentials never sit in the repo).

Conceptually it will contain:

```env
DOCKER_USERNAME=yourdockerhubuser
APP_NAME=backend-spring-boot
DB_URL=db
DB_PORT=3306
DB_NAME=kaknnea
DB_USERNAME=app_user
DB_PASSWORD=<strong password>
MYSQL_ROOT_PASSWORD=<strong root password>
JWT_SECRET=<32+ byte random string>
CORS_ALLOWED_ORIGINS=https://yourdomain.com
```

Note: the **local** `.env` you already have in the repo root is for local dev
only and is .gitignored. Don't reuse those weak values in prod.

---

## 9. GitHub Actions workflow — `.github/workflows/deploy.yml`

Create the directory and file:

```bash
mkdir -p .github/workflows
```

`.github/workflows/deploy.yml`:

```yaml
name: backend-spring-boot pipeline

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

env:
  APP_NAME: backend-spring-boot

jobs:
  tests:
    name: Tests
    runs-on: ubuntu-latest
    env:
      TZ: Asia/Phnom_Penh
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'corretto'

      - name: Cache Maven dependencies
        uses: actions/cache@v4
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-m2

      - name: Run unit tests
        run: ./mvnw -B clean test

  build-and-deploy:
    name: Build & deploy
    needs: tests
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'corretto'

      - name: Cache Maven dependencies
        uses: actions/cache@v4
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-m2

      - name: Extract project version
        id: extract_version
        run: |
          VERSION=$(./mvnw -q -Dexec.executable=echo -Dexec.args='${project.version}' --non-recursive exec:exec)
          echo "version=$VERSION" >> "$GITHUB_OUTPUT"

      - name: Build application jar
        run: ./mvnw -B clean package -DskipTests

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Login to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_PASSWORD }}

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: |
            ${{ secrets.DOCKER_USERNAME }}/${{ env.APP_NAME }}:latest
            ${{ secrets.DOCKER_USERNAME }}/${{ env.APP_NAME }}:${{ steps.extract_version.outputs.version }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
          build-args: |
            PROFILE=prod
            APP_VERSION=${{ steps.extract_version.outputs.version }}

      - name: Deploy to VPS
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.VPS_HOST }}
          username: ${{ secrets.VPS_USERNAME }}
          key: ${{ secrets.VPS_SSH_KEY }}
          passphrase: ${{ secrets.VPS_SSH_KEY_PASSPHRASE }}
          port: ${{ secrets.VPS_PORT }}
          script: |
            mkdir -p /opt/${{ env.APP_NAME }}
            cd /opt/${{ env.APP_NAME }}

            # 1. Write .env from secrets
            cat > .env <<EOF
            DOCKER_USERNAME=${{ secrets.DOCKER_USERNAME }}
            APP_NAME=${{ env.APP_NAME }}
            DB_URL=db
            DB_PORT=3306
            DB_NAME=${{ secrets.DB_NAME }}
            DB_USERNAME=${{ secrets.DB_USERNAME }}
            DB_PASSWORD=${{ secrets.DB_PASSWORD }}
            MYSQL_ROOT_PASSWORD=${{ secrets.MYSQL_ROOT_PASSWORD }}
            JWT_SECRET=${{ secrets.JWT_SECRET }}
            CORS_ALLOWED_ORIGINS=${{ secrets.CORS_ALLOWED_ORIGINS }}
            EOF

            # 2. Refresh docker-compose.prod.yml from the repo
            rm -f docker-compose.prod.yml
            curl -H "Authorization: token ${{ secrets.GITHUB_TOKEN }}" \
                 -o docker-compose.prod.yml \
                 https://raw.githubusercontent.com/${{ github.repository }}/refs/heads/main/docker-compose.prod.yml

            # 3. Pull the new image
            docker compose -f docker-compose.prod.yml --env-file .env pull

            # 4. Recreate containers
            docker compose -f docker-compose.prod.yml --env-file .env down
            docker compose -f docker-compose.prod.yml --env-file .env up -d

            # 5. Prune old, unused images
            docker image prune -f
```

Things the video got wrong (and we fix here):

- Used `setup-java@v3` and other older action versions → bumped to v4 / v5 / v3.
- Wrote `--exec` instead of `-Dexec.executable` (transcript typo) → fixed.
- Forgot to expose the app port in compose → included from the start.
- Forgot `push: true` and put `pull` in the script → corrected.
- Renamed jar with version baked in, then build args overrode it to empty
  → we copy as `app.jar` so it doesn't matter.

---

## 10. GitHub Secrets — what to add and where

**Repo → Settings → Secrets and variables → Actions → New repository secret.**

| Secret name              | Value                                                                |
|--------------------------|----------------------------------------------------------------------|
| `DOCKER_USERNAME`        | Your Docker Hub username                                             |
| `DOCKER_PASSWORD`        | Docker Hub **Personal Access Token** (Account Settings → Security)   |
| `VPS_HOST`               | VPS IP address                                                       |
| `VPS_USERNAME`           | `root`                                                               |
| `VPS_PORT`               | `22`                                                                 |
| `VPS_SSH_KEY`            | Contents of `~/.ssh/ci-cd` (the **private** key, full file)          |
| `VPS_SSH_KEY_PASSPHRASE` | The passphrase you set when generating the key (omit if none)        |
| `DB_NAME`                | e.g. `kaknnea`                                                       |
| `DB_USERNAME`            | e.g. `app_user` (avoid using `root` for the app)                     |
| `DB_PASSWORD`            | Strong random string                                                 |
| `MYSQL_ROOT_PASSWORD`    | Strong random string (different from `DB_PASSWORD`)                  |
| `JWT_SECRET`             | 32+ byte random string (`openssl rand -base64 48`)                   |
| `CORS_ALLOWED_ORIGINS`   | Comma-separated origins, e.g. `https://yourdomain.com`               |

`GITHUB_TOKEN` is provided automatically by GitHub Actions — no setup needed.

---

## 11. Push and watch the pipeline

```bash
git add Dockerfile docker-compose.prod.yml \
        src/main/resources/application-prod.yml \
        .github/workflows/deploy.yml \
        DEPLOYMENT_VPS.md
git commit -m "Add VPS deployment pipeline"
git push origin main
```

Then go to **Repo → Actions** and watch the run. After ~2 minutes you should
see both jobs green. Verify on the VPS:

```bash
ssh -i ~/.ssh/ci-cd root@<VPS_IP>
cd /opt/backend-spring-boot
docker compose -f docker-compose.prod.yml --env-file .env ps
docker logs backend-spring-boot --tail 50
```

Open `http://<VPS_IP>:8081/swagger-ui.html` (or whatever your API root is)
to confirm the app responds.

---

## 12. Troubleshooting (collected from the video + common gotchas)

### Pipeline failures

- **`invalid tag` when pushing image** — usually means `DOCKER_USERNAME` or
  `APP_NAME` is empty. Verify the secret exists and the env var is set in
  the workflow.
- **`access denied` when pulling on VPS** — the image isn't public on Docker
  Hub, or the repo doesn't exist (Docker Hub free tier limit is 1 private
  repo). Either delete an old repo or make this one public.
- **`exec: ... not found`** — typo in the `mvnw exec:exec` command. The
  correct flag is `-Dexec.executable`, not `--exec`.
- **`Unable to access jarfile`** — the runtime stage's `CMD`/`ENTRYPOINT`
  refers to a jar name that doesn't match what was copied. Using
  `COPY --from=build /app/target/*.jar /app/app.jar` and calling `app.jar`
  avoids this entirely.
- **App boots but isn't reachable on `<VPS_IP>:8081`** — the `ports:` block
  is missing in compose. Verify it's exactly `"8081:8081"`.

### MySQL-specific

- **`Unknown database 'kaknnea'`** — happens only if you pre-created the
  schema name doesn't match `DB_NAME`. The `mysql:8.0` image creates
  `MYSQL_DATABASE` on first boot, so for fresh volumes this should never
  trigger.
- **`Schema-validation: wrong column type encountered ...`** — this is the
  bug we already hit locally. Make sure `application-prod.yml` has
  `ddl-auto: none` (Flyway owns the schema).
- **`Communications link failure` from app to db** — the JDBC URL must use
  the **service name** `db` (not `localhost`). Inside Docker Compose,
  containers reach each other by service name on the shared network.
- **`Access denied for user 'app_user'@'%'`** — usually means
  `MYSQL_USER`/`MYSQL_PASSWORD` env vars don't match what the JDBC URL
  uses. They live in the same `.env` so this should be impossible — but
  watch for stale volumes (`docker volume rm mysql_data` to wipe and
  recreate from scratch in dev).

### SSH / VPS

- **`Permission denied (publickey)`** — `VPS_SSH_KEY` secret is missing
  the BEGIN/END lines or has CRLF instead of LF. Re-paste the entire file
  including header and footer.
- **Workflow hangs at "Deploy to VPS"** — check `VPS_HOST` and `VPS_PORT`
  are correct. Some providers block port 22; if so, use a different port
  and update `VPS_PORT`.

---

## 13. After the first successful deploy — what's missing

This guide gets you to "code reaches production on push." Before going truly
live, also set up:

- **HTTPS** — install nginx + Let's Encrypt (Certbot) on the VPS, terminate
  TLS at nginx, proxy to `localhost:8081`.
- **Database backups** — either Hostinger's $6/mo daily backup or a cron job
  that `mysqldump`s and uploads to S3/Backblaze.
- **A non-root SSH user** — disable root SSH login after creating an
  unprivileged user with sudo access.
- **A firewall** — `ufw allow 22,80,443/tcp` and deny everything else.
  Optionally drop port 8081 once nginx is in front.
- **Log rotation** — Docker logs grow forever by default. Add
  `--log-opt max-size=10m --log-opt max-file=3` to compose service definitions.

---

## 14. File summary — what to add to the repo

```
backend-spring-boot/
├── Dockerfile                                   # NEW (or replace existing)
├── docker-compose.prod.yml                      # NEW
├── DEPLOYMENT_VPS.md                            # this file
├── .github/
│   └── workflows/
│       └── deploy.yml                           # NEW
└── src/main/resources/
    └── application-prod.yml                     # NEW
```

Existing files you do **not** change:
- `application.yml`, `application-local.yml`, `application-dev.yml`, `.env` —
  all stay as they are. Local development workflow is unaffected.

---

That's the whole pipeline. Once everything is green, every push to `main`
deploys automatically.
