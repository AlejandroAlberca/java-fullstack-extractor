# java-angular-fullstack-extractor — User Manual

**Version 1.0.0**

---

## What is it?

`java-angular-fullstack-extractor` is a static analysis tool that scans a **Java backend** and an **Angular frontend** side by side and produces a structured set of **Markdown documents** summarising the full-stack application.

The generated documents are designed to be fed directly to an **AI assistant** (Claude, GPT, Gemini, etc.) as a compact reference that gives the model an accurate picture of the application's API surface, data shapes, call flows, and persistence layer — without having to upload thousands of source files.

### Why does this matter for AI-assisted development?

Large codebases exceed the context window of any AI model. Even when they don't, dumping raw source code is noisy and expensive. This tool distils what the AI actually needs:

- Which HTTP endpoints exist and what data they exchange
- How TypeScript models on the frontend map to Java DTOs on the backend
- How DTOs map to database entities (JPA) and which queries are declared
- Which background jobs run and what they call internally
- Visual flowcharts, sequence diagrams, and class diagrams of every API interaction
- What is unmatched, ambiguous, or low-confidence so the AI can reason about gaps

The result is a **living spec** that can be regenerated at any time and attached to any AI conversation as authoritative context.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 21 or later |
| Maven | 3.9+ (build only) |
| Node.js | 18+ (optional, improves TypeScript parsing accuracy) |

Node.js is **not required**. If it is present on the PATH the tool uses it for a more accurate TypeScript analysis (Strategy A). If absent it falls back to a built-in JVM ANTLR parser (Strategy B).

---

## Building

```bash
mvn package -q
```

This produces `target/java-angular-fullstack-extractor-1.0.0.jar` (a self-contained fat JAR).

---

## Usage

```
java -jar java-angular-fullstack-extractor.jar
    <java-project-path>
    <angular-project-path>
    [output-file]
    [--output-dir <dir>]
    [--index-detail-threshold <n>]
    [--url-prefix-map key=value] ...
    [--flow-diagrams-dir <dir>]
    [--sequence-diagrams-dir <dir>]
    [--class-diagrams-dir <dir>]
    [--db-url <jdbc-url>]
    [--db-type postgresql|mysql|oracle]
    [--db-user <user>]
    [--db-password <password>]
    [--db-profile <profile>]
    [--skip-db-connection]
```

### Parameters

