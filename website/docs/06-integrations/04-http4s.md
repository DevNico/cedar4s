---
sidebar_label: http4s
title: http4s
---

# http4s

This example shows how to integrate cedar4s in an http4s service using
cats-effect `IO`.

## Example Project

See `examples/http4s` for a runnable sample that exposes a simple
`POST /auth/check` endpoint.

## Key Pieces

- **Cedar schema**: `src/main/resources/schema/http4sauth.cedarschema`
- **Policies**: `src/main/resources/policies/main.cedar`
- **Server**: `src/main/scala/example/http4s/Http4sAuthServer.scala`

## Running

```bash
cd examples/http4s
sbt run
```

The server starts on [http://localhost:8080](http://localhost:8080).

