---
sidebar_label: Play Framework
title: Play Framework Example
---

# Play Framework Example

This example demonstrates a more realistic Play application that combines:

- Slick + H2 database layer
- Self-signed JWT auth (HS256)
- cedar4s authorization with Cedar policies
- smithy4s + smithy4play API layer
- Capabilities embedded in `Document` via Smithy mixin

## Example Project

See `examples/play-framework` for a runnable sample.

## Key Pieces

- **Database models**: `app/models/Models.scala`
- **Slick tables**: `app/db/Tables.scala`
- **Seed data**: `app/db/DbInitializer.scala`
- **JWT auth**: `app/auth/JwtService.scala`
- **cedar4s runtime**: `app/cedar/AuthRuntime.scala`
- **Controllers**: `app/controller/ApiController.scala`
- **Routes**: `conf/routes`

## Running

```bash
cd examples/play-framework
sbt run
```

smithy4play is published on GitHub Packages. Configure a token with access to
`innFactory/smithy4play`:

```bash
export GITHUB_TOKEN=your_token_here
```

## Quick Test

1) Get a token:

```bash
curl -X POST http://localhost:9000/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"alice"}'
```

2) Read a document:

```bash
curl http://localhost:9000/documents/doc-1 \
  -H "Authorization: Bearer <token>"
```

3) List documents (capabilities included in each Document):

```bash
curl "http://localhost:9000/documents?orgId=org-1" \
  -H "Authorization: Bearer <token>"
```

