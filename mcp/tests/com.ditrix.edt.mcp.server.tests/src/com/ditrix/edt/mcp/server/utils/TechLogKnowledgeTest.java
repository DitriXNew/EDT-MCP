// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.techlog.TechLogKnowledge;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Independent vocabulary snapshot from the supplied platform reference, sections 3.24.2.3/4.
 * https://its.1c.ru/db/v8327doc#bookmark:adm:TI000000397
 * Tests must remain self-contained: research files are not runtime/build dependencies.
 */
public class TechLogKnowledgeTest
{
    private static final List<String> CATEGORIES = List.of("events", "properties", "problems", "profiles",
        "elements", "gotchas");
    private static final Map<String, String> SELECTORS = Map.of("event", "events", "property", "properties",
        "problem", "problems", "profile", "profiles");

    @Test public void everyEventInPlatformEventTableIsDescribed()
    {
        String events = "ADDIN ADMIN ATTN CALL CLSTR CONFLOADFROMFILES CONN DB2 DBCOPIES DBMSSQL "
            + "DBMSSQLCONN DBORACLE DBPOSTGRS DBV8DBENG DHIST EDS EVENTLOG EXCP EXCPCNTX FTEXTCHECK "
            + "FTEXTUPD FTS HASP INPUTBYSTRING LEAKS LIC MAILPARSEERR MEM PROC QERR SCALL SCOM SDBL "
            + "SDGC SESN SINTEG SRVC STORE STT STTADM SYSTEM TDEADLOCK TLOCK TTIMEOUT VIDEOCALL "
            + "VIDEOCONN VIDEOSTATS VRSCACHE VRSREQUEST VRSRESPONSE WINCERT";
        assertEquals(51, events.split(" ").length);
        for (String event : events.split(" "))
            assertTrue(event, TechLogKnowledge.entries("events").has(event));
    }

    @Test public void everyPropertyInPlatformTableHasApplicabilityAndReadingFormat()
    {
        String properties = "Action Admin agentURL All ApplicationExt AudioEncoding AvMem BackgroundJobCreated "
            + "BackupBaseFileName BackupFileName BackupType Body Calls certificate Class Classes Cluster cn cnd "
            + "Component Connection Connections ConnLimit Context Contexts ContextsOnly CopyBytes cpu CpuTime "
            + "Crashed Database DBConnID DBConnStr DBCopy Dbms Dbpid DBUsr DeadlockConnectionIntersections Descr "
            + "description Direction DstAddr DstId DstPid DstSrv dumpError DumpFile Duration Durationus Err errorCode "
            + "ErrorDescr Event Exception ExcessDurationSec ExcessStartTime ExtSrvcUrl ExtSrvcUsr FailedJobsCount "
            + "File FileName Files FilesCount FilesSize FilesTotalSize FillRefsPresent FindByString findTicks Finish "
            + "fixedState Folder Frames FreeMemory ftextResultCount ftextSearchCount ftextTicks fullKey Func Headers "
            + "Host hResultMSOLEDBSQL19 hResultMSOLEDBSQL hResultNC2012 hResultNC2008 hResultNC2005 hResultOLEDB "
            + "IB IBLimit Id IName InBytes Info Infobases InMessage InstanceID JobCanceledByLoadLimit jobId Key Level "
            + "Line Location LockDuration Locks logFrom logTo MDX Memory MemoryLimits MemoryPeak MemoryUsed Message "
            + "MessageUid metaDataId Method MethodName MinDataId MinimalWriteSize MName modelID MyVer Name NeedResync "
            + "NewServiceDataDirectory Nmb NParams Obsolete OldServiceDataDirectory OSException OSThread OutBytes "
            + "OutMessage p:processName PacketCount parallelism Path Phrase PID planSQLText Port Process ProcessId "
            + "ProcessName procURL Query QueryFileds QueueLenght ReadOnlyMode Reason recordCount Recording recordRef "
            + "Ref Regions Registered reindexCount Released Report Request res Result RetExcp Rows RowsAffected "
            + "RunAs rx:Acoustic rx:Grammar rx:Language rx:Location rx:SampleRate rx:Version SafeLimit Sdbl seanceID "
            + "SearchByMask Separation SepId ServerComputerName ServerId ServiceName SessionID Size skippedRecords "
            + "Source Sql SrcAddr SrcId SrcPid srcProcessName SrcURL SrcVer SrvcName State Status StorageGUID "
            + "StreamType SyncPort Sz Szd t:applicationName t:clientID t:computerName t:connectID tableCode tableCount "
            + "tableName tableRef Text Time tooManyResults TotalJobsCount TotalMemory totalRecords Trans "
            + "tx:Acoustic tx:Grammar tx:Language tx:Location tx:SampleRate tx:Version Txt Type URI Url UsedSize "
            + "UseMode Usr Val Value WaitConnections Word Interface Prm depth DstClientID "
            + "Module Meth Data Deficit ByMemory ByTime average_response_time disk_performance memory_performance "
            + "queue_length queue_length/cpu_num response_time address pingTimeout pingPeriod directionID period "
            + "packetsSent avgResponseTime maxResponseTime packetsTimedOut packetsLost packetsLostAndFound";
        for (String property : properties.split(" "))
        {
            JsonObject entry = TechLogKnowledge.entry("properties", property);
            assertFalse(property, entry.getAsJsonArray("appearsIn").isEmpty());
            assertFalse(property, entry.get("read").getAsString().isBlank());
        }
    }

