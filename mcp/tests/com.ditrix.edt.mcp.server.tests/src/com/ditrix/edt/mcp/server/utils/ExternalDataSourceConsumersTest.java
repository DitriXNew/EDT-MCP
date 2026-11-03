/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Before;
import org.junit.Test;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttributeDataSeparation;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Pins every secondary resolver consumer affected by the expanded EDS grammar. */
public class ExternalDataSourceConsumersTest
{
    private final ExternalDataSourceFixtures f = new ExternalDataSourceFixtures();

    @Before public void addressMustActuallyResolve()
    {
        // Each consumer's pin exercises the expanded grammar, rather than passing on base's miss.
        assertNotNull(MetadataNodeResolver.resolveExisting(f.config, FIELD));
    }

    @Test public void vendorSupportTargetsTheTableOrFieldAndCreateTargetsTheParent()
    {
        var table = VendorSupportGuard.resolveTarget(f.scope(), TABLE, VendorSupportGuard.Intent.MODIFY);
        assertSame(f.table, table.object);
        assertTrue(table.exact);
        assertSame(f.field, VendorSupportGuard.resolveTarget(f.scope(), FIELD, VendorSupportGuard.Intent.DELETE).object);
        assertSame(f.table, VendorSupportGuard.resolveTarget(f.scope(), TABLE + ".Field.New",
            VendorSupportGuard.Intent.CREATE).object);
        assertEquals(TABLE, MetadataNodeResolver.addressOf((com._1c.g5.v8.dt.metadata.mdclass.MdObject)table.object));
    }

    @Test public void membershipWritersRefuseEdsMembersBeforeMutation() throws Exception
    {
        for (String address : List.of(TABLE, FIELD))
        {
            var entry = new JsonObject();
            entry.addProperty("metadata", address);
            Object membership = invoke(ReferenceMembershipWriter.class, "planEntry",
                new Class<?>[] { Configuration.class, JsonObject.class, ReferenceMembershipWriter.Kind.class },
                f.config, entry, ReferenceMembershipWriter.Kind.SUBSYSTEM_CONTENT);
            assertRefusal(membership, "Membership content");
            for (var kind : ReferenceMembershipWriter.Kind.values())
            {
                assertFalse(kind.toString(), kind.accepts(MetadataNodeResolver.resolveExisting(f.config, address).object));
            }
            assertRefusal(invoke(ExchangePlanContentWriter.class, "planEntry",
                new Class<?>[] { Configuration.class, JsonObject.class }, f.config, entry), "Exchange plan content");
            for (String op : List.of("add", "remove"))
            {
                entry.addProperty("op", op);
                assertRefusal(invoke(CommonAttributeContentWriter.class, "planEntry",
                    new Class<?>[] { Configuration.class, JsonObject.class, CommonAttributeDataSeparation.class },
                    f.config, entry, CommonAttributeDataSeparation.DONT_USE), "Common attribute content");
            }
        }
    }

    @Test public void tableFieldListsRemainExplicitlyUnavailable()
    {
        for (EStructuralFeature feature : List.of(MdClassPackage.Literals.TABLE__KEY_FIELDS,
            MdClassPackage.Literals.TABLE__INPUT_BY_STRING, MdClassPackage.Literals.TABLE__DATA_LOCK_FIELDS))
        {
            String refusal = FieldListSupport.resolve(f.table, feature, feature.getName(), new JsonArray(),
                new ArrayList<>());
            assertNotNull(refusal);
            assertTrue(refusal, refusal.contains("is not a field list of this object"));
        }
        var generated = mock(com._1c.g5.v8.dt.mcore.Field.class);
        when(generated.eContainer()).thenReturn(f.table);
        when(generated.getName()).thenReturn("Id");
        when(generated.getFieldIdPrefix()).thenReturn("StandardAttribute");
        assertEquals(TABLE + ".StandardAttribute.Id", FieldListSupport.addressOf(generated));
    }

    @Test public void pictureAndMcoreValueListsKeepTheirTypedRefusals()
    {
        assertNotNull(PictureValueBuilder.build(new JsonPrimitive(TABLE), f.scope(), Version.LATEST).error);
        JsonArray array = new JsonArray();
        array.add(TABLE);
        String message = McoreValueListBuilder.build(array, f.scope()).error;
        assertTrue(message, message.contains("XDTO package"));
    }

    @Test public void edsMembersCannotBecomeMetadataValueTypes()
    {
        for (String address : List.of(TABLE, FIELD))
        {
            var item = new JsonObject();
            item.addProperty("kind", "Ref");
            item.addProperty("ref", address);
            String refusal = MetadataTypeBuilder.addType(McoreFactory.eINSTANCE.createTypeDescription(), item,
                "Ref", null, f.config, false, MetadataTypeBuilder.TypeTarget.METADATA);
            assertNotNull(refusal);
            assertTrue(refusal, refusal.contains("Metadata type assignment does not support ExternalDataSource members"));
        }
    }

    @Test public void dcsRootsKeepTheirExplicitUnsupportedRootVerdict()
    {
        for (String address : List.of(TABLE, FIELD, TABLE + ".Template.Schema"))
        {
            var parsed = DcsAddress.parse(address);
            assertTrue(parsed.isSuccess());
            var classified = DcsTargetResolver.classifyRoot(parsed.address());
            assertFalse(classified.isSuccess());
            assertEquals(DcsTargetResolver.FailureCode.UNSUPPORTED_ROOT, classified.failure.code());
        }
    }

    @Test public void screenshotResolverCanReachATableTemplate() throws Exception
    {
        var template = named(MdClassFactory.eINSTANCE.createTemplate(), "Print");
        f.table.getTemplates().add(template);
        assertSame(template, invoke(TemplateScreenshotHelper.class, "resolveTemplateObject",
            new Class<?>[] { Configuration.class, String.class }, f.config, TABLE + ".Template.Print"));
    }

    @Test public void formMainTableResolutionCanReadTheTablesOwnDerivedView() throws Exception
    {
        var feature = MdClassPackage.Literals.TABLE__DB_VIEW_DEFS;
        EClass type = feature.getEReferenceType();
        EObject defs = (EObject)mock(type.getInstanceClass(),
            withSettings().extraInterfaces(org.eclipse.emf.ecore.InternalEObject.class));
        when(defs.eClass()).thenReturn(type);
        var mainFeature = type.getEStructuralFeature("mainView");
        assertNotNull(mainFeature);
        EObject view = mock(EObject.class);
        when(defs.eGet(mainFeature)).thenReturn(view);
        f.table.eSet(feature, defs);
        assertSame(view, invoke(FormElementWriter.class, "resolveMainTableDbView",
            new Class<?>[] { Configuration.class, String.class }, f.config, TABLE));
    }

    @Test public void configurationAndSubsystemGrammarsKeepTheirOwnBoundaries()
    {
        assertNull(ConfigurationRoot.resolve(f.scope(), TABLE));
        assertNull(SubsystemUtils.parseSubsystemPath(TABLE));
        String module = "src/ExternalDataSources/Eds/Tables/T/ManagerModule.bsl";
        assertNull(AdoptedModuleState.moduleAddress(module));
        assertNull(VendorSupportGuard.moduleOwnerFqn(module, true));
        assertEquals(EDS, VendorSupportGuard.moduleOwnerFqn(module, false));
    }

    private static void assertRefusal(Object plan, String operation) throws Exception
    {
        var field = plan.getClass().getDeclaredField("error");
        field.setAccessible(true);
        String refusal = (String)field.get(plan);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains(operation + " does not support ExternalDataSource members"));
    }

    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... args) throws Exception
    {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
}
