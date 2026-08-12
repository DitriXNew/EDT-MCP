/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.junit.Test;

import com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;

/**
 * Tests the {@code disableIndices} half of the {@code rename_metadata_object} contract: WHICH change
 * points a requested index may switch off (#393), WHAT the executed report then claims about it (#394 -
 * the change points the request really left switched off, not the size of the request), and how the
 * entries that produced no skip at all are accounted for: an index that matched nothing, an index
 * naming a required point, or a token that never parsed as a number (#401).
 * <p>
 * Everything here is driven through {@code applyDisableIndices} - the method {@code performRename}
 * actually calls - rather than through the leaf walker underneath it. That is deliberate: the decision
 * under test lives in the branch that chooses whether to disable a leaf, so a test that poked the
 * walker directly would survive removing the guard entirely, and a guard whose removal no test notices
 * is not a guard. The reporting assertions likewise go through the real
 * {@code renderExecutedReport}, on the outcome the real walk produced.
 * <p>
 * Reached by REFLECTION: {@code applyDisableIndices} and {@code renderExecutedReport} are private, and
 * their only public entry ({@code rename}) needs a live EDT project, an {@code IMdRefactoringService}
 * and a BM transaction, none of which exist headlessly. The cost is stated rather than hidden -
 * RENAMING either method breaks these tests with a {@code NoSuchMethodException} instead of a compile
 * error.
 * <p>
 * The refactoring items are Mockito doubles because {@code isOptional()} is exactly the input under
 * test and no fixture reaches every combination of it: on {@code TestConfiguration} every NATIVE item
 * arrives optional, so the required-native case - the one #393 is about - has no live reproduction at
 * all and can only be exercised here. The change trees are bare LTK ({@link NullChange} /
 * {@link CompositeChange}), the same headless technique the numbering tests use.
 */
public class MetadataRenameDisableIndicesTest
{
    // ==================== #393: a required change point is never switched off ====================

    /**
     * The promise the preview footer and the guide both make - "required ones are always applied" -
     * enforced for a NATIVE item. Before #393 {@code applyDisableToChange} was reached unconditionally
     * and this leaf came back disabled; {@code isOptional()} guarded only the item's own checkbox.
     */
    @Test
    public void testRequiredNativeItemKeepsItsLeavesEnabled() throws Exception
    {
        Change leaf = new NullChange("required-edit"); //$NON-NLS-1$
        IRefactoringItem item = nativeItem(leaf, false);

        Object outcome = applyDisableIndices(refactoring(item), request("0"));

        assertTrue("a REQUIRED change point must stay enabled even when its index is requested", //$NON-NLS-1$
            leaf.isEnabled());
        assertEquals(0, disabledCount(outcome));
        assertEquals("[0]", notSkippableIndices(outcome).toString()); //$NON-NLS-1$
    }

    /**
     * The positive control for the test above: with the SAME shape and only {@code isOptional()}
     * flipped, the leaf must go off. Without this, "never disable anything" would pass #393.
     */
    @Test
    public void testOptionalNativeItemDisablesTheRequestedLeaf() throws Exception
    {
        Change leaf = new NullChange("optional-edit"); //$NON-NLS-1$
        IRefactoringItem item = nativeItem(leaf, true);

        Object outcome = applyDisableIndices(refactoring(item), request("0"));

        assertFalse("an OPTIONAL change point must still be skippable", leaf.isEnabled()); //$NON-NLS-1$
        assertEquals(1, disabledCount(outcome));
        assertTrue(notSkippableIndices(outcome).isEmpty());
    }

    /**
     * The #388 trap, re-armed for the new branch: a required item must consume its indices exactly as
     * it did before, or every index after it addresses the wrong leaf. Here the required item owns
     * {@code #0} and {@code #1}, so the optional leaf that follows is {@code #2} - and asking for
     * {@code #2} must switch off THAT leaf, not the one an under-counting walk would land on.
     */
    @Test
    public void testRequiredItemStillConsumesItsIndicesSoLaterOnesStayAligned() throws Exception
    {
        CompositeChange required = new CompositeChange("required"); //$NON-NLS-1$
        Change requiredA = new NullChange("required-a"); //$NON-NLS-1$
        Change requiredB = new NullChange("required-b"); //$NON-NLS-1$
        required.add(requiredA);
        required.add(requiredB);
        Change optional = new NullChange("optional"); //$NON-NLS-1$

        Object outcome = applyDisableIndices(
            refactoring(nativeItem(required, false), nativeItem(optional, true)), request("2"));

        assertFalse("index #2 must reach the leaf AFTER the required item's two leaves", //$NON-NLS-1$
            optional.isEnabled());
        assertTrue(requiredA.isEnabled());
        assertTrue(requiredB.isEnabled());
        assertEquals(1, disabledCount(outcome));
    }

