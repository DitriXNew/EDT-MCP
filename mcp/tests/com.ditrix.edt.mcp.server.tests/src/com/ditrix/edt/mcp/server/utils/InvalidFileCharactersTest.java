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

import org.junit.Test;

/**
 * Covers {@link InvalidFileCharacters}: every character the 1C standard
 * {@code InvalidCharacterInFile} forbids is replaced, nothing else is touched, and the report
 * says what happened (#161).
 * <p>
 * Every forbidden character is written here as a Unicode escape, like in the class under test -
 * a literal em dash and a literal minus are indistinguishable in a diff, so a test written with
 * literals could assert the wrong character and still read as correct.
 * </p>
 */
public class InvalidFileCharactersTest
{
    /** The seven forbidden characters, in the order the class declares them. */
    private static final char[] FORBIDDEN = {'\u2013', '\u2012', '\u2014', '\u2015', '\u2212',
        '\u00AD', '\u00A0'};

    @Test
    public void everyDashShapedCharacterBecomesAnAsciiHyphen()
    {
        // The five dash-shaped ones all collapse to U+002D. Asserted per character rather than in
        // one string so a mapping that silently dropped ONE of them cannot hide behind the others.
        for (char c : new char[] {'\u2013', '\u2012', '\u2014', '\u2015', '\u2212'})
        {
            InvalidFileCharacters.Result r = InvalidFileCharacters.normalize("a" + c + "b"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("U+" + Integer.toHexString(c) + " must become an ASCII hyphen", //$NON-NLS-1$ //$NON-NLS-2$
                "a-b", r.text()); //$NON-NLS-1$
            assertTrue(r.changed());
        }
    }

    @Test
    public void aNoBreakSpaceBecomesASpaceAndASoftHyphenIsDropped()
    {
        // These two differ from the dashes: NBSP has a visible width to preserve, the soft hyphen
        // has none - replacing it with anything printable would ALTER the text, so it is removed.
        assertEquals("a b", InvalidFileCharacters.normalize("a\u00A0b").text()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ab", InvalidFileCharacters.normalize("a\u00ADb").text()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theAsciiTwinsAreLeftAlone()
    {
        // The whole risk of this class is over-reach: an ASCII hyphen and an ASCII space look the
        // same as two of the characters it replaces and must survive untouched, as must every
        // other character - including the Cyrillic text a BSL module is full of.
        String bsl = "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 A(x) \u042D\u043A\u0441\u043F\u043E\u0440\u0442 - x = 1 - 2; \u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B"; //$NON-NLS-1$
        InvalidFileCharacters.Result r = InvalidFileCharacters.normalize(bsl);
        assertFalse("clean BSL must report no change", r.changed()); //$NON-NLS-1$
        assertSame("clean text must come back as the same instance", bsl, r.text()); //$NON-NLS-1$
        assertNull("nothing replaced means no summary", r.summary()); //$NON-NLS-1$
        assertFalse(InvalidFileCharacters.contains(bsl));
    }

    @Test
    public void containsAgreesWithNormalizeOnEveryForbiddenCharacter()
    {
        // contains() is the cheap probe; if it ever disagrees with what normalize() acts on, a
        // caller that guards on it would skip a text that does need fixing.
        for (char c : FORBIDDEN)
        {
            String text = "x" + c + "y"; //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("contains must see U+" + Integer.toHexString(c), //$NON-NLS-1$
                InvalidFileCharacters.contains(text));
            assertTrue("normalize must act on U+" + Integer.toHexString(c), //$NON-NLS-1$
                InvalidFileCharacters.normalize(text).changed());
        }
    }

    @Test
    public void theSummaryNamesEachCharacterWithItsCount()
    {
        // Two of one kind and one of another, interleaved, so the summary cannot be produced by
        // simply echoing the encounter order.
        InvalidFileCharacters.Result r =
            InvalidFileCharacters.normalize("a\u2014b\u00A0c\u2014d"); //$NON-NLS-1$
        assertEquals("a-b c-d", r.text()); //$NON-NLS-1$
        assertEquals("EM DASH (U+2014) x2, NO-BREAK SPACE (U+00A0) x1", r.summary()); //$NON-NLS-1$
    }

    @Test
    public void theSummaryIsStableWhateverOrderTheCharactersAppearIn()
    {
        // Declaration order, not encounter order: the same multiset must always report the same
        // string, or a test (or a caller diffing two writes) would see spurious differences.
        assertEquals(
            InvalidFileCharacters.normalize("\u2014\u00A0").summary(), //$NON-NLS-1$
            InvalidFileCharacters.normalize("\u00A0\u2014").summary()); //$NON-NLS-1$
    }

    @Test
    public void nullAndEmptyAreHandled()
    {
        assertFalse(InvalidFileCharacters.contains(null));
        assertNull(InvalidFileCharacters.normalize(null).text());
        assertFalse(InvalidFileCharacters.normalize(null).changed());
        assertEquals("", InvalidFileCharacters.normalize("").text()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void unchangedReportsNoReplacementAndKeepsTheText()
    {
        // The opt-out branch of write_module_source: same result type, text as supplied.
        String dirty = "a\u2014b"; //$NON-NLS-1$
        InvalidFileCharacters.Result r = InvalidFileCharacters.unchanged(dirty);
        assertSame(dirty, r.text());
        assertFalse(r.changed());
        assertNull(r.summary());
    }
}
