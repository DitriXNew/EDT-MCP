/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

/**
 * The request-body read of {@link HttpTransport}. This runs inside the developer's IDE JVM, so
 * the size cap is the whole point: the previous reader accumulated line by line until EOF, which
 * a large or endless body turns into unbounded growth while holding a worker (#563).
 */
public class HttpTransportTest
{
    private static final int CAP = HttpTransport.MAX_BODY_BYTES;

    @Test
    public void testABodyUnderTheCapIsReturnedVerbatim() throws IOException
    {
        // Pretty-printed JSON is ordinary MCP traffic, and the newlines inside it must survive:
        // the reader this replaced joined lines and silently dropped every one of them.
        String body = "{\n  \"jsonrpc\": \"2.0\",\n  \"method\": \"tools/list\"\n}";
        StubExchange exchange = new StubExchange(body.getBytes(StandardCharsets.UTF_8));

        assertEquals(body, HttpTransport.readBody(exchange));
    }

    @Test
    public void testABodyOfExactlyTheCapIsAccepted() throws IOException
    {
        StubExchange exchange = new StubExchange(filled(CAP));

        String read = HttpTransport.readBody(exchange);

        assertEquals(CAP, read.length());
    }

    @Test
    public void testADeclaredContentLengthOverTheCapIsRefusedWithoutReadingTheStream()
        throws IOException
    {
        // The refusal has to happen BEFORE the read, or the memory is already spent by the time
        // the answer is decided. The container drains the unread stream when the exchange closes.
        StubExchange exchange = new StubExchange(new byte[0]);
        exchange.getRequestHeaders().add("Content-Length", Long.toString(CAP + 1L));

        assertNull(HttpTransport.readBody(exchange));
        assertFalse("an oversized declared length must not open the request body",
            exchange.wasBodyRead());
    }

    @Test
    public void testAnUnderstatedContentLengthIsStillCappedByTheRead() throws IOException
    {
        // Chunked transfer sends no Content-Length at all, and a lying one is just as cheap to
        // send - so the declared value is an optimization, never the enforcement.
        StubExchange understated = new StubExchange(filled(CAP + 1));
        understated.getRequestHeaders().add("Content-Length", "10");
        assertNull(HttpTransport.readBody(understated));
        assertTrue(understated.wasBodyRead());

        StubExchange undeclared = new StubExchange(filled(CAP + 1));
        assertNull(HttpTransport.readBody(undeclared));
    }

    @Test
    public void testAMalformedContentLengthFallsThroughToTheBoundedRead() throws IOException
    {
        StubExchange small = new StubExchange("{}".getBytes(StandardCharsets.UTF_8));
        small.getRequestHeaders().add("Content-Length", "not-a-number");
        assertEquals("{}", HttpTransport.readBody(small));

        StubExchange oversized = new StubExchange(filled(CAP + 1));
        oversized.getRequestHeaders().add("Content-Length", "not-a-number");
        assertNull(HttpTransport.readBody(oversized));
    }

