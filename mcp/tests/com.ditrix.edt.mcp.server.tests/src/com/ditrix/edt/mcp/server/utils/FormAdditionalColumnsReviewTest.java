/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttributeAdditionalColumns;
import com._1c.g5.v8.dt.form.model.FormAttributeColumn;
import com._1c.g5.v8.dt.mcore.McoreFactory;

/**
 * Issue #737 review: one table grammar for every tool, the created entry as the source of the reported
 * table path, and refusals that tell a readiness delay from a refusal that no retry can fix.
 */
public class FormAdditionalColumnsReviewTest
{
    private static final String TOVARY = "\u0422\u043e\u0432\u0430\u0440\u044b";
    private static final String LOG_PREFIX = "Failed to resolve the additional-column table"; //$NON-NLS-1$

    private AdditionalColumnFixture f;
    private ILog log;
    private final List<IStatus> logged = new CopyOnWriteArrayList<>();
    private final ILogListener listener = (status, plugin) -> {
        if (status.getMessage() != null && status.getMessage().startsWith(LOG_PREFIX))
        {
            logged.add(status);
        }
    };

    @Before
    public void setUp() throws Exception
    {
        f = new AdditionalColumnFixture();
        Bundle bundle = FrameworkUtil.getBundle(FormElementWriter.class);
        assertNotNull("the regression must observe the real OSGi bundle log", bundle); //$NON-NLS-1$
        log = Platform.getLog(bundle);
        log.addLogListener(listener);
    }

    @After
    public void tearDown()
    {
        if (log != null)
        {
            log.removeLogListener(listener);
        }
        f.close();
    }

    @Test
    public void testAnAliasTableTokenIsRefusedWithTheCanonicalName()
    {
        when(f.table.getNameRu()).thenReturn(TOVARY);
        // Found through the writer's own fallback over the attribute's children ...
        assertAlias(f.create("Extra", TOVARY, new String[1]));
        // ... and equally when EDT's own lookup answers the alias.
        doAnswer(call -> {
            List<String> path = ((AbstractDataPath)call.getArgument(1)).getSegments();
            return path.size() == 1 ? f.root : f.table;
        }).when(f.sources).findPropertyInfo(any(Form.class), any(AbstractDataPath.class));
        assertAlias(f.create("Extra", TOVARY, new String[1]));
        // A case variant of the programmatic name is the same name, not an alias.
        assertNull(f.create("Extra", "GOODS", new String[1]));
        assertEquals(List.of("Object", "Goods"), f.owner.getAdditionalColumns().get(0).getTablePath().getSegments());
    }

