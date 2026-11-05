/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.form.generator.FormFieldInfo;
import com._1c.g5.v8.dt.form.generator.FormType;
import com._1c.g5.v8.dt.form.generator.IFormFieldGenerator;
import com._1c.g5.v8.dt.form.generator.IFormGenerator;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterForm;
import com._1c.g5.v8.dt.metadata.mdclass.InterfaceCompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.RegisterWriteMode;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.Version;

public class FormGenerationSupportTest
{
    @Test
    public void registerListDefaultsUseEachRealModelSlot()
    {
        for (MdObject owner : registers())
        {
            EStructuralFeature forms = owner.eClass().getEStructuralFeature("forms");
            BasicForm form = (BasicForm)MdClassFactory.eINSTANCE.create((EClass)forms.getEType());
            FormGenerationSupport.validate(owner, owner.eClass().getName(), FormType.LIST, true, true, null);
            FormGenerationSupport.setDefault(owner, form, FormType.LIST);
            assertSame(form, owner.eGet(owner.eClass().getEStructuralFeature("defaultListForm")));
        }
    }

    @Test
    public void informationRegisterRecordDefaultDoesNotOverwriteListDefault()
    {
        InformationRegister owner = MdClassFactory.eINSTANCE.createInformationRegister();
        InformationRegisterForm list = MdClassFactory.eINSTANCE.createInformationRegisterForm();
        InformationRegisterForm record = MdClassFactory.eINSTANCE.createInformationRegisterForm();
        FormGenerationSupport.setDefault(owner, list, FormType.LIST);
        FormGenerationSupport.validate(owner, "InformationRegister", FormType.RECORD, true, true, null);
        FormGenerationSupport.setDefault(owner, record, FormType.RECORD);
        assertSame(list, owner.getDefaultListForm());
        assertSame(record, owner.getDefaultRecordForm());
    }

    @Test
    public void registerChoiceAndUnavailableRecordPurposesAreRefused()
    {
        for (MdObject owner : registers())
        {
            rejected(() -> FormGenerationSupport.validate(owner, owner.eClass().getName(),
                FormType.CHOICE, true, true, null), "CHOICE", "formType");
            if (!(owner instanceof InformationRegister))
            {
                rejected(() -> FormGenerationSupport.validate(owner, owner.eClass().getName(),
                    FormType.RECORD, true, true, null), "RECORD", "formType");
            }
        }
    }

    @Test
    public void subordinateRegisterRecordIsRefusedLikeTheWizard()
    {
        InformationRegister owner = MdClassFactory.eINSTANCE.createInformationRegister();
        owner.setWriteMode(RegisterWriteMode.RECORDER_SUBORDINATE);
        rejected(() -> FormGenerationSupport.validate(owner, "InformationRegister", FormType.RECORD,
            true, false, null), "RECORD", "independent");
    }

    @Test
    public void recordSetGenerationDoesNotInventADefaultSlot()
    {
        for (MdObject owner : registers())
        {
            FormGenerationSupport.validate(owner, owner.eClass().getName(), FormType.RECORD_SET,
                true, false, null);
            rejected(() -> FormGenerationSupport.validate(owner, owner.eClass().getName(), FormType.RECORD_SET,
                true, true, null), "RECORD_SET", "setAsDefault=false");
        }
    }

    @Test
    public void catalogAndDocumentListAndChoiceHaveSeparateDefaultSlots()
    {
        for (MdObject owner : new MdObject[] {MdClassFactory.eINSTANCE.createCatalog(),
            MdClassFactory.eINSTANCE.createDocument()})
        {
            EStructuralFeature forms = owner.eClass().getEStructuralFeature("forms");
            for (FormType type : new FormType[] {FormType.LIST, FormType.CHOICE})
            {
                BasicForm form = (BasicForm)MdClassFactory.eINSTANCE.create((EClass)forms.getEType());
                FormGenerationSupport.validate(owner, owner.eClass().getName(), type, true, true, null);
                FormGenerationSupport.setDefault(owner, form, type);
                assertSame(form, owner.eGet(FormGenerationSupport.defaultFeature(owner, type)));
            }
        }
    }

