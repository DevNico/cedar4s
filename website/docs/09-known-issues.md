---
sidebar_label: Known Issues
title: Known Issues
---

# Known Issues

## Cedar Schema Features

**Tags and Annotations**: Cedar schema `tags` blocks and multi-value annotations are not
currently supported by the schema parser.

**Workaround**: Use standard Cedar JSON schema features. Avoid `tags` blocks in
`.cedarschema` files.
