/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to read the editable model tree of a 1C managed form: the nested items
 * (groups / fields / tables), the form attributes, and the form commands.
 * <p>
 * Unlike {@code get_form_layout_snapshot} (rendered WYSIWYG bounds) and
 * {@code get_form_screenshot} (a PNG), this tool reads the <b>editable model</b>
 * straight from the BM business model inside a read transaction — it never opens
 * the form editor. The element {@code name} is the stable, programmatic id that a
 * future form-write tool will use to address an element (the {@code id} integer is
 * also reported). Titles/synonyms are read by the configuration language CODE (the
 * synonym map is keyed by code, never by the language name), and are purely
 * informational — they are never the addressing id.
 * <p>
 * The form model is read entirely through EMF reflection ({@code EObject} /
 * {@code eGet}) so the bundle does not need a compile-time dependency on the
 * {@code com._1c.g5.v8.dt.form.model} package.
 */
public class GetFormStructureTool implements IMcpTool
{
    public static final String NAME = "get_form_structure"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read the editable model tree of a managed form (nested items, attributes, commands) " + //$NON-NLS-1$
            "with each item's programmatic name and integer id. Use to get the addressing ids that " + //$NON-NLS-1$
            "form-write tools need, NOT the rendered WYSIWYG layout (use get_form_layout_snapshot for " + //$NON-NLS-1$
            "bounds). Reads the BM model directly; does not open the form editor. " + //$NON-NLS-1$
            "Full parameters and examples: call get_tool_guide('get_form_structure')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Form FQN: 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName' " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products.Forms.ItemForm'). Names are the programmatic Name, not the synonym.", //$NON-NLS-1$
                true)
            .stringProperty("language", //$NON-NLS-1$
                "Language code for titles, e.g. 'en'/'ru'. Defaults to the configuration language.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# get_form_structure\n\n" //$NON-NLS-1$
            + "Reads the **editable model tree** of a 1C managed form straight from the BM " //$NON-NLS-1$
            + "business model inside a read transaction. Returns, as Markdown: the nested " //$NON-NLS-1$
            + "**items** tree (groups / fields / tables), the form **attributes** " //$NON-NLS-1$
            + "(name + value type), and the form **commands** (name + title). The form editor " //$NON-NLS-1$
            + "is never opened.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- Get the stable item/attribute ids a form-write tool will address.\n" //$NON-NLS-1$
            + "- Inspect a form's structure (containment, attribute types, commands) " //$NON-NLS-1$
            + "without rendering it.\n\n" //$NON-NLS-1$

            + "## When NOT to use\n" //$NON-NLS-1$
            + "This is the editable MODEL, not the rendered layout. For on-screen WYSIWYG " //$NON-NLS-1$
            + "bounds use `get_form_layout_snapshot`; for a PNG use `get_form_screenshot`. " //$NON-NLS-1$
            + "Ordinary/legacy (non-managed) forms have no editable model and return an error.\n\n" //$NON-NLS-1$

            + "## Parameters\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `formPath` (required) - the form FQN. Two accepted shapes:\n" //$NON-NLS-1$
            + "  - `MetadataType.ObjectName.Forms.FormName` " //$NON-NLS-1$
            + "(e.g. `Catalog.Products.Forms.ItemForm`, `Document.SalesOrder.Forms.DocumentForm`).\n" //$NON-NLS-1$
            + "  - `CommonForm.FormName` (e.g. `CommonForm.MyForm`).\n" //$NON-NLS-1$
            + "- `language` - language code for titles (e.g. `en`, `ru`); defaults to the " //$NON-NLS-1$
            + "configuration language when omitted.\n\n" //$NON-NLS-1$

            + "## Naming: programmatic Name vs synonym (ru/en)\n" //$NON-NLS-1$
            + "- The object and form names in `formPath` are the **programmatic Name** " //$NON-NLS-1$
            + "(resolved case-insensitively), never the synonym / display name.\n" //$NON-NLS-1$
            + "- Only the metadata **TYPE token** is bilingual: " //$NON-NLS-1$
            + "`Catalog`/`\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A`, " //$NON-NLS-1$
            + "`Document`/`\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442`, etc. all resolve.\n" //$NON-NLS-1$
            + "- Item/command **titles** are read by the language CODE from the synonym map " //$NON-NLS-1$
            + "(keyed by code, never by the language name) and are purely informational - " //$NON-NLS-1$
            + "they are never the addressing id.\n\n" //$NON-NLS-1$

            + "## Output\n" //$NON-NLS-1$
            + "- `## Items` - a nested outline; each line is " //$NON-NLS-1$
            + "`- Name (type: EClassName, id: N, title: ...)`. The **Name** is the stable, " //$NON-NLS-1$
            + "programmatic addressing id; the integer **id** is also reported; the title is " //$NON-NLS-1$
            + "shown only when present. Indentation reflects containment.\n" //$NON-NLS-1$
            + "- `## Attributes` - a table of attribute Name + value Type.\n" //$NON-NLS-1$
            + "- `## Commands` - a table of command Name + Title.\n" //$NON-NLS-1$
            + "Empty sections render as `_(no items)_` / `_(no attributes)_` / " //$NON-NLS-1$
            + "`_(no commands)_`.\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Object form: `{projectName, formPath: \"Catalog.Products.Forms.ItemForm\"}`.\n" //$NON-NLS-1$
            + "- Common form: `{projectName, formPath: \"CommonForm.MyForm\"}`.\n" //$NON-NLS-1$
            + "- Russian type token + Russian titles: " //$NON-NLS-1$
            + "`{projectName, formPath: \"\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Products.Forms.ItemForm\", language: \"ru\"}`.\n\n" //$NON-NLS-1$

            + "## Gotchas\n" //$NON-NLS-1$
            + "- `projectName` is required whenever `formPath` is given.\n" //$NON-NLS-1$
            + "- A 3- or 5-part path, or a 2-part path whose type is not `CommonForm`, is " //$NON-NLS-1$
            + "rejected; the `Forms` keyword must be the third segment of a 4-part path.\n" //$NON-NLS-1$
            + "- A form that is empty, ordinary/legacy, or not yet built has no editable " //$NON-NLS-1$
            + "model and returns an error.\n" //$NON-NLS-1$
            + "- An unnamed item is surfaced as `(unnamed)` rather than dropped.\n"; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String formPath = params.get("formPath"); //$NON-NLS-1$
        if (formPath != null && !formPath.isEmpty())
        {
            String[] parts = formPath.split("\\."); //$NON-NLS-1$
            if (parts.length > 0)
            {
                return "form-structure-" + parts[parts.length - 1] + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return "form-structure.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        if (formPath == null || formPath.isEmpty())
        {
            return ToolResult.error("formPath is required (e.g. 'Catalog.Products.Forms.ItemForm' " + //$NON-NLS-1$
                "or 'CommonForm.MyForm')").toJson(); //$NON-NLS-1$
        }
        // Mirror the sibling form tools: projectName is required when formPath is given.
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required when formPath is specified").toJson(); //$NON-NLS-1$
        }

        try
        {
            return readFormStructure(projectName, formPath, language);
        }
        catch (Exception e)
        {
            Activator.logError("Error reading form structure", e); //$NON-NLS-1$
            return ToolResult.error("Failed to read form structure: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private String readFormStructure(String projectName, String formPath, String language)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
        }
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("Could not get configuration for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Resolve the metadata form object (a BasicForm) from the FQN path. The
        // metadata TYPE token may be English or Russian; the object/form names are
        // the programmatic Name (resolved case-insensitively), never the synonym.
        MdObject mdForm = FormStructureReader.resolveMdForm(config, formPath);
        if (mdForm == null)
        {
            return ToolResult.error("Form not found: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName'. " + //$NON-NLS-1$
                "Names are the programmatic Name, not the synonym.").toJson(); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form object is not a BM object: " + formPath).toJson(); //$NON-NLS-1$
        }

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        final long mdFormBmId = ((IBmObject)mdForm).bmGetId();
        final String resolvedLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);
        final String normalizedFormPath = MetadataTypeUtils.normalizeFqn(formPath);

        // Touch the model ONLY inside a READ transaction boundary (CLAUDE.md don't #1):
        // re-fetch the BasicForm by its BM id, read its editable Form model, and render
        // the whole structure to a string before the transaction closes (the EObjects
        // must not escape the read task).
        String rendered = BmTransactions.read(bmModel, "GetFormStructure", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txMdForm = tx.getObjectById(mdFormBmId);
            if (txMdForm == null)
            {
                return null;
            }
            EObject formModel = FormElementWriter.getEditableForm(txMdForm);
            if (formModel == null)
            {
                return null;
            }
            return FormStructureReader.render(normalizedFormPath, formModel, resolvedLanguage);
        });

        if (rendered == null)
        {
            return ToolResult.error("Form has no editable model (the form may be empty, " + //$NON-NLS-1$
                "an ordinary/legacy form, or not yet built): " + normalizedFormPath).toJson(); //$NON-NLS-1$
        }
        return rendered;
    }

    // ==================== shared form-read delegates ====================
    // Thin pass-throughs to FormStructureReader so the sibling form tools (being removed in F4b)
    // keep compiling against the single source of truth. They die with this class.

    static MdObject resolveMdForm(Configuration config, String formPath)
    {
        return FormStructureReader.resolveMdForm(config, formPath);
    }

    static java.util.List<org.eclipse.emf.ecore.EObject> getReferenceList(
        org.eclipse.emf.ecore.EObject object, String featureName)
    {
        return FormStructureReader.getReferenceList(object, featureName);
    }

    static String nameOf(org.eclipse.emf.ecore.EObject object)
    {
        return FormStructureReader.nameOf(object);
    }
}
