/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.EditingForbiddenProblem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * EDT's rename records a locked referrer as an {@link EditingForbiddenProblem} and still edits it;
 * the rename is refused before its preview or perform when any such problem is a support lock
 * (#642). Proved against a real support file by {@code test_vendor_support_guard.py}.
 */
public class MetadataRenameSupportLockTest
{
    private IModelEditingSupport support;

    @Before
    public void setUp()
    {
        support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        when(support.canDelete(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    @Test
    public void testALockedReferrerRefusesTheRenameAndIsNamed()
    {
        IBmObject rights = bmObject("Role.LockedRole.Rights", 7L); //$NON-NLS-1$
        when(support.canEdit(rights, EditingMode.DIRECT)).thenReturn(false);

        String refusal = MetadataRenameService.supportLockRefusal("Catalog.Open.Attribute.Note", //$NON-NLS-1$
            Collections.singletonList(refactoringWith(new EditingForbiddenProblem(rights))));

        assertNotNull("EDT would edit the locked role's rights anyway", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'Catalog.Open.Attribute.Note' cannot be renamed: the rename " //$NON-NLS-1$
            + "would update references inside Role.LockedRole.Rights, which vendor support")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed")); //$NON-NLS-1$
    }

    @Test
    public void testAnEditingProblemThatIsNotASupportLockDoesNotRefuse()
    {
        // EDT also raises EditingForbiddenProblem from a file-read failure; the object is editable.
        IBmObject module = bmObject("CommonModule.Open", 8L); //$NON-NLS-1$

        assertNull(MetadataRenameService.supportLockRefusal("Catalog.Open", //$NON-NLS-1$
            Collections.singletonList(refactoringWith(new EditingForbiddenProblem(module)))));
    }

    @Test
    public void testNoProblemsOrOnlyReferenceProblemsDoNotRefuse()
    {
        IRefactoring clean = refactoringWith(mock(CleanReferenceProblem.class));
        IRefactoring noStatus = mock(IRefactoring.class);

        assertNull(MetadataRenameService.supportLockRefusal("Catalog.Open", Arrays.asList(clean, noStatus))); //$NON-NLS-1$
    }

    @Test
    public void testAnUnanswerableCheckCountsAsLocked()
    {
        VendorSupportGuard.setServiceForTests(() -> null);
        IBmObject rights = bmObject("Role.OpenRole.Rights", 9L); //$NON-NLS-1$

        assertNotNull("fail closed", MetadataRenameService.supportLockRefusal("Catalog.Open", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList(refactoringWith(new EditingForbiddenProblem(rights)))));
    }

    @Test
    public void testALockedAdoptedCounterpartIsNamedAndAnEditableOneIsNot()
    {
        EObject lockedCopy = mock(EObject.class);
        EObject openCopy = mock(EObject.class);
        Map<String, EObject> counterparts = new LinkedHashMap<>();
        counterparts.put("the extension 'Locked'", lockedCopy); //$NON-NLS-1$
        counterparts.put("the extension 'Open'", openCopy); //$NON-NLS-1$

        assertEquals("EDT renames every counterpart and checks none of them", //$NON-NLS-1$
            Collections.singletonList("the extension 'Locked'"), //$NON-NLS-1$
            MetadataRenameService.lockedCounterparts(counterparts, object -> object != lockedCopy));
    }

    @Test
    public void testNoCounterpartsNothingLocked()
    {
        assertTrue(MetadataRenameService.lockedCounterparts(Collections.emptyMap(), object -> false).isEmpty());
    }

    private static IBmObject bmObject(String fqn, long id)
    {
        IBmObject object = mock(IBmObject.class);
        IBmEngine engine = mock(IBmEngine.class);
        when(object.bmGetEngine()).thenReturn(engine);
        when(object.bmGetId()).thenReturn(Long.valueOf(id));
        when(object.bmIsTop()).thenReturn(Boolean.TRUE);
        when(object.bmGetFqn()).thenReturn(fqn);
        when(engine.getObjectById(id)).thenReturn(object);
        return object;
    }

    private static IRefactoring refactoringWith(com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem problem)
    {
        RefactoringStatus status = new RefactoringStatus();
        status.addProblem(problem);
        IRefactoring refactoring = mock(IRefactoring.class);
        when(refactoring.getStatus()).thenReturn(status);
        return refactoring;
    }
}
