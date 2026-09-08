# module-structure-method-in-region-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `module-structure-method-in-region-check` |
| **Title** | Method is outside a standard region |
| **Description** | Checks that methods are placed in appropriate standard regions for the module type |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check ensures that **procedures and functions** are placed within appropriate **standard regions** based on the module type. Methods should not be outside of regions or in inappropriate regions.

### Why This Is Important

- **Code organization**: Consistent structure across modules
- **Navigation**: Easier to find methods
- **Readability**: Clear separation by purpose
- **Standards compliance**: Follows 1C module structure standard

---

## ❌ Error Example

### Error Messages

```
The method "CalculateTotal" should be placed in one of the standard regions: Public, Internal, Private
```

```
Only export methods can be placed in the "Public" region
```

### Noncompliant Code Example

```bsl
// ❌ Method outside any region
Procedure CalculateTotal()
    // This method is not in any region
EndProcedure

#Region Public
    // ❌ Non-export method in Public region
    Procedure HelperMethod() // Missing Export
        // Should be in Private region
    EndProcedure
#EndRegion

#Region SomeCustomRegion
    // ❌ Method in non-standard region
    Procedure ProcessData() Export
    EndProcedure
#EndRegion
```

---

## ✅ Compliant Solution

### Correct Structure

```bsl
#Region Public

// ✅ Export methods in Public region
Procedure ProcessData(Data) Export
    ValidateData(Data);
    InternalProcess(Data);
EndProcedure

Function GetResult() Export
    Return CalculateResult();
EndFunction

#EndRegion

#Region Internal

// ✅ Internal export methods for use within subsystem
Procedure InternalProcess(Data) Export
    // Used by other modules in same subsystem
EndProcedure

#EndRegion

#Region Private

// ✅ Non-export methods in Private region
Procedure ValidateData(Data)
    // Internal validation
EndProcedure

Function CalculateResult()
    Return 42;
EndFunction

Procedure HelperMethod()
    // Helper logic
EndProcedure

#EndRegion
```

---

## 📋 Standard Regions by Module Type

### Common Module

```bsl
#Region Public
    // Export procedures for external use
#EndRegion

#Region Internal
    // Export procedures for internal subsystem use
#EndRegion

#Region Private
    // Non-export helper methods
#EndRegion

#Region Initialize
    // Module initialization code
#EndRegion
```

### Object Module (Catalog, Document)

```bsl
#Region Public
    // Public API methods
#EndRegion

#Region EventHandlers
    // BeforeWrite, OnWrite, etc.
#EndRegion

#Region Private
    // Helper methods
#EndRegion
```

### Form Module

```bsl
#Region FormEventHandlers
    // OnOpen, OnClose, etc.
#EndRegion

#Region FormHeaderItemsEventHandlers
    // Header item events
#EndRegion

#Region FormTableItemsEventHandlers
    // Table item events
#EndRegion

#Region FormCommandsEventHandlers
    // Command handlers
#EndRegion

#Region Private
    // Helper methods
#EndRegion
```

### Manager Module

```bsl
#Region Public
    // Export API methods
#EndRegion

#Region EventHandlers
    // Manager events
#EndRegion

#Region Private
    // Helper methods
#EndRegion
```

---

## 📖 Region Rules

### Export vs Non-Export

| Method Type | Allowed Regions |
|-------------|-----------------|
| Export method | Public, Internal |
| Non-export method | Private |
| Event handler | EventHandlers, FormEventHandlers |

### Standard Regions

| Region | Purpose |
|--------|---------|
| `Public` | External API - export methods |
| `Internal` | Subsystem API - export methods |
| `Private` | Helper methods - non-export |
| `EventHandlers` | Event handling methods |
| `Initialize` | Initialization code |

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `multilevelNesting` | `False` | Allow multilevel nesting of regions |

### Multilevel Nesting Example

```bsl
#Region Public

    #Region ProductsAPI
        // ✅ Allowed with multilevelNesting = True
        Procedure GetProducts() Export
        EndProcedure
    #EndRegion

#EndRegion
```

---

## 🔧 How to Fix

### Step 1: Identify method purpose

- Is it export? → Public or Internal
- Is it non-export? → Private
- Is it event handler? → EventHandlers

### Step 2: Create appropriate region

```bsl
#Region Private
#EndRegion
```

### Step 3: Move method to correct region

```bsl
// Before (no region)
Procedure HelperMethod()
EndProcedure

// After
#Region Private
Procedure HelperMethod()
EndProcedure
#EndRegion
```

### Step 4: Verify export keyword

```bsl
// In Public region - must have Export
#Region Public
Procedure APIMethod() Export // ✅ Has Export
EndProcedure
#EndRegion

// In Private region - must NOT have Export
#Region Private
Procedure HelperMethod() // ✅ No Export
EndProcedure
#EndRegion
```

---

## 📋 Quick Reference

### Method Placement Decision

```
Is it an event handler?
├── Yes → EventHandlers region
└── No → Continue

Does it have Export keyword?
├── Yes → Is it for external use?
│   ├── Yes → Public region
│   └── No → Internal region
└── No → Private region
```

---

## ⚠️ Common Mistakes

### Mistake 1: Export in Private

```bsl
// ❌ Export method in Private
#Region Private
Procedure SomeMethod() Export // Wrong!
EndProcedure
#EndRegion
```

### Mistake 2: Non-Export in Public

```bsl
// ❌ Non-export in Public
#Region Public
Procedure HelperMethod() // Missing Export!
EndProcedure
#EndRegion
```

### Mistake 3: Custom Region Names

```bsl
// ❌ Non-standard region name
#Region MyMethods
Procedure DoSomething()
EndProcedure
#EndRegion
```

---

## 🔍 Technical Details

### What Is Checked

1. Method placement in regions
2. Export keyword matching
3. Standard region names

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ModuleStructureMethodInRegionCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Module Structure Top Region Check](module-structure-top-region-check.md)
- [Module Structure Event Regions Check](module-structure-event-regions-check.md)
- [1C Module Structure Standard](https://its.1c.ru/db/v8std)
