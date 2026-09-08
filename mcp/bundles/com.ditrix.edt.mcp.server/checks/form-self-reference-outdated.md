# form-self-reference-outdated

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `form-self-reference-outdated` |
| **Title** | Outdated alias used |
| **Description** | "ThisObject" should be used instead of outdated "ThisForm" |
| **Severity** | `MINOR` |
| **Type** | `CODE_STYLE` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies usage of the **outdated `ThisForm`** alias in form modules and recommends using the **modern `ThisObject`** instead.

### Why This Is Important

- **Modern syntax**: `ThisObject` is the current standard
- **Consistency**: Uniform codebase style
- **Platform evolution**: `ThisForm` is a legacy construct
- **Code clarity**: `ThisObject` better represents the managed form object

---

## ❌ Error Example

### Error Message

```
"ThisObject" should be used instead of outdated "ThisForm"
```

**Russian:**
```
"ЭтотОбъект" следует использовать вместо устаревшего "ЭтаФорма"
```

### Noncompliant Code Example

```bsl
// Form module
&AtClient
Procedure OnOpen(Cancel)
    // ❌ Outdated alias
    ThisForm.Title = "My Document";
    
    // ❌ Passing outdated reference
    ProcessForm(ThisForm);
EndProcedure

&AtClient  
Procedure SomeAction()
    // ❌ Using ThisForm
    If ThisForm.Modified Then
        ShowQueryBox(...);
    EndIf;
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Code

```bsl
// Form module
&AtClient
Procedure OnOpen(Cancel)
    // ✅ Modern syntax
    ThisObject.Title = "My Document";
    
    // ✅ Or simply use direct access
    Title = "My Document";
EndProcedure

&AtClient
Procedure SomeAction()
    // ✅ Using ThisObject
    If ThisObject.Modified Then
        ShowQueryBox(...);
    EndIf;
    
    // ✅ Or direct property access
    If Modified Then
        ShowQueryBox(...);
    EndIf;
EndProcedure
```

---

## 📖 Understanding Form References

### ThisForm vs ThisObject

| Aspect | ThisForm | ThisObject |
|--------|----------|------------|
| Status | Outdated | Current |
| Platform | 8.0-8.1 | 8.2+ |
| Type | `ManagedForm` | `ManagedForm` |
| Usage | Legacy code | Modern code |

### Both Reference the Same Object

```bsl
// These are equivalent
ThisForm.Title = "Test";
ThisObject.Title = "Test";
Title = "Test";  // Implicit ThisObject
```

---

## 📋 Common Usage Patterns

### Property Access

```bsl
// ❌ Outdated
Value = ThisForm.SomeAttribute;
ThisForm.Items.ButtonSave.Enabled = True;

// ✅ Modern
Value = ThisObject.SomeAttribute;
ThisObject.Items.ButtonSave.Enabled = True;

// ✅ Or direct access
Value = SomeAttribute;
Items.ButtonSave.Enabled = True;
```

### Passing Form Reference

```bsl
// ❌ Outdated
OpenForm("CommonForm.Settings", , ThisForm);
NotifyDescription = New NotifyDescription("Handler", ThisForm);

// ✅ Modern
OpenForm("CommonForm.Settings", , ThisObject);
NotifyDescription = New NotifyDescription("Handler", ThisObject);
```

### Type Checking

```bsl
// ❌ Outdated (and unnecessary)
If TypeOf(ThisForm) = Type("ManagedForm") Then

// ✅ Just use directly - you're already in a form
If True Then  // This check is usually unnecessary
```

---

## 🔧 How to Fix

### Step 1: Find all ThisForm usages

Search form modules for:
- `ThisForm.`
- `ThisForm)`
- `ThisForm,`
- `ЭтаФорма.` (Russian)

### Step 2: Replace with ThisObject

**Find:** `ThisForm`  
**Replace:** `ThisObject`

Or if applicable, remove the explicit reference:

```bsl
// Before
ThisForm.Items.Button.Visible = True;

// After (option 1)
ThisObject.Items.Button.Visible = True;

// After (option 2 - cleaner)
Items.Button.Visible = True;
```

### Step 3: Test the form

Verify all functionality works correctly after the change.

---

## 📋 When Explicit Reference Is Needed

### Callback Handlers

```bsl
&AtClient
Procedure ShowQuestion()
    // Need explicit reference for callback
    Handler = New NotifyDescription("QuestionHandler", ThisObject);
    ShowQueryBox(Handler, "Continue?", QuestionDialogMode.YesNo);
EndProcedure
```

### Opening Related Forms

```bsl
&AtClient
Procedure OpenRelatedDocument()
    // Pass form as owner
    FormParameters = New Structure("Owner", ThisObject);
    OpenForm("Document.Invoice.Form", FormParameters);
EndProcedure
```

### Dynamic Property Access

```bsl
&AtClient
Procedure SetPropertyByName(PropertyName, Value)
    // Dynamic access requires explicit reference
    ThisObject[PropertyName] = Value;
EndProcedure
```

---

## 🔍 Technical Details

### What Is Checked

1. Scans form module code
2. Finds references to `ThisForm` or `ЭтаФорма`
3. Reports each occurrence

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.FormSelfReferenceOutdatedCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

