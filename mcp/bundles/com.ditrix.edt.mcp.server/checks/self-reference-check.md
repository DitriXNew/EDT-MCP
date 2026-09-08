# self-reference-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `self-reference-check` |
| **Title** | Excessive self reference |
| **Description** | Checks for excessive usage of self reference when referencing method, property or attribute |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies unnecessary explicit **self-references** when accessing methods, properties, or attributes. In most cases, you can access members directly without prefixing with `ThisObject` or `ThisForm`.

### Why This Is Important

- **Code clarity**: Less verbose code is easier to read
- **Consistency**: Follow standard coding conventions
- **Maintenance**: Less code to maintain
- **Best practices**: Avoid redundant qualifiers

---

## ❌ Error Example

### Error Message

```
Excessive usage of self reference (when referencing method, property or attribute)
```

### Noncompliant Code Example

```bsl
// ❌ Excessive self-reference in form module
Procedure OnOpen(Cancel)
    ThisObject.LoadData(); // ❌ ThisObject is unnecessary
    ThisObject.Items.Table.Visible = True; // ❌ Redundant
    ThisObject.Title = "My Form"; // ❌ Unnecessary prefix
EndProcedure

// ❌ Excessive self-reference in object module
Procedure BeforeWrite(Cancel)
    ThisObject.Code = ThisObject.GenerateCode(); // ❌ Both unnecessary
    ThisObject.Description = "Item"; // ❌ Redundant
    If ThisObject.DeletionMark Then // ❌ Unnecessary
        Cancel = True;
    EndIf;
EndProcedure

// ❌ In form module accessing form items
Procedure UpdateForm()
    ThisObject.Items.CustomerField.Visible = True; // ❌
    ThisObject.Items.DateField.ReadOnly = False; // ❌
EndProcedure
```

---

## ✅ Compliant Solution

### Direct Access Without Self-Reference

```bsl
// ✅ Direct access in form module
Procedure OnOpen(Cancel)
    LoadData(); // ✅ Direct call
    Items.Table.Visible = True; // ✅ Direct access
    Title = "My Form"; // ✅ Direct assignment
EndProcedure

// ✅ Direct access in object module
Procedure BeforeWrite(Cancel)
    Code = GenerateCode(); // ✅ Direct
    Description = "Item"; // ✅ Direct
    If DeletionMark Then // ✅ Direct
        Cancel = True;
    EndIf;
EndProcedure

// ✅ Direct form item access
Procedure UpdateForm()
    Items.CustomerField.Visible = True; // ✅ Direct
    Items.DateField.ReadOnly = False; // ✅ Direct
EndProcedure
```

---

## 📋 When Self-Reference Is Needed

### 1. Passing Object as Parameter

```bsl
// ✅ Self-reference needed when passing object
Procedure Process()
    CommonModule.ProcessObject(ThisObject); // ✅ Required
EndProcedure
```

### 2. Distinguishing from Local Variable

```bsl
// ✅ When local variable shadows object property
Procedure Update(Description)
    ThisObject.Description = Description; // ✅ Distinguishes from parameter
EndProcedure
```

### 3. Storing Reference

```bsl
// ✅ Getting reference to self
Procedure SaveReference()
    ObjectRef = ThisObject.Ref; // ✅ May be clearer
EndProcedure
```

### 4. In Lambda/Callback Context

```bsl
// ✅ In callbacks where context matters
Description = New NotifyDescription("Handler", ThisObject);
```

---

## 📋 Context-Specific Rules

### Form Module

| Access | With Self-Reference | Without |
|--------|-------------------|---------|
| Form items | `ThisObject.Items.Field` | `Items.Field` ✅ |
| Form attributes | `ThisObject.Object.Name` | `Object.Name` ✅ |
| Form properties | `ThisObject.Title` | `Title` ✅ |
| Form methods | `ThisObject.Method()` | `Method()` ✅ |

### Object Module

| Access | With Self-Reference | Without |
|--------|-------------------|---------|
| Object attributes | `ThisObject.Code` | `Code` ✅ |
| Tabular sections | `ThisObject.Items` | `Items` ✅ |
| Methods | `ThisObject.Calculate()` | `Calculate()` ✅ |
| Standard properties | `ThisObject.Ref` | `Ref` ✅ |

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `checkObjectModule` | `True` | Check object (recordset, value manager) module |
| `checkOnlyExistingFormProperties` | `True` | Check only existing form properties |

---

## 🔧 How to Fix

### Step 1: Identify ThisObject/ThisForm usage

Find all occurrences of explicit self-reference.

### Step 2: Determine if necessary

Check if the reference is needed for:
- Passing as parameter
- Disambiguating from local variables
- Explicit context requirement

### Step 3: Remove if unnecessary

```bsl
// Before
ThisObject.Items.Field.Visible = True;
ThisObject.Description = "Value";
ThisObject.Process();

// After
Items.Field.Visible = True;
Description = "Value";
Process();
```

---

## 📖 Comparison Examples

### Form Module

```bsl
// ❌ With unnecessary self-reference
Procedure OnOpen(Cancel)
    ThisObject.Items.MainGroup.Visible = True;
    ThisObject.Object.Description = "New";
    ThisObject.Modified = True;
    ThisObject.RefreshDataRepresentation();
EndProcedure

// ✅ Without self-reference
Procedure OnOpen(Cancel)
    Items.MainGroup.Visible = True;
    Object.Description = "New";
    Modified = True;
    RefreshDataRepresentation();
EndProcedure
```

### Object Module

```bsl
// ❌ With unnecessary self-reference
Procedure Filling(FillingData, FillingText, StandardProcessing)
    ThisObject.Code = ThisObject.GenerateCode();
    ThisObject.Description = "Default";
    ThisObject.Date = CurrentDate();
    For Each Row In ThisObject.Items Do
        Row.Quantity = 1;
    EndDo;
EndProcedure

// ✅ Without self-reference
Procedure Filling(FillingData, FillingText, StandardProcessing)
    Code = GenerateCode();
    Description = "Default";
    Date = CurrentDate();
    For Each Row In Items Do
        Row.Quantity = 1;
    EndDo;
EndProcedure
```

---

## 🔍 Technical Details

### What Is Checked

1. `ThisObject` usage in modules
2. Member access patterns
3. Identifies redundant qualifiers

### Related Checks

- [Common Module Named Self Reference](common-module-named-self-reference.md)
- [Manager Module Named Self Reference](manager-module-named-self-reference-check.md)
- [Form Self Reference Outdated](form-self-reference-outdated.md)

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.SelfReferenceCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Form Self Reference Outdated](form-self-reference-outdated.md)
- [Common Module Named Self Reference](common-module-named-self-reference.md)
- [1C Coding Standards](https://its.1c.ru/db/v8std)
