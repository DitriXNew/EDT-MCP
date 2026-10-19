/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com.ditrix.edt.mcp.server.utils.DcsStructureReader;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.ditrix.edt.mcp.server.utils.TemplateContentSupport;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/** Exercises the real plain-view dispatch: an unhandled member must never render its owner (#731). */
public class GetMetadataDetailsMemberAddressTest
{
    private static final String RU_CATALOG = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a";
    private static final String RU_ATTRIBUTE = "\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442";
    private static final String RU_SUBSYSTEM = "\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u0430";
    private static final String RETRY = "This template's content could not be read right now "
        + "(the project model is not ready); retry the same call once the project is ready.";
    /** Silk spelled with ye (stored) and with yo (requested). */
    private static final String SILK_YE = "\u0428\u0435\u043b\u043a";
    private static final String SILK_YO = "\u0428\u0451\u043b\u043a";
    private static final String NOT_FOUND = "Object not found - use get_metadata_objects to list valid FQNs";

    private final Configuration config = named(MdClassFactory.eINSTANCE.createConfiguration(), "Cfg");
    private final Catalog goods = named(MdClassFactory.eINSTANCE.createCatalog(), "Goods");

    public GetMetadataDetailsMemberAddressTest()
    {
        config.getCatalogs().add(goods);
        goods.getAttributes().add(named(MdClassFactory.eINSTANCE.createCatalogAttribute(), "Weight"));
        var lines = named(MdClassFactory.eINSTANCE.createCatalogTabularSection(), "Lines");
        lines.getAttributes().add(named(MdClassFactory.eINSTANCE.createTabularSectionAttribute(), "Quantity"));
        goods.getTabularSections().add(lines);
        goods.getTemplates().add(named(MdClassFactory.eINSTANCE.createTemplate(), "Invoice"));
    }

    @Test
    public void testExistingAttributeIsAnErrorRowInCompactAndFullViews()
    {
        for (boolean full : new boolean[] { false, true })
        {
            assertMember(render(scope(), "Catalog.Goods.Attribute.Weight", full, false, null), "Catalog.Goods");
        }
    }

    @Test
    public void testMissingAttributeIsNotItsOwner()
    {
        assertMissing(render("Catalog.Goods.Attribute.NoSuch"));
    }

    @Test
    public void testRussianTypeAndMemberTokensHaveTheSameReason()
    {
        Rendered english = render("Catalog.Goods.Attribute.Weight");
        Rendered russian = render(RU_CATALOG + ".Goods." + RU_ATTRIBUTE + ".Weight");
        assertMember(russian, "Catalog.Goods");
        assertEquals(english.failures.get(0)[1], russian.failures.get(0)[1]);
    }

    @Test
    public void testMissingRussianMemberIsNotItsOwner()
    {
        assertMissing(render(RU_CATALOG + ".Goods." + RU_ATTRIBUTE + ".NoSuch"));
    }

    @Test
    public void testRussianProgrammaticNamesResolveButSynonymsDoNot()
    {
        String name = "\u0422\u043e\u0432\u0430\u0440\u044b";
        String attributeName = "\u0412\u0435\u0441";
        Catalog catalog = named(MdClassFactory.eINSTANCE.createCatalog(), name);
        var attribute = named(MdClassFactory.eINSTANCE.createCatalogAttribute(), attributeName);
        attribute.getSynonym().put("en", "Weight display name");
        catalog.getAttributes().add(attribute);
        config.getCatalogs().add(catalog);
        for (String prefix : new String[] { "Catalog." + name + ".Attribute.",
            RU_CATALOG + "." + name + "." + RU_ATTRIBUTE + "." })
        {
            assertMember(render(prefix + attributeName), "Catalog." + name);
            assertMissing(render(prefix + "Weight display name"));
        }
    }

    @Test
    public void testTabularSectionAndItsAttributeAreMembers()
    {
        assertMember(render("Catalog.Goods.TabularSection.Lines"), "Catalog.Goods");
        assertMember(render("Catalog.Goods.TabularSection.Lines.Attribute.Quantity"), "Catalog.Goods");
        assertMissing(render("Catalog.Goods.TabularSection.Lines.Attribute.NoSuch"));
    }

    @Test
    public void testRegisterDimensionIsAMember()
    {
        var register = named(MdClassFactory.eINSTANCE.createInformationRegister(), "Stock");
        register.getDimensions().add(named(MdClassFactory.eINSTANCE.createInformationRegisterDimension(), "Warehouse"));
        config.getInformationRegisters().add(register);
        assertMember(render("InformationRegister.Stock.Dimension.Warehouse"), "InformationRegister.Stock");
        assertMissing(render("InformationRegister.Stock.Dimension.NoSuch"));
    }

