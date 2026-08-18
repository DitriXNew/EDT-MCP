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
 * Tests for {@link WorkmateGateway#needsContinuation(String)} - the decision that separates a
 * finished answer from Workmate announcing what it is ABOUT to do.
 *
 * <p>The shapes come from issue #427: 1C:Workmate's conversation facade completes its future
 * after ONE assistant turn, so a plan ("I will look it up in the documentation") and an empty
 * answer both arrived as the final result. Workmate's own driver does not accept them either -
 * it re-sends into the same conversation - and this predicate is what makes this plugin do the
 * same.
 */
public class WorkmateGatewayContinuationTest
{
    /** The literal answer issue #427 reported as the (wrong) final result. */
    private static final String PLAN_ANSWER =
        "\u0414\u043B\u044F \u043F\u043E\u043B\u0443\u0447\u0435\u043D\u0438\u044F \u043F\u043E\u0434\u0440\u043E\u0431\u043D\u043E\u0439 \u0441\u043F\u0440\u0430\u0432\u043A\u0438 \u043F\u043E \u043E\u0431\u044A\u0435\u043A\u0442\u0443 \u0422\u0430\u0431\u043B\u0438\u0446\u0430\u0417\u043D\u0430\u0447\u0435\u043D\u0438\u0439 \u044F \u0432\u043E\u0441\u043F\u043E\u043B\u044C\u0437\u0443\u044E\u0441\u044C \u043F\u043E\u0438\u0441\u043A\u043E\u043C \u043F\u043E \u0434\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u0430\u0446\u0438\u0438 1\u0421:\u041F\u0440\u0435\u0434\u043F\u0440\u0438\u044F\u0442\u0438\u044F."; //$NON-NLS-1$

    @Test
    public void testEmptyAnswerIsNeverTheResult()
    {
        assertTrue("a null answer says nothing and must be continued",
            WorkmateGateway.needsContinuation(null));
        assertTrue("an empty answer is the '1C:Workmate returned an empty answer' failure",
            WorkmateGateway.needsContinuation(""));
        assertTrue("whitespace is empty too", WorkmateGateway.needsContinuation(" \t \r\n "));
    }

    @Test
    public void testTheAnnouncementFromTheIssueIsContinued()
    {
        assertTrue("the exact text of #427 must not be reported as an answer",
            WorkmateGateway.needsContinuation(PLAN_ANSWER));
    }

    @Test
    public void testMarkerMatchingIsCaseInsensitive()
    {
        assertTrue(WorkmateGateway.needsContinuation("\u0412\u043E\u0441\u043F\u043E\u043B\u044C\u0437\u0443\u044E\u0441\u044C \u043F\u043E\u0438\u0441\u043A\u043E\u043C \u043F\u043E \u0434\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u0430\u0446\u0438\u0438.")); //$NON-NLS-1$
    }

    @Test
    public void testEnglishAnnouncementIsContinued()
    {
        assertTrue(WorkmateGateway.needsContinuation("I will look it up in the documentation."));
        assertTrue(WorkmateGateway.needsContinuation("Let me search the 1C documentation first."));
    }

    @Test
    public void testAShortRealAnswerIsNotContinued()
    {
        // No announcement in it: nothing here says "about to", so it is the result.
        assertFalse(WorkmateGateway.needsContinuation("\u0422\u0430\u0431\u043B\u0438\u0446\u0430\u0417\u043D\u0430\u0447\u0435\u043D\u0438\u0439 - \u0443\u043D\u0438\u0432\u0435\u0440\u0441\u0430\u043B\u044C\u043D\u0430\u044F \u043A\u043E\u043B\u043B\u0435\u043A\u0446\u0438\u044F \u0437\u043D\u0430\u0447\u0435\u043D\u0438\u0439: \u0441\u0442\u0440\u043E\u043A\u0438 \u0438 \u0442\u0438\u043F\u0438\u0437\u0438\u0440\u043E\u0432\u0430\u043D\u043D\u044B\u0435 \u043A\u043E\u043B\u043E\u043D\u043A\u0438.")); //$NON-NLS-1$
    }

    @Test
    public void testAnInclusiveRecommendationIsNotMistakenForAPlan()
    {
        // Review of #440: "let us use an index" is a FINISHED short recommendation, not an
        // announcement of future work. Continuing it would run Workmate's tools again and let a
        // later turn replace an answer that was already correct.
        assertFalse(WorkmateGateway.needsContinuation(
            "\u0414\u0430\u0432\u0430\u0439\u0442\u0435 \u0438\u0441\u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u044C \u0438\u043D\u0434\u0435\u043A\u0441 \u043F\u043E \u043F\u043E\u043B\u044E \u0414\u0430\u0442\u0430 - \u0437\u0430\u043F\u0440\u043E\u0441 \u0441\u0442\u0430\u043D\u0435\u0442 \u0431\u044B\u0441\u0442\u0440\u0435\u0435.")); //$NON-NLS-1$
    }

    @Test
    public void testARhetoricalLetMeIsNotAnAnnouncement()
    {
        // Review of #440, second round: "let me" on its own is a discourse marker. Only the
        // phrases that name an ACTION (search, check, look, find, run) announce future work.
        assertFalse(WorkmateGateway.needsContinuation(
            "Let me clarify: ValueTable is an in-memory collection, not a database table."));
        assertTrue(WorkmateGateway.needsContinuation(
            "Let me search the 1C documentation for the exact method list."));
    }

    @Test
    public void testAPunctuatedAnnouncementIsStillAnAnnouncement()
    {
        // Review of #440, fourth round: a marker with a trailing space missed every announcement
        // the model punctuates, which is exactly the shape this predicate exists to catch.
        assertTrue(WorkmateGateway.needsContinuation("I will:\n- search the documentation"));
        assertTrue(WorkmateGateway.needsContinuation("I will."));
        assertTrue(WorkmateGateway.needsContinuation("I'll: look at the module first"));
        assertFalse("a longer word that merely starts the same way is not a marker",
            WorkmateGateway.needsContinuation("Willingness to index the column decides the plan."));
    }

    @Test
    public void testANegatedPromiseIsADecisionNotAPlan()
    {
        // Review of #440, fifth round: a refusal looks exactly like an announcement and means
        // the opposite. Continuing it would ask Workmate to do what it just declined to do.
        assertFalse(WorkmateGateway.needsContinuation(
            "I will not edit generated files; change the source model instead."));
        assertFalse(WorkmateGateway.needsContinuation("I won't touch the infobase."));
        assertTrue("the affirmative form still announces work",
            WorkmateGateway.needsContinuation("I will check the module list."));
    }

    @Test
    public void testARussianRefusalBuiltFromTheVerbItselfIsNotAPlan()
    {
        // Review of #440, sixth round: Russian negates the future by putting the particle in
        // front of the very verb this list matches, so the refusal contained its own marker.
        assertFalse(WorkmateGateway.needsContinuation(
            "\u042F \u043D\u0435 \u043F\u0440\u043E\u0432\u0435\u0440\u044E \u0437\u0430\u043A\u0440\u044B\u0442\u0443\u044E \u0431\u0430\u0437\u0443; \u043F\u0440\u043E\u0432\u0435\u0440\u044C\u0442\u0435 \u043F\u0440\u0430\u0432\u0430 \u0434\u043E\u0441\u0442\u0443\u043F\u0430.")); //$NON-NLS-1$
    }

    @Test
    public void testABarePresentTimeStatusIsNotAPlan()
    {
        // "Right now I am in debug mode" is a status, not an announcement of work.
        assertFalse(WorkmateGateway.needsContinuation(
            "\u0421\u0435\u0439\u0447\u0430\u0441 \u044F \u0432 \u0440\u0435\u0436\u0438\u043C\u0435 \u043E\u0442\u043B\u0430\u0434\u043A\u0438, \u043F\u043E\u044D\u0442\u043E\u043C\u0443 \u0442\u043E\u0447\u043A\u0430 \u043E\u0441\u0442\u0430\u043D\u043E\u0432\u0430 \u0430\u043A\u0442\u0438\u0432\u043D\u0430.")); //$NON-NLS-1$
    }

    @Test
    public void testMixedRefusalAndPromiseIsStillAPlan()
    {
        // Review of #440, seventh round: negation belongs to ITS occurrence, not to the whole
        // turn - the sentence refuses one thing and announces another, and the announcement wins.
        assertTrue(WorkmateGateway.needsContinuation(
            "I will not edit generated files; I will inspect the source model instead."));
    }

    @Test
    public void testAMarkerInsideAnotherWordIsNotAMarker()
    {
        // "api will" contains "i will": without a boundary BEFORE the match, a finished
        // technical answer was continued.
        assertFalse(WorkmateGateway.needsContinuation(
            "The API will return null when the project is closed."));
    }

    @Test
    public void testTheAnalyticFutureAnnouncesWorkButPolitenessDoesNot()
    {
        assertTrue(WorkmateGateway.needsContinuation("\u042F \u0431\u0443\u0434\u0443 \u0438\u0441\u043A\u0430\u0442\u044C \u0434\u043E\u043A\u0443\u043C\u0435\u043D\u0442\u0430\u0446\u0438\u044E \u043F\u043E \u044D\u0442\u043E\u043C\u0443 \u043E\u0431\u044A\u0435\u043A\u0442\u0443.")); //$NON-NLS-1$
        assertFalse("the bare auxiliary also opens finished statements",
            WorkmateGateway.needsContinuation("\u042F \u0431\u0443\u0434\u0443 \u0440\u0430\u0434 \u043F\u043E\u043C\u043E\u0447\u044C \u0441 \u044D\u0442\u0438\u043C \u0437\u0430\u043F\u0440\u043E\u0441\u043E\u043C.")); //$NON-NLS-1$
    }

    @Test
    public void testNotOnlyIntensifiesRatherThanNegates()
    {
        // Review of #440, eighth round: the correlative denies nothing - the sentence announces
        // MORE work, not less.
        assertTrue(WorkmateGateway.needsContinuation(
            "I will not only inspect the module, I will also fix the query."));
    }

    @Test
    public void testATypographicApostropheIsTheSameContraction()
    {
        // Review of #440, ninth round: models punctuate with the curly apostrophe, and an
        // ASCII-only marker silently accepted the plan as the answer.
        assertTrue(WorkmateGateway.needsContinuation(
            "I’ll search the documentation for the exact method list."));
    }

    @Test
    public void testTheDeclaredMarkerIsWhatDecidesFinality()
    {
        // The protocol this adapter adds to every request: an explicit end-of-answer marker
        // beats phrasing, in any language, which is what the phrase lists cannot promise.
        assertTrue(WorkmateGateway.declaresFinal("The table has 6 columns. " + WorkmateGateway.FINAL_MARKER));
        assertFalse(WorkmateGateway.declaresFinal("The table has 6 columns."));
        assertFalse(WorkmateGateway.declaresFinal(null));
    }

    @Test
    public void testTheMarkerNeverReachesTheCaller()
    {
        assertEquals("Done.", WorkmateGateway.stripFinalMarker("Done. " + WorkmateGateway.FINAL_MARKER));
        assertEquals("A\n\nB",
            WorkmateGateway.stripFinalMarker("A\n\nB\n" + WorkmateGateway.FINAL_MARKER));
        assertNull("a text that was ONLY the marker carries no answer",
            WorkmateGateway.stripFinalMarker(WorkmateGateway.FINAL_MARKER));
        assertNull(WorkmateGateway.stripFinalMarker(null));
    }

    @Test
    public void testAMentionedMarkerIsNotADeclaration()
    {
        // Review of #440: the protocol asks for the marker as the LAST line. A turn that only
        // talks about it - or quotes an earlier answer - has declared nothing.
        assertFalse(WorkmateGateway.declaresFinal(
            "I will add " + WorkmateGateway.FINAL_MARKER + " once the search is done."));
        assertTrue("trailing whitespace still ends the turn",
            WorkmateGateway.declaresFinal("Answer. " + WorkmateGateway.FINAL_MARKER + "  \n"));
    }

    @Test
    public void testStrippingSurvivesACharacterThatGrowsWhenLowercased()
    {
        // Review of #440: U+0130 lowercases to TWO chars, so an index taken from a lowercased
        // copy no longer points at the same place in the original - the answer got cut.
        String answer = "\u0130 answer";
        assertEquals(answer,
            WorkmateGateway.stripFinalMarker(answer + "\n" + WorkmateGateway.FINAL_MARKER));
    }

    @Test
    public void testALongAnswerIsTakenAtFaceValueEvenWithAMarkerInIt()
    {
        // THE guard against over-eagerness: a finished reference answer may well contain the word
        // the predicate keys on, and continuing would spend a round-trip re-asking for what the
        // caller already has.
        StringBuilder longAnswer = new StringBuilder("\u0422\u0430\u0431\u043B\u0438\u0446\u0430\u0417\u043D\u0430\u0447\u0435\u043D\u0438\u0439 - \u044D\u0442\u043E \u0443\u043D\u0438\u0432\u0435\u0440\u0441\u0430\u043B\u044C\u043D\u0430\u044F \u043A\u043E\u043B\u043B\u0435\u043A\u0446\u0438\u044F \u0437\u043D\u0430\u0447\u0435\u043D\u0438\u0439 \u043F\u043B\u0430\u0442\u0444\u043E\u0440\u043C\u044B 1\u0421:\u041F\u0440\u0435\u0434\u043F\u0440\u0438\u044F\u0442\u0438\u0435. \u041E\u043D\u0430 \u0445\u0440\u0430\u043D\u0438\u0442 \u0441\u0442\u0440\u043E\u043A\u0438 \u0438 \u043A\u043E\u043B\u043E\u043D\u043A\u0438, \u043A\u043E\u043B\u043E\u043D\u043A\u0438 \u0442\u0438\u043F\u0438\u0437\u0438\u0440\u0443\u044E\u0442\u0441\u044F \u0447\u0435\u0440\u0435\u0437 \u041E\u043F\u0438\u0441\u0430\u043D\u0438\u0435\u0422\u0438\u043F\u043E\u0432, \u0430 \u0441\u0442\u0440\u043E\u043A\u0438 \u0430\u0434\u0440\u0435\u0441\u0443\u044E\u0442\u0441\u044F \u043A\u0430\u043A \u043F\u043E \u0438\u043D\u0434\u0435\u043A\u0441\u0443, \u0442\u0430\u043A \u0438 \u043F\u043E \u0437\u043D\u0430\u0447\u0435\u043D\u0438\u044E \u043A\u043E\u043B\u043E\u043D\u043A\u0438. "); //$NON-NLS-1$
        while (longAnswer.length() <= 400)
        {
            longAnswer.append("\u0422\u0430\u0431\u043B\u0438\u0446\u0430\u0417\u043D\u0430\u0447\u0435\u043D\u0438\u0439 - \u044D\u0442\u043E \u0443\u043D\u0438\u0432\u0435\u0440\u0441\u0430\u043B\u044C\u043D\u0430\u044F \u043A\u043E\u043B\u043B\u0435\u043A\u0446\u0438\u044F \u0437\u043D\u0430\u0447\u0435\u043D\u0438\u0439 \u043F\u043B\u0430\u0442\u0444\u043E\u0440\u043C\u044B 1\u0421:\u041F\u0440\u0435\u0434\u043F\u0440\u0438\u044F\u0442\u0438\u0435. \u041E\u043D\u0430 \u0445\u0440\u0430\u043D\u0438\u0442 \u0441\u0442\u0440\u043E\u043A\u0438 \u0438 \u043A\u043E\u043B\u043E\u043D\u043A\u0438, \u043A\u043E\u043B\u043E\u043D\u043A\u0438 \u0442\u0438\u043F\u0438\u0437\u0438\u0440\u0443\u044E\u0442\u0441\u044F \u0447\u0435\u0440\u0435\u0437 \u041E\u043F\u0438\u0441\u0430\u043D\u0438\u0435\u0422\u0438\u043F\u043E\u0432, \u0430 \u0441\u0442\u0440\u043E\u043A\u0438 \u0430\u0434\u0440\u0435\u0441\u0443\u044E\u0442\u0441\u044F \u043A\u0430\u043A \u043F\u043E \u0438\u043D\u0434\u0435\u043A\u0441\u0443, \u0442\u0430\u043A \u0438 \u043F\u043E \u0437\u043D\u0430\u0447\u0435\u043D\u0438\u044E \u043A\u043E\u043B\u043E\u043D\u043A\u0438. "); //$NON-NLS-1$
        }
        longAnswer.append("\u0414\u0430\u043B\u044C\u0448\u0435 \u044F \u0432\u043E\u0441\u043F\u043E\u043B\u044C\u0437\u0443\u044E\u0441\u044C \u043F\u0440\u0438\u043C\u0435\u0440\u043E\u043C."); //$NON-NLS-1$
        assertFalse("a long answer is an answer, marker or not",
            WorkmateGateway.needsContinuation(longAnswer.toString()));
    }
}
