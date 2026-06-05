# Multi-Agent Intelligent Platform — Design Spec

**Date:** 2026-05-25
**Status:** Approved
**Scope:** Desktop-first web SPA (Vue3 + Spring Boot), multi-theme UI

## 1. Overview

Build a web platform for the "超级程序员Agent" (Super Programmer Agent) system. The platform displays 14 sub-Agents with 51 workflows, lets users browse Agent capabilities, and provides a visual drag-and-drop workflow editor for composing custom workflows from Agent skills.

### Goals

- Display all 14 Agents with their skills in a visual card-based interface
- Allow users to drill into Agent details and view individual skill workflows
- Provide a drag-and-drop workflow canvas for composing multi-skill workflows
- Support user authentication with JWT + RBAC permission control
- Multi-theme system (3 dark themes at launch, light theme reserved)

### Out of Scope (Future)

- LLM execution engine integration
- Mobile app
- Desktop packaging (Electron/Tauri)
- Light theme implementation
- OAuth2 third-party login

## 2. Architecture

**Pattern:** Modular monolith — 4 domain modules in one Spring Boot application.

```
┌─────────────────────────────────────────────────────────┐
│                    Vue3 Frontend (SPA)                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐  │
│  │ Login    │ │ Agent    │ │ Workflow │ │ Execution  │  │
│  │ /Auth    │ │ Hall     │ │ Editor   │ │ Monitor    │  │
│  └─────┬────┘ └─────┬────┘ └─────┬────┘ └─────┬─────┘  │
└────────┼────────────┼────────────┼────────────┼─────────┘
         │            │            │            │
    HTTP REST API (JWT Auth)
         │            │            │            │
┌────────┴────────────┴────────────┴────────────┴─────────┐
│              Spring Boot Modular Backend                  │
│  ┌─────────────┐  ┌─────────────┐  ┌────────────────┐  │
│  │ auth         │  │ agent       │  │ workflow       │  │
│  │ • Register   │  │ • Agent CRUD│  │ • Workflow CRUD│  │
│  │ • Login/JWT  │  │ • Markdown  │  │ • Node编排     │  │
│  │ • RBAC       │  │   解析引擎  │  │ • 模板管理     │  │
│  │ • User CRUD  │  │ • Search    │  │ • Import/Export│  │
│  └──────┬──────┘  └──────┬──────┘  └───────┬────────┘  │
│  ┌──────┴────────────────┴──────────────────┴────────┐  │
│  │ execution (reserved)                               │  │
│  │ • Execution engine interface                       │  │
│  │ • Execution log recording                          │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────┬───────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          │                │                │
   ┌──────┴──────┐  ┌──────┴──────┐  ┌──────┴──────┐
   │ PostgreSQL  │  │ Redis       │  │ File System │
   │ Users/Roles │  │ JWT黑名单   │  │ Agent       │
   │ Workflows   │  │ Session     │  │ Markdown    │
   │ Exec Logs   │  │ Hot data    │  │ Files       │
   └─────────────┘  └─────────────┘  └─────────────┘
```

### Module Responsibilities

| Module | Responsibility | Package |
|--------|---------------|---------|
| auth | User registration, JWT sign/refresh, RBAC roles/permissions, user CRUD | `com.superprogrammer.auth` |
| agent | Parse Markdown to Agent/skill tree, Agent CRUD, search/filter, permission filtering | `com.superprogrammer.agent` |
| workflow | Workflow CRUD, node orchestration (sequential/parallel/conditional), template management, version control | `com.superprogrammer.workflow` |
| execution | Execution engine interface, task logs, status tracking (reserved for LLM integration) | `com.superprogrammer.execution` |

## 3. Data Model

**ORM:** MyBatis-Plus with logical delete, auto-fill, optimistic locking.

**Database:** PostgreSQL.

### Common Fields (all business tables)

```sql
created_by    BIGINT        -- Auto-filled on insert
created_at    TIMESTAMP     -- Auto-filled on insert
updated_by    BIGINT        -- Auto-filled on update
updated_at    TIMESTAMP     -- Auto-filled on update
deleted       TINYINT       -- Logical delete: 0=active, 1=deleted (@TableLogic)
version       INT           -- Optimistic lock (@Version)
```

### Core Tables

**ER Relationships:**

