/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.adoptedDefinedType;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.adoptedSubscription;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.adoptedSubsystem;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.catalogRefType;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.description;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.stringQualifiers;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.type;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.typeExtension;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.typeSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.mcore.BinaryQualifiers;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.AccountingFlag;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.extension.BasicFeatureExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.CatalogExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.ConfigurationExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.DefinedTypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.EventSubscriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.StyleItemExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.SubsystemExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyType;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter.Decision;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter.Level;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter.Routing;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.FakeAccess;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.FakePlatform;

/**
 * Issues #709 / #710: what a write of a property of an object an extension ADOPTED does to the extension's
 * {@code .mdo}, decided before anything is written. The rules are EDT's, read from its bytecode (EDT 2026.1.2
 * and 2026.2.1 alike): the {@code .mdo} writer of an extension ({@code MdExtXmlFormatter}) drops a property of
 * an adopted object whose same-named extension state is unset or None; EDT's type-extension editor lets an
 * extension add types to a defined type from runtime 8.3.20 ({@code TypeDescriptionExtensionLevel}), offers
 * {@code Extended} only for some types ({@code TypeExtensionTree}) and refuses some compositions
 * ({@code TypeExtensionTreeValidator}); an event subscription's source is extensible from 8.3.17 (the
 * property-type delegates). The platform calls go through {@link FakePlatform}; the objects are headless.
 */
public class AdoptedPropertyRouterTest
{
    private static final Version V8_5_1 = Version.V8_5_1;
    private static final String FQN_DT = "DefinedType.Amount"; //$NON-NLS-1$
    private static final String FQN_SUB = "EventSubscription.OnWrite"; //$NON-NLS-1$

    // ==================== the formatter mirror (U1) ====================

    @Test
    public void testANativeObjectIsNeverDropped()
    {
        Catalog catalog = catalogWithBlock(ObjectBelonging.NATIVE);
        assertFalse("the extension's own object is written as is", //$NON-NLS-1$
            AdoptedPropertyRouter.wouldDrop(catalog, MdClassPackage.Literals.CATALOG__CODE_LENGTH));
    }