    @Test
    public void unsupportedObjectSeedIsAnActionableError()
    {
        MdObject owner = MdClassFactory.eINSTANCE.createInformationRegister();
        rejected(() -> FormGenerationSupport.validate(owner, "InformationRegister", FormType.OBJECT,
            true, false, null), "InformationRegister", "generateContent=false");
        rejected(() -> FormGenerationSupport.validate(MdClassFactory.eINSTANCE.createConstant(),
            "Constant", FormType.OBJECT, true, false, null), "Constant", "generateContent=false");
        FormGenerationSupport.validate(MdClassFactory.eINSTANCE.createConstant(), "Constant",
            FormType.OBJECT, false, false, null);
    }

    @Test
    public void objectFieldsAreNotSilentlyIgnoredOnListForms()
    {
        rejected(() -> FormGenerationSupport.validate(MdClassFactory.eINSTANCE.createInformationRegister(),
            "InformationRegister", FormType.LIST, true, false, Collections.emptyList()),
            "objectFields", "Omit objectFields");
    }

    @Test
    public void purposeDefaultsAreIndependentOfEnglishOrRussianNames()
    {
        for (String fqn : new String[] {"InformationRegister.Prices.Form.ListForm",
            "РегистрСведений.Цены.Форма.ФормаСписка"})
        {
            FormElementWriter.FormObjectRef ref = FormElementWriter.parseFormObjectCreate(
                MetadataTypeUtils.normalizeFqn(fqn));
            assertNotNull(ref);
            assertEquals(FormType.LIST, FormGenerationSupport.resolveType(null,
                MetadataTypeUtils.toEnglishSingular(ref.ownerType)));
        }
        assertEquals(FormType.OBJECT, FormGenerationSupport.resolveType(null, "Catalog"));
        assertEquals(FormType.RECORD, FormGenerationSupport.resolveType("RECORD", "InformationRegister"));
        rejected(() -> FormGenerationSupport.resolveType("nonsense", "Catalog"), "nonsense", "Use OBJECT");
    }

    @Test
    public void wizardReceivesFieldsDialectAndCompatibilityForBothLanguages()
    {
        for (ScriptVariant dialect : new ScriptVariant[] {ScriptVariant.ENGLISH, ScriptVariant.RUSSIAN})
        {
            for (FormType type : new FormType[] {FormType.LIST, FormType.CHOICE, FormType.RECORD,
                FormType.RECORD_SET})
            {
                MdObject owner = MdClassFactory.eINSTANCE.createInformationRegister();
                BasicForm descriptor = MdClassFactory.eINSTANCE.createInformationRegisterForm();
                IV8Project project = mock(IV8Project.class);
                when(project.getVersion()).thenReturn(Version.V8_3_27);
                when(project.getCompatibilityMode()).thenReturn(CompatibilityMode.VERSION8_326);
                when(project.getScriptVariant()).thenReturn(dialect);
                when(project.getInterfaceCompatibilityMode()).thenReturn(InterfaceCompatibilityMode.TAXI);
                IFormGenerator generator = mock(IFormGenerator.class);
                CompatibilityFields fields = new CompatibilityFields();
                FormFieldInfo root = fields.root;
                Form result = FormFactory.eINSTANCE.createForm();
                String language = dialect == ScriptVariant.ENGLISH ? "en" : "ru";
                when(generator.generateForm(owner, descriptor, type, dialect, language, Version.V8_3_27,
                    root, 1, InterfaceCompatibilityMode.TAXI)).thenReturn(result);
                assertSame(result, FormGenerationSupport.generate(owner, descriptor, type, project,
                    language, generator, fields));
                assertEquals(1, fields.calls);
                assertSame(owner, fields.owner);
                assertEquals(type, fields.type);
                assertEquals(dialect, fields.dialect);
                assertEquals(Version.V8_3_27, fields.version);
                assertEquals(Version.V8_3_26, fields.compatibility);
                verify(generator).generateForm(owner, descriptor, type, dialect, language, Version.V8_3_27,
                    root, 1, InterfaceCompatibilityMode.TAXI);
            }
        }
    }

