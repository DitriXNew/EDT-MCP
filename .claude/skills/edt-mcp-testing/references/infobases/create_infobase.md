# create_infobase

Follow [SETUP](SETUP.md). Use a new disposable path and name; capture bindings before each call.

```text
create_infobase({"projectName":"TestConfiguration","mode":"create","infobaseFile":"C:/bases/McpBindingProbe","infobaseName":"McpBindingProbe","setDefault":false})
```

Require `boundToProject=true` and a usable `applicationId`; re-read applications and launch
configurations. The existing explicit default and RuntimeClient selections must remain.
Repeat with `mode="register"` for a different existing file infobase, and repeat the same
registration to check that the duplicate refusal also preserves bindings.

Create/register another test-owned base with `setDefault=true` while a default already exists.
Require the new application's ID as the explicit default after read-back; existing launch
selections must remain. Binding recovery errors must name unresolved entries even if creation
succeeded. Clean up using the returned `registrationId` or `applicationId` through
[delete_infobase](delete_infobase.md), with `deleteDatabaseFiles=false`.