    /**
     * A plain (non-native) rename item owns no leaf {@link Change} to switch off, so it is not
     * skippable whatever it reports for {@code isOptional()}. It already behaved that way; what is new
     * is that asking for its index is now ACCOUNTED for instead of vanishing.
     */
    @Test
    public void testPlainItemIsReportedAsNotSkippable() throws Exception
    {
        Object outcome = applyDisableIndices(refactoring(mock(IRefactoringItem.class)), request("0"));

        assertEquals(0, disabledCount(outcome));
        assertEquals("[0]", notSkippableIndices(outcome).toString()); //$NON-NLS-1$
    }

    // ============ #393: an unrelated index must not silently drop a whole item ============

    /**
     * {@code applyDisableIndices} walks EVERY item, and an optional item whose leaves are already all
     * disabled looks "completely disabled" to the checkbox rule - so before this fix any request at all
     * unchecked it, including one that never named a single one of its indices. An empty change tree
     * counts as completely disabled too, which makes the same request drop an item that had nothing
     * disabled about it.
     */
    @Test
    public void testUnrelatedIndexDoesNotUncheckAnAlreadyDisabledOptionalItem() throws Exception
    {
        Change leaf = new NullChange("already-off"); //$NON-NLS-1$
        leaf.setEnabled(false);
        INativeChangeRefactoringItem item = nativeItem(leaf, true);

        applyDisableIndices(refactoring(item), request("99"));

        verify(item, never()).setChecked(false);
    }

    /** The positive control: when the request DID empty the item, unchecking it is still right. */
    @Test
    public void testRequestThatDisablesEveryLeafStillUnchecksTheItem() throws Exception
    {
        Change leaf = new NullChange("last-one"); //$NON-NLS-1$
        INativeChangeRefactoringItem item = nativeItem(leaf, true);

        applyDisableIndices(refactoring(item), request("0"));

        verify(item).setChecked(false);
    }

    /**
     * The boundary between the two tests above: the leaf is already off AND the request names it. The
     * criterion is that the request NAMED the leaf, not that the flag changed value, so this counts as
     * a skip and the emptied item is unchecked - the caller who names every leaf of an item means the
     * item, whatever those leaves happened to be set to beforehand. Pinned because the opposite reading
     * ("only count a state transition") is the natural one to drift into, and it is silently different.
     */
    @Test
    public void testNamingAnAlreadyDisabledLeafStillCountsAsASkip() throws Exception
    {
        Change leaf = new NullChange("already-off"); //$NON-NLS-1$
        leaf.setEnabled(false);
        INativeChangeRefactoringItem item = nativeItem(leaf, true);

        Object outcome = applyDisableIndices(refactoring(item), request("0"));

        assertEquals(1, disabledCount(outcome));
        assertTrue(notSkippableIndices(outcome).isEmpty());
        verify(item).setChecked(false);
    }

    // ==================== #394: the report states the REAL number ====================

