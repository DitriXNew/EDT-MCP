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

import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.metadata.mdclass.AccountingRegister;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.CalculationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Cube;
import com._1c.g5.v8.dt.metadata.mdclass.Dimension;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentJournal;
import com._1c.g5.v8.dt.metadata.mdclass.DocumentTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.HTTPService;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.IntegrationService;
import com._1c.g5.v8.dt.metadata.mdclass.IntegrationServiceChannel;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Method;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.TabularSectionAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Task;
import com._1c.g5.v8.dt.metadata.mdclass.URLTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.WebService;

/**
 * Tests for the pure, model-independent logic of {@link MetadataNodeResolver}: FQN arity validation
 * and the bilingual kind-token map. The model-dependent {@code resolveExisting} /
 * {@code resolveForCreate} paths are covered by the e2e suite against a live configuration.
 *
 * <p>Russian tokens are constructed here from code points (independently of the resolver's own
 * {@code cp(...)} construction) so the assertion verifies the actual Cyrillic mapping, not just a
 * round-trip of the same literal.</p>
 */
public class MetadataNodeResolverTest
{
    /** Builds a string from BMP code points (keeps this test source pure ASCII). */
    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    /**
     * {@link MetadataNodeResolver#childKindsFor} answers from the OWNER's own EClass, so a type
     * that simply has no such collection can be told apart from a mistyped kind (issue #309).
     */
    @Test
    public void testChildKindsAreReadOffTheOwnerType()
    {
        List<String> catalogKinds = MetadataNodeResolver.childKindsFor(
            MdClassFactory.eINSTANCE.createCatalog());
        assertTrue(catalogKinds.toString(), catalogKinds.contains("Attribute")); //$NON-NLS-1$
        assertTrue(catalogKinds.toString(), catalogKinds.contains("TabularSection")); //$NON-NLS-1$
        assertTrue(catalogKinds.toString(), catalogKinds.contains("Command")); //$NON-NLS-1$
        // A Catalog has no accounting flags - that is a ChartOfAccounts collection.
        assertFalse(catalogKinds.toString(), catalogKinds.contains("AccountingFlag")); //$NON-NLS-1$

        // An external data processor carries attributes / tabular sections / templates but NO
        // commands: the platform model has no such collection on it, which is why
        // '...Command.X' can never resolve there however it is spelled.
        List<String> externalKinds = MetadataNodeResolver.childKindsFor(
            MdClassFactory.eINSTANCE.createExternalDataProcessor());
        assertTrue(externalKinds.toString(), externalKinds.contains("Attribute")); //$NON-NLS-1$
        assertTrue(externalKinds.toString(), externalKinds.contains("TabularSection")); //$NON-NLS-1$
        assertTrue(externalKinds.toString(), externalKinds.contains("Template")); //$NON-NLS-1$
        assertFalse(externalKinds.toString(), externalKinds.contains("Command")); //$NON-NLS-1$

        assertTrue(MetadataNodeResolver.childKindsFor(null).isEmpty());
    }

    /**
     * A TOP-level create of a STANDALONE type does not resolve: an external data processor is the
     * root of its own project, not a row in a Configuration collection (issue #309). The tool
     * turns this {@code null} into the refusal that says where such an object comes from.
     */
    @Test
    public void testTopLevelCreateOfAStandaloneTypeDoesNotResolve()
    {
        assertNull(MetadataNodeResolver.resolveForCreate(
            MetadataScope.ofConfiguration(MdClassFactory.eINSTANCE.createConfiguration()),
            "ExternalDataProcessor.NewProc")); //$NON-NLS-1$
        // A configuration type still resolves for create, unchanged.
        assertNotNull(MetadataNodeResolver.resolveForCreate(
            MetadataScope.ofConfiguration(MdClassFactory.eINSTANCE.createConfiguration()),
            "Catalog.NewCatalog")); //$NON-NLS-1$
    }

