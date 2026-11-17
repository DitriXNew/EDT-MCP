/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ditrix.edt.mcp.server.ipc.JsonFrames;

/**
 * The stdio bootstrap's stdout is written by several threads (the request thread, the attach worker,
 * the reply threads). Their frames go through one {@link JsonFrames.Writer}, whose own lock keeps every
 * frame whole - the lock the bootstrap used to take on the stream object it was handed.
 */
public class JsonFramesWriterTest
{
    /** Hands every byte over one at a time and yields often, so unguarded writers interleave. */
    private static final class BytewiseStream extends OutputStream
    {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int count;

        @Override
        public void write(int b)
        {
            synchronized (bytes)
            {
                bytes.write(b);
            }
            if (++count % 16 == 0)
            {
                Thread.yield();
            }
        }

        @Override
        public void write(byte[] b, int off, int len)
        {
            for (int i = off; i < off + len; i++)
            {
                write(b[i]);
            }
        }

        byte[] toByteArray()
        {
            synchronized (bytes)
            {
                return bytes.toByteArray();
            }
        }
    }

    @Test(timeout = 60_000)
    public void testConcurrentFramesThroughOneWriterStayWhole() throws Exception
    {
        BytewiseStream stream = new BytewiseStream();
        JsonFrames.Writer writer = new JsonFrames.Writer(stream);
        int threads = 6;
        int framesEach = 40;
        Set<String> expected = new HashSet<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        try
        {
            for (int t = 0; t < threads; t++)
            {
                String payload = "{\"writer\":" + t + ",\"text\":\"" + String.valueOf((char)('a' + t)).repeat(300) + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                expected.add(payload);
                runs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < framesEach; i++)
                    {
                        writer.write(payload);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs)
            {
                run.get(30, TimeUnit.SECONDS);
            }
        }
        finally
        {
            pool.shutdownNow();
        }

        ByteArrayInputStream input = new ByteArrayInputStream(stream.toByteArray());
        int frames = 0;
        String frame;
        while ((frame = JsonFrames.read(input)) != null)
        {
            assertTrue("a frame was torn by a concurrent writer: " + frame, expected.contains(frame)); //$NON-NLS-1$
            frames++;
        }
        assertEquals(threads * framesEach, frames);
    }
}
