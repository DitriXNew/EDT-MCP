/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.description;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.stringQualifiers;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.type;
import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.typeExtension;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EAttribute;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.extension.BasicFeatureExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionPackage;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyType;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter.BaseRead;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter.BaseValue;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.FakeAccess;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouterTestAccess.FakePlatform;
import com.ditrix.edt.mcp.server.utils.ControlledMetadataProperties.Status;

public class ControlledMetadataPropertiesTest
{
    private static final String FQN = "Catalog.Goods.Attribute.\u0412\u0435\u0441";

    @Test
    public void storedNumberQualifiersAreComparedWithCurrentBase()
    {
        CatalogAttribute owner = adopted();
        var extension = typeExtension(type("Number"), MdPropertyState.CHECKED);
        extension.setNumberQualifiers(number(10).getNumberQualifiers());
        block(owner).setTypeExtension(extension);
        assertTrue(owner.getType() == null || owner.getType().getTypes().isEmpty());
        var difference = ControlledMetadataProperties.compareType(owner, base(number(15)), Version.V8_5_1);
        assertEquals(Status.MISMATCH, difference.status());
        assertTrue(difference.controlled(), difference.controlled().contains("Number(10, 2, Signed) (Checked)"));
        assertEquals("Number(15, 2, Signed)", difference.base());
        assertEquals(Status.MATCH, ControlledMetadataProperties.compareType(owner, base(number(10)),
            Version.V8_5_1).status());
        assertEquals(Status.UNAVAILABLE, ControlledMetadataProperties.compareType(owner,
            new BaseValue<>(BaseRead.Status.NOT_FOUND, null, null), Version.V8_5_1).status());
    }

    @Test
    public void onlyCheckedEntriesControlMultiStateTypes()
    {
        CatalogAttribute owner = adopted();
        var extension = typeExtension(type("String"), MdPropertyState.CHECKED);
        extension.setStringQualifiers(stringQualifiers(10));
        extension.getTypes().addAll(typeExtension(type("Boolean"), MdPropertyState.EXTENDED).getTypes());
        block(owner).setTypeExtension(extension);
        var source = description(type("String"), type("Date"));
        source.setStringQualifiers(stringQualifiers(10));
        assertEquals(Status.MATCH, ControlledMetadataProperties.compareType(owner, base(source), Version.V8_5_1).status());
        assertEquals(Status.MISMATCH, ControlledMetadataProperties.compareType(owner,
            base(description(type("Boolean"))), Version.V8_5_1).status());
        extension.getTypes().get(0).setState(MdPropertyState.NONE);
        assertEquals(Status.UNCHECKED, ControlledMetadataProperties.compareType(owner, base(source), Version.V8_5_1).status());
    }

    @Test
    public void reportsDifferentScalarValuesAndOmitsEqualControls()
    {
        var source = MdClassFactory.eINSTANCE.createCatalog();
        source.setCodeLength(12);
        source.setDescriptionLength(25);
        EAttribute code = MdClassPackage.Literals.CATALOG__CODE_LENGTH;
        EAttribute description = MdClassPackage.Literals.CATALOG__DESCRIPTION_LENGTH;
        var differences = ControlledMetadataProperties.attributeDifferences(Map.of(code, 9, description, 25), source);
        assertEquals(List.of(new ControlledMetadataProperties.Row("codeLength", "9", "12", Status.MISMATCH, false)),
            differences);
    }

    @Test
    public void refreshesCheckedTypesAndQualifiersAndKeepsExtendedEntries()
    {
        CatalogAttribute owner = adopted();
        var extension = typeExtension(type("String"), MdPropertyState.CHECKED, type("Date"), MdPropertyState.CHECKED);
        extension.setStringQualifiers(stringQualifiers(10));
        extension.getTypes().addAll(typeExtension(type("Boolean"), MdPropertyState.EXTENDED).getTypes());
        block(owner).setTypeExtension(extension);
        FakePlatform platform = new FakePlatform().baseWithString(15, "String", "Number")
            .propertyTypes(AdoptedPropertyRouter.BASIC_FEATURE_TYPE)
            .propertyTypes(MdClassExtensionPackage.Literals.BASIC_FEATURE_EXTENSION__TYPE_EXTENSION,
                MdPropertyType.MULTI_STATE)
            .buildable("Number", type("Number"));
        var routing = route(owner, Version.V8_5_1, platform);
        assertFalse(routing.refusal(), routing.isRefused());
        assertNull(routing.baseChangedRefusal());
        AdoptedPropertyRouter.applyTypeExtension(owner, routing.decision(0).typePlan());
        var refreshed = block(owner).getTypeExtension();
        assertEquals(15, refreshed.getStringQualifiers().getLength());
        assertEquals(List.of("String:CHECKED", "Number:CHECKED", "Boolean:EXTENDED"), refreshed.getTypes().stream()
            .map(item -> AdoptedPropertyRouter.typeName(item.getType()) + ":" + item.getState().name()).toList());
        assertTrue(owner.getType() == null || owner.getType().getTypes().isEmpty());
        assertEquals(AdoptedPropertyRouter.Decision.Kind.DROP, route(owner, Version.V8_5_1, platform).decision(0).kind());
    }

