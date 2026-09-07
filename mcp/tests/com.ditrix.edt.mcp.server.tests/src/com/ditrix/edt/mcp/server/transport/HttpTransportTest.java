/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
    public void testAnEndpointIsCalledOutExactlyWhenItWouldRefuseItsOwnClient()
    {
        // What the preferences page asks before it advertises a URL and hands out a config for
        // it. The one state that answers yes is the one nothing else on that page can see: the
        // listener is up on every interface, the token that allowed it to open has been cleared,
        // and every request now fails closed.
        for (String erased : new String[] { null, "", "   ", "\t\n" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("a remote listener with no token refuses the client it describes", //$NON-NLS-1$
                HttpTransport.refusesItsOwnConfiguration(erased, REMOTE));
            assertFalse("the same missing token on loopback is simply auth turned off", //$NON-NLS-1$
                HttpTransport.refusesItsOwnConfiguration(erased, LOOPBACK));
        }
        assertFalse("a remote listener WITH a token serves the config that carries it", //$NON-NLS-1$
            HttpTransport.refusesItsOwnConfiguration("s3cret", REMOTE)); //$NON-NLS-1$
        assertFalse("and so does a loopback one", //$NON-NLS-1$
            HttpTransport.refusesItsOwnConfiguration("s3cret", LOOPBACK)); //$NON-NLS-1$
        // The credential it asks about is the one the copy button produces, blanks and all -
        // a token stored with surrounding whitespace is presented trimmed and must still pass.
        assertFalse("a padded token is presented as it is compared", //$NON-NLS-1$
            HttpTransport.refusesItsOwnConfiguration("  s3cret  ", REMOTE)); //$NON-NLS-1$
    }

    @Test
    public void testATokenWithNoBytesIsNotAUsableOne()
    {
        // A header field value is BYTES. A code point above U+00FF has none, so no client can
        // send one: WHATWG fetch throws on a header value that is not a byte string, and the
        // JDK's own HttpClient refuses it too. Stored, such a token looks configured and locks
        // the endpoint out with nothing on screen to say why - the state the page must not
        // advertise as ready to paste.
        assertFalse("a Cyrillic token has no bytes to send", //$NON-NLS-1$
            HttpTransport.isTransportSafeToken("\u043f\u0430\u0440\u043e\u043b\u044c")); //$NON-NLS-1$
        assertFalse("nor does anything past the last byte-valued code point", //$NON-NLS-1$
            HttpTransport.isTransportSafeToken("\u0100")); //$NON-NLS-1$
        assertFalse("nor is a control byte field-content", //$NON-NLS-1$
            HttpTransport.isTransportSafeToken("a\u0001b")); //$NON-NLS-1$
        assertFalse("DEL included", HttpTransport.isTransportSafeToken("a\u007fb")); //$NON-NLS-1$ //$NON-NLS-2$

        // The printable ASCII range is what a header carries - punctuation and spaces included,
        // since the credential is everything after "Bearer " and an inner space survives.
        assertTrue("printable ASCII is exactly what a header carries", //$NON-NLS-1$
            HttpTransport.isTransportSafeToken("s3cret-~!@#$%^&*()_+ =/")); //$NON-NLS-1$
        assertTrue("no token is nothing to send, so nothing can go wrong", //$NON-NLS-1$
            HttpTransport.isTransportSafeToken("")); //$NON-NLS-1$
        assertTrue("and an absent one is the same case", //$NON-NLS-1$
            HttpTransport.isTransportSafeToken(null));
    }

    @Test
    public void testALatin1TokenIsNotCondemnedForBeingUnusualLookingAlone()
    {
        // The other edge of the same rule, and the one it is easy to get wrong by over-reaching.
        // Latin-1 IS a byte, and it round-trips with the clients this page hands a config to:
        // fetch and Python requests both serialise a header value as ISO-8859-1, which is how
        // the JDK's server decodes it back. Warning here would tell someone to replace a
        // credential that works, so the rule stops at the byte boundary and not at ASCII.
        assertTrue("an accented token is a byte a client can send", //$NON-NLS-1$
            HttpTransport.isTransportSafeToken("\u00e9")); //$NON-NLS-1$
        assertTrue("and so is the very last byte-valued code point", //$NON-NLS-1$
            HttpTransport.isTransportSafeToken("\u00ff")); //$NON-NLS-1$
        assertFalse("so the page must not warn about it on loopback", //$NON-NLS-1$
            HttpTransport.refusesItsOwnConfiguration("\u00e9", LOOPBACK)); //$NON-NLS-1$
        assertFalse("nor on a remote listener, where it authorizes just the same", //$NON-NLS-1$
            HttpTransport.refusesItsOwnConfiguration("\u00e9", REMOTE)); //$NON-NLS-1$
        // Which is only true because the authorizer really does accept it - the gate must not be
        // deciding something isAuthorized would answer differently.
        assertTrue("the authorizer itself accepts the header that carries it", //$NON-NLS-1$
            HttpTransport.isAuthorized("\u00e9", "Bearer \u00e9", REMOTE)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnUnsendableTokenIsCalledOutOnEveryBind()
    {
        // Unlike the empty-token lockout, this one does not depend on where the server is bound:
        // the client cannot present the credential to a loopback listener either. Both binds must
        // warn, so the page never labels that configuration ready.
        assertTrue("a token that cannot be sent refuses a remote listener", //$NON-NLS-1$
            HttpTransport.refusesItsOwnConfiguration("\u043f\u0430\u0440\u043e\u043b\u044c", REMOTE)); //$NON-NLS-1$
        assertTrue("and a loopback one, where it is equally unpresentable", //$NON-NLS-1$
            HttpTransport.refusesItsOwnConfiguration("\u043f\u0430\u0440\u043e\u043b\u044c", LOOPBACK)); //$NON-NLS-1$
        // The surrounding blanks are dropped before the question is asked, so a padded ASCII
        // token is still usable and must not be caught by this rule.
        assertFalse("trimming happens first, so padding is not a foreign character", //$NON-NLS-1$
            HttpTransport.refusesItsOwnConfiguration("  s3cret  ", LOOPBACK)); //$NON-NLS-1$
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
    public void testTheCorsPreflightIsTheOneMethodThatNeedNotAuthenticate()
    {
        // A browser sends OPTIONS with no credentials by design - it is asking what it may send.
        // Authenticating it would answer 401 to every browser client before it could present the
        // token, which is now mandatory for a remote bind.
        assertFalse("the preflight must not be authenticated", //$NON-NLS-1$
            HttpTransport.requiresAuthorization("OPTIONS")); //$NON-NLS-1$

        // The other edge: nothing else is exempt, and the exemption is not a spelling trick.
        for (String method : new String[] { "GET", "POST", "DELETE", "PUT", "HEAD", "options", "Options" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        {
            assertTrue(method + " must still carry the token", //$NON-NLS-1$
                HttpTransport.requiresAuthorization(method));
        }
    }

    @Test
    public void testThePreflightAllowsTheHeadersAClientHasToSend()
    {
        // A browser may not send a header the preflight did not allow, nor read one that was not
        // exposed - so an allow-list without these is the same as no browser client at all.
        StubExchange exchange = new StubExchange(new byte[0]);
        exchange.getRequestHeaders().add("Origin", "http://localhost:3000"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a localhost origin is allowed", HttpTransport.addCorsHeaders(exchange)); //$NON-NLS-1$

        String allowed = exchange.getResponseHeaders().getFirst("Access-Control-Allow-Headers"); //$NON-NLS-1$
        assertNotNull("the preflight must advertise an allow-list", allowed); //$NON-NLS-1$
        assertTrue("the shared token travels in Authorization: " + allowed, //$NON-NLS-1$
            allowed.contains("Authorization")); //$NON-NLS-1$
        assertTrue("the session id travels in Mcp-Session-Id: " + allowed, //$NON-NLS-1$
            allowed.contains("Mcp-Session-Id")); //$NON-NLS-1$
        assertEquals("and the session id must be readable back", "Mcp-Session-Id", //$NON-NLS-1$ //$NON-NLS-2$
            exchange.getResponseHeaders().getFirst("Access-Control-Expose-Headers")); //$NON-NLS-1$
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
        private final Headers responseHeaders = new Headers();
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
            // Real, because addCorsHeaders writes here and the test reads back what it wrote.
            return responseHeaders;
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
