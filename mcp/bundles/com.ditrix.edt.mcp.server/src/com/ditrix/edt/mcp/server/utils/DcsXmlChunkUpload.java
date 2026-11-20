/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Server-side staging for {@code dcs action='replace', type='schema'} sent in parts as
 * {@code body:{xmlChunk, offset, totalChars, uploadId}} - the write-side mirror of the paged
 * {@code format='xml'} get.
 *
 * <p>Nothing here writes anything. A chunk that does not complete the document is only kept in
 * memory; the chunk that brings the received length to {@code totalChars} hands the assembled
 * document back to the caller, which sends it through the unchanged whole-document
 * {@code body.xml} replace (same {@code expectedHash} check, same lossless round-trip compare,
 * same consent, preset and write barrier). Staging ends on that commit hand-off, on any refused
 * chunk, and on expiry; an upload is never resumed after it ended.
 *
 * <p>The {@code uploadId} is issued HERE on the first chunk (a random UUID) and is the only key to an
 * upload: every later chunk, and an offset-0 restart, must carry it, and an id that is not active is
 * refused. Two callers therefore cannot reach one staging by choosing the same id - with a
 * caller-chosen id, a second caller's restart silently replaced the first caller's prefix and the
 * first caller's suffix completed a mixed document.
 *
 * <p>Offsets and lengths are Java {@code char}s (UTF-16 code units), the unit of the paged get.
 * A chunk boundary inside a surrogate pair is harmless: the document is assembled by plain
 * concatenation, so the two halves are adjacent again before anything parses the XML.
 */
public final class DcsXmlChunkUpload
{
    /** The chunk text member of {@code body}. */
    public static final String KEY_CHUNK = "xmlChunk"; //$NON-NLS-1$
    /** Zero-based character position of the chunk in the whole document. */
    public static final String KEY_OFFSET = "offset"; //$NON-NLS-1$
    /** Character length of the whole document; reaching it commits. */
    public static final String KEY_TOTAL_CHARS = "totalChars"; //$NON-NLS-1$
    /** Server-issued id that ties the chunks of one document together; omitted on a new first chunk. */
    public static final String KEY_UPLOAD_ID = "uploadId"; //$NON-NLS-1$

    private static final String KEY_XML = "xml"; //$NON-NLS-1$
    private static final String KEY_BODY = "body"; //$NON-NLS-1$

    /**
     * Largest document one upload may assemble: 4 MiB of characters. The whole-document
     * {@code body.xml} replace arrives in one HTTP request, which the transport caps at 4 MiB of
     * UTF-8 bytes ({@code HttpTransport.MAX_BODY_BYTES}), so 4 MiB of characters is at least
     * everything that channel can carry - the chunked channel must not refuse a document the whole
     * channel accepts. One upload therefore holds at most 8 MiB of UTF-16 text.
     */
    public static final int MAX_TOTAL_CHARS = 4 * 1024 * 1024;

    /** Uploads staged at the same time; with {@link #MAX_TOTAL_CHARS} this bounds staging to 32 MiB. */
    public static final int MAX_UPLOADS = 4;

    /** An upload that receives no chunk for this long is dropped on the next access. */
    public static final long IDLE_EXPIRY_MINUTES = 10;

    private static final long IDLE_EXPIRY_NANOS = TimeUnit.MINUTES.toNanos(IDLE_EXPIRY_MINUTES);
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{20}"); //$NON-NLS-1$
    private static final Pattern HASH_HEADER = Pattern.compile("\\A\\*\\*Hash:\\*\\* `([0-9a-f]{20})`"); //$NON-NLS-1$
    private static final int MAX_ECHO_CHARS = 80;
    private static final String RESTART = "Start again at body.offset=0 without uploadId."; //$NON-NLS-1$
    private static final Set<String> CHUNK_MEMBERS =
        Set.of(KEY_CHUNK, KEY_OFFSET, KEY_TOTAL_CHARS, KEY_UPLOAD_ID);

    /** The staging every {@code dcs} call shares. */
    public static final DcsXmlChunkUpload SHARED = new DcsXmlChunkUpload(System::nanoTime);

    private final LongSupplier clock;
    private final Supplier<String> ids;
    private final Map<String, Staging> uploads = new LinkedHashMap<>();

