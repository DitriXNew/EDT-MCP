/**
 * Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later.
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.common.ChoiceParameter;
import com._1c.g5.v8.dt.metadata.common.ChoiceParameterLink;
import com._1c.g5.v8.dt.metadata.common.CommonFactory;
import com._1c.g5.v8.dt.metadata.common.LinkedValueChangeMode;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.google.gson.JsonArray;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The three metadata member properties use the ordinary introspector/prepared-change path. */
public final class MetadataMemberValues
{
    private MetadataMemberValues() { }

    public static boolean isFeature(EStructuralFeature feature)
    {
        if (feature == null) return false;
        boolean metadata = feature.getEContainingClass().getEPackage()
            == com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.eINSTANCE;
        return metadata && java.util.Set.of("fillValue", "choiceParameters", "choiceParameterLinks")
            .contains(feature.getName());
    }

    public record Entry(String name, MetadataValueCodec.Plan value, String address,
        LinkedValueChangeMode mode) { }

    /** An immutable replacement plan. No BM EObject escapes preparation. */
    public record Plan(String property, MetadataValueCodec.Plan value, List<Entry> entries)
    {
        public String typeRefusal(TypeDescription type)
        {
            return value == null ? null : MetadataValueCodec.fillRefusal(value, type);
        }

        @SuppressWarnings("unchecked")
        public void apply(EObject target, EStructuralFeature feature, IBmTransaction tx)
        {
            requireOwned(target, property);
            if (value != null) { target.eSet(feature, value.create(tx)); return; }
            List<EObject> replacement = new ArrayList<>();
            for (Entry entry : entries)
            {
                if ("choiceParameters".equals(property))
                {
                    ChoiceParameter parameter = CommonFactory.eINSTANCE.createChoiceParameter();
                    parameter.setName(entry.name());
                    parameter.setValue(entry.value().create(tx));
                    replacement.add(parameter);
                }
                else
                {
                    // Recheck the target in this transaction, then store its stable derived-field URI.
                    Field field = resolveField(target, entry.address());
                    ChoiceParameterLink link = CommonFactory.eINSTANCE.createChoiceParameterLink();
                    link.setName(entry.name());
                    link.setField(FieldListSupport.fieldProxy(EcoreUtil.getURI(field)));
                    link.setChangeMode(entry.mode());
                    replacement.add(link);
                }
            }
            List<EObject> stored = (List<EObject>)target.eGet(feature);
            stored.clear();
            stored.addAll(replacement);
        }
    }

    public static void requireOwned(EObject target, String property)
    {
        EObject owner = target;
        while (owner != null && !(owner instanceof MdObject)) owner = owner.eContainer();
        if (ExtensionServices.isAdopted(owner))
            throw new IllegalArgumentException("Cannot change '" + property + "' on adopted '"
                + MetadataNodeResolver.addressOf((MdObject)owner)
                + "': EDT does not persist these member values in the extension. "
                + "Change the member in the base configuration, or create an extension-owned member.");
    }

    public static TypeDescription typeOf(EObject member)
    {
        if (member instanceof BasicFeature) return ((BasicFeature)member).getTypeDescription();
        if (member instanceof Constant) return ((Constant)member).getTypeDescription();
        EStructuralFeature type = member.eClass().getEStructuralFeature("type");
        Object value = type == null ? null : member.eGet(type);
        return value instanceof TypeDescription ? (TypeDescription)value : null;
    }

    public static Plan prepare(MetadataScope scope, EObject target, String property, JsonElement raw)
    {
        requireOwned(target, property);
        if ("fillValue".equals(property))
        {
            MetadataValueCodec.Plan value = MetadataValueCodec.parse(raw, property,
                ref -> MetadataValueCodec.resolve(scope, ref));
            if ("fixedArray".equals(value.kind()))
                throw new IllegalArgumentException(MetadataValueCodec.fillRefusal(value, null));
            return new Plan(property, value, List.of());
        }
        if (raw == null || !raw.isJsonArray())
            throw ScalarValueCodec.bad(property, String.valueOf(raw), "Pass an array of objects; [] clears the list.");
        List<Entry> entries = new ArrayList<>();
        for (JsonElement item : raw.getAsJsonArray())
        {
            String path = property + '[' + entries.size() + ']';
            if (!item.isJsonObject()) throw ScalarValueCodec.bad(path, item.toString(), "Pass an object with 'name'.");
            JsonObject object = item.getAsJsonObject();
            boolean parameter = "choiceParameters".equals(property);
            String unknown = JsonUtils.unknownMemberError(object, path, parameter
                ? new String[] { "name", "value" } : new String[] { "name", "field", "changeMode" });
            if (unknown != null) throw new IllegalArgumentException(unknown);
            // Names are free strings, including choice-form parameters and Filter./Отбор. names.
            String name = ScalarValueCodec.string(object.get("name"), path + ".name");
            if (parameter)
                entries.add(new Entry(name, MetadataValueCodec.parse(object.get("value"), path + ".value",
                    ref -> MetadataValueCodec.resolve(scope, ref)), null, null));
            else
            {
                String requested = ScalarValueCodec.string(object.get("field"), path + ".field");
                Field field = resolveField(target, requested);
                String mode = ScalarValueCodec.string(object.get("changeMode"), path + ".changeMode");
                LinkedValueChangeMode changeMode = LinkedValueChangeMode.get(mode);
                if (changeMode == null)
                    throw ScalarValueCodec.bad(path + ".changeMode", mode, "Use Clear or DontChange.");
                entries.add(new Entry(name, null, FieldListSupport.addressOf(field), changeMode));
            }
        }
        return new Plan(property, null, List.copyOf(entries));
    }

    private static Field resolveField(EObject member, String requested)
    {
        String canonical = MetadataTypeUtils.toCanonicalEnglishFqn(requested);
        List<String> allowed = new ArrayList<>();
        for (Field field : FieldListSupport.choiceLinkTargets(member))
        {
            String address = FieldListSupport.addressOf(field);
            if (address == null) continue;
            allowed.add(address);
            int dot = address.lastIndexOf('.');
            String russianName = address.substring(0, dot + 1) + field.getNameRu();
            if (address.equalsIgnoreCase(canonical) || russianName.equalsIgnoreCase(canonical)) return field;
        }
        throw new IllegalArgumentException("choiceParameterLinks field '" + requested
            + "' does not resolve. Use a valid target: " + allowed
            + ". Use programmatic Names, not synonyms; retry after indexing if no targets are listed.");
    }

    /** Same JSON shape as the writer, including empty lists. Called inside the existing read boundary. */
    public static JsonElement render(String property, Object stored)
    {
        if ("fillValue".equals(property)) return MetadataValueCodec.render((Value)stored);
        JsonArray result = new JsonArray();
        for (Object item : (List<?>)stored)
        {
            JsonObject object = new JsonObject();
            if (item instanceof ChoiceParameter parameter)
            {
                object.addProperty("name", parameter.getName());
                object.add("value", MetadataValueCodec.render(parameter.getValue()));
            }
            else
            {
                ChoiceParameterLink link = (ChoiceParameterLink)item;
                String address = FieldListSupport.addressOf(link.getField());
                if (address == null) throw new IllegalStateException("Cannot read the stored choice-link field; retry after indexing.");
                object.addProperty("name", link.getName());
                object.addProperty("field", address);
                object.addProperty("changeMode", link.getChangeMode().getName());
            }
            result.add(object);
        }
        return result;
    }
}
