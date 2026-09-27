/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * The branch-binding decision of {@code update_database} (#459): a target is refused only when
 * the current branch binds infobases and the target's infobase is not one of them.
 */
public class BranchInfobaseBindingTest
{
    private static InfobaseReference ref(String name, UUID uuid)
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn(name);
        when(ref.getUuid()).thenReturn(uuid);
        return ref;
    }

    private static Optional<IInfobaseAssociation> bound(InfobaseReference... infobases)
    {
        IInfobaseAssociation association = mock(IInfobaseAssociation.class);
        when(association.getInfobases()).thenReturn(Arrays.asList(infobases));
        return Optional.of(association);
    }

    @Test
    public void testNoBindingIsNotAConflict()
    {
        assertNull(BranchInfobaseBinding.decide(Optional.empty(), ref("A", UUID.randomUUID()), //$NON-NLS-1$
            "P", "App", "ServerApplication.S")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testAnAssociationWithoutInfobasesIsNotAConflict()
    {
        IInfobaseAssociation association = mock(IInfobaseAssociation.class);
        when(association.getInfobases()).thenReturn(Collections.emptyList());
        assertNull(BranchInfobaseBinding.decide(Optional.of(association), ref("A", UUID.randomUUID()), //$NON-NLS-1$
            "P", "App", "id")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testBoundTargetPassesMatchedByUuidNotByName()
    {
        UUID uuid = UUID.randomUUID();
        // A standalone server reports its own web reference: same UUID, different display name.
        assertNull(BranchInfobaseBinding.decide(bound(ref("Other", UUID.randomUUID()), ref("Registered", uuid)), //$NON-NLS-1$ //$NON-NLS-2$
            ref("Server view", uuid), "P", "App", "ServerApplication.S")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testSameNameDifferentUuidIsRefused()
    {
        assertNotNull(BranchInfobaseBinding.decide(bound(ref("Base", UUID.randomUUID())), //$NON-NLS-1$
            ref("Base", UUID.randomUUID()), "P", "App", "id")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testUnboundTargetIsRefusedWithTheWayOut()
    {
        String refusal = BranchInfobaseBinding.decide(bound(ref("BranchB", UUID.randomUUID())), //$NON-NLS-1$
            ref("BaseA", UUID.randomUUID()), "Proj", "Server A", "ServerApplication.Server A"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("'BaseA'")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("bound: BranchB")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("ServerApplication.Server A")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("'Proj'")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was updated")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("get_applications")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("ignoreBranchBinding=true")); //$NON-NLS-1$
    }

    @Test
    public void testUnresolvableTargetUnderABindingIsRefused()
    {
        String refusal = BranchInfobaseBinding.decide(bound(ref("BranchB", UUID.randomUUID())), null, //$NON-NLS-1$
            "P", "App", "id"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("could not be resolved")); //$NON-NLS-1$
    }
}
