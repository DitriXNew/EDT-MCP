package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import com.sun.net.httpserver.HttpExchange;

/** Writes one selected tool response; closing an IPC response leaves its connection open. */
public interface ToolCallResponder extends AutoCloseable
{
    void send(String response) throws IOException;
    @Override default void close() { }

    record Http(HttpExchange exchange) implements ToolCallResponder
    {
        @Override public void send(String response) throws IOException
        {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }
        @Override public void close() { exchange.close(); }
    }

    record Ipc(Consumer<String> sink) implements ToolCallResponder
    {
        @Override public void send(String response) throws IOException
        {
            try { sink.accept(response); }
            catch (UncheckedIOException e) { throw e.getCause(); }
        }
    }
}
