/**
 * Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later.
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.v8.dt.core.naming.ISymbolicNameService;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.FixedArrayValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NullValue;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.UndefinedValue;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil;
import com.google.gson.JsonArray;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Metadata ValueSpec parsing, transaction-safe reference plans and feedable read-back. */
public final class MetadataValueCodec
{
    private MetadataValueCodec() { }

    /** Only immutable identities cross the read/write boundary, never a referenced EObject. */
    public record Reference(String canonical, URI uri, String typeName) { }

    public record Plan(String kind, String scalarJson, Reference reference, List<Plan> values)
    {
        /** Called in the active write transaction, including for every array element. */
        public Value create(IBmTransaction tx)
        {
            if (reference != null)
            {
                EObject fresh = tx.getObjectByUri(reference.uri());
                if (fresh == null || fresh.eIsProxy())
                    throw new IllegalArgumentException("Reference '" + reference.canonical()
                        + "' no longer resolves. Read the metadata and retry with an existing reference.");
                ReferenceValue value = McoreFactory.eINSTANCE.createReferenceValue();
                value.setValue(fresh);
                return value;
            }
            if ("fixedArray".equals(kind))
            {
                FixedArrayValue array = McoreFactory.eINSTANCE.createFixedArrayValue();
                for (Plan item : values) array.getValues().add(item.create(tx));
                return array;
            }
            return ScalarValueCodec.parse(JsonParser.parseString(scalarJson).getAsJsonObject(), "value");
        }
    }

    public static Plan parse(JsonElement raw, String path, Function<String, Reference> resolver)
    {
        if (raw == null || !raw.isJsonObject())
            throw ScalarValueCodec.bad(path, String.valueOf(raw), "Pass a ValueSpec object with 'kind'.");
        JsonObject object = raw.getAsJsonObject();
        String kind = ScalarValueCodec.string(object.get("kind"), path + ".kind");
        if (ScalarValueCodec.supports(kind))
        {
            ScalarValueCodec.parse(object, path);
            return new Plan(kind, object.toString(), null, List.of());
        }
        String unknown = JsonUtils.unknownMemberError(object, path,
            "ref".equals(kind) ? new String[] { "kind", "ref" } : new String[] { "kind", "values" });
        if (unknown != null) throw new IllegalArgumentException(unknown);
        if ("ref".equals(kind))
        {
            String ref = ScalarValueCodec.string(object.get("ref"), path + ".ref");
            Reference reference = resolver.apply(ref);
            if (reference == null)
                throw ScalarValueCodec.bad(path, ref,
                    "Use an existing Enum.X.EnumValue.Y, Catalog.X.<Predefined> or Catalog.X.EmptyRef; "
                    + "TYPE/KIND tokens may be Russian. Use programmatic Names, not synonyms.");
            return new Plan(kind, null, reference, List.of());
        }
        if ("fixedArray".equals(kind))
        {
            JsonElement values = object.get("values");
            if (values == null || !values.isJsonArray())
                throw ScalarValueCodec.bad(path, String.valueOf(values), "Pass a JSON array in 'values'.");
            List<Plan> result = new ArrayList<>();
            for (JsonElement value : values.getAsJsonArray())
                result.add(parse(value, path + ".values[" + result.size() + "]", resolver));
            return new Plan(kind, null, null, List.copyOf(result));
        }
        throw ScalarValueCodec.bad(path, kind,
            "Use string, number, boolean, date, null, undefined, ref or fixedArray.");
    }

    /** Resolves metadata owners and enum members through the shared bilingual resolver. */
    public static Reference resolve(MetadataScope scope, String requested)
    {
        String canonical = MetadataTypeUtils.normalizeFqn(requested);
        String[] parts = canonical.split("\\.", -1);
        EObject target = null;
        MdObject owner = null;
        if (parts.length == 4)
        {
            MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(scope, canonical);
            if (node != null && node.object instanceof EnumValue)
            {
                target = node.object;
                owner = (MdObject)target.eContainer();
                canonical = MetadataNodeResolver.addressOf(node.object);
            }
        }
        else if (parts.length == 3)
        {
            MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(scope, parts[0] + '.' + parts[1]);
            if (node == null) return null;
            owner = node.object;
            if ("EmptyRef".equalsIgnoreCase(parts[2]) || "ПустаяСсылка".equalsIgnoreCase(parts[2]))
            {
                EObject produced = MdClassUtil.getProducedTypes(owner);
                target = objectFeature(objectFeature(produced, "refType"), "emptyRef");
                canonical = MetadataNodeResolver.addressOf(owner) + ".EmptyRef";
            }
            else
            {
                PredefinedItem item = PredefinedWriter.findByName(owner, parts[2]);
                target = item;
                if (item != null) canonical = MetadataNodeResolver.addressOf(owner) + '.' + item.getName();
            }
        }
        if (target == null || owner == null || target.eIsProxy()) return null;
        String ownerAddress = MetadataNodeResolver.addressOf(owner);
        int dot = ownerAddress.indexOf('.');
        String type = ownerAddress.substring(0, dot) + "Ref" + ownerAddress.substring(dot);
        return new Reference(canonical, EcoreUtil.getURI(target), type);
    }