```
users ──→ user_roles ──→ roles ──→ role_permissions ──→ permissions

agent_groups ──→ agents ──→ skills ──→ skill_steps

users ──→ workflows ──→ workflow_nodes (ref skill_id)
                     ──→ workflow_edges

workflows ──→ execution_logs ←── users
```

**agents table (example — all tables include common fields):**

```sql
CREATE TABLE agents (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id        BIGINT NOT NULL REFERENCES agent_groups(id),
    name            VARCHAR(100) NOT NULL,       -- e.g. "编程语言与基础开发Agent"
    display_name    VARCHAR(100),
    domain_key      VARCHAR(50) NOT NULL,        -- e.g. "programming_language"
    description     TEXT,
    agent_path      VARCHAR(500),                -- Markdown directory path
    icon            VARCHAR(200),
    color           VARCHAR(20),                 -- Theme color
    sort_order      INT DEFAULT 0,
    -- Common fields
    created_by      BIGINT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT DEFAULT 0,
    version         INT DEFAULT 0
);
```

**Tables:** users, roles, permissions, user_roles, role_permissions, agent_groups, agents, skills, skill_steps, workflows, workflow_nodes, workflow_edges, execution_logs — all 13 tables include the 6 common fields.

### Key Design Decisions

- **Agent data source:** Markdown files are the single source of truth. Parsed into database on startup or manual trigger. Avoids runtime file reads.
- **Workflow model:** Node + Edge pattern. workflow_nodes reference skill_id, workflow_edges store connections. Supports sequential, parallel, and conditional branching. position_x/y for canvas positioning.
- **RBAC:** Five-table model (users ↔ roles ↔ permissions). permissions.resource_type distinguishes "agent" (Agent access control) and "system" (system feature control).
- **Execution logs:** Reserved table. Records workflow execution status and results. Node-level logging added when LLM is integrated.

### MyBatis-Plus Configuration

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
      id-type: auto
  configuration:
    map-underscore-to-camel-case: true
  plugin:
    pagination: true
    optimistic-locker: true