    private void assertAlias(String error)
    {
        assertNotNull("an alias table token must be refused", error);
        assertTrue(error, error.contains("by its name 'Goods'")); //$NON-NLS-1$
        assertTrue(error, error.contains("'...Attribute.Object.Column.Goods.<Name>'")); //$NON-NLS-1$
        assertFalse(error, error.contains("retry")); //$NON-NLS-1$
        assertTrue("nothing may be written", f.owner.getAdditionalColumns().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testCreateReportsTheTablePathOfTheEntryItLandedIn()
    {
        String[] tablePath = new String[1];
        assertNull(f.create("First", "goods", new String[1], tablePath));
        assertEquals("Object.Goods", tablePath[0]);
        // A reused case-variant entry reports its now canonical spelling, not the caller's token.
        f.owner.getAdditionalColumns().clear();
        f.entry("object", "GOODS").getColumns().add(AdditionalColumnFixture.column("Old", 7));
        String[] reused = new String[1];
        assertNull(f.create("Second", "gOoDs", new String[1], reused));
        assertEquals("Object.Goods", reused[0]);
        // A refusal reports nothing.
        String[] refused = new String[1];
        assertNotNull(f.create("Second", "Goods", new String[1], refused));
        assertNull(refused[0]);
    }

    @Test
    public void testTablePathOfAdditionalColumnIsNullSafe()
    {
        assertNull(FormElementWriter.tablePathOfAdditionalColumn(null));
        assertNull("a detached column has no entry", //$NON-NLS-1$
            FormElementWriter.tablePathOfAdditionalColumn(AdditionalColumnFixture.column("Loose", 1)));
        FormAttributeColumn direct = AdditionalColumnFixture.column("Direct", 2);
        f.owner.getColumns().add(direct);
        assertNull("a direct column sits in no additional-column entry", //$NON-NLS-1$
            FormElementWriter.tablePathOfAdditionalColumn(direct));
        FormAttributeAdditionalColumns entry = f.entry("Object", "Goods");
        FormAttributeColumn extra = AdditionalColumnFixture.column("Extra", 3);
        entry.getColumns().add(extra);
        assertEquals("Object.Goods", FormElementWriter.tablePathOfAdditionalColumn(extra));
    }

    @Test
    public void testAnOwnerWithoutAValueTypeIsRefusedWithoutRetry()
    {
        f.owner.setValueType(McoreFactory.eINSTANCE.createTypeDescription());
        when(f.root.getPropertyInfos()).thenReturn(List.of());
        String error = f.create("Extra", "Goods", new String[1]);
        assertNotNull(error);
        assertTrue(error, error.contains("'Object' has no value type")); //$NON-NLS-1$
        assertTrue(error, error.contains("object type first")); //$NON-NLS-1$
        assertFalse(error, error.contains("retry")); //$NON-NLS-1$
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
        f.owner.setValueType(null);
        assertTrue(f.create("Extra", "Goods", new String[1]).contains("has no value type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a refusal is not an EDT failure", logged.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAnUnexpectedEdtFailureIsLoggedAndNotWordedAsARetry()
    {
        doThrow(new ClassCastException("moved API")).when(f.sources) //$NON-NLS-1$
            .findPropertyInfo(any(Form.class), any(AbstractDataPath.class));
        String error = f.create("Extra", "Goods", new String[1]);
        assertNotNull(error);
        assertTrue(error, error.contains("EDT failed (ClassCastException: moved API)")); //$NON-NLS-1$
        assertTrue(error, error.contains("retrying will not help")); //$NON-NLS-1$
        assertFalse(error, error.contains("is not ready")); //$NON-NLS-1$
        assertEquals(1, logged.size());
        assertEquals(IStatus.ERROR, logged.get(0).getSeverity());
        assertTrue(logged.get(0).getException() instanceof ClassCastException);
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }

    @Test
    public void testAReadinessDelayIsARetryAndIsNotLogged() throws Exception
    {
        when(f.table.getPropertyInfos()).thenReturn(null);
        String unbuiltTable = f.create("Extra", "Goods", new String[1]);
        assertTrue(unbuiltTable, unbuiltTable.contains("model is not ready; retry")); //$NON-NLS-1$
        f.unavailable(true);
        String error = f.create("Extra", "Goods", new String[1]);
        assertNotNull(error);
        assertTrue(error, error.contains("model is not ready; retry")); //$NON-NLS-1$
        assertTrue("a readiness signal is not an EDT failure and is not logged", logged.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAnUnavailableServiceIsNotARetry()
    {
        FormElementWriter.attributeManagementSource = () -> null;
        String management = f.create("Extra", "Goods", new String[1]);
        assertTrue(management, management.contains("form-attribute management service is unavailable")); //$NON-NLS-1$
        assertTrue(management, management.contains("retrying will not help")); //$NON-NLS-1$
        FormElementWriter.dataSourcesSource = () -> null;
        String sources = f.create("Extra", "Goods", new String[1]);
        assertTrue(sources, sources.contains("data source service is unavailable")); //$NON-NLS-1$
        assertFalse(sources, sources.contains("is not ready")); //$NON-NLS-1$
        assertTrue(f.owner.getAdditionalColumns().isEmpty());
    }
}
