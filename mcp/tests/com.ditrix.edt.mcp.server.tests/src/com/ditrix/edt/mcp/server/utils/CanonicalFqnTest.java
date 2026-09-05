/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

/**
 * Tests {@link MetadataTypeUtils#toCanonicalEnglishFqn(String)} - the one case-preserving,
 * all-segments FQN canonicalizer, and the reason it had to exist beside two neighbours that look as
 * if they already did the job.
 * <p>
 * Half of this file is therefore a PIN on those neighbours rather than on the new method: the
 * comparison engine matches scope symlinks verbatim and monolingually, so
 * {@link MetadataTypeUtils#normalizeFqn(String)} (leading token only) and
 * {@link MetadataTypeUtils#getAllFqnVariants(String)} (every token, but lowercased) each produce a
 * string the engine silently matches nothing against. If someone later "simplifies" the canonicalizer
 * into either of them, those pins go red and say why.
 * <p>
 * Every Cyrillic literal is written as a Unicode code-point escape so this source stays pure ASCII -
 * the same rule {@code MetadataTypeUtils} follows for its own token tables - with the ASCII
 * transliteration spelled out in the constant's name and comment. (The escape sequence itself is
 * never spelled out in prose here: Java expands those escapes inside COMMENTS too, so writing one in
 * a javadoc line is a real way to break a build.)
 */
public class CanonicalFqnTest
{
    /** Spravochnik - the Russian type token for Catalog. */
    private static final String SPRAVOCHNIK = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a"; //$NON-NLS-1$

    /** Spravochniki - its PLURAL spelling, also accepted on input. */
    private static final String SPRAVOCHNIKI =
        "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a\u0438"; //$NON-NLS-1$

    /** Tovary - a programmatic object Name, which must survive byte-identical. */
    private static final String TOVARY = "\u0422\u043e\u0432\u0430\u0440\u044b"; //$NON-NLS-1$

    /** Forma - the Russian NESTED kind token for Form; the segment normalizeFqn never reaches. */
    private static final String FORMA = "\u0424\u043e\u0440\u043c\u0430"; //$NON-NLS-1$

    /** FormaElementa - a form's programmatic Name. */
    private static final String FORMA_ELEMENTA =
        "\u0424\u043e\u0440\u043c\u0430\u042d\u043b\u0435\u043c\u0435\u043d\u0442\u0430"; //$NON-NLS-1$

    /** Rekvizit - the Russian nested kind token for Attribute. */
    private static final String REKVIZIT = "\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442"; //$NON-NLS-1$

    /** Ves - an attribute's programmatic Name. */
    private static final String VES = "\u0412\u0435\u0441"; //$NON-NLS-1$

    /** mOiTovary - a Name in deliberately ragged case. */
    private static final String MIXED_CASE_NAME =
        "\u043c\u041e\u0438\u0422\u043e\u0432\u0430\u0440\u044b"; //$NON-NLS-1$

    /** fOrmaElementa - a Name in ragged case that also starts like a kind token. */
    private static final String MIXED_CASE_FORM_NAME =
        "\u0444\u041e\u0440\u043c\u0430\u042d\u043b\u0435\u043c\u0435\u043d\u0442\u0430"; //$NON-NLS-1$

    // ==================== the headline case ====================

    @Test
    public void testRussianNestedFqnBecomesAllEnglishStructuralSegments()
    {
        String russian = SPRAVOCHNIK + '.' + TOVARY + '.' + FORMA + '.' + FORMA_ELEMENTA;

        assertEquals("every STRUCTURAL segment must be English and every Name kept verbatim", //$NON-NLS-1$
            "Catalog." + TOVARY + ".Form." + FORMA_ELEMENTA, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeUtils.toCanonicalEnglishFqn(russian));
    }

    @Test
    public void testDeepAddressTranslatesEveryStructuralSegment()
    {
        String russian = SPRAVOCHNIK + '.' + TOVARY + '.' + REKVIZIT + '.' + VES;

        assertEquals("a nested kind two segments in must translate as well", //$NON-NLS-1$
            "Catalog." + TOVARY + ".Attribute." + VES, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeUtils.toCanonicalEnglishFqn(russian));
    }

