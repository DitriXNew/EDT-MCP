# infobase_extensions

List installed extensions and install, update or uninstall compiled `.cfe` files directly in an
application's infobase. Writes require preview, confirmation and the destructive-consent gate.

| Parameter | Required | Meaning |
|---|---|---|
| projectName | Yes | Configuration project owning the application |
| applicationId | Yes | Exact target from `get_applications` |
| action | No | `list` (default), `install`, `update`, `uninstall` |
| extensionName | For writes | Exact programmatic extension name |
| cfePath | Install/update | Absolute path to a non-empty `.cfe` on the EDT host |
| safeMode | No | Install defaults true; update preserves the installed value |
| protectionFromDangerousActions | No | Install defaults true; update preserves the installed value |
| expectedVersion | No | Exact version required in the final read-back |
| confirm | No | False previews; true requests consent and applies |
| timeoutSeconds | No | 10..600; default 120; cancellation does not imply rollback |

`list` returns actual names, versions, active state and runtime protection flags. Every successful
write independently rereads the inventory and returns `verification:"verified"`,
`extensionInstalled` and the observed `actualState`. A mismatched read-back is an error.
Versioned updates should supply `expectedVersion`; verification does not assert binary identity.

Call `get_tool_guide('infobase_extensions')` for examples and recovery details. Use a disposable
dev/test infobase and back it up first: uninstall can permanently delete extension data.
