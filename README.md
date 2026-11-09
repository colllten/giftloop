# GiftLoop
Shared wishlists for families and groups, with claim secrecy. See `docs/`.

## Local Database
Requires Docker and the [Supabase CLI](https://supabase.com/docs/guides/cli).

```bash
cd database
supabase start      # prints local URLs and keys
supabase db reset   # migrations + seed
supabase test db    # pgTAP tests
```

Seed users (`alice`, `bob`, `carol`, `dave` @example.com) use password `password123`.
Schema docs: [docs/DATABASE.md](docs/DATABASE.md).

## Web App
Requires Java 25+. With the local database running:

```bash
cd web
./mvnw quarkus:dev   # http://localhost:8080
./mvnw test
```

Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
Design system: [docs/DESIGN.md](docs/DESIGN.md).
