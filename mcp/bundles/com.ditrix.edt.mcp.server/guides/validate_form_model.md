Structural check of ONE managed form, computed from the model as it stands right now. It changes
nothing: no repair, no export, no write transaction.

### When to use it

After creating or modifying a form, and before handing the form back to a human. A form can be
saved successfully and still be structurally broken — a field whose data path names an attribute
that was renamed, a button whose command was deleted, a handler with no procedure. Those surface
later, when someone opens the form.

The short loop this is built for:

```
modify_metadata … → validate_form_model → read findings → fix → validate again
```

### Parameter details

- `projectName` — the EDT project. Works on a configuration, on an extension and on an
  external-objects project.
- `formFqn` — `Type.Object.Form.FormName` (for example `Catalog.Goods.Form.ItemForm`) or
  `CommonForm.Name`. Bilingual: `Справочник.Товары.Форма.ФормаЭлемента` resolves to the same form.

### What it checks

| code | severity | what it means |
|---|---|---|
| `unnamed-member` | error | A member has no name, so nothing can address it and a second unnamed one is indistinguishable from it. |
| `missing-id` | error | An element has no id; it serializes without an `<id>` and EDT then refuses the form. |
| `multiple-main-attributes` | error | Two or more attributes are flagged `main`; a form carries one or none. |
| `orphan-form-ext-info` | warning | The form root has an ext-info but no main attribute, so it advertises events nothing backs. |
| `missing-auto-command-bar` | error | The form has no root auto command bar. |
| `invalid-auto-command-bar-id` | error | Its id is neither the `-1` sentinel nor a positive id. |
| `duplicate-name` | error | Two members of ONE namespace share a name, so the name addresses neither. |
| `duplicate-id` | error | Two members of one id space share an id. |
| `missing-data-path` / `empty-data-path` | error | A field or table displays nothing. |
| `unresolved-data-path` | error | The path starts with a name that is not a form attribute. |
| `missing-command-reference` | error | A button runs no command. |
| `unresolved-command-reference` | error | Its command is no longer in the model. |
| `invalid-extension-call-type` | error | An extension handler uses `ChangeAndValidate`, which intercepts a method rather than a form event. |
| `invalid-extended-tooltip-type` | error | An extended tooltip is typed anything but `Label`, which the platform rejects outright. |
| `missing-ext-info` / `stale-ext-info` | error | An element has no type-specific ext-info, or one that its kind does not call for. |
| `empty-command-action` | error | A command has an action but names no BSL procedure to run. |
| `extension-handler-without-call-type` | error | An extension handler does not say how it intercepts the base event. |
| `duplicate-handler-binding` | error | One event is bound twice (with the same call type), so the platform cannot say which to call. |
| `empty-handler-name` | error | A binding - an event handler, or one entry of a command action - names no BSL procedure. |
| `unresolved-event-reference` | error | A binding carries no event reference, or one that does not resolve. |

### What it deliberately does NOT check

- **Data path segments past the first.** The first segment must name a form attribute (nothing
  binds to a form parameter by data path);
  the rest walk that attribute's own type, and a wrong answer there would be worse than no answer.
- **The `-1` id on the root auto command bar.** That sentinel is what the platform wants: a `0` id
  serializes without an `<id>` element and EDT then flags the form itself.
- **Computed model objects.** The walk is over PERSISTED contents only. A form root also answers
  three computed containments — the whole BSL context, the 22 inferred standard commands and a
  global-command-source marker — none of which anyone authored; judging them would produce a list
  of defects nobody can fix.

### Reading the result

`valid` is true when no finding has severity `error`; warnings do not make a form invalid. Each finding carries a `path` in the same address vocabulary the other form tools use
(`Field.Description`, `Attribute.Object`, `(form)` for the root), and most of them paste straight
into `get_metadata_details` or `modify_metadata`.

Three kinds of finding carry a LOCATION rather than an address, and that is inherent: a table
addition and an additional column have no address in that vocabulary at all, and a duplicate name is
ambiguous by definition — which is exactly what the finding reports.

### Not the same tool as `get_project_errors`

`get_project_errors` reports the markers EDT computed earlier, over the whole project, including
BSL. This one computes form structure on demand, for one form, and is therefore current right after
an edit. Use both: they disagree by design.
