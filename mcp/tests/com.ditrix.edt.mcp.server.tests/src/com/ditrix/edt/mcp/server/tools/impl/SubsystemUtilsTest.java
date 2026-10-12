/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EMap;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;

/**
 * Tests for {@link SubsystemUtils}.
 *
 * <p>Direct unit coverage for: type-token recognition ({@code isSubsystemTypeToken}),
 * FQN parsing ({@code parseSubsystemPath}, {@code nestedChain}, {@code malformedSegmentError}),
 * synonym lookup with language fallback ({@code getSynonymForLanguage}, exercised via
 * {@link BasicEMap}), language resolution ({@code resolveLanguage}) without a
 * {@code Configuration}, and - on an EMF {@code Configuration} built with {@link MdClassFactory} -
 * the chain walks ({@code resolveByPath}, {@code resolveByFqn}, {@code chainFqn}).</p>
 *
 * <p>Issue #708 adds the helpers several tools read: the addressing sentence and the
 * "subsystem not found" built on it, the rule a chain resolves by in a project's root
 * ({@code resolveInScope}), and the walk up a subsystem's own parents ({@code lineage}) - with
 * whether that walk is an address ({@code Lineage.spells}).</p>
 */
public class SubsystemUtilsTest
{
    /**
     * The addressing sentence written out as the wire text it is. Every tool test that pins a refusal
     * carrying it compares against THIS literal, not against {@link SubsystemUtils#addressingHint()}:
     * changing the sentence then turns every consumer's pin red, and a consumer that kept a copy of
     * its own is the one that stays green.
     */
    public static final String ADDRESSING_HINT_TEXT = "A top-level subsystem is addressed as 'Subsystem.<Name>' " //$NON-NLS-1$
        + "and a nested one by its whole chain from a top-level subsystem, with a type token before every " //$NON-NLS-1$
        + "name ('Subsystem.<Parent>.Subsystem.<Child>', any depth; the tokens may be English or Russian) - " //$NON-NLS-1$
        + "list_subsystems lists the existing ones in exactly that form"; //$NON-NLS-1$

    /** "Планирование" - the parent subsystem of issue #708. */
    private static final String RU_PLANNING = fromCp(0x041f, 0x043b, 0x0430, 0x043d, 0x0438, 0x0440, 0x043e,
        0x0432, 0x0430, 0x043d, 0x0438, 0x0435);
    /** "ПланированиеЗапасов" - the nested subsystem of issue #708. */
    private static final String RU_STOCK_PLANNING = RU_PLANNING + fromCp(0x0417, 0x0430, 0x043f, 0x0430, 0x0441,
        0x043e, 0x0432);

    // ========== isSubsystemTypeToken ==========

