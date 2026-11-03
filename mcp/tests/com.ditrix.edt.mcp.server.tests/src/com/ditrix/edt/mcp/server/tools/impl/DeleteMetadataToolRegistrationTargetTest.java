/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.EDS;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.RU_EDS;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.RU_TABLE;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.TABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A Russian-token delete keeps echoing the caller's normalized address in its response, preview and
 * texts, as before #448; only the post-export registration check compares EDT's canonical address.
 */
public class DeleteMetadataToolRegistrationTargetTest
{
    private static final String RU_ATTRIBUTE = "\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442";

    private final List<String> verifiedTargets = new ArrayList<>();
    private IProject project;
    private IMdRefactoringService service;
    private BmModelResolver.Resolution resolution;
    private DeleteMetadataTool tool;

    @Before
    public void setUp()
    {
        project = mock(IProject.class);
        when(project.getName()).thenReturn("TestConfiguration");
        IBmModelManager manager = mock(IBmModelManager.class);
        when(manager.getModel(project)).thenReturn(mock(IBmModel.class));
        resolution = BmModelResolver.resolve(project, manager);
        service = mock(IMdRefactoringService.class);
        when(service.createMdObjectDeleteRefactoring(anyCollection())).thenReturn(mock(IRefactoring.class));
        tool = new DeleteMetadataTool((name, preview) -> ConsentDecision.ALLOW, (name, timeout) -> null,
            (targetProject, fqn) -> true, base -> Collections.emptyList(),
            (projectName, file, container, target) -> {
                verifiedTargets.add(target);
                return DeleteMetadataTool.RegistrationState.ABSENT;
            });
    }

    @Test
    public void russianMemberPreviewEchoesTheCallersAddressAsBefore()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Cfg");
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods");
        var weight = MdClassFactory.eINSTANCE.createCatalogAttribute();
        weight.setName("Weight");
        goods.getAttributes().add(weight);
        config.getCatalogs().add(goods);
        String normFqn = MetadataTypeUtils.normalizeFqn("Catalog.Goods." + RU_ATTRIBUTE + ".Weight");
        assertTrue("the echo under test keeps the Russian member token: " + normFqn, normFqn.contains(RU_ATTRIBUTE));
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, normFqn);
        assertNotNull(node);

        String json = tool.prepareResolvedDelete(project, node, normFqn, "Catalog.Goods", false, false, service,
            resolution);

        JsonObject preview = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(json, "preview", preview.get("action").getAsString());
        assertEquals(normFqn, preview.get("fqn").getAsString());
        assertFalse(json, json.contains("Catalog.Goods.Attribute.Weight"));
    }

    @Test
    public void russianTablePreviewEchoesTheCallersAddress()
    {
        var fixtures = new ExternalDataSourceFixtures();
        String normFqn = MetadataTypeUtils.normalizeFqn(RU_EDS + ".Eds." + RU_TABLE + ".T");
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(fixtures.config, normFqn);
        assertNotNull(node);

        String json = tool.prepareResolvedDelete(project, node, normFqn, EDS, false, false, service, resolution);

        assertEquals(normFqn, JsonParser.parseString(json).getAsJsonObject().get("fqn").getAsString());
        assertFalse(json, json.contains(TABLE));
    }

    @Test
    public void forcedRussianTableDeleteEchoesTheCallerAndVerifiesTheCanonicalRegistration()
    {
        var fixtures = new ExternalDataSourceFixtures();
        String normFqn = MetadataTypeUtils.normalizeFqn(RU_EDS + ".Eds." + RU_TABLE + ".T");
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(fixtures.config, normFqn);
        assertNotNull(node);

        String raw = tool.prepareResolvedDelete(project, node, normFqn, EDS, true, true, service, resolution);
        JsonObject executed = JsonParser.parseString(raw).getAsJsonObject();
        assertEquals(raw, "executed", executed.get("action").getAsString());
        assertEquals(normFqn, executed.get("fqn").getAsString());

        JsonObject result = JsonParser.parseString(
            tool.refreshAfterExportAwait(Map.of("projectName", "TestConfiguration"), raw, true)).getAsJsonObject();

        assertEquals(List.of(TABLE), verifiedTargets);
        assertEquals(normFqn, result.get("fqn").getAsString());
        assertFalse("the internal member never reaches the caller: " + result,
            result.has("registrationTarget"));
        assertFalse(result.toString(), result.has("registeringContainer"));
    }

    @Test
    public void englishForcedDeleteCarriesNoInternalMember()
    {
        var fixtures = new ExternalDataSourceFixtures();
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(fixtures.config, TABLE);
        assertNotNull(node);

        String raw = tool.prepareResolvedDelete(project, node, TABLE, EDS, true, true, service, resolution);
        assertFalse(raw, raw.contains("registrationTarget"));

        JsonObject result = JsonParser.parseString(
            tool.refreshAfterExportAwait(Map.of("projectName", "TestConfiguration"), raw, true)).getAsJsonObject();

        assertEquals(List.of(TABLE), verifiedTargets);
        assertEquals(TABLE, result.get("fqn").getAsString());
    }
}
