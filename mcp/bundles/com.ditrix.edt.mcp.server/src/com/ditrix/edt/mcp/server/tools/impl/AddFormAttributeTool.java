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
import org.eclipse.emf.common.util.EMap;
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
 * Tool to add a FORM attribute (the form's own data-model attribute, NOT an
 * attribute of the underlying metadata object) to an existing managed form, via a
 * BM write transaction, then persist the change to the form's {@code Form.form}
 * file on disk.
 * <p>
 * The form model is mutated entirely through EMF reflection ({@code EObject} /
 * {@code EClass} / {@code EFactory} / {@code eSet}) so the bundle needs no
 * compile-time dependency on the {@code com._1c.g5.v8.dt.form.model} package. This
 * mirrors how {@link GetFormStructureTool} READS the same form model: the form is
 * resolved from a {@code formPath}, the editable {@code Form} is reached via
 * {@code BasicForm.getForm()}, and its {@code attributes} / {@code name} /
 * {@code title} features are addressed by name.
 * <p>
 * The new attribute is created with the form model's DEFAULT (empty) type
 * description, exactly as the platform's own {@code FormObjectFactory} does; the
 * {@code type} parameter is reserved for a later increment (see the guide).
 */
public class AddFormAttributeTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "add_form_attribute"; //$NON-NLS-1$

    /** EReference name holding the {@code FormAttribute}s on a {@code Form}. */
    private static final String FEATURE_ATTRIBUTES = "attributes"; //$NON-NLS-1$
    /** EAttribute name carrying the programmatic name on a {@code NamedElement}. */
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$
    /** EReference name (EMap by language code) carrying the title on a {@code Titled}. */
    private static final String FEATURE_TITLE = "title"; //$NON-NLS-1$
    /** EReference name carrying the value type ({@code TypeDescription}) on a {@code FormAttribute}. */
    private static final String FEATURE_VALUE_TYPE = "valueType"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Add a FORM attribute (the form's own data-model attribute, NOT an attribute of the " + //$NON-NLS-1$
               "underlying object) to an existing managed form, persisted to disk. Use " + //$NON-NLS-1$
               "get_form_structure to inspect the form's attributes/items first; to add an OBJECT " + //$NON-NLS-1$
               "attribute use create_metadata. The attribute is created with a default type. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('add_form_attribute')."; //$NON-NLS-1$
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
                "Name for the new form attribute (required). A valid 1C identifier.", true) //$NON-NLS-1$
            .stringProperty("type", //$NON-NLS-1$
                "Optional. Reserved: this version always creates the form model's DEFAULT (empty) " + //$NON-NLS-1$
                "type; set the concrete type afterwards in EDT. See the guide.") //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Optional display title; written for 'language' or the config default language") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Optional language code for the title, e.g. 'ru'/'en' (default: config default)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the form attribute was added", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("formPath", "Normalized FQN of the form") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", "Programmatic name of the added form attribute") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("persisted", "Whether the change was saved to the form file on disk") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("synonym", "Display title written, when a synonym was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", "Language code the title was written for") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable confirmation message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# add_form_attribute\n\n" //$NON-NLS-1$
            + "Adds one **form attribute** - the form's OWN data-model attribute - to an existing " //$NON-NLS-1$
            + "managed form via a BM write transaction, then force-exports the form to its " //$NON-NLS-1$
            + "`Form.form` file on disk so the change survives a refresh / clean_project / EDT " //$NON-NLS-1$
            + "restart.\n\n" //$NON-NLS-1$
            + "## Form attribute vs object attribute\n\n" //$NON-NLS-1$
            + "A FORM attribute lives in the form's data model (what `get_form_structure` lists " //$NON-NLS-1$
            + "under `## Attributes`). It is NOT the same as an attribute of the underlying metadata " //$NON-NLS-1$
            + "object (Catalog/Document/...) - to add THAT, use `create_metadata` (e.g. " //$NON-NLS-1$
            + "`fqn: 'Catalog.Products.Attribute.Weight'`). This tool " //$NON-NLS-1$
            + "only touches the `Form.form` model.\n\n" //$NON-NLS-1$
            + "## When to use\n\n" //$NON-NLS-1$
            + "Use to extend a managed form's data model with a new attribute. Read the form first " //$NON-NLS-1$
            + "with `get_form_structure` to see existing attribute names (a duplicate name is " //$NON-NLS-1$
            + "rejected). Ordinary/legacy (non-managed) forms have no editable model and are " //$NON-NLS-1$
            + "rejected.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `formPath` (required) - the form FQN. Two accepted shapes:\n" //$NON-NLS-1$
            + "  - `MetadataType.ObjectName.Forms.FormName` (e.g. `Catalog.Products.Forms.ItemForm`).\n" //$NON-NLS-1$
            + "  - `CommonForm.FormName` (e.g. `CommonForm.MyForm`).\n" //$NON-NLS-1$
            + "  The object/form names are the programmatic Name, not the synonym; only the TYPE " //$NON-NLS-1$
            + "token (`Catalog`/`\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a`, ...) is dialect-aware.\n" //$NON-NLS-1$
            + "- `name` (required) - new form-attribute name. Must be a valid 1C identifier: start " //$NON-NLS-1$
            + "with a letter or `_`, then letters / digits / `_` only. Cyrillic letters are valid. " //$NON-NLS-1$
            + "A case-insensitive duplicate of an existing form attribute is rejected.\n" //$NON-NLS-1$
            + "- `type` (optional) - RESERVED. Building an arbitrary 1C type description reflectively " //$NON-NLS-1$
            + "is risky, so this version ALWAYS creates the form model's DEFAULT (empty) type, " //$NON-NLS-1$
            + "exactly as the platform's own form factory does for a fresh attribute. Set the " //$NON-NLS-1$
            + "concrete type afterwards in the EDT form editor. The parameter is accepted (and " //$NON-NLS-1$
            + "echoed in the message) but does NOT yet build a concrete type.\n" //$NON-NLS-1$
            + "- `synonym` (optional) - localized display title. Written for `language`, or the " //$NON-NLS-1$
            + "configuration default language when `language` is omitted.\n" //$NON-NLS-1$
            + "- `language` (optional) - language CODE for the title (`ru`, `en`, ...). Only " //$NON-NLS-1$
            + "consulted when `synonym` is supplied. Defaults to the configuration's first / default " //$NON-NLS-1$
            + "language code.\n\n" //$NON-NLS-1$
            + "## Bilingual (ru/en) notes\n\n" //$NON-NLS-1$
            + "The form attribute's title EMap is keyed by the language CODE (`ru`/`en`), never the " //$NON-NLS-1$
            + "language name. If you pass `language`, pass the code. The form is resolved by its " //$NON-NLS-1$
            + "programmatic Name; only the TYPE token in `formPath` is dialect-aware.\n\n" //$NON-NLS-1$
            + "## Transaction & persistence\n\n" //$NON-NLS-1$
            + "The mutation runs inside a BM write transaction: the form metadata object is re-fetched " //$NON-NLS-1$
            + "by its BM id inside the transaction, the editable `Form` is reached via its `getForm()` " //$NON-NLS-1$
            + "reference, the new attribute is created and appended to the form's `attributes` " //$NON-NLS-1$
            + "collection. After the write commits, the editable form (its own top object) is " //$NON-NLS-1$
            + "force-exported to its `Form.form` file on disk.\n\n" //$NON-NLS-1$
            + "## Result\n\n" //$NON-NLS-1$
            + "JSON with `formPath`, `name`, `persisted` (true once the form file was exported to " //$NON-NLS-1$
            + "disk), and a `message`. When a synonym was written, `synonym` and the resolved " //$NON-NLS-1$
            + "`language` code are echoed back.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "Minimal: `{projectName: 'MyProject', formPath: 'CommonForm.MyForm', name: 'Total'}`\n\n" //$NON-NLS-1$
            + "On an object form: `{projectName: 'MyProject', " //$NON-NLS-1$
            + "formPath: 'Catalog.Products.Forms.ItemForm', name: 'Total'}`\n\n" //$NON-NLS-1$
            + "With a localized title: `{projectName: 'MyProject', formPath: 'CommonForm.MyForm', " //$NON-NLS-1$
            + "name: 'Total', synonym: 'Total', language: 'en'}`\n\n" //$NON-NLS-1$
            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- `formPath` not found / not a managed form -> error pointing at get_form_structure; " //$NON-NLS-1$
            + "check `Type.Object.Forms.Name` / `CommonForm.Name` and that you used the Name, not " //$NON-NLS-1$
            + "the synonym.\n" //$NON-NLS-1$
            + "- The attribute is created with the DEFAULT (empty) type; set the concrete type in EDT.\n" //$NON-NLS-1$
            + "- If `persisted` is false the in-memory model changed but the form-file write did not " //$NON-NLS-1$
            + "complete - re-check before relying on it on disk."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        // 'type' is reserved (see guide): read so it is declared/parity-clean, but this
        // version always creates the form model's DEFAULT (empty) type description.
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', formPath: 'CommonForm.MyForm', name: 'Total'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "formPath", //$NON-NLS-1$
            ". Examples: 'CommonForm.MyForm', 'Catalog.Products.Forms.ItemForm'. " //$NON-NLS-1$
            + "Usage: {formPath: 'CommonForm.MyForm', name: 'Total'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "name", //$NON-NLS-1$
            ". Usage: {formPath: 'CommonForm.MyForm', name: 'Total'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        if (!isValidIdentifier(name))
        {
            return ToolResult.error("Invalid form attribute name '" + name + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "A name must start with a letter or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, formPath, name, type, synonym, language);
    }

    private String executeInternal(String projectName, String formPath, String name, String type,
        String synonym, String language)
    {
        // 'type' is reserved for a later increment; touch it so it is not flagged unused.
        if (type != null && !type.isEmpty())
        {
            Activator.logInfo("add_form_attribute: 'type' is reserved; creating default type for '" //$NON-NLS-1$
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

        // Resolve synonym language (only required when a synonym is supplied). Keyed by
        // the language CODE, never the language NAME (see MetadataLanguageUtils).
        final String titleLanguage;
        if (synonym != null && !synonym.isEmpty())
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
        // attribute reflectively, append it. The editable Form's OWN FQN is captured for
        // the post-commit export (it is a separate top object that serializes to
        // Form.form - the BasicForm->Form reference is non-containment).
        final String fixedName = name;
        String formFqn;
        try
        {
            formFqn = BmTransactions.<String>write(bmModel, "AddFormAttribute", (tx, pm) -> //$NON-NLS-1$
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

                if (hasAttribute(formModel, fixedName))
                {
                    throw new RuntimeException("Form attribute already exists: " + fixedName); //$NON-NLS-1$
                }

                EObject newAttribute = createFormAttribute(formModel);
                if (newAttribute == null)
                {
                    throw new RuntimeException("Cannot create a form attribute for this form model"); //$NON-NLS-1$
                }
                setStringFeature(newAttribute, FEATURE_NAME, fixedName);
                setDefaultValueType(newAttribute);
                if (titleLanguage != null)
                {
                    putTitle(newAttribute, titleLanguage, synonym);
                }
                addAttribute(formModel, newAttribute);

                // Capture the editable form's own FQN for the post-commit export.
                return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding form attribute", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add form attribute: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the editable form (its OWN top object) to its Form.form on disk. A bare
        // BM write only updates the in-memory model and enqueues the async export, so
        // without this the new attribute is lost on refresh / clean_project / EDT restart.
        // Runs AFTER the write commit. We persist ONLY when the form's own top-object FQN
        // was captured: exporting the BasicForm FQN instead would write the parent .mdo,
        // NOT Form.form (the BasicForm->Form reference is non-containment), so it would not
        // persist the attribute — fail closed (persisted=false) rather than report a
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
            result.put("synonym", synonym) //$NON-NLS-1$
                .put("language", titleLanguage); //$NON-NLS-1$
        }
        return result
            .put("message", "Form attribute '" + name + "' added successfully to " + normalizedFormPath) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    // ==================== form-model reflection (no compile-time dependency) ====================

    /**
     * Reads the editable form model ({@code com._1c.g5.v8.dt.form.model.Form}) from a
     * metadata form object ({@code BasicForm}) via its {@code getForm()} reference,
     * accessed reflectively to avoid a compile-time dependency on the form model
     * package. Only the managed-form model (which exposes the {@code attributes}
     * feature) is mutable here. Must be called inside the write transaction.
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
                && ((EObject)form).eClass().getEStructuralFeature(FEATURE_ATTRIBUTES) != null)
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
     * Creates a new {@code FormAttribute} instance using the form model's REAL
     * generated {@code EFactory}, reached entirely through EMF metadata (no new bundle
     * dependency): the {@code attributes} EReference's {@code EType} is the
     * {@code FormAttribute} {@code EClass}, and its {@code EPackage}'s factory creates
     * a properly-typed instance.
     *
     * @param formModel the editable form model EObject
     * @return the new {@code FormAttribute} EObject, or {@code null} if the feature /
     *         type cannot be resolved
     */
    private static EObject createFormAttribute(EObject formModel)
    {
        EStructuralFeature feature = formModel.eClass().getEStructuralFeature(FEATURE_ATTRIBUTES);
        if (!(feature instanceof EReference))
        {
            return null;
        }
        EClass attrClass = ((EReference)feature).getEReferenceType();
        if (attrClass == null || attrClass.getEPackage() == null)
        {
            return null;
        }
        return attrClass.getEPackage().getEFactoryInstance().create(attrClass);
    }

    /**
     * Sets the attribute's {@code valueType} to a fresh, EMPTY {@code TypeDescription}
     * (the form model's DEFAULT type) - exactly what the platform's own
     * {@code FormObjectFactory} does for a new attribute. The {@code TypeDescription}
     * EClass and its factory are reached via the {@code valueType} EReference's
     * {@code EType}, so no dependency on the mcore model is needed. Best-effort: if the
     * feature is absent the attribute is left with its default (already empty) type.
     *
     * @param attribute the new form attribute EObject
     */
    private static void setDefaultValueType(EObject attribute)
    {
        EStructuralFeature feature = attribute.eClass().getEStructuralFeature(FEATURE_VALUE_TYPE);
        if (!(feature instanceof EReference))
        {
            return;
        }
        EClass typeClass = ((EReference)feature).getEReferenceType();
        if (typeClass == null || typeClass.getEPackage() == null)
        {
            return;
        }
        EObject typeDescription = typeClass.getEPackage().getEFactoryInstance().create(typeClass);
        attribute.eSet(feature, typeDescription);
    }

    /**
     * Writes the title for the given language CODE into the object's {@code title}
     * EMap. The map is keyed by language code (e.g. {@code "en"}/{@code "ru"}), never
     * by the language name (CLAUDE.md don't #2). Best-effort: silently does nothing if
     * the feature is not a string-valued EMap.
     * <p>
     * Package-private and reused by {@link SetFormItemPropertyTool}: the {@code title}
     * EMap lives on the shared {@code Titled} supertype, so the same writer applies to a
     * form attribute, a form command and a form item alike. (A shared form-model writer
     * util is the eventual home; until then this is the single bilingual title setter.)
     */
    @SuppressWarnings("unchecked")
    static void putTitle(EObject object, String languageCode, String title)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(FEATURE_TITLE);
        if (feature == null)
        {
            return;
        }
        Object value = object.eGet(feature);
        if (value instanceof EMap<?, ?>)
        {
            ((EMap<String, String>)value).put(languageCode, title);
        }
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
     * Case-insensitive duplicate check against the form's existing {@code attributes}
     * by their programmatic {@code name}.
     */
    private static boolean hasAttribute(EObject formModel, String name)
    {
        for (EObject attribute : GetFormStructureTool.getReferenceList(formModel, FEATURE_ATTRIBUTES))
        {
            if (name.equalsIgnoreCase(GetFormStructureTool.nameOf(attribute)))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends the new attribute to the form's {@code attributes} EList (a containment
     * reference), accessed reflectively. Throws when the feature is missing/not a list.
     */
    @SuppressWarnings("unchecked")
    private static void addAttribute(EObject formModel, EObject attribute)
    {
        EStructuralFeature feature = formModel.eClass().getEStructuralFeature(FEATURE_ATTRIBUTES);
        if (feature == null || !feature.isMany())
        {
            throw new RuntimeException("Form model has no 'attributes' collection"); //$NON-NLS-1$
        }
        Object value = formModel.eGet(feature);
        if (value instanceof List<?>)
        {
            ((List<EObject>)value).add(attribute);
        }
        else
        {
            throw new RuntimeException("Form 'attributes' feature is not a list"); //$NON-NLS-1$
        }
    }

    /**
     * Checks that a name is a valid 1C identifier: starts with a letter or underscore
     * and contains only letters, digits and underscores.
     * <p>
     * Mirrors {@code CreateMetadataTool.isValidIdentifier}; replicated here because it
     * is not yet extracted into a shared util. They must stay in sync until the shared
     * helper exists.
     *
     * @param name the candidate attribute name
     * @return {@code true} if the name is a valid identifier
     */
    static boolean isValidIdentifier(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_')
            {
                return false;
            }
        }
        return true;
    }
}
