/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import org.junit.Test;

/**
 * Ratchet: nothing that explains the padded-name refusals may give what the CALLER CAN PERCEIVE
 * as the reason they exist.
 *
 * <h2>Why the claim is false</h2>
 * {@code PaddedNames} asks its question at every INNER boundary of a compound name, which no
 * caller's {@code trim} reaches, and at every boundary of a merge-rule key, which is not trimmed
 * at all. {@code Catalog. Products} is refused by the scope builder over an ordinary
 * {@code U+0020} - a character every screen draws - and {@code commonModules} with one plain space
 * against its end is refused too. Invisibility is a property of the POSITION, not of the code
 * point: it holds for a character that survives a {@code trim} at the very END of a name, where
 * nothing on screen sits beside it, and fails everywhere else - a {@code U+00A0} after the dot of
 * {@code Catalog.Products} draws a gap there exactly as an ordinary {@code U+0020} does. So it is
 * the reason such a name is easy to READ PAST, never the reason the refusal exists, and a text
 * that states it unscoped is false about inputs the code really acts on.
 *
 * <h2>What is banned is a PHRASE, never a word</h2>
 * An earlier form of this test swept {@link #PADDED_NAMES} for the letters {@code invisib}
 * outright. That was too tight in both directions a ratchet can be wrong: it would have failed an
 * HONEST edit ("{@code U+2003} is invisible on most screens", "{@code U+0020} is not invisible")
 * while still passing any fresh paraphrase that avoided the word. So every pin below names the
 * exact proposition that was measured false. Each pin is a literal {@code contains} and judges
 * WORDING, never truth: a sentence carrying those same words in order to DENY the claim
 * ("{@code U+0020} is not invisible by construction") goes red exactly as the claim itself does.
 * That is the rule here rather than an oversight - anything true that has to be said at one of
 * these spots has to be said in other words.
 *
 * <h2>Why a source scan and not an assertion about behaviour</h2>
 * The claim survived a round of wire-text corrections because it also lives in comments and
 * javadoc, where no assertion about a rendered message or a returned value reaches it. This reads
 * the source of the five files that carry the explanation, exactly as
 * {@code ComparisonTreeReportTest} reads {@code ComparisonTreeReport}. THREE joins and no more:
 * adjacent string literals continued with a leading {@code +}, comment line prefixes ({@code *}
 * and {@code //} alike, because a phrase the formatter broke across two {@code //} lines is one
 * phrase too), and every run of whitespace collapsed. Those three shapes of re-wrapping cannot
 * slip a claim past; a {@code +} left at the END of a line, and an {@code .append(...)} chain,
 * are two that still can.
 *
 * <h2>What this does NOT catch, stated rather than implied</h2>
 * <ul>
 *   <li><b>The same claim in new words.</b> These are literal phrase pins. A fresh paraphrase
 *       asserting some other property of the character - that it "reads as a name", that the
 *       caller "has no reason to doubt it" - would pass. The defence against that is editorial,
 *       not mechanical: say what the refusal DOES, and drop the adjective.</li>
 *   <li><b>A sixth file.</b> The five below are the ones that carry the explanation today. An
 *       explanation of the same refusal written somewhere else is not read here.</li>
 *   <li><b>Anything about behaviour.</b> This reads characters. That the refusals still fire, and
 *       still name the character by code point, is {@code ComparisonScopeBuilderTest}'s,
 *       {@code MergeRulesToolTest}'s and {@code GetComparisonNodeToolTest}'s to say.</li>
 * </ul>
 */
public class PaddedNameClaimRatchetTest
{
    /** Where the bundle's packages start, below the repository root. */
    private static final String BUNDLE_SOURCE_ROOT =
        "mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/"; //$NON-NLS-1$

    /** The class that owns the judgement. */
    private static final String PADDED_NAMES = "utils/compare/PaddedNames.java"; //$NON-NLS-1$

    /** The scope-entry door. */
    private static final String SCOPE_BUILDER = "utils/compare/ComparisonScopeBuilder.java"; //$NON-NLS-1$

    /** The node-address door. */
    private static final String NODE_TOOL = "tools/impl/GetComparisonNodeTool.java"; //$NON-NLS-1$

    /** The merge-rule key door. */
    private static final String MERGE_RULES_TOOL = "tools/impl/MergeRulesTool.java"; //$NON-NLS-1$