    @Test
    public void testEnglishFqnRoundTripsByteIdentical()
    {
        String english = "Catalog." + TOVARY + ".Form." + FORMA_ELEMENTA; //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("an address already in canonical form must come back unchanged", english, //$NON-NLS-1$
            MetadataTypeUtils.toCanonicalEnglishFqn(english));
        assertEquals("canonicalizing twice must change nothing further", english, //$NON-NLS-1$
            MetadataTypeUtils.toCanonicalEnglishFqn(MetadataTypeUtils.toCanonicalEnglishFqn(english)));
    }

    // ==================== case ====================

    @Test
    public void testNameCaseIsPreservedWhileStructuralTokensAreCanonicalized()
    {
        assertEquals("Latin Names keep their exact case; the type/kind tokens take canonical spelling", //$NON-NLS-1$
            "Catalog.mYgOoDs.Form.iTeMfOrM", //$NON-NLS-1$
            MetadataTypeUtils.toCanonicalEnglishFqn("cAtAlOgS.mYgOoDs.fOrM.iTeMfOrM")); //$NON-NLS-1$
    }

    @Test
    public void testCyrillicNameCaseIsPreserved()
    {
        String russian = SPRAVOCHNIK + '.' + MIXED_CASE_NAME + '.' + FORMA + '.' + MIXED_CASE_FORM_NAME;

        assertEquals("a Cyrillic Name in ragged case must survive byte-identical", //$NON-NLS-1$
            "Catalog." + MIXED_CASE_NAME + ".Form." + MIXED_CASE_FORM_NAME, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeUtils.toCanonicalEnglishFqn(russian));
    }

    @Test
    public void testANameThatSpellsAKindTokenIsNotTranslated()
    {
        // The strongest form of "Names are verbatim": an object literally NAMED "Forma" sits on an odd
        // index and must be copied, while the kind token on the even index beside it is translated.
        String russian = SPRAVOCHNIK + '.' + FORMA + '.' + FORMA + '.' + FORMA;

        assertEquals("only the EVEN (structural) segments may be translated", //$NON-NLS-1$
            "Catalog." + FORMA + ".Form." + FORMA, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeUtils.toCanonicalEnglishFqn(russian));
    }

    // ==================== plural input ====================

    @Test
    public void testPluralTypeTokenIsCanonicalizedToTheSingular()
    {
        assertEquals("Catalog.Products", MetadataTypeUtils.toCanonicalEnglishFqn("Catalogs.Products")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog." + TOVARY, //$NON-NLS-1$
            MetadataTypeUtils.toCanonicalEnglishFqn(SPRAVOCHNIKI + '.' + TOVARY));
    }

    // ==================== unknown and degenerate input ====================

    @Test
    public void testUnknownSegmentsAreCopiedVerbatim()
    {
        assertEquals("an unknown leading token must not mangle the rest of the address", //$NON-NLS-1$
            "NoSuchType.Products", MetadataTypeUtils.toCanonicalEnglishFqn("NoSuchType.Products")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("an unknown NESTED token is copied, and the known ones still translate", //$NON-NLS-1$
            "Catalog." + TOVARY + ".NoSuchKind.X", //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeUtils.toCanonicalEnglishFqn(SPRAVOCHNIK + '.' + TOVARY + ".NoSuchKind.X")); //$NON-NLS-1$
    }

    @Test
    public void testNullEmptyAndSingleTokenAreReturnedUnchanged()
    {
        assertNull(MetadataTypeUtils.toCanonicalEnglishFqn(null));
        assertEquals("", MetadataTypeUtils.toCanonicalEnglishFqn("")); //$NON-NLS-1$ //$NON-NLS-2$

        // A bare word has no Type.Name shape, so it must not be read as a type token - the same guard
        // getAllFqnVariants applies. The identity assertion is deliberate: nothing is rebuilt.
        assertSame(SPRAVOCHNIK, MetadataTypeUtils.toCanonicalEnglishFqn(SPRAVOCHNIK));
        String leadingDot = '.' + SPRAVOCHNIK + '.' + TOVARY;
        assertSame("a leading dot is not a full address either", leadingDot, //$NON-NLS-1$
            MetadataTypeUtils.toCanonicalEnglishFqn(leadingDot));
    }

    // ==================== why the method exists: pins on the two neighbours ====================

    @Test
    public void testNormalizeFqnTranslatesTheLEADINGSegmentOnly()
    {
        String russian = SPRAVOCHNIK + '.' + TOVARY + '.' + FORMA + '.' + FORMA_ELEMENTA;

        // This is the defect the canonicalizer exists for. normalizeFqn is not wrong - it is the right
        // tool for a Type.Name address - but a nested address keeps its Russian kind token, and the
        // comparison engine has no bilingual branch, so the resulting symlink matches nothing at all.
        assertEquals("normalizeFqn must still leave the nested kind token untranslated", //$NON-NLS-1$
            "Catalog." + TOVARY + '.' + FORMA + '.' + FORMA_ELEMENTA, //$NON-NLS-1$
            MetadataTypeUtils.normalizeFqn(russian));
        assertFalse("if these two ever agree on a nested Russian address, one of them changed meaning", //$NON-NLS-1$
            MetadataTypeUtils.normalizeFqn(russian).equals(MetadataTypeUtils.toCanonicalEnglishFqn(russian)));
    }

    @Test
    public void testGetAllFqnVariantsLowercasesAndSoCannotProduceASymlink()
    {
        String english = "Catalog." + TOVARY + ".Form." + FORMA_ELEMENTA; //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> variants = MetadataTypeUtils.getAllFqnVariants(english);

        // The second neighbour DOES translate every segment - and lowercases the result, which is right
        // for case-insensitive marker matching and fatal for a symlink, compared verbatim.
        assertFalse("getAllFqnVariants must not be usable as a symlink source", variants.contains(english)); //$NON-NLS-1$
        assertTrue("it lowercases everything, Names included", //$NON-NLS-1$
            variants.contains(english.toLowerCase()));
        assertEquals("the canonicalizer keeps the case getAllFqnVariants throws away", english, //$NON-NLS-1$
            MetadataTypeUtils.toCanonicalEnglishFqn(english));
    }
}
