/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;

public class SortMetadataToolTest
{
    private final SortMetadataTool tool = new SortMetadataTool();

    @Test
    public void listUsesAReadTransactionAndPreservesUnsortedBilingualCollectionOrder()
    {
        var factory = MdClassFactory.eINSTANCE;
        var config = factory.createConfiguration();
        var z = factory.createCatalog();
        z.setName("Zulu");
        var a = factory.createCatalog();
        a.setName("Alpha");
        config.getCatalogs().addAll(List.of(z, a));
        IBmModel model = mock(IBmModel.class);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.toTransactionObject(config)).thenReturn(config);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });
        for (String fqn : List.of("Configuration.Catalogs",
            "\u041a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044f.\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a\u0438"))
        {
            JsonObject result = JsonParser.parseString(SortMetadataTool.readOrder(model, config, fqn)).getAsJsonObject();
            assertEquals("list", result.get("mode").getAsString());
            assertFalse(result.has("alreadySorted"));
            assertFalse(result.has("persisted"));
            var row = result.getAsJsonArray("collections").get(0).getAsJsonObject();
            assertEquals("Configuration.catalogs", row.get("fqn").getAsString());
            assertEquals(JsonParser.parseString("[\"Zulu\",\"Alpha\"]"), row.get("order"));
            assertFalse(row.has("moved"));
            assertFalse(row.has("autosort"));
            assertEquals(List.of(z, a), config.getCatalogs());
        }
        verify(model, times(2)).executeReadonlyTask(any());
        verify(model, never()).execute(any());
        verifyNoMoreInteractions(model);
    }

    @Test
    public void listRefusesRecursiveAndMutationArgumentsBeforeProjectResolution()
    {
        new SortMetadataTool.Request("list", false, null, null, null, 1, null, null).validate(false);
        for (Map<String, String> extra : List.of(Map.of("recursive", "true"), Map.of("items", "[]"),
            Map.of("objectName", "A"), Map.of("positions", "1")))
        {
            var params = new HashMap<>(extra);
            params.putAll(Map.of("mode", "list", "projectName", "Unused", "fqn", "Configuration"));
            assertError(params, "require");
        }
    }

    @Test
    public void moveAcceptsTransportRenderedIntegerPositions()
    {
        assertEquals(2, com.ditrix.edt.mcp.server.protocol.JsonUtils.extractIntArgument(
            Map.of("positions", "2.0"), "positions", 0));
        assertError(Map.of("projectName", "Unused", "fqn", "Configuration.Catalogs",
            "mode", "move", "objectName", "A", "direction", "up", "positions", "0.0"), "positive integer positions");
    }

    @Test
    public void plannedSortMovesTheActualMetadataListAndKeepsIdentity()
    {
        var factory = MdClassFactory.eINSTANCE;
        var owner = factory.createCatalog();
        owner.setName("X");
        var z = factory.createCatalogAttribute();
        z.setName("Z");
        var a = factory.createCatalogAttribute();
        a.setName("A");
        owner.getAttributes().addAll(List.of(z, a));
        var request = new SortMetadataTool.Request("alphabetical", false, null, null, null, 1, null, null);
        var change = new SortMetadataTool.Change(owner, MdClassPackage.Literals.CATALOG__ATTRIBUTES, request, false);
        assertEquals(List.of(z, a), owner.getAttributes());
        assertEquals("Catalog.X.attributes", change.fqn);
        assertEquals(List.of(Map.of("name", "A", "from", 2, "to", 1),
            Map.of("name", "Z", "from", 1, "to", 2)), change.moved);
        change.apply();
        assertEquals(List.of(a, z), owner.getAttributes());
        assertSame(owner, a.eContainer());
        assertSame(owner, z.eContainer());
        assertTrue(new SortMetadataTool.Change(owner, MdClassPackage.Literals.CATALOG__ATTRIBUTES,
            request, false).moved.isEmpty());
    }

    @Test
    public void siblingMovesCoverEverySourceAndAnchorPositionInReferenceAndContainmentLists()
    {
        var factory = MdClassFactory.eINSTANCE;
        List<String> initial = List.of("Beta", "Alpha", "Catalog", "Zulu");
        for (boolean containment : List.of(false, true))
        {
            for (String anchor : List.of("before", "after"))
            {
                for (String name : initial)
                {
                    for (String sibling : initial)
                    {
                        if (name.equals(sibling))
                        {
                            continue;
                        }
                        var owner = containment ? factory.createCatalog() : factory.createConfiguration();
                        owner.setName("X");
                        var ref = containment ? MdClassPackage.Literals.CATALOG__ATTRIBUTES
                            : MdClassPackage.Literals.CONFIGURATION__CATALOGS;
                        var members = com.ditrix.edt.mcp.server.utils.MetadataOrdering.members(owner, ref);
                        for (String memberName : initial)
                        {
                            var member = containment ? factory.createCatalogAttribute() : factory.createCatalog();
                            member.setName(memberName);
                            members.add(member);
                        }
                        List<String> expected = new ArrayList<>(initial);
                        expected.remove(name);
                        expected.add(expected.indexOf(sibling) + (anchor.equals("after") ? 1 : 0), name);
                        var request = new SortMetadataTool.Request("move", false, null, name, null, 1,
                            anchor.equals("before") ? sibling : null, anchor.equals("after") ? sibling : null);
                        var change = new SortMetadataTool.Change(owner, ref, request, false);
                        String context = containment + " " + name + " " + anchor + " " + sibling;
                        change.apply();
                        assertEquals(context, expected, members.stream().map(m -> m.getName()).toList());
                        assertEquals(context, initial.equals(expected), change.snapshot().moved().isEmpty());
                        assertEquals(context, expected, change.snapshot().names());
                        var repeated = new SortMetadataTool.Change(owner, ref, request, false);
                        repeated.apply();
                        assertTrue(context, repeated.snapshot().moved().isEmpty());
                        var noOp = new SortMetadataTool.Outcome();
                        noOp.add(repeated);
                        assertEquals(context, true, noOp.level.get(0).get("alreadySorted"));
                        assertEquals(context, List.of(), noOp.level.get(0).get("moved"));
                        assertEquals(context, expected, noOp.level.get(0).get("order"));
                        assertEquals(context, expected, members.stream().map(m -> m.getName()).toList());
                    }
                }
            }
        }
    }

    @Test
    public void responseSnapshotsTheModelInsteadOfThePlannedOrder()
    {
        var factory = MdClassFactory.eINSTANCE;
        var config = factory.createConfiguration();
        var beta = factory.createCatalog();
        beta.setName("Beta");
        var alpha = factory.createCatalog();
        alpha.setName("Alpha");
        config.getCatalogs().addAll(List.of(beta, alpha));
        var change = new SortMetadataTool.Change(config, MdClassPackage.Literals.CONFIGURATION__CATALOGS,
            new SortMetadataTool.Request("move", false, null, "Alpha", null, 1, "Beta", null), false);
        var observed = new SortMetadataTool.Outcome();
        observed.add(change);
        assertEquals(List.of("Beta", "Alpha"), observed.level.get(0).get("order"));
        assertEquals(List.of(), observed.level.get(0).get("moved"));
        assertEquals(true, observed.level.get(0).get("alreadySorted"));
        assertTrue(observed.exports.isEmpty());
        change.apply();
        assertEquals(List.of("Alpha", "Beta"), change.snapshot().names());
        assertEquals(List.of(Map.of("name", "Alpha", "from", 2, "to", 1),
            Map.of("name", "Beta", "from", 1, "to", 2)), change.snapshot().moved());
    }

    @Test
    public void failedModelMovesCannotSilentlyProduceAPlannedSuccess()
    {
        var factory = MdClassFactory.eINSTANCE;
        var beta = factory.createCatalog();
        beta.setName("Beta");
        var alpha = factory.createCatalog();
        alpha.setName("Alpha");
        var list = spy(new org.eclipse.emf.common.util.BasicEList<com._1c.g5.v8.dt.metadata.mdclass.MdObject>(
            List.of(beta, alpha)));
        doNothing().when(list).move(anyInt(), any(com._1c.g5.v8.dt.metadata.mdclass.MdObject.class));
        doAnswer(inv -> list.get((Integer)inv.getArgument(1))).when(list).move(anyInt(), anyInt());
        var config = mock(com._1c.g5.v8.dt.metadata.mdclass.Configuration.class);
        when(config.eGet(MdClassPackage.Literals.CONFIGURATION__CATALOGS)).thenReturn(list);
        var change = new SortMetadataTool.Change(config, MdClassPackage.Literals.CONFIGURATION__CATALOGS,
            new SortMetadataTool.Request("move", false, null, "Alpha", null, 1, "Beta", null), false);
        var error = assertThrows(IllegalStateException.class, change::apply);
        assertTrue(error.getMessage(), error.getMessage().contains("expected [Alpha, Beta]"));
        assertTrue(error.getMessage(), error.getMessage().contains("model reports [Beta, Alpha]"));
        assertTrue(error.getMessage(), error.getMessage().contains("mode=list"));
    }

    @Test
    public void responseChecksTheCommittedModelInAFreshReadTransaction()
    {
        var factory = MdClassFactory.eINSTANCE;
        var config = factory.createConfiguration();
        var alpha = factory.createCatalog();
        alpha.setName("Alpha");
        var beta = factory.createCatalog();
        beta.setName("Beta");
        config.getCatalogs().addAll(List.of(alpha, beta));
        IBmModel model = mock(IBmModel.class);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.toTransactionObject(config)).thenReturn(config);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });
        var outcome = new SortMetadataTool.Outcome();
        outcome.observedOrders.put("Configuration.catalogs", List.of("Alpha", "Beta"));
        outcome.level.add(new HashMap<>(Map.of("fqn", "Configuration.catalogs")));
        assertNull(SortMetadataTool.refreshCommittedOrder(model, config, outcome));
        assertEquals(List.of("Alpha", "Beta"), outcome.level.get(0).get("order"));
        config.getCatalogs().move(0, 1);
        String error = SortMetadataTool.refreshCommittedOrder(model, config, outcome);
        assertTrue(error, error.contains("model reports [Beta, Alpha]"));
        assertTrue(error, error.contains("mode=list"));
        verify(model, times(2)).executeReadonlyTask(any());
        verify(model, never()).execute(any());
    }

    @Test
    public void governedManualModesAreRefusedBeforeTheFirstMoveAndAlphabeticalUsesEdtComparator()
    {
        var factory = MdClassFactory.eINSTANCE;
        var config = factory.createConfiguration();
        for (String name : List.of("A_", "A1", "A2"))
        {
            var member = factory.createCatalog();
            member.setName(name);
            config.getCatalogs().add(member);
        }
        var ref = MdClassPackage.Literals.CONFIGURATION__CATALOGS;
        var policy = new com.ditrix.edt.mcp.server.utils.MetadataAutoSort.Policy(true, false, false, "topObjects");
        for (var request : List.of(
            new SortMetadataTool.Request("explicit", false, List.of("A2"), null, null, 1, null, null),
            new SortMetadataTool.Request("move", false, null, "A2", null, 1, "A1", null)))
        {
            var error = assertThrows(IllegalArgumentException.class,
                () -> new SortMetadataTool.Change(config, ref, request, false, policy));
            assertTrue(error.getMessage(), error.getMessage().contains("Configuration.catalogs"));
            assertTrue(error.getMessage(), error.getMessage().contains("revert"));
            assertEquals(List.of("A_", "A1", "A2"), config.getCatalogs().stream().map(m -> m.getName()).toList());
        }
        var change = new SortMetadataTool.Change(config, ref,
            new SortMetadataTool.Request("alphabetical", false, null, null, null, 1, null, null), false, policy);
        change.apply();
        assertEquals(List.of("A2", "A1", "A_"), change.snapshot().names());
        var noOp = new SortMetadataTool.Change(config, ref,
            new SortMetadataTool.Request("alphabetical", false, null, null, null, 1, null, null), false, policy);
        var outcome = new SortMetadataTool.Outcome();
        outcome.add(noOp);
        assertEquals(1, outcome.maintainedCount);
        assertEquals("descending, plain", ((Map<?, ?>)outcome.level.get(0).get("autosort")).get("order"));
        assertEquals("all top-level lists", ((Map<?, ?>)outcome.level.get(0).get("autosort")).get("setting"));
        assertEquals(true, outcome.level.get(0).get("alreadySorted"));
    }

    @Test
    public void listReportsAutosortWithoutChangingTheObservedOrder()
    {
        var config = MdClassFactory.eINSTANCE.createConfiguration();
        IBmModel model = mock(IBmModel.class);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.toTransactionObject(config)).thenReturn(config);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });
        var policy = new com.ditrix.edt.mcp.server.utils.MetadataAutoSort.Policy(true, false, true, "top/catalogs");
        var result = JsonParser.parseString(SortMetadataTool.readOrder(model, config, "Configuration.Catalogs",
            ref -> policy)).getAsJsonObject();
        var row = result.getAsJsonArray("collections").get(0).getAsJsonObject();
        assertEquals(JsonParser.parseString("[]"), row.get("order"));
        assertEquals("descending, natural", row.getAsJsonObject("autosort").get("order").getAsString());
        assertTrue(result.get("message").getAsString().contains("ask the user"));
        verify(model, never()).execute(any());
    }

    @Test
    public void maintainedNoOpAndManualRefusalsNeverEnterAWriteTransaction()
    {
        var config = MdClassFactory.eINSTANCE.createConfiguration();
        for (String name : List.of("Alpha", "Beta"))
        {
            var item = MdClassFactory.eINSTANCE.createCatalog();
            item.setName(name);
            config.getCatalogs().add(item);
        }
        IBmModel model = mock(IBmModel.class);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.toTransactionObject(config)).thenReturn(config);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> ((IBmTask<?>)inv.getArgument(0)).execute(tx, null));
        var policy = new com.ditrix.edt.mcp.server.utils.MetadataAutoSort.Policy(true, true, true, "topObjects");
        var outcome = SortMetadataTool.reorder(model, config, "Configuration.Catalogs",
            new SortMetadataTool.Request("alphabetical", false, null, null, null, 1, null, null), ref -> policy);
        assertEquals(true, outcome.level.get(0).get("alreadySorted"));
        assertEquals(List.of(), outcome.level.get(0).get("moved"));
        assertTrue(outcome.exports.isEmpty());
        var block = (Map<?, ?>)outcome.level.get(0).get("autosort");
        assertEquals("Configuration.catalogs", block.get("governs"));
        assertTrue(block.get("userAction").toString().contains("ask the user"));
        for (var request : List.of(
            new SortMetadataTool.Request("explicit", false, List.of("Beta"), null, null, 1, null, null),
            new SortMetadataTool.Request("move", false, null, "Alpha", null, 1, null, "Beta")))
        {
            var error = assertThrows(com.ditrix.edt.mcp.server.utils.MetadataAutoSort.ManualOrderRefusal.class,
                () -> SortMetadataTool.reorder(model, config, "Configuration.Catalogs", request, ref -> policy));
            var response = JsonParser.parseString(com.ditrix.edt.mcp.server.utils.MetadataAutoSort.refusalJson(error)).getAsJsonObject();
            assertFalse(response.get("success").getAsBoolean());
            assertEquals(block.get("governs"), response.getAsJsonObject("autosort").get("governs").getAsString());
            assertTrue(response.get("message").getAsString().contains("do not change this setting yourself"));
        }
        verify(model, never()).execute(any());
    }

    @Test
    public void recursiveAutosortMaintenanceSummariesAreCappedWithoutHidingTheCount()
    {
        var factory = MdClassFactory.eINSTANCE;
        var owner = factory.createCatalog();
        owner.setName("X");
        for (int i = 0; i < 105; i++)
        {
            var section = factory.createCatalogTabularSection();
            section.setName("Line" + i);
            owner.getTabularSections().add(section);
        }
        List<SortMetadataTool.Change> changes = new ArrayList<>();
        SortMetadataTool.collect(owner, com.ditrix.edt.mcp.server.utils.MetadataOrdering.collections(owner),
            new SortMetadataTool.Request("alphabetical", true, null, null, null, 1, null, null), false,
            changes, Collections.newSetFromMap(new IdentityHashMap<>()), ref ->
                new com.ditrix.edt.mcp.server.utils.MetadataAutoSort.Policy(ref.getName().equals("attributes"),
                    true, true, "subordinate/attributes"));
        var outcome = new SortMetadataTool.Outcome();
        // Snapshot the unchanged attribute collections without applying the section-name plan.
        changes.stream().filter(change -> change.autosort.active()).forEach(outcome::add);
        assertEquals(106, outcome.maintainedCount);
        assertEquals(100, outcome.maintained.size());
        assertEquals(105, outcome.descendantCount);
        assertTrue(outcome.exports.isEmpty());
    }

    @Test
    public void recursivePlanVisitsNestedAttributesButNotReferencedContentTargets()
    {
        var factory = MdClassFactory.eINSTANCE;
        var config = factory.createConfiguration();
        var catalog = factory.createCatalog();
        catalog.setName("X");
        var section = factory.createCatalogTabularSection();
        section.setName("Lines");
        catalog.getTabularSections().add(section);
        config.getCatalogs().add(catalog);
        for (boolean recursive : new boolean[] {false, true})
        {
            List<SortMetadataTool.Change> changes = new ArrayList<>();
            SortMetadataTool.collect(config, List.of(MdClassPackage.Literals.CONFIGURATION__CATALOGS),
                new SortMetadataTool.Request("alphabetical", recursive, null, null, null, 1, null, null),
                false, changes, Collections.newSetFromMap(new IdentityHashMap<>()));
            assertEquals(recursive, changes.stream().anyMatch(c -> c.fqn.equals("Catalog.X.TabularSection.Lines.attributes")));
            assertEquals(1, changes.stream().filter(c -> !c.descendant).count());
        }
        var subsystem = factory.createSubsystem();
        subsystem.setName("Sales");
        subsystem.getContent().add(catalog);
        List<SortMetadataTool.Change> changes = new ArrayList<>();
        SortMetadataTool.collect(subsystem, List.of(MdClassPackage.Literals.SUBSYSTEM__CONTENT),
            new SortMetadataTool.Request("alphabetical", true, null, null, null, 1, null, null),
            false, changes, Collections.newSetFromMap(new IdentityHashMap<>()));
        assertEquals(1, changes.size());
    }

    @Test
    public void contractDeclaresAllModesParametersAndStructuredPositions()
    {
        assertEquals("sort_metadata", tool.getName());
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.getDescription().endsWith("get_tool_guide('sort_metadata')."));
        assertTrue(tool.getDescription().contains("DIMENSIONS"));
        assertTrue(tool.getDescription().contains("ORDER BY"));
        assertTrue(tool.getDescription().contains("command interface"));
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals(JsonParser.parseString("[\"alphabetical\",\"explicit\",\"move\",\"list\"]"),
            schema.getAsJsonObject("properties").getAsJsonObject("mode").get("enum"));
        assertEquals(2, schema.getAsJsonArray("required").size());
        for (String param : List.of("projectName", "fqn", "mode", "recursive", "items", "objectName", "direction", "positions", "before", "after"))
        {
            assertTrue(param, schema.getAsJsonObject("properties").has(param));
        }
        JsonObject output = JsonParser.parseString(tool.getOutputSchema()).getAsJsonObject().getAsJsonObject("properties");
        for (String key : List.of("collections", "subCollections", "sensitiveCollections", "alreadySorted", "persisted", "truncated"))
        {
            assertTrue(key, output.has(key));
        }
    }

    @Test
    public void missingRequiredAndInvalidModeFailBeforeProjectResolution()
    {
        assertError(Map.of(), "projectName");
        assertError(Map.of("projectName", "Unused"), "fqn");
        assertError(Map.of("projectName", "Unused", "fqn", "Configuration", "mode", "bogus"), "bogus");
    }

    @Test
    public void incompatibleParametersAreActionableAndDoNotResolveAProject()
    {
        for (Map<String, String> extra : List.of(
            Map.of("mode", "explicit"), Map.of("items", "[\"A\"]"),
            Map.of("mode", "move", "objectName", "A"),
            Map.of("mode", "move", "objectName", "A", "before", "B", "after", "C"),
            Map.of("mode", "move", "objectName", "A", "direction", "left"),
            Map.of("mode", "move", "objectName", "A", "direction", "up", "positions", "0"),
            Map.of("mode", "move", "objectName", "A", "direction", "up", "positions", "1.5"),
            Map.of("mode", "move", "objectName", "A", "before", "B", "positions", "1"),
            Map.of("mode", "move", "recursive", "true", "objectName", "A", "direction", "up"),
            Map.of("mode", "explicit", "recursive", "true", "items", "[]"),
            Map.of("objectName", "A")))
        {
            Map<String, String> params = new HashMap<>(extra);
            params.put("projectName", "Unused");
            params.put("fqn", "Configuration.Catalogs");
            String error = assertError(params, "mode");
            assertTrue(error, error.contains("require"));
        }
    }

    @Test
    public void validRequestsCoverDefaultsAndEmptyExplicit()
    {
        new SortMetadataTool.Request("alphabetical", true, null, null, null, 1, null, null).validate(false);
        new SortMetadataTool.Request("explicit", false, List.of(), null, null, 1, null, null).validate(false);
        new SortMetadataTool.Request("move", false, null, "A", "up", 2, null, null).validate(true);
        new SortMetadataTool.Request("move", false, null, "A", null, 1, "B", null).validate(false);
    }

    @Test
    public void insertPositionSchemaAndInvalidValuesAreGuardedBeforeProjectResolution()
    {
        CreateMetadataTool create = new CreateMetadataTool();
        assertTrue(create.getInputSchema().contains("insertPosition"));
        var compact = com.ditrix.edt.mcp.server.protocol.InputSchemaCompactor.compact("create_metadata",
            JsonParser.parseString(create.getInputSchema()));
        assertTrue(compact.getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("insertPosition")
            .get("description").getAsString().contains("1-based"));
        for (String value : List.of("1.5", "bad", "2147483648"))
        {
            JsonObject response = JsonParser.parseString(create.executeOnUiThread(Map.of(
                "projectName", "Unused", "fqn", "Catalog.New", "insertPosition", value))).getAsJsonObject();
            assertFalse(response.toString(), response.get("success").getAsBoolean());
            assertTrue(response.toString(), response.get("error").getAsString().contains("insertPosition"));
            assertTrue(response.toString(), response.get("error").getAsString().contains(value));
        }
    }

    @Test
    public void nonpositiveTransportIndexesReachCollectionRangePreflight()
    {
        IllegalStateException boundary = new IllegalStateException("Reached collection range preflight");
        CreateMetadataTool create = new CreateMetadataTool()
        {
            @Override
            protected ProjectContext resolveProjectAndScope(String projectName, String fqn)
            {
                assertEquals("Unused", projectName);
                assertEquals("Catalog.New", fqn);
                throw boundary;
            }
        };
        for (String text : List.of("0.0", "-1.0", "-0.0"))
        {
            assertSame(boundary, assertThrows(IllegalStateException.class, () -> create.executeOnUiThread(Map.of(
                "projectName", "Unused", "fqn", "Catalog.New", "insertPosition", text))));
        }
    }

    private String assertError(Map<String, String> params, String contains)
    {
        JsonObject response = JsonParser.parseString(tool.executeOnUiThread(params)).getAsJsonObject();
        assertFalse(response.toString(), response.get("success").getAsBoolean());
        String error = response.get("error").getAsString();
        assertTrue(error, error.contains(contains));
        return error;
    }
}
