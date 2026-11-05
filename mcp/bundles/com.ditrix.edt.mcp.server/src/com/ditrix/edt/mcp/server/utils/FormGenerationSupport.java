/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.form.generator.FormFieldInfo;
import com._1c.g5.v8.dt.form.generator.FormType;
import com._1c.g5.v8.dt.form.generator.IFormFieldGenerator;
import com._1c.g5.v8.dt.form.generator.IFormGenerator;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.RegisterWriteMode;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Wizard generation and purpose-specific default references. Call model operations in a BM transaction. */
public final class FormGenerationSupport
{
    private FormGenerationSupport()
    {
    }

    public static FormType resolveType(String requested, String ownerEnglishType)
    {
        if (requested == null)
        {
            return ownerEnglishType.endsWith("Register") ? FormType.LIST : FormType.OBJECT;
        }
        try
        {
            FormType type = FormType.valueOf(requested.toUpperCase(Locale.ROOT));
            switch (type)
            {
                case OBJECT: case LIST: case CHOICE: case RECORD: case RECORD_SET:
                    return type;
                default:
                    break;
            }
        }
        catch (IllegalArgumentException e)
        {
            // Use the same actionable error for unknown and unexposed platform form types.
        }
        throw invalid("Unknown formType '" + requested
            + "'. Use OBJECT, LIST, CHOICE, RECORD or RECORD_SET.");
    }

    public static void validate(MdObject owner, String ownerEnglishType, FormType type,
        boolean generateContent, boolean setAsDefault, List<String> objectFields)
    {
        boolean supported = type == FormType.OBJECT
            ? MetadataTypeBuilder.hasObjectFormMainAttribute(ownerEnglishType)
            : type == FormType.RECORD_SET ? ownerEnglishType.endsWith("Register")
                : defaultFeature(owner, type) != null;
        if (type == FormType.RECORD && owner instanceof InformationRegister
            && ((InformationRegister)owner).getWriteMode() == RegisterWriteMode.RECORDER_SUBORDINATE)
        {
            supported = false;
        }
        if (!supported && (generateContent || setAsDefault || type != FormType.OBJECT))
        {
            throw invalid("formType '" + type + "' is not supported for " + ownerEnglishType
                + ". Choose a matching formType (registers: LIST; independent InformationRegister: RECORD),"
                + " or omit formType and set generateContent=false and setAsDefault=false for an empty form.");
        }
        if (objectFields != null && type != FormType.OBJECT)
        {
            throw invalid("objectFields applies only to formType OBJECT. Omit objectFields for " + type
                + " to use the designer's selected fields.");
        }
        if (setAsDefault && type != FormType.OBJECT && defaultFeature(owner, type) == null)
        {
            throw invalid("formType '" + type + "' has no default-form slot on " + ownerEnglishType
                + ". Set setAsDefault=false or choose LIST.");
        }
    }

    static EStructuralFeature defaultFeature(MdObject owner, FormType type)
    {
        // These are MD model properties, also used by the designer wizard's default-form references.
        String property;
        switch (type)
        {
            case LIST: property = "defaultListForm"; break;
            case CHOICE: property = "defaultChoiceForm"; break;
            case RECORD: property = "defaultRecordForm"; break;
            default: return null;
        }
        return owner.eClass().getEStructuralFeature(property);
    }

    static void setDefault(MdObject owner, BasicForm form, FormType type)
    {
        EStructuralFeature feature = defaultFeature(owner, type);
        if (feature == null || !feature.getEType().isInstance(form))
        {
            throw invalid("No matching default-form slot for formType " + type + " on "
                + owner.eClass().getName() + ". Set setAsDefault=false.");
        }
        owner.eSet(feature, form);
    }

    public static EObject generate(MdObject owner, BasicForm form, FormType type, IV8Project project,
        String languageCode)
    {
        IFormGenerator generator = ServiceAccess.get(IFormGenerator.class);
        IFormFieldGenerator fields = ServiceAccess.get(IFormFieldGenerator.class);
        if (generator == null || fields == null)
        {
            throw invalid("EDT form generator is unavailable. Enable EDT form services and retry,"
                + " or set generateContent=false to create an empty form.");
        }
        return generate(owner, form, type, project, languageCode, generator, fields);
    }

    static EObject generate(MdObject owner, BasicForm form, FormType type, IV8Project project,
        String languageCode, IFormGenerator generator, Object fields)
    {
        Version version = project.getVersion();
        Version compatibility = project.getCompatibilityMode() == null
            ? version : Version.parseCompatibilityMode(project.getCompatibilityMode());
        FormFieldInfo root;
        try
        {
            Class<?> api = fields instanceof IFormFieldGenerator ? IFormFieldGenerator.class : fields.getClass();
            Method method = ReflectionUtils.publicMethodOrNull(api, "getFormGeneratorFields",
                MdObject.class, FormType.class, ScriptVariant.class, Version.class, Version.class);
            if (method != null)
            {
                root = (FormFieldInfo)ReflectionUtils.invoke(method, fields, owner, type,
                    project.getScriptVariant(), version, compatibility);
            }
            else
            {
                method = api.getMethod("getFormGeneratorFields", MdObject.class, FormType.class,
                    ScriptVariant.class, Version.class);
                root = (FormFieldInfo)ReflectionUtils.invoke(method, fields, owner, type,
                    project.getScriptVariant(), version);
            }
        }
        catch (Exception | LinkageError e) // NOSONAR preserve the actionable field-generation refusal on incompatible EDT APIs
        {
            Activator.logError("EDT field generation failed for formType " + type, e);
            FormValidationException failure = invalid("EDT field generation failed for formType " + type
                + ". Check the EDT Error Log and retry, or set generateContent=false to create an empty form.");
            failure.initCause(e);
            throw failure;
        }
        EObject content = generator.generateForm(owner, form, type, project.getScriptVariant(), languageCode,
            version, root, 1, project.getInterfaceCompatibilityMode());
        if (content == null)
        {
            throw invalid("EDT generated no content for formType " + type
                + ". Retry with a supported formType or set generateContent=false.");
        }
        return content;
    }

    private static FormValidationException invalid(String message)
    {
        return new FormValidationException(ToolResult.error(message).toJson());
    }
}
