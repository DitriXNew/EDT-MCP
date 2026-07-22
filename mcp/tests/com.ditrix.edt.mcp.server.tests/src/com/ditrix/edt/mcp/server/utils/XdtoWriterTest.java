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
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.xdto.model.Import;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.xdto.model.XdtoFactory;
import com.ditrix.edt.mcp.server.utils.XdtoWriter.MemberRef;
import com.ditrix.edt.mcp.server.utils.XdtoWriter.QNameResult;
import com.ditrix.edt.mcp.server.utils.XdtoWriter.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests {@link XdtoWriter}: the {@code XDTOPackage.<Package>.ObjectType.<Name>(.Property.<Name>)?} FQN
 * grammar ({@link XdtoWriter#parseMemberRef}), find/create/remove on an in-memory {@link Package} built
 * with {@code XdtoFactory.eINSTANCE} (mirrors {@link DcsWriterTest}'s in-memory
 * {@code DcsFactory.eINSTANCE} fixtures - no BM transaction involved), the typed attribute writers
 * ({@link XdtoWriter#applyObjectTypeProperties} / {@link XdtoWriter#applyPropertyProperties}) and QName
 * resolution ({@link XdtoWriter#resolveQName}). The BM-transaction persistence hook
 * ({@link XdtoWriter#resolvePackageContent}) is intentionally NOT unit-tested here (mirrors the DCS /
 * template precedent): {@code IBmObject} is only implemented by live BM-backed proxies, so that path is
 * e2e/live-tested only.
 * <p>
 * Every error-path assertion also checks the result is a READY {@code ToolResult.error(...).toJson()}
 * envelope (codex review issue #183 finding #9: {@code XdtoWriteException}/{@code Result.error} must
 * never carry a bare string - it is surfaced verbatim as the tool's wire response).
 */
public class XdtoWriterTest
{
    private static JsonObject json(String s)
    {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static Package newPackage()
    {
        Package pkg = XdtoFactory.eINSTANCE.createPackage();
        pkg.setNsUri("http://example.org/MyPackage"); //$NON-NLS-1$
        return pkg;
    }

    private static void assertJsonError(String error)
    {
        assertNotNull("must carry an error", error); //$NON-NLS-1$
        assertTrue("the error must be a ready ToolResult error JSON envelope, not a bare string: " + error, //$NON-NLS-1$
            error.contains("\"error\"")); //$NON-NLS-1$
    }

    // ==================== parseMemberRef ====================

    @Test
    public void testParseObjectTypeRef()
    {
        MemberRef ref = XdtoWriter.parseMemberRef("XDTOPackage.MyPackage.ObjectType.MyType"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals(XdtoWriter.Kind.OBJECT_TYPE, ref.kind);
        assertEquals("XDTOPackage.MyPackage", ref.packageFqn); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("MyType", ref.objectTypeName); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ref.propertyName);
        assertEquals("MyType", ref.memberName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParsePackageGlobalPropertyRef()
    {
        MemberRef ref = XdtoWriter.parseMemberRef("XDTOPackage.MyPackage.Property.MyProp"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals(XdtoWriter.Kind.PACKAGE_PROPERTY, ref.kind);
        assertEquals("XDTOPackage.MyPackage", ref.packageFqn); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ref.objectTypeName);
        assertEquals("MyProp", ref.propertyName); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("MyProp", ref.memberName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseNestedPropertyRef()
    {
        MemberRef ref = XdtoWriter.parseMemberRef("XDTOPackage.MyPackage.ObjectType.MyType.Property.MyProp"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals(XdtoWriter.Kind.OBJECT_TYPE_PROPERTY, ref.kind);
        assertEquals("XDTOPackage.MyPackage", ref.packageFqn); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("MyType", ref.objectTypeName); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("MyProp", ref.propertyName); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseRejectsNonXdtoFqn()
    {
        assertNull(XdtoWriter.parseMemberRef("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
    }

    @Test
    public void testParseRejectsTopLevelPackageFqn()
    {
        // 2-part FQN (the package itself) is not a MEMBER ref - falls through to the normal top-level path.
        assertNull(XdtoWriter.parseMemberRef("XDTOPackage.MyPackage")); //$NON-NLS-1$
    }

    @Test
    public void testParseRejectsUnknownKindToken()
    {
        assertNull(XdtoWriter.parseMemberRef("XDTOPackage.MyPackage.Bogus.Name")); //$NON-NLS-1$
    }

    @Test
    public void testParseRejectsMalformedNestedKind()
    {
        // 6 parts but the inner kind is not 'Property'.
        assertNull(XdtoWriter.parseMemberRef("XDTOPackage.MyPackage.ObjectType.MyType.Bogus.Name")); //$NON-NLS-1$
    }

    @Test
    public void testParseAcceptsRussianTypeToken()
    {
        // The XDTOPackage type token is bilingual (MetadataTypeUtils.toEnglishSingular); the ru token is
        // built from code points to keep this source pure ASCII.
        String ruXdtoPackage = MetadataLanguageUtils.cp(0x041f, 0x0430, 0x043a, 0x0435, 0x0442) + "XDTO"; //$NON-NLS-1$
        MemberRef ref = XdtoWriter.parseMemberRef(ruXdtoPackage + ".MyPackage.ObjectType.MyType"); //$NON-NLS-1$
        assertNotNull("the Russian XDTOPackage type token must resolve", ref); //$NON-NLS-1$
        assertEquals("XDTOPackage.MyPackage", ref.packageFqn); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== find / create / remove - CASE-SENSITIVE (codex #8) ====================

    @Test
    public void testFindObjectTypeIsExactCaseSensitiveMatch()
    {
        Package pkg = newPackage();
        assertNull(XdtoWriter.findObjectType(pkg, "MyType")); //$NON-NLS-1$
        ObjectType type = XdtoWriter.createObjectType(pkg, "MyType"); //$NON-NLS-1$
        assertEquals("MyType", type.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, pkg.getObjects().size());
        assertEquals("an exact-case name must match", type, XdtoWriter.findObjectType(pkg, "MyType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a differently-cased name must NOT match (XDTO/XML QName local names are " //$NON-NLS-1$
            + "case-sensitive, unlike a 1C mdclass member name)", XdtoWriter.findObjectType(pkg, "mytype")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCaseDistinctObjectTypesAreBothCreatable()
    {
        // "Order" and "order" must be able to coexist - a case-insensitive collision would wrongly block
        // creating the second one (or wrongly resolve one as a duplicate of the other).
        Package pkg = newPackage();
        ObjectType upper = XdtoWriter.createObjectType(pkg, "Order"); //$NON-NLS-1$
        assertNull("must not collide with the differently-cased sibling", //$NON-NLS-1$
            XdtoWriter.findObjectType(pkg, "order")); //$NON-NLS-1$
        ObjectType lower = XdtoWriter.createObjectType(pkg, "order"); //$NON-NLS-1$
        assertEquals(2, pkg.getObjects().size());
        assertEquals(upper, XdtoWriter.findObjectType(pkg, "Order")); //$NON-NLS-1$
        assertEquals(lower, XdtoWriter.findObjectType(pkg, "order")); //$NON-NLS-1$
    }

    @Test
    public void testRemoveObjectTypeCascadesItsOwnProperties()
    {
        Package pkg = newPackage();
        ObjectType type = XdtoWriter.createObjectType(pkg, "MyType"); //$NON-NLS-1$
        XdtoWriter.createProperty(type.getProperties(), "Inner"); //$NON-NLS-1$
        assertEquals(1, type.getProperties().size());
        XdtoWriter.removeObjectType(pkg, type);
        assertTrue(pkg.getObjects().isEmpty());
    }

    @Test
    public void testFindPropertyIsExactCaseSensitiveMatch()
    {
        Package pkg = newPackage();
        assertNull(XdtoWriter.findProperty(pkg.getProperties(), "MyProp")); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        assertEquals("MyProp", property.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, pkg.getProperties().size());
        assertEquals(property, XdtoWriter.findProperty(pkg.getProperties(), "MyProp")); //$NON-NLS-1$
        assertNull("a differently-cased name must NOT match", //$NON-NLS-1$
            XdtoWriter.findProperty(pkg.getProperties(), "myprop")); //$NON-NLS-1$
    }

    @Test
    public void testRemoveProperty()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        XdtoWriter.removeProperty(pkg.getProperties(), property);
        assertTrue(pkg.getProperties().isEmpty());
    }

    // ==================== applyObjectTypeProperties ====================

    @Test
    public void testObjectTypeFlagsApplyAndAreTracked()
    {
        Package pkg = newPackage();
        ObjectType type = XdtoWriter.createObjectType(pkg, "MyType"); //$NON-NLS-1$
        Result r = XdtoWriter.applyObjectTypeProperties(type,
            List.of(json("{\"name\":\"open\",\"value\":true}"), //$NON-NLS-1$
                json("{\"name\":\"abstract\",\"value\":false}"))); //$NON-NLS-1$
        assertFalse(r.error, r.hasError());
        assertTrue(type.isOpen());
        assertTrue(type.isSetOpen());
        assertFalse(type.isAbstract());
        assertTrue(type.isSetAbstract());
        assertEquals(List.of("open", "abstract"), r.applied); //$NON-NLS-1$ //$NON-NLS-2$
        // untouched flags stay unset (platform defaults, not written to disk - #235 lesson)
        assertFalse(type.isSetMixed());
    }

    @Test
    public void testObjectTypeRejectsUnassignableProperty()
    {
        Package pkg = newPackage();
        ObjectType type = XdtoWriter.createObjectType(pkg, "MyType"); //$NON-NLS-1$
        Result r = XdtoWriter.applyObjectTypeProperties(type,
            List.of(json("{\"name\":\"bogus\",\"value\":true}"))); //$NON-NLS-1$
        assertTrue("an unknown ObjectType property must be rejected", r.hasError()); //$NON-NLS-1$
        assertFalse("a rejected spec must not mutate the type", type.isSetOpen()); //$NON-NLS-1$
        assertJsonError(r.error);
    }

    @Test
    public void testObjectTypeRejectsNonBooleanFlag()
    {
        Package pkg = newPackage();
        ObjectType type = XdtoWriter.createObjectType(pkg, "MyType"); //$NON-NLS-1$
        Result r = XdtoWriter.applyObjectTypeProperties(type,
            List.of(json("{\"name\":\"open\",\"value\":\"yes\"}"))); //$NON-NLS-1$
        assertTrue(r.hasError());
        assertJsonError(r.error);
    }

    // ==================== applyPropertyProperties ====================

    @Test
    public void testPropertyRequiresTypeOnCreate()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg, List.of(), true);
        assertTrue("a Property create without 'type' must be rejected", r.hasError()); //$NON-NLS-1$
        assertJsonError(r.error);
    }

    @Test
    public void testPropertyModifyDoesNotRequireType()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"nillable\",\"value\":true}")), false); //$NON-NLS-1$
        assertFalse(r.error, r.hasError());
        assertTrue(property.isNillable());
        assertEquals(List.of("nillable"), r.applied); //$NON-NLS-1$
    }

    @Test
    public void testPropertyTypePrimitiveShorthandUsesXsdNamespace()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":\"string\"}")), true); //$NON-NLS-1$
        assertFalse(r.error, r.hasError());
        assertNotNull(property.getType());
        assertEquals("string", property.getType().getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("http://www.w3.org/2001/XMLSchema", property.getType().getNsUri()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPropertyTypeSamePackageObjectTypeShorthandIsExactMatch()
    {
        Package pkg = newPackage();
        XdtoWriter.createObjectType(pkg, "Address"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":\"Address\"}")), true); //$NON-NLS-1$
        assertFalse(r.error, r.hasError());
        assertEquals("Address", property.getType().getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a same-package ObjectType reference uses the package's OWN nsUri", //$NON-NLS-1$
            pkg.getNsUri(), property.getType().getNsUri());

        // A different-case token does NOT match "Address" and is not an XSD built-in either -> rejected.
        Property other = XdtoWriter.createProperty(pkg.getProperties(), "Other"); //$NON-NLS-1$
        Result rejected = XdtoWriter.applyPropertyProperties(other, pkg,
            List.of(json("{\"name\":\"type\",\"value\":\"address\"}")), true); //$NON-NLS-1$
        assertTrue("a differently-cased ObjectType name must not silently resolve", rejected.hasError()); //$NON-NLS-1$
        assertJsonError(rejected.error);
    }

    @Test
    public void testPropertyTypeExplicitObjectFormWithMatchingImport()
    {
        Package pkg = newPackage();
        // A cross-namespace {nsUri,name} reference needs a matching Import - otherwise it is an invalid
        // (unreachable) XDTO reference (codex #4).
        Import dependency = XdtoFactory.eINSTANCE.createImport();
        dependency.setNamespace("http://custom/ns"); //$NON-NLS-1$
        pkg.getDependencies().add(dependency);
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":{\"nsUri\":\"http://custom/ns\",\"name\":\"Custom\"}}")), //$NON-NLS-1$
            true);
        assertFalse(r.error, r.hasError());
        assertEquals("Custom", property.getType().getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("http://custom/ns", property.getType().getNsUri()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPropertyTypeExplicitObjectFormOwnNamespaceNeedsNoImport()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":{\"nsUri\":\"" + pkg.getNsUri() + "\",\"name\":\"Custom\"}}")), //$NON-NLS-1$ //$NON-NLS-2$
            true);
        assertFalse("the package's own namespace is implicitly reachable, no Import needed", //$NON-NLS-1$
            r.hasError());
    }

    @Test
    public void testPropertyRejectsUnreachableNamespace()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":{\"nsUri\":\"http://not-imported/ns\",\"name\":\"X\"}}")), //$NON-NLS-1$
            true);
        assertTrue("a namespace with no matching Import (and not XSD / the package's own) must be " //$NON-NLS-1$
            + "rejected as an unreachable reference", r.hasError()); //$NON-NLS-1$
        assertJsonError(r.error);
        assertNull("a rejected spec must not mutate the property", property.getType()); //$NON-NLS-1$
    }

    @Test
    public void testPropertyRejectsUnknownBareStringShorthand()
    {
        // codex #4: a typo like "Adress" must be REJECTED, never silently become invalid xs:Adress.
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":\"Adress\"}")), true); //$NON-NLS-1$
        assertTrue("an unknown bare-string type/ref token must be rejected, not silently namespaced " //$NON-NLS-1$
            + "under XSD", r.hasError()); //$NON-NLS-1$
        assertJsonError(r.error);
        assertNull(property.getType());
    }

    @Test
    public void testPropertyAcceptsSeveralXsdBuiltinTypeNames()
    {
        Package pkg = newPackage();
        for (String builtin : new String[] { "string", "boolean", "decimal", "dateTime", "date", "int", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "anyURI", "base64Binary" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Property property = XdtoWriter.createProperty(pkg.getProperties(), "P_" + builtin); //$NON-NLS-1$
            Result r = XdtoWriter.applyPropertyProperties(property, pkg,
                List.of(json("{\"name\":\"type\",\"value\":\"" + builtin + "\"}")), true); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(builtin + ": " + r.error, r.hasError()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(builtin, property.getType().getName());
            assertEquals("http://www.w3.org/2001/XMLSchema", property.getType().getNsUri()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testPropertyAcceptsAnySimpleTypeAndAnyType()
    {
        // codex review residual #2/finding #4: these two XSD/EDT root ur-types were wrongly missing from
        // the whitelist, regressing a previously-valid shorthand.
        Package pkg = newPackage();
        for (String builtin : new String[] { "anySimpleType", "anyType" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Property property = XdtoWriter.createProperty(pkg.getProperties(), "P_" + builtin); //$NON-NLS-1$
            Result r = XdtoWriter.applyPropertyProperties(property, pkg,
                List.of(json("{\"name\":\"type\",\"value\":\"" + builtin + "\"}")), true); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(builtin + ": " + r.error, r.hasError()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(builtin, property.getType().getName());
        }
    }

    // ==================== 'ref' deferred for v1 (codex review residual #3/finding #5) ====================

    @Test
    public void testPropertyRefIsRejectedWithActionableMessage()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"ref\",\"value\":\"SomeGlobalProperty\"}")), false); //$NON-NLS-1$
        assertTrue("'ref' must be rejected - not yet supported for v1", r.hasError()); //$NON-NLS-1$
        assertJsonError(r.error);
        assertTrue("the refusal must say it is not yet supported", r.error.contains("not yet supported")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must point the caller at 'type' as the alternative", //$NON-NLS-1$
            r.error.contains("'type' instead")); //$NON-NLS-1$
        assertNull("a rejected spec must not mutate the property", property.getType()); //$NON-NLS-1$
    }

    @Test
    public void testPropertyRefRejectedEvenAlongsideType()
    {
        // 'ref' is rejected up front regardless of what else is in the same call.
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":\"string\"}"), //$NON-NLS-1$
                json("{\"name\":\"ref\",\"value\":\"SomeGlobalProperty\"}")), //$NON-NLS-1$
            true);
        assertTrue(r.hasError());
        assertJsonError(r.error);
        assertNull("a rejected spec must not mutate the property", property.getType()); //$NON-NLS-1$
    }

    @Test
    public void testSettingTypeClearsAPreExistingRefFromAnImport()
    {
        // Simulates a property that already carries a 'ref' from an XML import (this API can no longer
        // SET one - 'ref' write support is deferred) - setting 'type' through modify_metadata must still
        // clear the stale 'ref' defensively, so the property never ends up carrying both.
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        QName importedRef = McoreFactory.eINSTANCE.createQName();
        importedRef.setNsUri(pkg.getNsUri());
        importedRef.setName("SomeGlobalProperty"); //$NON-NLS-1$
        property.setRef(importedRef);
        assertNotNull(property.getRef());

        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":\"string\"}")), false); //$NON-NLS-1$
        assertFalse(r.error, r.hasError());
        assertNotNull("setting 'type' must land", property.getType()); //$NON-NLS-1$
        assertNull("setting 'type' must clear a pre-existing 'ref' (e.g. from an XML import)", //$NON-NLS-1$
            property.getRef());
    }

    // ==================== bounds / container / fixed validation (codex #6) ====================

    @Test
    public void testNestedPropertyAllAttributesLand()
    {
        Package pkg = newPackage();
        ObjectType owner = XdtoWriter.createObjectType(pkg, "Owner"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(owner.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":\"string\"}"), //$NON-NLS-1$
                json("{\"name\":\"lowerBound\",\"value\":1}"), //$NON-NLS-1$
                json("{\"name\":\"upperBound\",\"value\":-1}"), //$NON-NLS-1$
                json("{\"name\":\"nillable\",\"value\":true}"), //$NON-NLS-1$
                json("{\"name\":\"fixed\",\"value\":true}"), //$NON-NLS-1$
                json("{\"name\":\"default\",\"value\":\"N/A\"}")), //$NON-NLS-1$
            true);
        assertFalse(r.error, r.hasError());
        assertEquals(1, property.getLowerBound());
        assertEquals(-1, property.getUpperBound());
        assertTrue(property.isNillable());
        assertTrue(property.isFixed());
        assertEquals("N/A", property.getDefault()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(6, r.applied.size());
    }

    @Test
    public void testPackageGlobalPropertyRejectsOccurrenceBounds()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"lowerBound\",\"value\":0}")), false); //$NON-NLS-1$
        assertTrue("a package-global property must not accept occurrence bounds", r.hasError()); //$NON-NLS-1$
        assertJsonError(r.error);
        assertFalse(property.isSetLowerBound());
    }

    @Test
    public void testNegativeLowerBoundRejected()
    {
        Package pkg = newPackage();
        ObjectType owner = XdtoWriter.createObjectType(pkg, "Owner"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(owner.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"lowerBound\",\"value\":-1}")), false); //$NON-NLS-1$
        assertTrue(r.hasError());
        assertJsonError(r.error);
    }

    @Test
    public void testUpperBoundBelowLowerBoundRejected()
    {
        Package pkg = newPackage();
        ObjectType owner = XdtoWriter.createObjectType(pkg, "Owner"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(owner.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"lowerBound\",\"value\":5}"), //$NON-NLS-1$
                json("{\"name\":\"upperBound\",\"value\":2}")), //$NON-NLS-1$
            false);
        assertTrue("upperBound < lowerBound (and not -1/unbounded) must be rejected", r.hasError()); //$NON-NLS-1$
        assertJsonError(r.error);
        assertFalse(property.isSetUpperBound());
    }

    @Test
    public void testUpperBoundUnboundedAllowsAnyLowerBound()
    {
        Package pkg = newPackage();
        ObjectType owner = XdtoWriter.createObjectType(pkg, "Owner"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(owner.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"lowerBound\",\"value\":3}"), //$NON-NLS-1$
                json("{\"name\":\"upperBound\",\"value\":-1}")), //$NON-NLS-1$
            false);
        assertFalse(r.error, r.hasError());
    }

    @Test
    public void testUpperBoundValidatedAgainstAlreadySetLowerBound()
    {
        // A call that touches ONLY upperBound must still validate against the property's EXISTING
        // lowerBound (the effective post-change state), not just the value(s) supplied in this call.
        Package pkg = newPackage();
        ObjectType owner = XdtoWriter.createObjectType(pkg, "Owner"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(owner.getProperties(), "MyProp"); //$NON-NLS-1$
        Result first = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"lowerBound\",\"value\":5}")), false); //$NON-NLS-1$
        assertFalse(first.error, first.hasError());

        Result second = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"upperBound\",\"value\":2}")), false); //$NON-NLS-1$
        assertTrue("upperBound=2 must be rejected against the already-set lowerBound=5", //$NON-NLS-1$
            second.hasError());
        assertFalse("the rejected upperBound must not have been applied", property.isSetUpperBound()); //$NON-NLS-1$
    }

    @Test
    public void testUpperBoundBareNegativeOtherThanMinusOneRejected()
    {
        // codex review residual #4a: only -1 (unbounded) is a valid negative upperBound; -2 (or any other
        // negative) must be rejected even when lowerBound is never set (no cross-check available yet).
        Package pkg = newPackage();
        ObjectType owner = XdtoWriter.createObjectType(pkg, "Owner"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(owner.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"upperBound\",\"value\":-2}")), false); //$NON-NLS-1$
        assertTrue("upperBound=-2 is not valid XDTO (only -1 means unbounded)", r.hasError()); //$NON-NLS-1$
        assertJsonError(r.error);
        assertFalse(property.isSetUpperBound());
    }

    @Test
    public void testUpperBoundMinusOneAccepted()
    {
        Package pkg = newPackage();
        ObjectType owner = XdtoWriter.createObjectType(pkg, "Owner"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(owner.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"upperBound\",\"value\":-1}")), false); //$NON-NLS-1$
        assertFalse("-1 (unbounded) must be accepted even with no lowerBound known: " + r.error, r.hasError()); //$NON-NLS-1$
        assertEquals(-1, property.getUpperBound());
    }

    @Test
    public void testFixedTrueWithoutDefaultRejected()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"fixed\",\"value\":true}")), false); //$NON-NLS-1$
        assertTrue("fixed=true with no default (new or existing) must be rejected", r.hasError()); //$NON-NLS-1$
        assertJsonError(r.error);
        assertFalse(property.isSetFixed());
    }

    @Test
    public void testFixedTrueWithDefaultInSameCallAccepted()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"fixed\",\"value\":true}"), //$NON-NLS-1$
                json("{\"name\":\"default\",\"value\":\"N/A\"}")), //$NON-NLS-1$
            false);
        assertFalse(r.error, r.hasError());
        assertTrue(property.isFixed());
        assertEquals("N/A", property.getDefault()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFixedTrueWithAlreadyExistingDefaultAccepted()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result seedDefault = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"default\",\"value\":\"N/A\"}")), false); //$NON-NLS-1$
        assertFalse(seedDefault.error, seedDefault.hasError());

        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"fixed\",\"value\":true}")), false); //$NON-NLS-1$
        assertFalse("fixed=true against an ALREADY-set default must be accepted", r.hasError()); //$NON-NLS-1$
        assertTrue(property.isFixed());
    }

    @Test
    public void testFixedPropertyWithEmptyStringDefaultAllowsLaterUnrelatedEdit()
    {
        // codex review residual #4b: an EMPTY-STRING default is a legitimate value (Property has no
        // isSetDefault()/unset pair - null is the only "absent" signal), so it must count as PRESENT, not
        // absent - a later call editing an UNRELATED attribute on an already-fixed property must not
        // re-fire the fixed-needs-default check against it.
        Package pkg = newPackage();
        ObjectType owner = XdtoWriter.createObjectType(pkg, "Owner"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(owner.getProperties(), "MyProp"); //$NON-NLS-1$
        Result seed = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"fixed\",\"value\":true}"), //$NON-NLS-1$
                json("{\"name\":\"default\",\"value\":\"\"}")), //$NON-NLS-1$
            false);
        assertFalse("fixed=true with an EMPTY STRING default must be accepted: " + seed.error, //$NON-NLS-1$
            seed.hasError());
        assertTrue(property.isFixed());
        assertEquals("", property.getDefault()); //$NON-NLS-1$ //$NON-NLS-2$

        Result laterEdit = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"lowerBound\",\"value\":0}")), false); //$NON-NLS-1$
        assertFalse("an unrelated bound-only edit must not re-trigger the fixed-needs-default check " //$NON-NLS-1$
            + "against an empty-string default: " + laterEdit.error, laterEdit.hasError()); //$NON-NLS-1$
        assertEquals(0, property.getLowerBound());
    }

    @Test
    public void testNonStringDefaultIsRejectedAndDoesNotClearExisting()
    {
        // A present-but-non-string 'default' (object/array) must be REJECTED like the sibling
        // properties (lowerBound/nillable/fixed), not silently applied as setDefault(null) -
        // which would clear an existing default while still reporting it as applied.
        Package pkg = newPackage();
        ObjectType owner = XdtoWriter.createObjectType(pkg, "Owner"); //$NON-NLS-1$
        Property property = XdtoWriter.createProperty(owner.getProperties(), "MyProp"); //$NON-NLS-1$
        Result seed = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":\"string\"}"), //$NON-NLS-1$
                json("{\"name\":\"default\",\"value\":\"N/A\"}")), //$NON-NLS-1$
            true);
        assertFalse(seed.error, seed.hasError());
        assertEquals("N/A", property.getDefault()); //$NON-NLS-1$ //$NON-NLS-2$

        Result bad = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"default\",\"value\":{\"oops\":1}}")), false); //$NON-NLS-1$
        assertTrue("a non-string 'default' must be rejected", bad.hasError()); //$NON-NLS-1$
        assertTrue("the error must name the property and the expected type: " + bad.error, //$NON-NLS-1$
            bad.error.contains("'default'") && bad.error.contains("string")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the existing default must be untouched after the rejected edit", //$NON-NLS-1$
            "N/A", property.getDefault()); //$NON-NLS-1$
    }

    @Test
    public void testPropertyRejectsUnassignableAttribute()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":\"string\"}"), //$NON-NLS-1$
                json("{\"name\":\"bogus\",\"value\":1}")), //$NON-NLS-1$
            true);
        assertTrue("an unknown Property attribute must be rejected", r.hasError()); //$NON-NLS-1$
        assertNull("a rejected spec must not mutate the property", property.getType()); //$NON-NLS-1$
        assertJsonError(r.error);
    }

    @Test
    public void testPropertyRejectsMalformedType()
    {
        Package pkg = newPackage();
        Property property = XdtoWriter.createProperty(pkg.getProperties(), "MyProp"); //$NON-NLS-1$
        Result r = XdtoWriter.applyPropertyProperties(property, pkg,
            List.of(json("{\"name\":\"type\",\"value\":42}")), true); //$NON-NLS-1$
        assertTrue(r.hasError());
        assertJsonError(r.error);
    }

    // ==================== resolveQName ====================

    @Test
    public void testResolveQNameObjectFormOwnNamespace()
    {
        // pkg == null -> the Import-reachability check no-ops (no package context to validate against);
        // production call sites always supply the real Package.
        JsonObject spec = json("{\"nsUri\":\"http://custom/ns\",\"name\":\"Custom\"}"); //$NON-NLS-1$
        QNameResult r = XdtoWriter.resolveQName(spec, null, "'type'"); //$NON-NLS-1$
        assertFalse(r.error, r.error != null);
        QName qname = r.qname;
        assertEquals("Custom", qname.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("http://custom/ns", qname.getNsUri()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testResolveQNameObjectFormMissingNameFails()
    {
        QNameResult r = XdtoWriter.resolveQName(json("{\"nsUri\":\"http://custom/ns\"}"), null, "'type'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(r.error != null);
        assertJsonError(r.error);
    }

    @Test
    public void testResolveQNameNullFails()
    {
        QNameResult r = XdtoWriter.resolveQName(null, null, "'type'"); //$NON-NLS-1$
        assertTrue(r.error != null);
        assertJsonError(r.error);
    }
}
