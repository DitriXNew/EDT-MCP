# form-commands-single-action-handler

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `form-commands-single-action-handler` |
| **Title** | Single action handler assigned to multiple commands |
| **Description** | Each event should have its own handler procedure assigned |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **1C Standard** | [Module Structure (section 2.4.3)](https://its.1c.ru/db/v8std/content/455/hdoc#2.4.3) |

---

## 🎯 What This Check Does

The check ensures that **each form command** has **its own unique handler**. The same handler **should not** be assigned to multiple commands.

### Why This Is Important

Mixing multiple events in one procedure:
- **Complicates handler logic**
- **Reduces code stability** (instead of one expected call per event from platform, the procedure code must account for other calls too)
- **Violates Single Responsibility Principle** (SRP)
- **Complicates debugging** and code maintenance

---

## ❌ Error Example

### Error Message

```
Handler "{HandlerName}" of command "{Command1Name}" is already assigned to command {Command2Name}
```

**Example:**
```
Handler "Command1" of command "Command2" is already assigned to command Command1
```

### Wrong Code (Form.form - XML)

```xml
<formCommands>
  <name>Command1</name>
  <id>1</id>
  <use>
    <common>true</common>
  </use>
  <action xsi:type="form:FormCommandHandlerContainer">
    <handler>
      <name>Command1</name>  <!-- ❌ Handler Command1 -->
    </handler>
  </action>
  <currentRowUse>Auto</currentRowUse>
</formCommands>
<formCommands>
  <name>Command2</name>
  <id>2</id>
  <use>
    <common>true</common>
  </use>
  <action xsi:type="form:FormCommandHandlerContainer">
    <handler>
      <name>Command1</name>  <!-- ❌ ERROR: Same handler Command1 is reused! -->
    </handler>
  </action>
  <currentRowUse>Auto</currentRowUse>
</formCommands>
```

### Incorrect Code (Module.bsl)

```bsl
&AtClient
Procedure Command1(Command)
    // This handler is called for both Command1 and Command2
    // ❌ Bad: logic is mixed
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Code (Form.form - XML)

```xml
<formCommands>
  <name>Command1</name>
  <id>1</id>
  <use>
    <common>true</common>
  </use>
  <action xsi:type="form:FormCommandHandlerContainer">
    <handler>
      <name>Command1</name>  <!-- ✅ Own handler Command1 -->
    </handler>
  </action>
  <currentRowUse>Auto</currentRowUse>
</formCommands>
<formCommands>
  <name>Command2</name>
  <id>2</id>
  <use>
    <common>true</common>
  </use>
  <action xsi:type="form:FormCommandHandlerContainer">
    <handler>
      <name>Command2</name>  <!-- ✅ Own handler Command2 -->
    </handler>
  </action>
  <currentRowUse>Auto</currentRowUse>
</formCommands>
```

### Correct Code (Module.bsl)

```bsl
&AtClient
Procedure Command1(Command)
    // ✅ Separate handler for Command1
    DoCommonAction();  // Call common logic
EndProcedure

&AtClient
Procedure Command2(Command)
    // ✅ Separate handler for Command2
    DoCommonAction();  // Call common logic
EndProcedure

&AtClient
Procedure DoCommonAction()
    // ✅ Common logic extracted to separate procedure
    // Actions common to both commands are performed here
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Identify Conflicting Commands

From the error message, identify:
- **Handler name** that is being reused
- **Command names** that reference this handler

### Step 2: Create a Separate Handler

In the form module (`Module.bsl`), create a **new handler procedure** for the second command:

```bsl
&AtClient
Procedure NewCommandHandler(Command)
    // Processing logic
EndProcedure
```

### Step 3: Change the Binding in the Form File

In the `Form.form` file (XML), find the `<formCommands>` element for the command with the duplicate handler and change the handler name:

**Before:**
```xml
<handler>
  <name>OldHandler</name>
</handler>
```

**After:**
```xml
<handler>
  <name>NewCommandHandler</name>
</handler>
```

### Step 4: Extract Common Logic (if needed)

If both commands should perform **the same actions**, create a separate procedure for common logic and call it from each handler:

```bsl
&AtClient
Procedure Command1(Command)
    ExecuteCommonLogic();
EndProcedure

&AtClient
Procedure Command2(Command)
    ExecuteCommonLogic();
EndProcedure

&AtClient
Procedure ExecuteCommonLogic()
    // Common code for both commands
EndProcedure
```

---

## 📁 File Structure

This check affects the following files:

| File | Description | What is checked/changed |
|------|-------------|-------------------------|
| `Form.form` | XML form description | Elements `<formCommands>` → `<action>` → `<handler>` → `<name>` |
| `Module.bsl` | Form module | Handler procedures with `&AtClient` or `&AtServer` directive |

### Path to Files in Configuration

```
src/
└── Catalogs/           (or Documents/, DataProcessors/, etc.)
    └── CatalogName/
        └── Forms/
            └── FormName/
                ├── Form.form      ← XML with command descriptions
                └── Module.bsl     ← Handler code
```

---

## 🔍 Technical Details

### What the Check Does

1. Gets the form (`Form`)
2. Iterates through all form commands (`formCommands`)
3. For each command gets the list of handlers (`ModelUtils.getCommandHandlers`)
4. Maintains a `Map<String, String>` where key is handler name, value is command name
5. If handler already exists in the map - generates an error

### Check Class

```
com.e1c.v8codestyle.form.check.FormCommandsSingleEventHandlerCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.form/src/com/e1c/v8codestyle/form/check/FormCommandsSingleEventHandlerCheck.java
```

---

## 📚 References

- [1C Standard: Module Structure (section 2.4.3)](https://its.1c.ru/db/v8std/content/455/hdoc#2.4.3)
- [1C:Enterprise Development Standards: Module structure](https://kb.1ci.com/1C_Enterprise_Platform/Guides/Developer_Guides/1C_Enterprise_Development_Standards/Code_conventions/Module_formatting/Module_structure)