    @Test
    public void testOwnedSpreadsheetTemplateIsAMember()
    {
        assertMember(render("Catalog.Goods.Template.Invoice"), "Catalog.Goods");
        assertMissing(render("Catalog.Goods.Template.NoSuch"));
    }

    @Test
    public void testUnknownKindIncompleteAndTrailingSegmentsNeverRenderTheOwner()
    {
        for (String fqn : new String[] { "Catalog.Goods.Unknown.Weight", "Catalog.Goods.Attribute",
            "Catalog.Goods.Attribute.Weight.Extra", "Catalog.Goods.", "Catalog.Goods.Attribute.Weight.",
            "Catalog.Goods..", "Catalog.Goods.Attribute.Weight..", "Catalog.Goods.Attribute.",
            "Catalog.Goods..Weight" })
        {
            assertMissing(render(fqn));
        }
    }

    @Test
    public void testMissingOwnerIsNotCalledAnExistingMember()
    {
        assertMissing(render("Catalog.NoSuch.Attribute.Weight"));
    }

    @Test
    public void testExternalObjectMembersUseTheirOwnScope()
    {
        var processor = named(MdClassFactory.eINSTANCE.createExternalDataProcessor(), "Importer");
        processor.getAttributes().add(named(MdClassFactory.eINSTANCE.createDataProcessorAttribute(), "Source"));
        MetadataScope external = MetadataScopeTestFixtures.externalObjects(processor);
        assertMember(render(external, "ExternalDataProcessor.Importer.Attribute.Source", false, false, null),
            "ExternalDataProcessor.Importer");
        assertMissing(render(external, "ExternalDataProcessor.Importer.Attribute.NoSuch", false, false, null));
        Rendered wrongProject = render("ExternalDataProcessor.Importer.Attribute.Source");
        assertTrue(wrongProject.reason(), wrongProject.reason().startsWith(NOT_FOUND));
        assertTrue(wrongProject.reason(), wrongProject.reason().contains("external-objects"));
        assertEquals("", wrongProject.body);
    }

    @Test
    public void testExternalScopeKeepsItsAddressingHint()
    {
        Rendered result = render(MetadataScopeTestFixtures.externalObjectsWithBase(config),
            "Catalog.Goods.Attribute.Weight", false, false, null);
        assertTrue(result.reason(), result.reason().startsWith(NOT_FOUND));
        assertTrue(result.reason(), result.reason().contains("EXTERNAL-OBJECTS"));
        assertEquals("", result.body);
    }

    @Test
    public void testXdtoMemberAddressesRouteToThePackageContentView()
    {
        config.getXDTOPackages().add(named(MdClassFactory.eINSTANCE.createXDTOPackage(), "Transport"));
        for (String fqn : new String[] { "XDTOPackage.Transport.ObjectType.Message",
            "XDTOPackage.Transport.Property.Version", "XDTOPackage.Transport.ObjectType.Message.Property.Id" })
        {
            Rendered result = render(fqn);
            assertEquals("", result.body);
            assertEquals("This is an XDTO member address; read package 'XDTOPackage.Transport' to inspect its content.",
                result.reason());
            assertTrue(result.markdown(), result.markdown().contains("| " + fqn + " | ERROR |"));
            assertFalse(result.reason(), result.reason().contains("assignable"));
        }
        assertMissing(render("XDTOPackage.NoSuch.ObjectType.Message"));
        assertMissing(render("XDTOPackage.Transport.ObjectType.Message."));
    }

    @Test
    public void testTwoPartCatalogStillRendersItsDetailsInBothLanguages()
    {
        for (String fqn : new String[] { "Catalog.Goods", RU_CATALOG + ".Goods" })
        {
            Rendered result = render(fqn);
            assertTrue(result.markdown(), result.failures.isEmpty());
            assertTrue(result.body, result.body.contains("## Catalog: Goods"));
            assertTrue(result.body, result.body.contains("**Origin:** core"));
        }
    }

    @Test
    public void testTwoPartCommonTemplateStillRendersItsOwnDetails()
    {
        config.getCommonTemplates().add(named(MdClassFactory.eINSTANCE.createCommonTemplate(), "PrintForm"));
        Rendered result = render("CommonTemplate.PrintForm");
        assertTrue(result.markdown(), result.failures.isEmpty());
        assertTrue(result.body, result.body.contains("## CommonTemplate: PrintForm"));
    }

