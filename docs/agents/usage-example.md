# Agent note: the Embedded Mode usage example

If you're integrating `file-manager-spring-boot-starter` into a host application (or asked to
demonstrate/verify that integration), read
[`file-manager-usage-example`](../../file-manager-usage-example/README.md) first - it's a
runnable, compiled reference for exactly that, kept in sync with
[`docs/library-usage.md`](../library-usage.md) by its own test (issue #66:
`UsageExampleApplicationTest` fails if the calls it makes stop matching what
`file-manager-core`'s services actually accept).

Treat it as source of truth over the prose in `docs/library-usage.md` if the two ever disagree -
the module is compiled and tested on every build; the doc isn't.

Don't extend this module's scope beyond what it currently covers (Folder create/fetch/list, File
upload/download/trash/restore) without also updating `docs/library-usage.md`'s matching section -
see issue #66 for the reasoning on why the two are meant to grow together.