    @Test
    public void testAnAdoptedObjectWithoutABlockIsNeverDropped()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setObjectBelonging(ObjectBelonging.ADOPTED);
        assertFalse("EDT's writer keeps everything of an object without an extension block", //$NON-NLS-1$
            AdoptedPropertyRouter.wouldDrop(catalog, MdClassPackage.Literals.CATALOG__CODE_LENGTH));
    }

    @Test
    public void testAPropertyWithoutASameNamedStateIsKept()
    {
        Catalog catalog = catalogWithBlock(ObjectBelonging.ADOPTED);
        assertFalse("a comment has no extension state, so EDT writes it", //$NON-NLS-1$
            AdoptedPropertyRouter.wouldDrop(catalog, MdClassPackage.Literals.MD_OBJECT__COMMENT));
    }

    @Test
    public void testAnUnsetOrNoneStateIsDropped()
    {
        Catalog catalog = catalogWithBlock(ObjectBelonging.ADOPTED);
        assertTrue("an unset state drops the value", //$NON-NLS-1$
            AdoptedPropertyRouter.wouldDrop(catalog, MdClassPackage.Literals.CATALOG__CODE_LENGTH));
        ((CatalogExtension)catalog.getExtension()).setCodeLength(MdPropertyState.NONE);
        assertTrue("a None state drops the value", //$NON-NLS-1$
            AdoptedPropertyRouter.wouldDrop(catalog, MdClassPackage.Literals.CATALOG__CODE_LENGTH));
    }

    @Test
    public void testANullStateIsDroppedLikeNone()
    {
        // EDT's writer drops on "null or None" and the mirror keeps both halves. A generated block answers its
        // default (None) for an unset state, so only a block that answers null reaches the null half - here and,
        // against EDT's own writer, in AdoptedPropertyFormatterConformanceTest.
        CatalogExtension block = mock(CatalogExtension.class, withSettings().stubOnly());
        doReturn(MdClassExtensionFactory.eINSTANCE.createCatalogExtension().eClass()).when(block).eClass();
        doReturn(null).when(block).eGet(any(EStructuralFeature.class));
        Catalog catalog = mock(Catalog.class, withSettings().stubOnly());
        doReturn(ObjectBelonging.ADOPTED).when(catalog).getObjectBelonging();
        doReturn(block).when(catalog).getExtension();

        assertTrue("a null state drops the value", //$NON-NLS-1$
            AdoptedPropertyRouter.wouldDrop(catalog, MdClassPackage.Literals.CATALOG__CODE_LENGTH));
        assertNull(AdoptedPropertyRouter.stateOf(catalog, MdClassPackage.Literals.CATALOG__CODE_LENGTH));
    }

    @Test
    public void testACheckedExtendedOrNotifyStateIsKept()
    {
        Catalog catalog = catalogWithBlock(ObjectBelonging.ADOPTED);
        for (MdPropertyState state : List.of(MdPropertyState.CHECKED, MdPropertyState.EXTENDED,
            MdPropertyState.NOTIFY))
        {
            ((CatalogExtension)catalog.getExtension()).setCodeLength(state);
            assertFalse("a " + state + " state keeps the value", //$NON-NLS-1$ //$NON-NLS-2$
                AdoptedPropertyRouter.wouldDrop(catalog, MdClassPackage.Literals.CATALOG__CODE_LENGTH));
            assertSame(state, AdoptedPropertyRouter.stateOf(catalog, MdClassPackage.Literals.CATALOG__CODE_LENGTH));
        }
    }

    @Test
    public void testAnAdoptedSynonymWithANoneStateIsDropped()
    {
        Catalog catalog = catalogWithBlock(ObjectBelonging.ADOPTED);
        ((CatalogExtension)catalog.getExtension()).setSynonym(MdPropertyState.NONE);
        assertTrue("an adopted catalog's synonym is dropped while its state is None", //$NON-NLS-1$
            AdoptedPropertyRouter.wouldDrop(catalog, MdClassPackage.Literals.MD_OBJECT__SYNONYM));
    }

    @Test
    public void testTheExtensionRootsSynonymIsAlwaysWritten()
    {
        // The formatter's one exemption, which the configuration root's own address (#741 / #746) needs.
        Configuration root = adoptedRoot();
        assertFalse("an extension root's synonym is exempt", //$NON-NLS-1$
            AdoptedPropertyRouter.wouldDrop(root, MdClassPackage.Literals.MD_OBJECT__SYNONYM));
        assertTrue("the exemption is the synonym alone: a None caption is dropped", //$NON-NLS-1$
            AdoptedPropertyRouter.wouldDrop(root, MdClassPackage.Literals.CONFIGURATION__CAPTION));
    }

    @Test
    public void testTheStateIsFoundByTheExactName()
    {
        // BasicFeature.toolTip against BasicFeatureExtension.tooltip: EDT's writer looks the state up by the
        // exact name and misses, so it writes the value; EDT's own property mapper (equalsIgnoreCase) would
        // have found it - the mirror follows the writer.
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setObjectBelonging(ObjectBelonging.ADOPTED);
        BasicFeatureExtension extension = MdClassExtensionFactory.eINSTANCE.createBasicFeatureExtension();
        extension.setTooltip(MdPropertyState.NONE);
        attribute.setExtension(extension);
        assertFalse(AdoptedPropertyRouter.wouldDrop(attribute, MdClassPackage.Literals.BASIC_FEATURE__TOOL_TIP));
        assertNull(AdoptedPropertyRouter.stateOf(attribute, MdClassPackage.Literals.BASIC_FEATURE__TOOL_TIP));
    }

    @Test
    public void testTheLevelOfAnOwnerMirrorsEdtsTypeExtensionEditor()
    {
        MdClassPackage md = MdClassPackage.eINSTANCE;
        assertEquals(Level.CHECKED_ONLY, AdoptedPropertyRouter.levelOf(md.getDefinedType(), Version.V8_3_19));
        assertEquals(Level.CAN_EXTEND, AdoptedPropertyRouter.levelOf(md.getDefinedType(), Version.V8_3_20));
        assertEquals(Level.CHECKED_ONLY, AdoptedPropertyRouter.levelOf(md.getChartOfCharacteristicTypes(),
            Version.V8_3_19));
        assertEquals(Level.CHECKED_ONLY, AdoptedPropertyRouter.levelOf(md.getCommonAttribute(), Version.V8_3_19));
        assertEquals(Level.CAN_EXTEND, AdoptedPropertyRouter.levelOf(md.getCommonAttribute(), Version.V8_5_1));
        assertEquals(Level.ONE_STATUS_ONLY, AdoptedPropertyRouter.levelOf(md.getAccountingFlag(), V8_5_1));
        assertEquals(Level.ONE_STATUS_ONLY, AdoptedPropertyRouter.levelOf(md.getExtDimensionAccountingFlag(), V8_5_1));
        assertEquals(Level.ONE_STATUS_ONLY, AdoptedPropertyRouter.levelOf(md.getField(), Version.V8_3_23));
        assertEquals(Level.CAN_EXTEND, AdoptedPropertyRouter.levelOf(md.getField(), Version.V8_3_24));
        assertEquals(Level.ONE_STATUS_ONLY, AdoptedPropertyRouter.levelOf(md.getFunction(), Version.V8_3_23));
        assertEquals(Level.CAN_EXTEND, AdoptedPropertyRouter.levelOf(md.getCatalogAttribute(), Version.V8_3_19));
        assertEquals("EDT compares the class by identity", Level.CAN_EXTEND, //$NON-NLS-1$
            AdoptedPropertyRouter.levelOf(md.getAccumulationRegisterDimension(), Version.V8_3_19));
        assertEquals(Level.CAN_EXTEND, AdoptedPropertyRouter.levelOf(null, Version.V8_3_19));
    }

    // ==================== #709: an adopted defined type's type (U2) ====================

    @Test
    public void testADefinedTypeAtRuntime851IsRoutedToItsTypeExtension()
    {
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("String"), MdPropertyState.CHECKED)); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().baseWithString(10, "String").offer("String") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, platform, typeChange(catalogRefType("Own", //$NON-NLS-1$
            ObjectBelonging.NATIVE)));

        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.TYPE_EXTENSION, routing.decision(0).kind());
        assertNotNull(routing.decision(0).typePlan());
    }

    @Test
    public void testADefinedTypeBelow8320IsRefusedNaming8320()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        // 8.3.19: multi-state, but EDT's editor only lets an extension check the types.
        Routing routing = route(definedType, Version.V8_3_19, new FakePlatform().offer("CatalogRef.Own"), //$NON-NLS-1$
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        assertRefused(routing, "8.3.20", "only check", "8.3.19", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        // 8.3.17: the whole type is checkable, not multi-state.
        FakePlatform checkable = new FakePlatform().propertyTypes(MdClassPackage.Literals.DEFINED_TYPE__TYPE,
            MdPropertyType.CHECKABLE, MdPropertyType.CHECKABLE_OR_NOTIFY, MdPropertyType.NORMAL);
        routing = route(definedType, Version.V8_3_17, checkable,
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        assertRefused(routing, "8.3.20", "8.3.17"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testADefinedTypeAt8320IsTheFirstThatAddsTypes()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        Routing routing = route(definedType, Version.V8_3_20, new FakePlatform().offer("CatalogRef.Own"), //$NON-NLS-1$
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.TYPE_EXTENSION, routing.decision(0).kind());
    }

    @Test
    public void testAnUnknownVersionIsRefusedWithARetry()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        Routing routing = route(definedType, null, new FakePlatform(), typeChange(type("String"))); //$NON-NLS-1$
        assertRefused(routing, "'type' of DefinedType.Amount", "adopted from the base configuration 'TestConfiguration'", //$NON-NLS-1$ //$NON-NLS-2$
            "not known yet", "list_projects", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAnAdoptedDefinedTypeWithoutABlockIsRefused()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        definedType.setExtension(null);
        Routing routing = route(definedType, V8_5_1, new FakePlatform(), typeChange(type("String"))); //$NON-NLS-1$
        assertRefused(routing, "no extension block", "adopt the object again"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testABaseThatCannotBeReadIsRefused()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        assertRefused(route(definedType, V8_5_1, new FakePlatform().baseNotFound(), typeChange(type("String"))), //$NON-NLS-1$
            "does not find the object", "TestConfiguration", "get_project_errors"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(route(definedType, V8_5_1, new FakePlatform().baseNotReady(), typeChange(type("String"))), //$NON-NLS-1$
            "not ready yet", "list_projects"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(route(definedType, V8_5_1, new FakePlatform().baseUnavailable("a lock timed out"), //$NON-NLS-1$
            typeChange(type("String"))), "could not be read from the base configuration 'TestConfiguration'", //$NON-NLS-1$ //$NON-NLS-2$
            "(a lock timed out)", "list_projects", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testMissingServicesAreRefusedNeverGuessed()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        assertRefused(route(definedType, V8_5_1, new FakePlatform().withoutPropertyTypes(),
            typeChange(type("String"))), "IMdPropertyTypeProvider", "base configuration 'TestConfiguration'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Nothing was changed."); //$NON-NLS-1$
        assertRefused(route(definedType, V8_5_1, new FakePlatform().withoutTypeInfos(),
            typeChange(type("String"))), "type information", "adopted from the base configuration 'TestConfiguration'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "list_projects", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testATypeSetIsNotAddable()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().offerTypeSet("AnyRef"), //$NON-NLS-1$
            typeChange(typeSet("AnyRef"))); //$NON-NLS-1$
        assertRefused(routing, "'AnyRef' (type.types[0])", "type set", "only check", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "cannot be added to adopted DefinedType.Amount (base configuration 'TestConfiguration')"); //$NON-NLS-1$
    }

    @Test
    public void testASingleTypeIsNotAddable()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().offerSingle("ValueStorage"), //$NON-NLS-1$
            typeChange(type("ValueStorage"))); //$NON-NLS-1$
        assertRefused(routing, "'ValueStorage'", "cannot be combined"); //$NON-NLS-1$ //$NON-NLS-2$
        // Where the type mixes single types EDT offers Extended for it.
        routing = route(definedType, V8_5_1, new FakePlatform().offerSingle("ValueStorage").mixSingle(), //$NON-NLS-1$
            typeChange(type("ValueStorage"))); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.isRefused());
    }

    @Test
    public void testATypeOfAnObjectTheExtensionOwnsIsAlwaysAddable()
    {
        // TypeExtensionTree.createAvailableStates: an own object's type gets {Extended, None} before the
        // type-set and single checks.
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        Type own = catalogRefType("Own", ObjectBelonging.NATIVE); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().offerSingle("CatalogRef.Own"), //$NON-NLS-1$
            typeChange(own));
        assertFalse(routing.refusal(), routing.isRefused());
        assertTrue(AdoptedPropertyRouter.isExtensionOwnType(own));
        assertFalse(AdoptedPropertyRouter.isExtensionOwnType(catalogRefType("Base", ObjectBelonging.ADOPTED))); //$NON-NLS-1$
        assertFalse(AdoptedPropertyRouter.isExtensionOwnType(type("String"))); //$NON-NLS-1$
    }

    @Test
    public void testATypeEdtDoesNotOfferIsNotAddable()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().offer("String"), //$NON-NLS-1$
            typeChange(type("DocumentObject.Invoice"))); //$NON-NLS-1$
        assertRefused(routing, "'DocumentObject.Invoice'", "does not offer"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testABaseTypeWithOtherQualifiersIsRefused()
    {
        // C3: the refusal does not claim an extension cannot change a base type's qualifiers - EDT does it
        // by marking the base type Extended.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("String"), MdPropertyState.CHECKED)); //$NON-NLS-1$
        definedType.getExtension().eSet(DT_TYPE_EXTENSION, withString(typeExtension(type("String"), //$NON-NLS-1$
            MdPropertyState.CHECKED), 10));
        TypeDescription request = description(type("String")); //$NON-NLS-1$
        request.setStringQualifiers(stringQualifiers(20));
        Routing routing = route(definedType, V8_5_1, new FakePlatform().baseWithString(10, "String").offer("String"), //$NON-NLS-1$ //$NON-NLS-2$
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.DEFINED_TYPE__TYPE, request, null));
        assertRefused(routing, "'String' (type.types[0]) is already a base type", "String(10)", //$NON-NLS-1$ //$NON-NLS-2$
            "leaves base types as they stand", "marking the base type Extended", "pass only the types to add", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "of adopted DefinedType.Amount (base configuration 'TestConfiguration'), as String(10)"); //$NON-NLS-1$
        assertFalse("never the old, untrue claim", //$NON-NLS-1$
            routing.refusal().contains("an extension cannot change a base type")); //$NON-NLS-1$

        // The same String as it stands is a no-op, not a refusal.
        TypeDescription same = description(type("String")); //$NON-NLS-1$
        same.setStringQualifiers(stringQualifiers(10));
        routing = route(definedType, V8_5_1, new FakePlatform().baseWithString(10, "String").offer("String"), //$NON-NLS-1$ //$NON-NLS-2$
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.DEFINED_TYPE__TYPE, same, null));
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        assertTrue(routing.notes().get(0), routing.notes().get(0).contains("already base types, left as they stand: String")); //$NON-NLS-1$
    }

    @Test
    public void testAnAdoptedAttributesTypeIsRefused()
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setObjectBelonging(ObjectBelonging.ADOPTED);
        attribute.setExtension(MdClassExtensionFactory.eINSTANCE.createBasicFeatureExtension());
        Routing routing = AdoptedPropertyRouter.route(request(attribute, "Catalog.Goods.Attribute.Weight", V8_5_1, //$NON-NLS-1$
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.BASIC_FEATURE__TYPE, description(type("String")), //$NON-NLS-1$
                null)), new FakePlatform());
        assertRefused(routing, "'type' of Catalog.Goods.Attribute.Weight", "type extension", //$NON-NLS-1$ //$NON-NLS-2$
            "defined type's 'type' alone", "TestConfiguration"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnotherAdoptedTypeIsRefusedWordedByWhatEdtOffersAtThePlatform()
    {
        // C1: what EDT's property-type delegates offer for the type of an adopted object at the extension's
        // runtime (the bytecode of every delegate, v8.3.8 .. v8.5.1, identical on 2026.1.2 and 2026.2.1): only
        // checkable up to 8.3.17, multi-state from 8.3.18 (a command's commandParameterType from 8.3.25) - and
        // EDT's type-extension editor only checks the types of some owners (an accounting flag at any version).
        String fqn = "Catalog.Goods.Attribute.Weight"; //$NON-NLS-1$
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setObjectBelonging(ObjectBelonging.ADOPTED);
        attribute.setExtension(MdClassExtensionFactory.eINSTANCE.createBasicFeatureExtension());
        EStructuralFeature basicType = MdClassPackage.Literals.BASIC_FEATURE__TYPE;
        AdoptedPropertyRouter.Change retype = new AdoptedPropertyRouter.Change(basicType, description(type("String")), //$NON-NLS-1$
            null);

        Routing routing = AdoptedPropertyRouter.route(request(attribute, fqn, Version.V8_3_17, retype),
            new FakePlatform().propertyTypes(basicType, MdPropertyType.CHECKABLE, MdPropertyType.CHECKABLE_OR_NOTIFY,
                MdPropertyType.NORMAL));
        assertRefused(routing, "'type' of " + fqn + " was not written: " + fqn //$NON-NLS-1$ //$NON-NLS-2$
            + " is adopted from the base configuration 'TestConfiguration', and an extension for platform 8.3.17 " //$NON-NLS-1$
            + "can only check 'type' against the base configuration, not change it; change it in the base " //$NON-NLS-1$
            + "configuration 'TestConfiguration'. Nothing was changed."); //$NON-NLS-1$
        assertNoTypeExtensionAdvice(routing);

        // From 8.3.18 the type is multi-state: EDT keeps it in the object's type extension.
        routing = AdoptedPropertyRouter.route(request(attribute, fqn, V8_5_1, retype),
            new FakePlatform().propertyTypes(basicType, MdPropertyType.MULTI_STATE));
        assertRefused(routing, ", and EDT changes an adopted object's type only in its type extension, which " //$NON-NLS-1$
            + "modify_metadata writes for an adopted defined type's 'type' alone", //$NON-NLS-1$
            "Change it in EDT's type-extension editor of the adopted object, or in the base configuration " //$NON-NLS-1$
                + "'TestConfiguration'. Nothing was changed."); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.refusal().contains("can only check")); //$NON-NLS-1$

        // An owner whose types EDT's type-extension editor only checks, even where the type is multi-state.
        AccountingFlag flag = MdClassFactory.eINSTANCE.createAccountingFlag();
        flag.setObjectBelonging(ObjectBelonging.ADOPTED);
        flag.setExtension(MdClassExtensionFactory.eINSTANCE.createBasicFeatureExtension());
        routing = AdoptedPropertyRouter.route(request(flag, "ChartOfAccounts.Main.AccountingFlag.Currency", V8_5_1, //$NON-NLS-1$
            retype), new FakePlatform().propertyTypes(basicType, MdPropertyType.MULTI_STATE));
        assertRefused(routing, "an extension for platform 8.5.1 can only check 'type' against the base " //$NON-NLS-1$
            + "configuration, not change it"); //$NON-NLS-1$
        assertNoTypeExtensionAdvice(routing);

        // A platform at which EDT offers no state for it.
        routing = AdoptedPropertyRouter.route(request(attribute, fqn, Version.V8_3_21, retype),
            new FakePlatform().propertyTypes(basicType));
        assertRefused(routing, "an extension for platform 8.3.21 cannot change 'type'; change it in the base " //$NON-NLS-1$
            + "configuration 'TestConfiguration'. Nothing was changed."); //$NON-NLS-1$
        assertNoTypeExtensionAdvice(routing);

        // A command's parameter type: only checkable up to 8.3.24, multi-state from 8.3.25.
        CommonCommand command = MdClassFactory.eINSTANCE.createCommonCommand();
        command.setObjectBelonging(ObjectBelonging.ADOPTED);
        EStructuralFeature parameterType = MdClassPackage.Literals.BASIC_COMMAND__COMMAND_PARAMETER_TYPE;
        AdoptedPropertyRouter.Change reparameter = new AdoptedPropertyRouter.Change(parameterType,
            description(type("CatalogRef.Goods")), null); //$NON-NLS-1$
        routing = AdoptedPropertyRouter.route(request(command, "CommonCommand.Print", Version.V8_3_24, reparameter), //$NON-NLS-1$
            new FakePlatform().propertyTypes(parameterType, MdPropertyType.CHECKABLE,
                MdPropertyType.CHECKABLE_OR_NOTIFY, MdPropertyType.NORMAL));
        assertRefused(routing, "'commandParameterType' of CommonCommand.Print was not written", //$NON-NLS-1$
            "an extension for platform 8.3.24 can only check 'commandParameterType'"); //$NON-NLS-1$
        assertNoTypeExtensionAdvice(routing);
        routing = AdoptedPropertyRouter.route(request(command, "CommonCommand.Print", Version.V8_3_25, reparameter), //$NON-NLS-1$
            new FakePlatform().propertyTypes(parameterType, MdPropertyType.MULTI_STATE));
        assertRefused(routing, "'commandParameterType' of CommonCommand.Print", "only in its type extension"); //$NON-NLS-1$ //$NON-NLS-2$

        // What EDT allows cannot be told - the version is not known yet, or the service is missing: the refusal
        // claims nothing about the platform.
        for (Routing unknown : List.of(
            AdoptedPropertyRouter.route(request(attribute, fqn, null, retype), new FakePlatform()),
            AdoptedPropertyRouter.route(request(attribute, fqn, V8_5_1, retype), new FakePlatform().withoutPropertyTypes())))
        {
            assertRefused(unknown, "'type' of " + fqn + " was not written: " + fqn //$NON-NLS-1$ //$NON-NLS-2$
                + " is adopted from the base configuration 'TestConfiguration', and modify_metadata writes the type " //$NON-NLS-1$
                + "of an adopted object only for a defined type's 'type'", //$NON-NLS-1$
                "Change it in the base configuration 'TestConfiguration'", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(unknown.refusal(), unknown.refusal().contains("platform")); //$NON-NLS-1$
            assertFalse(unknown.refusal(), unknown.refusal().contains("type-extension editor")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAnEarlierTypeWriteOfTheSameCallIsDropped()
    {
        // As a batch is applied: only its last type write counts, so only that one is routed.
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().offer("CatalogRef.Own").offer("CatalogRef.Other"); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(definedType, V8_5_1, platform,
            typeChange(catalogRefType("Other", ObjectBelonging.NATIVE)), //$NON-NLS-1$
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        assertEquals(Decision.Kind.TYPE_EXTENSION, routing.decision(1).kind());
        assertTrue("a superseded write leaves no note", routing.notes().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testANativeTargetIsLeftAlone()
    {
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        definedType.setObjectBelonging(ObjectBelonging.NATIVE);
        FakePlatform platform = new FakePlatform();
        Routing routing = route(definedType, V8_5_1, platform, typeChange(type("String")), //$NON-NLS-1$
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.MD_OBJECT__SYNONYM, null, null));
        assertFalse(routing.isRefused());
        assertEquals(Decision.Kind.PLAIN, routing.decision(0).kind());
        assertEquals(Decision.Kind.PLAIN, routing.decision(1).kind());
        assertEquals("nothing is asked of the base for the extension's own object", 0, platform.baseReads()); //$NON-NLS-1$
    }

    @Test
    public void testAPropertyEdtWouldDropIsRefusedWordedByItsStates()
    {
        // C4: the way out depends on what EDT offers for the property at this platform.
        Catalog catalog = catalogWithBlock(ObjectBelonging.ADOPTED);
        Routing routing = AdoptedPropertyRouter.route(request(catalog, "Catalog.Goods", V8_5_1, //$NON-NLS-1$
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.MD_OBJECT__SYNONYM, null, null)),
            new FakePlatform());
        assertRefused(routing, "'synonym' of Catalog.Goods", "adopted from the base configuration 'TestConfiguration'", //$NON-NLS-1$ //$NON-NLS-2$
            "is None", "mark 'synonym' Extended", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        routing = AdoptedPropertyRouter.route(request(catalog, "Catalog.Goods", V8_5_1, //$NON-NLS-1$
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.CATALOG__CODE_LENGTH, null, null)),
            new FakePlatform());
        assertRefused(routing, "'codeLength'", "can only check 'codeLength'"); //$NON-NLS-1$ //$NON-NLS-2$

        routing = AdoptedPropertyRouter.route(request(catalog, "Catalog.Goods", Version.V8_3_21, //$NON-NLS-1$
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.MD_OBJECT__SYNONYM, null, null)),
            new FakePlatform().propertyTypes(MdClassPackage.Literals.MD_OBJECT__SYNONYM));
        assertRefused(routing, "platform 8.3.21 cannot change 'synonym'"); //$NON-NLS-1$
    }

    @Test
    public void testAStyleValueIsRefusedThroughItsTypeBinding()
    {
        StyleItem item = MdClassFactory.eINSTANCE.createStyleItem();
        item.setObjectBelonging(ObjectBelonging.ADOPTED);
        StyleItemExtension extension = MdClassExtensionFactory.eINSTANCE.createStyleItemExtension();
        extension.setValue(MdPropertyState.EXTENDED);
        extension.setType(MdPropertyState.NONE);
        item.setExtension(extension);
        Routing routing = AdoptedPropertyRouter.route(request(item, "StyleItem.Accent", V8_5_1, //$NON-NLS-1$
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.STYLE_ITEM__VALUE, null,
                MdClassPackage.Literals.STYLE_ITEM__TYPE)), new FakePlatform());
        assertRefused(routing, "'type' of StyleItem.Accent"); //$NON-NLS-1$
    }

    @Test
    public void testAnExtendedBaseTypeStandsWithItsOwnQualifiers()
    {
        // C1's other half: EDT lets an extension mark a base type Extended with other qualifiers, and that type
        // then stands as the extension holds it, not as the base defines it. Passed as it stands it changes
        // nothing; passed as the base defines it, it is refused naming how it stands.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            withString(typeExtension(type("String"), MdPropertyState.EXTENDED), 20)); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().baseWithString(10, "String").offer("String"); //$NON-NLS-1$ //$NON-NLS-2$
        TypeDescription asItStands = description(type("String")); //$NON-NLS-1$
        asItStands.setStringQualifiers(stringQualifiers(20));
        Routing routing = route(definedType, V8_5_1, platform,
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.DEFINED_TYPE__TYPE, asItStands, null));
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        assertTrue(routing.notes().get(0),
            routing.notes().get(0).contains("already base types, left as they stand: String")); //$NON-NLS-1$

        TypeDescription asTheBaseDefinesIt = description(type("String")); //$NON-NLS-1$
        asTheBaseDefinesIt.setStringQualifiers(stringQualifiers(10));
        routing = route(definedType, V8_5_1, platform,
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.DEFINED_TYPE__TYPE, asTheBaseDefinesIt, null));
        assertRefused(routing, "'String' (type.types[0]) is already a base type of adopted DefinedType.Amount " //$NON-NLS-1$
            + "(base configuration 'TestConfiguration'), as String(20);"); //$NON-NLS-1$
    }

    @Test
    public void testADateWithoutQualifiersStandsAsADateAndTime()
    {
        // A base Date without qualifiers is a date and time, as the platform reads it: passing Date(DateTime)
        // changes nothing, and Date(Date) is another type.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("Date"), MdPropertyState.CHECKED)); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("Date").offer("Date"); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(definedType, V8_5_1, platform, dateChange(DateFractions.DATE_TIME));
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        assertTrue(routing.notes().get(0),
            routing.notes().get(0).contains("already base types, left as they stand: Date")); //$NON-NLS-1$

        routing = route(definedType, V8_5_1, platform, dateChange(DateFractions.DATE));
        assertRefused(routing, "'Date' (type.types[0]) is already a base type", ", as Date;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== #709: the composition EDT's editor would refuse (C2) ====================

    @Test
    public void testAnUnlimitedStringCannotBeCombined()
    {
        // The base String is limited, but the extension marked it Extended with an unlimited length (EDT
        // offers that): it then counts as single, and nothing may join it.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            withString(typeExtension(type("String"), MdPropertyState.EXTENDED), 0)); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().baseWithString(10, "String").offer("String") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"), typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(routing, "would become String, CatalogRef.Own", "String cannot be combined", //$NON-NLS-1$ //$NON-NLS-2$
            "The type of adopted DefinedType.Amount (base configuration 'TestConfiguration')", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnUnlimitedBinaryDataCannotBeCombined()
    {
        // Rule S for BinaryData: the base BinaryData is limited, but the extension marked it Extended with an
        // unlimited length - it then counts as single, and nothing may join it.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("BinaryData"), MdPropertyState.EXTENDED)); //$NON-NLS-1$
        BinaryQualifiers unlimited = McoreFactory.eINSTANCE.createBinaryQualifiers();
        unlimited.setLength(0);
        ((DefinedTypeExtension)definedType.getExtension()).getTypeExtension().setBinaryQualifiers(unlimited);
        Routing routing = route(definedType, V8_5_1, new FakePlatform().baseWithBinary(10, "BinaryData") //$NON-NLS-1$
            .offer("BinaryData").offer("CatalogRef.Own"), typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(routing, "would become BinaryData, CatalogRef.Own", "BinaryData cannot be combined"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testASingleKeptTypeCannotBeCombined()
    {
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("UUID"), MdPropertyState.CHECKED)); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().offerSingle("UUID").offer("CatalogRef.Own"), //$NON-NLS-1$ //$NON-NLS-2$
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        assertRefused(routing, "UUID cannot be combined"); //$NON-NLS-1$
    }

    @Test
    public void testABaseBlobBlocksAnyAddition()
    {
        // The base type cannot be part of a composite type; the extension no longer checks it, so the
        // composition alone would pass - EDT's validator still refuses every Extended type next to the blob.
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().base("UUID").offerStrongSingle("UUID") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"), typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(routing, "'CatalogRef.Own' cannot be added", "base type 'UUID'", "composite", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "cannot be added to adopted DefinedType.Amount (base configuration 'TestConfiguration'): its base type"); //$NON-NLS-1$

        // An unlimited base String is a blob as well.
        routing = route(definedType, V8_5_1, new FakePlatform().baseWithString(0, "String").offer("String") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"), typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(routing, "base type 'String'"); //$NON-NLS-1$
    }

    @Test
    public void testABaseSingleTypeBlocksAnyAdditionWhileTheTypeDoesNotMix()
    {
        // Rule B for a single (not strong single) base type: while the type does not mix single types, it cannot
        // be part of a composite type either; where it mixes them, it blocks nothing.
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().base("UUID").offerSingle("UUID") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"), typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(routing, "'CatalogRef.Own' cannot be added to adopted DefinedType.Amount (base configuration " //$NON-NLS-1$
            + "'TestConfiguration'): its base type 'UUID' cannot be part of a composite type"); //$NON-NLS-1$

        routing = route(definedType, V8_5_1, new FakePlatform().base("UUID").offerSingle("UUID").mixSingle() //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"), typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(routing.refusal(), routing.isRefused());
    }

    // ==================== #709: the composition itself (U3, C1, C10, C11) ====================

    @Test
    public void testCheckedAndNotifyTypesAreKeptByReferenceInOrder()
    {
        Type string = type("String"); //$NON-NLS-1$
        TypeItem anyRef = typeSet("AnyRef"); //$NON-NLS-1$
        TypeDescriptionExtension current = typeExtension(anyRef, MdPropertyState.CHECKED, string,
            MdPropertyState.NOTIFY);
        Type own = type("CatalogRef.Own"); //$NON-NLS-1$

        AdoptedPropertyRouter.Composition composition = AdoptedPropertyRouter.composeTypeExtension(current,
            Set.of("String"), List.of(own), description(own)); //$NON-NLS-1$

        List<TypeExtension> next = composition.next.getTypes();
        assertEquals(3, next.size());
        assertSame("the kept item refers to the same type", anyRef, next.get(0).getType()); //$NON-NLS-1$
        assertEquals(MdPropertyState.CHECKED, next.get(0).getState());
        assertSame(string, next.get(1).getType());
        assertEquals(MdPropertyState.NOTIFY, next.get(1).getState());
        assertSame(own, next.get(2).getType());
        assertEquals(MdPropertyState.EXTENDED, next.get(2).getState());
        assertTrue(composition.changed);
        assertEquals(List.of("CatalogRef.Own"), composition.added); //$NON-NLS-1$
    }

    @Test
    public void testAnExtendedBaseTypeIsKeptWithItsQualifiers()
    {
        // C1: EDT lets an extension mark a base type Extended with other qualifiers; a later call that adds
        // a type keeps it - only Extended NON-base types are replaced.
        Type string = type("String"); //$NON-NLS-1$
        TypeDescriptionExtension current = withString(typeExtension(string, MdPropertyState.EXTENDED), 20);
        Type own = type("CatalogRef.Own"); //$NON-NLS-1$

        AdoptedPropertyRouter.Composition composition = AdoptedPropertyRouter.composeTypeExtension(current,
            Set.of("String"), List.of(own), description(own)); //$NON-NLS-1$

        assertEquals(2, composition.next.getTypes().size());
        assertSame(string, composition.next.getTypes().get(0).getType());
        assertEquals(MdPropertyState.EXTENDED, composition.next.getTypes().get(0).getState());
        assertNotNull("its String qualifiers are kept", composition.next.getStringQualifiers()); //$NON-NLS-1$
        assertEquals(20, composition.next.getStringQualifiers().getLength());
        assertTrue(composition.removed.isEmpty());
    }

    @Test
    public void testTheExtendedAdditionsAreReplacedByTheLatestCall()
    {
        Type earlier = type("CatalogRef.Earlier"); //$NON-NLS-1$
        Type kept = type("String"); //$NON-NLS-1$
        TypeDescriptionExtension current = typeExtension(kept, MdPropertyState.CHECKED, earlier,
            MdPropertyState.EXTENDED);
        Type later = type("CatalogRef.Later"); //$NON-NLS-1$

        AdoptedPropertyRouter.Composition composition = AdoptedPropertyRouter.composeTypeExtension(current,
            Set.of("String"), List.of(later), description(later)); //$NON-NLS-1$

        assertEquals(List.of("String", "CatalogRef.Later"), names(composition.next)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("CatalogRef.Earlier"), composition.removed); //$NON-NLS-1$
        assertEquals(List.of("CatalogRef.Later"), composition.added); //$NON-NLS-1$

        // No additions leaves the kept types only.
        composition = AdoptedPropertyRouter.composeTypeExtension(current, Set.of("String"), List.of(), //$NON-NLS-1$
            description());
        assertEquals(List.of("String"), names(composition.next)); //$NON-NLS-1$
        assertEquals(List.of("CatalogRef.Earlier"), composition.removed); //$NON-NLS-1$
    }

    @Test
    public void testAnIdenticalRepeatChangesNothing()
    {
        Type earlier = type("CatalogRef.Own"); //$NON-NLS-1$
        TypeDescriptionExtension current = typeExtension(type("String"), MdPropertyState.CHECKED, earlier, //$NON-NLS-1$
            MdPropertyState.EXTENDED);
        Type again = type("CatalogRef.Own"); //$NON-NLS-1$

        AdoptedPropertyRouter.Composition composition = AdoptedPropertyRouter.composeTypeExtension(current,
            Set.of("String"), List.of(again), description(again)); //$NON-NLS-1$

        assertFalse("the same types in the same states are no change", composition.changed); //$NON-NLS-1$
        assertTrue(composition.added.isEmpty());
        assertEquals(List.of("CatalogRef.Own"), composition.stillExtended); //$NON-NLS-1$
        assertTrue(composition.removed.isEmpty());
    }

    @Test
    public void testQualifiersFollowTheirKind()
    {
        // An added Number takes the request's; a kept String keeps the current ones; the Date of a removed
        // addition goes with it.
        TypeDescriptionExtension current = withString(typeExtension(type("String"), MdPropertyState.CHECKED, //$NON-NLS-1$
            type("Date"), MdPropertyState.EXTENDED), 25); //$NON-NLS-1$
        current.setDateQualifiers(McoreFactory.eINSTANCE.createDateQualifiers());
        current.getDateQualifiers().setDateFractions(DateFractions.DATE);
        Type number = type("Number"); //$NON-NLS-1$
        TypeDescription request = description(number);
        NumberQualifiers numberQualifiers = McoreFactory.eINSTANCE.createNumberQualifiers();
        numberQualifiers.setPrecision(15);
        numberQualifiers.setScale(2);
        request.setNumberQualifiers(numberQualifiers);

        AdoptedPropertyRouter.Composition composition = AdoptedPropertyRouter.composeTypeExtension(current,
            Set.of("String"), List.of(number), request); //$NON-NLS-1$

        assertEquals(15, composition.next.getNumberQualifiers().getPrecision());
        assertEquals(2, composition.next.getNumberQualifiers().getScale());
        assertEquals(25, composition.next.getStringQualifiers().getLength());
        assertNull("the removed Date's qualifiers go with it", composition.next.getDateQualifiers()); //$NON-NLS-1$
        assertEquals(List.of("Date"), composition.removed); //$NON-NLS-1$
    }

    @Test
    public void testAKeptCheckedTypePassedAgainIsNoBaseType()
    {
        // C10: a type the extension checks that is not a base type is reported as such.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(typeSet("AnyRef"), MdPropertyState.CHECKED)); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().base("CatalogRef.Base", "String") //$NON-NLS-1$ //$NON-NLS-2$
            .offerTypeSet("AnyRef"), typeChange(typeSet("AnyRef"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        String note = routing.notes().get(0);
        assertTrue(note, note.contains("still checked by the extension: AnyRef")); //$NON-NLS-1$
        assertFalse(note, note.contains("already base types")); //$NON-NLS-1$
    }

    @Test
    public void testAKeptCheckedTypeWithOtherQualifiersIsRefused()
    {
        // A type the extension checks that is no longer a base type: passed as it stands it changes nothing,
        // passed with other qualifiers it is refused - modify_metadata leaves the checked types as they stand.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            withString(typeExtension(type("String"), MdPropertyState.CHECKED), 10)); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("CatalogRef.Base").offer("String"); //$NON-NLS-1$ //$NON-NLS-2$
        TypeDescription other = description(type("String")); //$NON-NLS-1$
        other.setStringQualifiers(stringQualifiers(20));
        Routing routing = route(definedType, V8_5_1, platform,
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.DEFINED_TYPE__TYPE, other, null));
        assertRefused(routing, "'String' (type.types[0]) is a type the extension checks", //$NON-NLS-1$
            "on adopted DefinedType.Amount (base configuration 'TestConfiguration'), as String(10)", //$NON-NLS-1$
            "leaves the types the extension checks as they stand", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a checked type is no base type", routing.refusal().contains("already a base type")); //$NON-NLS-1$ //$NON-NLS-2$

        TypeDescription same = description(type("String")); //$NON-NLS-1$
        same.setStringQualifiers(stringQualifiers(10));
        routing = route(definedType, V8_5_1, platform,
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.DEFINED_TYPE__TYPE, same, null));
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        assertTrue(routing.notes().get(0), routing.notes().get(0).contains("still checked by the extension: String")); //$NON-NLS-1$
    }

    @Test
    public void testTheWriteGoesToTheTypeExtensionNeverTheNativeType()
    {
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("String"), MdPropertyState.CHECKED)); //$NON-NLS-1$
        // Whatever the native type holds - a plain write of master before #709 left the requested one in the
        // model - it is left as it is, as EDT's type-extension dialog leaves it; while the type state is None,
        // EDT's .mdo writer leaves it out of the extension's file.
        TypeDescription nativeType = description(type("String"), type("CatalogRef.Base")); //$NON-NLS-1$ //$NON-NLS-2$
        definedType.setType(nativeType);
        Type own = catalogRefType("Own", ObjectBelonging.NATIVE); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().base("String").offer("String") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"), typeChange(own)); //$NON-NLS-1$
        AdoptedPropertyRouter.TypePlan plan = routing.decision(0).typePlan();

        AdoptedPropertyRouter.applyTypeExtension(definedType, plan);

        TypeDescriptionExtension written = ((DefinedTypeExtension)definedType.getExtension()).getTypeExtension();
        assertEquals(List.of("String", "CatalogRef.Own"), names(written)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(MdPropertyState.EXTENDED, written.getTypes().get(1).getState());
        assertSame("the native type is left as it is", nativeType, definedType.getType()); //$NON-NLS-1$
        assertEquals(List.of("String", "CatalogRef.Base"), names(nativeType)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(plan.outcome().changed());
        assertTrue(plan.outcome().note(), plan.outcome().note().contains("added (Extended): CatalogRef.Own")); //$NON-NLS-1$
        assertTrue(plan.outcome().note(), plan.outcome().note().endsWith(
            "get_metadata_details shows the defined type's native type, not its type extension")); //$NON-NLS-1$
    }

    @Test
    public void testANativeTypeTheTypeExtensionDoesNotHoldChangesNothing()
    {
        // The native type does not take part: an unchanged type extension is unchanged whatever the native
        // type holds (a repeat stays a no-op whatever an earlier write left there).
        TypeDescriptionExtension current = typeExtension(type("String"), MdPropertyState.CHECKED); //$NON-NLS-1$
        DefinedType definedType = adoptedDefinedType("Amount", current); //$NON-NLS-1$
        TypeDescription nativeType = description(type("String")); //$NON-NLS-1$
        definedType.setType(nativeType);
        Routing routing = route(definedType, V8_5_1, new FakePlatform().base("String").offer("String"), //$NON-NLS-1$ //$NON-NLS-2$
            typeChange(type("String"))); //$NON-NLS-1$
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        assertTrue(routing.notes().get(0), routing.notes().get(0).startsWith("'type' of DefinedType.Amount unchanged")); //$NON-NLS-1$
        assertSame(nativeType, definedType.getType());
        assertSame(current, ((DefinedTypeExtension)definedType.getExtension()).getTypeExtension());
    }

    @Test
    public void testAnEmptyResultUnsetsTheTypeExtension()
    {
        // C11: EDT's dialog leaves the type extension unset when nothing is selected - never an empty one.
        Type earlier = type("CatalogRef.Own"); //$NON-NLS-1$
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension(earlier, MdPropertyState.EXTENDED)); //$NON-NLS-1$
        AdoptedPropertyRouter.TypePlan plan = plan(definedType, new FakePlatform().base("String") //$NON-NLS-1$
            .offer("String"), typeChange(type("String"))); //$NON-NLS-1$ //$NON-NLS-2$

        AdoptedPropertyRouter.applyTypeExtension(definedType, plan);

        DefinedTypeExtension extension = (DefinedTypeExtension)definedType.getExtension();
        assertNull("nothing left: the feature is unset", extension.getTypeExtension()); //$NON-NLS-1$
        assertFalse(extension.eIsSet(DT_TYPE_EXTENSION));
        assertTrue(plan.outcome().note(), plan.outcome().note().contains("earlier additions removed: CatalogRef.Own")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnchangedTypeExtensionIsNotWritten()
    {
        TypeDescriptionExtension current = typeExtension(type("String"), MdPropertyState.CHECKED); //$NON-NLS-1$
        DefinedType definedType = adoptedDefinedType("Amount", current); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, new FakePlatform().base("String").offer("String"), //$NON-NLS-1$ //$NON-NLS-2$
            typeChange(type("String"))); //$NON-NLS-1$
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        assertSame("the type extension is the same object", current, //$NON-NLS-1$
            ((DefinedTypeExtension)definedType.getExtension()).getTypeExtension());
        assertTrue(routing.notes().get(0), routing.notes().get(0).startsWith("'type' of DefinedType.Amount unchanged")); //$NON-NLS-1$
    }

    @Test
    public void testTheConsentWarningSaysWhatIsAddedAndRemoved()
    {
        // C8: a pure addition never claims the retype's data loss.
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        AdoptedPropertyRouter.TypePlan plan = plan(definedType, new FakePlatform().offer("CatalogRef.Own"), //$NON-NLS-1$
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        assertTrue(plan.consentWarning(), plan.consentWarning().startsWith("Adds CatalogRef.Own")); //$NON-NLS-1$
        assertFalse(plan.consentWarning(), plan.consentWarning().contains("drop")); //$NON-NLS-1$

        DefinedType withEarlier = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("CatalogRef.Earlier"), MdPropertyState.EXTENDED)); //$NON-NLS-1$
        plan = plan(withEarlier, new FakePlatform().offer("CatalogRef.Own"), //$NON-NLS-1$
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        assertTrue(plan.consentWarning(), plan.consentWarning().contains("Removes the earlier additions CatalogRef.Earlier")); //$NON-NLS-1$
        assertTrue(plan.consentWarning(), plan.consentWarning().contains("can drop values")); //$NON-NLS-1$
    }

    // ==================== #710: an adopted event subscription's source (U2, U4) ====================

    @Test
    public void testASourceIsRoutedOnlyWhereEdtOffersItExtensible()
    {
        // The 8.3.16 / 8.3.17 boundary is EDT's, not the router's: EDT's property-type delegates answer EXTENSIBLE
        // for an event subscription's source from v8.3.17 and nothing below it (their bytecode, both lines). The
        // fake answers as those delegates do, so this pins that the router follows the answer and that its refusal
        // names the platform and 8.3.17.
        EventSubscription subscription = adoptedSubscription("OnWrite", null, null); //$NON-NLS-1$
        Type base = type("CatalogObject.Goods"); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", base); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(subscription, FQN_SUB, Version.V8_3_17, platform,
            sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.EXTENDED_SOURCE, routing.decision(0).kind());

        FakePlatform below = new FakePlatform().propertyTypes(MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE)
            .base("CatalogObject.Goods").buildable("CatalogObject.Goods", base); //$NON-NLS-1$ //$NON-NLS-2$
        routing = route(subscription, FQN_SUB, Version.V8_3_16, below, sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$
        assertRefused(routing, "platform 8.3.16 cannot change", "8.3.17"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEachGuardOfTheSourceRouteRefusesNamingTheBase()
    {
        // One row per guard of the source route before anything is planned: the refusal names the property, the
        // object and the base project, and says that nothing was changed.
        Type base = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription withoutBlock = adoptedSubscription("OnWrite", description(base), null); //$NON-NLS-1$
        withoutBlock.setExtension(null);
        Object[][] rows = {
            { "the version is not known yet", adoptedSubscription("OnWrite", description(base), null), null, //$NON-NLS-1$ //$NON-NLS-2$
                new FakePlatform(), "the extension project's platform version, which decides what an extension may " //$NON-NLS-1$
                    + "change, is not known yet; retry once list_projects reports it ready" }, //$NON-NLS-1$
            { "no extension block", withoutBlock, V8_5_1, new FakePlatform(), //$NON-NLS-1$
                "but has no extension block of its kind" }, //$NON-NLS-1$
            { "no property-type service", adoptedSubscription("OnWrite", description(base), null), V8_5_1, //$NON-NLS-1$ //$NON-NLS-2$
                new FakePlatform().withoutPropertyTypes(), "EDT's IMdPropertyTypeProvider service" }, //$NON-NLS-1$
            { "no adopted-property access", adoptedSubscription("OnWrite", description(base), null), V8_5_1, //$NON-NLS-1$ //$NON-NLS-2$
                new FakePlatform().access(null), "EDT's IMdAdoptedPropertyAccess service" }, //$NON-NLS-1$
            { "the base object is not found", adoptedSubscription("OnWrite", description(base), null), V8_5_1, //$NON-NLS-1$ //$NON-NLS-2$
                new FakePlatform().baseNotFound(), "EDT does not find the object of adopted " + FQN_SUB //$NON-NLS-1$
                    + " in the base configuration 'TestConfiguration'" }, //$NON-NLS-1$
            { "the base is not ready", adoptedSubscription("OnWrite", description(base), null), V8_5_1, //$NON-NLS-1$ //$NON-NLS-2$
                new FakePlatform().baseNotReady(), "the base configuration 'TestConfiguration' is not ready yet" }, //$NON-NLS-1$
            { "the base cannot be read", adoptedSubscription("OnWrite", description(base), null), V8_5_1, //$NON-NLS-1$ //$NON-NLS-2$
                new FakePlatform().baseUnavailable("a lock timed out"), //$NON-NLS-1$
                "could not be read from the base configuration 'TestConfiguration' (a lock timed out)" }, //$NON-NLS-1$
        };
        for (Object[] row : rows)
        {
            Routing routing = route((EventSubscription)row[1], FQN_SUB, (Version)row[2], (FakePlatform)row[3],
                sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$
            assertTrue(row[0] + ": the change must be refused", routing.isRefused()); //$NON-NLS-1$
            for (String fragment : List.of("'source' of " + FQN_SUB + " was not written: ", "'TestConfiguration'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Nothing was changed.", (String)row[4])) //$NON-NLS-1$
            {
                assertTrue(row[0] + ": the refusal must say \"" + fragment + "\": " + routing.refusal(), //$NON-NLS-1$ //$NON-NLS-2$
                    routing.refusal().contains(fragment));
            }
        }
    }

    @Test
    public void testABaseSourceTheExtensionCannotCarryIsRefusedNamingTheObject()
    {
        // The BSP shape: the base subscription also handles a document the extension has not adopted, and the
        // subscription's own source does not hold it: an adoption leaves that source empty (EDT 2026.2), and an
        // earlier extended write is what fills it.
        EventSubscription subscription = adoptedSubscription("OnWrite", null, null); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods", "DocumentObject.Invoice") //$NON-NLS-1$ //$NON-NLS-2$
            .buildable("CatalogObject.Goods", type("CatalogObject.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(subscription, FQN_SUB, V8_5_1, platform, sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$
        assertRefused(routing, "'DocumentObject.Invoice'", "names Document.Invoice", "not adopted", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "no earlier extended write left it in the subscription's own source in the extension", //$NON-NLS-1$
            "Adopt Document.Invoice with adopt_metadata_object", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the refusal must not suggest that EDT keeps a copy of the base sources in the extension: " //$NON-NLS-1$
            + routing.refusal(), routing.refusal().contains("copy of the base sources")); //$NON-NLS-1$

        // ... but a base source an earlier extended write left in the subscription's own source is carried as it is.
        Type copied = type("DocumentObject.Invoice"); //$NON-NLS-1$
        EventSubscription withCopy = adoptedSubscription("OnWrite", description(copied), null); //$NON-NLS-1$
        routing = route(withCopy, FQN_SUB, V8_5_1, platform, sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.isRefused());
    }

    @Test
    public void testASourceWithoutAdditionsWhileItFollowsTheBaseIsUnchanged()
    {
        // C9: reported unchanged, not routed (and so not applied).
        EventSubscription subscription = adoptedSubscription("OnWrite", null, MdPropertyState.NONE); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods") //$NON-NLS-1$
            .buildable("CatalogObject.Goods", type("CatalogObject.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(subscription, FQN_SUB, V8_5_1, platform, sourceChange(type("CatalogObject.Goods"))); //$NON-NLS-1$
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        assertTrue(routing.notes().get(0), routing.notes().get(0).contains("unchanged")); //$NON-NLS-1$
        assertTrue(routing.notes().get(0), routing.notes().get(0).contains("state None")); //$NON-NLS-1$
    }

    @Test
    public void testAnExtendedSourceIsTheBaseSourcesPlusTheAdditions()
    {
        Type copied = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", description(copied), null); //$NON-NLS-1$
        Type addition = type("CatalogObject.Own"); //$NON-NLS-1$
        FakeAccess access = new FakeAccess();
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods") //$NON-NLS-1$
            .buildable("CatalogObject.Goods", type("CatalogObject.Goods")).access(access); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(subscription, FQN_SUB, V8_5_1, platform, sourceChange(copied, addition, addition));

        AdoptedPropertyRouter.SourcePlan plan = routing.decision(0).sourcePlan();
        AdoptedPropertyRouter.applySource(subscription, plan);

        List<TypeItem> written = subscription.getSource().getTypes();
        assertEquals(2, written.size());
        assertSame("a base source the subscription's own source holds is taken from there", copied, //$NON-NLS-1$
            written.get(0));
        assertSame(addition, written.get(1));
        assertEquals(List.of("setValue source EXTENSIBLE"), access.calls); //$NON-NLS-1$
        assertEquals(MdPropertyState.EXTENDED, ((EventSubscriptionExtension)subscription.getExtension()).getSource());
        assertTrue(plan.outcome().note(), plan.outcome().note().contains("its source is Extended")); //$NON-NLS-1$
        assertTrue(plan.consentWarning(), plan.consentWarning().contains("no stored data changes")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingBaseSourceIsTheItemBuiltInTheExtension()
    {
        EventSubscription subscription = adoptedSubscription("OnWrite", null, null); //$NON-NLS-1$
        Type built = type("CatalogObject.Goods"); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", built); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(subscription, FQN_SUB, V8_5_1, platform, sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$

        AdoptedPropertyRouter.applySource(subscription, routing.decision(0).sourcePlan());

        assertSame(built, subscription.getSource().getTypes().get(0));
        assertEquals(2, subscription.getSource().getTypes().size());
    }

    @Test
    public void testARepeatReplacesTheEarlierAdditions()
    {
        Type base = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", //$NON-NLS-1$
            description(base, type("CatalogObject.Earlier")), MdPropertyState.EXTENDED); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", base); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(subscription, FQN_SUB, V8_5_1, platform, sourceChange(type("CatalogObject.Later"))); //$NON-NLS-1$

        AdoptedPropertyRouter.applySource(subscription, routing.decision(0).sourcePlan());

        assertEquals(List.of("CatalogObject.Goods", "CatalogObject.Later"), //$NON-NLS-1$ //$NON-NLS-2$
            names(subscription.getSource()));
    }

    @Test
    public void testAnIdenticalExtendedSourceIsUnchangedAndNotRewritten()
    {
        // The routing reports an identical extended source unchanged, so nothing is applied ...
        Type base = type("CatalogObject.Goods"); //$NON-NLS-1$
        Type own = type("CatalogObject.Own"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", description(base, own), //$NON-NLS-1$
            MdPropertyState.EXTENDED);
        FakeAccess access = new FakeAccess();
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", base) //$NON-NLS-1$ //$NON-NLS-2$
            .access(access);
        Routing routing = route(subscription, FQN_SUB, V8_5_1, platform, sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.DROP, routing.decision(0).kind());
        assertEquals(List.of("'source' of EventSubscription.OnWrite unchanged: it is already Extended with the base " //$NON-NLS-1$
            + "sources plus CatalogObject.Own"), routing.notes()); //$NON-NLS-1$

        // ... and the write itself never rewrites a source that already holds what its plan asks.
        AdoptedPropertyRouter.SourcePlan plan = new AdoptedPropertyRouter.SourcePlan(FQN_SUB,
            MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE, List.of("CatalogObject.Goods"), Map.of(), //$NON-NLS-1$
            List.of(type("CatalogObject.Own")), List.of("CatalogObject.Own"), access, //$NON-NLS-1$ //$NON-NLS-2$
            Set.of("CatalogObject.Goods", "CatalogObject.Own"), MdPropertyState.EXTENDED); //$NON-NLS-1$ //$NON-NLS-2$
        AdoptedPropertyRouter.applySource(subscription, plan);
        assertTrue("no write: " + access.calls, access.calls.isEmpty()); //$NON-NLS-1$
        assertFalse(plan.outcome().changed());
        assertTrue(plan.outcome().note(), plan.outcome().note().contains("unchanged: it is already Extended")); //$NON-NLS-1$
        assertEquals(List.of(base, own), subscription.getSource().getTypes());
    }

    @Test
    public void testNoAdditionsWhileExtendedReturnsTheSourceToTheBase()
    {
        Type base = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", //$NON-NLS-1$
            description(base, type("CatalogObject.Earlier")), MdPropertyState.EXTENDED); //$NON-NLS-1$
        FakeAccess access = new FakeAccess();
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", base) //$NON-NLS-1$ //$NON-NLS-2$
            .access(access);
        Routing routing = route(subscription, FQN_SUB, V8_5_1, platform, sourceChange(base));
        assertEquals(Decision.Kind.EXTENDED_SOURCE, routing.decision(0).kind());

        AdoptedPropertyRouter.SourcePlan plan = routing.decision(0).sourcePlan();
        AdoptedPropertyRouter.applySource(subscription, plan);

        assertEquals(List.of("CatalogObject.Goods"), names(subscription.getSource())); //$NON-NLS-1$
        assertEquals(List.of("setPropertyState source NONE"), access.calls); //$NON-NLS-1$
        assertEquals(MdPropertyState.NONE, ((EventSubscriptionExtension)subscription.getExtension()).getSource());
        assertTrue(plan.outcome().note(), plan.outcome().note().contains("no longer extended (state None)")); //$NON-NLS-1$
    }

    @Test
    public void testComposeSourceRefusesABaseSourceItCannotCarry()
    {
        try
        {
            AdoptedPropertyRouter.composeSource(null, List.of("DocumentObject.Invoice"), Map.of(), List.of(), FQN_SUB); //$NON-NLS-1$
            fail("a base source carried neither way must roll the write back"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("DocumentObject.Invoice")); //$NON-NLS-1$
        }
        Type copied = type("CatalogObject.Goods"); //$NON-NLS-1$
        Type addition = type("CatalogObject.Own"); //$NON-NLS-1$
        Map<String, TypeItem> built = new LinkedHashMap<>();
        built.put("CatalogObject.Goods", type("CatalogObject.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        List<TypeItem> next = AdoptedPropertyRouter.composeSource(description(copied), List.of("CatalogObject.Goods"), //$NON-NLS-1$
            built, List.of(addition, type("CatalogObject.Own")), FQN_SUB); //$NON-NLS-1$
        assertEquals(2, next.size());
        assertSame("the item the own source holds wins over the built one", copied, next.get(0)); //$NON-NLS-1$
        assertSame("additions are deduplicated", addition, next.get(1)); //$NON-NLS-1$
    }

    @Test
    public void testAWriteWhoseBlockIsGoneRollsBackWithOurOwnRefusal()
    {
        // Between the routing and the write the extension block went away: the write rolls back by throwing our
        // own refusal - marked, so the log keeps it as a refusal (INFO, no stack), not as a platform failure.
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        AdoptedPropertyRouter.TypePlan typePlan = plan(definedType, new FakePlatform().offer("CatalogRef.Own"), //$NON-NLS-1$
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        definedType.setExtension(null);
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applyTypeExtension(definedType, typePlan),
            "'type' of DefinedType.Amount", "no extension block", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("no outcome: nothing was written", typePlan.outcome()); //$NON-NLS-1$

        EventSubscription subscription = adoptedSubscription("OnWrite", null, null); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods") //$NON-NLS-1$
            .buildable("CatalogObject.Goods", type("CatalogObject.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        AdoptedPropertyRouter.SourcePlan sourcePlan = route(subscription, FQN_SUB, V8_5_1, platform,
            sourceChange(type("CatalogObject.Own"))).decision(0).sourcePlan(); //$NON-NLS-1$
        subscription.setExtension(null);
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applySource(subscription, sourcePlan),
            "'source' of EventSubscription.OnWrite", "no extension block", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("nothing was written", subscription.getSource()); //$NON-NLS-1$
    }

    // ==================== the write writes what the routing judged (C3) ====================

    @Test
    public void testATypeExtensionEditedWhileTheCallWaitedIsRefusedAndKept()
    {
        // C3: the consent gate can hold a call between its routing and its write, and EDT's type-extension
        // editor can change the type extension meanwhile. The write refuses - and rolls back - when the type
        // extension is not the one the routing judged: a type the user added is never silently removed, and a
        // composition the routing never judged is never written.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("String"), MdPropertyState.CHECKED)); //$NON-NLS-1$
        AdoptedPropertyRouter.TypePlan plan = plan(definedType, new FakePlatform().base("String").offer("String") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"), typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        TypeDescriptionExtension edited = ((DefinedTypeExtension)definedType.getExtension()).getTypeExtension();
        TypeExtension user = MdClassExtensionFactory.eINSTANCE.createTypeExtension();
        user.setType(type("CatalogRef.User")); //$NON-NLS-1$
        user.setState(MdPropertyState.EXTENDED);
        edited.getTypes().add(user);

        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applyTypeExtension(definedType, plan),
            "'type' of DefinedType.Amount was not written: its type extension changed while the call waited; " //$NON-NLS-1$
                + "retry the call. Nothing was changed."); //$NON-NLS-1$
        assertNull("nothing was written", plan.outcome()); //$NON-NLS-1$
        assertSame(edited, ((DefinedTypeExtension)definedType.getExtension()).getTypeExtension());
        assertEquals("the user's edit stays", List.of("String", "CatalogRef.User"), names(edited)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // A state alone, and qualifiers alone, are a change too.
        FakePlatform platform = new FakePlatform().baseWithString(10, "String").offer("String") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"); //$NON-NLS-1$
        DefinedType restated = adoptedDefinedType("Amount", //$NON-NLS-1$
            withString(typeExtension(type("String"), MdPropertyState.CHECKED), 10)); //$NON-NLS-1$
        AdoptedPropertyRouter.TypePlan statePlan = plan(restated, platform,
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        ((DefinedTypeExtension)restated.getExtension()).getTypeExtension().getTypes().get(0)
            .setState(MdPropertyState.NOTIFY);
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applyTypeExtension(restated, statePlan),
            "its type extension changed while the call waited"); //$NON-NLS-1$
        assertEquals(MdPropertyState.NOTIFY, ((DefinedTypeExtension)restated.getExtension()).getTypeExtension()
            .getTypes().get(0).getState());

        DefinedType requalified = adoptedDefinedType("Amount", //$NON-NLS-1$
            withString(typeExtension(type("String"), MdPropertyState.CHECKED), 10)); //$NON-NLS-1$
        AdoptedPropertyRouter.TypePlan qualifierPlan = plan(requalified, platform,
            typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        ((DefinedTypeExtension)requalified.getExtension()).getTypeExtension().getStringQualifiers().setLength(20);
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applyTypeExtension(requalified, qualifierPlan),
            "its type extension changed while the call waited"); //$NON-NLS-1$
        assertEquals(List.of("String"), names(((DefinedTypeExtension)requalified.getExtension()).getTypeExtension())); //$NON-NLS-1$
    }

    @Test
    public void testASourceEditedWhileTheCallWaitedIsRefusedAndKept()
    {
        // C3 for #710: the source copy and its state the routing judged are what the write finds, or the write
        // refuses and rolls back.
        Type base = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", description(base), null); //$NON-NLS-1$
        FakeAccess access = new FakeAccess();
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods") //$NON-NLS-1$
            .buildable("CatalogObject.Goods", type("CatalogObject.Goods")).access(access); //$NON-NLS-1$ //$NON-NLS-2$
        AdoptedPropertyRouter.SourcePlan plan = route(subscription, FQN_SUB, V8_5_1, platform,
            sourceChange(type("CatalogObject.Own"))).decision(0).sourcePlan(); //$NON-NLS-1$
        // EDT's property editor extends the source meanwhile.
        subscription.getSource().getTypes().add(type("CatalogObject.User")); //$NON-NLS-1$
        ((EventSubscriptionExtension)subscription.getExtension()).setSource(MdPropertyState.EXTENDED);

        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applySource(subscription, plan),
            "'source' of EventSubscription.OnWrite was not written: its source changed while the call waited; " //$NON-NLS-1$
                + "retry the call. Nothing was changed."); //$NON-NLS-1$
        assertTrue("nothing was written: " + access.calls, access.calls.isEmpty()); //$NON-NLS-1$
        assertNull(plan.outcome());
        assertEquals(List.of("CatalogObject.Goods", "CatalogObject.User"), names(subscription.getSource())); //$NON-NLS-1$ //$NON-NLS-2$

        // The state alone, and the copy alone, are a change too.
        EventSubscription restated = adoptedSubscription("OnWrite", description(type("CatalogObject.Goods")), null); //$NON-NLS-1$ //$NON-NLS-2$
        AdoptedPropertyRouter.SourcePlan statePlan = route(restated, FQN_SUB, V8_5_1, platform,
            sourceChange(type("CatalogObject.Own"))).decision(0).sourcePlan(); //$NON-NLS-1$
        ((EventSubscriptionExtension)restated.getExtension()).setSource(MdPropertyState.EXTENDED);
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applySource(restated, statePlan),
            "its source changed while the call waited"); //$NON-NLS-1$

        EventSubscription recopied = adoptedSubscription("OnWrite", description(type("CatalogObject.Goods")), null); //$NON-NLS-1$ //$NON-NLS-2$
        AdoptedPropertyRouter.SourcePlan copyPlan = route(recopied, FQN_SUB, V8_5_1, platform,
            sourceChange(type("CatalogObject.Own"))).decision(0).sourcePlan(); //$NON-NLS-1$
        recopied.getSource().getTypes().add(type("CatalogObject.User")); //$NON-NLS-1$
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applySource(recopied, copyPlan),
            "its source changed while the call waited"); //$NON-NLS-1$
        assertTrue("nothing was written: " + access.calls, access.calls.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAnUnsetSourceStateAndNoneAreTheSameStateToTheWrite()
    {
        // The routing reads an unset source state as None (both "follows the base"), so a write whose plan read it
        // unset and that finds an explicit None goes ahead - only a change of what the routing judged refuses.
        Type base = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", description(base), MdPropertyState.NONE); //$NON-NLS-1$
        FakeAccess access = new FakeAccess();
        Type own = type("CatalogObject.Own"); //$NON-NLS-1$
        AdoptedPropertyRouter.SourcePlan plan = new AdoptedPropertyRouter.SourcePlan(FQN_SUB,
            MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE, List.of("CatalogObject.Goods"), Map.of(), List.of(own), //$NON-NLS-1$
            List.of("CatalogObject.Own"), access, Set.of("CatalogObject.Goods"), null); //$NON-NLS-1$ //$NON-NLS-2$

        AdoptedPropertyRouter.applySource(subscription, plan);

        assertEquals(List.of("setValue source EXTENSIBLE"), access.calls); //$NON-NLS-1$
        assertEquals(List.of(base, own), subscription.getSource().getTypes());
        assertTrue(plan.outcome().changed());
    }

    // ==================== the base is read in a read task of the BASE model (C6) ====================

    @Test
    public void testTheBaseIsReadInAReadTaskOfTheBaseModel()
    {
        boolean[] inReadTask = { false };
        IBmModel baseModel = mock(IBmModel.class);
        IBmTransaction tx = mock(IBmTransaction.class);
        doAnswer(invocation -> {
            inReadTask[0] = true;
            try
            {
                return ((IBmTask<?>)invocation.getArgument(0)).execute(tx, null);
            }
            finally
            {
                inReadTask[0] = false;
            }
        }).when(baseModel).executeReadonlyTask(any());
        DefinedType base = MdClassFactory.eINSTANCE.createDefinedType();
        TypeDescription baseType = description(type("String"), type("CatalogRef.Base")); //$NON-NLS-1$ //$NON-NLS-2$
        baseType.setStringQualifiers(stringQualifiers(10));
        base.setType(baseType);
        DefinedType found = mock(DefinedType.class, withSettings().extraInterfaces(IBmObject.class));
        doReturn(Long.valueOf(42L)).when((IBmObject)found).bmGetId();
        doReturn(base).when(tx).getObjectById(42L);
        DefinedType adopted = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        List<String> calledInReadTask = new ArrayList<>();
        IModelObjectAdopter adopter = mock(IModelObjectAdopter.class);
        doAnswer(invocation -> {
            calledInReadTask.add(String.valueOf(inReadTask[0]));
            return found;
        }).when(adopter).getSource(adopted);

        AdoptedPropertyRouter.BaseRead read = AdoptedPropertyRouter.readBase(baseModel, adopter, adopted,
            MdClassPackage.Literals.DEFINED_TYPE__TYPE);

        assertEquals("the adopter is asked inside the base model's read task", List.of("true"), calledInReadTask); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(AdoptedPropertyRouter.BaseRead.Status.FOUND, read.status);
        assertEquals(List.of("String", "CatalogRef.Base"), read.typeNames); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("only values cross the boundary", Integer.valueOf(10), read.qualifiers.stringLength); //$NON-NLS-1$
        verify(baseModel, never()).execute(any());
    }

    @Test
    public void testABaseObjectEdtDoesNotFindIsNotFound()
    {
        IBmModel baseModel = mock(IBmModel.class);
        doAnswer(invocation -> ((IBmTask<?>)invocation.getArgument(0)).execute(mock(IBmTransaction.class), null))
            .when(baseModel).executeReadonlyTask(any());
        DefinedType adopted = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        IModelObjectAdopter adopter = mock(IModelObjectAdopter.class);
        assertEquals(AdoptedPropertyRouter.BaseRead.Status.NOT_FOUND, AdoptedPropertyRouter.readBase(baseModel,
            adopter, adopted, MdClassPackage.Literals.DEFINED_TYPE__TYPE).status);
        doAnswer(invocation -> {
            throw new IllegalArgumentException("Illegal adopted object project"); //$NON-NLS-1$
        }).when(adopter).getSource(adopted);
        AdoptedPropertyRouter.BaseRead failed = AdoptedPropertyRouter.readBase(baseModel, adopter, adopted,
            MdClassPackage.Literals.DEFINED_TYPE__TYPE);
        assertEquals(AdoptedPropertyRouter.BaseRead.Status.UNAVAILABLE, failed.status);
        assertEquals("the refusal says what failed", "Illegal adopted object project", failed.detail); //$NON-NLS-1$ //$NON-NLS-2$

        // A platform failure is not one of our refusals: its own message - or the first one down its cause
        // chain, or its class - is the detail, never "null".
        doAnswer(invocation -> {
            throw new IllegalStateException((String)null, new RuntimeException("lock timed out")); //$NON-NLS-1$
        }).when(adopter).getSource(adopted);
        assertEquals("lock timed out", AdoptedPropertyRouter.readBase(baseModel, adopter, adopted, //$NON-NLS-1$
            MdClassPackage.Literals.DEFINED_TYPE__TYPE).detail);
        doAnswer(invocation -> {
            throw new IllegalStateException();
        }).when(adopter).getSource(adopted);
        assertEquals("IllegalStateException", AdoptedPropertyRouter.readBase(baseModel, adopter, adopted, //$NON-NLS-1$
            MdClassPackage.Literals.DEFINED_TYPE__TYPE).detail);
    }

    @Test
    public void testADescriptionOfAFailureEndsOnALoopingCauseChain()
    {
        // Throwable.initCause allows a cycle; the walk down the causes is bounded, so it ends on the class.
        IllegalStateException first = new IllegalStateException();
        RuntimeException second = new RuntimeException();
        first.initCause(second);
        second.initCause(first);
        assertEquals("IllegalStateException", AdoptedPropertyRouter.describe(first)); //$NON-NLS-1$
        assertEquals("a message down the chain is found", "deep", AdoptedPropertyRouter.describe( //$NON-NLS-1$ //$NON-NLS-2$
            new IllegalStateException((String)null, new RuntimeException(" ", new RuntimeException("deep"))))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== the content / predefined guard ====================

    @Test
    public void testAnAdoptedSubsystemsContentIsRefusedOnlyWhileNoneWordedByTheVersion()
    {
        // EDT's writer drops an adopted subsystem's content list while its state is None, on every platform version
        // (it reads none); the version only decides what the refusal offers - EDT's property-type delegates offer no
        // state for it below 8.3.24 and EXTENSIBLE from 8.3.24 (both EDT lines).
        EStructuralFeature content = MdClassPackage.Literals.SUBSYSTEM__CONTENT;
        Subsystem subsystem = adoptedSubsystem("Sales", MdPropertyState.NONE); //$NON-NLS-1$
        String below = AdoptedPropertyRouter.refusalIfDropped(subsystem, content, "Subsystem.Sales", null, //$NON-NLS-1$
            Version.V8_3_23, new FakePlatform().propertyTypes(content));
        assertNotNull("a None content is refused below 8.3.24 too", below); //$NON-NLS-1$
        assertTrue(below, below.contains("'content' of Subsystem.Sales was not written")); //$NON-NLS-1$
        assertTrue(below, below.contains("An extension for platform 8.3.23 cannot change 'content'")); //$NON-NLS-1$
        String from = AdoptedPropertyRouter.refusalIfDropped(subsystem, content, "Subsystem.Sales", null, //$NON-NLS-1$
            Version.V8_3_24, new FakePlatform().propertyTypes(content, MdPropertyType.EXTENSIBLE));
        assertNotNull("a None content is refused from 8.3.24", from); //$NON-NLS-1$
        assertTrue(from, from.contains("mark 'content' Extended in EDT's property editor")); //$NON-NLS-1$

        ((SubsystemExtension)subsystem.getExtension()).setContent(MdPropertyState.EXTENDED);
        for (Version version : List.of(Version.V8_3_23, Version.V8_3_24, V8_5_1))
        {
            assertNull("an Extended content is written at " + version, AdoptedPropertyRouter.refusalIfDropped( //$NON-NLS-1$
                subsystem, content, "Subsystem.Sales", null, version, new FakePlatform())); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheGuardOfTheWritersOutsideTheBatch()
    {
        Catalog catalog = catalogWithBlock(ObjectBelonging.ADOPTED);
        String refusal = AdoptedPropertyRouter.refusalIfDropped(catalog, MdClassPackage.Literals.CATALOG__OWNERS,
            "Catalog.Goods", null, V8_5_1, new FakePlatform()); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("'owners' of Catalog.Goods")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("can only check 'owners'")); //$NON-NLS-1$
        ((CatalogExtension)catalog.getExtension()).setOwners(MdPropertyState.CHECKED);
        assertNull("a Checked list is written", AdoptedPropertyRouter.refusalIfDropped(catalog, //$NON-NLS-1$
            MdClassPackage.Literals.CATALOG__OWNERS, "Catalog.Goods", null, V8_5_1, new FakePlatform())); //$NON-NLS-1$
        assertNull("no feature, no guard", AdoptedPropertyRouter.refusalIfDropped(catalog, null, "Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
            null, V8_5_1, new FakePlatform()));
    }

    // ==================== round 3: what changed while the call waited, and what cannot be read ====================

    @Test
    public void testABaseSourceAddedWhileTheCallWaitedRefusesTheWrite()
    {
        // T1: the base configuration is another project; the plan composes the extended source from the base
        // sources it read. A base source added meanwhile (while the call waited for consent) would be left out of
        // the written value - the base value is read again before the write, and a changed one refuses the call.
        Type goods = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", null, null); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", goods) //$NON-NLS-1$ //$NON-NLS-2$
            .buildable("CatalogObject.Other", type("CatalogObject.Other")); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(subscription, FQN_SUB, V8_5_1, platform, sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$
        assertEquals(Decision.Kind.EXTENDED_SOURCE, routing.decision(0).kind());
        int reads = platform.baseReads();

        assertNull("the same base: the write goes on", routing.baseChangedRefusal()); //$NON-NLS-1$
        assertEquals("the base is read again", reads + 1, platform.baseReads()); //$NON-NLS-1$

        platform.base("CatalogObject.Other", "CatalogObject.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        String refusal = routing.baseChangedRefusal();
        assertNotNull("a base source added meanwhile refuses the write", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("'source' of EventSubscription.OnWrite was not written: the base " //$NON-NLS-1$
            + "configuration 'TestConfiguration' changed the base value of 'source' while the call waited " //$NON-NLS-1$
            + "(CatalogObject.Goods when the call was checked, CatalogObject.Other, CatalogObject.Goods now)")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("retry the call. Nothing was changed.")); //$NON-NLS-1$

        // The order of a type description's types carries no meaning: the same sources in another order are the
        // same base.
        platform.base("CatalogObject.Goods", "CatalogObject.Other"); //$NON-NLS-1$ //$NON-NLS-2$
        Routing again = route(adoptedSubscription("OnWrite", null, null), FQN_SUB, V8_5_1, platform, //$NON-NLS-1$
            sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$
        assertEquals(Decision.Kind.EXTENDED_SOURCE, again.decision(0).kind());
        platform.base("CatalogObject.Other", "CatalogObject.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("the same sources in another order", again.baseChangedRefusal()); //$NON-NLS-1$

        // A base that cannot be read any more refuses as the routing would.
        platform.baseNotReady();
        String notReady = again.baseChangedRefusal();
        assertNotNull(notReady);
        assertTrue(notReady, notReady.contains("is not ready yet")); //$NON-NLS-1$
    }

    @Test
    public void testABaseTypeChangedWhileTheCallWaitedRefusesTheWrite()
    {
        // T1 for the defined type: the plan keeps the base types and judged them by their qualifiers.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            withString(typeExtension(type("String"), MdPropertyState.CHECKED), 10)); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().baseWithString(10, "String").offer("String") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"); //$NON-NLS-1$
        Routing routing = route(definedType, V8_5_1, platform, typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        assertEquals(Decision.Kind.TYPE_EXTENSION, routing.decision(0).kind());
        assertNull(routing.baseChangedRefusal());

        platform.baseWithString(20, "String"); //$NON-NLS-1$
        String qualifiers = routing.baseChangedRefusal();
        assertNotNull("other qualifiers of the same base type refuse the write", qualifiers); //$NON-NLS-1$
        assertTrue(qualifiers, qualifiers.contains("'type' of DefinedType.Amount was not written: the base " //$NON-NLS-1$
            + "configuration 'TestConfiguration' changed the base value of 'type' while the call waited (the same " //$NON-NLS-1$
            + "types with other qualifiers)")); //$NON-NLS-1$

        platform.baseWithString(10, "String", "Number"); //$NON-NLS-1$ //$NON-NLS-2$
        String types = routing.baseChangedRefusal();
        assertNotNull("a base type added meanwhile refuses the write", types); //$NON-NLS-1$
        assertTrue(types, types.contains("(String when the call was checked, String, Number now)")); //$NON-NLS-1$
    }

    @Test
    public void testABaseTypeThatBecameATypeSetIsNotCalledAQualifierChange()
    {
        // The label of a changed base value is chosen from what was compared: the same names with a type turned
        // into a type set are not 'the same types with other qualifiers'.
        AdoptedPropertyRouter.BaseRead planned = AdoptedPropertyRouter.BaseRead.found(List.of("Characteristic.Kinds"), //$NON-NLS-1$
            List.of(Boolean.FALSE), AdoptedPropertyRouter.Qualifiers.NONE);
        AdoptedPropertyRouter.BaseRead now = AdoptedPropertyRouter.BaseRead.found(List.of("Characteristic.Kinds"), //$NON-NLS-1$
            List.of(Boolean.TRUE), AdoptedPropertyRouter.Qualifiers.NONE);
        String text = AdoptedPropertyRouter.baseChanged(planned, now, "type", "DefinedType.Amount", "Base"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("a type that became a type set is a change", text); //$NON-NLS-1$
        assertTrue(text, text.contains("(the same type names, one of them now a type set or no longer one)")); //$NON-NLS-1$
        assertFalse(text, text.contains("other qualifiers")); //$NON-NLS-1$
        assertNull("the same value is no change", AdoptedPropertyRouter.baseChanged(planned, planned, "type", //$NON-NLS-1$ //$NON-NLS-2$
            "DefinedType.Amount", "Base")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testABatchWithoutAPlannedWriteReadsNoBaseAgain()
    {
        Catalog catalog = catalogWithBlock(ObjectBelonging.ADOPTED);
        ((CatalogExtension)catalog.getExtension()).setSynonym(MdPropertyState.EXTENDED);
        FakePlatform platform = new FakePlatform();
        Routing routing = route(catalog, "Catalog.Goods", V8_5_1, platform, //$NON-NLS-1$
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.MD_OBJECT__SYNONYM, null, null));
        assertEquals(Decision.Kind.PLAIN, routing.decision(0).kind());

        assertNull(routing.baseChangedRefusal());
        assertEquals("nothing planned from the base, nothing read", 0, platform.baseReads()); //$NON-NLS-1$
    }

    @Test
    public void testATypeExtensionHoldingATypeWhoseNameCannotBeReadIsNotReplaced()
    {
        // T2: the write replaces the whole type extension; a type whose name cannot be read (an unresolved
        // reference) could not be told a base type from an earlier addition, and would be lost. Refused before the
        // transaction ...
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("String"), MdPropertyState.CHECKED, nameless(), MdPropertyState.EXTENDED)); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("String").offer("String").offer("CatalogRef.Own"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Routing routing = route(definedType, V8_5_1, platform, typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        assertRefused(routing, "'type' of DefinedType.Amount was not written: the type extension of adopted " //$NON-NLS-1$
            + "DefinedType.Amount (base configuration 'TestConfiguration') holds a type (typeExtension[1], ", //$NON-NLS-1$
            "whose name cannot be read", "would lose that type", "EDT's type-extension editor does not list such a " //$NON-NLS-1$ //$NON-NLS-2$
                + "type either. Restore the object it refers to, or remove the item from the extension's .mdo", //$NON-NLS-1$
            "get_project_errors on the extension may show it", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$

        // ... and inside it, when such a type appeared while the call waited.
        DefinedType waited = adoptedDefinedType("Amount", typeExtension(type("String"), MdPropertyState.CHECKED)); //$NON-NLS-1$ //$NON-NLS-2$
        AdoptedPropertyRouter.TypePlan plan = plan(waited, platform, typeChange(catalogRefType("Own", ObjectBelonging.NATIVE))); //$NON-NLS-1$
        TypeDescriptionExtension edited = ((DefinedTypeExtension)waited.getExtension()).getTypeExtension();
        TypeExtension unreadable = MdClassExtensionFactory.eINSTANCE.createTypeExtension();
        unreadable.setType(nameless());
        unreadable.setState(MdPropertyState.EXTENDED);
        edited.getTypes().add(unreadable);
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applyTypeExtension(waited, plan),
            "'type' of DefinedType.Amount was not written", "typeExtension[1]", "whose name cannot be read", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Nothing was changed."); //$NON-NLS-1$
        assertNull("nothing was written", plan.outcome()); //$NON-NLS-1$
        assertSame(edited, ((DefinedTypeExtension)waited.getExtension()).getTypeExtension());
        assertEquals("the type stays", 2, edited.getTypes().size()); //$NON-NLS-1$
        assertSame(unreadable, edited.getTypes().get(1));
    }

    @Test
    public void testAnOwnSourceHoldingASourceWhoseNameCannotBeReadIsNotReplaced()
    {
        // T2 for the source: the write replaces the subscription's whole own source.
        Type goods = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", description(goods, nameless()), //$NON-NLS-1$
            MdPropertyState.EXTENDED);
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", goods); //$NON-NLS-1$ //$NON-NLS-2$
        Routing routing = route(subscription, FQN_SUB, V8_5_1, platform, sourceChange(type("CatalogObject.Own"))); //$NON-NLS-1$
        assertRefused(routing, "'source' of EventSubscription.OnWrite was not written: the own source of adopted " //$NON-NLS-1$
            + "EventSubscription.OnWrite (base configuration 'TestConfiguration') in the extension holds a source " //$NON-NLS-1$
            + "(source[1]) whose name cannot be read", "would lose that source", //$NON-NLS-1$ //$NON-NLS-2$
            "Restore the object it refers to, or remove the source from the extension's .mdo", //$NON-NLS-1$
            "get_project_errors on the extension may show it", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$

        // ... and inside the write, when such a source appeared while the call waited.
        EventSubscription waited = adoptedSubscription("OnWrite", description(type("CatalogObject.Goods")), //$NON-NLS-1$ //$NON-NLS-2$
            MdPropertyState.EXTENDED);
        FakeAccess access = new FakeAccess();
        AdoptedPropertyRouter.SourcePlan plan = route(waited, FQN_SUB, V8_5_1, platform.access(access),
            sourceChange(type("CatalogObject.Own"))).decision(0).sourcePlan(); //$NON-NLS-1$
        TypeItem unreadable = nameless();
        waited.getSource().getTypes().add(unreadable);
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applySource(waited, plan),
            "'source' of EventSubscription.OnWrite was not written", "(source[1]) whose name cannot be read", //$NON-NLS-1$ //$NON-NLS-2$
            "Nothing was changed."); //$NON-NLS-1$
        assertTrue("nothing was written: " + access.calls, access.calls.isEmpty()); //$NON-NLS-1$
        assertEquals(2, waited.getSource().getTypes().size());
        assertSame(unreadable, waited.getSource().getTypes().get(1));

        // Returning the source to the base replaces it too.
        EventSubscription back = adoptedSubscription("OnWrite", description(type("CatalogObject.Goods")), //$NON-NLS-1$ //$NON-NLS-2$
            MdPropertyState.EXTENDED);
        AdoptedPropertyRouter.SourcePlan backPlan = route(back, FQN_SUB, V8_5_1, platform,
            sourceChange(type("CatalogObject.Goods"))).decision(0).sourcePlan(); //$NON-NLS-1$
        back.getSource().getTypes().add(nameless());
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.applySource(back, backPlan),
            "whose name cannot be read"); //$NON-NLS-1$
        assertEquals(MdPropertyState.EXTENDED, ((EventSubscriptionExtension)back.getExtension()).getSource());
    }

    @Test
    public void testAPropertyWhoseStateBecameNoneIsRefusedInTheWrite()
    {
        // T3: the routing wrote the synonym as prepared while its state was Extended; set to None meanwhile,
        // EDT's .mdo writer would drop it - the write refuses instead of reporting it written.
        Catalog catalog = catalogWithBlock(ObjectBelonging.ADOPTED);
        CatalogExtension block = (CatalogExtension)catalog.getExtension();
        block.setSynonym(MdPropertyState.EXTENDED);
        AdoptedPropertyRouter.requireStillWritten(catalog, MdClassPackage.Literals.MD_OBJECT__SYNONYM, null,
            "Catalog.Goods"); //$NON-NLS-1$

        block.setSynonym(MdPropertyState.NONE);
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.requireStillWritten(catalog,
            MdClassPackage.Literals.MD_OBJECT__SYNONYM, null, "Catalog.Goods"), //$NON-NLS-1$
            "'synonym' of Catalog.Goods was not written: Catalog.Goods is adopted, and the extension state of " //$NON-NLS-1$
                + "'synonym' became None while the call waited", //$NON-NLS-1$
            "retry the call", "Nothing was changed."); //$NON-NLS-1$ //$NON-NLS-2$
        // The second feature a change writes is checked too, and an object without an FQN is named by its name.
        block.setSynonym(MdPropertyState.EXTENDED);
        assertRefusedInTheWrite(() -> AdoptedPropertyRouter.requireStillWritten(catalog,
            MdClassPackage.Literals.MD_OBJECT__SYNONYM, MdClassPackage.Literals.CATALOG__CODE_LENGTH, null),
            "'codeLength' of Goods was not written"); //$NON-NLS-1$

        // Never for an object the extension owns, or a property without a state.
        Catalog own = catalogWithBlock(ObjectBelonging.NATIVE);
        AdoptedPropertyRouter.requireStillWritten(own, MdClassPackage.Literals.CATALOG__CODE_LENGTH, null, "Catalog.Goods"); //$NON-NLS-1$
        AdoptedPropertyRouter.requireStillWritten(catalog, MdClassPackage.Literals.MD_OBJECT__COMMENT, null,
            "Catalog.Goods"); //$NON-NLS-1$
    }

    @Test
    public void testTheValidatorCountsOnlyTheTypesTheTypeExtensionHolds()
    {
        // T4 (declined, pinned): EDT's TypeExtensionTreeValidator counts only the tree items whose state is not
        // None, and a base type the type extension does not hold is None in its tree (its state comes from the type
        // extension alone; TypeExtensionTreeItem reads a missing state as None). An adopted defined type with an
        // ordinary base type and an empty type extension therefore takes an unlimited String in EDT's own editor -
        // and so does the router. A base type that cannot be part of a composite type still blocks any addition.
        DefinedType definedType = adoptedDefinedType("Amount", typeExtension()); //$NON-NLS-1$
        TypeDescription unlimited = description(type("String")); //$NON-NLS-1$
        unlimited.setStringQualifiers(stringQualifiers(0));
        FakePlatform platform = new FakePlatform().base("Number").offer("Number").offer("String"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Routing routing = route(definedType, V8_5_1, platform,
            new AdoptedPropertyRouter.Change(MdClassPackage.Literals.DEFINED_TYPE__TYPE, unlimited, null));
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.TYPE_EXTENSION, routing.decision(0).kind());
    }

    // ==================== helpers ====================

    private static final EStructuralFeature DT_TYPE_EXTENSION =
        com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionPackage.Literals.DEFINED_TYPE_EXTENSION__TYPE_EXTENSION;

    /** A type item whose name cannot be read - as an unresolved reference reads. */
    private static Type nameless()
    {
        return McoreFactory.eINSTANCE.createType();
    }

    private static Catalog catalogWithBlock(ObjectBelonging belonging)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        catalog.setObjectBelonging(belonging);
        catalog.setExtension(MdClassExtensionFactory.eINSTANCE.createCatalogExtension());
        return catalog;
    }

    private static Configuration adoptedRoot()
    {
        Configuration root = MdClassFactory.eINSTANCE.createConfiguration();
        root.setObjectBelonging(ObjectBelonging.ADOPTED);
        ConfigurationExtension extension = MdClassExtensionFactory.eINSTANCE.createConfigurationExtension();
        extension.setSynonym(MdPropertyState.NONE);
        extension.setCaption(MdPropertyState.NONE);
        root.setExtension(extension);
        return root;
    }

    private static TypeDescriptionExtension withString(TypeDescriptionExtension extension, int length)
    {
        extension.setStringQualifiers(stringQualifiers(length));
        return extension;
    }

    private static AdoptedPropertyRouter.Change typeChange(TypeItem... items)
    {
        return new AdoptedPropertyRouter.Change(MdClassPackage.Literals.DEFINED_TYPE__TYPE, description(items), null);
    }

    private static AdoptedPropertyRouter.Change dateChange(DateFractions fractions)
    {
        TypeDescription request = description(type("Date")); //$NON-NLS-1$
        DateQualifiers qualifiers = McoreFactory.eINSTANCE.createDateQualifiers();
        qualifiers.setDateFractions(fractions);
        request.setDateQualifiers(qualifiers);
        return new AdoptedPropertyRouter.Change(MdClassPackage.Literals.DEFINED_TYPE__TYPE, request, null);
    }

    private static AdoptedPropertyRouter.Change sourceChange(TypeItem... items)
    {
        return new AdoptedPropertyRouter.Change(MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE, description(items),
            null);
    }

    private static AdoptedPropertyRouter.Request request(MdObject target, String fqn, Version version,
        AdoptedPropertyRouter.Change... changes)
    {
        return new AdoptedPropertyRouter.Request(target, fqn, null, null, null, version, List.of(changes));
    }

    private static Routing route(MdObject target, Version version, FakePlatform platform,
        AdoptedPropertyRouter.Change... changes)
    {
        return route(target, FQN_DT, version, platform, changes);
    }

    private static Routing route(MdObject target, String fqn, Version version, FakePlatform platform,
        AdoptedPropertyRouter.Change... changes)
    {
        return AdoptedPropertyRouter.route(request(target, fqn, version, changes), platform);
    }

    private static AdoptedPropertyRouter.TypePlan plan(DefinedType definedType, FakePlatform platform,
        AdoptedPropertyRouter.Change change)
    {
        Routing routing = route(definedType, V8_5_1, platform, change);
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(Decision.Kind.TYPE_EXTENSION, routing.decision(0).kind());
        return routing.decision(0).typePlan();
    }

    private static void assertRefusedInTheWrite(Runnable write, String... fragments)
    {
        try
        {
            write.run();
            fail("the write must roll back"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertNotNull("our own refusal, logged as one - not a platform failure: " + e, Refusals.messageOf(e)); //$NON-NLS-1$
            for (String fragment : fragments)
            {
                assertTrue("the refusal must say \"" + fragment + "\": " + e.getMessage(), //$NON-NLS-1$ //$NON-NLS-2$
                    e.getMessage().contains(fragment));
            }
        }
    }

    private static void assertNoTypeExtensionAdvice(Routing routing)
    {
        assertFalse(routing.refusal(), routing.refusal().contains("type extension")); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.refusal().contains("type-extension")); //$NON-NLS-1$
    }

    private static void assertRefused(Routing routing, String... fragments)
    {
        assertTrue("the batch must be refused", routing.isRefused()); //$NON-NLS-1$
        for (String fragment : fragments)
        {
            assertTrue("the refusal must say \"" + fragment + "\": " + routing.refusal(), //$NON-NLS-1$ //$NON-NLS-2$
                routing.refusal().contains(fragment));
        }
    }

    private static List<String> names(TypeDescriptionExtension extension)
    {
        List<String> names = new ArrayList<>();
        for (TypeExtension type : extension.getTypes())
        {
            names.add(type.getType().getName());
        }
        return names;
    }

    private static List<String> names(TypeDescription description)
    {
        List<String> names = new ArrayList<>();
        for (TypeItem item : description.getTypes())
        {
            names.add(item.getName());
        }
        return names;
    }
}
