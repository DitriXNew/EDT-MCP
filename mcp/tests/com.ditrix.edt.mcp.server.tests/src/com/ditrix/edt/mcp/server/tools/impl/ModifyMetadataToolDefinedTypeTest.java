/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A DefinedType's own {@code type} is checked against what EDT's DefinedType type editor offers, not
 * as a stored metadata feature (issue #727): {@link ModifyMetadataTool#typeTargetForFeature} routes it
 * to {@link MetadataTypeBuilder.TypeTarget#DEFINED_TYPE}, a refusal of that type names the defined type,
 * and the guide says what that target takes. The builder side of the target is pinned by
 * {@code MetadataTypeBuilderDefinedTypeTest}.
 */
public class ModifyMetadataToolDefinedTypeTest
{
    /**
     * What the {@code type} section of the guide states about a DefinedType's own type (issue #727):
     * the vocabulary, the category that decides it, and that EDT's composite rule is left to EDT.
     */
    static final List<String> GUIDE_ANCHORS = List.of("A DefinedType's own `type`", //$NON-NLS-1$
        "`UserDefinedTypeInfoCategory`", "a type that must stand alone"); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void testADefinedTypesTypeSelectsTheDefinedTypeTarget()
    {
        DefinedType definedType = MdClassFactory.eINSTANCE.createDefinedType();
        EStructuralFeature type = definedType.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
        assertSame("modify_metadata resolves 'type' to this literal", //$NON-NLS-1$
            MdClassPackage.Literals.DEFINED_TYPE__TYPE, type);
        assertSame(McorePackage.Literals.TYPE_DESCRIPTION, type.getEType());

        assertSame(MetadataTypeBuilder.TypeTarget.DEFINED_TYPE,
            ModifyMetadataTool.typeTargetForFeature(MetadataTypeBuilder.TypeTarget.METADATA, type));
        assertSame("a form member's context keeps its own target", //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE,
            ModifyMetadataTool.typeTargetForFeature(MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE, type));
    }

    @Test
    public void testEveryOtherTypeDescriptionFeatureKeepsItsRoute()
    {
        // Every TypeDescription-valued feature the metadata model declares: only the three named
        // features leave the stored-feature target. A route keyed on anything looser than the feature
        // itself - its name, its owner's supertype - would move one more of them, and show up here.
        int checked = 0;
        for (EClassifier classifier : MdClassPackage.eINSTANCE.getEClassifiers())
        {
            if (!(classifier instanceof EClass))
            {
                continue;
            }
            for (EStructuralFeature feature : ((EClass)classifier).getEStructuralFeatures())
            {
                if (feature.getEType() != McorePackage.Literals.TYPE_DESCRIPTION)
                {
                    continue;
                }
                assertSame(classifier.getName() + "." + feature.getName(), expectedRoute(feature), //$NON-NLS-1$
                    ModifyMetadataTool.typeTargetForFeature(MetadataTypeBuilder.TypeTarget.METADATA, feature));
                checked++;
            }
        }
        assertTrue("the model must declare more TypeDescription features than the routed ones: " + checked, //$NON-NLS-1$
            checked > 3);
    }

    @Test
    public void testTheGuideStatesTheDefinedTypeVocabulary()
    {
        // Short anchors, not the paragraph: the target's own name for its vocabulary and the platform
        // category that decides it have to stay stated, in whatever words around them.
        String guide = new ModifyMetadataTool().getGuide();
        for (String anchor : GUIDE_ANCHORS)
        {
            assertTrue("the modify_metadata guide no longer says: " + anchor, guide.contains(anchor)); //$NON-NLS-1$
        }
        assertFalse("ValueTable / ValueTree are accepted in a DefinedType too, not on a form attribute only", //$NON-NLS-1$
            guide.contains("(and its columns) ONLY")); //$NON-NLS-1$
        assertFalse("the category judges items one by one; EDT's composite rule is not checked before the write", //$NON-NLS-1$
            guide.contains("takes exactly what EDT's DefinedType type editor offers")); //$NON-NLS-1$
    }

    @Test
    public void testATypeRefusalOnADefinedTypeNamesTheDefinedType() throws Exception
    {
        // Every refusal of a DefinedType's own type - a category's "no" included - leaves the same
        // prepare branch; a shape error reaches it without a platform service.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        DefinedType definedType = MdClassFactory.eINSTANCE.createDefinedType();
        definedType.setName("Money"); //$NON-NLS-1$
        config.getDefinedTypes().add(definedType);

        assertEquals("Invalid 'type' for 'type' of 'DefinedType.Money': type.types must be a non-empty array " //$NON-NLS-1$
            + "of {kind, ...} items.", emptyTypeRefusal(config, definedType)); //$NON-NLS-1$
    }

    @Test
    public void testATypeRefusalOnAnyOtherOwnerKeepsItsWording() throws Exception
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        CatalogAttribute weight = MdClassFactory.eINSTANCE.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        catalog.getAttributes().add(weight);
        config.getCatalogs().add(catalog);

        assertEquals("Invalid 'type' for 'type': type.types must be a non-empty array of {kind, ...} items.", //$NON-NLS-1$
            emptyTypeRefusal(config, weight));
    }

    /**
     * The refusal {@code modify_metadata} answers for {@code type = {types:[]}} on {@code target}, through
     * the private preparation every mdclass property passes before the write transaction.
     */
    private static String emptyTypeRefusal(Configuration config, MdObject target) throws Exception
    {
        JsonObject spec = new JsonObject();
        spec.add("types", new JsonArray()); //$NON-NLS-1$
        JsonObject property = new JsonObject();
        property.addProperty("name", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        property.add("value", spec); //$NON-NLS-1$

        Method prepare = ModifyMetadataTool.class.getDeclaredMethod("validateAndPrepare", IProject.class, //$NON-NLS-1$
            MetadataScope.class, Configuration.class, Version.class, MdObject.class, List.class, List.class,
            MdNameNormalizer.Report.class, boolean.class);
        prepare.setAccessible(true);
        String json = (String)prepare.invoke(new ModifyMetadataTool(), null, MetadataScope.ofConfiguration(config),
            config, Version.V8_3_24, target, List.of(property), new ArrayList<>(), new MdNameNormalizer.Report(true),
            false);
        assertNotNull("an empty type list must be refused", json); //$NON-NLS-1$
        return JsonParser.parseString(json).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }

    /** The target {@code modify_metadata} must build a {@code type} feature for, in the mdclass context. */
    private static MetadataTypeBuilder.TypeTarget expectedRoute(EStructuralFeature feature)
    {
        if (feature == MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE)
        {
            return MetadataTypeBuilder.TypeTarget.EVENT_SOURCE;
        }
        if (feature == MdClassPackage.Literals.SESSION_PARAMETER__TYPE)
        {
            return MetadataTypeBuilder.TypeTarget.SESSION_PARAMETER;
        }
        if (feature == MdClassPackage.Literals.DEFINED_TYPE__TYPE)
        {
            return MetadataTypeBuilder.TypeTarget.DEFINED_TYPE;
        }
        return MetadataTypeBuilder.TypeTarget.METADATA;
    }
}