    @Test
    public void olderWizardUsesFourArgumentsAndTheProjectVersion()
    {
        for (ScriptVariant dialect : new ScriptVariant[] {ScriptVariant.ENGLISH, ScriptVariant.RUSSIAN})
        {
            LegacyFields fields = new LegacyFields();
            MdObject owner = MdClassFactory.eINSTANCE.createInformationRegister();
            BasicForm descriptor = MdClassFactory.eINSTANCE.createInformationRegisterForm();
            IV8Project project = mock(IV8Project.class);
            when(project.getVersion()).thenReturn(Version.V8_3_27);
            when(project.getCompatibilityMode()).thenReturn(CompatibilityMode.VERSION8_326);
            when(project.getScriptVariant()).thenReturn(dialect);
            IFormGenerator generator = mock(IFormGenerator.class);
            Form result = FormFactory.eINSTANCE.createForm();
            when(generator.generateForm(owner, descriptor, FormType.LIST, dialect, "en", Version.V8_3_27,
                fields.root, 1, null)).thenReturn(result);
            assertSame(result, FormGenerationSupport.generate(owner, descriptor, FormType.LIST, project,
                "en", generator, fields));
            assertEquals(1, fields.calls);
            assertSame(owner, fields.owner);
            assertEquals(FormType.LIST, fields.type);
            assertEquals(dialect, fields.dialect);
            assertEquals(Version.V8_3_27, fields.version);
        }
    }

    @Test
    public void reflectiveFailuresRefuseBeforeGeneratingOrWritingContent()
    {
        for (LegacyFields fields : new LegacyFields[] {new LegacyFields(), new CompatibilityFields()})
        {
            for (Throwable cause : new Throwable[] {new IllegalStateException("field generation failed"),
                new NoSuchMethodError("generator dependency")})
            {
                fields.failure = cause;
                fields.calls = 0;
                InformationRegister owner = MdClassFactory.eINSTANCE.createInformationRegister();
                BasicForm descriptor = MdClassFactory.eINSTANCE.createInformationRegisterForm();
                IV8Project project = mock(IV8Project.class);
                when(project.getVersion()).thenReturn(Version.V8_3_27);
                IFormGenerator generator = mock(IFormGenerator.class);
                try
                {
                    FormGenerationSupport.generate(owner, descriptor, FormType.LIST, project, "en", generator, fields);
                    fail("Expected field generation refusal");
                }
                catch (FormValidationException e)
                {
                    assertSame(cause, e.getCause());
                    assertTrue(e.json(), e.json().contains("\"success\":false"));
                    assertTrue(e.json(), e.json().contains("LIST"));
                    assertTrue(e.json(), e.json().contains("generateContent=false"));
                }
                assertEquals(1, fields.calls);
                assertTrue(owner.getForms().isEmpty());
                assertEquals(null, owner.getDefaultListForm());
                assertEquals(null, descriptor.getForm());
                org.mockito.Mockito.verifyZeroInteractions(generator);
            }
        }
    }

    @Test
    public void fatalFieldGenerationErrorPropagatesWithoutGeneratingOrWritingContent()
    {
        for (LegacyFields fields : new LegacyFields[] {new LegacyFields(), new CompatibilityFields()})
        {
            OutOfMemoryError failure = new OutOfMemoryError("fatal field generation failure");
            fields.failure = failure;
            InformationRegister owner = MdClassFactory.eINSTANCE.createInformationRegister();
            BasicForm descriptor = MdClassFactory.eINSTANCE.createInformationRegisterForm();
            IV8Project project = mock(IV8Project.class);
            when(project.getVersion()).thenReturn(Version.V8_3_27);
            IFormGenerator generator = mock(IFormGenerator.class);
            try
            {
                FormGenerationSupport.generate(owner, descriptor, FormType.LIST, project, "en", generator, fields);
                fail("Expected the fatal field generation error");
            }
            catch (OutOfMemoryError expected)
            {
                assertSame(failure, expected);
            }
            assertEquals(1, fields.calls);
            assertTrue(owner.getForms().isEmpty());
            assertEquals(null, owner.getDefaultListForm());
            assertEquals(null, descriptor.getForm());
            org.mockito.Mockito.verifyZeroInteractions(generator);
        }
    }

    public static class LegacyFields
    {
        final FormFieldInfo root = new FormFieldInfo("", true, null, null, false);
        int calls;
        MdObject owner;
        FormType type;
        ScriptVariant dialect;
        Version version;
        Throwable failure;

