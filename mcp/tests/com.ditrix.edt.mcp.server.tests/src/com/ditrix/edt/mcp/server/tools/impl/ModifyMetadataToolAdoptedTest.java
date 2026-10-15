/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.adoptedDefinedType;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.adoptedSubscription;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.catalogRefType;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.description;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.stringQualifiers;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.type;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.typeExtension;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.extension.CatalogExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.DefinedTypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.FakePlatform;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.TypeChangeDefaults;

/**
 * Issues #709 / #710 through modify_metadata's own seams: the generic path routes the prepared batch of an
 * object an extension ADOPTED ({@link ModifyMetadataTool#routeAdopted}) before the consent gate and the write,
 * applies the routed changes in the write ({@link ModifyMetadataTool#applyChanges}), and says in its
 * confirmation what they did; the membership {@code content} writers are guarded the same way. The platform
 * the router consults is {@link FakePlatform}; the objects are headless.
 */
public class ModifyMetadataToolAdoptedTest
{
    private static final Version V8_5_1 = Version.V8_5_1;

    @Test
    public void testARefusedBatchIsLeftAsPrepared()
    {
        // routeAdopted itself never writes: that a refused call writes nothing is the caller's order
        // (applyGenericPropertyChanges returns the refusal before the consent gate and the write), pinned by the
        // e2e refusal tests of test_modify_metadata_adopted.py.
        Catalog catalog = adoptedCatalog();
        List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
        ModifyMetadataTool.PreparedChange comment = ModifyMetadataTool.PreparedChange.scalar(
            MdClassPackage.Literals.MD_OBJECT__COMMENT, "note"); //$NON-NLS-1$
        ModifyMetadataTool.PreparedChange synonym = ModifyMetadataTool.PreparedChange.localized(
            MdClassPackage.Literals.MD_OBJECT__SYNONYM, "en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        changes.add(comment);
        changes.add(synonym);

        AdoptedPropertyRouter.Routing routing = route("Catalog.Goods", catalog, changes, new FakePlatform()); //$NON-NLS-1$

        assertTrue(routing.isRefused());
        assertTrue(routing.refusal(), routing.refusal().contains("'synonym' of Catalog.Goods")); //$NON-NLS-1$
        assertEquals("the batch is left as prepared", List.of(comment, synonym), changes); //$NON-NLS-1$
        assertSame(comment, changes.get(0));
        assertSame(synonym, changes.get(1));
    }

    @Test
    public void testTheRoutedTypeLandsInTheTypeExtensionNeverInTheNativeType()
    {
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("String"), MdPropertyState.CHECKED)); //$NON-NLS-1$
        TypeItem own = catalogRefType("Own", ObjectBelonging.NATIVE); //$NON-NLS-1$
        TypeDescription request = description(type("String"), own); //$NON-NLS-1$
        request.setStringQualifiers(stringQualifiers(10));
        List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
        changes.add(ModifyMetadataTool.PreparedChange.typeDescription(MdClassPackage.Literals.DEFINED_TYPE__TYPE,
            request));
        FakePlatform platform = new FakePlatform().baseWithString(10, "String").offer("String") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("CatalogRef.Own"); //$NON-NLS-1$

