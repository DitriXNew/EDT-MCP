// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.TechLogTest;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogCapture;

public class ManageTechLogToolTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ManageTechLogTool tool(ConsentDecision decision, boolean readOnly, AtomicInteger asks)
    {
        return new ManageTechLogTool(new TechLogCapture(temporary.getRoot().toPath().resolve("conf/logcfg.xml"), List.of()))
        {
            @Override protected ConsentDecision requireConsent(ConsentPreview preview) { asks.incrementAndGet(); return decision; }
            @Override protected boolean isReadOnly() { return readOnly; }
        };
    }
    @Test public void contract()
    {
        var t = tool(ConsentDecision.REJECT, false, new AtomicInteger());
        assertEquals("manage_tech_log", t.getName()); assertEquals(ResponseType.MARKDOWN, t.getResponseType());
        assertFalse(com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier.classify(t.getName()).getReadOnlyHint());
        assertTrue(t.getDescription().contains("get_tool_guide('manage_tech_log')"));
    }
    @Test public void previewIsReadOnlyAndShowsGeneratingCostAndTarget()
    {
        AtomicInteger asks = new AtomicInteger(); var t = tool(ConsentDecision.REJECT, false, asks);
        String r = t.execute(Map.of("mode", "preview", "profile", "memory"));
        assertTrue(r, r.contains("<mem")); assertTrue(r.contains("<leaks")); assertTrue(r.contains("Cost"));
        assertTrue(r.contains("service-account")); assertEquals(0, asks.get());
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("conf")));
    }
    @Test public void readOnlyRefusesEveryWriterBeforeConsentOrFilesystem()
    {
        AtomicInteger asks = new AtomicInteger(); var t = tool(ConsentDecision.ALLOW, true, asks);
        for (String mode : List.of("start", "stop", "export"))
            assertTrue(t.execute(Map.of("mode", mode)).contains("Read-only mode refuses"));
        var unrestricted = tool(ConsentDecision.ALLOW, false, asks);
        assertTrue(unrestricted.execute(Map.of("mode", "start", "readOnly", "true")).contains("Read-only mode refuses"));
        assertEquals(0, asks.get()); assertEquals(0, temporary.getRoot().list().length);
    }
    @Test public void startConsentRefusalWritesNoConfigBackupOrManifest()
    {
        AtomicInteger asks = new AtomicInteger(); var t = tool(ConsentDecision.REJECT, false, asks);
        Path location = temporary.getRoot().toPath().resolve("capture");
        String r = t.execute(Map.of("mode", "start", "location", location.toString()));
        assertTrue(r, r.contains("declined")); assertEquals(1, asks.get());
        assertFalse("Consent errors must stay a plain error message, not nested serialized envelopes",
            com.google.gson.JsonParser.parseString(r).getAsJsonObject().get("error").getAsString().contains("\"success\""));
        assertFalse(Files.exists(location)); assertFalse(Files.exists(temporary.getRoot().toPath().resolve("conf/logcfg.xml")));
    }
    @Test public void stopConsentRefusalPreservesActiveConfigThenRestore()
    {
        AtomicInteger asks = new AtomicInteger(); var allow = tool(ConsentDecision.ALLOW, false, asks);
        Path location = temporary.getRoot().toPath().resolve("capture");
        assertTrue(allow.execute(Map.of("mode", "start", "location", location.toString())).contains("Config written"));
        Path target = temporary.getRoot().toPath().resolve("conf/logcfg.xml"); byte[] before;
        try { before = Files.readAllBytes(target); } catch (Exception e) { throw new AssertionError(e); }
        String r = tool(ConsentDecision.REJECT, false, asks).execute(Map.of("mode", "stop"));
        assertTrue(r, r.contains("declined"));
        try { assertArrayEquals(before, Files.readAllBytes(target)); } catch (Exception e) { throw new AssertionError(e); }
        assertTrue(allow.execute(Map.of("mode", "stop")).contains("byte-exactly")); assertFalse(Files.exists(target));
    }
    @Test public void exportConsentAndNonOverwriteAndBomCsv() throws Exception
    {
        AtomicInteger asks = new AtomicInteger();
        String input = TechLogTest.root().resolve("tests/fixtures/tech-log/synthetic.log").toString();
        Path output = temporary.getRoot().toPath().resolve("events.csv");
        Map<String, String> p = Map.of("mode", "export", "path", input, "outputPath", output.toString());
        assertTrue(tool(ConsentDecision.REJECT, false, asks).execute(p).contains("declined")); assertFalse(Files.exists(output));
        var allow = tool(ConsentDecision.ALLOW, false, asks); assertTrue(allow.execute(p).contains("Rows: 15"));
        byte[] bytes = Files.readAllBytes(output); assertEquals((byte)0xef, bytes[0]); assertEquals((byte)0xbb, bytes[1]); assertEquals((byte)0xbf, bytes[2]);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("MemoryPeak"));
        assertTrue(allow.execute(p).contains("never overwrite")); assertArrayEquals(bytes, Files.readAllBytes(output));
        Path groups = output.resolveSibling("groups.csv");
        assertTrue(allow.execute(Map.of("mode", "export", "path", input, "outputPath", groups.toString(), "exportKind", "groups")).contains("CSV exported"));
        assertTrue(Files.readString(groups).contains("managed-deadlocks"));
    }
    @Test public void invalidModesProfileThresholdAndExportDestinationRefuse()
    {
        var t = tool(ConsentDecision.ALLOW, false, new AtomicInteger());
        for (Map<String, String> p : List.of(Map.of("mode", "wat"), Map.of("profile", "missing"), Map.of("thresholdMs", "-1"),
            Map.of("rotationMb", "0"), Map.of("extraEvents", "DB*"), Map.of("mode", "export")))
        { String r = t.execute(p); assertTrue(r, r.contains("\"success\":false")); assertTrue(r.contains("preview")); }
    }
}
