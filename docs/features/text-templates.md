# Text templates

Read a TextDocument template with `get_metadata_details` using
`objectFqns:["DataProcessor.Invoices.Template.Contract"]` (or `CommonTemplate.Name`).
The result includes exact text and a stable `contentHash`. Edit it through
`modify_metadata.template`: `{oldText:"unique match", text:"replacement"}` performs
one literal search replacement by default; `mode:"append"` adds exact text;
`mode:"replace"` replaces all text and alone can create a missing Template.txt.
Replacing an existing file requires `expectedHash`, `expectedSource`, or
`overwrite:true`; supplied guards are always checked. Existing text-only payloads
must add the explicit replacement mode and a guard or overwrite. EDT encoding,
BOM and line endings are preserved, with vendor support enforced on the template.
See the `modify_metadata` tool guide for the payload rules.

