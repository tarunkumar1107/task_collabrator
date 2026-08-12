# TaskMaster

Spring Boot backend for collaborative task tracking — users, projects, assignments, comments, and attachments — with JWT auth and an H2 in-memory database.

## Features

- Secure registration / login / logout (BCrypt + JWT)
- User profile view and update
- Create projects and invite members by email
- Task CRUD, assignment, status updates
- Filter / search / sort tasks
- Task comments and file attachments
- Consistent REST validation and error responses

## Tech stack

| Layer | Choice |
|-------|--------|
| Language | Java 21 |
| Framework | Spring Boot 3.3 |
| Security | Spring Security + JJWT |
| Persistence | Spring Data JPA + H2 (in-memory) |
| Build | Maven |
| Tests | JUnit 5 + Mockito |
| API docs | SpringDoc OpenAPI 3 + Swagger UI |

## Quick start

### Prerequisites

- Java **21** (required — Java 25 breaks Mockito in this setup)
- Maven 3.9+

**Remember for this repo:** use Java 21. Helpers checked into the project:

| File | Purpose |
|------|---------|
| `.java-version` | Marks Java 21 (jenv / asdf) |
| `.sdkmanrc` | SDKMAN auto-switch (`sdk env`) |
| `.vscode/settings.json` | Cursor/VS Code terminal + language server use Java 21 |
| `.idea/misc.xml` + `compiler.xml` | IntelliJ language level / bytecode target = 21 |
| `Dockerfile` + `docker-compose.yml` | Run with Java 21 inside the container (no local JDK switch) |
| `./mvn21` | Run Maven with Java 21 without exporting anything |
| `pom.xml` → `java.version` | Source of truth for Maven / IDE sync |

```bash
chmod +x mvn21   # once
./mvn21 test
./mvn21 spring-boot:run
```

Or manually:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

### IntelliJ IDEA

You should **not** need to hand-edit Project Structure every time if you open it as a Maven project:

1. **File → Open** the repo root (folder with `pom.xml`), trust it as a Maven project.
2. Register JDK 21 once in IntelliJ: **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → JRE** and/or **Project Structure → SDKs** → add  
   `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`  
   Name it **`21`** (matches `.idea/misc.xml`).
3. Click the Maven tool window → **Reload All Maven Projects**.  
   IntelliJ reads `<java.version>21</java.version>` from `pom.xml` and sets language level to 21.
4. Confirm: **File → Project Structure → Project**
   - SDK: `21`
   - Language level: `21`

If IntelliJ still shows Java 25, set **Maven Runner JRE** to 21 (that controls `mvn test` / run configs from the IDE).

Run/Debug: use the Spring Boot run config or Maven `spring-boot:run` with JRE 21.
### Run

```bash
./mvn21 spring-boot:run
# or: mvn spring-boot:run   (only if JAVA_HOME is already 21)
```

