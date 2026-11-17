// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.ditrix.edt.mcp.server.utils.ExternalProcess;

/** Short OS helpers use argument arrays, bounded completion and the shared pipe drain. */
public interface WindowCommands
{
    record Answer(int code, String output, String error) { }
    boolean available(String command);
    Answer run(List<String> command) throws Exception;
    default Answer run(List<String> command, long timeoutMillis) throws Exception { return run(command); }

    default void require(String command, String packageName)
    {
        if (!available(command))
        {
            throw new IllegalArgumentException("Missing " + command + "; install the " + packageName + " package and retry.");
        }
    }

    default String checked(List<String> command) throws Exception
    {
        Answer result = run(command);
        if (result.code() != 0)
        {
            throw new IllegalStateException(command.get(0) + " failed: " + result.error().strip());
        }
        return result.output();
    }

    static WindowCommands system()
    {
        return new WindowCommands()
        {
            @Override public boolean available(String command)
            {
                String path = System.getenv("PATH");
                if (path == null) { return false; }
                for (String entry : path.split(java.io.File.pathSeparator))
                {
                    if (Files.isExecutable(Path.of(entry, command))) { return true; }
                }
                return false;
            }
            @Override public Answer run(List<String> command) throws Exception
            {
                return run(command, 3000);
            }
            @Override public Answer run(List<String> command, long timeoutMillis) throws Exception
            {
                ExternalProcess.Result result = ExternalProcess.await(new ProcessBuilder(command).start(), timeoutMillis,
                    process -> process.getOutputStream().close());
                return new Answer(result.timedOut() ? -1 : result.exitCode(),
                    new String(result.stdout(), StandardCharsets.UTF_8), new String(result.stderr(), StandardCharsets.UTF_8));
            }
        };
    }
}