    @Test
    public void refreshRechecksBaseAndRefusesAChangedExtensionSnapshot()
    {
        CatalogAttribute owner = adopted();
        var extension = typeExtension(type("String"), MdPropertyState.CHECKED);
        extension.setStringQualifiers(stringQualifiers(10));
        block(owner).setTypeExtension(extension);
        FakePlatform platform = new FakePlatform().baseWithString(15, "String");
        var routing = route(owner, Version.V8_5_1, platform);
        assertFalse(routing.refusal(), routing.isRefused());
        platform.baseWithString(20, "String");
        assertTrue(routing.baseChangedRefusal().contains("changed the base value"));
        extension.getStringQualifiers().setLength(11);
        var error = assertThrows(IllegalStateException.class,
            () -> AdoptedPropertyRouter.applyTypeExtension(owner, routing.decision(0).typePlan()));
        assertTrue(error.getMessage(), error.getMessage().contains("changed while the call waited"));
        assertEquals(11, block(owner).getTypeExtension().getStringQualifiers().getLength());
    }

    @Test
    public void olderRuntimeRefreshUsesCheckedNativeProperty()
    {
        CatalogAttribute owner = adopted();
        block(owner).setType(MdPropertyState.CHECKED);
        owner.setType(description(type("String")));
        owner.getType().setStringQualifiers(stringQualifiers(10));
        var source = description(type("String"));
        source.setStringQualifiers(stringQualifiers(15));
        assertEquals(Status.MISMATCH,
            ControlledMetadataProperties.compareType(owner, base(source), Version.V8_3_17).status());
        FakeAccess access = new FakeAccess();
        FakePlatform platform = new FakePlatform().baseWithString(15, "String").access(access)
            .propertyTypes(AdoptedPropertyRouter.BASIC_FEATURE_TYPE, MdPropertyType.CHECKABLE);
        var routing = route(owner, Version.V8_3_17, platform);
        assertFalse(routing.refusal(), routing.isRefused());
        AdoptedPropertyRouter.applyTypeExtension(owner, routing.decision(0).typePlan());
        assertEquals(List.of("setValue type CHECKABLE"), access.calls);
        assertNull(block(owner).getTypeExtension());
        assertEquals(15, owner.getType().getStringQualifiers().getLength());
        assertEquals(Status.MATCH,
            ControlledMetadataProperties.compareType(owner, base(source), Version.V8_3_17).status());
    }

    @Test
    public void refreshRefusesAConflictWithAnExtendedTypeAndAnUnresolvableBaseType()
    {
        CatalogAttribute owner = adopted();
        var extension = typeExtension(type("String"), MdPropertyState.CHECKED, type("Number"), MdPropertyState.EXTENDED);
        block(owner).setTypeExtension(extension);
        FakePlatform platform = new FakePlatform().base("Number");
        var conflicting = route(owner, Version.V8_5_1, platform);
        assertTrue(conflicting.refusal(), conflicting.refusal().contains("Base type 'Number' collides"));
        var unresolved = route(owner, Version.V8_5_1, platform.base("CatalogRef.Unadopted"));
        assertTrue(unresolved.refusal(), unresolved.refusal().contains("base type 'CatalogRef.Unadopted'"));
        assertTrue(unresolved.refusal(), unresolved.refusal().contains("Adopt the referenced metadata"));
        assertSame(extension, block(owner).getTypeExtension());
    }

    private static AdoptedPropertyRouter.Routing route(CatalogAttribute owner, Version version, FakePlatform platform)
    {
        var changes = new ArrayList<MetadataPropertyApplier.PreparedChange>();
        assertNull(MetadataPropertyApplierTest.prepare(owner, "type", "fromBase", changes));
        var change = changes.get(0);
        return AdoptedPropertyRouter.route(new AdoptedPropertyRouter.Request(owner, FQN, null, null, null, version,
            List.of(new AdoptedPropertyRouter.Change(change.feature(), change.value(), null))), platform);
    }

    private static CatalogAttribute adopted()
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setObjectBelonging(ObjectBelonging.ADOPTED);
        attribute.setExtension(MdClassExtensionFactory.eINSTANCE.createBasicFeatureExtension());
        return attribute;
    }

    private static BasicFeatureExtension block(CatalogAttribute owner)
    {
        return (BasicFeatureExtension)owner.getExtension();
    }

    private static BaseValue<BaseRead> base(TypeDescription type)
    {
        return new BaseValue<>(BaseRead.Status.FOUND, null, AdoptedPropertyRouter.snapshotType(type));
    }

    private static TypeDescription number(int precision)
    {
        var type = description(type("Number"));
        var qualifiers = McoreFactory.eINSTANCE.createNumberQualifiers();
        qualifiers.setPrecision(precision);
        qualifiers.setScale(2);
        type.setNumberQualifiers(qualifiers);
        return type;
    }
}
