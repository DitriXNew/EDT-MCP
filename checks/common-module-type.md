# common-module-type

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `common-module-type` |
| **Title** | Common module type is not set |
| **Description** | Check that the common module flag combination matches a standard module type |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [469](https://its.1c.ru/db/v8std/content/469/hdoc) |

---

## 🎯 What This Check Does

A common module is described by a set of context flags — **Server**, **Client (managed application)**, **Client (ordinary application)**, **External connection**, **Server call**, **Global**, **Privileged** — plus **Return value reuse**. Standard 469 does not allow arbitrary combinations: it defines a fixed list of *module types*, each being one exact flag combination.

This check compares the module's actual flag set against that list and reports when the combination **does not match any allowed type**. It is not a "some flag is still at its default" check, and it is not a name check — it only looks at the flag combination.

A common real-world miss (the one this check is meant to catch): a "server" module is created with **only** `Server` ticked, while **External connection** and **Client (ordinary application)** are left off. Per Standard 469 §2.1 a server module is `Server` + `External connection` + `Client (ordinary application)` (with `Server call` off), so the `Server`-only combination matches no type and is flagged.

### Why This Is Important

- **Predictable execution context**: the allowed combinations guarantee a method can be invoked everywhere the type implies — e.g. a server module reached over an external (COM) connection, or called with mutable values (`CatalogObject`, `DocumentObject`) that a `Server-call`-only module could not accept.
- **Standards compliance**: only the type combinations from Standard 469 are used.
- **Code review**: reviewers can confirm the module's context is one of the sanctioned types, not an ad-hoc mix.

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
<!-- ❌ Noncompliant: a "server" module with only <server> set. -->
<!-- External connection and Client (ordinary application) are missing, -->
<!-- so the combination matches no Standard 469 type. -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>DataProcessingServer</name>
  <server>true</server>
  <!-- externalConnection and clientOrdinaryApplication left at false -->
</mdclass:CommonModule>
```

### Noncompliant Code Example

```
Configuration/
└── CommonModules/
    └── DataProcessingServer/  ❌ Flag combination matches no standard type
        └── Module.bsl
```

**Module Properties (no valid type — only `Server` is on):**
- Server: ✓
- Client (managed application): ✗
- Client (ordinary application): ✗
- External connection: ✗
- Server call: ✗

> A module with **all** context flags off is flagged for the same reason — the empty combination is not one of the allowed types either.

---

## ✅ Compliant Solution

### Correct XML Configuration

```xml
<!-- ✅ Server module: Server + External connection + Client (ordinary application) -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>DataProcessingServer</name>
  <server>true</server>
  <externalConnection>true</externalConnection>
  <clientOrdinaryApplication>true</clientOrdinaryApplication>
</mdclass:CommonModule>

<!-- ✅ Server-call module: Server + Server call (reachable from the client) -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>DataServiceServerCall</name>
  <server>true</server>
  <serverCall>true</serverCall>
</mdclass:CommonModule>

<!-- ✅ Client-server module: all four contexts (Server call off) -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>StringUtilitiesClientServer</name>
  <server>true</server>
  <externalConnection>true</externalConnection>
  <clientManagedApplication>true</clientManagedApplication>
  <clientOrdinaryApplication>true</clientOrdinaryApplication>
</mdclass:CommonModule>

<!-- ✅ Client module: Client (managed) + Client (ordinary) -->
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>UserInterfaceClient</name>
  <clientManagedApplication>true</clientManagedApplication>
  <clientOrdinaryApplication>true</clientOrdinaryApplication>
</mdclass:CommonModule>
```

> In EDT serialization a flag that is `false` is usually omitted from the `.mdo` file. What matters to the check is the **effective** combination, not whether each flag appears in the XML.

---

## 📖 Common Module Types

### Core Types (Standard 469 §1.2 / §2)

The four base types and their flags. `Client (managed application)` and `Client (ordinary application)` are listed separately because the standard treats them as distinct contexts.

| Type | Server | Client (managed) | Client (ordinary) | External connection | Server call |
|------|:------:|:----------------:|:-----------------:|:-------------------:|:-----------:|
| **Server** (§2.1) | ✓ | ✗ | ✓ | ✓ | ✗ |
| **Client** | ✗ | ✓ | ✓ | ✗ | ✗ |
| **Client-server** (§2.4) | ✓ | ✓ | ✓ | ✓ | ✗ |
| **Server call** | ✓ | ✗ | ✗ | ✗ | ✓ |

### Specialized Types (Standard 469 §3.2)

The standard also sanctions specialized variants. Each is still one exact combination; the variant is distinguished by an extra flag (Global, Privileged, Return value reuse) and/or a name suffix. The **flag combination** is validated here; the **name suffix** is validated by the sibling `common-module-name-*` checks (see below).

| Variant | Distinguishing property | Standard | Name-suffix check |
|---------|-------------------------|----------|-------------------|
| Cached (return value reuse) | `Return value reuse` = During session/request | §3.2.3 | [common-module-name-cached](common-module-name-cached.md), [common-module-name-client-cached](common-module-name-client-cached.md), [common-module-name-server-call-cached](common-module-name-server-call-cached.md) |
| Global | `Global` = ✓ | §3.2.1 | [common-module-name-global](common-module-name-global.md), [common-module-name-global-client](common-module-name-global-client.md) |
| Privileged / Full access | `Privileged` = ✓ | §3.2.2 | [common-module-name-full-access](common-module-name-full-access.md) |
| Overridable | (server/client base + name suffix) | §3.2.4 | — |
| Localization | (server/client base + name suffix) | §3.2.5 | — |

### Choosing the Right Type

```
┌──────────────────────────────────────────────────────────┐
│           Which module type do you need?                 │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  Does code access the database / run on the server?      │
│  ├── Yes ──► Is it called directly from the client?      │
│  │           ├── Yes ──► Server call                     │
│  │           └── No  ──► Does it also run on the client? │
│  │                       ├── Yes ──► Client-server       │
│  │                       └── No  ──► Server              │
│  │                                                       │
│  └── No ───► Client (runs only on the client)            │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## 🔧 How to Fix

### Step 1: Determine the module's purpose

Ask yourself:
- Where will the code execute (server, client, both, external connection)?
- Will the client call these methods directly (server call)?
- Is this a specialized module (cached, global, privileged, overridable, localization)?

### Step 2: Set a complete, valid flag combination

Open the module properties and tick the flags so they match **one whole row** of the matrix below — not just the single flag that names the type. For example, a server module needs `Server` **and** `External connection` **and** `Client (ordinary application)`, not `Server` alone.

> The module **name** (and its suffix, e.g. `ServerCall`, `ClientServer`) is **not** part of this check. Naming is enforced by the dedicated checks: [common-module-name-server-call](common-module-name-server-call.md), [common-module-name-client](common-module-name-client.md), [common-module-name-client-server](common-module-name-client-server.md), [common-module-name-cached](common-module-name-cached.md), [common-module-name-global](common-module-name-global.md), [common-module-name-full-access](common-module-name-full-access.md), and the others under `checks/common-module-name-*`.

---

## 📋 Module Property Matrix

The complete set of flag combinations accepted by the check (mirrors the `CommonModuleTypes` enum in v8-code-style). A module is compliant if and only if its flags equal one of these rows.

| Module type | Server | Client (managed) | Client (ordinary) | External connection | Server call | Global | Privileged | Return value reuse |
|-------------|:------:|:----------------:|:-----------------:|:-------------------:|:-----------:|:------:|:----------:|:-------------------:|
| Server | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | Don't use |
| Client | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | Don't use |
| Client-server | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ | Don't use |
| Server call | ✓ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | Don't use |
| Server call (cached) | ✓ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | During session |
| Server (cached) | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | During session |
| Client (cached) | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | During session |
| Server global | ✓ | ✗ | ✓ | ✓ | ✗ | ✓ | ✗ | Don't use |
| Client global | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | Don't use |
| Server full access (privileged) | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | Don't use |
| Server overridable | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | Don't use |
| Client overridable | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | Don't use |
| Server localization | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | Don't use |
| Client localization | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | Don't use |

> Several rows share an identical flag set (e.g. *Server*, *Server overridable*, *Server localization*). The type check cannot tell them apart — the distinction is the **name**, enforced by the `common-module-name-*` checks. For a mobile application target, `External connection` and `Privileged` are ignored during comparison.

---

## 🔍 Technical Details

### What Is Checked

1. The module's flag set is read: `server`, `clientManagedApplication`, `clientOrdinaryApplication`, `externalConnection`, `serverCall`, `global`, `privileged`, and `returnValuesReuse`.
2. It is compared **by equality** against each allowed type (the matrix above).
3. If it equals no allowed type, the module is reported; the message names the closest type and the flags that differ.

### Known Limitation

Because the comparison is by exact flag set, a legitimate but unlisted combination is reported as a false positive. A known case is a module that reuses returned values *for the duration of the call* (`Return value reuse = During request`): such modules are flagged even though they are valid — see [v8-code-style issue #1455](https://github.com/1C-Company/v8-code-style/issues/1455).

### Check Implementation Class

```
com.e1c.v8codestyle.md.commonmodule.check.CommonModuleType
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/commonmodule/check/
```

The allowed type combinations live in:

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/CommonModuleTypes.java
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 469](https://its.1c.ru/db/v8std/content/469/hdoc)
- [v8-code-style issue #1455 — CommonModuleType false positive for "during request" reuse](https://github.com/1C-Company/v8-code-style/issues/1455)
