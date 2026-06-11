/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
            MetadataNodeResolver.resolveExistingWithYoFallback(null, "Catalog.X"); //$NON-NLS-1$
        assertNull(r.node);
        assertEquals("Catalog.X", r.fqn); //$NON-NLS-1$
        assertFalse(r.yoFallback);
    }
}