        AdoptedPropertyRouter.Routing routing = route("DefinedType.Amount", definedType, changes, platform); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.isRefused());
        assertEquals(1, changes.size());
        ModifyMetadataTool.PreparedChange routed = changes.get(0);
        assertTrue("the consent gate still sees a type change", routed.isTypeChange()); //$NON-NLS-1$
        assertNotNull("with its own warning", routed.routedConsentWarning()); //$NON-NLS-1$
        assertEquals("type", routed.featureName()); //$NON-NLS-1$

        TypeChangeDefaults.Reset reset = ModifyMetadataTool.applyChanges(definedType, changes,
            mock(IBmTransaction.class));

        assertEquals(TypeChangeDefaults.Reset.NONE, reset);
        TypeDescriptionExtension written = ((DefinedTypeExtension)definedType.getExtension()).getTypeExtension();
        assertEquals(2, written.getTypes().size());
        assertEquals(MdPropertyState.CHECKED, written.getTypes().get(0).getState());
        assertSame(own, written.getTypes().get(1).getType());
        assertEquals(MdPropertyState.EXTENDED, written.getTypes().get(1).getState());
        assertTrue("the native type stays empty", definedType.getType().getTypes().isEmpty()); //$NON-NLS-1$

        String note = routed.routedNote();
        assertNotNull(note);
        assertTrue(note, note.contains("added (Extended): CatalogRef.Own")); //$NON-NLS-1$
        assertTrue(note, note.contains("already base types, left as they stand: String")); //$NON-NLS-1$
        String message = ModifyMetadataTool.modifiedMessage("DefinedType.Amount", List.of("type"), List.of(), //$NON-NLS-1$ //$NON-NLS-2$
            TypeChangeDefaults.Reset.NONE, List.of(note));
        assertTrue(message, message.startsWith("Modified DefinedType.Amount (type); DefinedType.Amount is adopted")); //$NON-NLS-1$
    }

    @Test
    public void testTheRoutedSourceIsExtendedInTheWrite()
    {
        TypeItem base = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", description(base), null); //$NON-NLS-1$
        TypeItem own = type("CatalogObject.Own"); //$NON-NLS-1$
        List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
        changes.add(ModifyMetadataTool.PreparedChange.typeDescription(
            MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE, description(own)));
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", base); //$NON-NLS-1$ //$NON-NLS-2$

        route("EventSubscription.OnWrite", subscription, changes, platform); //$NON-NLS-1$
        ModifyMetadataTool.applyChanges(subscription, changes, mock(IBmTransaction.class));

        assertEquals(List.of(base, own), subscription.getSource().getTypes());
        assertEquals(MdPropertyState.EXTENDED, AdoptedPropertyRouter.stateOf(subscription,
            MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE));
        assertTrue(changes.get(0).routedNote(), changes.get(0).routedNote().contains("its source is Extended")); //$NON-NLS-1$
    }

    @Test
    public void testAChangeThatChangesNothingLeavesTheBatchAndSaysSo()
    {
        // C9: no additions while the source already follows the base - reported unchanged, never applied.
        TypeItem base = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", null, MdPropertyState.NONE); //$NON-NLS-1$
        List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
        changes.add(ModifyMetadataTool.PreparedChange.typeDescription(
            MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE, description(base)));
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", base); //$NON-NLS-1$ //$NON-NLS-2$

        AdoptedPropertyRouter.Routing routing = route("EventSubscription.OnWrite", subscription, changes, platform); //$NON-NLS-1$

        assertTrue("an unchanged source is not applied", changes.isEmpty()); //$NON-NLS-1$
        assertEquals(1, routing.notes().size());
        String message = ModifyMetadataTool.modifiedMessage("EventSubscription.OnWrite", List.of(), List.of(), //$NON-NLS-1$
            TypeChangeDefaults.Reset.NONE, routing.notes());
        assertTrue(message, message.startsWith("Nothing was changed on EventSubscription.OnWrite; 'source' of")); //$NON-NLS-1$
    }

    @Test
    public void testTheConsentPreviewOfARoutedWriteSaysWhatItAdds()
    {
        // C8: a pure addition to an adopted defined type's type extension never claims the retype's data loss.
        DefinedType definedType = adoptedDefinedType("Amount", //$NON-NLS-1$
            typeExtension(type("String"), MdPropertyState.CHECKED)); //$NON-NLS-1$
        List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
        changes.add(ModifyMetadataTool.PreparedChange.typeDescription(MdClassPackage.Literals.DEFINED_TYPE__TYPE,
            description(catalogRefType("Own", ObjectBelonging.NATIVE)))); //$NON-NLS-1$
        route("DefinedType.Amount", definedType, changes, new FakePlatform().base("String") //$NON-NLS-1$ //$NON-NLS-2$
            .offer("String").offer("CatalogRef.Own")); //$NON-NLS-1$ //$NON-NLS-2$

        ConsentPreview routed = ModifyMetadataTool.typeChangePreview("DefinedType.Amount", changes); //$NON-NLS-1$

        assertEquals("Change adopted DefinedType.Amount in the extension", routed.getTitle()); //$NON-NLS-1$
        assertTrue(routed.getSubtitle(), routed.getSubtitle().startsWith("Adds CatalogRef.Own to the type extension")); //$NON-NLS-1$
        assertFalse(routed.getSubtitle(), routed.getSubtitle().contains("Retyping")); //$NON-NLS-1$
        assertEquals(List.of("type"), routed.getTopNames()); //$NON-NLS-1$

        // The same type change of an object the extension owns is a retype, with the retype's warning.
        DefinedType own = adoptedDefinedType("Own", typeExtension()); //$NON-NLS-1$
        own.setObjectBelonging(ObjectBelonging.NATIVE);
        List<ModifyMetadataTool.PreparedChange> nativeChanges = new ArrayList<>();
        nativeChanges.add(ModifyMetadataTool.PreparedChange.typeDescription(MdClassPackage.Literals.DEFINED_TYPE__TYPE,
            description(type("String")))); //$NON-NLS-1$
        route("DefinedType.Own", own, nativeChanges, new FakePlatform()); //$NON-NLS-1$
        ConsentPreview retype = ModifyMetadataTool.typeChangePreview("DefinedType.Own", nativeChanges); //$NON-NLS-1$
        assertEquals("Change the data type of DefinedType.Own", retype.getTitle()); //$NON-NLS-1$
        assertTrue(retype.getSubtitle(), retype.getSubtitle().startsWith("Retyping stored data")); //$NON-NLS-1$

        assertNull("no type change, no preview", ModifyMetadataTool.typeChangePreview("Catalog.Goods", //$NON-NLS-1$ //$NON-NLS-2$
            List.of(ModifyMetadataTool.PreparedChange.scalar(MdClassPackage.Literals.MD_OBJECT__COMMENT, "x")))); //$NON-NLS-1$
    }

    @Test
    public void testANativeBatchIsUntouched()
    {
        Catalog catalog = adoptedCatalog();
        catalog.setObjectBelonging(ObjectBelonging.NATIVE);
        List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
        ModifyMetadataTool.PreparedChange synonym = ModifyMetadataTool.PreparedChange.localized(
            MdClassPackage.Literals.MD_OBJECT__SYNONYM, "en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        changes.add(synonym);

        AdoptedPropertyRouter.Routing routing = route("Catalog.Goods", catalog, changes, new FakePlatform()); //$NON-NLS-1$

        assertFalse(routing.isRefused());
        assertEquals(List.of(synonym), changes);
        assertSame(synonym, changes.get(0));
    }

    @Test
    public void testTheMembershipListOfEachKind()
    {
        MdClassFactory md = MdClassFactory.eINSTANCE;
        assertSame(MdClassPackage.Literals.CATALOG__OWNERS, ModifyMetadataTool.membershipList(md.createCatalog()));
        assertSame(MdClassPackage.Literals.COMMON_ATTRIBUTE__CONTENT,
            ModifyMetadataTool.membershipList(md.createCommonAttribute()));
        assertSame(MdClassPackage.Literals.EXCHANGE_PLAN__CONTENT,
            ModifyMetadataTool.membershipList(md.createExchangePlan()));
        assertSame(MdClassPackage.Literals.DOCUMENT__REGISTER_RECORDS,
            ModifyMetadataTool.membershipList(md.createDocument()));
        assertSame(MdClassPackage.Literals.SUBSYSTEM__CONTENT,
            ModifyMetadataTool.membershipList(md.createSubsystem()));
        assertNull(ModifyMetadataTool.membershipList(md.createConstant()));
        // The kinds are told apart by class, never confused by an unrelated one.
        assertFalse(CommonAttribute.class.isAssignableFrom(Catalog.class));
        assertFalse(ExchangePlan.class.isAssignableFrom(Document.class));
        assertFalse(Subsystem.class.isAssignableFrom(Catalog.class));
    }

    @Test
    public void testTheContentOfAnAdoptedCatalogIsGuardedLikeItsProperties()
    {
        // The production guard of the content payload (PLATFORM services; headless, a missing service only
        // changes the wording): refused at None, written at Checked and on the extension's own catalog.
        Catalog catalog = adoptedCatalog();
        String refusal = AdoptedPropertyRouter.refusalIfDropped(catalog, ModifyMetadataTool.membershipList(catalog),
            "Catalog.Goods", null, V8_5_1); //$NON-NLS-1$
        assertNotNull("the owners of an adopted catalog in state None are refused", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("'owners' of Catalog.Goods was not written")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed.")); //$NON-NLS-1$

        ((CatalogExtension)catalog.getExtension()).setOwners(MdPropertyState.CHECKED);
        assertNull("Checked owners are written", AdoptedPropertyRouter.refusalIfDropped(catalog, //$NON-NLS-1$
            ModifyMetadataTool.membershipList(catalog), "Catalog.Goods", null, V8_5_1)); //$NON-NLS-1$

        Catalog own = adoptedCatalog();
        own.setObjectBelonging(ObjectBelonging.NATIVE);
        assertNull("the extension's own catalog is written", AdoptedPropertyRouter.refusalIfDropped(own, //$NON-NLS-1$
            ModifyMetadataTool.membershipList(own), "Catalog.Goods", null, V8_5_1)); //$NON-NLS-1$
    }

    // ==================== helpers ====================

    private static Catalog adoptedCatalog()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        catalog.setObjectBelonging(ObjectBelonging.ADOPTED);
        catalog.setExtension(MdClassExtensionFactory.eINSTANCE.createCatalogExtension());
        return catalog;
    }

    private static AdoptedPropertyRouter.Routing route(String fqn, MdObject target,
        List<ModifyMetadataTool.PreparedChange> changes, FakePlatform platform)
    {
        return ModifyMetadataTool.routeAdopted(fqn, null, null, null, V8_5_1, target, changes,
            AdoptedPropertyRouterTestAccess.router(platform));
    }
}
