# method-too-many-params-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `method-too-many-params-check` |
| **Title** | Method has too many parameters |
| **Description** | Checks that methods don't exceed the maximum allowed number of parameters |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [640](https://its.1c.ru/db/v8std/content/640/hdoc) |

---

## 🎯 What This Check Does

This check identifies procedures and functions that have **too many parameters**. According to 1C standards, methods should have no more than **7 parameters** total, and no more than **4 parameters with default values**.

### Why This Is Important

- **Readability**: Many parameters make code hard to understand
- **Maintainability**: Difficult to modify and extend
- **Usability**: Hard to remember parameter order
- **Design smell**: Often indicates need for refactoring

---

## ❌ Error Example

### Error Messages

```
Method has more than 7 parameters
```

```
Method has more than 4 parameters with default value
```

### Noncompliant Code Example

```bsl
// ❌ Too many parameters (exceeds 7)
Procedure CreateDocument(DocumentType, Date, Number, Customer, Manager, 
    Warehouse, Currency, ExchangeRate, Comment, Priority) Export
    // 10 parameters - too many!
EndProcedure

// ❌ Too many optional parameters (exceeds 4)
Procedure FormatData(Data, 
    Format = "XML", 
    Encoding = "UTF-8", 
    IncludeHeader = True,
    PrettyPrint = False,
    Validate = True) Export
    // 5 parameters with default values
EndProcedure

// ❌ Both limits exceeded
Function ProcessOrder(Order, Customer, Date, Status, Manager, 
    Warehouse, Currency, 
    UseDiscount = True, 
    RoundTotal = True, 
    SendNotification = False,
    LogAction = True,
    ValidateBefore = True) Export
    // 12 total, 5 optional
EndFunction
```

---

## ✅ Compliant Solution

### Solution 1: Use Structure

```bsl
// ✅ Group related parameters into structure
Procedure CreateDocument(DocumentParameters) Export
    // DocumentParameters is a Structure with all needed fields
    DocumentType = DocumentParameters.DocumentType;
    Date = DocumentParameters.Date;
    Number = DocumentParameters.Number;
    Customer = DocumentParameters.Customer;
    // ...
EndProcedure

// Calling code
Parameters = New Structure;
Parameters.Insert("DocumentType", "SalesOrder");
Parameters.Insert("Date", CurrentDate());
Parameters.Insert("Number", "00001");
Parameters.Insert("Customer", CustomerRef);
CreateDocument(Parameters);
```

### Solution 2: Use Object/Builder Pattern

```bsl
// ✅ Create a builder or configuration object
Function CreateDocumentBuilder() Export
    Builder = New Structure;
    Builder.Insert("DocumentType", "");
    Builder.Insert("Date", CurrentDate());
    Builder.Insert("Number", "");
    Builder.Insert("Options", New Structure);
    Return Builder;
EndFunction

Procedure BuildDocument(Builder) Export
    // Create document using builder properties
EndProcedure
```

### Solution 3: Group Optional Parameters

```bsl
// ✅ Group optional parameters into options structure
Procedure FormatData(Data, Options = Undefined) Export
    // Set defaults
    If Options = Undefined Then
        Options = New Structure;
    EndIf;
    
    Format = ?(Options.Property("Format"), Options.Format, "XML");
    Encoding = ?(Options.Property("Encoding"), Options.Encoding, "UTF-8");
    IncludeHeader = ?(Options.Property("IncludeHeader"), Options.IncludeHeader, True);
    PrettyPrint = ?(Options.Property("PrettyPrint"), Options.PrettyPrint, False);
    
    // Process with options
EndProcedure

// Calling code
Options = New Structure("Format, PrettyPrint", "JSON", True);
FormatData(MyData, Options);
```

### Solution 4: Split Into Multiple Methods

```bsl
// ❌ Before - monolithic function
Function ProcessOrder(Order, Customer, Date, Status, Manager, 
    Warehouse, Currency, Options...) Export
EndFunction

// ✅ After - split by responsibility
Function CreateOrder(OrderData) Export
    // Create the order
EndFunction

Procedure AssignOrderManager(Order, Manager) Export
    // Assign manager
EndProcedure

Procedure SetOrderOptions(Order, Options) Export
    // Apply options
EndProcedure
```

---

## 📋 Parameter Limits

### Standard Limits

| Parameter Type | Maximum Count | Configurable |
|---------------|---------------|--------------|
| Total parameters | 7 | Yes |
| Parameters with default values | 4 | Yes |

### Check Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxParameters` | 7 | Maximum total parameters |
| `maxParametersWithDefaultValue` | 4 | Maximum optional parameters |

---

## 📖 Refactoring Strategies

### Strategy 1: Parameter Object

```bsl
// Before - many parameters
Procedure SendEmail(To, From, Subject, Body, CC, BCC, Attachments, Priority)

// After - parameter object
Procedure SendEmail(EmailMessage) Export
    // EmailMessage is a Structure or custom type
EndProcedure

// Create helper function
Function NewEmailMessage() Export
    Return New Structure("To, From, Subject, Body, CC, BCC, Attachments, Priority",
        "", "", "", "", New Array, New Array, New Array, "Normal");
EndFunction
```

### Strategy 2: Method Chaining

```bsl
// Create a fluent interface
Function CreateReportBuilder() Export
    Builder = New Structure;
    Builder.Insert("ReportType", "");
    Builder.Insert("Period", Undefined);
    Builder.Insert("Filters", New Structure);
    Return Builder;
EndFunction

Procedure SetReportType(Builder, ReportType) Export
    Builder.ReportType = ReportType;
EndProcedure

Procedure SetReportPeriod(Builder, StartDate, EndDate) Export
    Builder.Period = New Structure("Start, End", StartDate, EndDate);
EndProcedure

Procedure AddReportFilter(Builder, FilterName, FilterValue) Export
    Builder.Filters.Insert(FilterName, FilterValue);
EndProcedure

Function BuildReport(Builder) Export
    // Generate report using builder configuration
EndFunction
```

### Strategy 3: Context Object

```bsl
// Pass context that contains related data
Procedure ProcessDocumentInContext(Document, ProcessingContext) Export
    // ProcessingContext contains User, Settings, Options, etc.
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Analyze parameters

Group parameters by their purpose:
- Required data parameters
- Optional behavior modifiers
- Context/environment parameters

### Step 2: Choose refactoring strategy

| Situation | Strategy |
|-----------|----------|
| Many related data parameters | Parameter Object/Structure |
| Many optional parameters | Options structure |
| Mixed concerns | Split into multiple methods |
| Complex configuration | Builder pattern |

### Step 3: Refactor gradually

1. Create new method with better signature
2. Have old method call new one
3. Update call sites
4. Remove old method

### Step 4: Update documentation

Document the new parameter structure.

---

## 🔍 Technical Details

### What Is Checked

1. All procedure and function declarations
2. Parameter count analysis
3. Default value parameter counting

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.MethodTooManyPramsCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 640](https://its.1c.ru/db/v8std/content/640/hdoc)
- [Method Optional Parameter Check](method-optional-parameter-before-required-check.md)
