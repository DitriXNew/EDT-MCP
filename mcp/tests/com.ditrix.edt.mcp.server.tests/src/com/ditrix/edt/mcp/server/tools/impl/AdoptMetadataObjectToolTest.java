/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Arrays;

import org.eclipse.emf.ecore.EObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Lightweight contract tests for {@link AdoptMetadataObjectTool}: tool metadata and JSON schema,
 * without needing the Eclipse/EDT runtime. The {@code execute()} path requires a live workbench +
 * BM model + an extension project, so the actual adopt behaviour (objectBelonging=ADOPTED,
 * extendedConfigurationObject link, multi-extension selection) is covered by the E2E suite; the
 * pure steps around it - which source an FQN resolves to, the address the result echoes, the
 * project-kind and not-found refusals and the files an adoption exports - are pinned here headlessly
 * (issue #708).
 * The shared pieces they are built from have pins of their own: the address dispatch in
 * {@code MetadataNodeResolverTest}, the subsystem walk and the addressing sentence in
 * {@code SubsystemUtilsTest}.
 */
import java.util.Collections;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;

import java.util.Map;

public class AdoptMetadataObjectToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("adopt_metadata_object", new AdoptMetadataObjectTool().getName()); //$NON-NLS-1$
        assertEquals(AdoptMetadataObjectTool.NAME, new AdoptMetadataObjectTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new AdoptMetadataObjectTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new AdoptMetadataObjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('adopt_metadata_object')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new AdoptMetadataObjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"extensionProjectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AdoptMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // extensionProjectName is optional (auto-selected when there is a single extension).
        assertFalse("extensionProjectName must NOT be required", //$NON-NLS-1$
            tail.contains("\"extensionProjectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresContract()
    {
        String schema = new AdoptMetadataObjectTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"action\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectBelonging\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"persisted\"")); //$NON-NLS-1$
    }

    // ==================== source resolution (issue #708) ====================
    //
    // An in-memory configuration: Sales -> Orders -> Backlog, the reporter's Russian pair
    // Планирование -> ПланированиеЗапасов, and Catalog Goods with Attribute Weight and Form ItemForm.
    // Like the platform model, a nested subsystem is NOT contained by its parent: the parent only
    // lists it (Subsystem.subsystems is a reference), and the child points back (parentSubsystem).
    // Russian tokens and names are Unicode escapes so a non-UTF-8 build cannot corrupt them.

    /** "Подсистема" (Subsystem). */
    private static final String RU_SUBSYSTEM = "\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u0430"; //$NON-NLS-1$
    /** "Подсистемы" (Subsystems). */
    private static final String RU_SUBSYSTEMS = "\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u044b"; //$NON-NLS-1$
    /** "Справочник" (Catalog). */
    private static final String RU_CATALOG = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a"; //$NON-NLS-1$
    /** "Форма" (Form). */
    private static final String RU_FORM = "\u0424\u043e\u0440\u043c\u0430"; //$NON-NLS-1$
    /** "Формы" (Forms). */
    private static final String RU_FORMS = "\u0424\u043e\u0440\u043c\u044b"; //$NON-NLS-1$
    /** "Планирование" - the reporter's parent subsystem. */
    private static final String RU_PLANNING =
        "\u041f\u043b\u0430\u043d\u0438\u0440\u043e\u0432\u0430\u043d\u0438\u0435"; //$NON-NLS-1$
    /** "ПланированиеЗапасов" - the reporter's nested subsystem. */
    private static final String RU_STOCK_PLANNING = RU_PLANNING + "\u0417\u0430\u043f\u0430\u0441\u043e\u0432"; //$NON-NLS-1$

    /** The not-found text every address had before #708, kept byte for byte for a non-subsystem one. */
    private static final String OLD_NOT_FOUND_TAIL = ". Check the FQN: 'Type.Name' for a top object " //$NON-NLS-1$
        + "(e.g. 'Catalog.Products'), 'Type.Name.Kind.Name' for a member (e.g. " //$NON-NLS-1$
        + "'Catalog.Products.Attribute.Weight'), 'Type.Name.Form.FormName' for a form (e.g. " //$NON-NLS-1$
        + "'Catalog.Products.Form.ItemForm')."; //$NON-NLS-1$

    private Configuration config;
    private Catalog goods;
    private CatalogAttribute weight;
    private CatalogForm itemForm;
    private Subsystem sales;
    private Subsystem orders;
    private Subsystem backlog;
    private Subsystem planning;
    private Subsystem stockPlanning;

    @Before
    public void setUpModel()
    {
        config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Cfg"); //$NON-NLS-1$
        goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        weight = MdClassFactory.eINSTANCE.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        goods.getAttributes().add(weight);
        itemForm = MdClassFactory.eINSTANCE.createCatalogForm();
        itemForm.setName("ItemForm"); //$NON-NLS-1$
        goods.getForms().add(itemForm);
        config.getCatalogs().add(goods);

        sales = subsystem("Sales", null); //$NON-NLS-1$
        orders = subsystem("Orders", sales); //$NON-NLS-1$
        backlog = subsystem("Backlog", orders); //$NON-NLS-1$
        config.getSubsystems().add(sales);
        planning = subsystem(RU_PLANNING, null);
        stockPlanning = subsystem(RU_STOCK_PLANNING, planning);
        config.getSubsystems().add(planning);
    }

    /** A subsystem linked under {@code parent} both ways, as the platform links a nested one. */
    private static Subsystem subsystem(String name, Subsystem parent)
    {
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        subsystem.setName(name);
        if (parent != null)
        {
            parent.getSubsystems().add(subsystem);
            subsystem.setParentSubsystem(parent);
        }
        return subsystem;
    }

    /** Resolves {@code fqn} the way the tool does: normalized first, then the adoption source. */
    private AdoptMetadataObjectTool.AdoptionSource source(String fqn)
    {
        return AdoptMetadataObjectTool.AdoptionSource.resolve(MetadataScope.ofConfiguration(config),
            MetadataTypeUtils.normalizeFqn(fqn));
    }

    /** The object {@code fqn} resolves to as an adoption source, or {@code null}. */
    private EObject resolve(String fqn)
    {
        AdoptMetadataObjectTool.AdoptionSource source = source(fqn);
        return source == null ? null : source.object;
    }

    /** The address every result names the source {@code fqn} resolves to by. */
    private String name(String fqn)
    {
        return source(fqn).fqn;
    }

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    @Test
    public void testANestedSubsystemChainResolvesTheNestedSubsystem()
    {
        assertSame(orders, resolve("Subsystem.Sales.Subsystem.Orders")); //$NON-NLS-1$
        assertSame("every level of a deeper chain is walked", backlog, //$NON-NLS-1$
            resolve("Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog")); //$NON-NLS-1$
    }

    @Test
    public void testANestedSubsystemResolvesWhateverTheTokenLanguageNumberOrCase()
    {
        assertSame("Russian tokens", orders, resolve(RU_SUBSYSTEM + ".Sales." + RU_SUBSYSTEM + ".Orders")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertSame("Russian then English", orders, resolve(RU_SUBSYSTEM + ".Sales.Subsystem.Orders")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame("English then Russian", orders, resolve("Subsystem.Sales." + RU_SUBSYSTEM + ".Orders")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertSame("English plurals", orders, resolve("Subsystems.Sales.Subsystems.Orders")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame("a Russian plural", orders, resolve("Subsystem.Sales." + RU_SUBSYSTEMS + ".Orders")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertSame("lower case throughout", orders, resolve("subsystem.sales.subsystem.orders")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEverySpellingTheIssueTriedReachesTheRussianNestedSubsystem()
    {
        // #708 step 2: the chain exactly as list_subsystems printed it, then the Russian and the
        // plural spellings - every one of them answered "Object not found".
        assertSame(stockPlanning, resolve("Subsystem." + RU_PLANNING + ".Subsystem." + RU_STOCK_PLANNING)); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(stockPlanning, resolve(RU_SUBSYSTEM + "." + RU_PLANNING + "." + RU_SUBSYSTEM + "." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + RU_STOCK_PLANNING));
        assertSame(stockPlanning, resolve("Subsystem." + RU_PLANNING + ".Subsystems." + RU_STOCK_PLANNING)); //$NON-NLS-1$ //$NON-NLS-2$
        // ...and the fourth spelling stays a miss: a bare child names a TOP-level subsystem only.
        assertNull(resolve("Subsystem." + RU_STOCK_PLANNING)); //$NON-NLS-1$
        // Step 4, the control: the top-level parent was always adoptable.
        assertSame(planning, resolve("Subsystem." + RU_PLANNING)); //$NON-NLS-1$
    }

    @Test
    public void testATopLevelSubsystemStillResolves()
    {
        assertSame(sales, resolve("Subsystem.Sales")); //$NON-NLS-1$
        assertSame(sales, resolve(RU_SUBSYSTEM + ".Sales")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingNestedSubsystemIsNotFoundNeverItsParent()
    {
        assertNull("a missing leaf must not answer with its parent", //$NON-NLS-1$
            resolve("Subsystem.Sales.Subsystem.Missing")); //$NON-NLS-1$
        assertNull("a missing leaf two levels down", //$NON-NLS-1$
            resolve("Subsystem.Sales.Subsystem.Orders.Subsystem.Missing")); //$NON-NLS-1$
        assertNull("a missing parent", resolve("Subsystem.Missing.Subsystem.Orders")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testABareNestedChildIsNotFound()
    {
        // Same-named children can live under different parents, so the bare name is not guessed.
        assertNull(resolve("Subsystem.Orders")); //$NON-NLS-1$
        assertNull(resolve("Subsystem.Backlog")); //$NON-NLS-1$
    }

    @Test
    public void testTopObjectsMembersAndFormsResolveAsBefore()
    {
        assertSame(goods, resolve("Catalog.Goods")); //$NON-NLS-1$
        assertSame(goods, resolve(RU_CATALOG + ".Goods")); //$NON-NLS-1$
        assertSame(weight, resolve("Catalog.Goods.Attribute.Weight")); //$NON-NLS-1$
        assertSame(itemForm, resolve("Catalog.Goods.Form.ItemForm")); //$NON-NLS-1$
        assertSame(itemForm, resolve("Catalog.Goods.Forms.ItemForm")); //$NON-NLS-1$
        assertNull(resolve("Catalog.Goods.Attribute.Missing")); //$NON-NLS-1$
        assertNull(resolve("Catalog.Goods.Form.Missing")); //$NON-NLS-1$
        assertNull(resolve("Catalog.Missing")); //$NON-NLS-1$
    }

    @Test
    public void testTheRussianFormTokenAddressesTheForm()
    {
        // The guide promises bilingual KIND tokens; the form branch used to accept only 'Form'/'Forms'.
        assertSame(itemForm, resolve(RU_CATALOG + ".Goods." + RU_FORM + ".ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(itemForm, resolve("Catalog.Goods." + RU_FORMS + ".ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheSourceIsResolvedExactlyWithoutTheYoRetry()
    {
        // The shared dispatch can retry a yo spelling against a Name stored with ye; the guard asks
        // for that retry, adopt does not - it resolves exactly, as it always did.
        Catalog fir = MdClassFactory.eINSTANCE.createCatalog();
        fir.setName(fromCp(0x0415, 0x043b, 0x043a, 0x0430));
        config.getCatalogs().add(fir);

        assertSame(fir, resolve("Catalog." + fromCp(0x0415, 0x043b, 0x043a, 0x0430))); //$NON-NLS-1$
        assertNull("a yo spelling of a ye-stored Name is not this object to adopt", //$NON-NLS-1$
            resolve("Catalog." + fromCp(0x0401, 0x043b, 0x043a, 0x0430))); //$NON-NLS-1$
    }

    // ==================== the echoed address ====================

    @Test
    public void testASubsystemIsNamedByItsCanonicalChainOfStoredNames()
    {
        // The caller's mixed spelling and letter case come back as list_subsystems prints the chain.
        assertEquals("Subsystem.Sales.Subsystem.Orders", //$NON-NLS-1$
            name(RU_SUBSYSTEM + ".sales." + RU_SUBSYSTEMS + ".orders")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Subsystem." + RU_PLANNING + ".Subsystem." + RU_STOCK_PLANNING, //$NON-NLS-1$ //$NON-NLS-2$
            name(RU_SUBSYSTEM + "." + RU_PLANNING + "." + RU_SUBSYSTEM + "." + RU_STOCK_PLANNING)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog", //$NON-NLS-1$
            name("Subsystem.sales.Subsystem.orders.Subsystem.backlog")); //$NON-NLS-1$
        assertEquals("Subsystem.Sales", name("Subsystem.sales")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnyOtherSourceKeepsItsNormalizedFqn()
    {
        assertEquals("Catalog.Goods.Attribute.Weight", name("Catalog.Goods.Attribute.Weight")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.goods", name("Catalog.goods")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testASubsystemWhoseChainCannotBeReadUpKeepsTheCallersAddress()
    {
        // A broken model: Lost is listed under Sales, but its own parent link loops back to itself, so
        // the walk up never reaches a top-level subsystem - and must not invent a shorter address.
        Subsystem lost = subsystem("Lost", sales); //$NON-NLS-1$
        lost.setParentSubsystem(lost);

        assertSame(lost, resolve("Subsystem.sales.Subsystem.lost")); //$NON-NLS-1$
        assertEquals("Subsystem.sales.Subsystem.lost", name("Subsystem.sales.Subsystem.lost")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testASubsystemWhoseParentLinkIsMissingKeepsTheCallersAddress()
    {
        // Listed by its parent, but its own parentSubsystem is not set: the walk up ends at once and
        // reads it as TOP-LEVEL. The address resolved DOWN through two levels, so that one-level
        // reading ('Subsystem.Orphan', which names no subsystem here) is not its address.
        Subsystem orphan = MdClassFactory.eINSTANCE.createSubsystem();
        orphan.setName("Orphan"); //$NON-NLS-1$
        sales.getSubsystems().add(orphan);

        assertSame(orphan, resolve("Subsystem.sales.Subsystem.orphan")); //$NON-NLS-1$
        assertEquals("Subsystem.sales.Subsystem.orphan", name("Subsystem.sales.Subsystem.orphan")); //$NON-NLS-1$ //$NON-NLS-2$
        // ...while a linked child of the same parent is still named by its canonical chain.
        assertEquals("Subsystem.Sales.Subsystem.Orders", name("Subsystem.sales.Subsystem.orders")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testASubsystemWhoseParentLinkNamesAnotherTopLevelSubsystemKeepsTheCallersAddress()
    {
        // A broken model: Sales lists Stray, but Stray's own parent link names Planning. The walk up
        // has as many levels as the address and still spells another one - Planning's chain, where a
        // TWIN named Stray really lives. Named by it, every result would point at the twin.
        Subsystem stray = MdClassFactory.eINSTANCE.createSubsystem();
        stray.setName("Stray"); //$NON-NLS-1$
        sales.getSubsystems().add(stray);
        stray.setParentSubsystem(planning);
        Subsystem twin = subsystem("Stray", planning); //$NON-NLS-1$

        assertSame(stray, resolve("Subsystem.sales.Subsystem.stray")); //$NON-NLS-1$
        assertEquals("Subsystem.sales.Subsystem.stray", name("Subsystem.sales.Subsystem.stray")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame("the echoed address must lead back to the source itself, not to its twin", stray, //$NON-NLS-1$
            resolve(name("Subsystem.sales.Subsystem.stray"))); //$NON-NLS-1$
        // The control: the twin, linked both ways, is a real subsystem and keeps its canonical chain.
        assertSame(twin, resolve("Subsystem." + RU_PLANNING + ".Subsystem.Stray")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Subsystem." + RU_PLANNING + ".Subsystem.Stray", //$NON-NLS-1$ //$NON-NLS-2$
            name("Subsystem." + RU_PLANNING + ".Subsystem.stray")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testASubsystemWhoseMiddleLevelLinksToAnotherParentKeepsTheCallersAddress()
    {
        // The top level agrees and a middle one does not: Orders lists Refunds, whose parent link names
        // Returns, a sibling of Orders. The walk up reads Sales, Returns, Refunds - three levels, like
        // the address - and spells a chain that names nothing here.
        Subsystem returns = subsystem("Returns", sales); //$NON-NLS-1$
        Subsystem refunds = MdClassFactory.eINSTANCE.createSubsystem();
        refunds.setName("Refunds"); //$NON-NLS-1$
        orders.getSubsystems().add(refunds);
        refunds.setParentSubsystem(returns);
        String address = "Subsystem.sales.Subsystem.orders.Subsystem.refunds"; //$NON-NLS-1$

        assertSame(refunds, resolve(address));
        assertEquals(address, name(address));
        assertSame("the echoed address must lead back to the source itself", refunds, //$NON-NLS-1$
            resolve(name(address)));
        // The control: the chain the walk up reads is no address of it.
        assertNull(resolve("Subsystem.Sales.Subsystem.Returns.Subsystem.Refunds")); //$NON-NLS-1$
    }

    @Test
    public void testASubsystemWhoseWalkUpRunsPastTheAddressKeepsTheCallersAddress()
    {
        // A broken model: the configuration lists Sales at the top, but its own parent link names
        // Planning. The walk up reads Planning, Sales - one level more than the address - and only its
        // last level matches the address: named by that chain, every result would point at no subsystem.
        sales.setParentSubsystem(planning);

        assertSame(sales, resolve("Subsystem.sales")); //$NON-NLS-1$
        assertEquals("Subsystem.sales", name("Subsystem.sales")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame("the echoed address must lead back to the source itself", sales, //$NON-NLS-1$
            resolve(name("Subsystem.sales"))); //$NON-NLS-1$
        // The control: the chain the walk up reads is no address of it.
        assertNull(resolve("Subsystem." + RU_PLANNING + ".Subsystem.Sales")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== every result names the source by it (the wiring) ====================

    private static JsonObject json(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject();
    }

    @Test
    public void testEveryResultNamesASubsystemByItsCanonicalChain()
    {
        // Not the naming but its wiring: the address each result carries comes with the source.
        AdoptMetadataObjectTool.AdoptionSource source =
            source(RU_SUBSYSTEM + ".sales." + RU_SUBSYSTEMS + ".orders"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(source);
        assertSame(orders, source.object);
        String chain = "Subsystem.Sales.Subsystem.Orders"; //$NON-NLS-1$

        JsonObject adopted = json(AdoptMetadataObjectTool.adoptedResult(source, "Ext", true)); //$NON-NLS-1$
        assertTrue(adopted.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("adopted", adopted.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(chain, adopted.get("fqn").getAsString()); //$NON-NLS-1$
        assertEquals("Ext", adopted.get("extensionProject").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ADOPTED", adopted.get("objectBelonging").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(adopted.get("persisted").getAsBoolean()); //$NON-NLS-1$
        assertFalse(json(AdoptMetadataObjectTool.adoptedResult(source, "Ext", false)) //$NON-NLS-1$
            .get("persisted").getAsBoolean()); //$NON-NLS-1$

        JsonObject already = json(AdoptMetadataObjectTool.alreadyAdoptedResult(source, "Ext")); //$NON-NLS-1$
        assertTrue(already.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("alreadyAdopted", already.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(chain, already.get("fqn").getAsString()); //$NON-NLS-1$
        assertEquals("'" + chain + "' is already adopted in extension 'Ext'.", //$NON-NLS-1$ //$NON-NLS-2$
            already.get("message").getAsString()); //$NON-NLS-1$
        assertEquals("Ext", already.get("extensionProject").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ADOPTED", already.get("objectBelonging").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(already.get("persisted").getAsBoolean()); //$NON-NLS-1$

        JsonObject refusal = json(AdoptMetadataObjectTool.notAdoptableError(source));
        assertFalse(refusal.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("'" + chain + "' cannot be adopted into an extension " //$NON-NLS-1$ //$NON-NLS-2$
            + "(the platform reports it is not adoptable).", refusal.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnyOtherSourceIsNamedByItsNormalizedFqnAndAMissIsNoSource()
    {
        AdoptMetadataObjectTool.AdoptionSource attribute = source(RU_CATALOG + ".Goods.Attribute.Weight"); //$NON-NLS-1$
        assertNotNull(attribute);
        assertSame(weight, attribute.object);
        assertEquals("Catalog.Goods.Attribute.Weight", attribute.fqn); //$NON-NLS-1$
        // A miss is no source at all - not a source without an object, which the caller would adopt.
        assertNull(source("Subsystem.Sales.Subsystem.Missing")); //$NON-NLS-1$
        assertNull(source("Catalog.Missing")); //$NON-NLS-1$
    }

    // ==================== the project kind ====================

    @Test
    public void testAnExternalObjectsProjectIsRefusedByItsKind()
    {
        // Only a configuration has extensions. An external-objects project is refused by its KIND
        // before any address is resolved: its scope holds its own external objects only, so the base
        // catalog's valid FQN would come back "not found" there and blame the address.
        MetadataScope external = MetadataScopeTestFixtures.externalObjectsWithBase(config);
        assertEquals("Project 'Ext' is an EXTERNAL-OBJECTS project, and an extension extends only a " //$NON-NLS-1$
            + "configuration: pass the BASE configuration as projectName (adopt_metadata_object adopts " //$NON-NLS-1$
            + "an object of that configuration into one of its extensions).", //$NON-NLS-1$
            AdoptMetadataObjectTool.projectKindRefusal(external, "Ext")); //$NON-NLS-1$
        assertNull("a configuration project is adopted from", //$NON-NLS-1$
            AdoptMetadataObjectTool.projectKindRefusal(MetadataScope.ofConfiguration(config), "Cfg")); //$NON-NLS-1$
        // What the refusal comes BEFORE: the base catalog resolves to nothing in that scope.
        assertNull(AdoptMetadataObjectTool.AdoptionSource.resolve(external, "Catalog.Goods")); //$NON-NLS-1$
    }

    // ==================== the not-found refusal ====================

    @Test
    public void testANotFoundSubsystemAddressSaysHowANestedOneIsAddressed()
    {
        // The issue's fourth spelling: a bare child. The refusal must teach the chain, not only
        // 'Type.Name' - in the sentence the subsystem refusals share.
        String fqn = "Subsystem." + RU_STOCK_PLANNING; //$NON-NLS-1$
        assertEquals("Object not found: " + fqn + OLD_NOT_FOUND_TAIL + " " //$NON-NLS-1$ //$NON-NLS-2$
            + SubsystemUtilsTest.ADDRESSING_HINT_TEXT + ".", AdoptMetadataObjectTool.sourceNotFound(fqn)); //$NON-NLS-1$
        assertEquals("a missing nested chain gets it too", //$NON-NLS-1$
            "Object not found: Subsystem.Sales.Subsystem.Missing" + OLD_NOT_FOUND_TAIL + " " //$NON-NLS-1$ //$NON-NLS-2$
                + SubsystemUtilsTest.ADDRESSING_HINT_TEXT + ".", //$NON-NLS-1$
            AdoptMetadataObjectTool.sourceNotFound("Subsystem.Sales.Subsystem.Missing")); //$NON-NLS-1$
    }

    @Test
    public void testAnyOtherNotFoundAddressKeepsTheOldTextExactly()
    {
        assertEquals("Object not found: Catalog.Missing" + OLD_NOT_FOUND_TAIL, //$NON-NLS-1$
            AdoptMetadataObjectTool.sourceNotFound("Catalog.Missing")); //$NON-NLS-1$
        assertEquals("Object not found: Catalog.Goods.Form.Missing" + OLD_NOT_FOUND_TAIL, //$NON-NLS-1$
            AdoptMetadataObjectTool.sourceNotFound("Catalog.Goods.Form.Missing")); //$NON-NLS-1$
    }

    // ==================== the exported files ====================
    //
    // The parents come from SubsystemUtils.lineage - the walk that also names a subsystem source - and
    // its order, cycle and proxy stops are pinned in SubsystemUtilsTest; these pin what the export
    // list makes of it.

    @Test
    public void testAnAdoptedNestedSubsystemExportsEveryParentAboveIt()
    {
        // After the platform's cascade every parent is new in the extension, and each one's .mdo lists
        // its child in <subsystems>; the configuration lists the top-level one.
        Subsystem root = bmSubsystem("Subsystem.Sales", null); //$NON-NLS-1$
        Subsystem middle = bmSubsystem("Subsystem.Sales.Subsystem.Orders", root); //$NON-NLS-1$
        Subsystem leaf = bmSubsystem("Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog", middle); //$NON-NLS-1$

        assertEquals(Arrays.asList("Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog", //$NON-NLS-1$
            "Subsystem.Sales.Subsystem.Orders", "Subsystem.Sales", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            AdoptMetadataObjectTool.dirtyFqns(leaf, bmConfiguration()));
        assertEquals(Arrays.asList("Subsystem.Sales.Subsystem.Orders", "Subsystem.Sales", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            AdoptMetadataObjectTool.dirtyFqns(middle, bmConfiguration()));
    }

    @Test
    public void testAnyOtherAdoptionExportsItsTopObjectAndTheConfigurationOnly()
    {
        assertEquals(Arrays.asList("Subsystem.Sales", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$
            AdoptMetadataObjectTool.dirtyFqns(bmSubsystem("Subsystem.Sales", null), bmConfiguration())); //$NON-NLS-1$

        IBmObject catalog = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(catalog.bmGetTopObject()).thenReturn(catalog);
        when(catalog.bmGetFqn()).thenReturn("Catalog.Goods"); //$NON-NLS-1$
        IBmObject attribute = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(attribute.bmGetTopObject()).thenReturn(catalog);
        assertEquals("a member exports its top object", Arrays.asList("Catalog.Goods", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            AdoptMetadataObjectTool.dirtyFqns(attribute, bmConfiguration()));
        assertEquals("no configuration, no configuration entry", Collections.singletonList("Catalog.Goods"), //$NON-NLS-1$ //$NON-NLS-2$
            AdoptMetadataObjectTool.dirtyFqns(catalog, null));
    }

    @Test
    public void testAnUnresolvedParentIsNotAskedForItsFqn()
    {
        // A proxy is not an attached BM object: bmGetFqn() would throw after the adoption committed.
        Subsystem proxy = mock(Subsystem.class, withSettings().extraInterfaces(IBmObject.class));
        when(proxy.eIsProxy()).thenReturn(true);
        when(((IBmObject)proxy).bmGetFqn()).thenThrow(new IllegalStateException("not attached")); //$NON-NLS-1$
        Subsystem child = bmSubsystem("Subsystem.Gone.Subsystem.Child", proxy); //$NON-NLS-1$

        assertEquals(Arrays.asList("Subsystem.Gone.Subsystem.Child", "Configuration"), //$NON-NLS-1$ //$NON-NLS-2$
            AdoptMetadataObjectTool.dirtyFqns(child, bmConfiguration()));
    }

    /** A subsystem as the adopter returns it: an attached BM top object with an FQN and a parent link. */
    private static Subsystem bmSubsystem(String fqn, Subsystem parent)
    {
        Subsystem subsystem = mock(Subsystem.class, withSettings().extraInterfaces(IBmObject.class));
        IBmObject bm = (IBmObject)subsystem;
        when(bm.bmGetTopObject()).thenReturn(bm);
        when(bm.bmGetFqn()).thenReturn(fqn);
        when(subsystem.getParentSubsystem()).thenReturn(parent);
        return subsystem;
    }

    /** The extension's configuration as a BM top object. */
    private static Configuration bmConfiguration()
    {
        Configuration configuration = mock(Configuration.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)configuration).bmGetFqn()).thenReturn("Configuration"); //$NON-NLS-1$
        return configuration;
    }
}