    @Test public void everyRelatedReferenceResolvesAndRendersAsAnAcceptedDescribeSelector()
    {
        int checked = 0;
        for (String category : CATEGORIES)
            for (var item : TechLogKnowledge.entries(category).entrySet())
            {
                JsonObject entry = item.getValue().getAsJsonObject();
                if (!entry.has("related")) continue;
                String rendered = TechLogKnowledge.describe(category, item.getKey());
                for (JsonElement related : entry.getAsJsonArray("related"))
                {
                    String[] ref = related.getAsString().split(":", 2);
                    assertEquals(related.toString(), 2, ref.length);
                    assertTrue(related.toString(), SELECTORS.containsKey(ref[0]));
                    TechLogKnowledge.entry(SELECTORS.get(ref[0]), ref[1]);
                    assertTrue(rendered, rendered.contains("`get_tech_log mode=describe " + ref[0] + "=" + ref[1] + "`"));
                    checked++;
                }
            }
        assertTrue("Exercise related references", checked > 0);
    }

    @Test public void everyThresholdHasItsOwnHttpSourceAlsoPresentInTheEntry()
    {
        int checked = 0;
        for (String category : CATEGORIES)
            for (var item : TechLogKnowledge.entries(category).entrySet())
            {
                JsonObject entry = item.getValue().getAsJsonObject();
                if (!entry.has("thresholds")) continue;
                assertFalse(item.getKey(), entry.getAsJsonArray("thresholds").isEmpty());
                for (JsonElement threshold : entry.getAsJsonArray("thresholds"))
                {
                    JsonObject value = threshold.getAsJsonObject();
                    assertFalse(item.getKey(), value.get("text").getAsString().isBlank());
                    String url = value.get("source").getAsString();
                    URI uri = URI.create(url);
                    assertTrue(url, List.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null);
                    boolean found = false;
                    for (JsonElement source : entry.getAsJsonArray("sources"))
                        found |= source.getAsJsonObject().get("url").getAsString().equals(url);
                    assertTrue(item.getKey() + ": threshold source missing from entry sources", found);
                    checked++;
                }
            }
        assertTrue("Exercise sourced thresholds", checked > 0);
    }

