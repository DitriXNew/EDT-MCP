/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McorePackage;

/**
 * Discovers Value-valued model accessors/literals in the actual compiled writers, independently of
 * the guard's registrations. Adding a writer sink without a guard row must fail this ratchet even
 * if no behavior fixture happens to execute the new request branch yet.
 */
public class DcsWriterValueSlotCoverageTest
{
    private static final EPackage[] MODEL_PACKAGES = {
        com._1c.g5.v8.dt.dcs.model.core.DcsPackage.eINSTANCE,
        com._1c.g5.v8.dt.dcs.model.settings.DcsPackage.eINSTANCE,
        com._1c.g5.v8.dt.dcs.model.schema.DcsPackage.eINSTANCE,
        McorePackage.eINSTANCE
    };

    @Test
    public void testEveryValueSlotReferencedByAWriterHasAGuardClassification() throws Exception
    {
        Set<EReference> slots = writerSlots();
        var classifier = java.util.Arrays.stream(DcsReadProjection.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("valueSlot")
                && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[] {EReference.class}))
            .findFirst().orElse(null);
        assertNotNull("the replace guard needs an explicit Value-slot classification", classifier);
        Set<String> missing = new TreeSet<>();
        for (EReference slot : slots)
        {
            Object kind = classifier.invoke(null, slot);
            if (kind == null || "UNREGISTERED".equals(kind.toString())) missing.add(name(slot));
        }
        assertEquals("writer Value slots without a replace dry run", Set.of(), missing);
    }

    @Test
    public void testWriterDiscoveryCannotSilentlyBecomeEmptyOrSkipASinkFamily() throws Exception
    {
        Set<String> names = new TreeSet<>();
        for (EReference slot : writerSlots()) names.add(name(slot));
        // This is the writer inventory, not a copy of the guard's table. New sinks are discovered
        // automatically above; these witnesses keep a broken/removed scanner from passing vacuously.
        assertTrue(names.toString(), names.containsAll(Set.of(
            "DataCompositionUserFieldsVariant.value", "DataCompositionSchemaParameter.values",
            "AvailableValue.value", "Presentation.localValue", "StructureProperty.value",
            "TypedValueListItem.value", "DataCompositionFilterItem.left",
            "DataCompositionFilterItem.right", "DataCompositionGroupField.periodAdditionBegin",
            "DataCompositionGroupField.periodAdditionEnd", "DataCompositionGroupField.field",
            "DataCompositionSelectedField.field", "DataCompositionSelectedFieldGroup.field",
            "DataCompositionOrderItem.field", "DataCompositionAppearanceField.field",
            "DataCompositionParameterValue.parameter", "DataCompositionParameterValue.values")));
    }

    private static Set<EReference> writerSlots() throws Exception
    {
        Set<EReference> result = new LinkedHashSet<>();
        for (Class<?> writer : new Class<?>[] {DcsSettingsWriter.class, DcsWriter.class,
            DcsSchemaWriter.class, DcsDynamicListWriter.class, DcsFormAppearanceContent.class,
            DcsPresentationParser.class})
        {
            discover(writer, result);
        }
        return result;
    }

    /** Only reads JVM constant-pool member references; it never runs a writer or loads EDT. */
    private static void discover(Class<?> writer, Set<EReference> slots) throws Exception
    {
        String resource = "/" + writer.getName().replace('.', '/') + ".class";
        try (var raw = writer.getResourceAsStream(resource))
        {
            assertNotNull(resource, raw);
            var input = new DataInputStream(raw);
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            input.readUnsignedShort();
            int size = input.readUnsignedShort();
            String[] text = new String[size];
            int[] tags = new int[size];
            int[][] refs = new int[size][];
            for (int i = 1; i < size; i++)
            {
                int tag = tags[i] = input.readUnsignedByte();
                switch (tag)
                {
                    case 1: text[i] = input.readUTF(); break;
                    case 3: case 4: input.readInt(); break;
                    case 5: case 6: input.readLong(); i++; break;
                    case 7: case 8: case 16: case 19: case 20:
                        refs[i] = new int[] {input.readUnsignedShort()}; break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        refs[i] = new int[] {input.readUnsignedShort(), input.readUnsignedShort()}; break;
                    case 15: input.readUnsignedByte(); input.readUnsignedShort(); break;
                    default: throw new IOException("Unknown JVM constant tag " + tag + " in " + resource);
                }
            }
            for (int i = 1; i < size; i++)
            {
                if (tags[i] != 9 && tags[i] != 10 && tags[i] != 11) continue;
                String owner = text[refs[refs[i][0]][0]].replace('/', '.');
                String member = text[refs[refs[i][1]][0]];
                if (!owner.startsWith("com._1c.g5.v8.dt.")) continue;
                if (tags[i] == 9 && owner.endsWith("$Literals"))
                {
                    Object literal = Class.forName(owner, false, writer.getClassLoader()).getField(member).get(null);
                    if (literal instanceof EReference) addValueSlot((EReference)literal, slots);
                }
                else if (tags[i] != 9 && (member.startsWith("get") || member.startsWith("set")
                    || member.startsWith("unset")))
                {
                    String suffix = member.substring(member.startsWith("unset") ? 5 : 3);
                    if (suffix.isEmpty()) continue;
                    String feature = Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
                    for (EPackage pack : MODEL_PACKAGES)
                    {
                        var type = pack.getEClassifier(owner.substring(owner.lastIndexOf('.') + 1));
                        if (!(type instanceof EClass) || !owner.equals(type.getInstanceClassName())) continue;
                        var found = ((EClass)type).getEStructuralFeature(feature);
                        // A scalar getter only reads a stored value. Collection getters are sinks
                        // because the writers mutate their ELists; scalar sinks use setters/eSet.
                        if (found instanceof EReference
                            && (!member.startsWith("get") || ((EReference)found).isMany()))
                            addValueSlot((EReference)found, slots);
                    }
                }
            }
        }
    }

    private static void addValueSlot(EReference feature, Set<EReference> slots)
    {
        if (McorePackage.Literals.VALUE.isSuperTypeOf(feature.getEReferenceType())) slots.add(feature);
    }

    private static String name(EReference feature)
    {
        return feature.getEContainingClass().getName() + "." + feature.getName();
    }
}
