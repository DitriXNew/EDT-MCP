/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormChoiceListDesTimeValue;
import com._1c.g5.v8.dt.form.model.FormChoiceParameterLink;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.InputFieldExtInfo;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.mcore.FixedArrayValue;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.common.ChoiceParameter;
import com._1c.g5.v8.dt.metadata.common.CommonFactory;
import com._1c.g5.v8.dt.metadata.common.LinkedValueChangeMode;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Input-field list replacements; ValueSpecs stay owned by MetadataValueCodec. */
public final class FormChoiceParameters
{
    private FormChoiceParameters() { }

    public static boolean isFeature(EStructuralFeature feature)
    {
        return feature == FormPackage.Literals.INPUT_FIELD_EXT_INFO__CHOICE_PARAMETERS
            || feature == FormPackage.Literals.INPUT_FIELD_EXT_INFO__CHOICE_PARAMETER_LINKS;
    }

    public static boolean isProperty(String name)
    {
        return "choiceParameters".equalsIgnoreCase(name) || "choiceParameterLinks".equalsIgnoreCase(name);
    }

    public static void requireInputField(EObject item, String property)
    {
        if (FormElementWriter.resolveExtInfoEClass(item) != FormPackage.Literals.INPUT_FIELD_EXT_INFO)
            throw new IllegalArgumentException("Property '" + property + "' requires an InputField item; '"
                + FormStructureReader.nameOf(item) + "' is not an input field. "
                + "Use get_metadata_details to find a Field with type InputField.");
    }

    private static Form formOf(EObject object)
    {
        for (EObject current = object; current != null; current = current.eContainer())
            if (current instanceof Form form) return form;
        throw new IllegalArgumentException("Cannot find the item's form. Read the form with get_metadata_details and retry.");
    }

    private static void requireOwned(Form form, String property)
    {
        // EDT's own rule (FormExtensionExporterQualifier): a native form may carry a proxy baseForm.
        Form base = form.getBaseForm();
        if (ExtensionServices.isAdopted(form.getMdForm()) || (base != null && !base.eIsProxy()))
            throw new IllegalArgumentException("Cannot change '" + property + "' on adopted form '"
                + (form.getMdForm() == null ? "unknown" : MetadataNodeResolver.addressOf(form.getMdForm()))
                + "': EDT does not persist these input-field lists in the extension. "
                + "Change the base configuration, or create an extension-owned form.");
    }

    public record Entry(String name, MetadataValueCodec.Plan value, String dataPath, LinkedValueChangeMode mode) { }

    /** Immutable identities only: reference URIs in ValueSpec plans and canonical data-path strings. */
    public record Plan(String property, List<Entry> entries)
    {
        public void apply(EObject target, IBmTransaction tx)
        {
            Form form = formOf(target);
            requireOwned(form, property);
            if (!(target instanceof InputFieldExtInfo input))
                throw new IllegalArgumentException("Property '" + property + "' requires InputFieldExtInfo; read the field and retry.");
            // Build every replacement before clearing the stored list.
            if ("choiceParameters".equals(property))
            {
                List<ChoiceParameter> replacement = new ArrayList<>();
                for (Entry entry : entries)
                {
                    ChoiceParameter parameter = CommonFactory.eINSTANCE.createChoiceParameter();
                    parameter.setName(entry.name());
                    parameter.setValue(wrap(entry.value().create(tx)));
                    replacement.add(parameter);
                }
                input.getChoiceParameters().clear();
                input.getChoiceParameters().addAll(replacement);
            }
            else
            {
                List<FormChoiceParameterLink> replacement = new ArrayList<>();
                for (Entry entry : entries)
                {
                    FormChoiceParameterLink link = FormFactory.eINSTANCE.createFormChoiceParameterLink();
                    link.setName(entry.name());
                    link.setDatapath(dataPath(resolvePath(form, entry.dataPath())));
                    link.setChangeMode(entry.mode());
                    replacement.add(link);
                }
                input.getChoiceParameterLinks().clear();
                input.getChoiceParameterLinks().addAll(replacement);
            }
        }
    }

