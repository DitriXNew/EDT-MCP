Read the top-level properties of a project's configuration (or extension): Name, Synonym, Comment, script variant, compatibility mode, default language, vendor, version and other configuration-level settings. Returns a YAML document.

## When to use
- You need the configuration's **compatibility mode**, **script variant**, or **default language** before reading or writing code/metadata - those settings drive how 1C type tokens and synonyms resolve in `ru` vs `en` elsewhere.
- A quick identity check of a project's configuration (name / version / vendor).

## Parameter details
- `projectName` - which project to read. **Omit to use the first configuration project** in the workspace.

## What you get
A YAML document of configuration properties: `name`, `synonym`, `comment`, `scriptVariant`, `compatibilityMode`, `defaultLanguage`, `vendor`, `version` and more. Multi-valued properties (e.g. the configured languages) are nested.

- `languages` - every language code (`ru`/`en`/...) the configuration declares. These are the only keys a localized value (synonym, form title, `NStr()` literal, ...) may be written under; a value stored under any other code is never displayed.
- `languagesInUse` - the subset of `languages` the configuration's OWN synonym is actually filled in for. Fill these in freely when writing localized values.
- `languagesNotInUse` - the declared codes NOT in that subset: nobody is translating into them yet. Writing under one of these is not an error (the code is declared, so the value will display), but treat it as a question for the user before doing it - it may be a single-language build, or a language this configuration does not really support yet.

## Notes & gotchas
- This describes the configuration itself, not the objects inside it: to list those use `get_metadata_objects`, and for one object's full properties use `get_metadata_details`.
- The default language reported here is the one whose `code` (`ru`/`en`) keys every object's synonym; keep it in mind when a synonym comes back empty for another language.
- `languagesInUse` / `languagesNotInUse` use the same "in use" rule as `modify_metadata`'s `localeUnusedInConfiguration` report (the configuration's own synonym), so the two tools never disagree about which languages are actually in play.
