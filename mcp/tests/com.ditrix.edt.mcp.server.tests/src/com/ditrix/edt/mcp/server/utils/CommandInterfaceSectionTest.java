/**
 * MCP Server for EDT
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Group;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Item;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Panel;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Plan;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.PlanResult;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSection.Visibility;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link CommandInterfaceSection}: command and group lookup in both languages, and the
 * planner that turns a {@code commands} batch into the changes producing its END state.
 */
public class CommandInterfaceSectionTest
{
    private static final String CATALOG_OPEN_LIST = "Catalog.Products.StandardCommand.OpenList"; //$NON-NLS-1$
    private static final String DOCUMENT_OPEN_LIST = "Document.Orders.StandardCommand.OpenList"; //$NON-NLS-1$
    private static final String COMMON_PRINT = "CommonCommand.Print"; //$NON-NLS-1$
    private static final String CATALOG_RECALCULATE = "Catalog.Products.Command.Recalculate"; //$NON-NLS-1$
    private static final String CATALOG_CREATE = "Catalog.Products.StandardCommand.Create"; //$NON-NLS-1$
    private static final String REPORT_OPEN = "Report.Sales.StandardCommand.Open"; //$NON-NLS-1$
    private static final String IMPORTANT = "NavigationPanelImportant"; //$NON-NLS-1$
    private static final String REPORTS = "ActionsPanelReports"; //$NON-NLS-1$
    private static final String MANAGER = "Role.Manager"; //$NON-NLS-1$

    private static final UnaryOperator<String> ROLES = ref -> "role.manager".equalsIgnoreCase(ref) //$NON-NLS-1$
        || "Роль.Manager".equals(ref) ? MANAGER : null; //$NON-NLS-1$

    private Group important;
    private Group ordinary;
    private Group reports;
    private Group service;
    private Item catalogOpenList;
    private Item documentOpenList;
    private Item print;
    private Item reportOpen;
    private CommandInterfaceSection section;

