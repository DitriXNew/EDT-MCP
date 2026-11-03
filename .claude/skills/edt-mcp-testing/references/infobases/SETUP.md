# Infobase binding checks

Use a ready disposable configuration project, an existing test infobase, and a RuntimeClient
configuration bound to its application. Select that application explicitly as the project's
default in EDT. File creation needs a registered 1C runtime; run live mutations only on an
attended stand. Without a platform, use mocked EDT service tests for clear/restore/failure orders.

Before and after each call, read:

```text
get_applications({"projectName":"TestConfiguration"})
list_configurations({"projectName":"TestConfiguration","type":"client"})
```

Compare `defaultApplicationId` and each configuration's `applicationId`. Require
`defaultApplication="resolved"` when expecting a default; `unknown` is unverified.
Also check the explicit default selection in EDT: a sole application can be inferred by
`get_applications` even when no explicit default is stored.

Check `preservedBindings`, `restoredBindings`, `removedBindings`, and `unrestoredBindings`
against these observations. Recovery failure is an error even when the mutation happened;
fix the named bindings before retrying. Wait for pending native writes before completing recovery.
Keep differing non-null choices; avoid manual clears during a call because recovery restores them.

For global deletion, associate the same launcher UUID with a second disposable project and
repeat both reads there. Create a test RuntimeClient and explicit default in each project;
keep an unrelated application as a preservation control. Remove only test-owned registrations
and configurations during cleanup; keep database files unless their deletion is intended.
