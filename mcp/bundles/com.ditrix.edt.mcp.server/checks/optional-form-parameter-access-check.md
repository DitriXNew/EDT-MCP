# optional-form-parameter-access-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `optional-form-parameter-access-check` |
| **Title** | Access to optional form opening parameter |
| **Description** | Checks for access to optional form parameters without existence check |
| **Severity** | `MAJOR` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies when code accesses an **optional form opening parameter** without first checking if it exists. Optional parameters may not be passed when opening the form, leading to runtime errors.

### Why This Is Important

- **Runtime error**: Accessing non-existent parameter causes exception
- **Robustness**: Form should handle missing parameters gracefully
- **User experience**: Prevents crashes when form opened differently
- **Reusability**: Form works with various parameter sets

---

## ❌ Error Example

### Error Message

```
Access to optional form parameter 'ParameterName' without checking if it exists
```

### Noncompliant Code Example

```bsl
// ❌ Direct access to optional parameter without check
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // ❌ ERROR: "Filter" may not be passed!
    Object.Filter = Parameters.Filter;
    
    // ❌ ERROR: "Mode" may not be passed!
    If Parameters.Mode = "Edit" Then
        SetEditMode();
    EndIf;
    
    // ❌ ERROR: "Owner" may not be passed!
    FillByOwner(Parameters.Owner);
EndProcedure

// ❌ Optional parameter in event handler
&AtClient
Procedure OnOpen(Cancel)
    // ❌ ERROR: "AutoStart" is optional
    If Parameters.AutoStart Then
        StartProcess();
    EndIf;
EndProcedure

// ❌ Nested property access on optional parameter
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // ❌ ERROR: "Settings" may not be passed
    FieldValue = Parameters.Settings.Field1;
EndProcedure
```

---

## ✅ Compliant Solution

### Check Parameter Existence First

```bsl
// ✅ Check with Property method
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // ✅ Check if parameter exists before using
    If Parameters.Property("Filter") Then
        Object.Filter = Parameters.Filter;
    EndIf;
    
    // ✅ Check and get value in one call
    Mode = "";
    If Parameters.Property("Mode", Mode) Then
        If Mode = "Edit" Then
            SetEditMode();
        EndIf;
    EndIf;
    
    // ✅ Check before using in method call
    If Parameters.Property("Owner") And ValueIsFilled(Parameters.Owner) Then
        FillByOwner(Parameters.Owner);
    EndIf;
EndProcedure

// ✅ Optional parameter with default value
&AtClient
Procedure OnOpen(Cancel)
    AutoStart = False;
    If Parameters.Property("AutoStart", AutoStart) And AutoStart Then
        StartProcess();
    EndIf;
EndProcedure

// ✅ Safe nested access
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    If Parameters.Property("Settings") And Parameters.Settings <> Undefined Then
        If Parameters.Settings.Property("Field1") Then
            FieldValue = Parameters.Settings.Field1;
        EndIf;
    EndIf;
EndProcedure
```

---

## 📖 Understanding Form Parameters

### How Form Parameters Work

```bsl
// Opening form with parameters
OpenFormParameters = New Structure;
OpenFormParameters.Insert("Key", KeyValue);          // Required
OpenFormParameters.Insert("Filter", FilterValue);    // Optional
// "Mode" not passed - will be missing!

OpenForm("Document.Order.Form.ItemForm", OpenFormParameters);
```

### In the Form Being Opened

```bsl
// Parameters structure may or may not contain "Mode"
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Parameters.Key - always exists (required)
    // Parameters.Filter - may exist
    // Parameters.Mode - does NOT exist!
EndProcedure
```

---

## 📋 Checking Parameter Existence

### Method 1: Property() Method

```bsl
// ✅ Just check existence
If Parameters.Property("ParameterName") Then
    // Parameter exists, safe to use
    Value = Parameters.ParameterName;
EndIf;
```

### Method 2: Property() with Output Variable

```bsl
// ✅ Check and get value in one call
ParameterValue = Undefined;
If Parameters.Property("ParameterName", ParameterValue) Then
    // ParameterValue now contains the value
    ProcessValue(ParameterValue);
EndIf;
```

### Method 3: Combined with ValueIsFilled

```bsl
// ✅ Check existence AND meaningful value
If Parameters.Property("Owner") And ValueIsFilled(Parameters.Owner) Then
    FillByOwner(Parameters.Owner);
EndIf;
```

---

## 📋 Common Parameter Patterns

### Selection/Opening Parameters

```bsl
// ✅ Handling selection parameters
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Check "CloseOnChoice" parameter
    If Parameters.Property("CloseOnChoice") Then
        CloseOnChoice = Parameters.CloseOnChoice;
    EndIf;
    
    // Check "ChoiceMode" parameter
    If Parameters.Property("ChoiceMode") And Parameters.ChoiceMode Then
        SetChoiceModeAppearance();
    EndIf;
EndProcedure
```

### Filter Parameters

```bsl
// ✅ Handling filter parameters
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    If Parameters.Property("Filter") And TypeOf(Parameters.Filter) = Type("Structure") Then
        If Parameters.Filter.Property("Company") Then
            SetFilterByCompany(Parameters.Filter.Company);
        EndIf;
        If Parameters.Filter.Property("Period") Then
            SetFilterByPeriod(Parameters.Filter.Period);
        EndIf;
    EndIf;
EndProcedure
```

### Mode Parameters

```bsl
// ✅ Handling mode parameters with default
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    FormMode = "View"; // Default value
    Parameters.Property("Mode", FormMode);
    
    If FormMode = "Edit" Then
        EnableEditing();
    ElsIf FormMode = "Copy" Then
        PrepareForCopy();
    EndIf;
EndProcedure
```

---

## 📋 Required vs Optional Parameters

### Defining in Form

Form parameters are defined in form properties. You can mark parameters as:

| Setting | Description |
|---------|-------------|
| Required | Must be passed, causes error if missing |
| Optional | May or may not be passed |

### Checking Required Parameters

```bsl
// Required parameters are always safe to access
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // If "Key" is required, this is safe
    CurrentKey = Parameters.Key;
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Identify optional parameters

Review form parameter definitions.

### Step 2: Find direct access

Search for `Parameters.` in form module.

### Step 3: Add existence check

Use `Property()` method before accessing.

### Step 4: Provide default values

Handle missing parameters gracefully.

---

## 📋 Fixing Examples

### Example 1: Simple Parameter

```bsl
// ❌ Before
Title = Parameters.CustomTitle;

// ✅ After
Title = "";
If Parameters.Property("CustomTitle", Title) Then
    // Use CustomTitle
EndIf;
```

### Example 2: Boolean Parameter

```bsl
// ❌ Before
If Parameters.ReadOnly Then
    LockForm();
EndIf;

// ✅ After
If Parameters.Property("ReadOnly") And Parameters.ReadOnly Then
    LockForm();
EndIf;
```

### Example 3: Reference Parameter

```bsl
// ❌ Before
FillByParent(Parameters.Parent);

// ✅ After
If Parameters.Property("Parent") And ValueIsFilled(Parameters.Parent) Then
    FillByParent(Parameters.Parent);
EndIf;
```

---

## 🔍 Technical Details

### What Is Checked

1. Form module code
2. Access to Parameters structure
3. Parameter definition (required/optional)
4. Existence check before access

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.OptionalFormParameterAccessCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Unknown Form Parameter Access Check](unknown-form-parameter-access-check.md)
