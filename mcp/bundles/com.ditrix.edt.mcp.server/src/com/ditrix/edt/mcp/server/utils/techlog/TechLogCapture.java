// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.utils.techlog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.SecureXml;

/** Owns a single recoverable user-level capture; never writes a platform installation. */
public final class TechLogCapture
{
    public static final String MARKER = "EDT-MCP-TECH-LOG";
    private static final String NS = "http://v8.1c.ru/v8/tech-log";
    private final Path target;
    private final List<Path> installations;

    public TechLogCapture(Path target, List<Path> installations)
    { this.target = target; this.installations = List.copyOf(installations); }

    public static TechLogCapture user()
    {
        String local = System.getenv("LOCALAPPDATA");
        Path config = Path.of(local == null ? System.getProperty("user.home") + "/AppData/Local" : local)
            .resolve("1C/1cv8/conf/logcfg.xml");
        return new TechLogCapture(config, List.of(Path.of("C:/Program Files/1cv8"), Path.of("C:/Program Files (x86)/1cv8")));
    }

    public Path target() { return target; }
    private Path state() { return target.resolveSibling("edt-mcp-active-capture.json"); }
    private Path lock()
    { return Path.of(System.getProperty("java.io.tmpdir")).resolve("edt-mcp-tech-log-" + TechLogAnalysis.hash(target.toString()) + ".lock"); }

