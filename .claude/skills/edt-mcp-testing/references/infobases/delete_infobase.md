# delete_infobase

Follow [SETUP](SETUP.md). Use two projects sharing a test FILE registration and an unrelated control.
Substitute the target application ID; keep database files.

```text
delete_infobase({"projectName":"TestConfiguration","applicationId":"<test-application-id>","deleteRegistration":true,"deleteDatabaseFiles":false})
delete_infobase({"projectName":"TestConfiguration","applicationId":"<test-application-id>","deleteRegistration":true,"deleteDatabaseFiles":false,"confirm":true})
```

Preview must change nothing. Confirm must return `action="deleted"` and the removed
`registrationId`. Re-read both projects: defaults and launch selections referencing the
removed UUID must be cleared, and `removedBindings` must match observed removal. Unrelated
bindings must remain; `unrestoredBindings` must be empty for success. Repeat with new test
targets selected by `infobaseName` and by `registrationId` plus the exact `infobaseName`.

With `deleteRegistration=false`, only the target project's application is removed; restore
any reset bindings in the other project. If global cleanup fails, the retained registration
must likewise leave the other project's bindings intact. Database files must remain in all cases.
