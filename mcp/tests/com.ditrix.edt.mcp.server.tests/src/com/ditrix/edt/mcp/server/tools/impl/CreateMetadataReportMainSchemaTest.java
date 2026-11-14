/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.emf.ecore.EReference;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.metadata.mdclass.extension.ReportExtension;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Issue #919: a report template created as a DataCompositionSchema becomes the report's main data
 * composition schema in the same create_metadata call ({@code setAsDefault}), and the create surface
 * leads an agent to {@code templateType} instead of a second retype call.
 */
public class CreateMetadataReportMainSchemaTest
{
    private static final String RU_REPORT_TEMPLATE_FQN =
        "Отчет.Sales919.Макет.Main"; //$NON-NLS-1$

    // ---- prepareMainSchema: the up-front validation ----

    @Test public void testSetAsDefaultOnReportTemplateImpliesDataCompositionSchema()
    {
        CreateMetadataTool.Props props = props(true, null);
        assertNull(prepare(report(), "Report.Sales919.Template.Main", props));
        assertSame(TemplateType.DATA_COMPOSITION_SCHEMA, props.templateType);
    }

    @Test public void testSetAsDefaultAcceptsAnExplicitDataCompositionSchema()
    {
        CreateMetadataTool.Props props = props(true, TemplateType.DATA_COMPOSITION_SCHEMA);
        assertNull(prepare(report(), RU_REPORT_TEMPLATE_FQN, props));
        assertSame(TemplateType.DATA_COMPOSITION_SCHEMA, props.templateType);
    }

    @Test public void testSetAsDefaultOnExternalReportTemplateIsAccepted()
    {
        ExternalReport owner = MdClassFactory.eINSTANCE.createExternalReport();
        owner.setName("Ext919"); //$NON-NLS-1$
        CreateMetadataTool.Props props = props(true, null);
        assertNull(prepare(owner, "ExternalReport.Ext919.Template.Main", props));
        assertSame(TemplateType.DATA_COMPOSITION_SCHEMA, props.templateType);
    }

    @Test public void testSetAsDefaultRefusesAnotherTemplateType()
    {
        CreateMetadataTool.Props props = props(true, TemplateType.TEXT_DOCUMENT);
        String error = prepare(report(), "Report.Sales919.Template.Main", props);
        assertNotNull(error);
        assertTrue(error, error.contains("must be a DataCompositionSchema template, not TextDocument"));
        assertTrue(error, error.contains("Nothing was created"));
        assertSame(TemplateType.TEXT_DOCUMENT, props.templateType);
    }

    @Test public void testSetAsDefaultRefusesATemplateOfANonReportOwner()
    {
        DataProcessor owner = MdClassFactory.eINSTANCE.createDataProcessor();
        owner.setName("Proc919"); //$NON-NLS-1$
        String error = prepare(owner, "DataProcessor.Proc919.Template.Main", props(true, null));
        assertNotNull(error);
        assertTrue(error, error.contains("applies only to 'Report.<Name>.Template.<Template>'"));
        assertTrue(error, error.contains("DataProcessor.Proc919.Template.Main"));
    }

    @Test public void testSetAsDefaultRefusesACommonTemplate()
    {
        String error = CreateMetadataTool.prepareMainSchema(null, MdClassPackage.Literals.COMMON_TEMPLATE,
            "CommonTemplate.Shared919", props(true, null), null, Version.LATEST);
        assertNotNull(error);
        assertTrue(error, error.contains("Omit setAsDefault"));
    }

    @Test public void testSetAsDefaultOnAnAdoptedReportFollowsTheExtensionRule()
    {
        Report owner = report();
        owner.setObjectBelonging(ObjectBelonging.ADOPTED);
        ReportExtension extension = MdClassExtensionFactory.eINSTANCE.createReportExtension();
        owner.setExtension(extension);
        String error = prepare(owner, "Report.Sales919.Template.Main", props(true, null));
        assertNotNull("an adopted report whose main-DCS state is None would drop the reference", error);
        assertTrue(error, error.contains("'mainDataCompositionSchema' of Report.Sales919"));
    }

    @Test public void testSetAsDefaultOnAnAdoptedReportMarkedExtendedIsAccepted()
    {
        Report owner = report();
        owner.setObjectBelonging(ObjectBelonging.ADOPTED);
        ReportExtension extension = MdClassExtensionFactory.eINSTANCE.createReportExtension();
        extension.setMainDataCompositionSchema(MdPropertyState.EXTENDED);
        owner.setExtension(extension);
        assertNull(prepare(owner, "Report.Sales919.Template.Main", props(true, null)));
    }

    @Test public void testWithoutSetAsDefaultNothingIsImplied()
    {
        CreateMetadataTool.Props props = props(false, null);
        assertNull(prepare(report(), "Report.Sales919.Template.Main", props));
        assertNull("no setAsDefault: the template keeps the SpreadsheetDocument default", props.templateType);
    }

    @Test public void testSetAsDefaultIsIgnoredForANonTemplateCreate()
    {
        CreateMetadataTool.Props props = props(true, null);
        assertNull(CreateMetadataTool.prepareMainSchema(report(), MdClassPackage.Literals.REPORT_ATTRIBUTE,
            "Report.Sales919.Attribute.Amount", props, null, Version.LATEST));
        assertNull(props.templateType);
    }

    // ---- the in-transaction child creation ----

    @Test public void testCreatedTemplateBecomesTheReportsMainSchema()
    {
        Report owner = report();
        BasicTemplate created = createTemplate(owner, props(true, TemplateType.DATA_COMPOSITION_SCHEMA));
        assertSame(TemplateType.DATA_COMPOSITION_SCHEMA, created.getTemplateType());
        assertSame(created, owner.getMainDataCompositionSchema());
        assertSame(created, owner.getTemplates().get(0));
    }