    @Test
    public void testAssignableMemberViewStillRendersItsProperties()
    {
        for (String fqn : new String[] { "Catalog.Goods.Attribute.Weight",
            RU_CATALOG + ".Goods." + RU_ATTRIBUTE + ".Weight" })
        {
            Rendered result = render(scope(), fqn, false, true, null);
            assertTrue(result.markdown(), result.failures.isEmpty());
            assertTrue(result.body, result.body.contains("## Assignable properties:"));
            assertFalse(result.body, result.body.contains("## Catalog: Goods"));
        }
    }

    @Test
    public void testConfigurationRootKeepsItsOwnRoute()
    {
        Rendered result = render("Configuration.Cfg");
        assertEquals("", result.body);
        assertTrue(result.reason(), result.reason().contains("get_configuration_properties"));
    }

    @Test
    public void testFormPathKeepsItsOwnNoContentReasonWithoutABmModel()
    {
        Rendered result = render("Catalog.Goods.Form.ItemForm");
        assertEquals("", result.body);
        assertTrue(result.reason(), result.reason().contains("the form has no editable content model"));
    }

    @Test
    public void testFormMemberAndHandlerKeepTheirOwnReasons()
    {
        Rendered member = render("Catalog.Goods.Form.ItemForm.Attribute.Weight");
        assertEquals("", member.body);
        assertTrue(member.reason(), member.reason().contains("this addresses a form MEMBER"));
        Rendered handler = render("Catalog.Goods.Form.ItemForm.Handler.OnOpen");
        assertEquals("", handler.body);
        assertTrue(handler.reason(), handler.reason().contains("event HANDLER"));
    }

    @Test
    public void testPredefinedItemStillRendersOnlyThatItem()
    {
        PredefinedWriter.create(goods, "Basic", new PredefinedWriter.ItemProps(), false);
        Rendered result = render("Catalog.Goods.Predefined.Basic");
        assertTrue(result.markdown(), result.failures.isEmpty());
        assertTrue(result.body, result.body.contains("## Predefined item: Catalog.Goods.Predefined.Basic"));
        assertFalse(result.body, result.body.contains("## Catalog: Goods"));
    }

    @Test
    public void testNestedSubsystemChainStillRendersTheChildInBothLanguages()
    {
        Subsystem parent = named(MdClassFactory.eINSTANCE.createSubsystem(), "Sales");
        Subsystem child = named(MdClassFactory.eINSTANCE.createSubsystem(), "Orders");
        parent.getSubsystems().add(child);
        child.setParentSubsystem(parent);
        config.getSubsystems().add(parent);
        for (String fqn : new String[] { "Subsystem.Sales.Subsystem.Orders",
            RU_SUBSYSTEM + ".Sales." + RU_SUBSYSTEM + ".Orders" })
        {
            Rendered result = render(fqn);
            assertTrue(result.markdown(), result.failures.isEmpty());
            assertTrue(result.body, result.body.contains("## Subsystem: Orders"));
            assertFalse(result.body, result.body.contains("## Subsystem: Sales"));
        }
        assertTrue(render("Subsystem.Sales.Attribute.Weight").reason().startsWith("Not a subsystem address."));
        assertTrue(render("Subsystem.Sales.Subsystem.NoSuch").reason().startsWith("Subsystem not found"));
    }

    @Test
    public void testCommandInterfaceKeepsItsOwnReasonWithoutABmModel()
    {
        for (String fqn : new String[] { "Subsystem.Sales.CommandInterface",
            "Subsystem.Sales.Subsystem.Orders.CommandInterface", "Configuration.MainSectionCommandInterface" })
        {
            Rendered result = render(fqn);
            assertEquals("", result.body);
            assertEquals("the project's BM model is not available", result.reason());
        }
        assertTrue(render("Catalog.Goods.CommandInterface").reason().contains("only a subsystem"));
    }

    @Test
    public void testOwnedTextTemplateRendersBeforeTheMemberCheck()
    {
        mockTemplate("Text");
        IBmModel model = mock(IBmModel.class);
        // Stub only the cross-model read; the real FQN dispatch and node resolution still run.
        String text = TemplateContentSupport.renderText("Catalog.Goods.Template.Text", "Hello\n");
        when(model.executeAndRollback(any())).thenReturn(text);
        Rendered result = render(scope(), "Catalog.Goods.Template.Text", false, false, model);
        assertTrue(result.markdown(), result.failures.isEmpty());
        assertEquals(text + "\n---\n\n", result.body);
        verify(model, times(1)).executeAndRollback(any());
    }