        public FormFieldInfo getFormGeneratorFields(MdObject owner, FormType type,
            ScriptVariant dialect, Version version)
        {
            calls++;
            this.owner = owner;
            this.type = type;
            this.dialect = dialect;
            this.version = version;
            if (failure instanceof RuntimeException exception)
            {
                throw exception;
            }
            if (failure instanceof Error error)
            {
                throw error;
            }
            return root;
        }
    }

    public static class CompatibilityFields extends LegacyFields
    {
        Version compatibility;

        public FormFieldInfo getFormGeneratorFields(MdObject owner, FormType type,
            ScriptVariant dialect, Version version, Version compatibility)
        {
            this.compatibility = compatibility;
            return super.getFormGeneratorFields(owner, type, dialect, version);
        }

        @Override
        public FormFieldInfo getFormGeneratorFields(MdObject owner, FormType type,
            ScriptVariant dialect, Version version)
        {
            throw new AssertionError("The compatibility-aware overload must be preferred");
        }
    }

    @Test
    public void generatedContentUsesSharedAttachmentAndDefaultPath()
    {
        InformationRegister owner = MdClassFactory.eINSTANCE.createInformationRegister();
        owner.setName("Цены");
        BasicForm descriptor = MdClassFactory.eINSTANCE.createInformationRegisterForm();
        Form content = FormFactory.eINSTANCE.createForm();
        IModelObjectFactory mdFactory = mock(IModelObjectFactory.class);
        when(mdFactory.create(MdClassPackage.Literals.INFORMATION_REGISTER_FORM, Version.V8_3_27))
            .thenReturn(descriptor);
        ITopObjectFqnGenerator naming = mock(ITopObjectFqnGenerator.class);
        String fqn = "InformationRegister.Цены.Form.ФормаСписка.Form";
        when(naming.generateExternalPropertyFqn(descriptor, MdClassPackage.Literals.BASIC_FORM__FORM))
            .thenReturn(fqn);
        IBmTransaction tx = mock(IBmTransaction.class);
        assertEquals(fqn, FormElementWriter.createForm(tx, owner, "ФормаСписка", "en", "Prices", null,
            true, mdFactory, null, naming, Version.V8_3_27, true, false, "InformationRegister", null,
            FormType.LIST, (actualOwner, actualForm) -> {
                assertSame(owner, actualOwner);
                assertSame(descriptor, actualForm);
                assertTrue(owner.getForms().contains(actualForm));
                return content;
            }));
        assertSame(content, descriptor.getForm());
        assertSame(descriptor, content.getMdForm());
        assertSame(descriptor, owner.getDefaultListForm());
        assertEquals("Prices", descriptor.getSynonym().get("en"));
        verify(tx).attachTopObject((IBmObject)content, fqn);
        verify(mdFactory).fillDefaultReferences(descriptor);
    }

    @Test
    public void emptyGeneratorResultCannotBeReportedAsSuccess()
    {
        IV8Project project = mock(IV8Project.class);
        when(project.getVersion()).thenReturn(Version.V8_3_27);
        rejected(() -> FormGenerationSupport.generate(MdClassFactory.eINSTANCE.createInformationRegister(),
            MdClassFactory.eINSTANCE.createInformationRegisterForm(), FormType.LIST, project, "en",
            mock(IFormGenerator.class), mock(IFormFieldGenerator.class)), "LIST", "generateContent=false");
    }

    private static MdObject[] registers()
    {
        return new MdObject[] {MdClassFactory.eINSTANCE.createInformationRegister(),
            MdClassFactory.eINSTANCE.createAccumulationRegister(), MdClassFactory.eINSTANCE.createAccountingRegister(),
            MdClassFactory.eINSTANCE.createCalculationRegister()};
    }

    private static void rejected(Runnable action, String value, String remedy)
    {
        try
        {
            action.run();
            fail("Expected an actionable refusal");
        }
        catch (FormValidationException e)
        {
            assertTrue(e.json(), e.json().contains("\"success\":false"));
            assertTrue(e.json(), e.json().contains(value));
            assertTrue(e.json(), e.json().contains(remedy));
        }
    }
}