    @Before
    public void setUp()
    {
        important = group(IMPORTANT, "ПанельНавигацииВажное", Panel.NAVIGATION, false); //$NON-NLS-1$
        ordinary = group("NavigationPanelOrdinary", "ПанельНавигацииОбычное", Panel.NAVIGATION, false); //$NON-NLS-1$ //$NON-NLS-2$
        Group seeAlso = group("NavigationPanelSeeAlso", "ПанельНавигацииСмТакже", Panel.NAVIGATION, false); //$NON-NLS-1$ //$NON-NLS-2$
        service = group("CommandGroup.Service", null, Panel.NAVIGATION, true); //$NON-NLS-1$
        Group create = group("ActionsPanelCreate", "ПанельДействийСоздать", Panel.ACTIONS, false); //$NON-NLS-1$ //$NON-NLS-2$
        reports = group(REPORTS, "ПанельДействийОтчеты", Panel.ACTIONS, false); //$NON-NLS-1$

        catalogOpenList = item(CATALOG_OPEN_LIST, "Catalog.Products.StandardCommand.ОткрытьСписок", true); //$NON-NLS-1$
        documentOpenList = item(DOCUMENT_OPEN_LIST, "Document.Orders.StandardCommand.ОткрытьСписок", true); //$NON-NLS-1$
        print = item(COMMON_PRINT, null, true);
        Item recalculate = item(CATALOG_RECALCULATE, null, true);
        Item catalogCreate = item(CATALOG_CREATE, "Catalog.Products.StandardCommand.Создать", true); //$NON-NLS-1$
        reportOpen = item(REPORT_OPEN, "Report.Sales.StandardCommand.Открыть", true); //$NON-NLS-1$
        Item unresolved = new Item("Catalog.Gone.StandardCommand.OpenList", null, new Object(), //$NON-NLS-1$
            new Visibility(true, Collections.emptyMap()), true, false, false);

        important.items.addAll(Arrays.asList(catalogOpenList, documentOpenList));
        ordinary.items.addAll(Arrays.asList(print, recalculate, unresolved));
        create.items.add(catalogCreate);
        reports.items.add(reportOpen);
        section = new CommandInterfaceSection("Subsystem.Sales", "Subsystem.Sales.CommandInterface", //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.asList(important, ordinary, seeAlso, service, create, reports));
    }

    // ===== lookup ===================================================================================

    @Test
    public void testFindItemByEnglishFqn()
    {
        assertSame(reportOpen, section.findItem(REPORT_OPEN));
        assertSame(print, section.findItem("commoncommand.print")); //$NON-NLS-1$
    }

    @Test
    public void testFindItemByRussianTokensAndStandardCommandName()
    {
        assertSame(reportOpen, section.findItem("Отчет.Sales.СтандартнаяКоманда.Открыть")); //$NON-NLS-1$
        assertSame(reportOpen, section.findItem("Report.Sales.StandardCommand.Открыть")); //$NON-NLS-1$
        assertSame(catalogOpenList, section.findItem("Справочник.Products.СтандартныеКоманды.OpenList")); //$NON-NLS-1$
        assertSame(print, section.findItem("ОбщаяКоманда.Print")); //$NON-NLS-1$
        assertEquals(CATALOG_RECALCULATE, section.findItem("Справочник.Products.Команда.Recalculate").fqn()); //$NON-NLS-1$
    }

    @Test
    public void testFindItemMissesUnresolvedAndUnknown()
    {
        assertNull(section.findItem("Catalog.Gone.StandardCommand.OpenList")); //$NON-NLS-1$
        assertNull(section.findItem("CommonCommand.Missing")); //$NON-NLS-1$
        assertNull(section.findItem("Print")); //$NON-NLS-1$
        assertNull(section.findItem(null));
    }

    @Test
    public void testFindGroupByEnglishRussianAndCommandGroupFqn()
    {
        assertSame(important, section.findGroup(IMPORTANT));
        assertSame(important, section.findGroup("ПанельНавигацииВажное")); //$NON-NLS-1$
        assertSame(service, section.findGroup("CommandGroup.Service")); //$NON-NLS-1$
        assertSame(service, section.findGroup("ГруппаКоманд.Service")); //$NON-NLS-1$
        assertNull(section.findGroup("FormCommandBarImportant")); //$NON-NLS-1$
        assertNull(section.findGroup(" ")); //$NON-NLS-1$
    }

    // ===== visibility ===============================================================================

    @Test
    public void testHideCommand()
    {
        Plan plan = planOk("[{command:'" + COMMON_PRINT + "', visible:false}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, plan.visibility().size());
        assertFalse(plan.visibility().get(print).common());
        assertTrue(plan.placement().isEmpty());
        assertTrue(plan.order().isEmpty());
        assertTrue(plan.unchanged().isEmpty());
    }

    @Test
    public void testRequestingTheCurrentStateIsANoOp()
    {
        Plan plan = planOk("[{command:'" + COMMON_PRINT + "', visible:true}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(plan.isEmpty());
        assertEquals(Collections.singletonList(COMMON_PRINT), plan.unchanged());
    }

    @Test
    public void testBatchIsJudgedByItsEndState()
    {
        Plan plan = planOk("[{command:'" + COMMON_PRINT + "', visible:false}, " //$NON-NLS-1$ //$NON-NLS-2$
            + "{command:'CommonCommand.Print', visible:'true'}]"); //$NON-NLS-1$
        assertTrue(plan.isEmpty());
        assertEquals(Collections.singletonList(COMMON_PRINT), plan.unchanged());
    }

    @Test
    public void testRoleOverrideRussianRoleToken()
    {
        Plan plan = planOk("[{command:'" + COMMON_PRINT + "', roles:[{role:'Роль.Manager', visible:false}]}]"); //$NON-NLS-1$ //$NON-NLS-2$
        Visibility visibility = plan.visibility().get(print);
        assertNotNull(visibility);
        assertTrue(visibility.common());
        assertEquals(Collections.singletonMap(MANAGER, Boolean.FALSE), visibility.roles());
    }

    @Test
    public void testDefaultRoleValueDropsTheOverride()
    {
        Item overridden = overriddenAudit();
        Plan plan = planOk("[{command:'CommonCommand.Audit', roles:[{role:'Role.Manager', visible:'Default'}]}]"); //$NON-NLS-1$
        assertTrue(plan.visibility().get(overridden).roles().isEmpty());
        assertTrue(plan.visibility().get(overridden).common());
    }

    @Test
    public void testNullRoleValueDropsTheOverrideWhenItArrives()
    {
        Item overridden = overriddenAudit();
        Plan plan = planOk("[{command:'CommonCommand.Audit', roles:[{role:'Role.Manager', visible:null}]}]"); //$NON-NLS-1$
        assertTrue(plan.visibility().get(overridden).roles().isEmpty());
    }

    private Item overriddenAudit()
    {
        Map<String, Boolean> roles = new LinkedHashMap<>();
        roles.put(MANAGER, Boolean.FALSE);
        Item overridden = new Item("CommonCommand.Audit", null, new Object(), new Visibility(true, roles), //$NON-NLS-1$
            true, false, true);
        ordinary.items.add(overridden);
        return overridden;
    }

    @Test
    public void testUnknownRoleIsRefused()
    {
        String error = planError("[{command:'" + COMMON_PRINT + "', roles:[{role:'Role.Nope', visible:false}]}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.startsWith("commands[0].roles[0].role 'Role.Nope' is not a role")); //$NON-NLS-1$
    }

    @Test
    public void testRoleEntryShapeIsValidated()
    {
        assertTrue(planError("[{command:'" + COMMON_PRINT + "', roles:{role:'Role.Manager'}}]") //$NON-NLS-1$ //$NON-NLS-2$
            .startsWith("commands[0].roles must be an array")); //$NON-NLS-1$
        assertTrue(planError("[{command:'" + COMMON_PRINT + "', roles:[{role:'Role.Manager'}]}]") //$NON-NLS-1$ //$NON-NLS-2$
            .startsWith("commands[0].roles[0].visible is required: true, false, or 'default'")); //$NON-NLS-1$
        assertTrue(planError("[{command:'" + COMMON_PRINT + "', roles:[{role:'Role.Manager', visible:'no'}]}]") //$NON-NLS-1$ //$NON-NLS-2$
            .startsWith("commands[0].roles[0].visible must be true, false or 'default'")); //$NON-NLS-1$
    }

    @Test
    public void testVisibleMustBeBoolean()
    {
        String error = planError("[{command:'" + COMMON_PRINT + "', visible:'maybe'}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.startsWith("commands[0].visible must be true or false")); //$NON-NLS-1$
    }

    // ===== placement and order ======================================================================

    @Test
    public void testMoveToAnotherPanelGroup()
    {
        Plan plan = planOk("[{command:'" + REPORT_OPEN + "', group:'" + IMPORTANT + "'}]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertSame(important, plan.placement().get(reportOpen));
        assertTrue("a move without an anchor leaves the order automatic", plan.order().isEmpty()); //$NON-NLS-1$
        assertTrue(plan.visibility().isEmpty());
    }

    @Test
    public void testMoveThereAndBackIsANoOp()
    {
        Plan plan = planOk("[{command:'" + REPORT_OPEN + "', group:'ПанельНавигацииВажное'}, " //$NON-NLS-1$ //$NON-NLS-2$
            + "{command:'" + REPORT_OPEN + "', group:'" + REPORTS + "'}]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(plan.isEmpty());
    }

    @Test
    public void testOrderBeforeAnchorWritesTheWholeGroupOrder()
    {
        Plan plan = planOk("[{command:'" + DOCUMENT_OPEN_LIST + "', before:'" + CATALOG_OPEN_LIST + "'}]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Arrays.asList(documentOpenList, catalogOpenList), plan.order().get(important));
        assertTrue(plan.placement().isEmpty());
    }

    @Test
    public void testOrderAlreadyInPlaceIsANoOp()
    {
        Plan plan = planOk("[{command:'" + DOCUMENT_OPEN_LIST + "', after:'" + CATALOG_OPEN_LIST + "'}]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(plan.isEmpty());
        assertEquals(Collections.singletonList(DOCUMENT_OPEN_LIST), plan.unchanged());
    }

    @Test
    public void testMoveAndAnchorInOneEntry()
    {
        Plan plan = planOk("[{command:'" + REPORT_OPEN + "', group:'" + IMPORTANT + "', after:'" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + CATALOG_OPEN_LIST + "'}]"); //$NON-NLS-1$
        assertSame(important, plan.placement().get(reportOpen));
        assertEquals(Arrays.asList(catalogOpenList, reportOpen, documentOpenList), plan.order().get(important));
    }

    @Test
    public void testAnchorIsCheckedAgainstTheStateThePreviousEntriesLeft()
    {
        String moveThenAnchor = "[{command:'" + REPORT_OPEN + "', group:'" + IMPORTANT + "'}, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "{command:'" + REPORT_OPEN + "', before:'" + CATALOG_OPEN_LIST + "'}]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Plan plan = planOk(moveThenAnchor);
        assertEquals(Arrays.asList(reportOpen, catalogOpenList, documentOpenList), plan.order().get(important));

        String anchorThenMove = "[{command:'" + REPORT_OPEN + "', before:'" + CATALOG_OPEN_LIST + "'}, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "{command:'" + REPORT_OPEN + "', group:'" + IMPORTANT + "'}]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String error = planError(anchorThenMove);
        assertTrue(error, error.startsWith("commands[0].before anchor '" + CATALOG_OPEN_LIST //$NON-NLS-1$
            + "' is in group NavigationPanelImportant, but '" + REPORT_OPEN + "' is in group ActionsPanelReports")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.contains("group:'NavigationPanelImportant'")); //$NON-NLS-1$
    }

    @Test
    public void testMovingOutOfAnOrderedGroupKeepsTheRestOfItsOrder()
    {
        Plan plan = planOk("[{command:'" + DOCUMENT_OPEN_LIST + "', before:'" + CATALOG_OPEN_LIST + "'}, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "{command:'" + DOCUMENT_OPEN_LIST + "', group:'NavigationPanelOrdinary'}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(ordinary, plan.placement().get(documentOpenList));
        assertEquals(Collections.singletonList(catalogOpenList), plan.order().get(important));
    }

    // ===== refusals =================================================================================

    @Test
    public void testEmptyBatchIsRefused()
    {
        assertTrue(planError("[]").startsWith("'commands' is empty")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnknownKeyIsRefused()
    {
        String error = planError("[{command:'" + COMMON_PRINT + "', position:1}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.startsWith("commands[0] has an unknown key 'position'")); //$NON-NLS-1$
        assertTrue(error, error.contains("command, visible, roles, group, after, before")); //$NON-NLS-1$
    }

    @Test
    public void testMissingCommandIsRefused()
    {
        assertTrue(planError("[{visible:false}]").startsWith("commands[0].command is required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnknownCommandListsTheSectionAndHowToAddIt()
    {
        String error = planError("[{command:'" + COMMON_PRINT + "', visible:false}, " //$NON-NLS-1$ //$NON-NLS-2$
            + "{command:'CommonCommand.Missing', visible:false}]"); //$NON-NLS-1$
        assertTrue(error, error.startsWith("commands[1].command 'CommonCommand.Missing' is not in the command " //$NON-NLS-1$
            + "interface of Subsystem.Sales")); //$NON-NLS-1$
        assertTrue(error, error.contains(REPORT_OPEN));
        assertTrue(error, error.contains("modify_metadata(fqn='Subsystem.Sales', content=")); //$NON-NLS-1$
        assertFalse("an unresolved stored command is not offered", error.contains("Catalog.Gone")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEntryThatChangesNothingIsRefused()
    {
        String error = planError("[{command:'" + COMMON_PRINT + "'}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.startsWith("commands[0] ('" + COMMON_PRINT + "') changes nothing")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBothAnchorsAreRefused()
    {
        String error = planError("[{command:'" + DOCUMENT_OPEN_LIST + "', after:'" + CATALOG_OPEN_LIST //$NON-NLS-1$ //$NON-NLS-2$
            + "', before:'" + CATALOG_OPEN_LIST + "'}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.startsWith("commands[0] sets both 'after' and 'before'")); //$NON-NLS-1$
    }

    @Test
    public void testSelfAnchorIsRefused()
    {
        String error = planError("[{command:'" + DOCUMENT_OPEN_LIST + "', after:'" + DOCUMENT_OPEN_LIST + "'}]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(error, error.startsWith("commands[0].after names the command itself")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownAnchorIsRefused()
    {
        String error = planError("[{command:'" + DOCUMENT_OPEN_LIST + "', after:'CommonCommand.Missing'}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.startsWith("commands[0].after 'CommonCommand.Missing' is not in the command")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownGroupListsTheGroupsInBothLanguages()
    {
        String error = planError("[{command:'" + REPORT_OPEN + "', group:'FormCommandBarImportant'}]"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.startsWith("commands[0].group 'FormCommandBarImportant' is not a panel group")); //$NON-NLS-1$
        assertTrue(error, error.contains("NavigationPanelImportant (ПанельНавигацииВажное)")); //$NON-NLS-1$
        assertTrue(error, error.contains("CommandGroup.Service")); //$NON-NLS-1$
    }

    @Test
    public void testAFailedBatchPlansNothing()
    {
        PlanResult result = section.plan(entries("[{command:'" + COMMON_PRINT + "', visible:false}, " //$NON-NLS-1$ //$NON-NLS-2$
            + "{command:'" + COMMON_PRINT + "', group:'Nope'}]"), ROLES); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(result.plan);
        assertTrue(result.error, result.error.startsWith("commands[1].group")); //$NON-NLS-1$
    }

    @Test
    public void testFailedFactoryCarriesTheMessage()
    {
        PlanResult result = CommandInterfaceSection.failed("boom"); //$NON-NLS-1$
        assertNull(result.plan);
        assertEquals("boom", result.error); //$NON-NLS-1$
    }

    // ===== render ===================================================================================

    @Test
    public void testRenderListsGroupsCommandsAndCustomizations()
    {
        Map<String, Boolean> roles = new LinkedHashMap<>();
        roles.put(MANAGER, Boolean.FALSE);
        service.items.add(new Item("CommonCommand.Audit|x", null, new Object(), new Visibility(false, roles), //$NON-NLS-1$
            true, true, true));
        String md = section.render("stale"); //$NON-NLS-1$
        assertTrue(md, md.startsWith("## Command interface: Subsystem.Sales\n")); //$NON-NLS-1$
        assertTrue(md, md.contains("modify_metadata(fqn='Subsystem.Sales.CommandInterface', commands=[...])")); //$NON-NLS-1$
        assertTrue(md, md.contains("> stale\n")); //$NON-NLS-1$
        assertTrue(md, md.contains("### NavigationPanelImportant (ПанельНавигацииВажное) - navigation panel, " //$NON-NLS-1$
            + "order: automatic")); //$NON-NLS-1$
        assertTrue(md, md.contains("### NavigationPanelSeeAlso (ПанельНавигацииСмТакже) - navigation panel, " //$NON-NLS-1$
            + "order: automatic\n\n_(empty)_")); //$NON-NLS-1$
        assertTrue(md, md.contains("### CommandGroup.Service - navigation panel, command group, order: automatic")); //$NON-NLS-1$
        assertTrue(md, md.contains("Role.Manager: no")); //$NON-NLS-1$
        assertTrue(md, md.contains("placement, visibility")); //$NON-NLS-1$
        assertTrue("a pipe in a cell is escaped", md.contains("CommonCommand.Audit\\|x")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("**Unresolved stored commands**")); //$NON-NLS-1$
        assertTrue(md, md.contains("Catalog.Gone.StandardCommand.OpenList")); //$NON-NLS-1$
        assertTrue("the display order is kept", md.indexOf(CATALOG_OPEN_LIST) < md.indexOf(DOCUMENT_OPEN_LIST)); //$NON-NLS-1$
    }

    @Test
    public void testRenderWithoutNoteHasNoQuote()
    {
        assertFalse(section.render(null).contains("\n> ")); //$NON-NLS-1$
    }

    // ===== helpers ==================================================================================

    private static Group group(String id, String russianId, Panel panel, boolean custom)
    {
        return new Group(id, russianId, panel, custom, new Object(), false);
    }

    private static Item item(String fqn, String russianFqn, boolean visible)
    {
        return new Item(fqn, russianFqn, new Object(), new Visibility(visible, Collections.emptyMap()), false,
            false, true);
    }

    private static List<JsonObject> entries(String json)
    {
        List<JsonObject> list = new ArrayList<>();
        JsonParser.parseString(json).getAsJsonArray().forEach(e -> list.add(e.getAsJsonObject()));
        return list;
    }

    private Plan planOk(String json)
    {
        PlanResult result = section.plan(entries(json), ROLES);
        assertNull(result.error, result.error);
        return result.plan;
    }

    private String planError(String json)
    {
        PlanResult result = section.plan(entries(json), ROLES);
        assertNotNull("expected a refusal", result.error); //$NON-NLS-1$
        assertNull(result.plan);
        return result.error;
    }
}