    private FileChannel lockChannel() throws IOException
    {
        // Windows keeps a delete-pending entry reserved until its handle closes, including on a crash.
        // Other systems keep the stable inode: unlinking an active Unix lock permits a second owner.
        return System.getProperty("os.name", "").startsWith("Windows")
            ? FileChannel.open(lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE)
            : FileChannel.open(lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    public record Options(String profile, Path location, int thresholdMs, List<String> extraEvents,
        String infobase, boolean plans, boolean dbmsLocks, int historyHours, int rotationMb)
    {
        public Options
        {
            TechLogKnowledge.entry("profiles", profile);
            if (location == null || !location.isAbsolute()) throw new IllegalArgumentException("location must be an absolute dedicated directory; preview again.");
            if (thresholdMs < 0 || historyHours < 1 || historyHours > 24 || rotationMb < 1 || rotationMb > 1024)
                throw new IllegalArgumentException("Use thresholdMs >= 0, historyHours 1..24, rotationMb 1..1024; preview again.");
            extraEvents = List.copyOf(extraEvents);
            for (String name : extraEvents) TechLogKnowledge.entry("events", name);
        }
        public List<String> events()
        {
            Set<String> names = new LinkedHashSet<>(TechLogKnowledge.events(profile));
            extraEvents.forEach(n -> names.add(n.toUpperCase(java.util.Locale.ROOT)));
            return List.copyOf(names);
        }
    }

    public static Path defaultLocation()
    { return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("edt-mcp-tech-log").resolve(UUID.randomUUID().toString()); }

    public List<String> shadowing() throws IOException
    {
        List<String> found = new ArrayList<>();
        for (Path root : installations)
        {
            if (!Files.isDirectory(root)) continue;
            try (var versions = Files.list(root))
            {
                for (Path version : versions.sorted().toList())
                {
                    Path config = version.resolve("bin/conf/logcfg.xml");
                    if (Files.isRegularFile(config)) found.add(version.getFileName() + ": " + config);
                }
            }
        }
        return found;
    }

    public static String xml(Options o, String id)
    {
        StringBuilder out = new StringBuilder("<!-- " + MARKER + " BEGIN " + id + " -->\n");
        out.append("<log xmlns=\"").append(NS).append("\" format=\"json\" placement=\"plain\" location=\"")
            .append(escape(o.location().toString())).append("\" history=\"").append(o.historyHours())
            .append("\" rotation=\"size\" rotationsize=\"").append(o.rotationMb()).append("\">\n");
        for (String event : o.events())
        {
            out.append("  <event><eq property=\"name\" value=\"").append(escape(event)).append("\"/>");
            if (!o.infobase().isBlank()) out.append("<eq property=\"p:processName\" value=\"")
                .append(escape(o.infobase())).append("\"/>");
            // Keep incident events and lock acquisitions/transaction boundaries without a duration filter.
            if (o.thresholdMs() > 0 && Set.of("CALL", "SCALL", "DBMSSQL", "DBPOSTGRS", "DBV8DBENG").contains(event))
                out.append("<ge property=\"durationus\" value=\"").append(o.thresholdMs() * 1000L).append("\"/>");
            out.append("</event>\n");
        }
        out.append("  <property name=\"all\"/>\n</log>\n");
        if (o.plans()) out.append("<plansql xmlns=\"").append(NS).append("\"/>\n");
        if (o.dbmsLocks()) out.append("<dbmslocks xmlns=\"").append(NS).append("\"/>\n");
        if (o.events().contains("MEM")) out.append("<mem xmlns=\"").append(NS).append("\"/>\n");
        if (o.events().contains("LEAKS")) out.append("<leaks xmlns=\"").append(NS).append("\" collect=\"1\"><point call=\"server\"/></leaks>\n");
        if (o.events().contains("SCRIPTCIRCREFS")) out.append("<scriptcircrefs xmlns=\"").append(NS).append("\"/>\n");
        out.append("<!-- ").append(MARKER).append(" END ").append(id).append(" -->\n");
        return out.toString();
    }

    private static String escape(String text)
    { return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"); }

    public String preview(Options o) throws Exception
    {
        byte[] original = Files.exists(target) ? Files.readAllBytes(target) : null;
        byte[] generated = insert(original, xml(o, "PREVIEW"));
        String body = decode(generated);
        String fence = MarkdownUtils.codeBlockFence(body);
        StringBuilder out = new StringBuilder("# Capture preview\n\nTarget: " + MarkdownUtils.inlineCode(target.toString())
            + "; existing: " + (original == null ? "absent" : original.length + " bytes; preserved with byte-exact backup")
            + ".\n\nDedicated location: " + MarkdownUtils.inlineCode(o.location().toString()) + ".\n\n"
            + "Platform 8.3.25+ required. The platform re-reads logcfg.xml every 60 s. A service-account cluster does not read this user's LOCALAPPDATA; configure that account separately. "
            + "An infobase filter can exclude client/file events lacking p:processName. Scenario: reproduce once briefly, then stop; no automatic stop timer.\n\n");
        List<String> shadowing = shadowing();
        if (!shadowing.isEmpty()) out.append("WARNING: earlier bin/conf/logcfg.xml shadows this target for: ")
            .append(MarkdownUtils.escapeMarkdown(shadowing.toString())).append("\n\n");
        out.append(fence).append("xml\n").append(body).append("\n").append(fence).append("\n\n")
            .append(MarkdownUtils.tableHeader("Element", "Effect / reason", "Cost"));
        out.append(element("log")).append(element("event")).append(element("property"));
        for (String event : o.events())
        {
            JsonObject knowledge = TechLogKnowledge.entry("events", event);
            out.append(MarkdownUtils.tableRow("event " + event, knowledge.get("meaning").getAsString(), knowledge.get("cost").getAsString()));
        }
        if (o.thresholdMs() > 0) out.append(element("durationus"));
        if (!o.infobase().isBlank()) out.append(element("infobase"));
        if (o.plans()) out.append(element("plansql"));
        if (o.dbmsLocks()) out.append(element("dbmslocks"));
        if (o.events().contains("MEM")) out.append(element("mem"));
        if (o.events().contains("LEAKS")) out.append(element("leaks"));
        if (o.events().contains("SCRIPTCIRCREFS")) out.append(element("scriptcircrefs"));
        out.append("\nProfile reference and source links:\n\n").append(TechLogKnowledge.describe("profiles", o.profile()));
        return out.toString();
    }

    private static String element(String id)
    {
        JsonObject entry = TechLogKnowledge.entry("elements", id);
        return MarkdownUtils.tableRow(id, entry.get("meaning").getAsString() + " " + entry.get("reason").getAsString(),
            entry.get("cost").getAsString() + " Sources: " + entry.get("sources"));
    }

    /** Preserve original bytes during insertion; stop restores the independently saved bytes. */
    public static byte[] insert(byte[] original, String block) throws Exception
    {
        if (original == null) return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config xmlns=\"" + NS + "\">\n"
            + block + "</config>\n").getBytes(StandardCharsets.UTF_8);
        var factory = SecureXml.documentBuilderFactory();
        var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(original));
        String encoding = doc.getXmlEncoding();
        if (encoding != null && !encoding.equalsIgnoreCase("UTF-8") && !encoding.toUpperCase(java.util.Locale.ROOT).startsWith("UTF-16")
            || encoding != null && encoding.toUpperCase(java.util.Locale.ROOT).startsWith("UTF-16") && charset(original).equals(StandardCharsets.UTF_8))
            throw new IOException("Existing logcfg.xml encoding '" + encoding + "' is unsupported for safe insertion; save as UTF-8 or BOM UTF-16 and preview again.");
        if (!"config".equals(doc.getDocumentElement().getLocalName())
            || !NS.equals(doc.getDocumentElement().getNamespaceURI()))
            throw new IOException("Existing logcfg.xml must have config in the technological-log namespace; fix it and preview again.");
        String text = decode(original);
        if (text.contains(MARKER)) throw new IOException("Our capture marker already exists; call manage_tech_log mode=stop first.");
        for (String element : List.of("plansql", "dbmslocks", "mem", "leaks", "scriptcircrefs"))
            if (block.contains("<" + element + " ") && doc.getElementsByTagNameNS(NS, element).getLength() > 0)
                throw new IOException("Foreign logcfg.xml already configures " + element + "; resolve shared generating-element ownership before start.");
        var closing = Pattern.compile("</(?:[A-Za-z_][\\w.-]*:)?config\\s*>").matcher(text);
        int at = -1;
        while (closing.find()) at = closing.start();
        if (at < 0)
        {
            // A self-closing empty root is common. Preserve its prefix and all surrounding bytes.
            var empty = Pattern.compile("<((?:[A-Za-z_][\\w.-]*:)?config)\\b[^>]*?/>").matcher(text);
            if (!empty.find()) throw new IOException("Cannot locate config closing element; expand it and preview again.");
            text = text.substring(0, empty.end() - 2) + ">\n" + block + "</" + empty.group(1) + ">" + text.substring(empty.end());
        }
        else text = text.substring(0, at) + block + text.substring(at);
        byte[] result = encode(text, original);
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(result));
        return result;
    }