    @Test
    public void testTypeTokenEnglishSingular()
    {
        assertTrue(SubsystemUtils.isSubsystemTypeToken("Subsystem")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenEnglishPlural()
    {
        assertTrue(SubsystemUtils.isSubsystemTypeToken("Subsystems")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenCaseInsensitive()
    {
        assertTrue(SubsystemUtils.isSubsystemTypeToken("subsystem")); //$NON-NLS-1$
        assertTrue(SubsystemUtils.isSubsystemTypeToken("SUBSYSTEM")); //$NON-NLS-1$
        assertTrue(SubsystemUtils.isSubsystemTypeToken("SubSystem")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenRussianSingular()
    {
        // Подсистема
        assertTrue(SubsystemUtils.isSubsystemTypeToken("Подсистема")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenRussianPlural()
    {
        // Подсистемы
        assertTrue(SubsystemUtils.isSubsystemTypeToken("Подсистемы")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenWithWhitespace()
    {
        assertTrue(SubsystemUtils.isSubsystemTypeToken(" Subsystem ")); //$NON-NLS-1$
        assertTrue(SubsystemUtils.isSubsystemTypeToken("\tSubsystem")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenNotASubsystem()
    {
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Catalog")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Document")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Role")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Справочник")); // Справочник //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenNullOrEmpty()
    {
        assertFalse(SubsystemUtils.isSubsystemTypeToken(null));
        assertFalse(SubsystemUtils.isSubsystemTypeToken("")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("   ")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenGarbage()
    {
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Sub")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("System")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("foo bar")); //$NON-NLS-1$
    }

    // ========== parseSubsystemPath ==========

    @Test
    public void testParseTopLevel()
    {
        assertArrayEquals(new String[] { "Sales" }, //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath("Subsystem.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testParseNested()
    {
        assertArrayEquals(new String[] { "Sales", "Orders" }, //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.parseSubsystemPath("Subsystem.Sales.Subsystem.Orders")); //$NON-NLS-1$
    }

    @Test
    public void testParseDeeplyNested()
    {
        assertArrayEquals(new String[] { "Sales", "Orders", "Backlog" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            SubsystemUtils.parseSubsystemPath("Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog")); //$NON-NLS-1$
    }

    @Test
    public void testParseRussianTopLevel()
    {
        // Подсистема.Продажи
        String fqn = "Подсистема.Продажи"; //$NON-NLS-1$
        assertArrayEquals(new String[] { "Продажи" }, // Продажи //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath(fqn));
    }

    @Test
    public void testParseRussianNested()
    {
        // Подсистема.Продажи.Подсистема.Заказы
        String fqn = "Подсистема.Продажи.Подсистема.Заказы"; //$NON-NLS-1$
        assertArrayEquals(
            new String[] { "Продажи", "Заказы" }, // Продажи, Заказы //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.parseSubsystemPath(fqn));
    }

    @Test
    public void testParseMixedEnglishRussian()
    {
        // Подсистема.Продажи.Subsystem.Orders
        String fqn = "Подсистема.Продажи.Subsystem.Orders"; //$NON-NLS-1$
        assertArrayEquals(
            new String[] { "Продажи", "Orders" }, // Продажи, Orders //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.parseSubsystemPath(fqn));
    }

    @Test
    public void testParseLowercaseTypeToken()
    {
        assertArrayEquals(new String[] { "Sales" }, //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath("subsystem.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testParsePluralTypeToken()
    {
        assertArrayEquals(new String[] { "Sales" }, //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath("Subsystems.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testParseLeadingTrailingWhitespace()
    {
        assertArrayEquals(new String[] { "Sales" }, //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath("  Subsystem.Sales  ")); //$NON-NLS-1$
    }

    @Test
    public void testParseNameTrimmed()
    {
        // Each name segment is trimmed individually after splitting on '.'
        assertArrayEquals(new String[] { "Sales", "Orders" }, //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.parseSubsystemPath("Subsystem. Sales .Subsystem. Orders ")); //$NON-NLS-1$
    }

    @Test
    public void testParseWrongTypeToken()
    {
        assertNull(SubsystemUtils.parseSubsystemPath("Catalog.Products")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath("Document.SalesOrder")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath("Role.FullAccess")); //$NON-NLS-1$
    }

    @Test
    public void testParseWrongTypeTokenInNestedSegment()
    {
        // Second segment has a wrong type token (Catalog instead of Subsystem)
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem.Sales.Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void testParseOddNumberOfParts()
    {
        // "Subsystem.Sales.Subsystem" — name missing for the second segment
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem.Sales.Subsystem")); //$NON-NLS-1$
    }

    @Test
    public void testParseSingleToken()
    {
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem")); //$NON-NLS-1$
    }

    @Test
    public void testParseEmptyName()
    {
        // "Subsystem." or "Subsystem. " — empty name segment
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem.")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem. ")); //$NON-NLS-1$
    }

    @Test
    public void testParseNullOrBlank()
    {
        assertNull(SubsystemUtils.parseSubsystemPath(null));
        assertNull(SubsystemUtils.parseSubsystemPath("")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath("   ")); //$NON-NLS-1$
    }

    @Test
    public void testParseGarbage()
    {
        assertNull(SubsystemUtils.parseSubsystemPath("not a fqn at all")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath(".Subsystem.Sales")); // leading dot //$NON-NLS-1$
    }

    // ========== getSynonymForLanguage ==========

    @Test
    public void testGetSynonymNullMap()
    {
        assertEquals("", SubsystemUtils.getSynonymForLanguage(null, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymEmptyMap()
    {
        EMap<String, String> empty = new BasicEMap<>();
        assertEquals("", SubsystemUtils.getSynonymForLanguage(empty, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymPreferredLanguage()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("ru", "Продажи"); // Продажи //$NON-NLS-1$ //$NON-NLS-2$
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Продажи", SubsystemUtils.getSynonymForLanguage(synonyms, "ru")); // Продажи //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymFallbackWhenLanguageMissing()
    {
        // Map has only English; user asks for Russian — fallback returns English
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymFallbackSkipsEmptyValues()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("ru", ""); //$NON-NLS-1$ //$NON-NLS-2$
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        // ru is empty — should skip and return en
        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymNullLanguageFallsBack()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        // null language — skip preferred lookup, go to fallback
        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, null)); //$NON-NLS-1$
    }

    @Test
    public void testGetSynonymEmptyLanguageFallsBack()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymAllValuesEmpty()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("ru", ""); //$NON-NLS-1$ //$NON-NLS-2$
        synonyms.put("en", ""); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("", SubsystemUtils.getSynonymForLanguage(synonyms, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ========== resolveLanguage ==========

    @Test
    public void testResolveLanguageExplicitWins()
    {
        // Explicit non-empty value is returned regardless of config
        assertEquals("en", SubsystemUtils.resolveLanguage("en", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testResolveLanguageNullConfigAndExplicit()
    {
        // No explicit, no config → null (caller falls back via getSynonymForLanguage)
        assertNull(SubsystemUtils.resolveLanguage(null, null));
        assertNull(SubsystemUtils.resolveLanguage("", null)); //$NON-NLS-1$
    }

    // ========== nestedChain (the create_metadata dispatch gate, issue #351) ==========

    @Test
    public void testNestedChainAcceptsDepthTwo()
    {
        assertArrayEquals(new String[] { "Sales", "Orders" }, //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.nestedChain("Subsystem.Sales.Subsystem.Orders")); //$NON-NLS-1$
    }

    @Test
    public void testNestedChainAcceptsDeeperChains()
    {
        assertArrayEquals(new String[] { "Sales", "Orders", "Backlog" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            SubsystemUtils.nestedChain("Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog")); //$NON-NLS-1$
    }

    @Test
    public void testNestedChainRejectsTopLevelSubsystem()
    {
        // A top-level subsystem is NOT nested: create_metadata must keep taking the normal
        // top-object path for it, or every Subsystem.Name create would land in the nested branch.
        assertNull(SubsystemUtils.nestedChain("Subsystem.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testNestedChainRejectsNonSubsystemAddresses()
    {
        assertNull(SubsystemUtils.nestedChain("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
        assertNull(SubsystemUtils.nestedChain("Subsystem.Sales.Catalog.Products")); //$NON-NLS-1$
        assertNull(SubsystemUtils.nestedChain(null));
    }

    // ========== malformedSegmentError (the well-formedness gate of the create branch) ==========

    @Test
    public void testWellFormedAddressIsNotRefused()
    {
        // The CONTROL for every refusal below. Without it the whole group would still pass if the
        // check simply refused everything.
        assertNull(SubsystemUtils.malformedSegmentError("Subsystem.Sales.Subsystem.Child")); //$NON-NLS-1$
        assertNull(SubsystemUtils.malformedSegmentError(
            "Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog")); //$NON-NLS-1$
        assertNull(SubsystemUtils.malformedSegmentError("Подсистема.Продажи.Subsystem.Orders")); //$NON-NLS-1$
        assertNull(SubsystemUtils.malformedSegmentError(null));
    }

    @Test
    public void testAPaddedSegmentIsRefusedByName()
    {
        // parseSubsystemPath TRIMS each segment, which is right for a lookup and wrong for a create:
        // it would store 'Child' for ' Child ' and navigate to 'Sales' for ' Sales ', while the
        // ordinary create path refuses both (findObject matches the owner name verbatim, and the
        // identifier check rejects a leading space).
        String padded = SubsystemUtils.malformedSegmentError("Subsystem.Sales.Subsystem. Child "); //$NON-NLS-1$
        assertNotNull("a padded segment must be refused", padded); //$NON-NLS-1$
        assertTrue("the refusal must quote the offending segment: " + padded, //$NON-NLS-1$
            padded.contains("' Child '")); //$NON-NLS-1$
        assertNotNull(SubsystemUtils.malformedSegmentError("Subsystem. Sales .Subsystem.Child")); //$NON-NLS-1$
        // Whitespace around the WHOLE address is the same fault: it lands in the first or the last
        // segment, and the refusal must still name THAT segment rather than the whole address.
        String outer = SubsystemUtils.malformedSegmentError(" Subsystem.Sales.Subsystem.Child "); //$NON-NLS-1$
        assertNotNull(outer);
        assertTrue("the refusal must quote the offending segment: " + outer, //$NON-NLS-1$
            outer.contains("' Subsystem'")); //$NON-NLS-1$
        // A segment that is nothing BUT whitespace is padded, not empty - it must not be mislabelled.
        String blank = SubsystemUtils.malformedSegmentError("Subsystem.Sales.Subsystem. "); //$NON-NLS-1$
        assertNotNull(blank);
        assertFalse("a blank segment is padded, not empty: " + blank, //$NON-NLS-1$
            blank.contains("EMPTY segment")); //$NON-NLS-1$
        // ...while the LOOKUP parser keeps tolerating it - the two really do differ on purpose.
        assertArrayEquals(new String[] { "Sales", "Child" }, //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.parseSubsystemPath("Subsystem. Sales .Subsystem. Child ")); //$NON-NLS-1$
    }

    @Test
    public void testATrailingSeparatorIsRefusedAndNotSilentlyDropped()
    {
        // String.split() DROPS trailing empty strings, so 'Subsystem.Sales.Subsystem.Child.' splits
        // into the same four segments as the clean address: the stray separator is invisible unless
        // the split is given an explicit -1 limit. Accepting it would act on an address the caller
        // did not type - the same defect class as the padded segment above, and the same verdict
        // get_project_errors already gives an empty segment.
        for (String stray : new String[] {
            "Subsystem.Sales.Subsystem.Child.", //$NON-NLS-1$
            "Subsystem.Sales.Subsystem.Child..", //$NON-NLS-1$
            "Subsystem.Sales.Subsystem.Child..."}) //$NON-NLS-1$
        {
            String err = SubsystemUtils.malformedSegmentError(stray);
            assertNotNull("a stray trailing separator must be refused: " + stray, err); //$NON-NLS-1$
            assertTrue("the refusal must say WHAT is wrong: " + err, err.contains("EMPTY segment")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the refusal must quote the address: " + err, err.contains(stray)); //$NON-NLS-1$
            // ...and the parser really does read it as the clean chain - which is exactly why the
            // check above cannot be left to it.
            assertArrayEquals("the parser reads the stray address as the clean chain", //$NON-NLS-1$
                new String[] { "Sales", "Child" }, SubsystemUtils.parseSubsystemPath(stray)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testLeadingAndMidStringEmptySegmentsNeverReachTheCreateBranch()
    {
        // The symmetric spellings. These are refused one step EARLIER - split keeps a leading or
        // mid-string empty string, so the arity / empty-name checks in parseSubsystemPath fire and
        // the address is not a subsystem chain at all. Pinned so a later change to that parse cannot
        // quietly let them through on the assumption that malformedSegmentError covers them.
        for (String bad : new String[] {
            ".Subsystem.Sales.Subsystem.Child", //$NON-NLS-1$
            "Subsystem.Sales..Subsystem.Child", //$NON-NLS-1$
            "Subsystem..Subsystem.Child", //$NON-NLS-1$
            "Subsystem.Sales.Subsystem..Child", //$NON-NLS-1$
            ".", //$NON-NLS-1$
            "..."}) //$NON-NLS-1$
        {
            assertNull("must not be read as a nested-subsystem chain: " + bad, //$NON-NLS-1$
                SubsystemUtils.nestedChain(bad));
        }
    }

    @Test
    public void testNestedChainIsBilingualAtEVERYPosition()
    {
        // The whole point of routing this through the shared catalogue: the token is translated at
        // EVERY position, not only the leading one (the #342 defect shape). Each of the four
        // spellings below must work as the NESTED token, next to any spelling of the leading one.
        String ruSingular = "Подсистема"; //$NON-NLS-1$
        String ruPlural = "Подсистемы"; //$NON-NLS-1$
        for (String leading : new String[] { "Subsystem", "Subsystems", ruSingular, ruPlural }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            for (String nested : new String[] { "Subsystem", "Subsystems", ruSingular, ruPlural }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                assertArrayEquals(leading + " / " + nested + " must be a nested chain", //$NON-NLS-1$ //$NON-NLS-2$
                    new String[] { "Sales", "Orders" }, //$NON-NLS-1$ //$NON-NLS-2$
                    SubsystemUtils.nestedChain(leading + ".Sales." + nested + ".Orders")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    // ========== resolveByPath / chainFqn ==========

    /** A configuration holding Sales -> Orders, plus a childless Marketing next to Sales. */
    private static Configuration nestedFixture()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Subsystem sales = MdClassFactory.eINSTANCE.createSubsystem();
        sales.setName("Sales"); //$NON-NLS-1$
        Subsystem orders = MdClassFactory.eINSTANCE.createSubsystem();
        orders.setName("Orders"); //$NON-NLS-1$
        sales.getSubsystems().add(orders);
        Subsystem marketing = MdClassFactory.eINSTANCE.createSubsystem();
        marketing.setName("Marketing"); //$NON-NLS-1$
        config.getSubsystems().add(sales);
        config.getSubsystems().add(marketing);
        return config;
    }

    @Test
    public void testResolveByPathWalksOnlyTheRequestedPrefix()
    {
        // The PREFIX overload is what a create needs: the leaf does not exist yet, so only the
        // parent chain may be walked. Asking for depth 1 of a depth-2 chain must answer the PARENT,
        // never the leaf and never null.
        Configuration config = nestedFixture();
        String[] chain = SubsystemUtils.nestedChain("Subsystem.Sales.Subsystem.NewOne"); //$NON-NLS-1$
        Subsystem parent = SubsystemUtils.resolveByPath(config, chain, chain.length - 1);
        assertNotNull("the existing parent must resolve even though the leaf does not exist", parent); //$NON-NLS-1$
        assertEquals("Sales", parent.getName()); //$NON-NLS-1$
        assertNull("the leaf itself must not resolve - it is the node to create", //$NON-NLS-1$
            SubsystemUtils.resolveByPath(config, chain, chain.length));
    }

    @Test
    public void testResolveByPathAnswersNullForADeadEndParent()
    {
        // Marketing exists but has no children: a create addressed under it must be refused at the
        // PARENT step only when the parent itself is missing, and the existing-leaf probe below must
        // stay null so the create is allowed to proceed.
        Configuration config = nestedFixture();
        String[] chain = SubsystemUtils.nestedChain("Subsystem.Marketing.Subsystem.Orders"); //$NON-NLS-1$
        assertNotNull(SubsystemUtils.resolveByPath(config, chain, chain.length - 1));
        assertNull(SubsystemUtils.resolveByPath(config, chain, chain.length));
        // ...and a parent that does not exist at all resolves to nothing.
        String[] missing = SubsystemUtils.nestedChain("Subsystem.NoSuch.Subsystem.Orders"); //$NON-NLS-1$
        assertNull(SubsystemUtils.resolveByPath(config, missing, missing.length - 1));
    }

    @Test
    public void testResolveByPathOutOfRangeDepth()
    {
        Configuration config = nestedFixture();
        String[] chain = SubsystemUtils.nestedChain("Subsystem.Sales.Subsystem.Orders"); //$NON-NLS-1$
        assertNull(SubsystemUtils.resolveByPath(config, chain, 0));
        assertNull(SubsystemUtils.resolveByPath(config, chain, 3));
        assertNull(SubsystemUtils.resolveByPath(null, chain, 1));
        assertNull(SubsystemUtils.resolveByPath(config, null, 1));
    }

    @Test
    public void testResolveByFqnStillResolvesTheWholeChain()
    {
        // The refactor to the prefix walk must not change what the whole-chain resolver answers.
        Configuration config = nestedFixture();
        Subsystem orders = SubsystemUtils.resolveByFqn(config, "Subsystem.Sales.Subsystem.Orders"); //$NON-NLS-1$
        assertNotNull(orders);
        assertEquals("Orders", orders.getName()); //$NON-NLS-1$
        assertNull(SubsystemUtils.resolveByFqn(config, "Subsystem.Marketing.Subsystem.Orders")); //$NON-NLS-1$
        assertNull(SubsystemUtils.resolveByFqn(null, "Subsystem.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testChainFqnRendersTheCanonicalEnglishToken()
    {
        // Whatever the caller spelled, the rendered address carries the canonical token - this is
        // the form EDT itself stores in <parentSubsystem>.
        String[] chain = SubsystemUtils.nestedChain("Подсистема.Продажи.Подсистемы.Заказы"); //$NON-NLS-1$
        assertEquals("Subsystem.Продажи.Subsystem.Заказы", //$NON-NLS-1$
            SubsystemUtils.chainFqn(chain, chain.length));
        assertEquals("Subsystem.Продажи", SubsystemUtils.chainFqn(chain, 1)); //$NON-NLS-1$
        assertNull(SubsystemUtils.chainFqn(chain, 0));
        assertNull(SubsystemUtils.chainFqn(chain, 3));
        assertNull(SubsystemUtils.chainFqn(null, 1));
    }

    // ========== the helpers several tools read (issue #708) ==========
    //
    // Like the platform model, a nested subsystem is NOT contained by its parent here: the parent
    // only lists it (Subsystem.subsystems is a reference) and the child points back
    // (parentSubsystem). Russian names are built from code points so a non-UTF-8 build cannot
    // corrupt them.

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    /** A subsystem linked under {@code parent} both ways, as the platform links a nested one. */
    private static Subsystem linked(String name, Subsystem parent)
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

    /** Sales -> Orders -> Backlog and the issue's Russian pair, every nested level linked both ways. */
    private static final class LinkedTree
    {
        final Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        final Subsystem sales = linked("Sales", null); //$NON-NLS-1$
        final Subsystem orders = linked("Orders", sales); //$NON-NLS-1$
        final Subsystem backlog = linked("Backlog", orders); //$NON-NLS-1$
        final Subsystem planning = linked(RU_PLANNING, null);
        final Subsystem stockPlanning = linked(RU_STOCK_PLANNING, planning);

        LinkedTree()
        {
            config.setName("Cfg"); //$NON-NLS-1$
            config.getSubsystems().add(sales);
            config.getSubsystems().add(planning);
        }
    }

    // ---------- the addressing sentence and the "subsystem not found" built on it ----------

    @Test
    public void testTheAddressingHintIsTheSentenceTheSubsystemRefusalsShare()
    {
        assertEquals(ADDRESSING_HINT_TEXT, SubsystemUtils.addressingHint());
    }

    @Test
    public void testASubsystemNotFoundNamesTheAddressThenHowSubsystemsAreAddressed()
    {
        // The one "subsystem not found" of modify_metadata and get_subsystem_content: the address as
        // the caller wrote it - not normalized - then the shared sentence.
        assertEquals("Subsystem not found: subsystem.Sales.Subsystem.Missing. " + ADDRESSING_HINT_TEXT + ".", //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.notFoundMessage("subsystem.Sales.Subsystem.Missing")); //$NON-NLS-1$
    }

    // ---------- a chain in a project's root ----------

    @Test
    public void testAChainInAScopeIsTheSubsystemAtItsLastLevelNeverAParent()
    {
        LinkedTree tree = new LinkedTree();
        MetadataScope scope = MetadataScope.ofConfiguration(tree.config);

        assertSame(tree.sales, SubsystemUtils.resolveInScope(scope, new String[] { "Sales" })); //$NON-NLS-1$
        assertSame(tree.orders, SubsystemUtils.resolveInScope(scope, new String[] { "Sales", "Orders" })); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(tree.backlog,
            SubsystemUtils.resolveInScope(scope, new String[] { "sales", "orders", "backlog" })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("a missing leaf must not answer with its parent", //$NON-NLS-1$
            SubsystemUtils.resolveInScope(scope, new String[] { "Sales", "Missing" })); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a missing parent", //$NON-NLS-1$
            SubsystemUtils.resolveInScope(scope, new String[] { "Missing", "Orders" })); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a bare child names a top-level subsystem only", //$NON-NLS-1$
            SubsystemUtils.resolveInScope(scope, new String[] { "Orders" })); //$NON-NLS-1$
    }

    @Test
    public void testAnExternalObjectsProjectResolvesNoChainInItsLinkedBase()
    {
        // The linked base configuration is not this project's root (issue #309).
        LinkedTree tree = new LinkedTree();
        MetadataScope external = MetadataScopeTestFixtures.externalObjectsWithBase(tree.config);

        assertNull(SubsystemUtils.resolveInScope(external, new String[] { "Sales", "Orders" })); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(SubsystemUtils.resolveInScope(external, new String[] { "Sales" })); //$NON-NLS-1$
        assertNull(SubsystemUtils.resolveInScope(null, new String[] { "Sales" })); //$NON-NLS-1$
        assertNull(SubsystemUtils.resolveInScope(MetadataScope.ofConfiguration(tree.config), null));
    }

    // ---------- the lineage: one walk, both answers ----------

    @Test
    public void testTheLineageReadsTheChainOffTheLeafsOwnParents()
    {
        LinkedTree tree = new LinkedTree();
        SubsystemUtils.Lineage deep = SubsystemUtils.lineage(tree.backlog);
        assertEquals("nearest first", Arrays.asList(tree.orders, tree.sales), deep.ancestors()); //$NON-NLS-1$
        assertEquals("Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog", deep.chainFqn()); //$NON-NLS-1$

        assertEquals(Collections.singletonList(tree.sales), SubsystemUtils.lineage(tree.orders).ancestors());
        assertEquals("Subsystem.Sales.Subsystem.Orders", SubsystemUtils.lineage(tree.orders).chainFqn()); //$NON-NLS-1$

        SubsystemUtils.Lineage top = SubsystemUtils.lineage(tree.sales);
        assertTrue(top.ancestors().isEmpty());
        assertEquals("Subsystem.Sales", top.chainFqn()); //$NON-NLS-1$

        // The STORED names and the canonical English token, whatever language the names are in.
        assertEquals("Subsystem." + RU_PLANNING + ".Subsystem." + RU_STOCK_PLANNING, //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.lineage(tree.stockPlanning).chainFqn());
    }

    @Test(timeout = 10000)
    public void testTheLineageStopsOnACycleAndNamesNothing()
    {
        Subsystem first = linked("First", null); //$NON-NLS-1$
        Subsystem second = linked("Second", first); //$NON-NLS-1$
        first.setParentSubsystem(second);

        SubsystemUtils.Lineage cyclic = SubsystemUtils.lineage(second);
        assertEquals(Collections.singletonList(first), cyclic.ancestors());
        assertNull("a walk cut short never spells an address", cyclic.chainFqn()); //$NON-NLS-1$
    }

    @Test
    public void testTheLineageStopsAtAnUnresolvedParentAndNamesNothing()
    {
        // A proxy is not an attached object: neither an export entry nor a level of the address.
        Subsystem proxy = mock(Subsystem.class);
        when(proxy.eIsProxy()).thenReturn(true);
        when(proxy.getName()).thenReturn("Gone"); //$NON-NLS-1$
        Subsystem child = mock(Subsystem.class);
        when(child.getParentSubsystem()).thenReturn(proxy);
        when(child.getName()).thenReturn("Child"); //$NON-NLS-1$

        SubsystemUtils.Lineage cut = SubsystemUtils.lineage(child);
        assertTrue(cut.ancestors().isEmpty());
        assertNull("a walk cut short never spells an address", cut.chainFqn()); //$NON-NLS-1$
    }

    @Test
    public void testTheLineageOfNothingIsEmptyAndUnmodifiable()
    {
        SubsystemUtils.Lineage nothing = SubsystemUtils.lineage(null);
        assertTrue(nothing.ancestors().isEmpty());
        assertNull(nothing.chainFqn());
        try
        {
            nothing.ancestors().add(MdClassFactory.eINSTANCE.createSubsystem());
            fail("ancestors() is documented unmodifiable - on the null leaf's path too"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // The contract Lineage.ancestors() documents, held on every path.
        }
    }

    // ---------- whether the walk up is an address: Lineage.spells ----------
    //
    // An address resolves DOWN by name; the walk reads the levels back UP. spells answers whether the
    // walk passed through the very names of an address, level for level, by the rule the descent
    // matches a level with. Each @Test pins one way of getting that wrong.

    @Test
    public void testTheWalkUpSpellsTheExactStoredNames()
    {
        LinkedTree tree = new LinkedTree();
        assertTrue(SubsystemUtils.lineage(tree.backlog).spells(new String[] { "Sales", "Orders", "Backlog" })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(SubsystemUtils.lineage(tree.orders).spells(new String[] { "Sales", "Orders" })); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a top-level subsystem is a walk of one level", //$NON-NLS-1$
            SubsystemUtils.lineage(tree.sales).spells(new String[] { "Sales" })); //$NON-NLS-1$
        assertTrue(SubsystemUtils.lineage(tree.stockPlanning).spells(new String[] { RU_PLANNING, RU_STOCK_PLANNING }));
    }

    @Test
    public void testTheWalkUpSpellsAnAddressInAnyLetterCase()
    {
        LinkedTree tree = new LinkedTree();
        assertTrue(SubsystemUtils.lineage(tree.backlog).spells(new String[] { "sales", "ORDERS", "bAcKlOg" })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testTheWalkUpSpellsAnAddressedNameWithSurroundingWhitespace()
    {
        // The descent trims an addressed name before it matches a level, so a padded name is the
        // same level on the way up too - not another address.
        LinkedTree tree = new LinkedTree();
        assertTrue(SubsystemUtils.lineage(tree.backlog).spells(new String[] { " Sales ", "\tOrders", "Backlog " })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testTheWalkUpSpellsEveryAddressTheDescentResolves()
    {
        // ONE rule for a level, down and up: whatever spelling of a name the descent resolves a chain
        // by, the walk up from what it found must accept as that very chain.
        LinkedTree tree = new LinkedTree();
        MetadataScope scope = MetadataScope.ofConfiguration(tree.config);
        String[][] spellings = {
            { "Sales", "Orders", "Backlog" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            { "sales", "orders", "backlog" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            { "SALES", "Orders", "bAcKlOg" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            { " Sales ", "\tOrders", "Backlog " } }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String[] names : spellings)
        {
            Subsystem found = SubsystemUtils.resolveInScope(scope, names);
            assertSame("the descent: " + Arrays.toString(names), tree.backlog, found); //$NON-NLS-1$
            assertTrue("the walk up: " + Arrays.toString(names), SubsystemUtils.lineage(found).spells(names)); //$NON-NLS-1$
        }
    }

    @Test
    public void testADifferentTopLevelNameIsNotSpelled()
    {
        // The top level is a level too: a walk that reached another top-level subsystem is another
        // address, however many levels it has.
        LinkedTree tree = new LinkedTree();
        assertFalse(SubsystemUtils.lineage(tree.backlog).spells(new String[] { "Planning", "Orders", "Backlog" })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testADifferentMiddleNameIsNotSpelled()
    {
        LinkedTree tree = new LinkedTree();
        assertFalse(SubsystemUtils.lineage(tree.backlog).spells(new String[] { "Sales", "Returns", "Backlog" })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testADifferentLeafNameIsNotSpelled()
    {
        // The leaf is a level too. A source found by its address always matches it, but the predicate
        // answers for any address, not only for the one the subsystem was found by.
        LinkedTree tree = new LinkedTree();
        assertFalse(SubsystemUtils.lineage(tree.backlog).spells(new String[] { "Sales", "Orders", "Archive" })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAShorterAddressIsNotSpelled()
    {
        LinkedTree tree = new LinkedTree();
        assertFalse("the upper levels of the walk only", //$NON-NLS-1$
            SubsystemUtils.lineage(tree.backlog).spells(new String[] { "Sales", "Orders" })); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the lower levels of the walk only", //$NON-NLS-1$
            SubsystemUtils.lineage(tree.backlog).spells(new String[] { "Orders", "Backlog" })); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testALongerAddressIsNotSpelled()
    {
        LinkedTree tree = new LinkedTree();
        assertFalse(SubsystemUtils.lineage(tree.orders).spells(new String[] { "Sales", "Orders", "Backlog" })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test(timeout = 10000)
    public void testAWalkCutShortByACycleSpellsNothing()
    {
        Subsystem first = linked("First", null); //$NON-NLS-1$
        Subsystem second = linked("Second", first); //$NON-NLS-1$
        first.setParentSubsystem(second);

        SubsystemUtils.Lineage cyclic = SubsystemUtils.lineage(second);
        assertFalse(cyclic.spells(new String[] { "First", "Second" })); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(cyclic.spells(new String[] { "Second" })); //$NON-NLS-1$
    }

    @Test
    public void testAWalkCutShortByAnUnresolvedParentSpellsNothing()
    {
        Subsystem proxy = mock(Subsystem.class);
        when(proxy.eIsProxy()).thenReturn(true);
        when(proxy.getName()).thenReturn("Gone"); //$NON-NLS-1$
        Subsystem child = mock(Subsystem.class);
        when(child.getParentSubsystem()).thenReturn(proxy);
        when(child.getName()).thenReturn("Child"); //$NON-NLS-1$

        SubsystemUtils.Lineage cut = SubsystemUtils.lineage(child);
        assertFalse(cut.spells(new String[] { "Gone", "Child" })); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(cut.spells(new String[] { "Child" })); //$NON-NLS-1$
    }

    @Test
    public void testANullAddressIsNeverSpelled()
    {
        LinkedTree tree = new LinkedTree();
        assertFalse(SubsystemUtils.lineage(tree.sales).spells(null));
        assertFalse("a level without a name is not a name", //$NON-NLS-1$
            SubsystemUtils.lineage(tree.orders).spells(new String[] { "Sales", null })); //$NON-NLS-1$
        assertFalse("the lineage of no subsystem spells nothing", //$NON-NLS-1$
            SubsystemUtils.lineage(null).spells(new String[] { "Sales" })); //$NON-NLS-1$
    }

    // ---------- a subsystem that stores no name: the STORED side of the one rule ----------
    //
    // The rule a level matches by answers false for a stored name that is null - on the way down and
    // on the way up alike. testANullAddressIsNeverSpelled pins the ADDRESSED side; these two pin
    // the stored one, so a rule that asks the stored name first cannot pass for the same rule.

    /**
     * A subsystem whose Name was never set, listed FIRST under {@code parent} - so a descent meets it
     * before any named sibling - and linked back to it.
     */
    private static Subsystem unnamedFirstUnder(Subsystem parent)
    {
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        parent.getSubsystems().add(0, subsystem);
        subsystem.setParentSubsystem(parent);
        return subsystem;
    }

    @Test
    public void testTheDescentPassesOverASubsystemThatStoresNoName()
    {
        LinkedTree tree = new LinkedTree();
        Subsystem unnamedTop = MdClassFactory.eINSTANCE.createSubsystem();
        tree.config.getSubsystems().add(0, unnamedTop);
        unnamedFirstUnder(tree.sales);
        assertNull("the premise: a Name that was never set is null", unnamedTop.getName()); //$NON-NLS-1$
        MetadataScope scope = MetadataScope.ofConfiguration(tree.config);

        assertSame("past an unnamed top-level subsystem listed first", tree.sales, //$NON-NLS-1$
            SubsystemUtils.resolveInScope(scope, new String[] { "Sales" })); //$NON-NLS-1$
        assertSame("past an unnamed child listed first", tree.orders, //$NON-NLS-1$
            SubsystemUtils.resolveInScope(scope, new String[] { "Sales", "Orders" })); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAWalkUpThroughASubsystemThatStoresNoNameSpellsNothing()
    {
        LinkedTree tree = new LinkedTree();
        Subsystem underUnnamedTop = linked("Child", MdClassFactory.eINSTANCE.createSubsystem()); //$NON-NLS-1$
        Subsystem underUnnamedMiddle = linked("Leaf", unnamedFirstUnder(tree.sales)); //$NON-NLS-1$
        Subsystem unnamedLeaf = unnamedFirstUnder(tree.orders);
        assertNull("the premise: a Name that was never set is null", unnamedLeaf.getName()); //$NON-NLS-1$

        assertFalse("an unnamed top level", //$NON-NLS-1$
            SubsystemUtils.lineage(underUnnamedTop).spells(new String[] { "Top", "Child" })); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an unnamed middle level", SubsystemUtils.lineage(underUnnamedMiddle) //$NON-NLS-1$
            .spells(new String[] { "Sales", "Middle", "Leaf" })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("an unnamed leaf", //$NON-NLS-1$
            SubsystemUtils.lineage(unnamedLeaf).spells(new String[] { "Sales", "Orders", "Leaf" })); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