| Parameter | Required | Description |
|---|---|---|
| `java-project-path` | Yes | Root directory of the Java project (must be readable) |
| `angular-project-path` | Yes | Root directory of the Angular project |
| `output-file` | No | Name (or path) of the main Markdown file to generate, written under `full_specs/`. Without `--output-dir`, its directory component (or the current directory, if none given) becomes the base directory. Default: `full-stack-spec.md` |
| `--output-dir <dir>` | No | Base directory for **all** generated output. `full_specs/`, `indexed_specs/`, and `index_specs.md` are created under it automatically, created if absent, regardless of where `output-file` points |
| `--index-detail-threshold <n>` | No | Minimum element count (tables, persistence mappings) a category needs before it gets its own per-element index + detail documents under `indexed_specs/`. Below it, `index_specs.md` links straight to the `full_specs/` document instead. Default: `3` |
| `--url-prefix-map key=value` | No, repeatable | Explicit mapping for dynamic Angular URL prefixes that cannot be resolved automatically (see [Resolving dynamic URLs](#resolving-dynamic-urls)) |
| `--flow-diagrams-dir <dir>` | No | Directory where PNG flowchart diagrams are written. The directory is created if absent. If omitted, no PNGs are generated |
| `--sequence-diagrams-dir <dir>` | No | Directory where PNG sequence diagrams are written |
| `--class-diagrams-dir <dir>` | No | Directory where PNG class diagrams are written |
| `--db-url <jdbc-url>` | No | JDBC URL to connect to the database. Examples: `jdbc:postgresql://localhost:5432/myapp`, `jdbc:mysql://localhost:3306/myapp`, `jdbc:oracle:thin:@localhost:1521:myapp`. Overrides `spring.datasource.url` from config files. If omitted, configuration is read from `application.yml`/`application.properties` |
| `--db-type postgresql\|mysql\|oracle` | No | Database type for connection. Auto-detected from the JDBC URL pattern if omitted. Useful to override if URL format is non-standard |
| `--db-user <user>` | No | Database username. Overrides `spring.datasource.username` from config. If omitted, read from configuration files |
| `--db-password <password>` | No | Database password. Overrides `spring.datasource.password` from config. If omitted, read from configuration files or may be empty |
| `--db-profile <profile>` | No | Spring profile name (e.g., `prod`, `dev`, `staging`). Causes `application-{profile}.yml` or `application-{profile}.properties` to be loaded alongside `application.yml`. Allows different datasource configs per environment |
| `--skip-db-connection` | No | If set, skips all live database connection attempts entirely. Schema extraction uses only SQL migrations + JPA entities. Useful when the database is not reachable or when you want static analysis only |

### Examples

**Centralized output directory:**
```bash
java -jar java-angular-fullstack-extractor.jar \
  /workspace/my-app/backend \
  /workspace/my-app/frontend \
  api-spec.md \
  --output-dir /workspace/reports/my-app
```
Writes `/workspace/reports/my-app/index_specs.md`, `full_specs/*.md`, and `indexed_specs/*` — independent of where the backend/frontend projects themselves live.

**Minimal — output in current directory, DB config from application.yml:**
```bash
java -jar java-angular-fullstack-extractor.jar \
  /workspace/my-app/backend \
  /workspace/my-app/frontend
```

**Custom output path, live database connection (PostgreSQL):**
```bash
java -jar java-angular-fullstack-extractor.jar \
  /workspace/my-app/backend \
  /workspace/my-app/frontend \
  /workspace/my-app/docs/api-spec.md \
  --db-url "jdbc:postgresql://localhost:5432/myapp" \
  --db-user appuser \
  --db-password secret
```

**With production database and profile-specific config:**
```bash
java -jar java-angular-fullstack-extractor.jar \
  /workspace/my-app/backend \
  /workspace/my-app/frontend \
  /workspace/my-app/docs/api-spec.md \
  --db-profile prod \
  --db-url "jdbc:mysql://prod-db.internal:3306/myapp" \
  --db-type mysql \
  --db-user dbuser \
  --db-password \${DB_PASSWORD}
```

**Static analysis only (no live database, SQL migrations + JPA only):**
```bash
java -jar java-angular-fullstack-extractor.jar \
  /workspace/my-app/backend \
  /workspace/my-app/frontend \
  /workspace/my-app/docs/api-spec.md \
  --skip-db-connection
```

**With dynamic URL resolution:**
```bash
java -jar java-angular-fullstack-extractor.jar \
  /workspace/my-app/backend \
  /workspace/my-app/frontend \
  /workspace/my-app/docs/api-spec.md \
  --url-prefix-map "applicationConfigService.getEndpointFor(API_ROOT_V5)=/api/v5/"
```

**Complete example: live DB, all PNG diagrams, dynamic URLs:**
```bash
java -jar java-angular-fullstack-extractor.jar \
  /workspace/my-app/backend \
  /workspace/my-app/frontend \
  /workspace/my-app/docs/api-spec.md \
  --db-url "jdbc:postgresql://localhost:5432/myapp" \
  --db-user appuser \
  --db-password secret \
  --url-prefix-map "configService.getEndpointFor(API_V5)=/api/v5/" \
  --flow-diagrams-dir /workspace/my-app/docs/diagrams/flows \
  --sequence-diagrams-dir /workspace/my-app/docs/diagrams/sequences \
  --class-diagrams-dir /workspace/my-app/docs/diagrams/classes
```

---

## Output files

Every run writes all documents under one base directory, split into `full_specs/` (complete, monolithic documents) and `indexed_specs/` (per-element index + detail documents), with `index_specs.md` as the single entry point:

```
<base-dir>/
├── index_specs.md
├── full_specs/
│   ├── api-spec.md
│   ├── api-spec-data-model.md
│   ├── api-spec-flows.md
│   ├── api-spec-flows-full.md
│   ├── api-spec-flows-full.zip        (ZIP equivalent, kept for compatibility)
│   ├── api-spec-sequence.md
│   ├── api-spec-classes.md
│   ├── api-spec-sections.md
│   ├── api-spec-frontend-pages.md
│   ├── api-spec-frontend-pages.zip    (ZIP equivalent, kept for compatibility)
│   ├── api-spec-sitemap.md
│   ├── api-spec-security-matrix.md
│   ├── api-spec-errors-catalog.md
│   └── api-spec-traceability.md
└── indexed_specs/
    ├── index-spec-flows.md            (index of every API flow / scheduled job)
    ├── flows/flow-NNN-*.md            (one document per flow)
    ├── batches/batch-NNN-*.md         (one document per scheduled job)
    ├── index-spec-frontend-pages.md   (architecture index of every Angular page)
    ├── index-spec-sitemap.md          (route tree with the same per-page links)
    ├── frontend-pages/<slug>.md       (one document per page)
    ├── index-spec-data-model.md               (only if table count ≥ --index-detail-threshold)
    ├── detailed_tables/<table-slug>.md
    ├── index-spec-persistence-mappings.md      (only if mapping count ≥ --index-detail-threshold)
    ├── detailed_persistence_mappings/<dto-entity-slug>.md
    ├── index-spec-data-contracts.md            (only if contract count ≥ --index-detail-threshold)
    ├── detailed_data_contracts/<ts-dto-slug>.md
    ├── index-spec-static-routes.md             (only if route count ≥ --index-detail-threshold)
    ├── detailed_static_routes/<route-slug>.md
    ├── index-spec-error-catalog.md             (only if exception count ≥ --index-detail-threshold)
    └── detailed_exceptions/<exception-slug>.md
```

| File / directory | Always generated | Description |
|---|---|---|
| `index_specs.md` | Yes | Entry point — links to every document below. Stays small regardless of application size |
| `full_specs/api-spec.md` | Yes | Main spec: API flows, persistence mapping, warnings |
| `full_specs/api-spec-data-model.md` | Yes | Database schema: tables, columns, relationships, indexes, constraints. Every fact tagged with provenance (db/sql/jpa/merged) |
| `full_specs/api-spec-flows.md` | Yes | Flowchart diagrams (Mermaid + PlantUML) per API flow and scheduled job |
| `full_specs/api-spec-sequence.md` | Yes | Sequence diagrams (Mermaid + PlantUML) per API flow and scheduled job |
| `full_specs/api-spec-classes.md` | Yes | Class diagrams (Mermaid + PlantUML) per API flow |
| `indexed_specs/index-spec-flows.md` | Yes | Index of every API flow / scheduled job, each linking to its own detail document |
| `indexed_specs/index-spec-frontend-pages.md` | Yes | Architecture index of every Angular page, each linking to its own detail document |
| `indexed_specs/index-spec-sitemap.md` | Yes | Route tree with the same per-page links, for navigation-oriented lookups |
| `indexed_specs/index-spec-data-model.md` | Only if table count ≥ threshold | Extraction notes, provenance legend, relation graph, enumerations, and a table listing linking to `detailed_tables/` |
| `indexed_specs/index-spec-persistence-mappings.md` | Only if mapping count ≥ threshold | Listing of every DTO ↔ Entity mapping, each linking to its own document under `detailed_persistence_mappings/`. Each detail document folds in that pair's UI Label and calculation evidence from Field Traceability — see below |
| `indexed_specs/index-spec-data-contracts.md` | Only if contract count ≥ threshold | Listing of every unique TypeScript ↔ DTO field contract (deduplicated across flows), each linking to its own document under `detailed_data_contracts/` |
| `indexed_specs/index-spec-static-routes.md` | Only if route count ≥ threshold | Listing of every SPA / static route, each linking to its own document under `detailed_static_routes/` |
| `indexed_specs/index-spec-error-catalog.md` | Only if exception count ≥ threshold | Listing of every custom exception (grouped by domain), each linking to its own document under `detailed_exceptions/` |
| `<flow-diagrams-dir>/*.png` | With `--flow-diagrams-dir` | PNG flowcharts, one per flow (`flow-01-post-accounts.png`) |
| `<sequence-diagrams-dir>/*.png` | With `--sequence-diagrams-dir` | PNG sequence diagrams (`seq-01-post-accounts.png`) |
| `<class-diagrams-dir>/*.png` | With `--class-diagrams-dir` | PNG class diagrams (`cls-01-post-accounts.png`) |

Below `--index-detail-threshold` (default `3`), a category has no `indexed_specs/` entry at all — `index_specs.md` links straight to its `full_specs/` document, since splitting 2 tables into 2 files adds overhead without saving context.

### Cross-references (`## Related`)

Each per-element detail document (tables, persistence mappings, data contracts, flows, exceptions, pages) ends with a `## Related` section listing bidirectional cross-links to related elements, so an AI assistant can navigate between them without reloading whole documents.

**Exact edges** (name-matched, no guessing):

- **Table** ⟷ outgoing/incoming foreign keys (other tables) + the persistence mappings that map it
- **Persistence mapping** → its table (entity) and its data contract (DTO)
- **Data contract** → its persistence mapping + the flows that return/accept it
- **Flow** → the data contract it returns or accepts

**Inferred edges** (heuristic, each marked `(inferred)`):

- **Flow** ⟷ **Table** — the flow's call-graph repositories map to entity names (`EmployeeRepository` → `Employee` → `employees`)
- **Exception** ⟷ **Flow** — the exception's throw class appears in the flow's call graph (class-level approximation)
- **Page** ⟷ **Flow** — the page's injected Angular service backs the flow

Links are grouped into **Links out** and **Referenced by**, and long groups are truncated with `(+N more)`. When the related element's category is below the threshold and has no detail document, its name is shown as text marked `(not indexed)` rather than a broken link (tables and exceptions fall back to their full-spec document). Inferred edges are class-level approximations — useful for navigation, not a precise guarantee.

The `full_specs/` documents take the stem of the main output file: if the output is `my-report.md`, the files are `my-report-data-model.md`, `my-report-flows.md`, `my-report-sequence.md`, and `my-report-classes.md`. `indexed_specs/` file names are fixed (`index-spec-*.md`) regardless of the output file's name.

### Full specs vs. indexed specs

Use `full_specs/` documents directly for small-to-medium applications, or to read a whole area in one go. Use `indexed_specs/` when working with an AI assistant on a large application: point it at `index_specs.md`, and let it follow links into `indexed_specs/index-spec-flows.md` or `index-spec-frontend-pages.md` to load only the specific flow or page it needs, instead of the whole monolithic document — keeping context usage low regardless of how large the application grows.

### Why Field Traceability has no `index-spec-field-traceability.md`

`api-spec-traceability.md` (UI ↔ DTO ↔ Entity ↔ Database) is not split into its own indexed category, even though it can grow large. It shares the exact same DTO+Entity identity as a persistence mapping — a separate category would mean two documents per pair, largely repeating the same field table. Instead, each `detailed_persistence_mappings/<slug>.md` folds in the incremental content: a UI Label column on the existing Field Mapping table, and a `#### Calculation Details` section with per-field calculation evidence (how and where a value is computed, and a warning when it's computed in both backend and frontend). `full_specs/api-spec-traceability.md` still exists in full, reduced to what doesn't belong to any single pair: the Legend, the **Double Calculations** triage section (start here when a computed figure "doesn't add up"), and **Calculations Outside Persistence Traceability** (orphan fields with no persisted entity).

### About PNG generation

PNG diagrams are rendered by the embedded **PlantUML** engine using its built-in Smetana layout (a Java port of Graphviz). No external tools are required. If Graphviz is installed on the system PlantUML uses it automatically for higher-quality layout.

PNG generation adds approximately 200–800 ms per diagram. For large projects with many flows, this can add several seconds to the total run time. This is why PNG export is opt-in.

---

## Data Model (`api-spec-data-model.md`)

This file contains a **comprehensive database schema** extracted from three sources:

1. **Live database** (highest priority) — JDBC introspection via `DatabaseMetaData` from a connected PostgreSQL, MySQL, or Oracle database
2. **SQL migrations** (medium priority) — parsing of `V*__*.sql` Flyway migration files under `src/main/resources/db/migration`
3. **JPA entities** (lowest priority) — parsing of `@Entity` classes and Bean Validation annotations

Every fact is **tagged with its provenance** — `db` (live), `sql` (migration), `jpa` (entity), or `merged` (confirmed by multiple sources) — so you and your AI understand which facts are ground truth and which are inferred.

### Sections within the data model

**Extraction Notes** — Warning messages if the database connection failed, migrations were missing, or entities were incomplete.

**Sources Legend** — Explanation of the four provenance tags and which ones appear in this document.

**Global Relation Graph** — ASCII list of all foreign-key relationships (N:1, 1:N, M:N, 1:1, self-joins) with their cardinality and provenance, plus a **Mermaid ER diagram**.

**Tables** — One subsection per table, including:
- **Columns & Business Logic** — row per physical database column with: type, nullable, default value, business meaning (from Javadoc), check constraints (both raw SQL and prose translation from `@Min`, `@Max`, `@Pattern`, etc.)
- **Derived Fields / Virtual Attributes** — JPA fields without a database column (`@Formula`, `@Transient` getters). Clearly separated so AI knows these are computed, not selectable.
- **Constraints & Indexes** — primary key, unique constraints, foreign keys with cardinality, indexes (including partial WHERE predicates for PostgreSQL), and incoming references from other tables.

**Enumerations & Global Constraints** — centralized list of all `@Enumerated` fields with their allowed values, so AI can validate its generated SQL against the exact enum constants.

### Database configuration

To extract the live schema, the tool reads Spring Boot configuration from `application.yml` or `application.properties`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp
    username: appuser
    password: secret
    driver-class-name: org.postgresql.Driver
```

CLI flags override these settings:

```bash
java -jar extractor.jar ... \
  --db-url "jdbc:postgresql://prod-db:5432/myapp" \
  --db-user dbuser \
  --db-password secure_password
```

If no database is reachable (or `--skip-db-connection` is set), the tool falls back gracefully to SQL migrations + JPA analysis. The output clearly notes which sources were used.

---

## Main spec structure (`api-spec.md`)

The main Markdown file has seven sections.

### 1. INDEX

A navigable table of contents listing:

- **UI Components** — Angular components found
- **Backend Controllers** — Spring / JAX-RS / Micronaut controllers with file paths
- **API Flows** — links to each matched frontend↔backend call
- **Data Contracts** — TypeScript model ↔ Java DTO pairs
- **Persistence Contracts** — DTO ↔ JPA Entity pairs
- **SPA / Static Routes** — view-serving endpoints (`@Controller`) labelled `[STATIC_ROUTE]`
- **Background Jobs** — `@Scheduled` methods
- **Unresolved / Low Confidence** — endpoints or calls that could not be matched

### 2. API FLOWS

One subsection per matched HTTP call, rendered as an ASCII call tree:

```
# ACTION: GET /api/v5/accounts/{param}
# FRONTEND: frontend/src/app/account/account.service.ts
  |--> [SERVICE] AccountService :: getAccount : Observable<AccountResponse>
       |--> [HTTP] GET /api/v5/accounts/{id}
            |-- (Network Bridge)
# BACKEND: backend/src/main/java/.../AccountController.java
  |--> [CONTROLLER] AccountController :: getAccount(Long id) : AccountResponse
            |--> [SERVICE] AccountService :: findById [@Tx]
                      |--> [REPOSITORY] AccountRepository :: findById
```

The call graph follows method calls through Services and Repositories up to 10 levels deep. `[@Tx]` marks `@Transactional` boundaries. Return types are shown for each node in the chain.

### 3. DATA MODEL

Field-by-field comparison of each TypeScript interface/class against the corresponding Java DTO or record:

| TS Field | TS Type | Java Field | Java Type | Rules |
|---|---|---|---|---|
| `id` | `number` | `id` | `Long` | |
| `name?` | `string` | `name` | `String` | `@NotBlank` |
| `score` | `number` | `[no match]` | | |

`[no match]` flags fields present on one side but not the other.

### 4. PERSISTENCE MAPPING

For each DTO↔Entity pair:

- Field mapping table with confidence (`explicit` via MapStruct `@Mapping`, `(implicit)` by name match, or `[no match]`)
- JPA relations (`@OneToMany`, `@ManyToOne`, etc.) with join columns
- Repository `@Query` declarations

### 5. SPA / STATIC ROUTES

Endpoints served by `@Controller` classes (view-forwarding, not REST). These are catalogued separately and **excluded from unmatched-endpoint warnings** because they are intentionally not called by Angular's `HttpClient`.

### 6. BACKGROUND JOBS (`@Scheduled`)

One subsection per scheduled job with its cron expression / fixed-rate schedule and its internal call graph:

```
# JOB: ReportGenerationJob :: generateDailyReport
# SCHEDULE: cron = 0 0 6 * * *
# BACKEND: backend/src/main/java/.../ReportGenerationJob.java
  |--> [SERVICE] ReportService :: buildReport [@Tx]
            |--> [REPOSITORY] ReportRepository :: findPendingReports
```

### 7. WARNINGS & LOW-CONFIDENCE MATCHES

- **Unmatched Angular call** — the frontend calls an endpoint the extractor could not find in the Java source
- **Unmatched Java endpoint** — a Java endpoint exists but no Angular service calls it
- **Unresolved dynamic URL** — an Angular URL uses a runtime expression that could not be resolved statically (see below)
- **Call graph cycle** — a circular method call was detected and traversal stopped
- **Degraded type resolution** — Maven classpath could not be resolved; generic type parameters may be inaccurate
- **Name collision** — two controllers share the same simple class name

---

## Diagram files

### Flow diagrams (`api-spec-flows.md`)

One diagram per matched API flow and per scheduled job. Shows the sequence of method calls as a flowchart, including business-logic conditions (if/else branches) filtered by semantic rules.

Each diagram is provided in two formats:
- **Mermaid** (`graph TD`) — rendered inline by GitHub, GitLab, Obsidian, and most modern Markdown viewers
- **PlantUML** — paste into [plantuml.com](https://www.plantuml.com/plantuml) or any PlantUML-compatible tool

Condition filtering rules — a branch is included if it:
- Calls a method on a known service object (R1)
- Has substantive logic in both then and else branches (R2)
- References a domain enum or named business constant (R3)
- Controls the return value of the method (R4)
- Throws a domain-specific exception (R6)

Noise is suppressed: null guards, logging/metrics branches, instanceof checks, and simple collection size checks are excluded.

### Sequence diagrams (`api-spec-sequence.md`)

One sequence diagram per matched API flow and per scheduled job. Shows the full request/response message exchange across participants:

```
Browser → AngularService → Controller → Service → Repository
                                                  ↓
Browser ← AngularService ← Controller ← Service ←
```

`@Transactional` methods are annotated with a `note over` block. Business-logic conditions appear as `alt/else` blocks.

### Class diagrams (`api-spec-classes.md`)

One class diagram per matched API flow. Shows:

- Each class in the call graph with the method relevant to the flow and its return type
- `"1" --> "1" : uses` relationships between consecutive classes
- Implemented interfaces (`..|>`)
- Superclass inheritance (`--|>`)
- DTO dependencies on the controller (`..> : receives` / `..> : returns`)

---

## Supported frameworks and patterns

### Backend (Java)

| Framework | Annotations detected |
|---|---|
| Spring MVC / WebFlux | `@RestController`, `@Controller`, `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping` |
| JAX-RS | `@Path`, `@GET`, `@POST`, `@PUT`, `@DELETE`, `@PATCH` |
| Micronaut | `@Controller`, `@Get`, `@Post`, `@Put`, `@Delete`, `@Patch` |
| JPA / Hibernate | `@Entity`, `@Table`, `@Column`, `@Id`, `@GeneratedValue`, `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`, `@Transient` |
| MapStruct | `@Mapper`, `@Mapping` |
| Spring Data | `@Repository`, `@Query` |
| Spring Scheduling | `@Scheduled` (cron, fixedRate, fixedDelay, initialDelay) |

**DTO detection** covers:

- Java `record` types (top-level and inner)
- Classes whose name ends with `Dto`, `DTO`, `Request`, `Response`, `Command`, `Query`, `Payload`, `Data`, `Result`, `Info`, `Params`, `Body`, or `Form`
- Any class or record in a package whose name contains `model`, `dto`, `api`, `contract`, or `payload`

**Annotation constant resolution** — if a `@RequestMapping` value references a string constant defined elsewhere in the project (e.g. `WebConstants.API_V1_ROOT_URL + "/users"`), the extractor resolves it to its literal value automatically.

### Frontend (Angular / TypeScript)

Two parsing strategies are attempted in order:

| Strategy | Trigger | Quality |
|---|---|---|
| **A — Node.js + ts-morph** | Node.js found on PATH | Full TypeScript type resolution |
| **B — JVM ANTLR parser** | Node.js not available | Heuristic lexing, no type resolution |

The extractor finds:

- Angular services (`@Injectable`) with `HttpClient` calls (`get`, `post`, `put`, `delete`, `patch`)
- TypeScript `interface` and `class` model declarations
- URL template literals, path parameter placeholders, response and request body types

---

## Resolving dynamic URLs

Some Angular services build URLs at runtime using a configuration service or environment variable:

```typescript
// Example: URL cannot be determined statically
this.http.post(this.configService.getEndpointFor(API_ROOT_V5) + 'account', body)
```

The extractor captures this as the token pattern `{configService}{getEndpointFor}{API_ROOT_V5}account` and emits a warning in Section 7 with the exact `--url-prefix-map` argument needed.

### Automatic resolution (webpack DefinePlugin)

If the Angular project uses webpack `DefinePlugin` to inject string constants, the extractor reads those values automatically:

```js
// webpack.custom.js — read automatically
new webpack.DefinePlugin({
  API_ROOT_V5: JSON.stringify('api/v5/'),
});
```

This resolves `{API_ROOT_V5}` → `api/v5/` without any manual configuration.

### Manual resolution (`--url-prefix-map`)

For the service method part, provide an explicit mapping. The key may be written in human-readable or token form:

```bash
# Human-readable (recommended)
--url-prefix-map "configService.getEndpointFor(API_ROOT_V5)=/api/v5/"

# Token form (copy from the warning message)
--url-prefix-map "{configService}{getEndpointFor}{API_ROOT_V5}=/api/v5/"
```

The flag is repeatable for multiple services:

```bash
--url-prefix-map "configService.getEndpointFor(API_ROOT_V5)=/api/v5/" \
--url-prefix-map "configService.getEndpointFor(ADMIN_API)=/admin/api/v1/"
```

---

## Using the output with an AI assistant

The generated files are designed to be attached as context at the start of an AI conversation. Typical use cases:

**Codebase onboarding**
> "Here is `api-spec.md` summarising our Java/Angular application. Help me understand how the account management flow works end to end."

**Database schema understanding**
> "Here is `api-spec-data-model.md` showing our database schema extracted from live DB + migrations + JPA entities. Which tables store customer contact information? What constraints apply to the email field?"

**Impact analysis**
> "I need to add a `lastLoginAt` field to the `AccountResponse`. Based on `api-spec.md` and `api-spec-data-model.md`, which TypeScript models, Java DTOs, and database entities would need to change?"

**Bug investigation**
> "Section 7 of `api-spec.md` shows `POST /api/v5/account` as an unmatched Angular call. What might cause this? Also check `api-spec-data-model.md` for any related constraints."

**Code generation**
> "Using the patterns in `api-spec.md` and the schema in `api-spec-data-model.md`, generate a new endpoint `GET /api/v5/accounts/{id}/audit-log` with its Angular service method, Java controller, DTO, and repository query. Respect the database cardinality and constraints."

**Schema debugging**
> "I'm getting a `DataIntegrityViolationException` when saving a new employee record. Based on `api-spec-data-model.md`, which constraints might be violated? Here's the stack trace..."

**Architecture review**
> "Here are `api-spec-sequence.md`, `api-spec-classes.md`, and `api-spec-data-model.md` (with its Mermaid ER diagram). Review the class structure, sequence of the upload flow, and the database design. Suggest improvements."

**AI-friendly schema facts** — Every fact in `api-spec-data-model.md` is tagged with its provenance (`db` / `sql` / `jpa` / `merged`), so AI understands which facts are verified ground truth (highest confidence) vs. inferred from static analysis (lower confidence).

**Regenerate frequently** — the files are fast to produce and should be kept in sync with the codebase, especially before starting a new AI-assisted task.

---

## Limitations

### API flows and call graphs
- **Dynamic path construction** not using webpack `DefinePlugin` requires manual `--url-prefix-map` entries.
- **Call graph depth** is capped at 10 levels to prevent runaway traversal.
- **Condition extraction** captures only top-level business-logic branches; conditions nested inside loops or purely technical guards (null checks, instanceof) are filtered out.
- **Class diagrams** show the method relevant to the flow but not all class fields or unrelated methods.
- **JAX-RS and Micronaut** support is best-effort; Spring MVC receives the most complete analysis.
- **Generic type parameters** may be missing or incorrect if the Maven classpath cannot be resolved (degraded mode warning is shown in Section 7).
- **TypeScript without Node.js** uses a heuristic ANTLR parser; complex type expressions may not be resolved.

### Database schema extraction
- **Live database connection** requires network reachability and valid credentials. If unreachable, extraction gracefully falls back to SQL migrations + JPA.
- **Supported databases** are PostgreSQL, MySQL, and Oracle. Other JDBC databases may work but are not tested.
- **Enum resolution** works for `@Enumerated` fields; CHECK constraint values are parsed from SQL but enum constants are inferred, not resolved to Java enum names.
- **Derived fields** (`@Formula`, `@Transient`) are detected and separated, but the exact derivation logic is not extracted — only marked as "Computed field".
- **Indexes with complex expressions** (partial WHERE, computed columns) may not parse correctly in all database dialects.
- **Database documentation** (comments/descriptions) is not extracted; only Javadoc from JPA entity classes appears in the "Business Meaning" column.
- **Schema changes between sources** — if the live database schema does not match SQL migrations or JPA entities, facts are merged by provenance priority (live DB > SQL > JPA). Warnings are emitted for inconsistencies.

### General
- **PNG diagrams** require more time to generate; they are opt-in via the diagram directory flags.
- **Performance** on very large codebases (100+ controllers, 1000+ entity fields) may take 30+ seconds.