    private static byte[] filled(int size)
    {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte)'x');
        return bytes;
    }

    /** A loopback bind, where the shared token is optional. */
    private static final boolean LOOPBACK = false;

    /** A listener on every interface, which only opened because a token was set. */
    private static final boolean REMOTE = true;

    @Test
    public void testAConfiguredTokenWithSurroundingWhitespaceStillAuthorizes()
    {
        // The header can only ever carry the trimmed credential - the authorizer trims what is
        // presented, and an HTTP field value cannot preserve surrounding whitespace anyway.
        // Comparing it against an untrimmed preference would lock the operator out of a server
        // that looks correctly configured, so the configured value is trimmed too.
        assertTrue("a padded preference must accept the Bearer form", //$NON-NLS-1$
            HttpTransport.isAuthorized("  s3cret  ", "Bearer s3cret", LOOPBACK)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a padded preference must accept the raw form", //$NON-NLS-1$
            HttpTransport.isAuthorized("  s3cret  ", "s3cret", REMOTE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the scheme is case-insensitive per RFC 6750", //$NON-NLS-1$
            HttpTransport.isAuthorized(" s3cret ", "bearer s3cret", REMOTE)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTrimmingTheConfiguredTokenDoesNotWidenWhatItAccepts()
    {
        // The other edge of the same change: trimming must not turn the token into a prefix
        // match or let a different secret through.
        assertFalse("a different secret must still be rejected", //$NON-NLS-1$
            HttpTransport.isAuthorized(" s3cret ", "Bearer s3cre", REMOTE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("inner whitespace is part of the token, not padding", //$NON-NLS-1$
            HttpTransport.isAuthorized(" s3 cret ", "Bearer s3cret", REMOTE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a configured token still demands a header", //$NON-NLS-1$
            HttpTransport.isAuthorized(" s3cret ", null, LOOPBACK)); //$NON-NLS-1$
    }

    @Test
    public void testARemoteListenerRefusesEveryRequestOnceTheTokenIsGone()
    {
        // The preference page saves a changed token WITHOUT restarting the server, so a listener
        // on every interface can outlive the token that allowed it to open. Whether the operator
        // cleared the field or left blanks in it, the answer is the same: refuse, rather than
        // serve the network unauthenticated because "no token means auth is off".
        for (String erased : new String[] { null, "", "   ", "\t\n" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertFalse("a remote listener must not serve without a token", //$NON-NLS-1$
                HttpTransport.isAuthorized(erased, null, REMOTE));
            assertFalse("nor with any credential the caller invents", //$NON-NLS-1$
                HttpTransport.isAuthorized(erased, "Bearer anything", REMOTE)); //$NON-NLS-1$
            assertFalse("nor with an empty one, which must not match the empty preference", //$NON-NLS-1$
                HttpTransport.isAuthorized(erased, "Bearer ", REMOTE)); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheLoopbackDefaultStillNeedsNoToken()
    {
        // The other direction of the same rule: the default bind is protected by being loopback,
        // and requiring a token there would break every existing local setup.
        for (String erased : new String[] { null, "", "   ", "\t\n" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("no token on loopback means authentication is off", //$NON-NLS-1$
                HttpTransport.isAuthorized(erased, null, LOOPBACK));
            assertTrue("and a client sending one anyway is still served", //$NON-NLS-1$
                HttpTransport.isAuthorized(erased, "Bearer anything", LOOPBACK)); //$NON-NLS-1$
        }
    }

    @Test
    public void testNormalizeTokenIsWhatBothDecisionsRead()
    {
        assertEquals("", HttpTransport.normalizeToken(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", HttpTransport.normalizeToken("  \t ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("s3cret", HttpTransport.normalizeToken(" s3cret ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The smallest exchange {@link HttpTransport#readBody} can be asked about: the request
     * headers, the request body, and whether the body was opened at all. Everything else throws,
     * so a future read of some other part of the exchange cannot pass unnoticed.
     */
    private static final class StubExchange extends HttpExchange
    {
        private final Headers requestHeaders = new Headers();
        private final InputStream body;
        private boolean bodyRead;

        StubExchange(byte[] body)
        {
            this.body = new ByteArrayInputStream(body);
        }

        boolean wasBodyRead()
        {
            return bodyRead;
        }

        @Override
        public Headers getRequestHeaders()
        {
            return requestHeaders;
        }

        @Override
        public InputStream getRequestBody()
        {
            bodyRead = true;
            return body;
        }

        @Override
        public void close()
        {
            // nothing to release
        }

        @Override
        public Headers getResponseHeaders()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI getRequestURI()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRequestMethod()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpContext getHttpContext()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream getResponseBody()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendResponseHeaders(int code, long responseLength)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getResponseCode()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getProtocol()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getAttribute(String name)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttribute(String name, Object value)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setStreams(InputStream input, OutputStream output)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpPrincipal getPrincipal()
        {
            throw new UnsupportedOperationException();
        }
    }
}
