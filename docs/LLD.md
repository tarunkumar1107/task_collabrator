# TaskMaster — Low Level Design (LLD)

## 1. Purpose

TaskMaster is a Spring Boot REST backend for team task tracking. It supports user authentication, project membership, task lifecycle management, comments, and file attachments.

## 2. Architecture Overview

```text
Client
  │  HTTPS / JSON (multipart for files)
  ▼
Controller Layer     (validation, HTTP mapping)
  ▼
Service Layer        (authorization rules, business logic)
  ▼
Repository Layer     (Spring Data JPA)
  ▼
H2 In-Memory DB      (+ local disk for attachment binaries)
```

Cross-cutting:

- **Spring Security + JWT filter** authenticates every protected request.
- **GlobalExceptionHandler** maps domain/validation errors to consistent JSON.
- **TokenDenylist** supports logout in a stateless JWT setup.

## 3. Package Structure

| Package | Responsibility |
|---------|----------------|
| `com.taskmaster` | Boot entrypoint |
| `config` | Security filter chain, JWT/upload properties |
| `security` | JWT issue/validate/revoke, principal, filter |
| `domain` | JPA entities and enums |
| `repository` | Data access interfaces |
| `dto` | Request/response contracts |
| `service` | Use-case orchestration |
| `controller` | REST endpoints |
| `exception` | Error model and handler |

## 4. Data Model (LLD)

### 4.1 Entity relationships

```text
User 1 ──creates──▶ Project
User N ◀──ProjectMember──▶ N Project
Project 1 ──contains──▶ N Task
User 1 ──assigned──▶ N Task
Task 1 ──has──▶ N Comment
Task 1 ──has──▶ N Attachment
User 1 ──authors──▶ N Comment
```

### 4.2 Tables / fields

#### `users`
- `id` (PK)
- `email` (unique, required)
- `password_hash` (BCrypt)
- `display_name`
- `bio` (optional)
- `created_at`, `updated_at`

#### `projects`
- `id` (PK)
- `name`, `description`
- `owner_id` (FK → users)
- `created_at`, `updated_at`

#### `project_members`
- `id` (PK)
- unique(`project_id`, `user_id`)
- `role` (`OWNER` | `MEMBER`)
- `joined_at`

#### `tasks`
- `id` (PK)
- `project_id` (FK)
- `assignee_id` (FK, nullable)
- `created_by_id` (FK)
- `title`, `description`, `due_date`
- `status` (`OPEN` | `IN_PROGRESS` | `COMPLETED`)
- `created_at`, `updated_at`

#### `comments`
- `id` (PK)
- `task_id`, `author_id`
- `body`
- `created_at`

#### `attachments`
- `id` (PK)
- `task_id`, `uploaded_by_id`
- `file_name`, `content_type`, `storage_path`, `size_bytes`
- `created_at`

### 4.3 Persistence notes

- H2 URL: `jdbc:h2:mem:taskmaster;DB_CLOSE_DELAY=-1`
- Schema managed by Hibernate `ddl-auto=update`
- Attachment binaries are stored under `./uploads`; only metadata is in H2
- Process restart clears H2 data; uploaded files on disk may remain

## 5. Security LLD

### 5.1 Authentication flow

1. `POST /api/auth/register` creates user with BCrypt hash and returns JWT.
2. `POST /api/auth/login` authenticates via `AuthenticationManager`, returns JWT.
3. Client sends `Authorization: Bearer <token>` on subsequent calls.
4. `JwtAuthenticationFilter` validates signature/expiry/denylist and sets `SecurityContext`.
5. `POST /api/auth/logout` adds token `jti` to in-memory denylist until expiry.

### 5.2 JWT claims

| Claim | Meaning |
|-------|---------|
| `sub` | user email |
| `uid` | user id |
| `jti` | unique token id (for revoke) |
| `iat` / `exp` | issued/expiry timestamps |

### 5.3 Authorization rules

| Action | Rule |
|--------|------|
| View project / tasks / comments / attachments | Caller must be project member |
| Invite member | Caller must be project **owner** |
| Assign task | Assignee must already be project member |
| Create task/comment/attachment | Caller must be project member |

Membership checks are centralized in `ProjectService.requireMembership` / `requireOwner` and reused by task/collaboration services.

## 6. Component LLD by Feature

### 6.1 Auth

**Classes:** `AuthController` → `AuthService` → `UserRepository`, `PasswordEncoder`, `JwtService`, `AuthenticationManager`