    @Test public void examplesAreSingleJsonRecordsWithOnlyStringValuesAndExplicitProvenance()
    {
        int checked = 0;
        for (String category : CATEGORIES)
            for (var item : TechLogKnowledge.entries(category).entrySet())
            {
                JsonObject entry = item.getValue().getAsJsonObject();
                if (!entry.has("example")) continue;
                String example = entry.get("example").getAsString();
                assertFalse(example, example.contains("\n") || example.contains("\r"));
                JsonElement parsed = JsonParser.parseString(example);
                assertTrue(example, parsed.isJsonObject());
                assertFalse(example, parsed.getAsJsonObject().entrySet().isEmpty());
                for (var value : parsed.getAsJsonObject().entrySet())
                    assertTrue(value.toString(), value.getValue().isJsonPrimitive()
                        && value.getValue().getAsJsonPrimitive().isString());
                if (category.equals("events"))
                    assertEquals(item.getKey(), parsed.getAsJsonObject().get("name").getAsString());
                assertTrue(item.getKey(), entry.get("exampleNote").getAsString().contains("[illustrative]"));
                String rendered = TechLogKnowledge.describe(category, item.getKey());
                assertTrue(rendered, rendered.contains("```json\n" + example + "\n```"));
                checked++;
            }
        assertTrue("Exercise worked records", checked > 0);
    }

    @Test public void rendererAppendsNewFieldsInFixedOrderWithoutSerializedArrays()
    {
        String[] fields = {"example", "thresholds", "causes", "fixExample", "nextSteps", "related"};
        String[] labels = {"Example", "Thresholds", "Causes", "Fix example", "Next steps", "Related"};
        int[] seen = new int[fields.length];
        for (String category : CATEGORIES)
            for (var item : TechLogKnowledge.entries(category).entrySet())
            {
                JsonObject entry = item.getValue().getAsJsonObject();
                String rendered = TechLogKnowledge.describe(category, item.getKey());
                int previous = rendered.indexOf("**Sources:**");
                for (int i = 0; i < fields.length; i++)
                {
                    int position = rendered.indexOf("**" + labels[i] + ":**");
                    if (!entry.has(fields[i])) { assertEquals(rendered, -1, position); continue; }
                    assertTrue(item.getKey() + ": " + fields[i], position > previous);
                    previous = position;
                    seen[i]++;
                    if (fields[i].equals("fixExample"))
                        assertTrue(rendered, rendered.contains("```bsl\n" + entry.get(fields[i]).getAsString() + "\n```"));
                    if (List.of("causes", "nextSteps").contains(fields[i]))
                        for (JsonElement value : entry.getAsJsonArray(fields[i]))
                            assertTrue(rendered, rendered.contains("- " + MarkdownUtils.escapeMarkdown(value.getAsString()) + "\n"));
                }
                assertFalse(rendered, rendered.contains("[\""));
                assertFalse(rendered, rendered.contains("[{\""));
            }
        for (int count : seen) assertTrue("Every optional field must be exercised", count > 0);
    }

    @Test public void individualDescriptionsRemainWithinFourKiB()
    {
        for (String category : CATEGORIES)
            for (String id : TechLogKnowledge.entries(category).keySet())
            {
                int bytes = TechLogKnowledge.describe(category, id).getBytes(StandardCharsets.UTF_8).length;
                assertTrue(category + "/" + id + " renders " + bytes + " bytes", bytes <= 4096);
            }
    }

    @Test public void indexListsIdsPerCategoryWithoutExpandingEntries()
    {
        String index = TechLogKnowledge.describe("events", null);
        for (String category : CATEGORIES)
        {
            assertTrue(index, index.contains("**" + category + ":**"));
            for (String id : TechLogKnowledge.entries(category).keySet())
                assertTrue(id, index.contains(MarkdownUtils.escapeMarkdown(id)));
        }
        assertFalse(index, index.contains("## CALL"));
        assertFalse(index, index.contains("**Sources:**"));
        assertFalse(index, index.contains("**Example:**"));
        assertTrue("Index must stay compact", index.getBytes(StandardCharsets.UTF_8).length < 10000);
    }
}
