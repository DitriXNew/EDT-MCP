/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/** Pins template discovery through the same owner read path as the tool call. */
public class GetMetadataDetailsOwnedTemplatesTest
{
    private static final MdClassFactory FACTORY = MdClassFactory.eINSTANCE;

    @Test
    public void documentRendersOnlyItsOwnTemplatesWithResolvedAddressesAndLocalizedSynonyms()
    {
        var config = FACTORY.createConfiguration();
        var document = FACTORY.createDocument();
        document.setName("Shipment"); //$NON-NLS-1$
        config.getDocuments().add(document);
        var form = template("Invoice", "Invoice", "Счёт", TemplateType.SPREADSHEET_DOCUMENT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var text = template("Query", "Query", "Запрос", TemplateType.TEXT_DOCUMENT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        document.getTemplates().addAll(List.of(form, text));

        var catalog = FACTORY.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        catalog.getTemplates().add(template("Invoice", "Only catalog", "Только справочник", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            TemplateType.SPREADSHEET_DOCUMENT));
        config.getCatalogs().add(catalog);
        var common = FACTORY.createCommonTemplate();
        common.setName("Global"); //$NON-NLS-1$
        config.getCommonTemplates().add(common);

        MetadataScope scope = MetadataScope.ofConfiguration(config);
        String full = render(scope, "Документ.Shipment", true, "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(full, full.contains("### Templates\n")); //$NON-NLS-1$
        assertTrue(full, full.contains("| Name | Synonym | Type | FQN |")); //$NON-NLS-1$
        String invoice = addressInRow(full, "Invoice", "Счёт", "SpreadsheetDocument"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String query = addressInRow(full, "Query", "Запрос", "TextDocument"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Document.Shipment.Template.Invoice", invoice); //$NON-NLS-1$
        assertEquals("Document.Shipment.Template.Query", query); //$NON-NLS-1$
        assertSame(form, MetadataNodeResolver.resolveExisting(config, invoice).object);
        assertSame(text, MetadataNodeResolver.resolveExisting(config, query).object);
        assertFalse(full, full.contains("Catalog.Goods.Template.")); //$NON-NLS-1$
        assertFalse(full, full.contains("CommonTemplate.Global")); //$NON-NLS-1$

        String basic = render(scope, "Document.Shipment", false, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(basic, basic.contains("| Invoice | Invoice |")); //$NON-NLS-1$
        assertFalse(basic, basic.contains("| FQN |")); //$NON-NLS-1$
    }

    @Test
    public void ownedTemplateIsDiscoveredForRegistersAndExternalOwnersButEmptyOwnersHaveNoTable()
    {
        var config = FACTORY.createConfiguration();
        var register = FACTORY.createInformationRegister();
        register.setName("Prices"); //$NON-NLS-1$
        config.getInformationRegisters().add(register);
        var template = template("Summary", "Price sheet", "Цены", TemplateType.SPREADSHEET_DOCUMENT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        register.getTemplates().add(template);
        var empty = FACTORY.createCatalog();
        empty.setName("Empty"); //$NON-NLS-1$
        config.getCatalogs().add(empty);

        MetadataScope scope = MetadataScope.ofConfiguration(config);
        String registerDetails = render(scope, "InformationRegister.Prices", true, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        String registerAddress = addressInRow(registerDetails, "Summary", "Price sheet", "SpreadsheetDocument"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertSame(template, MetadataNodeResolver.resolveExisting(scope, registerAddress).object);
        String emptyDetails = render(scope, "Catalog.Empty", true, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(emptyDetails, emptyDetails.contains("### Templates")); //$NON-NLS-1$

        var processor = FACTORY.createExternalDataProcessor();
        processor.setName("Export"); //$NON-NLS-1$
        var owned = template("Body", "Body", "Форма", TemplateType.SPREADSHEET_DOCUMENT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        processor.getTemplates().add(owned);
        MetadataScope external = MetadataScopeTestFixtures.externalObjects(processor);
        String md = render(external, "ExternalDataProcessor.Export", true, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        String externalAddress = addressInRow(md, "Body", "Body", "SpreadsheetDocument"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("ExternalDataProcessor.Export.Template.Body", externalAddress); //$NON-NLS-1$
        assertSame(owned, MetadataNodeResolver.resolveExisting(external, externalAddress).object);
    }

    private static Template template(String name, String english, String russian, TemplateType type)
    {
        Template template = FACTORY.createTemplate();
        template.setName(name);
        template.getSynonym().put("en", english); //$NON-NLS-1$
        template.getSynonym().put("ru", russian); //$NON-NLS-1$
        template.setTemplateType(type);
        return template;
    }

    private static String addressInRow(String details, String name, String synonym, String type)
    {
        String start = "| " + name + " | " + synonym + " | " + type + " | "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String row = details.lines().filter(line -> line.startsWith(start)).findFirst().orElse(null);
        assertNotNull(details, row);
        return row.substring(start.length(), row.lastIndexOf(" |")); //$NON-NLS-1$
    }

    private static String render(MetadataScope scope, String fqn, boolean full, String language)
    {
        var context = new GetMetadataDetailsTool.RenderContext(null, scope, null, language, full, false, false, 0);
        StringBuilder markdown = new StringBuilder();
        List<String[]> failures = new ArrayList<>();
        VendorSupportGuard.setServiceForTests(() -> null);
        try
        {
            new GetMetadataDetailsTool().processFqn(fqn, markdown, failures, context);
        }
        finally
        {
            VendorSupportGuard.setServiceForTests(null);
        }
        assertEquals(fqn + " must render successfully", 0, failures.size()); //$NON-NLS-1$
        return markdown.toString();
    }
}