**Key algorithms:**
- Register: uniqueness check → hash password → persist → issue token
- Login: authenticate credentials → load user → issue token
- Logout: parse bearer → revoke `jti`

### 6.2 User profile

**Classes:** `UserController` → `UserService` → `UserRepository`

- `GET /api/users/me` returns current profile
- `PUT /api/users/me` updates `displayName` and `bio`

### 6.3 Projects & membership

**Classes:** `ProjectController` → `ProjectService` → `ProjectRepository`, `ProjectMemberRepository`, `UserRepository`

**Create project algorithm:**
1. Load current user
2. Persist `Project` with owner
3. Persist `ProjectMember(role=OWNER)`
4. Return project DTO

**Invite algorithm:**
1. Verify caller is owner
2. Resolve invitee by email (must already exist)
3. Reject if already a member
4. Persist `ProjectMember(role=MEMBER)`

### 6.4 Tasks

**Classes:** `TaskController` → `TaskService` → `TaskRepository`, `ProjectService`, `ProjectMemberRepository`

**Supported operations:**
- Create under project
- List mine (`assignee = current user`)
- List in project with `status`, `q`, `sort`
- Get / update / delete
- Patch status
- Patch assignee

**Search/filter query (`TaskRepository.searchInProject`):**
- Optional status equality
- Optional case-insensitive `LIKE` on title/description
- Sort whitelist: `dueDate`, `createdAt`, `title`, `status`, `updatedAt`

### 6.5 Comments

**Classes:** `CommentController` → `CommentService` → `CommentRepository`, `TaskService`

1. Ensure task is accessible via membership
2. Persist comment with author = current user
3. List ordered by `createdAt ASC`

### 6.6 Attachments

**Classes:** `AttachmentController` → `AttachmentService` → `AttachmentRepository`, filesystem

**Upload algorithm:**
1. Validate non-empty multipart file
2. Ensure task access
3. Create `uploads/` directory if missing
4. Store file as `{uuid}_{sanitizedName}`
5. Persist metadata row

**Download algorithm:**
1. Load attachment metadata
2. Ensure task access
3. Stream file with `Content-Disposition: attachment`

## 7. API Contract Summary

See [API.md](API.md) for full request/response shapes.

Base URL: `http://localhost:8080`

Public:
- `POST /api/auth/register`
- `POST /api/auth/login`

Protected (Bearer JWT):
- Auth logout, user profile
- Projects + members
- Tasks CRUD/status/assignee/search
- Comments + attachments

## 8. Error Handling LLD

`GlobalExceptionHandler` returns:

```json
{
  "timestamp": "...",
  "status": 400,
  "error": "Bad Request",
  "message": "Title is required",
  "path": "/api/projects/1/tasks"
}
```

| Exception | Status |
|-----------|--------|
| validation errors | 400 |
| bad credentials | 401 |
| `ApiException` | status carried by exception |
| access denied | 403 |
| unhandled | 500 |

## 9. Sequence Diagrams

### 9.1 Create task

```text
Client -> TaskController: POST /api/projects/{id}/tasks + JWT
TaskController -> TaskService: createTask(principal, projectId, req)
TaskService -> ProjectService: requireMembership(...)
ProjectService -> ProjectMemberRepository: exists(...)
TaskService -> TaskRepository: save(task)
TaskService --> Client: TaskResponse 201
```

### 9.2 Logout

```text
Client -> AuthController: POST /api/auth/logout + JWT
AuthController -> AuthService: logout(bearer)
AuthService -> JwtService: revokeToken(token)
JwtService -> TokenDenylist: revoke(jti, exp)
AuthController --> Client: 204
```

## 10. Testing LLD

Unit tests use **JUnit 5 + Mockito** (no Spring context) for:

- `AuthServiceTest`
- `UserServiceTest`
- `ProjectServiceTest`
- `TaskServiceTest`
- `CommentServiceTest`
- `JwtServiceTest` (real JWT crypto + denylist)

Repositories and web layer are exercised via manual smoke curls / running app.

## 11. Configuration LLD

| Key | Purpose |
|-----|---------|
| `spring.datasource.url` | H2 in-memory DB |
| `app.jwt.secret` | HMAC signing key |
| `app.jwt.expiration-ms` | Token TTL (default 24h) |
| `app.upload-dir` | Attachment directory |

## 12. Extension points

- Swap H2 for PostgreSQL by changing datasource config (entities remain portable)
- Replace disk storage with S3-compatible object storage in `AttachmentService`
- Add WebSocket/SSE notifications on task/comment events
- Replace invite-by-email with email token invitations