    /**
     * The MIRROR case: a CONFIGURATION type addressed at an external-objects project. There is
     * no configuration here to add a row to, so handing back a target made the create treat this
     * project's root as a Configuration and die with a raw ClassCastException. Answering
     * "no target" is what lets the tool refuse by naming the project kind instead
     * (issue #309 review round 4).
     */
    @Test
    public void testTopLevelCreateOfAConfigurationTypeDoesNotResolveInAnExternalScope()
    {
        MetadataScope external = MetadataScope.ofExternalObjectProject(null,
            MdClassFactory.eINSTANCE.createConfiguration(),
            mock(IExternalObjectProject.class));
        assertNull(MetadataNodeResolver.resolveForCreate(external, "Catalog.NewCatalog")); //$NON-NLS-1$
        // Its OWN root type is refused here too - those come with the project, not from a create.
        assertNull(MetadataNodeResolver.resolveForCreate(external, "ExternalDataProcessor.New")); //$NON-NLS-1$
    }

    @Test
    public void testValidArity()
    {
        // Type.Name, then complete .Kind.Name pairs: 2, 4, 6 are valid.
        assertTrue(MetadataNodeResolver.isValidArity(2));
        assertTrue(MetadataNodeResolver.isValidArity(4));
        assertTrue(MetadataNodeResolver.isValidArity(6));
    }

    @Test
    public void testInvalidArity()
    {
        // An odd trailing token (a kind with no name) must be rejected.
        assertFalse(MetadataNodeResolver.isValidArity(0));
        assertFalse(MetadataNodeResolver.isValidArity(1));
        assertFalse(MetadataNodeResolver.isValidArity(3));
        assertFalse(MetadataNodeResolver.isValidArity(5));
    }