    @Test
    public void testOwnedDcsTemplateRendersBeforeTheMemberCheck()
    {
        mockTemplate("Schema");
        IBmModel model = mock(IBmModel.class);
        String dcs = DcsStructureReader.render("Catalog.Goods.Template.Schema",
            DcsFactory.eINSTANCE.createDataCompositionSchema(), "en");
        // The text-template read is inapplicable, then the DCS read returns its document.
        when(model.executeAndRollback(any())).thenReturn(null, dcs);
        Rendered result = render(scope(), "Catalog.Goods.Template.Schema", false, false, model);
        assertTrue(result.markdown(), result.failures.isEmpty());
        assertEquals(dcs + "\n---\n\n", result.body);
        verify(model, times(2)).executeAndRollback(any());
    }

    @Test
    public void testUnreadTextTemplateAsksForARetryNotAssignable()
    {
        var notes = named(MdClassFactory.eINSTANCE.createTemplate(), "Notes");
        notes.setTemplateType(TemplateType.TEXT_DOCUMENT);
        goods.getTemplates().add(notes);
        // Without a BM model the text view cannot read the content; the address is still a template.
        Rendered result = render("Catalog.Goods.Template.Notes");
        assertRefused(result);
        assertEquals(RETRY, result.reason());
    }

    @Test
    public void testUnreadDcsTemplateAsksForARetryNotAssignable()
    {
        var schema = named(MdClassFactory.eINSTANCE.createTemplate(), "Layout");
        schema.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
        goods.getTemplates().add(schema);
        Rendered result = render("Catalog.Goods.Template.Layout");
        assertRefused(result);
        assertEquals(RETRY, result.reason());
    }

    @Test
    public void testYoSpelledMemberIsNotCalledExisting()
    {
        // Stored with ye; the assignable and owner routes resolve exactly, so a yo spelling of the
        // member is reported as missing rather than sent to a route that would not find it.
        goods.getAttributes().add(named(MdClassFactory.eINSTANCE.createCatalogAttribute(), SILK_YE));
        assertMember(render("Catalog.Goods.Attribute." + SILK_YE), "Catalog.Goods");
        assertMissing(render("Catalog.Goods.Attribute." + SILK_YO));
    }

    private void mockTemplate(String name)
    {
        Template template = mock(Template.class, withSettings().extraInterfaces(IBmObject.class, InternalEObject.class));
        when(template.getName()).thenReturn(name);
        when(template.eClass()).thenReturn(MdClassPackage.Literals.TEMPLATE);
        goods.getTemplates().add(template);
    }

    private MetadataScope scope()
    {
        return MetadataScope.ofConfiguration(config);
    }

    private Rendered render(String fqn)
    {
        return render(scope(), fqn, false, false, null);
    }

    private static Rendered render(MetadataScope scope, String fqn, boolean full, boolean assignable, IBmModel model)
    {
        var context = new GetMetadataDetailsTool.RenderContext(null, scope, model, "en", full, assignable, false, 0);
        StringBuilder body = new StringBuilder();
        List<String[]> failures = new ArrayList<>();
        VendorSupportGuard.setServiceForTests(() -> null);
        try
        {
            new GetMetadataDetailsTool().processFqn(fqn, body, failures, context);
        }
        finally
        {
            VendorSupportGuard.setServiceForTests(null);
        }
        return new Rendered(fqn, body.toString(), failures);
    }

    private static void assertMember(Rendered result, String owner)
    {
        assertRefused(result);
        assertEquals("This addresses a member of '" + owner
            + "'; read its properties with assignable: true. The owner's details render from '" + owner + "'.",
            result.reason());
    }

    private static void assertMissing(Rendered result)
    {
        assertRefused(result);
        assertEquals(NOT_FOUND, result.reason());
    }

    private static void assertRefused(Rendered result)
    {
        assertEquals("no owner data may render for " + result.fqn, "", result.body);
        assertEquals(result.fqn, 1, result.failures.size());
        assertEquals(result.fqn, result.failures.get(0)[0]);
        assertTrue(result.markdown(), result.markdown().startsWith("## Errors\n"));
        assertTrue(result.markdown(), result.markdown().contains("| " + result.fqn + " | ERROR |"));
        assertFalse(result.markdown(), result.markdown().contains("## Catalog: Goods"));
    }

    private static <T extends MdObject> T named(T object, String name)
    {
        object.setName(name);
        return object;
    }

    private static final class Rendered
    {
        final String fqn;
        final String body;
        final List<String[]> failures;

        Rendered(String fqn, String body, List<String[]> failures)
        {
            this.fqn = fqn;
            this.body = body;
            this.failures = failures;
        }

        String reason()
        {
            assertEquals(fqn, 1, failures.size());
            return failures.get(0)[1];
        }

        String markdown()
        {
            return body + (failures.isEmpty() ? "" : new GetMetadataDetailsTool().formatFailures(failures));
        }
    }
}
