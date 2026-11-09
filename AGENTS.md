# Project Overview
GiftLoop is a webapp that allows families, friends, and groups of people to
make wishlists/registries of gift items for events/holidays that others in the
group can claim to purchase. The app aims to reduce confusion and duplication
of gift purchases among groups. Wishlist owners cannot see who has claimed
to purchase items on their wishlist until after the event is over.

# Technology Stack
GiftLoop is in early stages of development and is pending several architectural
and technical decisions. As of writing, it will start as a web application
accessible for mobile and desktop browsers. The web application will utilize
Quarkus, HTMX, and Qute. Supabase will be the database.

Later stages of development will include cross-platform mobile development.

# Architecture
See `docs/ARCHITECTURE.md`. All user-facing DB access goes through
`RlsContext` (ADR 001, ADR 002).
CI/CD: `docs/CICD.md`.

# Coding Conventions
Code should conform to "clean code" within reason. Strict conformance is too
dogmatic. When certain conventions of clean code are not followed, they must
be documented.

All functions and methods must be documented with JavaDocs and/or other
language-specific ways of documenting functionality.

Code should be tested. Major functionality requires testing, but 100% code
coverage is not mandatory.

Sufficient logging of all levels is recommended.

UI must follow `docs/DESIGN.md`: style through the tokens in
`assets/app.css`, never with hard-coded colors.

Other coding conventions are open to discussion, and should be added to this
section if added later.

# Development Commands
Database (requires Docker and the Supabase CLI; run from `database/`):
- `supabase start` / `supabase stop` — local stack (Studio: http://127.0.0.1:54323)
- `supabase db reset` — reapply all migrations and `seed.sql`
- `supabase test db` — run pgTAP tests in `supabase/tests/`
- `supabase db lint` — lint schemas
- `supabase migration new <name>` — create a migration
- After changing `config.toml`: `supabase stop && supabase start` (a running stack keeps its old config)
- Emails (sign-in codes): Mailpit at http://127.0.0.1:54324

Web app (Java 25+, run from `web/`; needs the local database running and reset):
- `./mvnw quarkus:dev` — dev mode on http://localhost:8080 (Swagger UI: /q/swagger-ui)
- `./mvnw test` — tests (run against local Supabase and its seed users)
- `./mvnw package` — build `target/quarkus-app/`

# Development Workflow
1. Plan changes and propose data model
2. Create migrations, constraints, and auth policies
3. Implement APIs and business logic
4. Build interface using API contract
5. Write integration and end-to-end tests
6. Review implementation for security, correctness, and consistency
7. Pull request review performed by developer and optionally other agents
  - Successful CI/CD checks required

# Database Rules
Tables will be singular (not plural) and TitleCase, while columns are
snake_case. Boolean columns should typically have "is_" prepended unless it
should not apply.

Migration files should include a short, developer-focused header justifying
the migration.

RLS policies must be configured for each table.

Additional database rules can be added in this section if determined later.

# Security
GiftLoop will utilize Supabase auth.

Secret management is to be determined -- if needed.

# Git Conventions
`main` branch is the branch to be deployed to production. Features must be
created with a `feature/` prefix, bugs with `bug`.

Atomic commits are preferred.

# Agent Expectations
Agents are expected to upkeep code quality.

Agents must test code before calling the prompt done.

Agents should not be verbose when writing documentation. Documentation is meant
to be developer-focused and digestible for quick reading.

If there is any level of ambiguity, agents must ask clarifying questions.

Architecture and sequence diagrams should be documented.
