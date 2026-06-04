/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to add a FORM command (a {@code FormCommand} in the form's {@code formCommands}
 * collection, what {@link GetFormStructureTool} lists under {@code ## Commands}) to an
 * existing managed form, via a BM write transaction, then persist the change to the
 * form's {@code Form.form} file on disk.
 * <p>
 * This is the form-COMMAND sibling of {@link AddFormAttributeTool} (the form-ATTRIBUTE
 * writer) and reuses its proven pattern EXACTLY: resolve the {@code BasicForm} via
 * {@link GetFormStructureTool#resolveMdForm}, capture its BM id, run all creation /
 * mutation inside a {@link BmTransactions#write} task, reach the editable {@code Form}
 * inside the task, create the {@code FormCommand} reflectively via the collection
 * EReference's {@code EType} factory, set {@code name} + {@code title}, append it, and
 * force-export the editable form's OWN FQN to disk AFTER the commit (failing closed if
 * the FQN was not captured). The {@code title} EMap writer is the shared
 * {@link AddFormAttributeTool#putTitle}.
 * <p>
 * Scope is deliberately narrow: it creates the command and sets its {@code name} and
 * (bilingual) {@code title}. It does NOT wire the command's ACTION/handler and does NOT
 * place a button on the form:
 * <ul>
 * <li>{@code action} (the handler) is a multi-level CONTAINMENT chain in the form model
 * ({@code FormCommand.action} -&gt; {@code CommandHandlerContainer} -&gt;
 * {@code FormCommandHandlerContainer.handler} -&gt; {@code CommandHandler.name}), NOT a
 * plain string feature that can be {@code eSet}. The platform's own form factory
 * ({@code FormObjectFactory.newFormCommand}) does not wire it either - it is attached
 * separately when the command is bound to code. So {@code action} is accepted but
 * RESERVED here (echoed in the message), exactly as {@link AddFormAttributeTool} treats
 * its {@code type} parameter; this tool does NOT create the handler method.</li>
 * <li>Binding the command to a visual button is the job of a future form-item tool, NOT
 * this one (see the guide).</li>
 * </ul>
 * The form model is mutated entirely through EMF reflection ({@code EObject} /
 * {@code EClass} / {@code EFactory} / {@code eSet}) so the bundle needs no compile-time
 * dependency on the {@code com._1c.g5.v8.dt.form.model} package.
 */
public class AddFormCommandTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "add_form_command"; //$NON-NLS-1$

    /** EReference name holding the {@code FormCommand}s on a {@code Form}. */
    private static final String FEATURE_FORM_COMMANDS = "formCommands"; //$NON-NLS-1$
    /** EAttribute name carrying the programmatic name on a {@code NamedElement}. */
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Add a FORM command (a FormCommand, what get_form_structure lists under ## Commands) " + //$NON-NLS-1$
               "to an existing managed form, persisted to disk. Use get_form_structure to inspect " + //$NON-NLS-1$
               "the form's commands first. Sets the command name + bilingual title; the action " + //$NON-NLS-1$
               "(handler method) is reserved and binding the command to a button is out of scope. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('add_form_command')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Form FQN: 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName' " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products.Forms.ItemForm'). Names are the programmatic Name, " + //$NON-NLS-1$
                "not the synonym; the TYPE token may be en/ru. (required)", true) //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Name for the new form command (required). A valid 1C identifier.", true) //$NON-NLS-1$
            .stringProperty("title", //$NON-NLS-1$
                "Optional display title; written for 'language' or the config default language") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Optional language code for the title, e.g. 'ru'/'en' (default: config default)") //$NON-NLS-1$
            .stringProperty("action", //$NON-NLS-1$
                "Optional. RESERVED: the command's action (handler) is a complex containment chain " + //$NON-NLS-1$
                "in the form model, not a plain string; this version does NOT wire it. Wire the " + //$NON-NLS-1$
                "handler afterwards in EDT. See the guide.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the form command was added", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("formPath", "Normalized FQN of the form") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", "Programmatic name of the added form command") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("persisted", "Whether the change was saved to the form file on disk") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("title", "Display title written, when a title was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", "Language code the title was written for") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable confirmation message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# add_form_command\n\n" //$NON-NLS-1$
            + "Adds one **form command** - a `FormCommand` in the form's `formCommands` collection " //$NON-NLS-1$
            + "(what `get_form_structure` lists under `## Commands`) - to an existing managed form " //$NON-NLS-1$
            + "via a BM write transaction, then force-exports the form to its `Form.form` file on " //$NON-NLS-1$
            + "disk so the change survives a refresh / clean_project / EDT restart.\n\n" //$NON-NLS-1$
            + "## What this does and does NOT do\n\n" //$NON-NLS-1$
            + "It creates the command and sets its `name` and (bilingual) `title`. It does NOT:\n" //$NON-NLS-1$
            + "- wire the command's **action/handler** - the `action` parameter is RESERVED (see " //$NON-NLS-1$
            + "below); this tool does NOT create the form-module handler method.\n" //$NON-NLS-1$
            + "- place a **button** for the command on the form. Binding the command to a visual " //$NON-NLS-1$
            + "button is a form-ITEM operation (a future `add_form_item`), OUT OF SCOPE here - the " //$NON-NLS-1$
            + "command exists in the model but no button references it yet.\n\n" //$NON-NLS-1$
            + "## When to use\n\n" //$NON-NLS-1$
            + "Use to add a command to a managed form's command list. Read the form first with " //$NON-NLS-1$
            + "`get_form_structure` to see existing command names (a duplicate name is rejected). " //$NON-NLS-1$
            + "Ordinary/legacy (non-managed) forms have no editable model and are rejected.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `formPath` (required) - the form FQN. Two accepted shapes:\n" //$NON-NLS-1$
            + "  - `MetadataType.ObjectName.Forms.FormName` (e.g. `Catalog.Products.Forms.ItemForm`).\n" //$NON-NLS-1$
            + "  - `CommonForm.FormName` (e.g. `CommonForm.MyForm`).\n" //$NON-NLS-1$
            + "  The object/form names are the programmatic Name, not the synonym; only the TYPE " //$NON-NLS-1$
            // The escape spells the Russian Catalog token (Spravochnik).
            + "token (`Catalog`/`\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a`, ...) is dialect-aware.\n" //$NON-NLS-1$
            + "- `name` (required) - new form-command name. Must be a valid 1C identifier: start " //$NON-NLS-1$
            + "with a letter or `_`, then letters / digits / `_` only. Cyrillic letters are valid. " //$NON-NLS-1$
            + "A case-insensitive duplicate of an existing form command is rejected.\n" //$NON-NLS-1$
            + "- `title` (optional) - localized display title. Written for `language`, or the " //$NON-NLS-1$
            + "configuration default language when `language` is omitted.\n" //$NON-NLS-1$
            + "- `language` (optional) - language CODE for the title (`ru`, `en`, ...). Only " //$NON-NLS-1$
            + "consulted when `title` is supplied. Defaults to the configuration's first / default " //$NON-NLS-1$
            + "language code.\n" //$NON-NLS-1$
            + "- `action` (optional) - RESERVED. A command's action (the handler it calls) is a " //$NON-NLS-1$
            + "multi-level CONTAINMENT chain in the form model " //$NON-NLS-1$
            + "(`FormCommand.action` -> `CommandHandlerContainer` -> handler -> `CommandHandler.name`), " //$NON-NLS-1$
            + "NOT a plain string feature; the platform wires it separately when the command is bound " //$NON-NLS-1$
            + "to code. This version does NOT wire it (and does NOT create the handler method). The " //$NON-NLS-1$
            + "parameter is accepted (and echoed in the message) but has no effect yet - wire the " //$NON-NLS-1$
            + "handler afterwards in the EDT form editor.\n\n" //$NON-NLS-1$
            + "## Bilingual (ru/en) notes\n\n" //$NON-NLS-1$
            + "The form command's title EMap is keyed by the language CODE (`ru`/`en`), never the " //$NON-NLS-1$
            + "language name. If you pass `language`, pass the code. The form is resolved by its " //$NON-NLS-1$
            + "programmatic Name; only the TYPE token in `formPath` is dialect-aware.\n\n" //$NON-NLS-1$
            + "## Transaction & persistence\n\n" //$NON-NLS-1$
            + "The mutation runs inside a BM write transaction: the form metadata object is re-fetched " //$NON-NLS-1$
            + "by its BM id inside the transaction, the editable `Form` is reached via its `getForm()` " //$NON-NLS-1$
            + "reference, the new command is created and appended to the form's `formCommands` " //$NON-NLS-1$
            + "collection. After the write commits, the editable form (its own top object) is " //$NON-NLS-1$
            + "force-exported to its `Form.form` file on disk.\n\n" //$NON-NLS-1$
            + "## Result\n\n" //$NON-NLS-1$
            + "JSON with `formPath`, `name`, `persisted` (true once the form file was exported to " //$NON-NLS-1$
            + "disk), and a `message`. When a title was written, `title` and the resolved " //$NON-NLS-1$
            + "`language` code are echoed back.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "Minimal: `{projectName: 'MyProject', formPath: 'CommonForm.MyForm', name: 'Refresh'}`\n\n" //$NON-NLS-1$
            + "On an object form: `{projectName: 'MyProject', " //$NON-NLS-1$
            + "formPath: 'Catalog.Products.Forms.ItemForm', name: 'Refresh'}`\n\n" //$NON-NLS-1$
            + "With a localized title: `{projectName: 'MyProject', formPath: 'CommonForm.MyForm', " //$NON-NLS-1$
            + "name: 'Refresh', title: 'Refresh', language: 'en'}`\n\n" //$NON-NLS-1$
            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- `formPath` not found / not a managed form -> error pointing at get_form_structure; " //$NON-NLS-1$
            + "check `Type.Object.Forms.Name` / `CommonForm.Name` and that you used the Name, not " //$NON-NLS-1$
            + "the synonym.\n" //$NON-NLS-1$
            + "- The command is created WITHOUT an action handler and WITHOUT a button; wire the " //$NON-NLS-1$
            + "handler and add the button afterwards in EDT.\n" //$NON-NLS-1$
            + "- If `persisted` is false the in-memory model changed but the form-file write did not " //$NON-NLS-1$
            + "complete - re-check before relying on it on disk."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        // 'action' is reserved (see guide): read so it is declared/parity-clean, but this
        // version does NOT wire the command's handler.
        String action = JsonUtils.extractStringArgument(params, "action"); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', formPath: 'CommonForm.MyForm', name: 'Refresh'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "formPath", //$NON-NLS-1$
            ". Examples: 'CommonForm.MyForm', 'Catalog.Products.Forms.ItemForm'. " //$NON-NLS-1$
            + "Usage: {formPath: 'CommonForm.MyForm', name: 'Refresh'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "name", //$NON-NLS-1$
            ". Usage: {formPath: 'CommonForm.MyForm', name: 'Refresh'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        if (!AddFormAttributeTool.isValidIdentifier(name))
        {
            return ToolResult.error("Invalid form command name '" + name + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "A name must start with a letter or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, formPath, name, title, language, action);
    }

    private String executeInternal(String projectName, String formPath, String name, String title,
        String language, String action)
    {
        // 'action' is reserved for a later increment; touch it so it is not flagged unused.
        if (action != null && !action.isEmpty())
        {
            Activator.logInfo("add_form_command: 'action' is reserved; not wiring a handler for '" //$NON-NLS-1$
                + name + "'"); //$NON-NLS-1$
        }

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

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

        // Resolve the metadata form object (a BasicForm) from the FQN path. The TYPE
        // token may be en/ru; the object/form names are the programmatic Name. Reuses
        // get_form_structure's resolver so addressing stays identical across read/write.
        MdObject mdForm = GetFormStructureTool.resolveMdForm(config, formPath);
        if (mdForm == null)
        {
            return ToolResult.error("Form not found: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName'. " + //$NON-NLS-1$
                "Names are the programmatic Name, not the synonym. " + //$NON-NLS-1$
                "Use get_form_structure to inspect the form.").toJson(); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form object is not a BM object: " + formPath).toJson(); //$NON-NLS-1$
        }

        final long mdFormBmId = ((IBmObject)mdForm).bmGetId();
        final String normalizedFormPath = MetadataTypeUtils.normalizeFqn(formPath);

        // Resolve title language (only required when a title is supplied). Keyed by the
        // language CODE, never the language NAME (see MetadataLanguageUtils).
        final String titleLanguage;
        if (title != null && !title.isEmpty())
        {
            titleLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);
            if (titleLanguage == null)
            {
                return ToolResult.error("Cannot determine a language code for the title " + //$NON-NLS-1$
                    "in this configuration. Specify 'language' explicitly (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            titleLanguage = null;
        }

        // Execute the write task. The form-model MUTATION runs ONLY here (CLAUDE.md
        // don't #1): re-fetch the BasicForm by bmId, reach its editable Form, create the
        // command reflectively, append it. The editable Form's OWN FQN is captured for
        // the post-commit export (it is a separate top object that serializes to
        // Form.form - the BasicForm->Form reference is non-containment).
        final String fixedName = name;
        final String fixedTitle = title;
        String formFqn;
        try
        {
            formFqn = BmTransactions.<String>write(bmModel, "AddFormCommand", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txMdForm = tx.getObjectById(mdFormBmId);
                if (txMdForm == null)
                {
                    throw new RuntimeException("Form object not found in transaction"); //$NON-NLS-1$
                }
                EObject formModel = getEditableForm(txMdForm);
                if (formModel == null)
                {
                    throw new RuntimeException("Form has no editable model (the form may be empty, " //$NON-NLS-1$
                        + "an ordinary/legacy form, or not yet built)"); //$NON-NLS-1$
                }

                if (hasCommand(formModel, fixedName))
                {
                    throw new RuntimeException("Form command already exists: " + fixedName); //$NON-NLS-1$
                }

                EObject newCommand = createFormCommand(formModel);
                if (newCommand == null)
                {
                    throw new RuntimeException("Cannot create a form command for this form model"); //$NON-NLS-1$
                }
                setStringFeature(newCommand, FEATURE_NAME, fixedName);
                if (titleLanguage != null)
                {
                    AddFormAttributeTool.putTitle(newCommand, titleLanguage, fixedTitle);
                }
                addCommand(formModel, newCommand);

                // Capture the editable form's own FQN for the post-commit export.
                return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding form command", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add form command: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the editable form (its OWN top object) to its Form.form on disk. A bare
        // BM write only updates the in-memory model and enqueues the async export, so
        // without this the new command is lost on refresh / clean_project / EDT restart.
        // Runs AFTER the write commit. We persist ONLY when the form's own top-object FQN
        // was captured: exporting the BasicForm FQN instead would write the parent .mdo,
        // NOT Form.form (the BasicForm->Form reference is non-containment), so it would not
        // persist the command - fail closed (persisted=false) rather than report a
        // misleading success. (A resolved top object is always an IBmObject, so in practice
        // the FQN is always captured.)
        boolean persisted = formFqn != null && !formFqn.isEmpty()
            && BmTransactions.forceExportToDisk(project, formFqn);

        ToolResult result = ToolResult.success()
            .put("formPath", normalizedFormPath) //$NON-NLS-1$
            .put("name", name) //$NON-NLS-1$
            .put("persisted", persisted); //$NON-NLS-1$
        if (titleLanguage != null)
        {
            result.put("title", title) //$NON-NLS-1$
                .put("language", titleLanguage); //$NON-NLS-1$
        }
        return result
            .put("message", "Form command '" + name + "' added successfully to " + normalizedFormPath) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    // ==================== form-model reflection (no compile-time dependency) ====================

    /**
     * Reads the editable form model ({@code com._1c.g5.v8.dt.form.model.Form}) from a
     * metadata form object ({@code BasicForm}) via its {@code getForm()} reference,
     * accessed reflectively to avoid a compile-time dependency on the form model
     * package. Only the managed-form model (which exposes the {@code formCommands}
     * feature) is mutable here. Must be called inside the write transaction.
     * <p>
     * Mirrors the same helper in {@link AddFormAttributeTool} /
     * {@link SetFormItemPropertyTool} / {@link GetFormStructureTool} (private there);
     * replicated here until a shared form-model accessor is extracted.
     *
     * @param txMdForm the transaction-bound {@code BasicForm} EObject
     * @return the editable form model EObject, or {@code null} if absent / not managed
     */
    private static EObject getEditableForm(EObject txMdForm)
    {
        try
        {
            Method getForm = txMdForm.getClass().getMethod("getForm"); //$NON-NLS-1$
            Object form = getForm.invoke(txMdForm);
            if (form instanceof EObject
                && ((EObject)form).eClass().getEStructuralFeature(FEATURE_FORM_COMMANDS) != null)
            {
                return (EObject)form;
            }
        }
        catch (ReflectiveOperationException e)
        {
            // No getForm() / inaccessible - treated as "no editable model".
        }
        return null;
    }

    /**
     * Creates a new {@code FormCommand} instance using the form model's REAL generated
     * {@code EFactory}, reached entirely through EMF metadata (no new bundle
     * dependency): the {@code formCommands} EReference's {@code EType} is the
     * {@code FormCommand} {@code EClass}, and its {@code EPackage}'s factory creates a
     * properly-typed instance. Mirrors how {@link AddFormAttributeTool} creates a
     * {@code FormAttribute}.
     *
     * @param formModel the editable form model EObject
     * @return the new {@code FormCommand} EObject, or {@code null} if the feature /
     *         type cannot be resolved
     */
    private static EObject createFormCommand(EObject formModel)
    {
        EStructuralFeature feature = formModel.eClass().getEStructuralFeature(FEATURE_FORM_COMMANDS);
        if (!(feature instanceof EReference))
        {
            return null;
        }
        EClass commandClass = ((EReference)feature).getEReferenceType();
        if (commandClass == null || commandClass.getEPackage() == null)
        {
            return null;
        }
        return commandClass.getEPackage().getEFactoryInstance().create(commandClass);
    }

    private static void setStringFeature(EObject object, String featureName, String value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, value);
        }
    }

    /**
     * Case-insensitive duplicate check against the form's existing {@code formCommands}
     * by their programmatic {@code name}.
     */
    private static boolean hasCommand(EObject formModel, String name)
    {
        for (EObject command : GetFormStructureTool.getReferenceList(formModel, FEATURE_FORM_COMMANDS))
        {
            if (name.equalsIgnoreCase(GetFormStructureTool.nameOf(command)))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends the new command to the form's {@code formCommands} EList (a containment
     * reference), accessed reflectively. Throws when the feature is missing/not a list.
     */
    @SuppressWarnings("unchecked")
    private static void addCommand(EObject formModel, EObject command)
    {
        EStructuralFeature feature = formModel.eClass().getEStructuralFeature(FEATURE_FORM_COMMANDS);
        if (feature == null || !feature.isMany())
        {
            throw new RuntimeException("Form model has no 'formCommands' collection"); //$NON-NLS-1$
        }
        Object value = formModel.eGet(feature);
        if (value instanceof List<?>)
        {
            ((List<EObject>)value).add(command);
        }
        else
        {
            throw new RuntimeException("Form 'formCommands' feature is not a list"); //$NON-NLS-1$
        }
    }
}