    private static Charset charset(byte[] bytes)
    {
        if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) return StandardCharsets.UTF_16LE;
        if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) return StandardCharsets.UTF_16BE;
        return StandardCharsets.UTF_8;
    }
    private static String decode(byte[] bytes) { return new String(bytes, charset(bytes)); }
    private static byte[] encode(String text, byte[] original) { return text.getBytes(charset(original)); }

    public String warning()
    {
        try
        {
            if (Files.exists(target) && decode(Files.readAllBytes(target)).contains(MARKER))
                return "WARNING: capture marker remains in " + MarkdownUtils.inlineCode(target.toString())
                    + "; capture may still be active (including after an EDT restart). Clean it with manage_tech_log mode=stop.\n\n";
            if (Files.exists(state())) return "WARNING: pending capture recovery state in " + state()
                + "; call manage_tech_log mode=status then mode=stop.\n\n";
        }
        catch (IOException e) { return "WARNING: cannot inspect " + target + ": " + e.getMessage() + "\n\n"; }
        return "";
    }

    /** Gate runs inside the cross-process mutex after the exact affected bytes have been computed. */
    public synchronized String start(Options o, Supplier<String> refusal) throws Exception
    {
        Files.createDirectories(target.getParent());
        try (FileChannel channel = lockChannel();
            var held = channel.tryLock())
        {
            if (held == null) throw new IOException("Another capture operation owns " + lock() + "; retry status.");
            if (Files.exists(state())) throw new IOException("Capture state exists: " + state() + "; call status then stop before start.");
            String id = UUID.randomUUID().toString();
            byte[] original = Files.exists(target) ? Files.readAllBytes(target) : null;
            byte[] generated = insert(original, xml(o, id));
            if (Files.exists(o.location()))
            {
                try (var contents = Files.list(o.location()))
                { if (contents.findAny().isPresent()) throw new IOException("Capture location must be empty: " + o.location() + "; use a new dedicated directory."); }
            }
            String denied = refusal.get(); if (denied != null) return denied;
            if (!java.util.Arrays.equals(original, Files.exists(target) ? Files.readAllBytes(target) : null))
                throw new IOException("logcfg.xml changed during consent; preview and start again.");
            Files.createDirectories(o.location());
            Path backup = target.resolveSibling("logcfg.xml.edt-mcp-" + id + ".bak");
            if (original != null) Files.write(backup, original, StandardOpenOption.CREATE_NEW);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("id", id); manifest.addProperty("profile", o.profile());
            manifest.addProperty("startTime", Instant.now().toString()); manifest.addProperty("targetConfigPath", target.toString());
            manifest.addProperty("backupPath", original == null ? "" : backup.toString());
            manifest.addProperty("location", o.location().toString()); manifest.addProperty("originalExisted", original != null);
            manifest.addProperty("originalHash", original == null ? "" : digest(original));
            manifest.addProperty("writtenHash", digest(generated)); manifest.addProperty("status", "prepared");
            JsonObject overrides = new JsonObject();
            overrides.addProperty("thresholdMs", o.thresholdMs()); overrides.addProperty("infobase", o.infobase());
            overrides.addProperty("plans", o.plans()); overrides.addProperty("dbmsLocks", o.dbmsLocks());
            overrides.addProperty("historyHours", o.historyHours()); overrides.addProperty("rotationMb", o.rotationMb());
            var extra = new com.google.gson.JsonArray(); o.extraEvents().forEach(extra::add); overrides.add("extraEvents", extra);
            manifest.add("overrides", overrides);
            var events = new com.google.gson.JsonArray(); o.events().forEach(events::add); manifest.add("events", events);
            // Durable recovery record precedes config mutation. Never overwrite an unrelated manifest/state.
            writeNew(o.location().resolve("edt-mcp-capture.json"), manifest);
            writeNew(state(), manifest);
            atomicWrite(target, generated);
            manifest.addProperty("status", "config-written");
            atomicWrite(state(), json(manifest)); atomicWrite(o.location().resolve("edt-mcp-capture.json"), json(manifest));
            return "Config written: " + target + ". Capture " + id + "; location " + o.location()
                + ". Waiting for pickup (≤60 s); log-file appearance is not yet observed.\n\n"
                + "Shadowing configurations: " + shadowing() + ". Service accounts use their own configuration lookup.\n\n"
                + "Next: manage_tech_log mode=status; reproduce briefly, then mode=stop.";
        }
    }

    public String status() throws Exception
    {
        if (!Files.exists(state())) return "No active capture recovery record. Config exists: " + Files.exists(target)
            + ". " + (warning().isEmpty() ? "Use preview then start." : "Marker without recovery state: use stop for actionable recovery instructions.");
        JsonObject m = readState();
        Path location = Path.of(m.get("location").getAsString());
        boolean config = Files.exists(target) && digest(Files.readAllBytes(target)).equals(m.get("writtenHash").getAsString());
        var index = TechLogReader.read(location, 8, 2, null, null);
        String last = index.events.stream().map(TechLogReader.Event::end).max(java.util.Comparator.naturalOrder())
            .map(Object::toString).orElse("unknown");
        return "Capture " + m.get("id").getAsString() + "; profile " + m.get("profile").getAsString()
            + ".\n\nConfig written (observed exact contents): " + config + "; target " + target
            + ".\n\nLog files appeared: " + (index.files > 0) + "; files=" + index.files
            + "; last event time observed in bounded scan=" + last + ".\n\n"
            + (index.files == 0 ? "Waiting for pickup (≤60 s). If this persists, check shadowing, account and enabled activity." : "Files observed; this alone does not prove all processes picked up this profile.")
            + "\n\n" + TechLogAnalysis.header(index);
    }

    public synchronized String stop(Supplier<String> refusal) throws Exception
    {
        JsonObject orphan = null;
        if (!Files.exists(state()))
        {
            if (Files.exists(target) && decode(Files.readAllBytes(target)).contains(MARKER))
                orphan = recoverOrphan();
            else return "No active capture. Log files are preserved; use get_tech_log with path to analyze them.";
        }
        try (FileChannel channel = lockChannel();
            var held = channel.tryLock())
        {
            if (held == null) throw new IOException("Another capture operation owns " + lock() + "; retry status.");
            JsonObject m = Files.exists(state()) ? readState() : orphan;
            if (m == null) throw new IOException("Capture state disappeared; inspect status and retry stop.");
            byte[] current = Files.exists(target) ? Files.readAllBytes(target) : null;
            byte[] original = m.get("originalExisted").getAsBoolean()
                ? Files.readAllBytes(Path.of(m.get("backupPath").getAsString())) : null;
            if (original != null && !digest(original).equals(m.get("originalHash").getAsString()))
                throw new IOException("Capture backup changed; restore the original backup before retrying stop: " + m.get("backupPath"));
            boolean alreadyRestored = java.util.Arrays.equals(current, original);
            if (!alreadyRestored && (current == null || !digest(current).equals(m.get("writtenHash").getAsString())))
                throw new IOException("Config changed outside capture: " + target + ". Stop refuses to discard foreign edits; reconcile with backup "
                    + m.get("backupPath") + " and restore the byte-exact original before retrying stop.");
            String denied = refusal.get(); if (denied != null) return denied;
            if (!java.util.Arrays.equals(current, Files.exists(target) ? Files.readAllBytes(target) : null))
                throw new IOException("Config changed during consent; inspect status before retrying stop.");
            if (!alreadyRestored)
            {
                if (original == null) Files.delete(target); else atomicWrite(target, original);
            }
            Path location = Path.of(m.get("location").getAsString());
            m.addProperty("stopTime", Instant.now().toString()); m.addProperty("status", "stopped");
            atomicWrite(location.resolve("edt-mcp-capture.json"), json(m));
            Files.deleteIfExists(state());
            if (original != null) Files.delete(Path.of(m.get("backupPath").getAsString()));
            return "Stopped; original config restored byte-exactly (original absence restored when applicable). Log files kept. "
                + "Platform pickup may take ≤60 s.\n\nNext: get_tech_log {\"mode\":\"summary\",\"path\":\""
                + location.toString().replace("\\", "\\\\") + "\"}";
        }
    }

    /** The capture manifest is a second durable recovery receipt if the config-side record was lost. */
    private JsonObject recoverOrphan() throws Exception
    {
        String current = decode(Files.readAllBytes(target));
        var begin = Pattern.compile("<!-- " + MARKER + " BEGIN ([a-f0-9-]{36}) -->").matcher(current);
        if (!begin.find()) throw new IOException("Unrecognized orphan marker in " + target + "; inspect its capture id and byte-exact backup before manual recovery.");
        String id = begin.group(1);
        int end = current.indexOf("<!-- " + MARKER + " END " + id + " -->", begin.end());
        if (end < 0) throw new IOException("Incomplete orphan block in " + target + "; recover from logcfg.xml.edt-mcp-" + id + ".bak after reviewing foreign edits.");
        String block = current.substring(begin.end(), end);
        var factory = SecureXml.documentBuilderFactory();
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
            ("<config xmlns=\"" + NS + "\">" + block + "</config>").getBytes(StandardCharsets.UTF_8)));
        var logs = document.getElementsByTagNameNS(NS, "log");
        if (logs.getLength() != 1) throw new IOException("Cannot identify orphan capture log; inspect " + target);
        Path location = Path.of(((org.w3c.dom.Element)logs.item(0)).getAttribute("location"));
        Path manifest = location.resolve("edt-mcp-capture.json");
        if (!location.isAbsolute() || !Files.isRegularFile(manifest) || Files.size(manifest) > 1024 * 1024)
            throw new IOException("Orphan recovery manifest missing/unreadable: " + manifest + "; review and restore logcfg.xml.edt-mcp-" + id + ".bak before manual cleanup.");
        JsonObject m = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        if (!id.equals(m.get("id").getAsString()) || !target.toString().equals(m.get("targetConfigPath").getAsString()))
            throw new IOException("Orphan recovery identity mismatch: " + manifest + "; review config and backup before retrying stop.");
        validateState(m);
        return m;
    }

    private JsonObject readState() throws IOException
    {
        if (Files.size(state()) > 1024 * 1024) throw new IOException("Capture state too large: " + state());
        JsonObject m = JsonParser.parseString(Files.readString(state())).getAsJsonObject();
        validateState(m);
        return m;
    }
    private void validateState(JsonObject m) throws IOException
    {
        String id = m.get("id").getAsString();
        if (!id.matches("[a-f0-9-]{36}") || !target.toString().equals(m.get("targetConfigPath").getAsString())
            || !Path.of(m.get("location").getAsString()).isAbsolute())
            throw new IOException("Capture recovery target/identity mismatch; inspect " + state());
        String expectedBackup = m.get("originalExisted").getAsBoolean()
            ? target.resolveSibling("logcfg.xml.edt-mcp-" + id + ".bak").toString() : "";
        if (!expectedBackup.equals(m.get("backupPath").getAsString()))
            throw new IOException("Capture backup identity mismatch; inspect " + state());
    }
    private static byte[] json(JsonObject object)
    { return new GsonBuilder().setPrettyPrinting().create().toJson(object).getBytes(StandardCharsets.UTF_8); }
    private static void writeNew(Path path, JsonObject object) throws IOException
    { Files.write(path, json(object), StandardOpenOption.CREATE_NEW); }
    private static String digest(byte[] bytes)
    {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void atomicWrite(Path path, byte[] bytes) throws IOException
    {
        Path temporary = Files.createTempFile(path.getParent(), ".edt-mcp-", ".tmp");
        try
        {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temporary); }
    }
}
