# Swagger UI — Register & Use Task APIs

Step-by-step guide for TaskMaster OpenAPI docs.

## 1. Start the app

```bash
./mvn21 spring-boot:run
# or
docker compose up --build
```

Open: **http://localhost:8080/swagger-ui.html**

---

## 2. Register

1. Expand **Authentication**
2. Open **`POST /api/auth/register`**
3. Click **Try it out**
4. Use the pre-filled example or paste:

```json
{
  "email": "alice@example.com",
  "password": "password123",
  "displayName": "Alice"
}
```

5. Click **Execute**
6. Copy **`accessToken`** from the response

---

## 3. Authorize

1. Click **Authorize** (top right)
2. Enter: `Bearer <paste-accessToken-here>`
3. Click **Authorize** → **Close**

All protected endpoints now send your JWT.

---

## 4. Create a project (required before tasks)

1. **Projects** → **`POST /api/projects`**
2. Example body:

```json
{
  "name": "Website Redesign",
  "description": "Q3 launch work"
}
```

3. Note the returned **`id`** (usually `1` on a fresh database)

---

## 5. Create a task (auto-generated description in Swagger)

1. **Tasks** → **`POST /api/projects/{projectId}/tasks`**
2. Set `projectId` to your project id
3. Click **Try it out** — Swagger pre-fills the request from OpenAPI `@Schema` examples:

```json
{
  "title": "Draft wireframes",
  "description": "Create mobile and desktop wireframes for the homepage redesign",
  "dueDate": "2026-08-20",
  "assigneeId": 2
}
```

4. Edit **`description`** as needed (up to 5000 characters)
5. Click **Execute**

The **Schemas** panel under the endpoint shows auto-generated field descriptions for `title`, `description`, `dueDate`, and `assigneeId`.

---

## 6. Other task operations

| Action | Endpoint | Notes |
|--------|----------|-------|
| List my tasks | `GET /api/tasks/mine` | Tasks assigned to you |
| Search/filter | `GET /api/projects/{id}/tasks?status=OPEN&q=wire&sort=dueDate,asc` | Query params documented in Swagger |
| Update description | `PUT /api/tasks/{id}` | Edit `description` in example body |
| Mark complete | `PATCH /api/tasks/{id}/status` | `{ "status": "COMPLETED" }` |
| Assign | `PATCH /api/tasks/{id}/assignee` | `{ "assigneeId": 2 }` |
| Comment | `POST /api/tasks/{id}/comments` | Collaboration on a task |
| Attach file | `POST /api/tasks/{id}/attachments` | Multipart `file` field |

---

## 7. View raw OpenAPI spec

**http://localhost:8080/v3/api-docs**

Task schemas (`CreateTaskRequest`, `TaskResponse`, `TaskStatus`) include descriptions and examples generated from `@Schema` annotations in code.

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| 401 Unauthorized | Re-authorize with a fresh token from login/register |
| 403 Forbidden | You are not a project member |
| Empty task list | Create a project and task first |
| Example body missing | Expand **Schemas** → `CreateTaskRequest` on the create-task endpoint |
