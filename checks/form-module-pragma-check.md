# form-module-pragma-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `form-module-pragma-check` |
| **Title** | Form module compilation directives check |
| **Description** | Checks for proper use of compilation directives (pragmas) in form modules |
| **Severity** | `MAJOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check validates that **compilation directives** (pragmas like `&AtClient`, `&AtServer`, etc.) are used correctly and consistently in form module code.

### Why This Is Important

- **Client-server separation**: Proper directives ensure correct execution context
- **Performance**: Wrong context causes unnecessary server calls
- **Security**: Server code should not run on client
- **Architecture**: Clear separation of concerns

---

## ❌ Error Example

### Error Messages

```
Missing compilation directive for procedure
Incorrect compilation directive for this operation
Procedure should be marked as &AtServer
```

### Noncompliant Code Example

```bsl
// ❌ Missing compilation directive
Procedure ProcessData()
    // No directive - defaults may not be what you expect
    DoWork();
EndProcedure

// ❌ Wrong directive for server-only operation
&AtClient
Procedure SaveDataAtClient()
    // ❌ Write() requires server context!
    Object.Write();
EndProcedure

// ❌ Database access from client
&AtClient
Procedure GetItemNameAtClient(ItemRef)
    // ❌ Database access requires server!
    Return ItemRef.Name;
EndProcedure

// ❌ Mixed operations in wrong context
&AtServer
Procedure ShowMessageAtServer()
    // ❌ ShowMessageBox is client-only!
    ShowMessageBox(, "Message");
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Directive Usage

```bsl
// ✅ Explicit directive for all procedures
&AtClient
Procedure ProcessDataAtClient()
    DoClientWork();
EndProcedure

// ✅ Server directive for database operations
&AtServer
Procedure SaveDataAtServer()
    Object.Write();
EndProcedure

// ✅ Server directive for database access
&AtServerNoContext
Function GetItemNameAtServer(ItemRef)
    Return ItemRef.Name;
EndFunction

// ✅ Client directive for UI operations
&AtClient
Procedure ShowMessageAtClient()
    ShowMessageBox(, "Message");
EndProcedure
```

---

## 📋 Compilation Directives Reference

### Available Directives in Form Modules

| Directive | Runs On | Access to Form | Use For |
|-----------|---------|----------------|---------|
| `&AtClient` | Client | Yes | UI, user interaction |
| `&AtServer` | Server | Yes | Database, object write |
| `&AtServerNoContext` | Server | No | Utility functions |
| `&AtClientAtServerNoContext` | Both | No | Shared utilities |

### Operations by Context

| Operation | Required Directive |
|-----------|-------------------|
| ShowMessageBox | `&AtClient` |
| OpenForm | `&AtClient` |
| User dialogs | `&AtClient` |
| Object.Write() | `&AtServer` |
| Database queries | `&AtServer` |
| Reference attribute access | `&AtServer` |
| ValueToFormData | `&AtServer` |
| Form data modification | Any with context |
| Pure calculations | Any |

---

## 📋 Common Patterns

### Client Event Handler

```bsl
// ✅ Event handlers are typically client
&AtClient
Procedure ItemOnChange(Item)
    Recalculate();
EndProcedure

&AtClient
Procedure SaveButtonClick(Command)
    SaveDocument();
EndProcedure
```

### Server Data Operation

```bsl
// ✅ Server for data operations
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    FillFormData();
EndProcedure

&AtServer
Procedure WriteObjectAtServer()
    Object.Write();
EndProcedure
```

### Utility Function (No Context)

```bsl
// ✅ No context for pure utility functions
&AtClientAtServerNoContext
Function FormatAmount(Amount)
    Return Format(Amount, "NFD=2");
EndFunction

&AtServerNoContext
Function GetSetting(SettingName)
    Return Constants[SettingName].Get();
EndFunction
```

---

## 📋 Directive Selection Guide

### Flowchart for Choosing Directive

```
Does procedure need form data?
├── No → Does it need database?
│        ├── No → &AtClientAtServerNoContext
│        └── Yes → &AtServerNoContext
│
└── Yes → Does it need database?
          ├── No → Does it need UI?
          │        ├── Yes → &AtClient
          │        └── No → &AtClient (or &AtServer)
          │
          └── Yes → &AtServer
```

### Quick Reference

| Need Form Data | Need Database | Need UI | Use Directive |
|----------------|---------------|---------|---------------|
| No | No | No | `&AtClientAtServerNoContext` |
| No | Yes | No | `&AtServerNoContext` |
| Yes | No | Yes | `&AtClient` |
| Yes | Yes | No | `&AtServer` |
| Yes | Yes | Yes | Split into two procedures |

---

## 📋 Splitting Client-Server Logic

### When Both Client and Server Are Needed

```bsl
// ✅ Split UI and database operations
&AtClient
Procedure SaveDocument(Command)
    // Client: UI, user confirmation
    If Not Modified Then
        ShowMessageBox(, "No changes to save");
        Return;
    EndIf;
    
    // Call server for database work
    SaveDocumentAtServer();
    
    // Client: Show result
    ShowUserNotification("Document saved");
EndProcedure

&AtServer
Procedure SaveDocumentAtServer()
    // Server: Database operations only
    Object.Write();
    Modified = False;
EndProcedure
```

### Avoid Mixing Contexts

```bsl
// ❌ Before: Mixed concerns
&AtServer
Procedure Process()
    Calculate();
    Object.Write();
    ShowMessageBox(, "Done"); // ❌ Can't run on server!
EndProcedure

// ✅ After: Separated concerns
&AtClient
Procedure Process()
    ProcessAtServer();
    ShowMessageBox(, "Done");
EndProcedure

&AtServer
Procedure ProcessAtServer()
    Calculate();
    Object.Write();
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Add missing directives

Every procedure/function should have explicit directive.

### Step 2: Analyze operations in procedure

Identify database, UI, and form data access.

### Step 3: Choose appropriate directive

Use the selection guide above.

### Step 4: Split if necessary

If procedure needs both client and server, split it.

---

## 📋 Fixing Examples

### Example 1: Add Missing Directive

```bsl
// ❌ Before
Procedure Calculate()
    Total = 0;
    For Each Row In Object.Items Do
        Total = Total + Row.Amount;
    EndDo;
    Object.Total = Total;
EndProcedure

// ✅ After
&AtServer
Procedure Calculate()
    Total = 0;
    For Each Row In Object.Items Do
        Total = Total + Row.Amount;
    EndDo;
    Object.Total = Total;
EndProcedure
```

### Example 2: Fix Wrong Directive

```bsl
// ❌ Before
&AtClient
Procedure GetCustomerName(CustomerRef)
    Return CustomerRef.Name; // Database access!
EndProcedure

// ✅ After
&AtServerNoContext
Function GetCustomerName(CustomerRef)
    Return CustomerRef.Name;
EndFunction
```

---

## 🔍 Technical Details

### What Is Checked

1. Presence of compilation directive
2. Appropriateness for operations
3. Client/server context separation

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.FormModulePragmaCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

