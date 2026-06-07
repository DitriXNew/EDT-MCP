# get_module_structure

Get structure of a BSL module: all procedures/functions with signatures, line numbers, regions, execution context (&AtServer, &AtClient), export flag, and parameters. responseFormat=concise (default) returns a leaner methods table (drops the verbose Parameters and Description columns; keeps type, name, export, context, lines, region); responseFormat=detailed returns the full table with signatures and doc-comments. Use detailed when you need parameter lists or descriptions. Use this for the structure of ONE module; to discover module paths across a project use list_modules.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |
| modulePath | yes | string | Path from src/ folder, e.g. 'CommonModules/MyModule/Module.bsl' (required) |
| includeVariables | — | boolean | Include module-level variable declarations. Default: false |
| includeComments | — | boolean | Include documentation comments for methods. Default: false |
| responseFormat | — | string (one of: concise, detailed) | Output verbosity. concise (default) = leaner methods table (drops Parameters and Description columns); detailed = full table with signatures and doc-comments. |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