    public static Plan prepare(MetadataScope scope, EObject item, String property, JsonElement raw)
    {
        requireInputField(item, property);
        Form form = formOf(item);
        requireOwned(form, property);
        if (raw == null || !raw.isJsonArray())
            throw ScalarValueCodec.bad(property, String.valueOf(raw), "Pass an array of objects; [] clears the list.");
        List<Entry> entries = new ArrayList<>();
        for (JsonElement value : raw.getAsJsonArray())
        {
            String path = property + '[' + entries.size() + ']';
            if (!value.isJsonObject()) throw ScalarValueCodec.bad(path, value.toString(), "Pass an object with 'name'.");
            JsonObject object = value.getAsJsonObject();
            boolean parameter = "choiceParameters".equals(property);
            String unknown = JsonUtils.unknownMemberError(object, path, parameter
                ? new String[] { "name", "value" } : new String[] { "name", "dataPath", "changeMode" });
            if (unknown != null) throw new IllegalArgumentException(unknown);
            String name = ScalarValueCodec.string(object.get("name"), path + ".name");
            if (parameter)
                entries.add(new Entry(name, MetadataValueCodec.parse(object.get("value"), path + ".value",
                    ref -> MetadataValueCodec.resolve(scope, ref)), null, null));
            else
            {
                String requested = ScalarValueCodec.string(object.get("dataPath"), path + ".dataPath");
                String canonical = resolvePath(form, requested);
                String mode = ScalarValueCodec.string(object.get("changeMode"), path + ".changeMode");
                LinkedValueChangeMode changeMode = LinkedValueChangeMode.get(mode);
                if (changeMode == null) throw ScalarValueCodec.bad(path + ".changeMode", mode, "Use Clear or DontChange.");
                entries.add(new Entry(name, null, canonical, changeMode));
            }
        }
        return new Plan(property, List.copyOf(entries));
    }

    private static DataPath dataPath(String text)
    {
        DataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().addAll(Arrays.asList(text.split("\\.", -1)));
        return path;
    }

    /** The platform resolves both dialects; never interpret a form path as a metadata field FQN. */
    private static String resolvePath(Form form, String requested)
    {
        IDataSourceInfoAssociationService service = FormElementWriter.dataSourcesSource.get();
        if (service == null)
            throw new IllegalStateException("Cannot resolve choiceParameterLinks dataPath '" + requested
                + "': EDT's data tree service is unavailable. Retry after the form finishes loading.");
        DataPath path = dataPath(requested);
        PropertyInfo info = requested.isBlank() || path.getSegments().stream().anyMatch(String::isBlank)
            ? null : service.findPropertyInfo(form, path);
        if (info != null)
        {
            AbstractDataPath canonical = info.getDataPath(ScriptVariant.ENGLISH);
            if (canonical != null && !canonical.getSegments().isEmpty())
                return String.join(".", canonical.getSegments());
            throw new IllegalStateException("EDT cannot provide an English dataPath for '" + requested
                + "'. Retry after the form finishes loading.");
        }
        // Offer siblings under the deepest resolved prefix, or form roots for a bad first segment.
        List<PropertyInfo> valid = null;
        for (int count = path.getSegments().size() - 1; count > 0; count--)
        {
            DataPath prefix = dataPath(String.join(".", path.getSegments().subList(0, count)));
            PropertyInfo parent = service.findPropertyInfo(form, prefix);
            if (parent != null) { valid = parent.getPropertyInfos(); break; }
        }
        if (valid == null) valid = form.propertyInfos();
        TreeSet<String> allowed = new TreeSet<>();
        for (PropertyInfo child : valid)
        {
            AbstractDataPath offered = child.getDataPath(ScriptVariant.ENGLISH);
            if (offered != null && !offered.getSegments().isEmpty())
                allowed.add(String.join(".", offered.getSegments()));
        }
        throw new IllegalArgumentException("choiceParameterLinks dataPath '" + requested
            + "' does not resolve. Use a valid form data path at this level: " + allowed
            + ". Use programmatic Names, not synonyms; retry after indexing if no paths are listed.");
    }

    /** Matches FormModelFactory.wrapIntoDesTimeValue, including array elements. */
    private static Value wrap(Value value)
    {
        if (value instanceof FixedArrayValue array)
        {
            List<Value> children = new ArrayList<>(array.getValues());
            array.getValues().clear();
            for (Value child : children) array.getValues().add(wrap(child));
        }
        FormChoiceListDesTimeValue wrapper = FormFactory.eINSTANCE.createFormChoiceListDesTimeValue();
        wrapper.setValue(value);
        return wrapper;
    }

    /** Reads through wrappers in place, retaining the original reference context for symbolic names. */
    public static JsonArray render(Object stored)
    {
        JsonArray result = new JsonArray();
        for (Object item : (List<?>)stored)
        {
            JsonObject object = new JsonObject();
            if (item instanceof ChoiceParameter parameter)
            {
                object.addProperty("name", parameter.getName());
                object.add("value", MetadataValueCodec.render(parameter.getValue(), value -> {
                    while (value instanceof FormChoiceListDesTimeValue wrapper) value = wrapper.getValue();
                    return value;
                }));
            }
            else
            {
                FormChoiceParameterLink link = (FormChoiceParameterLink)item;
                List<String> segments = UseAlwaysSupport.segmentsOf(link.getDatapath());
                if (segments.isEmpty())
                    throw new IllegalStateException("Cannot read stored choiceParameterLinks dataPath; read the form and retry after indexing.");
                object.addProperty("name", link.getName());
                object.addProperty("dataPath", String.join(".", segments));
                object.addProperty("changeMode", link.getChangeMode().getName());
            }
            result.add(object);
        }
        return result;
    }
}
