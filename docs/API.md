# TaskMaster REST API Reference

Base URL: `http://localhost:8080`

**Auto-generated docs (recommended):**
- Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- OpenAPI JSON: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

This file is a static reference. Swagger UI stays in sync with the running app.

Auth header for protected routes:

```http
Authorization: Bearer <accessToken>
```

---

## Auth

### Register

`POST /api/auth/register`

```json
{
  "email": "alice@example.com",
  "password": "password123",
  "displayName": "Alice"
}
```

**201**

```json
{
  "accessToken": "<jwt>",
  "tokenType": "Bearer",
  "user": {
    "id": 1,
    "email": "alice@example.com",
    "displayName": "Alice",
    "bio": null,
    "createdAt": "...",
    "updatedAt": "..."
  }
}
```

### Login

`POST /api/auth/login`

```json
{
  "email": "alice@example.com",
  "password": "password123"
}
```

**200** — same shape as register response.

### Logout

`POST /api/auth/logout`  
Requires Bearer token.  
**204 No Content**

---

## Users

### Get profile

`GET /api/users/me` → **200** `UserResponse`

### Update profile

`PUT /api/users/me`

```json
{
  "displayName": "Alice Updated",
  "bio": "Backend engineer"
}
```

---

## Projects

### Create project

`POST /api/projects`

```json
{
  "name": "Website Redesign",
  "description": "Q3 launch work"
}
```

Creator becomes `OWNER`.

### List my projects

`GET /api/projects`

### Get project

`GET /api/projects/{id}`

### Invite member

`POST /api/projects/{id}/members` (owner only)

```json
{
  "email": "bob@example.com"
}
```

Invitee must already have an account.

### List members

`GET /api/projects/{id}/members`

---

## Tasks

### Create task

`POST /api/projects/{projectId}/tasks`

```json
{
  "title": "Draft wireframes",
  "description": "Mobile + desktop",
  "dueDate": "2026-08-20",
  "assigneeId": 2
}
```

`assigneeId` is optional. Defaults status: `OPEN`.

### List my assigned tasks

`GET /api/tasks/mine?sort=dueDate,asc`

### List project tasks (filter / search / sort)

`GET /api/projects/{projectId}/tasks?status=OPEN&q=wire&sort=createdAt,desc`

| Param | Description |
|-------|-------------|
| `status` | `OPEN`, `IN_PROGRESS`, `COMPLETED` |
| `q` | Search title/description |
| `sort` | `field,asc\|desc` — fields: `dueDate`, `createdAt`, `title`, `status`, `updatedAt` |

### Get / update / delete

- `GET /api/tasks/{id}`
- `PUT /api/tasks/{id}`
- `DELETE /api/tasks/{id}` → **204**

Update body:

```json
{
  "title": "Draft wireframes v2",
  "description": "Updated scope",
  "dueDate": "2026-08-22"
}
```

### Update status

`PATCH /api/tasks/{id}/status`

```json
{ "status": "COMPLETED" }
```

### Assign task

`PATCH /api/tasks/{id}/assignee`

```json
{ "assigneeId": 2 }
```

### AI-generate description

`POST /api/tasks/{id}/generate-description`

Drafts a description from the task's title and any existing notes. **Suggestion only** — the task
is not modified; call `PUT /api/tasks/{id}` with the accepted text to save it.

**200**

```json
{
  "taskId": 1,
  "title": "Draft wireframes",
  "generatedDescription": "Complete \"Draft wireframes\": outline the steps needed, note any dependencies or blockers, and define what \"done\" looks like.",
  "source": "stub"
}
```

`source` is `"stub"` (a locally templated draft, no network call) until `APP_LLM_API_KEY` is set to
a real OpenAI key, after which it's `"openai"`. **502** if the AI provider call fails once configured.

---

## Comments

### List comments

`GET /api/tasks/{taskId}/comments`

### Add comment

`POST /api/tasks/{taskId}/comments`

```json
{ "body": "Please attach the Figma link." }
```

---

## Attachments

### List attachments

`GET /api/tasks/{taskId}/attachments`

### Upload attachment

`POST /api/tasks/{taskId}/attachments`  
`Content-Type: multipart/form-data`  
form field name: `file`

### Download attachment

`GET /api/attachments/{id}/download` → file stream

---

## Error shape

```json
{
  "timestamp": "2026-08-11T06:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Title is required",
  "path": "/api/projects/1/tasks"
}
```

Common statuses: `400`, `401`, `403`, `404`, `409`, `500`.
