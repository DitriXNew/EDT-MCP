package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;

public class JsonFramesTest extends StdioTestSupport
{
    @Test public void framesPreserveCyrillicAndCoalescedMessages() throws Exception
    {
        String data = "{\"text\":\"Справочник.Номенклатура Ёж\"}";
        var bytes = new ByteArrayOutputStream(); JsonFrames.write(bytes, data); JsonFrames.write(bytes, "{}");
        var input = new ByteArrayInputStream(bytes.toByteArray());
        assertEquals(data, JsonFrames.read(input)); assertEquals("{}", JsonFrames.read(input)); assertNull(JsonFrames.read(input));
        assertEquals(data, JsonFrames.read(new ByteArrayInputStream((data + "\r\n").getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void rejectsTruncatedMalformedAndOversizedFrames() throws Exception
    {
        assertThrows(EOFException.class, () -> JsonFrames.read(new ByteArrayInputStream(new byte[]{'{'})));
        assertThrows(IOException.class, () -> JsonFrames.read(new ByteArrayInputStream(new byte[]{(byte) 0xff, 10})));
        assertThrows(IOException.class, () -> JsonFrames.write(new ByteArrayOutputStream(), "{}\n{}"));
        byte[] oversized = new byte[JsonFrames.MAX_BYTES + 1]; Arrays.fill(oversized, (byte)'a');
        assertThrows(IOException.class, () -> JsonFrames.read(new ByteArrayInputStream(oversized)));
    }
}
