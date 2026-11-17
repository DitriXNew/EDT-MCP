/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;

import org.eclipse.emf.ecore.EClass;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.RegisterWriteMode;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com.google.gson.JsonArray;

/** Issue #933 PR-1: Constant, Enum, InformationRegister, ScheduledJob, CommonModule and Role clones. */
public class MetadataCloneKindsTest
{
    @Test
    public void constantHasItsOwnCollection()
    {
        assertCollection(MdClassPackage.Literals.CONSTANT, "constants");
    }

    @Test
    public void enumHasItsOwnCollection()
    {
        assertCollection(MdClassPackage.Literals.ENUM, "enums");
    }

    @Test
    public void informationRegisterHasItsOwnCollection()
    {
        assertCollection(MdClassPackage.Literals.INFORMATION_REGISTER, "informationRegisters");
    }

    @Test
    public void scheduledJobHasItsOwnCollection()
    {
        assertCollection(MdClassPackage.Literals.SCHEDULED_JOB, "scheduledJobs");
    }

    @Test
    public void commonModuleHasItsOwnCollection()
    {
        assertCollection(MdClassPackage.Literals.COMMON_MODULE, "commonModules");
    }

    /** Configuration declares defaultRoles before roles; a clone must never land in the default-roles list. */
    @Test
    public void roleIsAttachedToRolesNotToDefaultRoles()
    {
        assertCollection(MdClassPackage.Literals.ROLE, "roles");
    }