    @Test public void testCreatedTemplateBecomesTheExternalReportsMainSchema()
    {
        ExternalReport owner = MdClassFactory.eINSTANCE.createExternalReport();
        owner.setName("Ext919"); //$NON-NLS-1$
        EReference feature = (EReference)owner.eClass().getEStructuralFeature("templates"); //$NON-NLS-1$
        BasicTemplate created = (BasicTemplate)CreateMetadataTool.createMemberChild(new CreateMetadataTool.MemberChildSpec(
            mock(IModelObjectFactory.class), feature.getEReferenceType(), owner, Version.LATEST, "Main", //$NON-NLS-1$
            props(true, TemplateType.DATA_COMPOSITION_SCHEMA), null, feature, null));
        assertSame(created, owner.getMainDataCompositionSchema());
    }

    @Test public void testWithoutSetAsDefaultTheMainSchemaIsUntouched()
    {
        Report owner = report();
        createTemplate(owner, props(false, TemplateType.DATA_COMPOSITION_SCHEMA));
        assertNull(owner.getMainDataCompositionSchema());
    }

    // ---- what the agent reads ----

    @Test public void testMessageNamesTheTemplateTypeAndTheMainSchema()
    {
        String note = CreateMetadataTool.templateNote(MdClassPackage.Literals.TEMPLATE,
            props(true, TemplateType.DATA_COMPOSITION_SCHEMA), "Report.Sales919.Template.Main");
        assertEquals(" (templateType DataCompositionSchema), set as the main data composition schema of Report.Sales919",
            note);
    }

    @Test public void testMessageNamesTheSilentSpreadsheetDefault()
    {
        assertEquals(" (templateType SpreadsheetDocument)", CreateMetadataTool.templateNote(
            MdClassPackage.Literals.COMMON_TEMPLATE, props(false, null), "CommonTemplate.Shared919"));
    }

    @Test public void testMessageIsUnchangedForANonTemplate()
    {
        assertEquals("", CreateMetadataTool.templateNote(MdClassPackage.Literals.REPORT, props(true, null),
            "Report.Sales919"));
    }

    @Test public void testRefusedTypePropertyPointsAtTemplateType() throws Exception
    {
        JsonObject property = new JsonObject();
        property.addProperty("name", "type"); //$NON-NLS-1$ //$NON-NLS-2$
        property.addProperty("value", "DataCompositionSchema"); //$NON-NLS-1$ //$NON-NLS-2$
        Method parse = CreateMetadataTool.class.getDeclaredMethod("parseProperties", List.class,
            CreateMetadataTool.Props.class, MdNameNormalizer.Report.class);
        parse.setAccessible(true);
        String error = (String)parse.invoke(new CreateMetadataTool(), List.of(property),
            new CreateMetadataTool.Props(), new MdNameNormalizer.Report(true));
        assertNotNull(error);
        assertTrue(error, error.contains("create it with properties:[{name:'templateType', value:'DataCompositionSchema'}]"));
        assertTrue(error, error.contains("setAsDefault=true"));
    }

    @Test public void testRefusalOfAnotherPropertyCarriesNoTemplateHint()
    {
        assertEquals("", CreateMetadataTool.templateTypeHint("fillChecking")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test public void testSchemaDescribesTheMainSchemaUseOfSetAsDefault()
    {
        JsonObject schema = JsonParser.parseString(new CreateMetadataTool().getInputSchema()).getAsJsonObject();
        String description = schema.getAsJsonObject("properties").getAsJsonObject("setAsDefault") //$NON-NLS-1$ //$NON-NLS-2$
            .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(description, description.contains("main data composition schema"));
        assertTrue(description, description.contains("defaults to DataCompositionSchema"));
    }

    @Test public void testGuideTeachesTemplateTypeAndTheOneCallMainSchema() throws Exception
    {
        try (InputStream stream = CreateMetadataTool.class.getResourceAsStream("/guides/create_metadata.md")) //$NON-NLS-1$
        {
            assertNotNull(stream);
            String guide = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(guide.contains("`value:\"DataCompositionSchema\"` for a data composition schema"));
            assertTrue(guide.contains("`templateType` is a `properties` entry, not a top-level argument"));
            assertTrue(guide.contains("{\"fqn\":\"Report.Sales.Template.MainSchema\",\"setAsDefault\":true}"));
        }
    }

    // ---- helpers ----

    private static Report report()
    {
        Report owner = MdClassFactory.eINSTANCE.createReport();
        owner.setName("Sales919"); //$NON-NLS-1$
        return owner;
    }

    private static CreateMetadataTool.Props props(boolean setAsDefault, TemplateType type)
    {
        CreateMetadataTool.Props props = new CreateMetadataTool.Props();
        props.setAsDefault = setAsDefault;
        props.templateType = type;
        return props;
    }

    private static String prepare(org.eclipse.emf.ecore.EObject owner, String fqn, CreateMetadataTool.Props props)
    {
        return CreateMetadataTool.prepareMainSchema(owner, MdClassPackage.Literals.TEMPLATE, fqn, props, null,
            Version.LATEST);
    }

    private static BasicTemplate createTemplate(Report owner, CreateMetadataTool.Props props)
    {
        EReference feature = (EReference)owner.eClass().getEStructuralFeature("templates"); //$NON-NLS-1$
        return (BasicTemplate)CreateMetadataTool.createMemberChild(new CreateMetadataTool.MemberChildSpec(
            mock(IModelObjectFactory.class), feature.getEReferenceType(), owner, Version.LATEST, "Main", //$NON-NLS-1$
            props, null, feature, null));
    }
}