    /**
     * What a merge-rule key is made of - the fifth file, and the one this ratchet did not read
     * while it carried a live copy of the claim.
     */
    private static final String MERGE_RULES_DOCUMENT = "utils/compare/MergeRulesDocument.java"; //$NON-NLS-1$

    // ==================== the class that owns the judgement ====================
    //
    // PaddedNames explains the rule for every caller, so a false reason stated here is stated for
    // all of them.

    @Test
    public void testTheJudgementClassNeverSaysThePaddingIsInvisible()
    {
        assertAbsent(PADDED_NAMES, "the padding is invisible", //$NON-NLS-1$
            "'Catalog. Products' is refused over a space anyone can see"); //$NON-NLS-1$
    }

    @Test
    public void testTheJudgementClassNeverSaysTheCharacterIsInvisibleByConstruction()
    {
        // Each proposition gets its own @Test: JUnit stops a method at its first failed
        // assertion, so a second pin sharing a method with the one above would only ever be
        // reached while that one held.
        assertAbsent(PADDED_NAMES, "invisible by construction", //$NON-NLS-1$
            "nothing about this rule makes the character unshowable"); //$NON-NLS-1$
    }

    @Test
    public void testTheJudgementClassNeverSaysNobodyCanSeeTheCharacter()
    {
        assertAbsent(PADDED_NAMES, "nobody can see", //$NON-NLS-1$
            "the same claim without the word"); //$NON-NLS-1$
    }

    @Test
    public void testTheJudgementClassNeverSaysTheCharacterCannotBeSeen()
    {
        assertAbsent(PADDED_NAMES, "cannot be seen", //$NON-NLS-1$
            "the third phrasing of the same overclaim"); //$NON-NLS-1$
    }

    @Test
    public void testTheJudgementClassNeverSaysTheEchoedNameWouldLookIdentical()
    {
        // The consequence half of the old sentence. Dropping "invisible by construction" while
        // keeping "the name would come back looking identical" leaves the overclaim standing in
        // other words, and the absences above would not notice.
        assertAbsent(PADDED_NAMES, "looking identical", //$NON-NLS-1$
            "an echoed 'Catalog. Products' does not look identical to 'Catalog.Products'"); //$NON-NLS-1$
    }

    @Test
    public void testTheJudgementClassNeverSaysQuotingTheNameIdentifiesNothing()
    {
        // The replacement wording of the round that removed "invisible", and false for the same
        // input: quoting 'Catalog. Products' back shows the reader where the space sits. What is
        // true is narrower - the echo carries the character instead of NAMING it.
        assertAbsent(PADDED_NAMES, "identifies nothing", //$NON-NLS-1$
            "a quoted 'Catalog. Products' does identify the offending character"); //$NON-NLS-1$
    }

    @Test
    public void testTheJudgementClassNeverCallsTheCodePointTheOnlyFormAReaderCanActOn()
    {
        // The other half of that replacement. "The code point and the position are the only form
        // of it a reader can act on" is the perception claim again, one abstraction up.
        assertAbsent(PADDED_NAMES, "only form of it a reader can act on", //$NON-NLS-1$
            "what a reader can act on is not this class's to state"); //$NON-NLS-1$
    }

    // ==================== the merge-rule key door ====================

    @Test
    public void testTheMergeRulesToolNeverSaysTheCharacterIsInvisibleByConstruction()
    {
        // A merge-rule key reaches this refusal untrimmed, so it fires on an ordinary U+0020 at a
        // name's end - the input that disproves "by construction" outright.
        assertAbsent(MERGE_RULES_TOOL, "invisible by construction", //$NON-NLS-1$
            "a key is not trimmed on its way in, so a plain space reaches this refusal"); //$NON-NLS-1$
    }

    @Test
    public void testTheMergeRulesToolNeverCallsItTheSameInvisibleCharacter()
    {
        // The cross-reference to the scope-entry refusal carried the claim a second time, in the
        // one word that makes it a statement about every character the two doors share.
        assertAbsent(MERGE_RULES_TOOL, "the same invisible character", //$NON-NLS-1$
            "the two doors share a whitespace character, not an unseeable one"); //$NON-NLS-1$
    }