    /**
     * The live reproduction from #394, headless: two change points exist, {@code #99} is requested, and
     * the old report announced "disabledCount: 1" plus "1 change point(s) were skipped as requested"
     * while nothing whatsoever had been skipped.
     */
    @Test
    public void testUnknownIndexIsNotCountedAsASkip() throws Exception
    {
        Change leaf = new NullChange("edit"); //$NON-NLS-1$
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, true)), request("99"));

        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertTrue(leaf.isEnabled());
        assertTrue(report.contains("disabledCount: 0")); //$NON-NLS-1$
        assertFalse("nothing was skipped, so the report must not claim a skip", //$NON-NLS-1$
            report.contains("were skipped as requested")); //$NON-NLS-1$
        assertTrue(report.contains("unknownIndices: [99]")); //$NON-NLS-1$
    }

    /**
     * The other half of #394, and the case measured live on the stand: the index names a REAL change
     * point that cannot be skipped. The caller has to be able to tell "I protected it" from "it was
     * applied anyway", which is precisely what a bare count of the request cannot express.
     */
    @Test
    public void testRequiredIndexIsReportedAsAppliedNotSkipped() throws Exception
    {
        Change leaf = new NullChange("mandatory"); //$NON-NLS-1$
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, false)), request("0"));

        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertTrue(report.contains("disabledCount: 0")); //$NON-NLS-1$
        assertTrue(report.contains("notSkippableIndices: [0]")); //$NON-NLS-1$
        assertTrue(report.contains("could NOT be skipped and were left in the rename")); //$NON-NLS-1$
        assertFalse(report.contains("were skipped as requested")); //$NON-NLS-1$
    }

    /**
     * The report is written from what the DISABLE pass decided, and that runs before {@code perform()};
     * so the mandatory-index note must not assert that the change point succeeded. A refactoring that
     * fails is reported under {@code errors}, and the two must not contradict each other in one report.
     */
    @Test
    public void testMandatoryNoteDoesNotClaimSuccessWhenTheRefactoringFailed() throws Exception
    {
        Change leaf = new NullChange("mandatory"); //$NON-NLS-1$
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, false)), request("0"));

        String report = renderExecutedReport(outcome, List.of(), List.of("Rename: boom")); //$NON-NLS-1$

        assertTrue(report.contains("errors: 1")); //$NON-NLS-1$
        assertTrue(report.contains("notSkippableIndices: [0]")); //$NON-NLS-1$
        // Case-insensitive on purpose: the claim is about the WORD, and pinning one casing would let
        // the same overclaim back in as "applied" the moment someone rewrote the sentence.
        assertFalse("the report must not claim the change point was applied - perform() had not run " //$NON-NLS-1$
            + "when this was decided, and here it went on to fail", //$NON-NLS-1$
            report.toLowerCase(java.util.Locale.ROOT).contains("applied")); //$NON-NLS-1$
    }

    /** A real skip still reports as one, and reports the COUNT rather than the request's size. */
    @Test
    public void testActualSkipsAreCountedAndIneffectiveOnesListedSeparately() throws Exception
    {
        CompositeChange root = new CompositeChange("root"); //$NON-NLS-1$
        Change first = new NullChange("first"); //$NON-NLS-1$
        Change second = new NullChange("second"); //$NON-NLS-1$
        root.add(first);
        root.add(second);

        // Three requested: #0 lands, #1 lands, #99 does not exist.
        Object outcome = applyDisableIndices(refactoring(nativeItem(root, true)), request("0,1,99"));
        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertFalse(first.isEnabled());
        assertFalse(second.isEnabled());
        assertTrue(report.contains("disabledCount: 2")); //$NON-NLS-1$
        assertTrue(report.contains("_2 change point(s) were skipped as requested._")); //$NON-NLS-1$
        assertTrue(report.contains("unknownIndices: [99]")); //$NON-NLS-1$
        assertFalse("nothing here is required, so that bucket must stay off the report", //$NON-NLS-1$
            report.contains("notSkippableIndices")); //$NON-NLS-1$
    }

    /** With no request at all the report keeps its old shape - no counts, no extra keys, no prose. */
    @Test
    public void testEmptyRequestRendersNoSkipSectionsAtAll() throws Exception
    {
        Object outcome = newOutcome(request(null));

        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertTrue(report.contains("disabledCount: 0")); //$NON-NLS-1$
        assertFalse(report.contains("notSkippableIndices")); //$NON-NLS-1$
        assertFalse(report.contains("unknownIndices")); //$NON-NLS-1$
        assertFalse(report.contains("skipped")); //$NON-NLS-1$
    }

    /** Indices are rendered as a sorted YAML flow sequence, so one value parses like several do. */
    @Test
    public void testIneffectiveIndicesRenderSortedAsAYamlSequence() throws Exception
    {
        Object outcome = applyDisableIndices(refactoring(nativeItem(new NullChange("x"), true)), //$NON-NLS-1$
            request("99,7,42"));

        String report = renderExecutedReport(outcome, List.of(), List.of());

        assertTrue(report.contains("unknownIndices: [7, 42, 99]")); //$NON-NLS-1$
    }

    // ============ #401: a token that never became an index is still accounted for ============

    /**
     * The sharpest statement of the gap, and the one that fails on the old behaviour: a call that asked
     * to skip something must not answer IDENTICALLY to a call that asked for nothing. Before the tokens
     * were carried through the parse, {@code disableIndices: "abc"} produced a report byte for byte
     * equal to omitting the argument - the caller's request had left no trace anywhere.
     */
    @Test
    public void testAnUnparsableRequestIsDistinguishableFromNoRequestAtAll() throws Exception
    {
        String askedForNothing = renderExecutedReport(newOutcome(request(null)),
            List.of("TestConfiguration"), List.of()); //$NON-NLS-1$
        String askedWithGarbage = renderExecutedReport(newOutcome(request("abc")), //$NON-NLS-1$
            List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertNotEquals("a request the tool could not parse must leave a trace in the report", //$NON-NLS-1$
            askedForNothing, askedWithGarbage);
        assertTrue(askedWithGarbage.contains("unparsedTokens: [\"abc\"]")); //$NON-NLS-1$
        assertTrue(askedWithGarbage.contains("are not whole numbers and were ignored")); //$NON-NLS-1$
    }

    /** A stray token must not hide behind the indices that DID work. */
    @Test
    public void testUnparsedTokenIsReportedAlongsideASuccessfulSkip() throws Exception
    {
        Change leaf = new NullChange("edit"); //$NON-NLS-1$
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, true)), request("0,abc")); //$NON-NLS-1$

        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertFalse(leaf.isEnabled());
        assertTrue(report.contains("disabledCount: 1")); //$NON-NLS-1$
        assertTrue(report.contains("unparsedTokens: [\"abc\"]")); //$NON-NLS-1$
    }

    /** Separator noise is punctuation, not a typo'd index - reporting it would be noise. */
    @Test
    public void testEmptyEntriesAreNotReportedAsUnparsedTokens()
    {
        DisableRequest parsed = request("1,,2,"); //$NON-NLS-1$

        assertEquals(Set.of(Integer.valueOf(1), Integer.valueOf(2)), parsed.indices());
        assertTrue("a stray comma is formatting, not a token the caller meant", //$NON-NLS-1$
            parsed.unparsedTokens().isEmpty());
    }

    /**
     * The token is echoed back into the report's YAML front matter, so a control character comes back
     * as an escape rather than as itself, and the request does not get to decide how long the answer
     * is.
     */
    @Test
    public void testEchoedTokensAreBoundedAndControlCharactersEscaped()
    {
        DisableRequest parsed = request("a\u0007b," + "x".repeat(80)); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> tokens = parsed.unparsedTokens();
        assertEquals(2, tokens.size());
        assertEquals("a\\u0007b", tokens.get(0)); //$NON-NLS-1$
        assertTrue("a long token must be truncated, not echoed whole", //$NON-NLS-1$
            tokens.get(1).length() < 80);
        assertTrue(tokens.get(1).endsWith("...")); //$NON-NLS-1$
    }

    /**
     * Many tokens are counted rather than all named - the request must not size the answer. The overflow
     * is a SEPARATE key: a "(N total)" suffix after the closing bracket would make the front matter
     * unparsable as YAML, which is the one part of this report meant to be read by machine.
     */
    @Test
    public void testTokenListIsCappedAndTheOverflowIsItsOwnYamlKey() throws Exception
    {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 30; i++)
        {
            raw.append(raw.length() == 0 ? "" : ",").append("t").append(i); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        String report = renderExecutedReport(newOutcome(request(raw.toString())), List.of(), List.of());

        assertTrue(report.contains("unparsedTokenCount: 30")); //$NON-NLS-1$
        // Exactly the first 20, not merely "fewer than all": a cap of 0 or 29 would satisfy a test
        // that only checked the last one was missing.
        assertTrue("the 20th token must still be named", report.contains("\"t19\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the 21st must not be", report.contains("\"t20\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(20, frontMatterValue(report, "unparsedTokens").split(",").length); //$NON-NLS-1$
        assertTrue("the sequence must END at its bracket, with nothing trailing on the line", //$NON-NLS-1$
            frontMatterValue(report, "unparsedTokens").endsWith("]")); //$NON-NLS-1$ //$NON-NLS-2$
        for (String line : frontMatter(report).split("\n")) //$NON-NLS-1$
        {
            assertTrue("every front-matter line must stay a 'key: value' pair, got: " + line, //$NON-NLS-1$
                line.matches("^[A-Za-z][A-Za-z0-9]*: .*$")); //$NON-NLS-1$
        }
    }

    /**
     * A token made ONLY of control characters used to vanish: {@code trim()} strips every character
     * <= U+0020, so it came back empty and was dropped as separator noise - the exact silence this is
     * meant to end, surviving in the one shape nobody would think to try.
     */
    @Test
    public void testATokenOfOnlyControlCharactersIsStillReported() throws Exception
    {
        DisableRequest parsed = request("\u0007"); //$NON-NLS-1$

        assertEquals(1, parsed.unparsedTokens().size());
        assertEquals("\\u0007", parsed.unparsedTokens().get(0)); //$NON-NLS-1$
        String report = renderExecutedReport(newOutcome(parsed), List.of(), List.of());
        // The backslash is doubled by the YAML quoting on top, so the scalar reads back as the
        // literal text \u0007 - which is what the caller should see.
        assertTrue(report.contains("unparsedTokens: [\"\\\\u0007\"]")); //$NON-NLS-1$
    }

    /**
     * Truncation counts CODE POINTS. Cutting at a fixed number of UTF-16 units can split a surrogate
     * pair and leave an unpaired surrogate - not legal YAML content - in the report.
     */
    @Test
    public void testAnAstralCharacterIsEchoedIntactRatherThanAsTwoReplacements()
    {
        String emoji = new String(Character.toChars(0x1F600));

        String echoed = request("ab" + emoji).unparsedTokens().get(0); //$NON-NLS-1$

        // A char-by-char walk sees the pair as two lone surrogates, neither of which is safe to echo,
        // and hands back "ab??" - the caller's token unrecognisable in the report meant to identify it.
        assertEquals("ab" + emoji, echoed); //$NON-NLS-1$

        // And the pair must survive the CAP too: the astral character sits exactly ON the 40th code
        // point, where a cut counting UTF-16 units instead would land between its halves.
        String atTheCap = request("x".repeat(39) + emoji + "tail").unparsedTokens().get(0); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("x".repeat(39) + emoji + "...", atTheCap); //$NON-NLS-1$ //$NON-NLS-2$
        // And whatever survives, no HALF of a pair may: an unpaired surrogate is not legal YAML content.
        for (int i = 0; i < echoed.length(); i++)
        {
            char c = echoed.charAt(i);
            if (Character.isHighSurrogate(c))
            {
                assertTrue("a high surrogate must keep its low half, at " + i, //$NON-NLS-1$
                    i + 1 < echoed.length() && Character.isLowSurrogate(echoed.charAt(i + 1)));
            }
            if (Character.isLowSurrogate(c))
            {
                assertTrue("a low surrogate must keep its high half, at " + i, //$NON-NLS-1$
                    i > 0 && Character.isHighSurrogate(echoed.charAt(i - 1)));
            }
        }
    }

    /**
     * Everything that is not legible is ESCAPED rather than printed, and the reasons differ - which is
     * exactly why the render does not enumerate them. A line separator splits the YAML front
     * matter and the prose; a bidi override is invisible and reverses the reading order of what follows,
     * so the sentence explaining the mistake can be made to read as something else (the "Trojan Source"
     * trick, against a report an agent acts on); a zero-width or a non-character breaks the container or
     * is simply illegal in it.
     * <p>
     * Two of these arrived one review round apart, each as "also reject THIS one". The list stopped
     * being extended at that point: the render now names what may be printed - letters, marks, digits,
     * punctuation, symbols, and the plain space - so the members below are escaped by DEFAULT, together
     * with the ones nobody has reported yet (a no-break space, a private-use code point, an unassigned
     * one). That is the difference between a guard that is patched and a guard that is closed.
     */
    @Test
    public void testNothingButLegibleCharactersIsPrintedAsItself()
    {
        // Built from code points, never pasted: an invisible character in the source of the test that
        // guards against invisible characters is unreviewable, and raw non-ASCII literals are the
        // corruption risk the repo escapes for anyway.
        int[] refused = {
            0x2028, // LINE SEPARATOR - splits the YAML scalar and the Markdown sentence
            0x2029, // PARAGRAPH SEPARATOR
            0x202E, // RIGHT-TO-LEFT OVERRIDE - reorders the text after it
            0x202A, // LEFT-TO-RIGHT EMBEDDING
            0x2066, // LEFT-TO-RIGHT ISOLATE
            0x200B, // ZERO WIDTH SPACE
            0x00AD, // SOFT HYPHEN
            0xFEFF, // BOM / ZERO WIDTH NO-BREAK SPACE
            0x0007, // BEL
            0x00A0, // NO-BREAK SPACE - invisible, and never reported: caught by the allow-list
            0xE000, // private use
            0x0378, // unassigned
            0x0060, // backtick - would close the Markdown code span the prose renders the token in
        };

        for (int codePoint : refused)
        {
            String token = "a" + new String(Character.toChars(codePoint)) + "b"; //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(String.format("U+%04X must be escaped, not printed", codePoint), //$NON-NLS-1$
                String.format("a\\u%04Xb", codePoint), request(token).unparsedTokens().get(0)); //$NON-NLS-1$
        }
    }

    /**
     * The property the whole escape exists for, asserted ONCE over the finished report instead of
     * case by case: nothing in what we render can break the YAML front matter or the Markdown. Stated
     * this way it cannot be outgrown - a character nobody has thought of yet either prints as itself
     * (and is therefore legible) or comes out as an escape sequence; there is no third outcome to miss.
     * <p>
     * This is the assertion that would have caught all six rounds of this at once.
     */
    @Test
    public void testNoRenderedCharacterCanBreakTheReportStructure() throws Exception
    {
        StringBuilder nasty = new StringBuilder();
        for (int codePoint : new int[] {0x2028, 0x2029, 0x202E, 0x2066, 0x200B, 0x00AD, 0xFEFF,
            0x0007, 0x00A0, 0xE000, 0x0378, 0x0060, 0x0022, 0x005C, 0x000A, 0x000D})
        {
            nasty.append(Character.toChars(codePoint));
        }
        String report = renderExecutedReport(newOutcome(request("x" + nasty)), //$NON-NLS-1$
            List.of("TestConfiguration"), List.of("boom")); //$NON-NLS-1$ //$NON-NLS-2$

        report.codePoints().forEach(codePoint -> {
            if (codePoint == '\n')
            {
                return; // the report's OWN line breaks, the only structure it is allowed to create
            }
            assertTrue(String.format(
                "U+%04X reached the rendered report and can break its structure", codePoint), //$NON-NLS-1$
                isPrintable(codePoint));
        });
    }

    /**
     * The reverse side, and the one that stops a safety fix from being paid for with an unreadable
     * report: legible text must come out AS ITSELF, never escaped. A false escape costs more here than
     * a tolerated oddity - this field is where a mistyped 1C identifier lands, and an escaped form is
     * not a name anyone recognises.
     */
    @Test
    public void testLegibleTextIsNeverEscaped() throws Exception
    {
        String cyrillic = "Справочник"; //$NON-NLS-1$
        String emoji = new String(Character.toChars(0x1F600));

        String report = renderExecutedReport(newOutcome(request(cyrillic + emoji)), //$NON-NLS-1$
            List.of(), List.of());

        assertTrue("legible text must be echoed as itself, not as an escape: " + report, //$NON-NLS-1$
            report.contains(cyrillic + emoji));
        assertFalse("nothing legible may be escaped", report.contains("\\u04")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The other half of that boundary, and the reason it is drawn by CATEGORY rather than by a wider
     * "reject anything unusual": a false refusal costs more than a tolerated oddity, and this field is
     * where a mistyped identifier lands. Cyrillic, diacritics - combining ones included - punctuation
     * and non-Latin scripts must all come back as themselves; only the INVISIBLE is escaped.
     * <p>
     * Pinned because the natural next edit is to widen the predicate, and nothing else would notice.
     */
    @Test
    public void testLegibleNonAsciiTokensAreEchoedUnchanged()
    {
        String cyrillic = "Справочник"; //$NON-NLS-1$
        String precomposed = "café"; // Latin small e with acute //$NON-NLS-1$
        // Built from code points: a combining mark pasted into source sits invisibly on its base
        // letter, so the literal would be unreviewable and look identical to the precomposed one.
        String combining = "e" + (char)0x0301; // e + COMBINING ACUTE ACCENT (category Mn) //$NON-NLS-1$
        String punctuation = "«—…»"; // guillemets, em dash, ellipsis //$NON-NLS-1$
        String cjk = "中"; //$NON-NLS-1$
        String emoji = new String(Character.toChars(0x1F600)); // category So
        // The shapes an allow-list is most likely to swallow by accident: a plain space, and the
        // characters the report itself has to escape rather than refuse.
        String withSpace = "a b"; //$NON-NLS-1$
        String quoteAndBackslash = "a\"b\\c"; //$NON-NLS-1$
        String currencyAndMath = "€+"; //$NON-NLS-1$

        for (String token : List.of(cyrillic, precomposed, combining, punctuation, cjk, emoji,
            withSpace, quoteAndBackslash, currencyAndMath))
        {
            assertEquals("a legible token must be echoed as itself: " + token, //$NON-NLS-1$
                token, request(token).unparsedTokens().get(0));
        }
    }

    /**
     * The token is echoed into a Markdown sentence as well as into YAML. Rendered as a bare value it
     * closed the sequence's own opening bracket: {@code x](http://evil)} turned the caller's typo into
     * a link. The prose renders code spans instead, and the backtick that could close one is escaped.
     */
    @Test
    public void testATokenCannotInjectMarkdownIntoTheProse() throws Exception
    {
        String report = renderExecutedReport(newOutcome(request("x](http://evil)`_")), //$NON-NLS-1$
            List.of(), List.of());

        String prose = report.substring(report.indexOf("_Entr(ies)")); //$NON-NLS-1$
        assertTrue("the token must be inside a code span", prose.startsWith("_Entr(ies) `")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a backtick in the token would close that span", //$NON-NLS-1$
            prose.substring(prose.indexOf('`') + 1, prose.indexOf("` in disableIndices")).contains("`")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A quoted token that LOOKS like a number stays distinguishable from a real index. */
    @Test
    public void testTokensAreQuotedSoTheyCannotBeReadAsIndices() throws Exception
    {
        String report = renderExecutedReport(newOutcome(request("1.5")), List.of(), List.of()); //$NON-NLS-1$

        assertTrue(report.contains("unparsedTokens: [\"1.5\"]")); //$NON-NLS-1$
        assertFalse(report.contains("unknownIndices")); //$NON-NLS-1$
    }

    /**
     * The characters that would end a YAML double-quoted scalar early must come back escaped, and the
     * ones that are merely unusual - Cyrillic, astral - must come back as themselves. Cyrillic matters
     * here beyond tidiness: a mistyped 1C identifier is a likely thing to land in this field, and a
     * sanitizer that mangled it would hide the very thing the report exists to show.
     */
    @Test
    public void testYamlEscapingSurvivesQuotesBackslashesAndNonAsciiTokens() throws Exception
    {
        String cyrillic = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a"; //$NON-NLS-1$
        String emoji = new String(Character.toChars(0x1F600));

        String report = renderExecutedReport(
            newOutcome(request("a\"b|" + cyrillic + "|" + emoji)), List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String value = frontMatterValue(report, "unparsedTokens"); //$NON-NLS-1$
        // The quote is escaped, so the scalar does not end at it...
        assertTrue("the embedded quote must be escaped, got: " + value, //$NON-NLS-1$
            value.contains("a\\\"b")); //$NON-NLS-1$
        // ...and everything legible is echoed as itself.
        assertTrue(value.contains(cyrillic));
        assertTrue(value.contains(emoji));
        assertTrue(value.startsWith("[\"") && value.endsWith("\"]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A backslash must not escape the closing quote of the scalar it sits in. */
    @Test
    public void testABackslashTokenIsEscapedRatherThanEndingTheScalar() throws Exception
    {
        String report = renderExecutedReport(newOutcome(request("a\\")), List.of(), List.of()); //$NON-NLS-1$

        assertEquals("[\"a\\\\\"]", frontMatterValue(report, "unparsedTokens")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An entry that is empty or only whitespace stays formatting, not a token - tab included, which
     * Java counts as whitespace. Pinned so the boundary is a decision rather than an accident.
     */
    @Test
    public void testWhitespaceOnlyEntriesStayFormattingNotTokens()
    {
        assertTrue(request("1,\t,2").unparsedTokens().isEmpty()); //$NON-NLS-1$
        assertEquals(2, request("1,\t,2").indices().size()); //$NON-NLS-1$
        // ...but a control character that is NOT whitespace is a token and is reported.
        assertEquals(1, request("\u0007").unparsedTokens().size()); //$NON-NLS-1$
    }

    /**
     * Whether a code point may appear literally in a rendered report. Mirrors the production predicate
     * deliberately rather than calling it: the test states the PROPERTY it wants ("nothing here can
     * break the structure"), so that a change loosening the production rule shows up as a failure here
     * instead of being silently agreed with.
     */
    private static boolean isPrintable(int codePoint)
    {
        if (codePoint == ' ')
        {
            return true;
        }
        switch (Character.getType(codePoint))
        {
        case Character.UPPERCASE_LETTER:
        case Character.LOWERCASE_LETTER:
        case Character.TITLECASE_LETTER:
        case Character.MODIFIER_LETTER:
        case Character.OTHER_LETTER:
        case Character.NON_SPACING_MARK:
        case Character.COMBINING_SPACING_MARK:
        case Character.ENCLOSING_MARK:
        case Character.DECIMAL_DIGIT_NUMBER:
        case Character.LETTER_NUMBER:
        case Character.OTHER_NUMBER:
        case Character.CONNECTOR_PUNCTUATION:
        case Character.DASH_PUNCTUATION:
        case Character.START_PUNCTUATION:
        case Character.END_PUNCTUATION:
        case Character.INITIAL_QUOTE_PUNCTUATION:
        case Character.FINAL_QUOTE_PUNCTUATION:
        case Character.OTHER_PUNCTUATION:
        case Character.MATH_SYMBOL:
        case Character.CURRENCY_SYMBOL:
        case Character.MODIFIER_SYMBOL:
        case Character.OTHER_SYMBOL:
            return true;
        default:
            return false;
        }
    }

    // ==================== helpers ====================

    /** The YAML front matter of a rendered report, without its --- fences. */
    private static String frontMatter(String report)
    {
        int start = report.indexOf("---\n") + 4; //$NON-NLS-1$
        return report.substring(start, report.indexOf("---\n", start)).trim(); //$NON-NLS-1$
    }

    /** The raw value of one front-matter key. */
    private static String frontMatterValue(String report, String key)
    {
        for (String line : frontMatter(report).split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith(key + ": ")) //$NON-NLS-1$
            {
                return line.substring(key.length() + 2).trim();
            }
        }
        throw new AssertionError("no front-matter key " + key + " in:\n" + report); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static INativeChangeRefactoringItem nativeItem(Change change, boolean optional)
    {
        INativeChangeRefactoringItem item = mock(INativeChangeRefactoringItem.class);
        when(item.getNativeChange()).thenReturn(change);
        when(item.isOptional()).thenReturn(optional);
        return item;
    }

    private static Collection<IRefactoring> refactoring(IRefactoringItem... items)
    {
        IRefactoring refactoring = mock(IRefactoring.class);
        when(refactoring.getItems()).thenReturn(Arrays.asList(items));
        when(refactoring.getTitle()).thenReturn("Rename"); //$NON-NLS-1$
        return List.of(refactoring);
    }

    /**
     * Builds the request through the REAL parser rather than handing the service a set of integers:
     * what a token DOES is half of this contract (#401), and a test that skipped the parse could not
     * see the half where a token never becomes an index at all.
     */
    private static DisableRequest request(String raw)
    {
        return DisableRequest.parse(raw);
    }

    private static Object applyDisableIndices(Collection<IRefactoring> refactorings,
        DisableRequest disableRequest) throws Exception
    {
        Object outcome = newOutcome(disableRequest);
        Method method = MetadataRenameService.class.getDeclaredMethod(
            "applyDisableIndices", Collection.class, Set.class, outcomeClass()); //$NON-NLS-1$
        method.setAccessible(true);
        method.invoke(new MetadataRenameService(), refactorings, disableRequest.indices(), outcome);
        return outcome;
    }

    private static Object newOutcome(DisableRequest requested) throws Exception
    {
        java.lang.reflect.Constructor<?> constructor =
            outcomeClass().getDeclaredConstructor(DisableRequest.class);
        constructor.setAccessible(true);
        return constructor.newInstance(requested);
    }

    private static String renderExecutedReport(Object outcome, List<String> performed,
        List<String> errors) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod("renderExecutedReport", //$NON-NLS-1$
            String.class, String.class, outcomeClass(), List.class, List.class);
        method.setAccessible(true);
        return (String)method.invoke(null, "CommonModule.Old", "New", outcome, performed, errors); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Class<?> outcomeClass()
    {
        for (Class<?> candidate : MetadataRenameService.class.getDeclaredClasses())
        {
            if ("DisableOutcome".equals(candidate.getSimpleName())) //$NON-NLS-1$
            {
                return candidate;
            }
        }
        throw new AssertionError("MetadataRenameService.DisableOutcome not found"); //$NON-NLS-1$
    }

    private static int disabledCount(Object outcome) throws Exception
    {
        Method method = outcomeClass().getDeclaredMethod("disabledCount"); //$NON-NLS-1$
        method.setAccessible(true);
        return (Integer)method.invoke(outcome);
    }

    private static Set<?> notSkippableIndices(Object outcome) throws Exception
    {
        Method method = outcomeClass().getDeclaredMethod("notSkippableIndices"); //$NON-NLS-1$
        method.setAccessible(true);
        return (Set<?>)method.invoke(outcome);
    }
}
