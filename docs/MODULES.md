# TaskMaster — Module LLD Notes

Implementation notes for each major module.

## Auth module

| Item | Detail |
|------|--------|
| Entry | `AuthController` |
| Service | `AuthService` |
| Security deps | `JwtService`, `TokenDenylist`, `PasswordEncoder`, `AuthenticationManager` |
| Persistence | `UserRepository` |

**Invariants**
- Emails stored lowercased
- Passwords never returned in DTOs
- Duplicate email → HTTP 409
- Logout requires `Bearer` prefix

## User module

| Item | Detail |
|------|--------|
| Entry | `UserController` |
| Service | `UserService` |

**Invariants**
- Profile operations always target the authenticated principal (`/me`)
- No admin/user-impersonation APIs in this version

## Project module

| Item | Detail |
|------|--------|
| Entry | `ProjectController` |
| Service | `ProjectService` |
| Persistence | `ProjectRepository`, `ProjectMemberRepository` |

**Invariants**
- Creating a project always inserts an `OWNER` membership row
- Invite is owner-only
- Invitee must already exist
- Membership uniqueness enforced by DB constraint + service check

## Task module

| Item | Detail |
|------|--------|
| Entry | `TaskController` |
| Service | `TaskService` |
| Persistence | `TaskRepository` |

**Invariants**
- Tasks always belong to a project
- Default status is `OPEN`
- Assignee must be a project member
- Sort fields are allow-listed to avoid injection of arbitrary property paths

## Collaboration module

| Item | Detail |
|------|--------|
| Entry | `CommentController`, `AttachmentController` |
| Services | `CommentService`, `AttachmentService` |

**Invariants**
- Access always re-checked through `TaskService.requireAccessibleTask`
- Attachment filenames sanitized before disk write
- Missing disk file after metadata lookup → 404

## Security module

| Class | Role |
|-------|------|
| `SecurityConfig` | Stateless filter chain, public auth + H2 console |
| `JwtAuthenticationFilter` | Bearer parsing + context population |
| `JwtService` | Issue / parse / validate / revoke |
| `TokenDenylist` | In-memory revoked `jti` set with expiry cleanup |
| `UserPrincipal` | `UserDetails` adapter carrying user id |
| `SecurityUtils` | Resolve current principal in controllers |

## Cross-cutting

| Class | Role |
|-------|------|
| `GlobalExceptionHandler` | Uniform error JSON |
| `ApiException` | Domain/HTTP status carrier |
| DTO records | Validation annotations at the boundary |
