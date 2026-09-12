# java-angular-fullstack-extractor

> Static analysis tool that generates structured Markdown documents from a Java backend + Angular frontend — designed as context for AI assistants.

![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![Build](https://img.shields.io/badge/build-Maven-C71A36?logo=apachemaven)
![License](https://img.shields.io/badge/license-MIT-green)

---

## The problem

AI assistants are most useful when they understand your codebase. But sharing raw source files is slow, expensive, and often exceeds context limits. A senior engineer asked to review a feature doesn't read every file — they look at the API contract, the data shapes, and the call graph.

This tool does the same: it statically analyses a full-stack Java + Angular application and distils it into a set of Markdown documents that an AI can consume in seconds.

```
java -jar java-angular-fullstack-extractor.jar /my-app/backend /my-app/frontend
# → index_specs.md                      (start here — links to every document below)
# → full_specs/api-spec.md              (API flows, persistence mapping, warnings)
# → full_specs/api-spec-data-model.md   (database schema: tables, columns, relationships, constraints)
# → full_specs/api-spec-flows.md        (flowchart diagrams per API flow and scheduled job)
# → full_specs/api-spec-sequence.md     (sequence diagrams per API flow and scheduled job)
# → full_specs/api-spec-classes.md      (class diagrams per API flow)
# → indexed_specs/index-spec-flows.md           (one document per API flow / job)
# → indexed_specs/index-spec-frontend-pages.md  (one document per Angular page)
```

---

## What it extracts

| Area | Details |
|---|---|
| **API flows** | Every matched HTTP call from Angular service → Java controller, rendered as an ASCII call tree with `[@Tx]` markers and return types |
| **Call graph** | Method traversal through Services and Repositories, up to 10 levels deep |
| **Business conditions** | Semantically significant if/else branches in service methods (null guards and logging noise filtered out) |
| **Data contracts** | TypeScript interface/class fields side-by-side with the Java DTO or `record` they correspond to |
| **Persistence mapping** | DTO ↔ JPA Entity field mapping (explicit via MapStruct, implicit by name, or `[no match]`) + relations + `@Query` declarations |
| **Database schema** | Tables, columns, relationships, indexes, constraints, and enumerations extracted from live database, SQL migrations, and JPA entities — each fact tagged with its provenance (db/sql/jpa/merged) |
| **Derived fields** | JPA-computed fields (`@Formula`, `@Transient`) clearly separated from persistent columns so AI never attempts a direct SQL SELECT |
| **Background jobs** | `@Scheduled` methods with cron/fixedRate schedule and their own call graph and diagrams |
| **SPA routes** | `@Controller` view-forwarding endpoints catalogued separately, excluded from unmatched warnings |
| **Warnings** | Unmatched endpoints, unresolved dynamic URLs, call graph cycles, name collisions, database connection failures (graceful fallback to static analysis) |
| **Flow diagrams** | Mermaid + PlantUML flowcharts for every API flow and job, with business-condition branches |
| **Sequence diagrams** | Mermaid + PlantUML sequence diagrams showing the full Browser → Angular → Controller → Service → Repository exchange |
| **Class diagrams** | Mermaid + PlantUML class diagrams with relationships, multiplicity, interfaces, and inheritance |
| **ER diagrams** | Mermaid ER diagrams showing database cardinality (1:N, M:N, 1:1, self-joins) with relationship names |

### Supported frameworks

**Backend:** Spring MVC · Spring WebFlux · JAX-RS · Micronaut · JPA/Hibernate · MapStruct · Spring Data · Spring Scheduling

**Frontend:** Angular `HttpClient` calls · TypeScript `interface` / `class` models · template literals · path parameters

---

## Output files

Every run writes everything under one base directory (current directory, the output-file's own directory, or `--output-dir` — see [CLI reference](#cli-reference)), organised as:

```
<base-dir>/
├── index_specs.md            # start here — links to every document below
├── full_specs/                # complete, monolithic documents
│   ├── api-spec.md
│   ├── api-spec-data-model.md
│   ├── api-spec-flows.md
│   ├── api-spec-flows-full.md
│   ├── api-spec-sequence.md
│   ├── api-spec-classes.md
│   ├── api-spec-sections.md
│   ├── api-spec-frontend-pages.md
│   ├── api-spec-sitemap.md
│   ├── api-spec-security-matrix.md
│   ├── api-spec-errors-catalog.md
│   └── api-spec-traceability.md
└── indexed_specs/              # per-element index + detail documents (lighter on AI context)
    ├── index-spec-flows.md
    ├── flows/flow-NNN-*.md
    ├── batches/batch-NNN-*.md
    ├── index-spec-frontend-pages.md
    ├── index-spec-sitemap.md
    ├── frontend-pages/<page-slug>.md
    ├── index-spec-data-model.md               # only once table count ≥ --index-detail-threshold
    ├── detailed_tables/<table-slug>.md
    ├── index-spec-persistence-mappings.md      # only once mapping count ≥ --index-detail-threshold
    ├── detailed_persistence_mappings/<dto-entity-slug>.md
    ├── index-spec-data-contracts.md            # only once contract count ≥ --index-detail-threshold
    ├── detailed_data_contracts/<ts-dto-slug>.md
    ├── index-spec-static-routes.md             # only once route count ≥ --index-detail-threshold
    ├── detailed_static_routes/<route-slug>.md
    ├── index-spec-error-catalog.md             # only once exception count ≥ --index-detail-threshold
    └── detailed_exceptions/<exception-slug>.md
```

| File / directory | Description |
|---|---|
| `index_specs.md` | Single entry point — links to everything else. Stays small regardless of application size; point an AI assistant here first |
| `full_specs/api-spec.md` | Main spec: API flows, persistence mapping, warnings |
| `full_specs/api-spec-data-model.md` | Database schema: tables, columns, relationships, indexes, constraints with full provenance tags (db/sql/jpa/merged) |
| `full_specs/api-spec-flows.md` | Flowchart diagrams (Mermaid + PlantUML) per API flow and scheduled job |
| `full_specs/api-spec-sequence.md` | Sequence diagrams (Mermaid + PlantUML) per API flow and scheduled job |
| `full_specs/api-spec-classes.md` | Class diagrams (Mermaid + PlantUML) per API flow |
| `indexed_specs/index-spec-flows.md` | Index of every API flow / scheduled job, each linking to its own detail document under `flows/` or `batches/` |
| `indexed_specs/index-spec-frontend-pages.md` | Architecture index of every Angular page, each linking to its own detail document under `frontend-pages/` |
| `indexed_specs/index-spec-sitemap.md` | Route tree with the same per-page links, for navigation-oriented lookups |
| `indexed_specs/index-spec-data-model.md` | Extraction notes, provenance legend, global relation graph, and enumerations, each table linking to its own document under `detailed_tables/` |
| `indexed_specs/index-spec-persistence-mappings.md` | Listing of every DTO ↔ Entity mapping, each linking to its own document under `detailed_persistence_mappings/`. Each detail document also folds in that pair's UI Label column and field-level calculation evidence from Field Traceability, instead of duplicating the same DTO↔Entity table in a separate category |
| `indexed_specs/index-spec-data-contracts.md` | Listing of every unique TypeScript ↔ DTO field contract (deduplicated across flows), each linking to its own document under `detailed_data_contracts/` |
| `indexed_specs/index-spec-static-routes.md` | Listing of every SPA / static route, each linking to its own document under `detailed_static_routes/` |
| `indexed_specs/index-spec-error-catalog.md` | Listing of every custom exception (grouped by domain), each linking to its own document under `detailed_exceptions/` |

A ZIP equivalent of the flows and frontend-pages subtrees (`api-spec-flows-full.zip`, `api-spec-frontend-pages.zip`) is still generated in `full_specs/` for compatibility, but the unzipped tree in `indexed_specs/` is the canonical, browsable form.

**`--index-detail-threshold <n>`** (default `3`) controls when a category gets split into a per-element index + detail documents at all. Below the threshold, `index_specs.md` links straight to the `full_specs/` document instead — a 2-table app doesn't need a `detailed_tables/` folder with 2 files in it.

### Cross-references (`## Related`)

Each per-element detail document (tables, persistence mappings, data contracts, flows, exceptions, pages) ends with a `## Related` section of bidirectional cross-links, so an AI assistant can hop between related elements without loading a whole document. Links are split into **Links out** and **Referenced by**. When a related element's category is below the threshold (no detail document), its name is still shown as text marked `(not indexed)` instead of a broken link; tables and exceptions fall back to their full-spec document.

Two tiers of edges:

- **Exact** (name-matched, no guessing): Table ⟷ Table (foreign keys), Persistence mapping → Table, Data contract ⟷ Persistence mapping, Flow → Data contract.
- **Inferred** (heuristic, marked `(inferred)`): Flow ⟷ Table (via call-graph repositories → entity name), Exception ⟷ Flow (exception's throw-class appears in a flow's call graph), Page ⟷ Flow (page's injected Angular service backs the flow). Inferred edges are class-level approximations — useful for navigation, not a precise guarantee.

PNG images are generated on demand with optional flags (see [CLI reference](#cli-reference)).

---

## Output example

```
## 2. API FLOWS

### 2.1 POST /api/v5/accounts

\```
# ACTION: POST /api/v5/accounts
# FRONTEND: frontend/src/app/account/account.service.ts
  |--> [SERVICE] AccountService :: create : Observable<AccountResponse>
       |--> [HTTP] POST /api/v5/accounts
            |-- (Network Bridge)
# BACKEND: backend/src/main/java/.../AccountController.java
  |--> [CONTROLLER] AccountController :: create(AccountRequest body) : AccountResponse
            |--> [SERVICE] AccountService :: save [@Tx] : Account
                      |--> [REPOSITORY] AccountRepository :: save : Account
\```
```

---

## Quick start

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21+ | Required |
| Maven | 3.9+ | Build only |
| Node.js | 18+ | Optional — improves TypeScript analysis accuracy |

### Build

```bash
git clone https://github.com/devmanchego/java-angular-fullstack-extractor.git
cd java-angular-fullstack-extractor
mvn package -q
```

### Run

```bash
java -jar target/java-angular-fullstack-extractor-1.0.0.jar \
  /path/to/java-project \
  /path/to/angular-project
```

All documents are written under `full_specs/` and `indexed_specs/` next to the output path (default: current directory), with `index_specs.md` as the entry point. Use `--output-dir` to centralize everything in one place regardless of where the analyzed projects live.

---

## CLI reference

```
java -jar java-angular-fullstack-extractor.jar
    <java-project-path>                          required
    <angular-project-path>                       required
    [output-file]                                optional  default: full-stack-spec.md
    [--output-dir <dir>]                         optional  centralizes full_specs/ + indexed_specs/ here
    [--index-detail-threshold <n>]               optional  default: 3 (see below)
    [--url-prefix-map key=value] ...             optional  repeatable
    [--flow-diagrams-dir <dir>]                  optional  PNG flowcharts
    [--sequence-diagrams-dir <dir>]              optional  PNG sequence diagrams
    [--class-diagrams-dir <dir>]                 optional  PNG class diagrams
    [--db-url <jdbc-url>]                        optional  override spring.datasource.url
    [--db-type postgresql|mysql|oracle]          optional  override DB type detection
    [--db-user <user>]                           optional  override spring.datasource.username
    [--db-password <password>]                   optional  override spring.datasource.password
    [--db-profile <profile>]                     optional  active Spring profile for datasource config
    [--skip-db-connection]                       optional  skip live DB introspection (SQL/JPA only)
```

| Parameter | Description |
|---|---|
| `java-project-path` | Root of the Java project (Maven or Gradle layout) |
| `angular-project-path` | Root of the Angular project |
| `output-file` | Name (or path) of the main generated Markdown file, written under `full_specs/`. Without `--output-dir`, its directory component (or the current directory, if none) becomes the base directory. Default: `full-stack-spec.md` |
| `--output-dir` | Base directory for all output. `full_specs/`, `indexed_specs/`, and `index_specs.md` are created under it, created automatically if absent |
| `--index-detail-threshold` | Minimum element count (tables, persistence mappings) a category needs before it gets its own per-element index + detail documents under `indexed_specs/`. Default: `3` |
| `--url-prefix-map` | Maps a dynamic Angular URL prefix to its resolved base path (see below) |
| `--flow-diagrams-dir` | Directory for PNG flowchart exports. Directory is created if absent |
| `--sequence-diagrams-dir` | Directory for PNG sequence diagram exports |
| `--class-diagrams-dir` | Directory for PNG class diagram exports |
| `--db-url` | JDBC URL to connect to the database (e.g., `jdbc:postgresql://localhost:5432/mydb`). Overrides `spring.datasource.url` from config files |
| `--db-type` | Database type: `postgresql`, `mysql`, or `oracle`. Auto-detected from JDBC URL if omitted |
| `--db-user` | Database username. Overrides `spring.datasource.username` from config |
| `--db-password` | Database password. Overrides `spring.datasource.password` from config |
| `--db-profile` | Spring profile name to load `application-{profile}.yml` or `.properties` (e.g., `prod`, `dev`) |
| `--skip-db-connection` | If set, skips live database connection entirely and uses only SQL migrations + JPA entities for schema extraction |

### Examples

```bash
# Minimal (reads DB config from application.yml/properties)
java -jar extractor.jar /app/backend /app/frontend

# Custom output location
java -jar extractor.jar /app/backend /app/frontend /app/docs/api-spec.md

# Centralize all output in one directory regardless of project location
java -jar extractor.jar /app/backend /app/frontend api-spec.md --output-dir /app/docs/spec

# With live database connection (PostgreSQL)
java -jar extractor.jar /app/backend /app/frontend /app/docs/api-spec.md \
  --db-url "jdbc:postgresql://db.prod.example.com:5432/myapp" \
  --db-user dbuser \
  --db-password secure_password

# With dynamic URL resolution
java -jar extractor.jar /app/backend /app/frontend /app/docs/api-spec.md \
  --url-prefix-map "configService.getEndpointFor(API_ROOT_V5)=/api/v5/" \
  --url-prefix-map "configService.getEndpointFor(ADMIN_API)=/admin/api/v1/"

# With all PNG diagram exports + database config
java -jar extractor.jar /app/backend /app/frontend /app/docs/api-spec.md \
  --db-url "jdbc:mysql://localhost:3306/myapp" \
  --db-type mysql \
  --db-user root \
  --db-password password \
  --flow-diagrams-dir /app/docs/diagrams/flows \
  --sequence-diagrams-dir /app/docs/diagrams/sequences \
  --class-diagrams-dir /app/docs/diagrams/classes

# Skip database connection (static analysis only: SQL migrations + JPA)
java -jar extractor.jar /app/backend /app/frontend /app/docs/api-spec.md \
  --skip-db-connection
```

### PNG generation

PNG diagrams are rendered by the embedded **PlantUML** engine using its built-in Smetana layout — no Graphviz or other external tools required. Generation adds roughly 200–800 ms per diagram, so it is opt-in via the three directory flags above.

---

## Resolving dynamic Angular URLs

Angular services often build URLs at runtime:

```typescript
this.http.get(this.configService.getEndpointFor(API_ROOT_V5) + 'accounts')
```

The extractor captures this as the token pattern `{configService}{getEndpointFor}{API_ROOT_V5}accounts`.

**Automatic (webpack DefinePlugin):** if `API_ROOT_V5` is defined as a string literal in a webpack config, the tool reads it automatically — no configuration needed.

**Manual:** for the service method prefix, add a `--url-prefix-map` entry. The Section 7 warning message tells you the exact key to use:

```
Unresolved dynamic URL in `AccountService#fetch`:
`/{configService}{getEndpointFor}{API_ROOT_V5}accounts`
— add `--url-prefix-map "{configService}{getEndpointFor}{API_ROOT_V5}=<base-url>"`
```

---

## Using the output with AI

Attach the generated files at the start of any AI conversation. For small-to-medium applications, `full_specs/api-spec.md` is usually enough; for larger ones, start with `index_specs.md` and let the AI follow links into `indexed_specs/` only for the areas it actually needs — keeping context usage low.

```
Here is index_specs.md, the entry point for our application's generated docs.

[paste or attach file]

I need to add a `lastLoginAt` field to AccountResponse.
Which TypeScript models, Java DTOs, and database entities need to change?
```

Common use cases:

- **Onboarding** — understand an unfamiliar codebase in minutes
- **Impact analysis** — ask what breaks when a field or endpoint changes
- **Code generation** — generate consistent new endpoints following existing patterns
- **Bug investigation** — ask why an endpoint appears as unmatched in Section 7
- **Architecture review** — attach `api-spec-sequence.md` and `api-spec-classes.md` for a grounded structural review
- **PR review** — give the AI the spec + the diff for a grounded review

Regenerate the files whenever the codebase changes significantly. Generation is fast and deterministic.

---

## How it works

```
Java sources  ──► JavaParser (AST)  ──► Endpoints, DTOs, Entities, Jobs, Call Graph, JPA Annotations
                                                   │
Angular sources ─► ts-morph (Node.js)  ──►         ├─► EndpointMatcher
                 └─► ANTLR JVM parser              │
                                                   ├─► SchemaBuilder
Database/Migrations ─► JDBC/FlywayParser  ──►     │
                                                   ▼
                                    api-spec.md              (main spec)
                                    api-spec-data-model.md   (database schema)
                                    api-spec-flows.md        (flowcharts)
                                    api-spec-sequence.md     (sequence diagrams)
                                    api-spec-classes.md      (class diagrams)
                                    [diagrams/*.png]         (optional PNGs)
```

1. **Java AST parsing** — JavaParser with symbol resolution reads all `.java` files. String constants referenced in annotations are resolved across files. JPA entities and Bean Validation annotations are extracted.
2. **Database introspection** — LiveSchemaIntrospector attempts a JDBC connection (if configured) to read live schema from PostgreSQL, MySQL, or Oracle. Fails gracefully if DB is unavailable.
3. **SQL migration parsing** — FlywayMigrationParser reads `V*__*.sql` files from `src/main/resources/db/migration` to extract structural facts (CREATE TABLE, ALTER TABLE, indexes, constraints).
4. **Schema merging** — SchemaBuilder combines live DB (highest priority) + SQL migrations + JPA annotations (lowest priority) with per-fact provenance tags (db/sql/jpa/merged). Semantic facts (business meaning, constraints in prose) always come from JPA.
5. **Angular parsing** — Strategy A uses a Node.js subprocess running ts-morph for full TypeScript type resolution. If Node.js is unavailable, Strategy B uses a bundled ANTLR island grammar.
6. **Endpoint matching** — paths are canonicalised (`{id}` ≡ `{sessionId}` in the same position) before matching Angular calls to Java endpoints.
7. **Call graph traversal** — field types, method parameters, and local variable declarations are used to follow method calls across class boundaries. Return types and `@Transactional` markers are captured at each node.
8. **Condition extraction** — business-significant if/else branches are identified using semantic accept/discard rules; noise (null guards, logging, instanceof) is filtered out.
9. **Markdown rendering** — all data is assembled into five structured documents with navigable anchors and both Mermaid and PlantUML diagram sources, including Mermaid ER diagrams.
10. **PNG export (optional)** — PlantUML's embedded Smetana engine renders diagrams to PNG without any external dependencies.

---

## Project structure

```
src/main/java/com/devmanchego/contextextractor/
├── Application.java                       # Pipeline orchestrator
├── angular/
│   ├── jvmparser/                         # Strategy B: ANTLR TypeScript parser
│   ├── nodebridge/                        # Strategy A: ts-morph Node.js bridge
│   ├── resolve/UrlPrefixResolver          # Dynamic URL resolution
│   └── webpack/WebpackConstantReader      # DefinePlugin constant extraction
├── callgraph/
│   ├── CallGraphBuilder                   # Method call traversal
│   ├── CallNode                           # Call graph node (class, method, role, conditions)
│   ├── ConditionExtractor                 # Business-logic branch detection
│   └── FlowCondition                      # Condition data (text, then/else summaries)
├── cli/                                   # Argument parsing and validation
├── java/
│   ├── extractor/                         # Spring / JAX-RS / Micronaut / Scheduled
│   └── persistence/                       # JPA entities, DTOs, MapStruct, @Query
├── matching/EndpointMatcher               # Angular ↔ Java path matching
└── render/
    ├── MarkdownRenderer                   # Main spec (api-spec.md)
    ├── FlowDiagramRenderer                # Flowchart Markdown (api-spec-flows.md)
    ├── FlowDiagramPngExporter             # Flowchart PNGs
    ├── SequenceDiagramRenderer            # Sequence diagram Markdown
    ├── SequenceDiagramPngExporter         # Sequence diagram PNGs
    ├── ClassDiagramRenderer               # Class diagram Markdown
    └── ClassDiagramPngExporter            # Class diagram PNGs
```

---

## Full documentation

See [usage.md](usage.md) for the complete user manual including:

- Detailed output section descriptions with field-level examples
- Full list of supported annotations and DTO naming conventions
- Annotation constant resolution (`WebConstants.API_V1_ROOT_URL + "/path"`)
- SPA / static route detection
- Diagram types: flow diagrams, sequence diagrams, class diagrams
- PNG generation details and performance notes
- All warning types and how to resolve them

---

## License

MIT
