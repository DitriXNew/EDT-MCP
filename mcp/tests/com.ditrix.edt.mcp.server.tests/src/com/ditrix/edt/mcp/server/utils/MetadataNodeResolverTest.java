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
}