    private static EObject objectFeature(EObject object, String name)
    {
        EStructuralFeature feature = object == null ? null : object.eClass().getEStructuralFeature(name);
        Object value = feature == null ? null : object.eGet(feature);
        return value instanceof EObject ? (EObject)value : null;
    }

    /** Undefined is the designer's reset value for any type. Unknown type sets remain EDT's decision. */
    public static String fillRefusal(Plan value, TypeDescription type)
    {
        if ("fixedArray".equals(value.kind()))
            return "fillValue cannot be a fixedArray. Use a scalar or ref; arrays belong in choiceParameters.";
        if ("undefined".equals(value.kind()) || type == null || type.getTypes().isEmpty()) return null;
        String expected = value.reference() == null ? value.kind() : value.reference().typeName();
        boolean unknown = false;
        List<String> names = new ArrayList<>();
        List<TypeItem> effective;
        try
        {
            effective = com._1c.g5.v8.dt.mcore.util.McoreUtil.flatTypes(type);
        }
        catch (RuntimeException e)
        {
            return null; // Unresolved derived types cannot prove a mismatch; EDT markers decide.
        }
        if (effective.isEmpty()) return null;
        for (TypeItem item : effective)
        {
            String name;
            try
            {
                name = com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeName(item);
            }
            catch (RuntimeException e)
            {
                unknown = true;
                continue;
            }
            if (name != null && name.equalsIgnoreCase(expected)) return null;
            names.add(name == null ? "unresolved" : name);
            // A named type set can contain the requested primitive/reference. Do not guess its expansion.
            if (item instanceof TypeSet || name == null) unknown = true;
        }
        return unknown ? null : "fillValue '" + expected + "' does not match the member type " + names
            + ". Pass a matching ValueSpec or change type in the same call.";
    }

    /** Returns the write grammar; an unsupported stored kind fails visibly rather than losing data. */
    public static JsonObject render(Value value)
    {
        JsonObject result = new JsonObject();
        if (value instanceof ReferenceValue ref)
        {
            EObject target = ref.getValue();
            ISymbolicNameService names = ServiceAccess.get(ISymbolicNameService.class);
            String canonical = names == null ? null
                : names.generateSymbolicName(target, value, McorePackage.Literals.REFERENCE_VALUE__VALUE);
            if (canonical == null && target instanceof MdObject)
                canonical = MetadataNodeResolver.addressOf((MdObject)target);
            if (canonical == null) throw new IllegalStateException("Cannot read the stored reference; retry after indexing.");
            result.addProperty("kind", "ref");
            result.addProperty("ref", canonical);
        }
        else if (value instanceof FixedArrayValue array)
        {
            result.addProperty("kind", "fixedArray");
            JsonArray values = new JsonArray();
            for (Value item : array.getValues()) values.add(render(item));
            result.add("values", values);
        }
        else if (value instanceof StringValue string)
        {
            // EDT stores an empty string fill value as a null value; render what the writer accepts.
            result.addProperty("kind", "string");
            result.addProperty("value", string.getValue() == null ? "" : string.getValue());
        }
        else if (value instanceof NumberValue number) { result.addProperty("kind", "number"); result.addProperty("value", number.getValue()); }
        else if (value instanceof BooleanValue bool) { result.addProperty("kind", "boolean"); result.addProperty("value", bool.isValue()); }
        else if (value instanceof DateValue date) { result.addProperty("kind", "date"); result.addProperty("value", date.getValue().toString()); }
        else if (value instanceof UndefinedValue) result.addProperty("kind", "undefined");
        else if (value instanceof NullValue) result.addProperty("kind", "null");
        else throw new IllegalStateException("Cannot render stored Value kind " + value.eClass().getName());
        return result;
    }
}