```

## 4. Frontend Design

### Tech Stack

- **Framework:** Vue3 + TypeScript + Vite + Pinia
- **UI Library:** Naive UI (dark theme support)
- **Canvas Engine:** Vue Flow (node-edge drag-and-drop)
- **Theme System:** CSS Variables + Naive UI dark theme tokens

### Pages

#### 4.1 Login Page

- Full-screen dark background with particle animation
- Centered login card with glassmorphism (backdrop-filter: blur)
- Glowing card border (box-shadow + accent-glow)
- Gradient login button with hover glow expansion
- Theme switcher icon in top-right corner
- OAuth2 third-party login buttons (reserved, UI present)

#### 4.2 Agent Hall (Main Dashboard)

- **Left sidebar:** Fixed narrow bar with icons + text (n8n style). Shows: Home, Agents, Workflows, Execution, Settings. Agent groups listed below for quick filtering.
- **Main content:** 4-column card grid (Coze style). Each Agent card has:
  - Top gradient color bar (each Agent has a unique theme color)
  - Agent name + description
  - Skill count badge + top 3 skills preview
  - Hover: card floats up + border glow
- **Top bar:** Search input, group filter dropdown, theme switcher, user avatar

#### 4.3 Agent Detail Page

- **Header:** Gradient color bar (Coze) + icon + name + description + stat badges (skill count, workflow count, popularity, difficulty)
- **Two-column layout:**
  - Left: Skill list (clickable items)
  - Right: Selected skill detail — purpose description, workflow steps (vertical timeline with numbered steps and connecting lines), action buttons ("View full workflow", "Open in editor")
- **Action:** "Add to workflow" button → opens workflow selector modal
- **Breadcrumb:** Agent Hall > Agent Name > Skill Name

#### 4.4 Workflow Editor

- **Three-column layout:**
  - Left panel: Component palette — searchable skill list grouped by Agent, plus flow control nodes (Start, End, Condition)
  - Center canvas: Dark grid background (n8n style), zoom/pan support
  - Right panel: Property editor for selected node — configuration fields, condition editor, delete button
- **Nodes:** Rounded cards with Agent theme color icons. Types: Start (circle), Skill (rounded rect), Condition (diamond), End (circle)
- **Edges:** Bezier curves with animated flow effect
- **Interactions:** Drag from palette to canvas, right-click context menu (delete/copy/edit condition), bottom toolbar (zoom, undo/redo, minimap, export)
- **Save/Run:** Top toolbar with Save and Run buttons (Run triggers execution log recording)

### Theme System

**3 dark themes at launch, light theme reserved:**

| Theme | Background | Accent | Key Effect |
|-------|-----------|--------|------------|
| Deep Space (Botpress) | Deep blue (#0a0e1a) | Blue-purple (#6366f1) | Glassmorphism + glow borders |
| Dark Pro (Dify/n8n) | Dark gray (#0f1117) | Purple-blue gradient | Clean cards + orange secondary |
| Cyber Glow | Pure black (#000000) | Neon multi-color | Glowing edges + animated effects |

**Implementation:**
- CSS custom properties (`--bg-primary`, `--accent`, etc.) on `:root[data-theme="..."]`
- Vue3 reactive theme state via Pinia store
- `localStorage` persistence
- Reserved: user preference API to sync across devices

## 5. API Design

RESTful endpoints grouped by module:

### Auth Module
- `POST /api/auth/register` — User registration
- `POST /api/auth/login` — Login, returns JWT access + refresh tokens
- `POST /api/auth/refresh` — Refresh access token
- `POST /api/auth/logout` — Logout (blacklist token)
- `GET /api/users` — List users (admin)
- `GET /api/users/{id}` — Get user detail
- `PUT /api/users/{id}` — Update user
- `DELETE /api/users/{id}` — Delete user (logical)
- `GET /api/roles` — List roles
- `POST /api/roles` — Create role
- `PUT /api/roles/{id}` — Update role
- `GET /api/permissions` — List permissions

### Agent Module
- `GET /api/agent-groups` — List agent groups
- `GET /api/agents` — List agents (with group filter, search)
- `GET /api/agents/{id}` — Get agent detail with skills
- `GET /api/agents/{id}/skills` — List skills for an agent
- `GET /api/skills/{id}` — Get skill detail with steps
- `POST /api/agents/sync` — Trigger Markdown → DB sync

### Workflow Module
- `GET /api/workflows` — List user's workflows
- `POST /api/workflows` — Create workflow
- `GET /api/workflows/{id}` — Get workflow with nodes and edges
- `PUT /api/workflows/{id}` — Update workflow
- `DELETE /api/workflows/{id}` — Delete workflow (logical)
- `POST /api/workflows/{id}/duplicate` — Duplicate workflow
- `GET /api/workflow-templates` — List templates
- `POST /api/workflows/import` — Import workflow JSON
- `GET /api/workflows/{id}/export` — Export workflow JSON

### Execution Module (Reserved)
- `POST /api/executions` — Start workflow execution
- `GET /api/executions/{id}` — Get execution status
- `GET /api/executions` — List execution logs

## 6. Security

- **Authentication:** JWT access token (15min TTL) + refresh token (7d TTL)
- **Token storage:** Access token in memory (Pinia), refresh token in httpOnly cookie
- **JWT blacklist:** Redis SET, checked on every request
- **RBAC:** Custom annotation `@RequirePermission("agent:programming_language:read")` on endpoints
- **Agent-level access:** permissions table with resource_type="agent" controls which Agents a user can see
- **Input validation:** Spring Validation annotations on all request DTOs
- **SQL injection:** MyBatis-Plus parameterized queries
- **XSS:** Vue3 auto-escaping + CSP headers
- **CORS:** Configured for frontend origin only
- **Rate limiting:** Spring Boot rate limiter on auth endpoints

## 7. Agent Data Sync

The Agent hierarchy lives in Markdown files. Path configured via `agent.data-path` in application.yml (dev: `C:\Users\Administrator\.openclaw\workspace\all_agents\`).

**Sync flow:**
1. On startup (or manual trigger via `/api/agents/sync`)
2. Read top-level `skills-router.md` → parse 14 sub-agent entries
3. For each sub-agent, read its `skills-router.md` → parse skill entries
4. For each skill, read its workflow file → parse steps
5. Upsert into agent_groups, agents, skills, skill_steps tables (match by domain_key/skill_name)
6. Fields that exist in DB but not in Markdown are preserved (user customization)

## 8. Non-Functional Requirements

- **Performance:** Agent list page loads < 2s, workflow canvas renders 50+ nodes smoothly
- **Scalability:** Modular monolith supports future extraction to microservices
- **Theme switching:** Instant (CSS variable swap), no page reload
- **Browser support:** Chrome 90+, Firefox 90+, Edge 90+
- **Responsive:** Desktop-first (1280px+). Tablet/mobile adaptations deferred to mobile app phase.