    @Test
    public void testTheMergeRulesToolNeverSaysTheEchoedKeyWouldLookIdentical()
    {
        assertAbsent(MERGE_RULES_TOOL, "looking identical", //$NON-NLS-1$
            "an echoed key with a plain space does not look identical to a clean one"); //$NON-NLS-1$
    }

    @Test
    public void testTheMergeRulesToolNeverSaysQuotingTheKeyIdentifiesNothing()
    {
        assertAbsent(MERGE_RULES_TOOL, "identifies nothing", //$NON-NLS-1$
            "a quoted 'Alpha: Beta:Gamma' does identify the offending character"); //$NON-NLS-1$
    }

    @Test
    public void testTheMergeRulesToolNeverCallsTheCodePointTheOnlyFormAReaderCanActOn()
    {
        assertAbsent(MERGE_RULES_TOOL, "only form of it a reader can act on", //$NON-NLS-1$
            "what a reader can act on is not this refusal's to state"); //$NON-NLS-1$
    }

    // ==================== what a merge-rule key is made of ====================

    @Test
    public void testTheMergeRulesDocumentNeverSaysTheCallerCannotSeeItInTheirOwnRequest()
    {
        // The fifth file, and the copy that outlived the first correction round because nothing
        // read it. 'commonModules ' with one trailing space, and 'Alpha: Beta:Gamma', are both
        // refused here and both show the offending character in the caller's own request.
        assertAbsent(MERGE_RULES_DOCUMENT, "cannot see by looking at their own request", //$NON-NLS-1$
            "a trailing plain space is in the request the caller sent"); //$NON-NLS-1$
    }

    @Test
    public void testTheMergeRulesDocumentNeverSaysQuotingTheKeyIdentifiesNothing()
    {
        // Pinned here as well as at the door, so the claim cannot simply move one file down.
        assertAbsent(MERGE_RULES_DOCUMENT, "identifies nothing", //$NON-NLS-1$
            "quoting the key back does identify a visible space"); //$NON-NLS-1$
    }

    // ==================== the node-address door ====================

    @Test
    public void testTheNodeToolNeverSaysTheRefusedAddressLooksExactlyRight()
    {
        assertAbsent(NODE_TOOL, "looks exactly right", //$NON-NLS-1$
            "the address it refuses may carry a plain, visible space"); //$NON-NLS-1$
    }

    @Test
    public void testTheNodeToolNeverAppealsToWhatIsOnTheCallersScreen()
    {
        // The other half of the same sentence. Kept apart because "looks exactly right" could be
        // softened while "on any screen" carried the claim on alone.
        assertAbsent(NODE_TOOL, "on any screen", //$NON-NLS-1$
            "what the address looks like is the caller's screen to judge"); //$NON-NLS-1$
    }

    @Test
    public void testTheNodeToolNeverQuantifiesTheFaultAsOneWhitespaceCharacter()
    {
        // 'Catalog.  Products' is two characters, and 'Catalog..Products' is no character at all
        // - the second half of this very refusal reports a MISSING SEGMENT NAME. A text that
        // counts the fault at one describes neither.
        assertAbsent(NODE_TOOL, "one whitespace character", //$NON-NLS-1$
            "the fault is not always one character, and not always a character"); //$NON-NLS-1$
    }

    @Test
    public void testTheNodeToolNeverSaysTheReportedCharacterSurvivesAnOrdinaryTrim()
    {
        // The wire sentence, whose subject was "such a character" - the code point just named,
        // which for 'Catalog. Products' is U+0020. The advice survives with its subject NAMED
        // (U+2003, U+00A0 and their kin), which is the sibling door's wording and true of itself;
        // that the message still names them is pinned on the WIRE, in GetComparisonNodeToolTest.
        assertAbsent(NODE_TOOL, "such a character survives an ordinary trim", //$NON-NLS-1$
            "the character it just reported may be an ordinary space"); //$NON-NLS-1$
    }

    // ==================== the scope-entry door ====================

    @Test
    public void testTheScopeBuilderNeverCallsTheEmptySegmentInvisible()
    {
        // The sibling refusal, and the same mistake one question over: a segment that names
        // nothing is reported for 'Catalog. .Products' too, where the offending segment is a
        // plain space. The scoped statements elsewhere in this file - each of which names U+2003
        // and U+00A0 before it appeals to a screen - are deliberately left alone.
        assertAbsent(SCOPE_BUILDER, "the offending part is invisible", //$NON-NLS-1$
            "a segment that names nothing may hold a space anyone can see"); //$NON-NLS-1$
    }