    @Test
    public void testFeatureNameForEnglishTokens()
    {
        assertEquals("attributes", MetadataNodeResolver.featureNameForKind("attribute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("attributes", MetadataNodeResolver.featureNameForKind("attributes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("tabularSections", MetadataNodeResolver.featureNameForKind("tabularSection")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("dimensions", MetadataNodeResolver.featureNameForKind("dimension")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("resources", MetadataNodeResolver.featureNameForKind("resource")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("enumValues", MetadataNodeResolver.featureNameForKind("enumValue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("commands", MetadataNodeResolver.featureNameForKind("command")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("commands", MetadataNodeResolver.featureNameForKind("commands")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFeatureNameIsCaseInsensitive()
    {
        assertEquals("attributes", MetadataNodeResolver.featureNameForKind("ATTRIBUTE")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("tabularSections", MetadataNodeResolver.featureNameForKind("TABULARSECTION")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFeatureNameForRussianTokens()
    {
        // rekvizit -> attributes
        assertEquals("attributes", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(
                fromCp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442)));
        // izmerenie -> dimensions
        assertEquals("dimensions", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(
                fromCp(0x0438, 0x0437, 0x043c, 0x0435, 0x0440, 0x0435, 0x043d, 0x0438, 0x0435)));
        // resurs -> resources
        assertEquals("resources", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(
                fromCp(0x0440, 0x0435, 0x0441, 0x0443, 0x0440, 0x0441)));
        // komanda -> commands
        assertEquals("commands", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(
                fromCp(0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430)));
    }

    @Test
    public void testFeatureNameForLongAndPluralRussianTokens()
    {
        // The longest / most transposition-prone code-point arrays, plus the plural forms.
        // tablichnaya chast -> tabularSections
        assertEquals("tabularSections", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x0442, 0x0430, 0x0431, 0x043b, 0x0438, 0x0447, 0x043d, 0x0430, 0x044f, 0x0447, 0x0430, 0x0441, 0x0442, 0x044c)));
        // tablichnye chasti (plural) -> tabularSections
        assertEquals("tabularSections", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x0442, 0x0430, 0x0431, 0x043b, 0x0438, 0x0447, 0x043d, 0x044b, 0x0435, 0x0447, 0x0430, 0x0441, 0x0442, 0x0438)));
        // znachenie perechisleniya -> enumValues
        assertEquals("enumValues", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x0437, 0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438, 0x0435, 0x043f, 0x0435, 0x0440, 0x0435, 0x0447, 0x0438, 0x0441, 0x043b, 0x0435, 0x043d, 0x0438, 0x044f)));
        // znacheniya perechisleniya (plural) -> enumValues
        assertEquals("enumValues", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x0437, 0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438, 0x044f, 0x043f, 0x0435, 0x0440, 0x0435, 0x0447, 0x0438, 0x0441, 0x043b, 0x0435, 0x043d, 0x0438, 0x044f)));
        // plural forms of the short tokens
        assertEquals("attributes", // rekvizity //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442, 0x044b)));
        assertEquals("dimensions", // izmereniya //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x0438, 0x0437, 0x043c, 0x0435, 0x0440, 0x0435, 0x043d, 0x0438, 0x044f)));
        assertEquals("resources", // resursy //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x0440, 0x0435, 0x0441, 0x0443, 0x0440, 0x0441, 0x044b)));
    }

    @Test
    public void testFeatureNameForEnglishEnumValuePlural()
    {
        assertEquals("enumValues", MetadataNodeResolver.featureNameForKind("enumValues")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFeatureNameForInlineSpecialChildTokens()
    {
        // ChartOfAccounts.accountingFlags (en singular/plural)
        assertEquals("accountingFlags", MetadataNodeResolver.featureNameForKind("AccountingFlag")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("accountingFlags", MetadataNodeResolver.featureNameForKind("accountingFlags")); //$NON-NLS-1$ //$NON-NLS-2$
        // ChartOfAccounts.extDimensionAccountingFlags
        assertEquals("extDimensionAccountingFlags", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind("ExtDimensionAccountingFlag")); //$NON-NLS-1$
        // Task.addressingAttributes
        assertEquals("addressingAttributes", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind("AddressingAttribute")); //$NON-NLS-1$
        // DocumentJournal.columns
        assertEquals("columns", MetadataNodeResolver.featureNameForKind("Column")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("columns", MetadataNodeResolver.featureNameForKind("columns")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFeatureNameForInlineSpecialChildRussianTokens()
    {
        // priznak ucheta -> accountingFlags
        assertEquals("accountingFlags", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x043f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0443, 0x0447, 0x0435, 0x0442, 0x0430)));
        // priznak ucheta subkonto -> extDimensionAccountingFlags
        assertEquals("extDimensionAccountingFlags", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x043f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0443, 0x0447, 0x0435, 0x0442, 0x0430,
                0x0441, 0x0443, 0x0431, 0x043a, 0x043e, 0x043d, 0x0442, 0x043e)));
        // rekvizit adresacii -> addressingAttributes
        assertEquals("addressingAttributes", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442,
                0x0430, 0x0434, 0x0440, 0x0435, 0x0441, 0x0430, 0x0446, 0x0438, 0x0438)));
        // kolonka -> columns
        assertEquals("columns", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x043a, 0x043e, 0x043b, 0x043e, 0x043d, 0x043a, 0x0430)));
    }

    @Test
    public void testFeatureNameForSeparateFileChildTokens()
    {
        // Template (en singular/plural)
        assertEquals("templates", MetadataNodeResolver.featureNameForKind("Template")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("templates", MetadataNodeResolver.featureNameForKind("templates")); //$NON-NLS-1$ //$NON-NLS-2$
        // Recalculation
        assertEquals("recalculations", MetadataNodeResolver.featureNameForKind("Recalculation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("recalculations", MetadataNodeResolver.featureNameForKind("recalculations")); //$NON-NLS-1$ //$NON-NLS-2$
        // maket -> templates
        assertEquals("templates", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(0x043c, 0x0430, 0x043a, 0x0435, 0x0442)));
        // pereraschet -> recalculations
        assertEquals("recalculations", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x043f, 0x0435, 0x0440, 0x0435, 0x0440, 0x0430, 0x0441, 0x0447, 0x0435, 0x0442)));
    }

    @Test
    public void testFeatureNameForServiceChildTokens()
    {
        // HTTPService.urlTemplates / URLTemplate.methods (en singular/plural)
        assertEquals("urlTemplates", MetadataNodeResolver.featureNameForKind("URLTemplate")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("urlTemplates", MetadataNodeResolver.featureNameForKind("urlTemplates")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("methods", MetadataNodeResolver.featureNameForKind("Method")); //$NON-NLS-1$ //$NON-NLS-2$
        // WebService.operations / Operation.parameters
        assertEquals("operations", MetadataNodeResolver.featureNameForKind("Operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("parameters", MetadataNodeResolver.featureNameForKind("Parameter")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFeatureNameForServiceChildRussianTokens()
    {
        // shablon + ASCII "URL" -> urlTemplates (the ru token mixes Cyrillic and Latin)
        assertEquals("urlTemplates", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(
                fromCp(0x0448, 0x0430, 0x0431, 0x043b, 0x043e, 0x043d) + "url")); //$NON-NLS-1$
        // metod -> methods
        assertEquals("methods", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(0x043c, 0x0435, 0x0442, 0x043e, 0x0434)));
        // operaciya -> operations
        assertEquals("operations", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x043e, 0x043f, 0x0435, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f)));
        // parametr -> parameters
        assertEquals("parameters", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(fromCp(
                0x043f, 0x0430, 0x0440, 0x0430, 0x043c, 0x0435, 0x0442, 0x0440)));
    }

    @Test
    public void testRussianTokenIsCaseInsensitive()
    {
        // Upper-case REKVIZIT -> attributes (the map is queried lower-cased)
        assertEquals("attributes", //$NON-NLS-1$
            MetadataNodeResolver.featureNameForKind(
                fromCp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442)));
    }

    @Test
    public void testUnknownAndNullTokens()
    {
        assertNull(MetadataNodeResolver.featureNameForKind("Form")); //$NON-NLS-1$
        assertNull(MetadataNodeResolver.featureNameForKind("nonsense")); //$NON-NLS-1$
        assertNull(MetadataNodeResolver.featureNameForKind(null));
    }

    // ===== yo-addressing fallback (the pure resolver seams; the model retry path is e2e) =====

    @Test
    public void testYoRetryFqnReturnsNormalizedFormOnlyWhenDistinct()
    {
        // "Otchyot" with the Russian yo (U+0451) -> the ye-normalized (U+0435) retry form.
        String yoFqn = "Catalog." + fromCp(0x041e, 0x0442, 0x0447, 0x0451, 0x0442); //$NON-NLS-1$
        String expected = "Catalog." + fromCp(0x041e, 0x0442, 0x0447, 0x0435, 0x0442); //$NON-NLS-1$
        assertEquals(expected, MetadataNodeResolver.yoRetryFqn(yoFqn));
        // Already normalized / no yo anywhere / null -> no distinct retry form.
        assertNull(MetadataNodeResolver.yoRetryFqn(expected));
        assertNull(MetadataNodeResolver.yoRetryFqn("Catalog.Products")); //$NON-NLS-1$
        assertNull(MetadataNodeResolver.yoRetryFqn(null));
    }

    @Test
    public void testYoNotFoundHintNamesTheNormalizedForm()
    {
        String yoFqn = "Catalog." + fromCp(0x041e, 0x0442, 0x0447, 0x0451, 0x0442); //$NON-NLS-1$
        String norm = "Catalog." + fromCp(0x041e, 0x0442, 0x0447, 0x0435, 0x0442); //$NON-NLS-1$
        String hint = MetadataNodeResolver.yoNotFoundHint(yoFqn);
        assertTrue("hint must name the normalized form", hint.contains(norm)); //$NON-NLS-1$
        assertTrue("hint must explain create's default normalization", //$NON-NLS-1$
            hint.contains("create_metadata")); //$NON-NLS-1$
        // A yo-less FQN appends nothing (callers append the hint unconditionally).
        assertEquals("", MetadataNodeResolver.yoNotFoundHint("Catalog.Products")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", MetadataNodeResolver.yoNotFoundHint(null)); //$NON-NLS-1$
    }

    @Test
    public void testResolveWithYoFallbackIsNullSafeAndEchoesInput()
    {
        // No configuration: neither form resolves; the result echoes the requested FQN and is
        // NOT flagged as a fallback hit (the not-found message / log contract relies on that).
        MetadataNodeResolver.ResolvedNode r =
            MetadataNodeResolver.resolveExistingWithYoFallback(MetadataScope.ofConfiguration(null),
                "Catalog.X"); //$NON-NLS-1$
        assertNull(r.node);
        assertEquals("Catalog.X", r.fqn); //$NON-NLS-1$
        assertFalse(r.yoFallback);
    }

    // ==================== addressOf / resolvableAddressOf (#685) ====================

    @Test
    public void testAddressOfNamesEveryLevelByItsKindToken()
    {
        Document sale = named(MdClassFactory.eINSTANCE.createDocument(), "Sale"); //$NON-NLS-1$
        DocumentTabularSection goods = named(MdClassFactory.eINSTANCE.createDocumentTabularSection(), "Goods"); //$NON-NLS-1$
        sale.getTabularSections().add(goods);
        TabularSectionAttribute price = named(MdClassFactory.eINSTANCE.createTabularSectionAttribute(), "Price"); //$NON-NLS-1$
        goods.getAttributes().add(price);

        assertEquals("Document.Sale.TabularSection.Goods.Attribute.Price", MetadataNodeResolver.addressOf(price)); //$NON-NLS-1$
        assertEquals("Document.Sale.TabularSection.Goods.Attribute.Price", //$NON-NLS-1$
            MetadataNodeResolver.resolvableAddressOf(price));
        assertEquals("Document.Sale", MetadataNodeResolver.resolvableAddressOf(sale)); //$NON-NLS-1$
    }

    @Test
    public void testTheAddressUsesTheProgrammaticNameNotTheSynonym()
    {
        Catalog goods = named(MdClassFactory.eINSTANCE.createCatalog(), "Goods"); //$NON-NLS-1$
        CatalogAttribute weight = named(MdClassFactory.eINSTANCE.createCatalogAttribute(), "Weight"); //$NON-NLS-1$
        weight.getSynonym().put("en", "Item weight"); //$NON-NLS-1$ //$NON-NLS-2$
        goods.getAttributes().add(weight);

        assertEquals("Catalog.Goods.Attribute.Weight", MetadataNodeResolver.resolvableAddressOf(weight)); //$NON-NLS-1$
        assertEquals("Catalog.Goods.Attribute.Weight", MetadataNodeResolver.addressOf(weight)); //$NON-NLS-1$
    }

    @Test
    public void testResolvableAddressIsNullForAKindTheGrammarLacks()
    {
        IntegrationService bus = named(MdClassFactory.eINSTANCE.createIntegrationService(), "Bus"); //$NON-NLS-1$
        IntegrationServiceChannel in = named(MdClassFactory.eINSTANCE.createIntegrationServiceChannel(), "In"); //$NON-NLS-1$
        bus.getIntegrationServiceChannels().add(in);
        Catalog goods = named(MdClassFactory.eINSTANCE.createCatalog(), "Goods"); //$NON-NLS-1$
        CatalogForm item = named(MdClassFactory.eINSTANCE.createCatalogForm(), "Item"); //$NON-NLS-1$
        goods.getForms().add(item);

        assertNull(MetadataNodeResolver.resolvableAddressOf(in));
        assertNull(MetadataNodeResolver.resolvableAddressOf(item));
        // The label form still prints every level - that is what the vendor-support refusals name.
        assertEquals("IntegrationService.Bus.IntegrationServiceChannel.In", MetadataNodeResolver.addressOf(in)); //$NON-NLS-1$
        assertEquals("Catalog.Goods.Form.Item", MetadataNodeResolver.addressOf(item)); //$NON-NLS-1$
    }

    @Test
    public void testResolvableAddressIsNullBelowAnOwnerThatIsNotAConfigurationType()
    {
        // A cube's dimensions sit in a collection the grammar knows, but a Cube is not a top-level
        // configuration type: "Cube.Sales.Dimension.Region" would not resolve.
        Cube sales = named(MdClassFactory.eINSTANCE.createCube(), "Sales"); //$NON-NLS-1$
        Dimension region = named(MdClassFactory.eINSTANCE.createDimension(), "Region"); //$NON-NLS-1$
        sales.getDimensions().add(region);

        assertNull(MetadataNodeResolver.resolvableAddressOf(region));
    }

    @Test
    public void testResolvableAddressIsNullForAnUnnamedLevel()
    {
        Catalog unnamed = MdClassFactory.eINSTANCE.createCatalog();
        CatalogAttribute weight = named(MdClassFactory.eINSTANCE.createCatalogAttribute(), "Weight"); //$NON-NLS-1$
        unnamed.getAttributes().add(weight);
        Catalog goods = named(MdClassFactory.eINSTANCE.createCatalog(), "Goods"); //$NON-NLS-1$
        CatalogAttribute anonymous = MdClassFactory.eINSTANCE.createCatalogAttribute();
        goods.getAttributes().add(anonymous);

        assertNull(MetadataNodeResolver.resolvableAddressOf(weight));
        assertNull(MetadataNodeResolver.resolvableAddressOf(anonymous));
        assertNull(MetadataNodeResolver.resolvableAddressOf(null));
    }

    /**
     * The contract: for every subordinate kind a role right can name and the writer can address, the
     * printed address resolves back to the SAME object through {@link MetadataNodeResolver#resolveExisting}.
     */
    @Test
    public void testEverySubordinateKindRoundTripsThroughResolveExisting()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Cfg"); //$NON-NLS-1$
        MdClassFactory f = MdClassFactory.eINSTANCE;
        List<MdObject> targets = new ArrayList<>();

        Catalog catalog = named(f.createCatalog(), "Goods"); //$NON-NLS-1$
        config.getCatalogs().add(catalog);
        targets.add(catalog);
        targets.add(add(catalog.getAttributes(), named(f.createCatalogAttribute(), "Weight"))); //$NON-NLS-1$
        CatalogTabularSection lines = add(catalog.getTabularSections(),
            named(f.createCatalogTabularSection(), "Lines")); //$NON-NLS-1$
        targets.add(lines);
        targets.add(add(lines.getAttributes(), named(f.createTabularSectionAttribute(), "Qty"))); //$NON-NLS-1$
        targets.add(add(catalog.getCommands(), named(f.createCatalogCommand(), "Print"))); //$NON-NLS-1$

        Document document = named(f.createDocument(), "Sale"); //$NON-NLS-1$
        config.getDocuments().add(document);
        targets.add(add(document.getAttributes(), named(f.createDocumentAttribute(), "Customer"))); //$NON-NLS-1$
        DocumentTabularSection goods = add(document.getTabularSections(),
            named(f.createDocumentTabularSection(), "Goods")); //$NON-NLS-1$
        targets.add(goods);
        targets.add(add(goods.getAttributes(), named(f.createTabularSectionAttribute(), "Price"))); //$NON-NLS-1$

        InformationRegister prices = named(f.createInformationRegister(), "Prices"); //$NON-NLS-1$
        config.getInformationRegisters().add(prices);
        targets.add(add(prices.getDimensions(), named(f.createInformationRegisterDimension(), "Product"))); //$NON-NLS-1$
        targets.add(add(prices.getResources(), named(f.createInformationRegisterResource(), "Price"))); //$NON-NLS-1$
        targets.add(add(prices.getAttributes(), named(f.createInformationRegisterAttribute(), "Note"))); //$NON-NLS-1$

        AccumulationRegister stock = named(f.createAccumulationRegister(), "Stock"); //$NON-NLS-1$
        config.getAccumulationRegisters().add(stock);
        targets.add(add(stock.getDimensions(), named(f.createAccumulationRegisterDimension(), "Item"))); //$NON-NLS-1$
        targets.add(add(stock.getResources(), named(f.createAccumulationRegisterResource(), "Qty"))); //$NON-NLS-1$

        AccountingRegister ledger = named(f.createAccountingRegister(), "Ledger"); //$NON-NLS-1$
        config.getAccountingRegisters().add(ledger);
        targets.add(add(ledger.getDimensions(), named(f.createAccountingRegisterDimension(), "Company"))); //$NON-NLS-1$
        targets.add(add(ledger.getResources(), named(f.createAccountingRegisterResource(), "Amount"))); //$NON-NLS-1$

        CalculationRegister payroll = named(f.createCalculationRegister(), "Payroll"); //$NON-NLS-1$
        config.getCalculationRegisters().add(payroll);
        targets.add(add(payroll.getDimensions(), named(f.createCalculationRegisterDimension(), "Employee"))); //$NON-NLS-1$
        targets.add(add(payroll.getRecalculations(), named(f.createRecalculation(), "Again"))); //$NON-NLS-1$

        ChartOfAccounts accounts = named(f.createChartOfAccounts(), "Main"); //$NON-NLS-1$
        config.getChartsOfAccounts().add(accounts);
        targets.add(add(accounts.getAccountingFlags(), named(f.createAccountingFlag(), "Currency"))); //$NON-NLS-1$
        targets.add(add(accounts.getExtDimensionAccountingFlags(),
            named(f.createExtDimensionAccountingFlag(), "Amount"))); //$NON-NLS-1$

        Task task = named(f.createTask(), "Approve"); //$NON-NLS-1$
        config.getTasks().add(task);
        targets.add(add(task.getAddressingAttributes(), named(f.createAddressingAttribute(), "Performer"))); //$NON-NLS-1$

        DocumentJournal journal = named(f.createDocumentJournal(), "All"); //$NON-NLS-1$
        config.getDocumentJournals().add(journal);
        targets.add(add(journal.getColumns(), named(f.createColumn(), "Partner"))); //$NON-NLS-1$

        WebService ws = named(f.createWebService(), "Exchange"); //$NON-NLS-1$
        config.getWebServices().add(ws);
        targets.add(add(ws.getOperations(), named(f.createOperation(), "Ping"))); //$NON-NLS-1$

        HTTPService http = named(f.createHTTPService(), "Api"); //$NON-NLS-1$
        config.getHttpServices().add(http);
        URLTemplate items = add(http.getUrlTemplates(), named(f.createURLTemplate(), "Items")); //$NON-NLS-1$
        Method get = add(items.getMethods(), named(f.createMethod(), "Get")); //$NON-NLS-1$
        targets.add(get);

        for (MdObject target : targets)
        {
            String address = MetadataNodeResolver.resolvableAddressOf(target);
            assertNotNull("no address for " + target.eClass().getName() + " " + target.getName(), address); //$NON-NLS-1$ //$NON-NLS-2$
            MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, address);
            assertNotNull(address + " does not resolve", node); //$NON-NLS-1$
            assertTrue(address + " resolves to another object", node.object == target); //$NON-NLS-1$
        }
        assertEquals("HTTPService.Api.UrlTemplate.Items.Method.Get", //$NON-NLS-1$
            MetadataNodeResolver.resolvableAddressOf(get));
    }

    // ==================== resolveAddress: the one dispatch (#708) ====================
    //
    // The vendor-support guard (yoFallback=true) and adopt_metadata_object (false) both say through
    // this method which object an FQN names. The model: Catalog Goods with Form ItemForm, a Catalog
    // whose Name create_metadata stored with ye, and Sales -> Orders linked like the platform model
    // (the parent only lists the child, the child points back).

    /** A Name stored with ye (U+0415), as create_metadata normalizes it by default. */
    private static final String YE_NAME = fromCp(0x0415, 0x043b, 0x043a, 0x0430);
    /** The same Name spelled with yo (U+0401). */
    private static final String YO_NAME = fromCp(0x0401, 0x043b, 0x043a, 0x0430);
    /** The Russian form kind token (Form). */
    private static final String RU_FORM = fromCp(0x0424, 0x043e, 0x0440, 0x043c, 0x0430);

    private static Configuration addressModel()
    {
        MdClassFactory f = MdClassFactory.eINSTANCE;
        Configuration config = named(f.createConfiguration(), "Cfg"); //$NON-NLS-1$
        Catalog goods = named(f.createCatalog(), "Goods"); //$NON-NLS-1$
        goods.getForms().add(named(f.createCatalogForm(), "ItemForm")); //$NON-NLS-1$
        config.getCatalogs().add(goods);
        config.getCatalogs().add(named(f.createCatalog(), YE_NAME));
        Subsystem sales = named(f.createSubsystem(), "Sales"); //$NON-NLS-1$
        Subsystem orders = named(f.createSubsystem(), "Orders"); //$NON-NLS-1$
        sales.getSubsystems().add(orders);
        orders.setParentSubsystem(sales);
        config.getSubsystems().add(sales);
        return config;
    }

    @Test
    public void testResolveAddressRetriesAYoSpellingOnlyWhenAsked()
    {
        Configuration config = addressModel();
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        Catalog stored = config.getCatalogs().get(1);

        assertSame(stored, MetadataNodeResolver.resolveAddress(scope, "Catalog." + YE_NAME, false)); //$NON-NLS-1$
        assertSame(stored, MetadataNodeResolver.resolveAddress(scope, "Catalog." + YE_NAME, true)); //$NON-NLS-1$
        assertSame("the yo spelling finds the ye-stored Name with the fallback", stored, //$NON-NLS-1$
            MetadataNodeResolver.resolveAddress(scope, "Catalog." + YO_NAME, true)); //$NON-NLS-1$
        assertNull("...and only with it", //$NON-NLS-1$
            MetadataNodeResolver.resolveAddress(scope, "Catalog." + YO_NAME, false)); //$NON-NLS-1$
    }

    @Test
    public void testResolveAddressNamesASubsystemByItsChainNeverByAParent()
    {
        Configuration config = addressModel();
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        Subsystem sales = config.getSubsystems().get(0);
        Subsystem orders = sales.getSubsystems().get(0);

        for (boolean yoFallback : new boolean[] { false, true })
        {
            assertSame(sales, MetadataNodeResolver.resolveAddress(scope, "Subsystem.Sales", yoFallback)); //$NON-NLS-1$
            assertSame(orders, MetadataNodeResolver.resolveAddress(scope, "Subsystem.Sales.Subsystem.Orders", //$NON-NLS-1$
                yoFallback));
            assertNull("a missing leaf must not answer with its parent", //$NON-NLS-1$
                MetadataNodeResolver.resolveAddress(scope, "Subsystem.Sales.Subsystem.Missing", yoFallback)); //$NON-NLS-1$
            assertNull("a bare child names a top-level subsystem only", //$NON-NLS-1$
                MetadataNodeResolver.resolveAddress(scope, "Subsystem.Orders", yoFallback)); //$NON-NLS-1$
        }
        assertNull("the linked base is no root of an external-objects project", //$NON-NLS-1$
            MetadataNodeResolver.resolveAddress(MetadataScopeTestFixtures.externalObjectsWithBase(config),
                "Subsystem.Sales.Subsystem.Orders", true)); //$NON-NLS-1$
    }

    @Test
    public void testResolveAddressTakesAFormInEitherLanguageBeforeTheNodeGrammar()
    {
        Configuration config = addressModel();
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        MdObject itemForm = config.getCatalogs().get(0).getForms().get(0);

        assertSame(itemForm, MetadataNodeResolver.resolveAddress(scope, "Catalog.Goods.Form.ItemForm", false)); //$NON-NLS-1$
        assertSame(itemForm, MetadataNodeResolver.resolveAddress(scope, "Catalog.Goods." + RU_FORM + ".ItemForm", //$NON-NLS-1$ //$NON-NLS-2$
            true));
        assertNull(MetadataNodeResolver.resolveAddress(scope, "Catalog.Goods.Form.Missing", true)); //$NON-NLS-1$
        assertSame("anything else goes to the node grammar", config.getCatalogs().get(0), //$NON-NLS-1$
            MetadataNodeResolver.resolveAddress(scope, "Catalog.Goods", false)); //$NON-NLS-1$
        assertNull(MetadataNodeResolver.resolveAddress(scope, null, true));
    }

    private static <T extends MdObject> T named(T object, String name)
    {
        object.setName(name);
        return object;
    }

    private static <T> T add(List<? super T> list, T element)
    {
        list.add(element);
        return element;
    }
}
