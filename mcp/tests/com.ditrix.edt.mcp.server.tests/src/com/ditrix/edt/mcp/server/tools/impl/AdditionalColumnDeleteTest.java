/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.service.extension.IFormExtensionService;
import com.ditrix.edt.mcp.server.EdtServices;
import com.ditrix.edt.mcp.server.utils.AdditionalColumnFixture;
import com.ditrix.edt.mcp.server.utils.ComposerDataTreeFake;
import com.ditrix.edt.mcp.server.utils.FormAttributeDeletion;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;

public class AdditionalColumnDeleteTest
{
    @Test
    public void testFormCheckCarriesTheAdditionalColumnTablePath()
    {
        var ref = AdditionalColumnFixture.ref(AdditionalColumnFixture.PREFIX + "Goods.Extra");
        var check = new DeleteMetadataTool.AttributeDeleteOutcome().formCheck(ref);
        assertEquals("Object", check.get("attribute"));
        assertEquals("Extra", check.get("column"));
        assertEquals("Object.Goods", check.get("tablePath"));
    }

    @Test
    public void testDiskCheckSelectsOnlyTheAdditionalPathWithDottedOrSeparateSegments() throws Exception
    {
        for (String segments : List.of("<segments>Object.Goods</segments>",
            "<segments>Object</segments><segments>Goods</segments>"))
        {
            byte[] xml = ("<Form><attributes><name>Object</name><columns><name>DirectOnly</name></columns>"
                + "<additionalColumns><tablePath>" + segments + "</tablePath><columns><name>Extra</name>"
                + "</columns></additionalColumns></attributes></Form>").getBytes(StandardCharsets.UTF_8);
            assertEquals(DeleteMetadataTool.RegistrationState.PRESENT, state(xml, "extra", "object.goods"));
            assertEquals(DeleteMetadataTool.RegistrationState.ABSENT, state(xml, "Extra", "Object.Other"));
            assertEquals(DeleteMetadataTool.RegistrationState.ABSENT, state(xml, "DirectOnly", "Object.Goods"));
            assertEquals(DeleteMetadataTool.RegistrationState.ABSENT, state(xml, "Missing", "Object.Goods"));
            assertEquals(DeleteMetadataTool.RegistrationState.PRESENT,
                DeleteMetadataTool.formAttributeState(xml, "Object", "DirectOnly"));
        }
    }

    @Test
    public void testResolvedAdditionalColumnUsesEdtPredictionAndRemovesTheEmptyEntry() throws Exception
    {
        try (AdditionalColumnFixture f = new AdditionalColumnFixture())
        {
            var entry = f.entry("Object", "Goods");
            var column = AdditionalColumnFixture.column("Extra", 4);
            entry.getColumns().add(column);
            f.entry("Object", "Goods", "Extra").getColumns().add(AdditionalColumnFixture.column("NestedExtra", 5));
            var field = FormFactory.eINSTANCE.createFormField();
            field.setName("BoundExtra");
            field.setDataPath(ComposerDataTreeFake.dataPath("Object.Goods.Extra"));
            f.form.getItems().add(field);
            var ref = AdditionalColumnFixture.ref(AdditionalColumnFixture.PREFIX + "Goods.Extra");
            EObject resolved = FormElementWriter.resolveFormMember(f.form, ref);
            assertSame(column, resolved);
            PropertyInfo info = mock(PropertyInfo.class);
            when(info.getDataPath(any())).thenReturn(field.getDataPath());
            when(f.sources.findPropertyInfo(any(Form.class), any(AbstractFormAttribute.class))).thenReturn(info);
            doAnswer(call -> {
                PropertyInfo resolvedPath = mock(PropertyInfo.class);
                when(resolvedPath.getDataPath(any())).thenReturn(call.getArgument(1));
                return resolvedPath;
            }).when(f.sources).findPropertyInfo(any(Form.class), any(AbstractDataPath.class));
            var edt = new FormAttributeDeletion(f.management, f.sources, mock(IFormExtensionService.class),
                EdtServices.deleteItemCollectorConstructor());
            var deleter = new DeleteMetadataTool.FormAttributeDeleter()
            {
                @Override
                public FormAttributeDeletion.Plan plan(EObject form, EObject target)
                {
                    return edt.plan(form, target);
                }

                @Override
                public void delete(IBmTransaction tx, EObject target)
                {
                    // Emulate only the platform mutation; prediction above is EDT's own collector.
                    List<EObject> nested = List.copyOf(edt.plan(f.form, target).additionalColumns);
                    EcoreUtil.remove(field);
                    EcoreUtil.remove(target);
                    if (entry.getColumns().isEmpty()) { EcoreUtil.remove(entry); }
                    nested.forEach(EcoreUtil::remove);
                }
            };
            var preview = new DeleteMetadataTool.FormDeletePreview();
            DeleteMetadataTool.readAttributeDeletePreview(f.form, resolved, deleter, preview);
            assertTrue(preview.attribute);
            assertEquals(List.of(field), edt.plan(f.form, resolved).bound);
            assertTrue(preview.scope.toString(), preview.scope.toString().contains("Object.Goods"));
            var outcome = DeleteMetadataTool.deleteAttributeInTx(f.form, resolved, mock(IBmTransaction.class),
                deleter, AdditionalColumnFixture.PREFIX + "Goods.Extra", preview.scope);
            assertTrue(f.owner.getAdditionalColumns().isEmpty());
            assertTrue(f.form.getItems().isEmpty());
            assertNull(FormElementWriter.resolveFormMember(f.form, ref));
            assertTrue(outcome.removed.toString(), outcome.removed.stream()
                .anyMatch(removed -> "BoundExtra".equals(removed.get("name"))));
            assertTrue(outcome.removed.toString(), outcome.removed.stream()
                .anyMatch(removed -> "NestedExtra".equals(removed.get("name"))
                    && "Object.Goods.Extra".equals(removed.get("tablePath"))));
            assertEquals("Object.Goods", outcome.formCheck(ref).get("tablePath"));
        }
    }

    private static DeleteMetadataTool.RegistrationState state(byte[] xml, String column, String path)
    {
        return DeleteMetadataTool.formAttributeState(xml, "Object", column, path);
    }
}
