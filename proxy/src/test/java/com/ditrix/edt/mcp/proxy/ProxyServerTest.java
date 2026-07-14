/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.junit.Test;

/**
 * Unit tests for {@link ProxyServer#isLoopbackRequest}: the address-check helper behind
 * {@code POST /admin/shutdown}'s loopback-only guard (issue #253 follow-up, spec section 7/8).
 * A genuinely non-loopback remote socket cannot be faked in an in-process integration test (see
 * {@link AdminShutdownIT} for the loopback-accepted wire behaviour), so the {@code 403} branch
 * is proven directly on this helper instead.
 */
public class ProxyServerTest
{
    @Test
    public void testNullRemoteAddressIsNotLoopback()
    {
        assertFalse(ProxyServer.isLoopbackRequest(null));
    }

    @Test
    public void testLoopbackIpv4AddressIsAccepted()
    {
        InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 54321);

        assertTrue(ProxyServer.isLoopbackRequest(loopback));
    }

    @Test
    public void testExplicit127AddressIsAccepted() throws UnknownHostException
    {
        InetAddress explicitLoopback = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });

        assertTrue(ProxyServer.isLoopbackRequest(new InetSocketAddress(explicitLoopback, 1)));
    }

    @Test
    public void testNonLoopbackAddressIsRejected() throws UnknownHostException
    {
        InetAddress remote = InetAddress.getByAddress(new byte[] { 8, 8, 8, 8 });

        assertFalse(ProxyServer.isLoopbackRequest(new InetSocketAddress(remote, 54321)));
    }
}