API base: [http://localhost:8080](http://localhost:8080)  
H2 console: [http://localhost:8080/h2-console](http://localhost:8080/h2-console)

H2 connection settings:

- JDBC URL: `jdbc:h2:mem:taskmaster`
- User: `sa`
- Password: _(empty)_

### Test

```bash
./mvn21 test
```

### Docker (recommended — no local Java switch)

Java **21 is baked into the image**, so Docker works the same on any machine.

```bash
# build + run
docker compose up --build

# or plain Docker
docker build -t taskmaster:1.0.0 .
docker run --rm -p 8080:8080 \
  -e APP_JWT_SECRET='TaskMasterDockerSecretKeyThatIsLongEnoughForHS256' \
  -v taskmaster_uploads:/app/uploads \
  taskmaster:1.0.0
```

API: [http://localhost:8080](http://localhost:8080)

Stop:

```bash
docker compose down
```

Useful env vars (also in `docker-compose.yml`):

| Variable | Default / purpose |
|----------|-------------------|
| `SERVER_PORT` | `8080` |
| `APP_JWT_SECRET` | JWT signing secret (set your own outside local use) |
| `APP_JWT_EXPIRATION_MS` | `86400000` (24h) |
| `APP_UPLOAD_DIR` | `/app/uploads` in the container |
| `JAVA_OPTS` | JVM flags (Compose sets container RAM %) |

## OpenAPI / Swagger UI

API docs are **auto-generated** from controllers at runtime (SpringDoc OpenAPI).

| URL | Description |
|-----|-------------|
| [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | Interactive Swagger UI — try endpoints in the browser |
| [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs) | Raw OpenAPI 3 JSON spec |

**Try it:**
1. Start the app (`./mvn21 spring-boot:run` or `docker compose up`)
2. Open Swagger UI
3. Call `POST /api/auth/register` or `POST /api/auth/login`
4. Click **Authorize**, enter `Bearer <accessToken>`
5. Call protected endpoints from the UI

Descriptions come from `@Tag`, `@Operation`, `@Schema`, and validation annotations. Task request/response bodies include **auto-generated field descriptions and example JSON** in Swagger UI.

Full walkthrough: [docs/SWAGGER.md](docs/SWAGGER.md)  
Config: [`OpenApiConfig.java`](src/main/java/com/taskmaster/config/OpenApiConfig.java), [`TaskDtos.java`](src/main/java/com/taskmaster/dto/TaskDtos.java)

## Smoke test (curl)

```bash
# 1) Register
curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123","displayName":"Alice"}'

# 2) Save token from response, then create a project
TOKEN=<paste-accessToken-here>

curl -s -X POST http://localhost:8080/api/projects \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Website Redesign","description":"Q3 work"}'

# 3) Create a task in project 1
curl -s -X POST http://localhost:8080/api/projects/1/tasks \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Draft wireframes","description":"Mobile + desktop","dueDate":"2026-08-20"}'

# 4) Complete the task
curl -s -X PATCH http://localhost:8080/api/tasks/1/status \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"status":"COMPLETED"}'

# 5) Comment
curl -s -X POST http://localhost:8080/api/tasks/1/comments \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"body":"Looks good to ship"}'

# 6) Logout
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer $TOKEN"
```

## Project structure

```text
src/main/java/com/taskmaster/
  config/        Security, OpenAPI, app properties
  controller/    REST endpoints
  domain/        JPA entities + enums
  dto/           Request/response records
  exception/     API errors + handler
  repository/    Spring Data repositories
  security/      JWT filter/service/principal
  service/       Business logic
docs/
  LLD.md         Low-level design
  API.md         Endpoint reference
```

## Domain model

```text
User ──creates──▶ Project
User N ◀──ProjectMember──▶ N Project
Project 1 ──▶ N Task ──▶ N Comment
                 └────▶ N Attachment
User (assignee) ──▶ Task
```

## API map

| Area | Endpoints |
|------|-----------|
| Auth | `POST /api/auth/register`, `/login`, `/logout` |
| Users | `GET/PUT /api/users/me` |
| Projects | `POST/GET /api/projects`, `GET /api/projects/{id}`, members invite/list |
| Tasks | project tasks, `/api/tasks/mine`, CRUD, status, assignee |
| Collab | `/api/tasks/{id}/comments`, `/attachments`, download |

Full request/response docs: [docs/API.md](docs/API.md)  
Interactive docs: Swagger UI at `/swagger-ui.html`  
Low-level design: [docs/LLD.md](docs/LLD.md)  
Module notes: [docs/MODULES.md](docs/MODULES.md)

## Configuration

`src/main/resources/application.yml`

| Property | Default | Notes |
|----------|---------|-------|
| `server.port` | `8080` | HTTP port |
| `spring.datasource.url` | H2 mem `taskmaster` | In-memory; cleared on restart |
| `app.jwt.secret` | dev secret | Change for any shared/deployed env |
| `app.jwt.expiration-ms` | `86400000` | 24 hours |
| `app.upload-dir` | `uploads` | Local attachment storage |

## Design decisions

- **Stateless JWT** for easy API clients; logout uses an in-memory token denylist keyed by `jti`.
- **H2 in-memory** for zero-setup local development; JPA mappings stay portable for PostgreSQL/MySQL later.
- **Membership-gated collaboration** — only project members can touch tasks/comments/attachments.
- **Invite by existing email** — no outbound email service in this version.
- **Files on disk, metadata in DB** — keeps the H2 schema simple and uploads inspectable.

## User stories covered

- Register, login, logout
- View/update profile
- Create tasks with title/description/due date
- View assigned tasks
- Mark tasks completed
- Assign tasks to teammates
- Filter by status and search by title/description
- Comment and attach files on tasks
- Create projects and invite members

## License

MIT