    /** @param clock monotonic nanoseconds; tests drive expiry through it */
    public DcsXmlChunkUpload(LongSupplier clock)
    {
        this(clock, () -> UUID.randomUUID().toString());
    }

    /**
     * @param clock monotonic nanoseconds
     * @param ids issues a new, unguessable uploadId
     */
    DcsXmlChunkUpload(LongSupplier clock, Supplier<String> ids)
    {
        this.clock = clock;
        this.ids = ids;
    }

    /**
     * @param body the raw {@code body} argument, may be {@code null}
     * @return whether it is a JSON object carrying {@code xmlChunk} - the chunked replace
     */
    public static boolean isChunkBody(String body)
    {
        if (body == null)
        {
            return false;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() && parsed.getAsJsonObject().has(KEY_CHUNK);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /** Reads the CURRENT hash of the target before the first chunk is staged. */
    @FunctionalInterface
    public interface HashProbe
    {
        /** @return the current hash, or the read's error */
        ProbeResult currentHash();
    }

    /** A {@link HashProbe} outcome: a hash, or the error JSON of the read. */
    public static final class ProbeResult
    {
        private final String hash;
        private final String errorJson;

        private ProbeResult(String hash, String errorJson)
        {
            this.hash = hash;
            this.errorJson = errorJson;
        }

        /** @return a successful read of {@code hash} */
        public static ProbeResult hash(String hash)
        {
            return new ProbeResult(hash, null);
        }

        /** @return a failed read, reported to the caller as is */
        public static ProbeResult error(String errorJson)
        {
            return new ProbeResult(null, errorJson);
        }

        /**
         * Reads the hash from a Markdown {@code dcs get} of the root, which starts with
         * {@code **Hash:** `...`}; an error payload is passed through.
         *
         * @param getResult the result of the root read
         * @return the probe outcome
         */
        public static ProbeResult fromRootRead(String getResult)
        {
            if (getResult == null || ToolResult.isErrorPayload(getResult))
            {
                return error(getResult == null ? ToolResult.error("The current schema hash could not " //$NON-NLS-1$
                    + "be read. Retry the chunk at body.offset=0.").toJson() : getResult); //$NON-NLS-1$
            }
            Matcher matcher = HASH_HEADER.matcher(getResult);
            if (!matcher.find())
            {
                return error(ToolResult.error("The current schema hash could not be read from dcs " //$NON-NLS-1$
                    + "action='get'. Retry the chunk at body.offset=0; nothing was staged.").toJson()); //$NON-NLS-1$
            }
            return hash(matcher.group(1));
        }
    }

    /** One chunked call: where it goes and what it carries. */
    public record Request(String projectName, String target, String action, String type,
        boolean pointer, String expectedHash, boolean pagingParameters, JsonObject body)
    {
    }

    /** The answer to one chunk: a staged acknowledgement, a refusal, or the assembled document. */
    public static final class Step
    {
        private final String response;
        private final String xml;

        private Step(String response, String xml)
        {
            this.response = response;
            this.xml = xml;
        }

        /** @return whether the document is complete and must now be committed */
        public boolean isComplete()
        {
            return xml != null;
        }

        /** @return the Markdown acknowledgement or the error JSON; {@code null} when complete */
        public String response()
        {
            return response;
        }

        /** @return the assembled document; {@code null} unless complete */
        public String xml()
        {
            return xml;
        }

        /**
         * @param params the arguments of the completing chunk
         * @return the same arguments with {@code body} replaced by {@code {"xml": <assembled>}} -
         *         exactly the whole-document replace call
         */
        public Map<String, String> wholeDocumentParams(Map<String, String> params)
        {
            JsonObject body = new JsonObject();
            body.addProperty(KEY_XML, xml);
            Map<String, String> whole = new LinkedHashMap<>(params);
            whole.put(KEY_BODY, GsonProvider.toJson(body));
            return whole;
        }
    }

    private static final class Staging
    {
        final String projectName;
        final String target;
        final String expectedHash;
        final int totalChars;
        final StringBuilder received;
        long lastTouched;
        /** Bumped by every accepted append; a restart commits only over the state it checked. */
        long version;

        Staging(String projectName, String target, String expectedHash, int totalChars, long now)
        {
            this.projectName = projectName;
            this.target = target;
            this.expectedHash = expectedHash;
            this.totalChars = totalChars;
            this.received = new StringBuilder();
            this.lastTouched = now;
        }
    }

    private record Chunk(String uploadId, String text, int offset, int totalChars)
    {
    }

    /**
     * Accepts one chunk.
     *
     * @param request the call
     * @param probe reads the current hash; consulted only for a first chunk that does not complete
     *            the document (a completing chunk goes straight to the commit, which checks the hash)
     * @return the acknowledgement, the refusal, or the assembled document
     */
    public Step accept(Request request, HashProbe probe)
    {
        String uploadId = uploadIdOf(request.body());
        String shapeError = shapeError(request);
        if (shapeError != null)
        {
            return refuse(uploadId, shapeError);
        }
        Chunk chunk = new Chunk(uploadId, request.body().get(KEY_CHUNK).getAsString(),
            integral(request.body().get(KEY_OFFSET)).intValue(),
            integral(request.body().get(KEY_TOTAL_CHARS)).intValue());
        if ((long)chunk.offset() + chunk.text().length() > chunk.totalChars())
        {
            return refuse(uploadId, "body.xmlChunk ends at character " //$NON-NLS-1$
                + ((long)chunk.offset() + chunk.text().length()) + ", past body.totalChars=" //$NON-NLS-1$
                + chunk.totalChars() + ". totalChars must be the length of the whole document."); //$NON-NLS-1$
        }
        if (chunk.offset() == 0)
        {
            return start(request, chunk, probe);
        }
        return append(request, chunk);
    }

    /**
     * The one rule for ending an upload on a refusal: a refused {@code dcs} call whose {@code body} is a
     * JSON object carrying a string {@code uploadId} ends that upload - whatever else the body holds or
     * lacks (a chunk, a misspelled or missing {@code xmlChunk}, a {@code body.xml}, structured members)
     * and wherever the refusal came from (the staging itself, the tool's argument checks before it, or
     * the structured-body path a body without {@code xmlChunk} takes). A body that names no upload
     * (not an object, not JSON, no string {@code uploadId}) ends nothing: only an id's holder can name it.
     *
     * @param body the raw {@code body} argument of the call
     * @param result the call's result
     */
    public void discardIfRefused(String body, String result)
    {
        if (result == null || body == null || !ToolResult.isErrorPayload(result))
        {
            return;
        }
        JsonElement parsed;
        try
        {
            parsed = JsonParser.parseString(body);
        }
        catch (RuntimeException e)
        {
            return;
        }
        if (parsed.isJsonObject())
        {
            discard(uploadIdOf(parsed.getAsJsonObject()));
        }
    }

    private Step start(Request request, Chunk chunk, HashProbe probe)
    {
        boolean completes = chunk.text().length() == chunk.totalChars();
        if (completes)
        {
            // Claim and remove in one step: of two simultaneous completing restarts only one commits.
            if (chunk.uploadId() != null && !claim(chunk.uploadId()))
            {
                return unknownUpload(chunk);
            }
            return new Step(null, chunk.text());
        }
        long observedVersion = -1;
        Staging observedStaging = null;
        if (chunk.uploadId() != null)
        {
            synchronized (this)
            {
                sweep(clock.getAsLong());
                observedStaging = uploads.get(chunk.uploadId());
                if (observedStaging == null)
                {
                    return unknownUpload(chunk);
                }
                observedVersion = observedStaging.version;
            }
        }
        // The probe reads the model on the UI thread; never hold the staging lock across it.
        ProbeResult current = probe.currentHash();
        if (current.errorJson != null)
        {
            discard(chunk.uploadId());
            return new Step(current.errorJson, null);
        }
        if (!request.expectedHash().equals(current.hash))
        {
            return refuse(chunk.uploadId(), "expectedHash '" + request.expectedHash() //$NON-NLS-1$
                + "' does not match current hash '" + current.hash + "' for '" + request.target() //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Re-run dcs action='get', rebuild the document from it, and start again at " //$NON-NLS-1$
                + "body.offset=0 with the new expectedHash and no uploadId."); //$NON-NLS-1$
        }
        synchronized (this)
        {
            long now = clock.getAsLong();
            sweep(now);
            String uploadId = chunk.uploadId();
            if (uploadId != null)
            {
                Staging live = uploads.get(uploadId);
                if (live == null)
                {
                    // It ended while the hash was being read.
                    return unknownUpload(chunk);
                }
                if (live != observedStaging || live.version != observedVersion)
                {
                    // Another call restarted or continued it meanwhile: neither prefix may be continued.
                    uploads.remove(uploadId);
                    return new Step(ToolResult.error("Upload '" + uploadId + "' changed while this restart " //$NON-NLS-1$ //$NON-NLS-2$
                        + "was being checked: another call restarted or continued it at the same time. " //$NON-NLS-1$
                        + "Upload '" + uploadId + "' was discarded; " + RESTART + " Nothing was written.") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        .toJson(), null);
                }
            }
            if (uploadId == null && uploads.size() >= MAX_UPLOADS)
            {
                // The ids are capabilities: never list another caller's upload here.
                return new Step(ToolResult.error(MAX_UPLOADS + " chunked uploads are already in progress. " //$NON-NLS-1$
                    + "Finish one, restart one of your own at body.offset=0 with its uploadId, or retry " //$NON-NLS-1$
                    + "after one has been idle for " + IDLE_EXPIRY_MINUTES + " minutes. Nothing was " //$NON-NLS-1$ //$NON-NLS-2$
                    + "staged or written.").toJson(), null); //$NON-NLS-1$
            }
            if (uploadId == null)
            {
                uploadId = ids.get();
            }
            Staging staging = new Staging(request.projectName(), request.target(), request.expectedHash(),
                chunk.totalChars(), now);
            staging.received.append(chunk.text());
            // An offset-0 chunk with an active uploadId restarts that upload; only its holder knows the id.
            uploads.remove(uploadId);
            uploads.put(uploadId, staging);
            return staged(request, uploadId, staging);
        }
    }

    private synchronized Step append(Request request, Chunk chunk)
    {
        long now = clock.getAsLong();
        sweep(now);
        Staging staging = uploads.get(chunk.uploadId());
        if (staging == null)
        {
            return unknownUpload(chunk);
        }
        String mismatch = mismatch(staging, request, chunk);
        if (mismatch != null)
        {
            uploads.remove(chunk.uploadId());
            return new Step(ToolResult.error(mismatch + " Upload '" + chunk.uploadId() //$NON-NLS-1$
                + "' was discarded; " + RESTART + " Nothing was written.").toJson(), null); //$NON-NLS-1$ //$NON-NLS-2$
        }
        staging.received.append(chunk.text());
        staging.lastTouched = now;
        staging.version++;
        if (staging.received.length() == staging.totalChars)
        {
            uploads.remove(chunk.uploadId());
            return new Step(null, staging.received.toString());
        }
        return staged(request, chunk.uploadId(), staging);
    }

    private static Step unknownUpload(Chunk chunk)
    {
        return new Step(ToolResult.error("Upload '" + chunk.uploadId() + "' is not in progress " //$NON-NLS-1$ //$NON-NLS-2$
            + "(body.offset=" + chunk.offset() + "): it was committed, refused or idle for " //$NON-NLS-1$ //$NON-NLS-2$
            + IDLE_EXPIRY_MINUTES + " minutes, or this server never issued it. " + RESTART //$NON-NLS-1$
            + " Nothing was written by this call. After a lost answer to a final chunk, re-run dcs " //$NON-NLS-1$
            + "action='get' first: a changed hash means that replace was applied.").toJson(), null); //$NON-NLS-1$
    }

    /** @return whether {@code uploadId} was active and is now removed by this call alone */
    private synchronized boolean claim(String uploadId)
    {
        sweep(clock.getAsLong());
        return uploads.remove(uploadId) != null;
    }

    private static String uploadIdOf(JsonObject body)
    {
        JsonElement id = body.get(KEY_UPLOAD_ID);
        return id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
            && id.getAsString().length() <= MAX_ECHO_CHARS ? id.getAsString() : null;
    }

    private static String mismatch(Staging staging, Request request, Chunk chunk)
    {
        if (!staging.projectName.equals(request.projectName()))
        {
            return "projectName '" + request.projectName() + "' differs from '" + staging.projectName //$NON-NLS-1$ //$NON-NLS-2$
                + "', which started upload '" + chunk.uploadId() + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!staging.target.equals(request.target()))
        {
            return "fqn '" + request.target() + "' differs from '" + staging.target //$NON-NLS-1$ //$NON-NLS-2$
                + "', which started upload '" + chunk.uploadId() + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!staging.expectedHash.equals(request.expectedHash()))
        {
            return "expectedHash '" + request.expectedHash() + "' differs from '" + staging.expectedHash //$NON-NLS-1$ //$NON-NLS-2$
                + "', which started upload '" + chunk.uploadId() + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (staging.totalChars != chunk.totalChars())
        {
            return "body.totalChars=" + chunk.totalChars() + " differs from " + staging.totalChars //$NON-NLS-1$ //$NON-NLS-2$
                + ", which started upload '" + chunk.uploadId() + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (staging.received.length() != chunk.offset())
        {
            return "body.offset=" + chunk.offset() + " is not where upload '" + chunk.uploadId() //$NON-NLS-1$ //$NON-NLS-2$
                + "' continues: " + staging.received.length() + " characters were received, so the " //$NON-NLS-1$ //$NON-NLS-2$
                + "next chunk must start at body.offset=" + staging.received.length() + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static Step staged(Request request, String uploadId, Staging staging)
    {
        int received = staging.received.length();
        return new Step("**Action:** `replace` (chunk staged, not written)" //$NON-NLS-1$
            + "\n\n**Target:** `" + request.target() + "`" //$NON-NLS-1$ //$NON-NLS-2$
            + "\n\n**Upload id:** `" + uploadId + "`" //$NON-NLS-1$ //$NON-NLS-2$
            + "\n\n**Received:** `" + received + "` of `" + staging.totalChars + "` characters" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "\n\n**Next offset:** `" + received + "`" //$NON-NLS-1$ //$NON-NLS-2$
            + "\n\nNothing was written. Send the next chunk with body.offset=" + received //$NON-NLS-1$
            + ", body.uploadId=\"" + uploadId + "\" (this server issued it; send it with every later " //$NON-NLS-1$ //$NON-NLS-2$
            + "chunk) and the same totalChars and expectedHash; the chunk that reaches totalChars " //$NON-NLS-1$
            + "replaces the schema. Staging expires after " + IDLE_EXPIRY_MINUTES //$NON-NLS-1$
            + " idle minutes.", null); //$NON-NLS-1$
    }

    private Step refuse(String uploadId, String message)
    {
        boolean discarded = uploadId != null && discard(uploadId);
        return new Step(ToolResult.error(message + (discarded
            ? " Upload '" + uploadId + "' was discarded; " + RESTART + " Nothing was written." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            : " Nothing was staged or written.")).toJson(), null); //$NON-NLS-1$
    }

    private synchronized boolean discard(String uploadId)
    {
        return uploadId != null && uploads.remove(uploadId) != null;
    }

    private void sweep(long now)
    {
        for (Iterator<Staging> it = uploads.values().iterator(); it.hasNext();)
        {
            if (now - it.next().lastTouched >= IDLE_EXPIRY_NANOS)
            {
                it.remove();
            }
        }
    }

    /** @return the ids staged right now, oldest first (expired ones swept) */
    public synchronized List<String> uploadIds()
    {
        sweep(clock.getAsLong());
        return new ArrayList<>(uploads.keySet());
    }

    /** @return the characters staged under {@code uploadId}, or {@code -1} when none */
    public synchronized int receivedChars(String uploadId)
    {
        sweep(clock.getAsLong());
        Staging staging = uploads.get(uploadId);
        return staging == null ? -1 : staging.received.length();
    }

    private static String shapeError(Request request)
    {
        JsonObject body = request.body();
        if (body.has(KEY_XML))
        {
            return "body.xmlChunk and body.xml are mutually exclusive. Send the whole document as " //$NON-NLS-1$
                + "body={\"xml\":\"...\"}, or send it in parts as body={\"xmlChunk\":\"...\",\"offset\":0," //$NON-NLS-1$
                + "\"totalChars\":N} (later chunks add the uploadId the first answer returns)."; //$NON-NLS-1$
        }
        Set<String> extra = new LinkedHashSet<>(body.keySet());
        extra.removeAll(CHUNK_MEMBERS);
        if (!extra.isEmpty())
        {
            return "body.xmlChunk accepts only offset, totalChars and uploadId beside it, but the body " //$NON-NLS-1$
                + "also contains: " + String.join(", ", extra) + ". Remove them."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!"replace".equals(request.action()) || !"schema".equals(request.type()) || request.pointer()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "body.xmlChunk is not allowed with action='" + request.action() + "', type='" //$NON-NLS-1$ //$NON-NLS-2$
                + request.type() + "', fqn='" + request.target() + "'. Use it only with " //$NON-NLS-1$ //$NON-NLS-2$
                + "action='replace', type='schema', and a bare root FQN without a '#/...' fragment."; //$NON-NLS-1$
        }
        if (request.pagingParameters())
        {
            return "limit/offset apply only to action='get' or action='options'. A chunk's position " //$NON-NLS-1$
                + "goes in body.offset; omit the top-level limit and offset."; //$NON-NLS-1$
        }
        if (request.expectedHash() == null)
        {
            return "expectedHash is required for a chunked replace of '" + request.target() //$NON-NLS-1$
                + "'. Re-run dcs action='get' and pass its current hash with every chunk."; //$NON-NLS-1$
        }
        if (!HASH.matcher(request.expectedHash()).matches())
        {
            return "expectedHash '" + request.expectedHash() //$NON-NLS-1$
                + "' is invalid. Re-run dcs action='get' and copy its 20-character lowercase hash."; //$NON-NLS-1$
        }
        JsonElement id = body.get(KEY_UPLOAD_ID);
        if (id != null && uploadIdOf(body) == null)
        {
            return "body.uploadId " + describe(id) + " is not an upload id this server issued. Omit it on " //$NON-NLS-1$ //$NON-NLS-2$
                + "a new first chunk and send the uploadId returned by that chunk's answer with every later chunk."; //$NON-NLS-1$
        }
        JsonElement text = body.get(KEY_CHUNK);
        if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString() || text.getAsString().isEmpty())
        {
            return "body.xmlChunk " + describe(text) + " must be a non-empty string: the next part of " //$NON-NLS-1$ //$NON-NLS-2$
                + "the complete DataCompositionSchema XML."; //$NON-NLS-1$
        }
        Integer offset = integral(body.get(KEY_OFFSET));
        if (offset == null || offset.intValue() < 0)
        {
            return "body.offset " + describe(body.get(KEY_OFFSET)) + " must be a whole number >= 0: " //$NON-NLS-1$ //$NON-NLS-2$
                + "the character position of this chunk in the whole document (0 for the first)."; //$NON-NLS-1$
        }
        if (offset.intValue() > 0 && id == null)
        {
            return "body.uploadId is required for body.offset=" + offset + ": send the uploadId returned by " //$NON-NLS-1$ //$NON-NLS-2$
                + "the first chunk's answer. " + RESTART; //$NON-NLS-1$
        }
        Integer total = integral(body.get(KEY_TOTAL_CHARS));
        if (total == null || total.intValue() < 1)
        {
            return "body.totalChars " + describe(body.get(KEY_TOTAL_CHARS)) + " must be a whole number >= 1: " //$NON-NLS-1$ //$NON-NLS-2$
                + "the character length of the whole document, the same on every chunk."; //$NON-NLS-1$
        }
        if (total.intValue() > MAX_TOTAL_CHARS)
        {
            return "body.totalChars=" + total + " exceeds the chunked-upload limit of " + MAX_TOTAL_CHARS //$NON-NLS-1$ //$NON-NLS-2$
                + " characters. A document that large cannot be replaced through dcs."; //$NON-NLS-1$
        }
        return null;
    }

    private static Integer integral(JsonElement value)
    {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            return null;
        }
        return JsonUtils.parseIntegralInt(value.getAsString());
    }

    private static String describe(JsonElement value)
    {
        if (value == null)
        {
            return "(missing)"; //$NON-NLS-1$
        }
        String text = value.toString();
        return "'" + (text.length() > MAX_ECHO_CHARS ? text.substring(0, MAX_ECHO_CHARS) + "..." : text) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
