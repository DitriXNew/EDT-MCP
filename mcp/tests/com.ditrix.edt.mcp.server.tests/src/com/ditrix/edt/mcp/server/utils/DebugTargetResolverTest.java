/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.model.IDebugTarget;
import org.junit.Test;

/**
 * Headless tests for {@link DebugTargetResolver} — the unified resolver that maps
 * every accepted {@code applicationId} form to one underlying Eclipse debug target.
 *
 * <p>Exercised here: null/empty-safety and the CANONICAL snapshot-key policy
 * ({@link DebugTargetResolver#canonicalIdFor}) against mocked launch/server views —
 * the policy that guarantees ONE snapshot key per session whichever id form a tool
 * call used. The synthetic-prefix classification authority is
 * {@link LaunchConfigUtils#isSyntheticApplicationId} (covered by
 * {@link LaunchConfigUtilsSyntheticIdTest}). Actual target resolution needs a live
 * debug session (no {@code IRuntimeDebugClientTargetManager} service and no active
 * launches exist in the Tycho headless runtime), so it can only assert that
 * resolution yields {@code null} rather than throwing.
 */
public class DebugTargetResolverTest
{
    // --- resolve(): null-safety / headless behavior ---

    @Test
    public void testResolveNullReturnsNull()
    {
        // No active sessions headless → blank id auto-resolves to nothing.
        assertNull(DebugTargetResolver.resolve(null));
    }

    @Test
    public void testResolveEmptyReturnsNull()
    {
        assertNull(DebugTargetResolver.resolve("")); //$NON-NLS-1$
    }

    @Test
    public void testResolveUnknownConcreteIdReturnsNull()
    {
        // A concrete id with no matching session resolves to null (never throws),
        // for every id form.
        assertNull(DebugTargetResolver.resolve("ServerApplication.Nope")); //$NON-NLS-1$
        assertNull(DebugTargetResolver.resolve("attach:Nope")); //$NON-NLS-1$
        assertNull(DebugTargetResolver.resolve("launch:Nope")); //$NON-NLS-1$
        assertNull(DebugTargetResolver.resolve("real-or-bare-nope")); //$NON-NLS-1$
    }

    @Test
    public void testServerTargetForTargetNullIsNull()
    {
        assertNull(DebugTargetResolver.serverTargetForTarget(null));
    }

    // --- canonicalIdFor(): ONE snapshot key per session ---

    private static final String MINTED_ID = "ServerApplication.App"; //$NON-NLS-1$

    /** Mocks a debug target owned by a launch with the given configuration. */
    private static IDebugTarget mockTargetWithConfig(ILaunchConfiguration config)
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.getLaunchConfiguration()).thenReturn(config);
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.getLaunch()).thenReturn(launch);
        return target;
    }

    /** Mocks a launch configuration of the given type id, name and (optional) real app id. */
    private static ILaunchConfiguration mockConfig(String typeId, String name, String realAppId)
        throws Exception
    {
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(type.getIdentifier()).thenReturn(typeId);
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getType()).thenReturn(type);
        when(config.getName()).thenReturn(name);
        when(config.getAttribute(eq(LaunchConfigUtils.ATTR_APPLICATION_ID), nullable(String.class)))
            .thenReturn(realAppId);
        return config;
    }

    /** The server-target view of a target, with the minted ServerApplication.<app> id. */
    private static DebugServerTargetSupport.ServerTarget serverView(IDebugTarget target)
    {
        return new DebugServerTargetSupport.ServerTarget(target, MINTED_ID, "App", null); //$NON-NLS-1$
    }

    @Test
    public void testCanonicalIdPrefersOwningLaunchIdOverMintedId() throws Exception
    {
        // The core case: a session owned by an EDT launch (snapshot key 'launch:Cfg')
        // that is ALSO visible through the server view must NOT canonicalize to the
        // minted ServerApplication.<app> id — that split one session across two
        // snapshot keys (resume cleared one, wait_for_break read the other).
        ILaunchConfiguration config =
            mockConfig(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID, "Cfg", null); //$NON-NLS-1$
        IDebugTarget target = mockTargetWithConfig(config);
        assertEquals(LaunchConfigUtils.LAUNCH_APP_ID_PREFIX + "Cfg", //$NON-NLS-1$
            DebugTargetResolver.canonicalIdFor(target, serverView(target)));
    }

    @Test
    public void testCanonicalIdUsesRealApplicationIdWhenPresent() throws Exception
    {
        ILaunchConfiguration config =
            mockConfig(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID, "Cfg", "real-app-uuid"); //$NON-NLS-1$ //$NON-NLS-2$
        IDebugTarget target = mockTargetWithConfig(config);
        assertEquals("real-app-uuid", //$NON-NLS-1$
            DebugTargetResolver.canonicalIdFor(target, serverView(target)));
    }

    @Test
    public void testCanonicalIdUsesAttachIdForAttachConfig() throws Exception
    {
        ILaunchConfiguration config =
            mockConfig(LaunchConfigUtils.TYPE_LOCAL_RUNTIME, "Att", null); //$NON-NLS-1$
        IDebugTarget target = mockTargetWithConfig(config);
        assertEquals(LaunchConfigUtils.ATTACH_APP_ID_PREFIX + "Att", //$NON-NLS-1$
            DebugTargetResolver.canonicalIdFor(target, serverView(target)));
    }

    @Test
    public void testCanonicalIdFallsBackToMintedIdWithoutLaunch()
    {
        // A pure server target (no owning Eclipse launch) keys by the minted id —
        // the only id the registry ever sees for it (snapshots are injected).
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.getLaunch()).thenReturn(null);
        assertEquals(MINTED_ID, DebugTargetResolver.canonicalIdFor(target, serverView(target)));
    }

    @Test
    public void testCanonicalIdFallsBackToMintedIdForNonEdtLaunch() throws Exception
    {
        // A launch outside the 1C/EDT namespace yields no launch-based id —
        // the minted id is the canonical key.
        ILaunchConfiguration config =
            mockConfig("org.eclipse.jdt.launching.localJavaApplication", "JavaApp", null); //$NON-NLS-1$ //$NON-NLS-2$
        IDebugTarget target = mockTargetWithConfig(config);
        assertEquals(MINTED_ID, DebugTargetResolver.canonicalIdFor(target, serverView(target)));
    }

    @Test
    public void testCanonicalIdConsistentAcrossBothViews() throws Exception
    {
        // The same session must canonicalize to the SAME key whether it was located
        // through the launch view (no server view attached) or the server view.
        ILaunchConfiguration config =
            mockConfig(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID, "Cfg", null); //$NON-NLS-1$
        IDebugTarget target = mockTargetWithConfig(config);
        String viaLaunchView = DebugTargetResolver.canonicalIdFor(target, null);
        String viaServerView = DebugTargetResolver.canonicalIdFor(target, serverView(target));
        assertEquals(LaunchConfigUtils.LAUNCH_APP_ID_PREFIX + "Cfg", viaLaunchView); //$NON-NLS-1$
        assertEquals(viaLaunchView, viaServerView);
    }

    @Test
    public void testCanonicalIdNullSafety()
    {
        assertNull(DebugTargetResolver.canonicalIdFor(null, null));
        assertEquals(MINTED_ID,
            DebugTargetResolver.canonicalIdFor(null, serverView(mock(IDebugTarget.class))));
    }
}