    @Test
    public void everyNewKindResolvesBilinguallyFromTheConfiguration()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Role role = MdClassFactory.eINSTANCE.createRole();
        role.setName("Reader");
        config.getRoles().add(role);
        assertSame(role, MetadataClonePlan.source(config, "Role.Reader"));
        assertSame(role, MetadataClonePlan.source(config, "Роль.Reader"));
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("Расчет");
        config.getCommonModules().add(module);
        assertSame(module, MetadataClonePlan.source(config,
            "ОбщийМодуль." + module.getName()));
        assertSame(module, MetadataClonePlan.source(config, "CommonModule." + module.getName()));
    }

    @Test
    public void commonFormAndExchangePlanUseTheirConfigurationCollections()
    {
        assertCollection(MdClassPackage.Literals.COMMON_FORM, "commonForms");
        assertCollection(MdClassPackage.Literals.EXCHANGE_PLAN, "exchangePlans");
    }

    @Test
    public void commonFormAndExchangePlanResolveByNameInBothLanguages()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        var form = MdClassFactory.eINSTANCE.createCommonForm();
        form.setName("Window");
        form.getSynonym().put("en", "WindowLabel");
        config.getCommonForms().add(form);
        assertSame(form, MetadataClonePlan.source(config, "CommonForm.Window"));
        assertSame(form, MetadataClonePlan.source(config, "ОбщаяФорма.Window"));
        assertThrows(IllegalArgumentException.class, () -> MetadataClonePlan.source(config, "ОбщаяФорма.WindowLabel"));

        var plan = MdClassFactory.eINSTANCE.createExchangePlan();
        plan.setName("Очередь");
        plan.getSynonym().put("ru", "СинонимОчереди");
        config.getExchangePlans().add(plan);
        assertSame(plan, MetadataClonePlan.source(config, "ExchangePlan.Очередь"));
        assertSame(plan, MetadataClonePlan.source(config, "ПланОбмена.Очередь"));
        assertThrows(IllegalArgumentException.class, () -> MetadataClonePlan.source(config, "ExchangePlan.СинонимОчереди"));
    }

    @Test
    public void commonFormGraphIncludesExternalContent()
    {
        var owner = MdClassFactory.eINSTANCE.createCommonForm();
        var form = FormFactory.eINSTANCE.createForm();
        owner.setForm(form);
        form.getItems().add(FormFactory.eINSTANCE.createDecoration());
        var graph = MetadataClonePlan.graph(owner);
        assertSame(owner, graph.get(""));
        assertSame(form, graph.get("/form"));
        assertSame(form.getItems().get(0), graph.get("/form/items/0"));
    }

    @Test
    public void unsupportedKindNamesTheWholeSupportedList()
    {
        String error = MetadataClonePlan.requestError("AccumulationRegister.Stock", null, false, null, null);
        assertNotNull(error);
        for (String kind : List.of("Catalog", "Document", "Report", "DataProcessor", "Constant", "Enum",
            "InformationRegister", "ScheduledJob", "CommonModule", "Role", "CommonForm", "ExchangePlan"))
        {
            assertTrue(kind + " missing from: " + error, error.contains(kind));
        }
        assertTrue(error, error.contains("AccumulationRegister.Stock"));
    }

    @Test
    public void globalCommonModuleIsRefusedWithTheReason()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("Globals");
        module.setGlobal(true);
        config.getCommonModules().add(module);
        try
        {
            MetadataClonePlan.source(config, "CommonModule.Globals");
            fail("A global common module clone would duplicate its exported methods in the global context");
        }
        catch (MetadataClonePlan.Refusal expected)
        {
            String message = expected.getMessage();
            assertTrue(message, message.contains("CommonModule.Globals"));
            assertTrue(message, message.contains("global=true"));
            assertTrue(message, message.contains("non-global"));
        }
        module.setGlobal(false);
        assertSame(module, MetadataClonePlan.source(config, "CommonModule.Globals"));
    }

    @Test
    public void commonModuleTextReferencesIncludeDirectMethodCalls()
    {
        String text = String.join("\n",
            "Result = Calc.Add(1, 2);",
            "Value = Расчет.Add(1);",
            "CalcExtra.Add(1);",
            "Object.Calc.Add(1);",
            "Calc . Add (1);",
            "Total = Calc;",
            "Meta = Metadata.CommonModules.Calc;",
            "Name = \"Calc\";",
            "MyCalc.Add(1);",
            "calc.add(2);");
        JsonArray refs = MetadataClonePlan.textReferences("Module.bsl", text, "CommonModule.Calc");
        assertEquals(List.of(1, 5, 7, 10), lines(refs));
        JsonArray russian = MetadataClonePlan.textReferences("Module.bsl", text,
            "CommonModule.Расчет");
        assertEquals(List.of(2), lines(russian));
    }

    /** Review #940: a qualifying dot is a qualifier however much whitespace or how many line breaks follow it. */
    @Test
    public void qualifiedCallsStayQualifiedHoweverFarTheDotIs()
    {
        String text = String.join("\n",
            "Value = Object." + " ".repeat(17) + "Calc.Add(1);",
            "Value = Object.\t\t  Calc.Add(1);",
            "Value = Object.",
            "",
            "    Calc.Add(1);",
            "Calc.Add(1);",
            "X = Object. Calc.Add(1); Calc.Add(2);");
        assertEquals(List.of(6, 7), lines(MetadataClonePlan.textReferences("Module.bsl", text, "CommonModule.Calc")));
    }

    /** Review #940: a full stop ending a comment is not a qualifier; a code dot before a comment still is. */
    @Test
    public void commentPunctuationDoesNotQualifyTheNextCall()
    {
        String text = String.join("\n",
            "// Explanation.",
            "Calc.Add(1);",
            "Result = Object. // trailing note.",
            "    Calc.Add(2);",
            "Value = Object.Method(\"a//b\").",
            "    Calc.Add(3);",
            "X = 1; // Done.",
            "Calc.Add(4);");
        assertEquals(List.of(2, 8), lines(MetadataClonePlan.textReferences("Module.bsl", text, "CommonModule.Calc")));
        assertEquals("Value = \"a//b\" ", MetadataClonePlan.codeBeforeComment("Value = \"a//b\" // note"));
        assertEquals("|text // inside the literal\";", MetadataClonePlan.codeBeforeComment(
            "|text // inside the literal\";"));
    }

    @Test
    public void bareNameCallsAreNotReferencesForOtherKinds()
    {
        JsonArray refs = MetadataClonePlan.textReferences("Module.bsl", "Calc.Add(1);\nCatalogs.Calc.EmptyRef();",
            "Catalog.Calc");
        assertEquals(List.of(2), lines(refs));
    }

    @Test
    public void enabledScheduledJobWarnsThatBothJobsRunTheSameMethod()
    {
        ScheduledJob job = MdClassFactory.eINSTANCE.createScheduledJob();
        job.setName("Nightly");
        job.setMethodName("CommonModule.Calc.Add");
        assertTrue(MetadataClonePlan.kindWarnings(job).isEmpty());
        job.setUse(true);
        List<String> warnings = MetadataClonePlan.kindWarnings(job);
        assertEquals(1, warnings.size());
        String warning = warnings.get(0);
        assertTrue(warning, warning.contains("use=true"));
        assertTrue(warning, warning.contains("CommonModule.Calc.Add"));
        assertTrue(warning, warning.contains("both"));
        assertTrue(warning, warning.contains("modify_metadata"));
    }

    @Test
    public void recorderSubordinateRegisterWarnsThatTheCloneHasNoRecorder()
    {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        register.setName("Prices");
        assertTrue(MetadataClonePlan.kindWarnings(register).isEmpty());
        register.setWriteMode(RegisterWriteMode.RECORDER_SUBORDINATE);
        List<String> warnings = MetadataClonePlan.kindWarnings(register);
        assertEquals(1, warnings.size());
        String warning = warnings.get(0);
        assertTrue(warning, warning.contains("RecorderSubordinate"));
        assertTrue(warning, warning.contains("registerRecords"));
        assertTrue(warning, warning.contains("Independent"));
    }

    @Test
    public void otherKindsCarryNoKindWarning()
    {
        for (EClass type : List.of(MdClassPackage.Literals.CATALOG, MdClassPackage.Literals.CONSTANT,
            MdClassPackage.Literals.ENUM, MdClassPackage.Literals.ROLE, MdClassPackage.Literals.COMMON_MODULE))
        {
            assertTrue(type.getName(),
                MetadataClonePlan.kindWarnings((MdObject)MdClassFactory.eINSTANCE.create(type)).isEmpty());
        }
    }

    private static List<Integer> lines(JsonArray refs)
    {
        List<Integer> result = new java.util.ArrayList<>();
        refs.forEach(ref -> result.add(ref.getAsJsonObject().get("line").getAsInt()));
        return result;
    }

    private static void assertCollection(EClass type, String expected)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        MdObject object = (MdObject)MdClassFactory.eINSTANCE.create(type);
        assertEquals(expected, MetadataClonePlan.collection(config, object).getName());
        assertNull(MetadataClonePlan.requestError(type.getName() + ".Source", null, false, null, null));
        assertTrue(MetadataClonePlan.SUPPORTED.contains(type.getName()));
    }
}
