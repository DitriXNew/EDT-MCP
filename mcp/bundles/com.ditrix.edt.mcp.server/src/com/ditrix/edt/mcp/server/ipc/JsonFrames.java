/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Newline-delimited UTF-8. Socket reads and writes deliberately do not use Channels streams:
 * those streams share a blocking lock and can deadlock a full-duplex connection. */
public final class JsonFrames
{
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private JsonFrames() { }

    public static String read(InputStream input) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1)
        {
            if (next == '\n') { return decode(bytes.toByteArray()); }
            if (bytes.size() >= MAX_BYTES) { throw new IOException("Frame exceeds 16 MiB"); }
            bytes.write(next);
        }
        if (bytes.size() != 0) { throw new EOFException("Truncated JSON-RPC frame"); }
        return null;
    }

    public static String decode(byte[] bytes) throws CharacterCodingException
    {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().stripTrailing();
    }

    public static void write(OutputStream output, String json) throws IOException
    {
        byte[] bytes = encode(json);
        synchronized (output) { output.write(bytes); output.flush(); }
    }

    private static byte[] encode(String json) throws IOException
    {
        if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) { throw new IOException("Noncompact frame"); }
        byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES + 1) { throw new IOException("Frame exceeds 16 MiB"); }
        return bytes;
    }

    public static final class Socket implements AutoCloseable
    {
        private final SocketChannel channel;
        private final Object writeLock = new Object();
        private final ByteBuffer buffer = ByteBuffer.allocate(8192);
        public Socket(SocketChannel channel) { this.channel = channel; buffer.limit(0); }
        public void connect(java.net.SocketAddress address) throws IOException { channel.connect(address); }
        public String read() throws IOException
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true)
            {
                if (!buffer.hasRemaining())
                {
                    buffer.clear();
                    if (channel.read(buffer) < 0)
                    {
                        if (bytes.size() != 0) { throw new EOFException("Truncated IPC frame"); }
                        return null;
                    }
                    buffer.flip();
                }
                while (buffer.hasRemaining())
                {
                    byte next = buffer.get();
                    if (next == '\n') { return decode(bytes.toByteArray()); }
                    if (bytes.size() >= MAX_BYTES) { throw new IOException("Frame exceeds 16 MiB"); }
                    bytes.write(next);
                }
            }
        }
        public void write(String json) throws IOException
        {
            ByteBuffer bytes = ByteBuffer.wrap(encode(json));
            synchronized (writeLock) { while (bytes.hasRemaining()) { channel.write(bytes); } }
        }
        @Override public void close() throws IOException { channel.close(); }
    }
}
