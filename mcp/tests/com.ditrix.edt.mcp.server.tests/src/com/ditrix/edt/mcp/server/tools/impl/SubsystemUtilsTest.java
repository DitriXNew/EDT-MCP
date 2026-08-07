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
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EMap;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;

/**
 * Tests for {@link SubsystemUtils}.
 *
 * <p>Direct unit coverage for: type-token recognition ({@code isSubsystemTypeToken}),
 * FQN parsing ({@code parseSubsystemPath}), synonym lookup with language fallback
 * ({@code getSynonymForLanguage}, exercised via {@link BasicEMap}), and language
 * resolution ({@code resolveLanguage}) without a {@code Configuration}.</p>
 *
 * <p>The full {@code resolveByFqn} method needs an EMF {@code Configuration}
 * with live {@code Subsystem} children, so it is covered through e2e tests
 * against {@code TestConfiguration} rather than here.</p>
 */
public class SubsystemUtilsTest
{
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
}
