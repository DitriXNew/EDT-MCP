/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.adoptedDefinedType;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.adoptedSubscription;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.adoptedSubsystem;
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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
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
import com._1c.g5.v8.dt.metadata.mdclass.extension.SubsystemExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.FakePlatform;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.ditrix.edt.mcp.server.utils.TypeChangeDefaults;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
    public void testThePlainChangeWhoseStateBecameNoneIsRefusedInTheWrite()
    {
        // T3 through the write: a synonym routed as prepared while its state was Extended, whose state was set to
        // None while the call waited, is refused before anything of the batch is written.
        Catalog catalog = adoptedCatalog();
        CatalogExtension block = (CatalogExtension)catalog.getExtension();
        block.setSynonym(MdPropertyState.EXTENDED);
        List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
        changes.add(ModifyMetadataTool.PreparedChange.scalar(MdClassPackage.Literals.MD_OBJECT__COMMENT, "note")); //$NON-NLS-1$
        changes.add(ModifyMetadataTool.PreparedChange.localized(MdClassPackage.Literals.MD_OBJECT__SYNONYM, "en", //$NON-NLS-1$
            "Goods")); //$NON-NLS-1$
        AdoptedPropertyRouter.Routing routing = route("Catalog.Goods", catalog, changes, new FakePlatform()); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.isRefused());

        block.setSynonym(MdPropertyState.NONE);
        try
        {
            ModifyMetadataTool.applyChanges(catalog, changes, mock(IBmTransaction.class), "Catalog.Goods"); //$NON-NLS-1$
            fail("a value EDT would now drop must roll the write back"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("'synonym' of Catalog.Goods was not written: " //$NON-NLS-1$
                + "Catalog.Goods is adopted, and the extension state of 'synonym' became None while the call waited")); //$NON-NLS-1$
        }
        assertNull("nothing of the batch was written", catalog.getComment()); //$NON-NLS-1$
        assertTrue(catalog.getSynonym().isEmpty());

        // While the state stays Extended the batch is written.
        block.setSynonym(MdPropertyState.EXTENDED);
        ModifyMetadataTool.applyChanges(catalog, changes, mock(IBmTransaction.class), "Catalog.Goods"); //$NON-NLS-1$
        assertEquals("note", catalog.getComment()); //$NON-NLS-1$
        assertEquals("Goods", catalog.getSynonym().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The source read to pin the call sites no headless test can drive. */
    private static final String TOOL_SOURCE = "mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/" //$NON-NLS-1$
        + "server/tools/impl/ModifyMetadataTool.java"; //$NON-NLS-1$

    @Test
    public void testTheWritePathsCallTheChecksTheTestsAbovePin()
    {
        // applyGenericPropertyChanges and dispatchPredefinedItemFqn need a workspace project, a BM model and a
        // write transaction, so no headless test drives them, and every test above stays green if they stop
        // calling the seams those tests pin. Their call sites are therefore read here - comments removed, so a
        // commented-out call is no call.
        File source = locate(TOOL_SOURCE);
        assertNotNull("could not locate " + TOOL_SOURCE + " from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir"), source); //$NON-NLS-1$
        String text = read(source);

        String generic = code(methodBody(text, "private String applyGenericPropertyChanges(")); //$NON-NLS-1$
        assertTrue("the generic path must ask consent and then read the base again (T1): " + generic, //$NON-NLS-1$
            generic.contains("consentThenBaseCheck(normFqn, changes, routing)")); //$NON-NLS-1$
        assertFalse("the generic path must not ask consent without reading the base again", //$NON-NLS-1$
            generic.contains("consentForTypeChanges(")); //$NON-NLS-1$
        assertTrue("the generic write must check the prepared changes again in the transaction (T3): " //$NON-NLS-1$
            + generic, generic.contains("applyChanges(applyTo, changes, tx, normFqn)")); //$NON-NLS-1$

        String predefined = code(methodBody(text, "private String dispatchPredefinedItemFqn(")); //$NON-NLS-1$
        assertTrue("the predefined write must check the owner's state again in the transaction (T3): " //$NON-NLS-1$
            + predefined, predefined.contains("modifyPredefinedInWrite(txOwner, itemName, props, ownerFqn)")); //$NON-NLS-1$
        assertFalse("the predefined write must not call the writer past that check", //$NON-NLS-1$
            predefined.contains("PredefinedWriter.modify(")); //$NON-NLS-1$
    }

    /** The body of the method {@code signature} opens, comments removed. */
    private static String methodBody(String source, String signature)
    {
        String text = source.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        int start = text.indexOf(signature);
        assertTrue("signature not found: " + signature, start >= 0); //$NON-NLS-1$
        int end = text.indexOf("\n    }\n", start); //$NON-NLS-1$
        assertTrue("no closing brace after " + signature, end > start); //$NON-NLS-1$
        return text.substring(start, end)
            .replaceAll("(?s)/\\*.*?\\*/", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replaceAll("//[^\\n]*", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** {@code body} with every run of whitespace made one space. */
    private static String code(String body)
    {
        return body.replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String read(File file)
    {
        try
        {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static File locate(String relative)
    {
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File candidate = new File(dir, relative);
            if (candidate.exists())
            {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    @Test
    public void testAPredefinedItemWhoseOwnersStateBecameNoneIsRefusedInTheWrite()
    {
        // T3 on the predefined path: the guard judged the owner's 'predefined' state before the consent gate of a
        // retype; set to None while the call waited there, the write refuses before the item is changed.
        Catalog catalog = adoptedCatalog();
        assertFalse(PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false).isError()); //$NON-NLS-1$
        CatalogExtension block = (CatalogExtension)catalog.getExtension();
        block.setPredefined(MdPropertyState.EXTENDED);
        assertNull(AdoptedPropertyRouter.refusalIfDropped(catalog, MdClassPackage.Literals.CATALOG__PREDEFINED, //$NON-NLS-1$
            "Catalog.Goods", null, null)); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.description = "Sky blue"; //$NON-NLS-1$
        props.descriptionSet = true;
        String before = PredefinedWriter.findByName(catalog, "Blue").getDescription(); //$NON-NLS-1$

        block.setPredefined(MdPropertyState.NONE);
        try
        {
            ModifyMetadataTool.modifyPredefinedInWrite(catalog, "Blue", props, "Catalog.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("predefined items EDT would now drop must roll the write back"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(), e.getMessage().startsWith("'predefined' of Catalog.Goods was not written: " //$NON-NLS-1$
                + "Catalog.Goods is adopted, and the extension state of 'predefined' became None while the call " //$NON-NLS-1$
                + "waited")); //$NON-NLS-1$
        }
        assertEquals("the item was not changed", before, PredefinedWriter.findByName(catalog, "Blue").getDescription()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Sky blue".equals(before)); //$NON-NLS-1$

        // While the state stays Extended the item is written.
        block.setPredefined(MdPropertyState.EXTENDED);
        PredefinedWriter.WriteResult written = ModifyMetadataTool.modifyPredefinedInWrite(catalog, "Blue", props, //$NON-NLS-1$
            "Catalog.Goods"); //$NON-NLS-1$
        assertFalse(written.error, written.isError());
        assertEquals("Sky blue", PredefinedWriter.findByName(catalog, "Blue").getDescription()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheBaseIsReadAgainAfterTheConsentGateAndAChangedOneRefuses()
    {
        // T1 through the tool: the consent gate can hold the call while the base configuration changes; the base is
        // read again only once consent is given, and a changed base refuses the call before the write.
        TypeItem goods = type("CatalogObject.Goods"); //$NON-NLS-1$
        EventSubscription subscription = adoptedSubscription("OnWrite", null, null); //$NON-NLS-1$
        List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
        changes.add(ModifyMetadataTool.PreparedChange.typeDescription(
            MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE, description(type("CatalogObject.Own")))); //$NON-NLS-1$
        FakePlatform platform = new FakePlatform().base("CatalogObject.Goods").buildable("CatalogObject.Goods", goods); //$NON-NLS-1$ //$NON-NLS-2$
        AdoptedPropertyRouter.Routing routing = route("EventSubscription.OnWrite", subscription, changes, platform); //$NON-NLS-1$
        assertFalse(routing.refusal(), routing.isRefused());
        int reads = platform.baseReads();

        // Consent given while the base gained a source: refused, naming it.
        ModifyMetadataTool changedMeanwhile = new ModifyMetadataTool((tool, preview) -> {
            platform.base("CatalogObject.Goods", "CatalogObject.Other"); //$NON-NLS-1$ //$NON-NLS-2$
            return ConsentDecision.ALLOW;
        });
        String refused = changedMeanwhile.consentThenBaseCheck("EventSubscription.OnWrite", changes, routing); //$NON-NLS-1$
        assertNotNull("a base changed while the call waited refuses it", refused); //$NON-NLS-1$
        assertTrue(refused, refused.contains("'source' of EventSubscription.OnWrite was not written: the base " //$NON-NLS-1$
            + "configuration 'TestConfiguration' changed the base value of 'source' while the call waited")); //$NON-NLS-1$

        // Consent denied: the denial, and the base is not read.
        int before = platform.baseReads();
        String denied = new ModifyMetadataTool((tool, preview) -> ConsentDecision.REJECT)
            .consentThenBaseCheck("EventSubscription.OnWrite", changes, routing); //$NON-NLS-1$
        assertNotNull(denied);
        assertFalse(denied, denied.contains("changed the base value")); //$NON-NLS-1$
        assertEquals("a denied call reads no base", before, platform.baseReads()); //$NON-NLS-1$

        // The base as it was: the write goes on.
        platform.base("CatalogObject.Goods"); //$NON-NLS-1$
        assertNull(new ModifyMetadataTool((tool, preview) -> ConsentDecision.ALLOW)
            .consentThenBaseCheck("EventSubscription.OnWrite", changes, routing)); //$NON-NLS-1$
        assertTrue("the base was read again", platform.baseReads() > reads); //$NON-NLS-1$
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

    @Test
    public void testTheContentOfAnAdoptedSubsystemInStateNoneIsRefusedOnItsOwnPath()
        throws ReflectiveOperationException
    {
        // modify_metadata's own content path for a Subsystem FQN (dispatchSubsystemContentPayload), reached as
        // executeOnUiThread reaches it: the resolution, the adopted-content guard, then the membership writer. EDT's
        // adopter marks an adopted subsystem's content Extended, so MCP cannot produce this state (the e2e control
        // pins the Extended case); with the state None EDT's .mdo writer drops the list, and the call is refused
        // before the membership writer, changing nothing. None is the state's generated default, so an unset state is
        // this same state. EDT's writer reads no platform version, so neither does the verdict: the version only
        // words the refusal (AdoptedPropertyRouterTest pins those words), and here the project is one EDT does not
        // know, so the version reads as unknown.
        Catalog member = adoptedCatalog();
        Subsystem subsystem = adoptedSubsystem("Sales", MdPropertyState.NONE, member); //$NON-NLS-1$

        String refusal = errorOf(dispatchSubsystemContent(subsystem, false), "a content state of None"); //$NON-NLS-1$

        assertTrue(refusal, refusal.startsWith("'content' of Subsystem.Sales was not written: Subsystem.Sales is " //$NON-NLS-1$
            + "adopted from the base configuration")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("the extension state of 'content' is None")); //$NON-NLS-1$
        assertTrue(refusal, refusal.endsWith("Nothing was changed.")); //$NON-NLS-1$
        assertEquals("nothing changed: the members", List.of(member), subsystem.getContent()); //$NON-NLS-1$
        assertEquals("nothing changed: the state", MdPropertyState.NONE, //$NON-NLS-1$
            ((SubsystemExtension)subsystem.getExtension()).getContent());
    }

    @Test
    public void testTheContentOfAnAdoptedSubsystemInStateExtendedPassesTheGuard() throws ReflectiveOperationException
    {
        // The control: as EDT's adopter leaves it (content Extended) EDT's .mdo writer keeps the list, so the guard lets
        // the call through to the membership writer - which, handed a role payload as well, refuses that payload by
        // its own first check: proof that the guard did not refuse. So does the subsystem the extension owns, whatever
        // its block says. The real write of an adopted subsystem's content is the e2e control's.
        Subsystem extended = adoptedSubsystem("Sales", MdPropertyState.EXTENDED); //$NON-NLS-1$
        Subsystem own = adoptedSubsystem("Sales", MdPropertyState.NONE); //$NON-NLS-1$
        own.setObjectBelonging(ObjectBelonging.NATIVE);
        for (Subsystem subsystem : List.of(extended, own))
        {
            String ctx = subsystem.getObjectBelonging() + " with a content state " //$NON-NLS-1$
                + ((SubsystemExtension)subsystem.getExtension()).getContent();

            String refusal = errorOf(dispatchSubsystemContent(subsystem, true), ctx);

            assertFalse(ctx + ": " + refusal, refusal.contains("was not written")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(ctx + ": " + refusal, refusal.startsWith( //$NON-NLS-1$
                "'rights' / 'templates' / 'roleProperties' are only valid for a Role FQN")); //$NON-NLS-1$
        }
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

    /**
     * modify_metadata's own content path for {@code Subsystem.<name>} ({@code dispatchSubsystemContentPayload}: the
     * subsystem's resolution, the adopted-content guard, then the membership writer), called as executeOnUiThread
     * calls it - with one content member and, when {@code rolePayload}, a role payload too - on a headless
     * configuration that holds the subsystem. The project is a handle EDT does not know, so the platform version
     * reads as unknown. A throw comes back as {@code threw: <exception>}, so the assertion that meets it names it.
     */
    private static String dispatchSubsystemContent(Subsystem subsystem, boolean rolePayload)
        throws ReflectiveOperationException
    {
        Method dispatch = null;
        for (Method method : ModifyMetadataTool.class.getDeclaredMethods())
        {
            if ("dispatchSubsystemContentPayload".equals(method.getName())) //$NON-NLS-1$
            {
                dispatch = method;
            }
        }
        assertNotNull("modify_metadata's subsystem content path is not where this test looks for it", dispatch); //$NON-NLS-1$
        dispatch.setAccessible(true);
        Class<?>[] parameters = dispatch.getParameterTypes();

        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.getSubsystems().add(subsystem);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("ZZNoSuchProject709"); //$NON-NLS-1$
        Object context = newInstance(parameters[0]);
        set(context, "project", project); //$NON-NLS-1$
        set(context, "config", config); //$NON-NLS-1$

        String fqn = "Subsystem." + subsystem.getName(); //$NON-NLS-1$
        JsonObject member = new JsonObject();
        member.addProperty("metadata", "Catalog.Own"); //$NON-NLS-1$ //$NON-NLS-2$
        Object args = newInstance(parameters[2]);
        set(args, "projectName", "ZZNoSuchProject709"); //$NON-NLS-1$ //$NON-NLS-2$
        set(args, "fqn", fqn); //$NON-NLS-1$
        set(args, "properties", List.of()); //$NON-NLS-1$
        set(args, "content", List.of(member)); //$NON-NLS-1$
        set(args, "hasContentPayload", Boolean.TRUE); //$NON-NLS-1$
        set(args, "hasRolePayload", Boolean.valueOf(rolePayload)); //$NON-NLS-1$
        try
        {
            return (String)dispatch.invoke(new ModifyMetadataTool(), context, fqn, args);
        }
        catch (InvocationTargetException e)
        {
            return "threw: " + e.getCause(); //$NON-NLS-1$
        }
    }

    private static Object newInstance(Class<?> type) throws ReflectiveOperationException
    {
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException
    {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    /** The text of a {@code ToolResult} error; fails naming {@code answer} when it is anything else. */
    private static String errorOf(String answer, String ctx)
    {
        assertNotNull(ctx + ": the content path must answer", answer); //$NON-NLS-1$
        assertTrue(ctx + ": an error is expected, got: " + answer, answer.startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject result = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(ctx + ": " + answer, result.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        return result.get("error").getAsString(); //$NON-NLS-1$
    }

    private static AdoptedPropertyRouter.Routing route(String fqn, MdObject target,
        List<ModifyMetadataTool.PreparedChange> changes, FakePlatform platform)
    {
        return ModifyMetadataTool.routeAdopted(fqn, null, null, null, V8_5_1, target, changes,
            AdoptedPropertyRouterTestAccess.router(platform));
    }
}
