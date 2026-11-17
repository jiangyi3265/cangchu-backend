# Backend origin and local integration

This directory is derived from [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue), branch `springboot3`, pinned to commit `127ac73146272b71e070662043917d80585d5bae`.

The original MIT license is retained byte-for-byte in `LICENSE`. Upstream source was copied without its `.git` metadata. The public source lineage is the pinned commit; the private copy manifest records every original file SHA-256.

Local changes add `ruoyi-warehouse` to the Maven reactor and `ruoyi-admin`, require warehouse JWT authentication, use environment configuration instead of sample database/token secrets, isolate the explicit `local` profile, disable the Druid console and API documentation by default, and configure a current JUnit 5-capable Surefire plugin. Warehouse business code runs natively in Java. The legacy Node application has been removed from the current project; the retained Vue admin frontend uses Node.js/npm only for development and builds.

The original RuoYi and Quartz SQL files remain upstream reference material; numbered `warehouse_*.sql` files are local business migrations. `README.md` is rewritten for this warehouse project. Sample account hashes are not production initialization: `tools/initialize-local.py` replaces them before applying SQL to an empty isolated local database and disables the demo account. Never initialize production from sample accounts.

The master connection pool initializes UTC sessions and the warehouse module checks database/JDBC clock interpretation before business use. This changes application sessions only, not a database server's global timezone.

The Jackson customization additionally enables `USE_BIG_DECIMAL_FOR_FLOATS` for untyped request bodies, preserving decimal JSON numbers before warehouse validation. Migration 018 replaces native warehouse binary JSON columns with exact JSON text plus enforced validity checks; the warehouse module refuses startup against the earlier storage layout. Upstream system tables are not converted.

Migration 019 adds native ASN custody tasks and exact-text event evidence; migration 020 adds five separate action permissions. The exact-document startup check includes the new custody event column. These changes stay inside warehouse business tables, services and menus.