    @Test
    public void testTheScopeBuilderNeverSaysTheCallerHasNoReasonToReadTheAddressAsWrong()
    {
        // The same family, aimed at the OTHER door from inside this file: the sentence explaining
        // what a padded address costs get_comparison_node used to end "the refusal quotes an
        // address the caller has no reason to read as wrong".
        assertAbsent(SCOPE_BUILDER, "no reason to read as wrong", //$NON-NLS-1$
            "'Catalog. Products' quoted back carries the space that is wrong with it"); //$NON-NLS-1$
    }

    // ==================== machinery ====================

    /**
     * Asserts that one bundle source file does not make a claim, in the exact words it was
     * measured false in.
     *
     * @param relative the file, below {@link #BUNDLE_SOURCE_ROOT}
     * @param phrase the lower-case phrase that must not appear
     * @param why the input or the fact that disproves it, for the failure message
     */
    private static void assertAbsent(String relative, String phrase, String why)
    {
        String source = normalized(relative);

        assertFalse(why + " - so '" + phrase + "' may not be stated in " + relative + ": " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + window(source, phrase), source.contains(phrase));
    }

    /**
     * Shows WHERE a banned phrase was found, without emptying the file into the report.
     *
     * @param source the normalized source text
     * @param phrase the phrase looked for
     * @return the phrase with up to 120 characters of context on either side, or an empty string
     *         when it is absent - the message is built on every call, pass or fail, and the whole
     *         normalized {@code MergeRulesTool} is ~180 KB of it
     */
    private static String window(String source, String phrase)
    {
        int at = source.indexOf(phrase);
        if (at < 0)
        {
            return ""; //$NON-NLS-1$
        }
        int from = Math.max(0, at - 120);
        int to = Math.min(source.length(), at + phrase.length() + 120);
        return (from > 0 ? "..." : "") + source.substring(from, to) //$NON-NLS-1$ //$NON-NLS-2$
            + (to < source.length() ? "..." : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads one bundle source file and normalizes it, so a claim made in a comment can be pinned
     * the way a claim made on the wire is.
     *
     * @param relative the path below {@link #BUNDLE_SOURCE_ROOT}
     * @return the file's text with comment line prefixes dropped, every run of whitespace
     *         collapsed to one space, and the whole lower-cased
     */
    private static String normalized(String relative)
    {
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File candidate = new File(dir, BUNDLE_SOURCE_ROOT + relative);
            if (candidate.isFile())
            {
                return unwrapped(read(candidate)).toLowerCase(Locale.ROOT);
            }
            dir = dir.getParentFile();
        }
        throw new AssertionError("could not locate '" + BUNDLE_SOURCE_ROOT + relative //$NON-NLS-1$
            + "' by walking up from user.dir=" + System.getProperty("user.dir")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param file a source file
     * @return its text
     */
    private static String read(File file)
    {
        try
        {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Joins what the Java formatter broke, so a phrase is one phrase wherever it is written.
     * <p>
     * THREE joins, and each was measured: two of them let a claim through while the third was in
     * place. Javadoc {@code *} prefixes were the first. Line comments were the second - a
     * sentence the formatter wrapped across two {@code //} lines normalized to
     * "one whitespace // character" and slipped past a pin for "one whitespace character".
     * Adjacent string LITERALS were the third and the one that matters most, because the wire
     * texts are the reader-facing half of this family: {@code "...Such a character " + "survives
     * an ordinary trim..."} normalizes with the closing quote, the {@code //$NON-NLS-n$} markers
     * and the {@code +} still between the two halves, so every pin longer than one source line
     * was blind to a message on the wire. Measured by mutation, not assumed: the wire sentence
     * this ratchet exists to keep out was reinstated and the pin stayed green until this join
     * was added.
     *
     * @param source Java source text
     * @return the same text with adjacent string literals glued, javadoc and line-comment
     *         prefixes dropped, and every run of whitespace collapsed to one space
     */
    private static String unwrapped(String source)
    {
        return source.replace("\r", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replaceAll("\"[ \\t]*(?://\\$NON-NLS-\\d+\\$[ \\t]*)*\\n\\s*\\+[ \\t]*\"", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replaceAll("\\n\\s*(?:\\*|//)", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
