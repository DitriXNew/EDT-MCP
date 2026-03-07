# common-module-type

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `common-module-type` |
| **Title** | Common module type is not set |
| **Description** | Check that common module type is explicitly set |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [469](https://its.1c.ru/db/v8std/content/469/hdoc) |

---

## 🎯 What This Check Does

This check validates that common module properties are explicitly configured, not left at default values. The module type (Server, Client, etc.) should be intentionally set.

### Why This Is Important

- **Explicit intent**: Developers should consciously choose module type
- **Prevents errors**: Default settings may not match intended behavior
- **Standards compliance**: Follows 1C development standards (Standard 469)
- **Code review**: Reviewers can verify module type is appropriate

---

## ❌ Error Example

### Error Message

```
Common module type is not set
```

**Russian:**
```
Тип общего модуля не установлен
```

### Noncompliant XML Configuration

```xml
<!-- ❌ Noncompliant: Module with default/unset type configuration -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>NewModule</name>
  <!-- ❌ No explicit type configuration - using platform defaults -->
  <!-- server, clientManagedApplication, serverCall - not explicitly set -->
</mdclass:CommonModule>
```

### Noncompliant Code Example

```
Configuration/
└── CommonModules/
    └── NewModule/  ❌ Module type not configured
        └── Module.bsl
```

**Module Properties (all defaults):**
- Client (managed application): ✗
- Server: ✓ (default)
- External connection: ✗
- Server call: ✗
- Global: ✗
- Privileged: ✗

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Correct: Server module explicitly configured -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>DataProcessingServer</name>
  <server>true</server>                            <!-- ✅ Explicitly set -->
  <clientManagedApplication>false</clientManagedApplication>  <!-- ✅ Explicitly set -->
  <serverCall>false</serverCall>                   <!-- ✅ Explicitly set -->
</mdclass:CommonModule>

<!-- ✅ Correct: ServerCall module explicitly configured -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>DataServiceServerCall</name>
  <server>true</server>                            <!-- ✅ Explicitly set -->
  <serverCall>true</serverCall>                    <!-- ✅ Explicitly set -->
  <clientManagedApplication>false</clientManagedApplication>
</mdclass:CommonModule>

<!-- ✅ Correct: Client module explicitly configured -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>UserInterfaceClient</name>
  <server>false</server>                           <!-- ✅ Explicitly set -->
  <clientManagedApplication>true</clientManagedApplication>  <!-- ✅ Explicitly set -->
</mdclass:CommonModule>
```

### Correct Module Configuration

**For Server Module:**
```
Configuration/
└── CommonModules/
    └── DataProcessing/  ✅ Explicitly configured as Server
        └── Module.bsl
```

| Property | Value | Explicitly Set? |
|----------|-------|-----------------|
| Server | ✓ | Yes |
| Client | ✗ | Yes |
| Server call | ✗ | Yes |

**For Client Module:**
```
Configuration/
└── CommonModules/
    └── UIHelpersClient/  ✅ Explicitly configured as Client
        └── Module.bsl
```

| Property | Value | Explicitly Set? |
|----------|-------|-----------------|
| Server | ✗ | Yes |
| Client (managed application) | ✓ | Yes |

---

## 📖 Common Module Types

### Available Module Types

| Type | Server | Client | ServerCall | Description |
|------|--------|--------|------------|-------------|
| Server | ✓ | ✗ | ✗ | Runs only on server |
| Client | ✗ | ✓ | ✗ | Runs only on client |
| ClientServer | ✓ | ✓ | ✗ | Runs on both |
| ServerCall | ✓ | ✗ | ✓ | Called from client, runs on server |

### Choosing the Right Type

```
┌──────────────────────────────────────────────────────────┐
│           Which module type do you need?                 │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  Does code access database?                              │
│  ├── Yes ──► Does client call it directly?               │
│  │           ├── Yes ──► ServerCall                      │
│  │           └── No  ──► Server                          │
│  │                                                       │
│  └── No ───► Does code run on client?                    │
│              ├── Only client ──► Client                  │
│              ├── Only server ──► Server                  │
│              └── Both ────────► ClientServer             │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## 🔧 How to Fix

### Step 1: Determine module purpose

Ask yourself:
- What will this module do?
- Where will the code execute?
- Who will call these methods?

### Step 2: Configure module properties

Open module properties and set:

**For Server module:**
- Server: ✓
- Client (managed application): ✗
- Name suffix: (none required)

**For Client module:**
- Server: ✗
- Client (managed application): ✓
- Name suffix: `Client`

**For ClientServer module:**
- Server: ✓
- Client (managed application): ✓
- Name suffix: `ClientServer`

**For ServerCall module:**
- Server: ✓
- Server call: ✓
- Name suffix: `ServerCall`

### Step 3: Update module name

Add appropriate suffix according to Standard 469:

| Type | Suffix (EN) | Suffix (RU) |
|------|-------------|-------------|
| Server | (none) | (нет) |
| Client | Client | Клиент |
| ClientServer | ClientServer | КлиентСервер |
| ServerCall | ServerCall | ВызовСервера |

---

## 📋 Module Property Matrix

| Property | Server | Client | ClientServer | ServerCall |
|----------|--------|--------|--------------|------------|
| Server | ✓ | ✗ | ✓ | ✓ |
| Client (managed app) | ✗ | ✓ | ✓ | ✗ |
| Server call | ✗ | ✗ | ✗ | ✓ |
| External connection | Optional | ✗ | Optional | ✗ |
| Global | Optional | Optional | ✗ | ✗ |
| Privileged | Optional | ✗ | ✗ | ✗ |

---

## 🔍 Technical Details

### What Is Checked

1. Examines common module properties
2. Verifies that execution context is explicitly configured
3. Reports if module appears to have default/unconfigured settings

### Check Implementation Class

```
com.e1c.v8codestyle.md.commonmodule.check.CommonModuleType
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/commonmodule/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 469](https://its.1c.ru/db/v8std/content/469/hdoc)
