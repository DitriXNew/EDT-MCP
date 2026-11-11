/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

public class BearerAuthenticationTest
{
    private final BearerAuthentication authentication = new BearerAuthentication("secret-token");

    @Test
    public void requiresOneBearerHeaderWithTheExactToken()
    {
        assertTrue(authentication.accepts(List.of("Bearer secret-token")));
        assertTrue(authentication.accepts(List.of("bearer secret-token")));
        assertFalse(authentication.accepts(null));
        assertFalse(authentication.accepts(List.of()));
        for (String header : List.of("secret-token", "Basic secret-token", "Bearer wrong-token",
            "Bearer secret-token-extra", "Bearer secret", "Bearer  secret-token", "Bearer secret-token ",
            "Bearer secret-token, Bearer wrong-token", "Bearer " + "x".repeat(4097)))
        {
            assertFalse(authentication.accepts(List.of(header)));
        }
    }

    @Test
    public void rejectsDuplicateAuthorizationHeadersEvenWhenOneIsCorrect()
    {
        assertFalse(authentication.accepts(List.of("Bearer secret-token", "Bearer wrong-token")));
        assertFalse(authentication.accepts(List.of("Bearer wrong-token", "Bearer secret-token")));
        assertFalse(authentication.accepts(List.of("Bearer secret-token", "Bearer secret-token")));
    }

    @Test
    public void localLegacyModeDoesNotRequireAHeader()
    {
        assertTrue(new BearerAuthentication(null).accepts(null));
    }
}
