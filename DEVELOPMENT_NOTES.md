# MCP Server Plugin for 1C:EDT - Development Notes

## Overview

This document describes the problems encountered during development of the MCP (Model Context Protocol) server plugin for 1C:EDT and their solutions.

---

## Problem 1: Guice/Wiring Dependency Issues

### Symptoms
```
java.lang.ClassNotFoundException: com._1c.g5.wiring.binder.IServiceAwareAnnotatedBindingBuilder
```

The plugin failed to load because it tried to use EDT's internal wiring/Guice injection system.

### Root Cause
EDT uses internal packages (`com._1c.g5.wiring`, `com._1c.g5.wiring.binder`) for dependency injection. These packages are NOT exported to external plugins - they are internal implementation details.

### Solution
**Switched from Guice injection to OSGi ServiceTracker pattern.**

Before (broken):
```java
@Inject
private IV8ProjectManager v8ProjectManager;

@Inject
private IDtProjectManager dtProjectManager;
```

After (working):
```java
private ServiceTracker<IV8ProjectManager, IV8ProjectManager> v8ProjectManagerTracker;
private ServiceTracker<IDtProjectManager, IDtProjectManager> dtProjectManagerTracker;

@Override
public void start(BundleContext context) throws Exception {
    v8ProjectManagerTracker = new ServiceTracker<>(context, IV8ProjectManager.class, null);
    v8ProjectManagerTracker.open();
    // ...
}

public IV8ProjectManager getV8ProjectManager() {
    return v8ProjectManagerTracker.getService();
}
```

### Files Changed
- `Activator.java` - Completely rewritten to use ServiceTracker
- `MANIFEST.MF` - Removed `com._1c.g5.wiring`, `com._1c.g5.wiring.binder`, `com.google.inject` from Import-Package
- `ExternalDependenciesModule.java` - Deleted (was Guice module, no longer needed)

---

## Problem 2: Wrong EDT Version Display

### Symptoms
The `get_edt_version` tool returned "1.34.0.454" instead of "2025.2.0.454".

### Root Cause
The code was using bundle version from the EDT core bundle, which has a different versioning scheme.

### Solution
Use `System.getProperty("eclipse.buildId")` which returns the actual EDT build ID.

```java
public static String getEdtVersion() {
    // First try eclipse.buildId - this is the EDT version
    String buildId = System.getProperty("eclipse.buildId");
    if (buildId != null && !buildId.isEmpty()) {
        return buildId; // Returns "2025.2.0.454"
    }
    // Fallback to bundle version...
}
```

---

## Problem 3: getConfigurationProperties Hanging

### Symptoms
Calling `get_configuration_properties` tool would hang indefinitely.

### Root Cause
EDT services (IV8ProjectManager, IDtProjectManager, IConfigurationProvider) require access from the UI thread. The HTTP server handler was calling these services from a worker thread.

### Solution
Wrap service access in `Display.syncExec()`:

```java
public static String getConfigurationProperties(String projectName) {
    final String[] result = new String[1];
    
    Display display = Display.getDefault();
    if (display.getThread() == Thread.currentThread()) {
        // Already on UI thread
        result[0] = getConfigurationPropertiesInternal(projectName);
    } else {
        // Need to switch to UI thread
        display.syncExec(() -> {
            result[0] = getConfigurationPropertiesInternal(projectName);
        });
    }
    
    return result[0];
}
```

---

## Problem 4: Request Counter Not Updating

### Symptoms
The status bar showed counter stuck at [1] and didn't increment.

### Root Cause
The counter was declared as `volatile long` but was being accessed from multiple threads without proper synchronization. The increment operation (`counter++`) is not atomic.

### Solution
Changed to `AtomicLong` for thread-safe atomic operations:

```java
private final AtomicLong requestCount = new AtomicLong(0);

public long getRequestCount() { 
    return requestCount.get(); 
}

public void incrementRequestCount() { 
    requestCount.incrementAndGet(); 
}
```

---

## Problem 5: Status Bar Not Appearing

### Symptoms
After adding `org.eclipse.ui.menus` extension, the status bar widget wasn't visible.

### Root Cause
Incorrect structure for status bar contribution. Eclipse requires a specific nested structure with `<toolbar>` inside `<menuContribution>`.

### Solution
Analyzed working plugin (Workmate from `com.e1c.edt.ai.ui`) and copied the exact structure:

```xml
<extension point="org.eclipse.ui.menus">
   <menuContribution locationURI="toolbar:org.eclipse.ui.trim.status">
      <toolbar id="com.ditrix.edt.mcp.server.statusBar">
         <control class="com.ditrix.edt.mcp.server.ui.McpStatusContribution"
               id="com.ditrix.edt.mcp.server.status"/>
      </toolbar>
   </menuContribution>
</extension>
```

Key learnings from Workmate plugin:
- Use `toolbar:org.eclipse.ui.trim.status` as locationURI
- Nest `<control>` inside `<toolbar>` inside `<menuContribution>`
- Use `isDynamic() { return true; }` in the control contribution class
- Use negative margins: `marginHeight = -5`, `marginBottom = -5`

---

## Architecture Decisions

### Why OSGi ServiceTracker Instead of Guice?

1. **Compatibility** - EDT's internal wiring packages are not exported
2. **Simplicity** - No external dependencies on injection frameworks
3. **Standard OSGi Pattern** - Works across all Eclipse versions
4. **Explicit Control** - Clear lifecycle management of service references

### Why HTTP Server Instead of STDIO?

1. **Easier Debugging** - Can test with curl, Postman, browser
2. **Multiple Clients** - Can serve multiple AI assistants simultaneously
3. **Status Monitoring** - Easy to check if server is running
4. **Standard Protocol** - HTTP + JSON-RPC is well-supported

---

## Key Files Overview

| File | Purpose |
|------|---------|
| `Activator.java` | Plugin activator, manages ServiceTrackers and MCP server lifecycle |
| `McpServer.java` | HTTP server with MCP JSON-RPC handlers |
| `McpStatusContribution.java` | Status bar widget with colored indicator |
| `McpServerPreferencePage.java` | Preferences page for server configuration |
| `PreferenceConstants.java` | Preference keys and defaults |
| `PreferenceInitializer.java` | Default preference values |

---

## Useful Commands

### Build Plugin
```bash
cd c:/Users/qww/MCP-EDT/mcp-edt-plugins/mcp
export PATH="$PATH:/c/ProgramData/chocolatey/lib/maven/apache-maven-3.9.12/bin"
mvn clean verify
```

### Test MCP Server
```bash
# Initialize
curl -X POST http://localhost:8765 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2025-03-26","clientInfo":{"name":"test"}},"id":1}'

# List tools
curl -X POST http://localhost:8765 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":2}'

# Call tool
curl -X POST http://localhost:8765 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_edt_version"},"id":3}'
```

---

## Version Information

- **Plugin Version**: 1.0.0
- **Author**: DitriX
- **MCP Protocol Version**: 2025-03-26
- **Target EDT Version**: 2025.2 (Ruby)
- **Target Eclipse Version**: 2023-12
