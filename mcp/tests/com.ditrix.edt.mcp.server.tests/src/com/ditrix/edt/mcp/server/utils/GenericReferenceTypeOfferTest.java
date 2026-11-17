/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegisterResource;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeDescriptionInfoWithCategories;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Issue #966: which stored feature may hold a generic reference ({@code DocumentRef}) is EDT's type editor's
 * call for that very owner and feature - the editor is faked here, the preparation path is real.
 */
public class GenericReferenceTypeOfferTest
{
    private BiFunction<EObject, EReference, TypeDescriptionInfoWithCategories> previous;
    private TypeDescriptionInfoWithCategories editor;

    @Before
    public void setUp()
    {
        previous = GenericReferenceTypeOffer.typeInfoSource;
        editor = mock(TypeDescriptionInfoWithCategories.class);
        when(editor.contains(any(TypeItem.class))).thenAnswer(call ->
            Set.of("String", "DocumentRef", "AnyRef").contains(McoreUtil.getTypeName(call.getArgument(0)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GenericReferenceTypeOffer.typeInfoSource = (owner, feature) -> editor;
    }

    @After
    public void tearDown()
    {
        GenericReferenceTypeOffer.typeInfoSource = previous;
    }

    @Test
    public void testAnOfferedGenericReferencePasses()
    {
        assertNull(GenericReferenceTypeOffer.check(dimension(), typeFeature(), types(typeSet("DocumentRef")))); //$NON-NLS-1$
        assertNull(GenericReferenceTypeOffer.check(dimension(), typeFeature(),
            types(typeSet("AnyRef"), plain("CatalogRef.Goods")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAGenericReferenceTheEditorDoesNotOfferIsRefusedNamingTheOwnerAndTheConcreteForm()
    {
        AccumulationRegisterResource amount = MdClassFactory.eINSTANCE.createAccumulationRegisterResource();
        amount.setName("Amount"); //$NON-NLS-1$

        GenericReferenceTypeOffer.Problem problem = GenericReferenceTypeOffer.check(amount, typeFeature(),
            types(typeSet("CatalogRef"))); //$NON-NLS-1$

        assertNotNull(problem);
        assertFalse(problem.platformFailure());
        assertEquals("Type 'CatalogRef' (the generic reference: any object of that kind) is not offered by EDT's " //$NON-NLS-1$
            + "type editor for AccumulationRegisterResource 'Amount'. Use a reference to one object " //$NON-NLS-1$
            + "({kind:'CatalogRef', ref:'Name'}) or list the objects as a composite type.", problem.message()); //$NON-NLS-1$
    }

    @Test
    public void testTheEditorOfTheActualOwnerAndFeatureIsAsked()
    {
        InformationRegisterDimension dimension = dimension();
        List<Object[]> asked = new ArrayList<>();
        GenericReferenceTypeOffer.typeInfoSource = (owner, feature) -> {
            asked.add(new Object[] { owner, feature });
            return editor;
        };

        assertNull(GenericReferenceTypeOffer.check(dimension, typeFeature(), types(typeSet("DocumentRef")))); //$NON-NLS-1$

        assertEquals(1, asked.size());
        assertSame(dimension, asked.get(0)[0]);
        assertSame(typeFeature(), asked.get(0)[1]);
    }

    @Test
    public void testATypeWithoutAGenericReferenceNeverAsksTheEditor()
    {
        // A plain type and a type set naming ONE object (DefinedType.X, Characteristic.X) keep their rules.
        GenericReferenceTypeOffer.typeInfoSource = (owner, feature) -> {
            throw new AssertionError("no generic reference: the editor must not be asked"); //$NON-NLS-1$
        };
        assertNull(GenericReferenceTypeOffer.check(dimension(), typeFeature(),
            types(plain("String"), plain("CatalogRef.Goods"), typeSet("DefinedType.Money"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                typeSet("Characteristic.Kinds")))); //$NON-NLS-1$
        assertNull(GenericReferenceTypeOffer.check(dimension(), typeFeature(), types()));
    }

    @Test
    public void testAnEditorThatCannotAnswerFailsClosedAsThePlatformsFailure()
    {
        GenericReferenceTypeOffer.typeInfoSource = (owner, feature) -> null;
        assertPlatformFailure(GenericReferenceTypeOffer.check(dimension(), typeFeature(), types(typeSet("DocumentRef")))); //$NON-NLS-1$

        GenericReferenceTypeOffer.typeInfoSource = (owner, feature) -> {
            throw new IllegalStateException("not ready"); //$NON-NLS-1$
        };
        assertPlatformFailure(GenericReferenceTypeOffer.check(dimension(), typeFeature(), types(typeSet("DocumentRef")))); //$NON-NLS-1$

        GenericReferenceTypeOffer.typeInfoSource = (owner, feature) -> editor;
        when(editor.contains(any(TypeItem.class))).thenThrow(new IllegalStateException("category failed")); //$NON-NLS-1$
        assertPlatformFailure(GenericReferenceTypeOffer.check(dimension(), typeFeature(), types(typeSet("DocumentRef")))); //$NON-NLS-1$
    }

    // ---- the preparation path asks the editor --------------------------------------------------------

    @Test
    public void testModifyPreparationRefusesAGenericReferenceTheFeatureDoesNotOffer()
    {
        when(editor.contains(any(TypeItem.class))).thenReturn(false);
        List<MetadataPropertyApplier.PreparedChange> changes = new ArrayList<>();

        String error = prepare(dimension(), changes, "{name:'type',value:{types:[{kind:'DocumentRef'}]}}"); //$NON-NLS-1$

        assertNotNull(error);
        assertTrue(error, error.contains("Invalid 'type' for 'type': Type 'DocumentRef'")); //$NON-NLS-1$
        assertTrue(error, error.contains("not offered by EDT's type editor for InformationRegisterDimension 'Document'")); //$NON-NLS-1$
        assertTrue("nothing is prepared", changes.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testModifyPreparationAcceptsAGenericReferenceTheFeatureOffers()
    {
        List<MetadataPropertyApplier.PreparedChange> changes = new ArrayList<>();

        String error = prepare(dimension(), changes, "{name:'type',value:{types:[{kind:'DocumentRef'}]}}"); //$NON-NLS-1$

        assertNull(error, error);
        assertEquals(1, changes.size());
    }

    @Test
    public void testOnlyAStoredFeatureAndASessionParameterAskThisEditor()
    {
        GenericReferenceTypeOffer.typeInfoSource = (owner, feature) -> {
            throw new AssertionError("judged elsewhere: the form picker / the DefinedType category"); //$NON-NLS-1$
        };
        for (MetadataTypeBuilder.TypeTarget target : new MetadataTypeBuilder.TypeTarget[] {
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE, MetadataTypeBuilder.TypeTarget.DEFINED_TYPE })
        {
            assertNull(target.toString(), MetadataPropertyApplier.genericReferenceOfferError(target, dimension(),
                typeFeature(), "type", types(typeSet("DocumentRef")))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        GenericReferenceTypeOffer.typeInfoSource = (owner, feature) -> null;
        for (MetadataTypeBuilder.TypeTarget target : new MetadataTypeBuilder.TypeTarget[] {
            MetadataTypeBuilder.TypeTarget.METADATA, MetadataTypeBuilder.TypeTarget.SESSION_PARAMETER })
        {
            assertNotNull(target.toString(), MetadataPropertyApplier.genericReferenceOfferError(target, dimension(),
                typeFeature(), "type", types(typeSet("DocumentRef")))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------------

    private static String prepare(MdObject target, List<MetadataPropertyApplier.PreparedChange> changes, String property)
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalArgumentException("unknown")).when(provider).createProxy(Mockito.anyString()); //$NON-NLS-1$
        Map<String, TypeItem> known = Map.of("DocumentRef", typeSet("DocumentRef")); //$NON-NLS-1$ //$NON-NLS-2$
        known.forEach((name, type) -> Mockito.doReturn(type).when(provider).createProxy(name));
        JsonObject prop = JsonParser.parseString(property).getAsJsonObject();
        return MetadataPropertyApplier.validateAndPrepare(null, null, null, Version.V8_3_27, target, List.of(prop),
            changes, new MdNameNormalizer.Report(true), false,
            (spec, config, scope, version, extension, typeTarget) -> MetadataTypeBuilder.buildWithProvider(spec,
                provider, config, scope, extension, typeTarget));
    }

    private static void assertPlatformFailure(GenericReferenceTypeOffer.Problem problem)
    {
        assertNotNull(problem);
        assertTrue(problem.message(), problem.platformFailure());
        assertTrue(problem.message(), problem.message().startsWith("Cannot check the generic reference type")); //$NON-NLS-1$
        assertTrue(problem.message(), problem.message().contains("Nothing was written")); //$NON-NLS-1$
    }

    private static InformationRegisterDimension dimension()
    {
        InformationRegisterDimension dimension = MdClassFactory.eINSTANCE.createInformationRegisterDimension();
        dimension.setName("Document"); //$NON-NLS-1$
        return dimension;
    }

    private static EReference typeFeature()
    {
        return MdClassPackage.Literals.BASIC_FEATURE__TYPE;
    }

    private static TypeSet typeSet(String name)
    {
        TypeSet set = McoreFactory.eINSTANCE.createTypeSet();
        set.setName(name);
        return set;
    }

    private static Type plain(String name)
    {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
    }

    private static TypeDescription types(TypeItem... items)
    {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        for (TypeItem item : items)
        {
            td.getTypes().add(item);
        }
        return td;
    }
}
