# is-in-role-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `is-in-role-check` |
| **Title** | Using "IsInRole" method |
| **Description** | Use the AccessRight() function instead of IsInRole() |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [488](https://its.1c.ru/db/v8std/content/488/hdoc) |

---

## 🎯 What This Check Does

This check identifies usage of the **`IsInRole()`** method and recommends using **`AccessRight()`** function instead.

### Why This Is Important

- **Role independence**: `AccessRight()` checks actual rights, not role membership
- **Flexibility**: Rights can come from multiple roles
- **Role changes**: Configuration updates may change role structure
- **Standards compliance**: Follows Standard 488

---

## ❌ Error Example

### Error Message

```
Use the AccessRight() function instead of IsInRole()
```

**Russian:**
```
Используйте функцию ПравоДоступа() вместо РольДоступна()
```

### Noncompliant Code Example

```bsl
// ❌ Checking specific role
If IsInRole("FullAccess") Then
    ShowAdminPanel();
EndIf;

// ❌ Multiple role checks
If IsInRole("Salesperson") Or IsInRole("SalesManager") Then
    EnableSalesFeatures();
EndIf;

// ❌ Role check for data access
If IsInRole("Accountant") Then
    LoadFinancialData();
EndIf;
```

---

## ✅ Compliant Solution

### Correct Code Using AccessRight

```bsl
// ✅ Check specific access right
If AccessRight("Administration", Metadata) Then
    ShowAdminPanel();
EndIf;

// ✅ Check right to specific object
If AccessRight("View", Metadata.Documents.SalesOrder) Then
    EnableSalesFeatures();
EndIf;

// ✅ Check data access right
If AccessRight("Read", Metadata.Documents.Invoice) Then
    LoadFinancialData();
EndIf;
```

---

## 📖 Understanding the Difference

### IsInRole vs AccessRight

| Aspect | IsInRole | AccessRight |
|--------|----------|-------------|
| Checks | Role membership | Actual permission |
| Flexibility | Low (specific role) | High (any role grants) |
| Maintenance | Role name changes break code | Rights are stable |
| Best practice | ❌ Not recommended | ✅ Recommended |

### Why AccessRight Is Better

```bsl
// Scenario: User needs "View" access to Documents.Invoice

// IsInRole approach - ❌ Fragile
If IsInRole("Accountant") Or IsInRole("Manager") Or IsInRole("Administrator") Then
    // Must list ALL roles that have this right
    // If new role is added - code must change
EndIf;

// AccessRight approach - ✅ Robust
If AccessRight("Read", Metadata.Documents.Invoice) Then
    // Works regardless of WHICH role grants the right
    // New roles automatically work
EndIf;
```

---

## 📋 AccessRight Function

### Syntax

```bsl
AccessRight(RightName, MetadataObject)
```

### Common Rights

| Right | Description |
|-------|-------------|
| `Read` | View data |
| `Insert` | Create new records |
| `Update` | Modify existing records |
| `Delete` | Remove records |
| `View` | View object (forms, reports) |
| `Edit` | Edit object |
| `Post` | Post documents |
| `UndoPosting` | Unpost documents |
| `Administration` | Administrative functions |
| `InteractiveInsert` | Interactive record creation |

### Examples

```bsl
// Check read access to catalog
If AccessRight("Read", Metadata.Catalogs.Products) Then
    LoadProducts();
EndIf;

// Check posting access
If AccessRight("Post", Metadata.Documents.Invoice) Then
    Items.PostButton.Visible = True;
EndIf;

// Check administrative access
If AccessRight("Administration", Metadata) Then
    Items.AdminMenu.Visible = True;
EndIf;

// Check delete access
If AccessRight("Delete", Metadata.Catalogs.Customers) Then
    Items.DeleteButton.Enabled = True;
EndIf;
```

---

## 📋 When IsInRole Might Be Acceptable

### Exceptional Cases

| Scenario | Acceptable? |
|----------|-------------|
| Role-specific business logic | ⚠️ Maybe |
| UI personalization by role | ⚠️ Maybe |
| License checking | ⚠️ Maybe |
| Rights checking | ❌ No - use AccessRight |

### Example of Acceptable Use

```bsl
// Specific role-based workflow (rare case)
// When business logic genuinely differs by role, not by rights
If IsInRole("Approver") Then
    // Role-specific workflow, not rights-based
    EnableApprovalWorkflow();
ElsIf IsInRole("Requestor") Then
    EnableRequestWorkflow();
EndIf;
```

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `exceptionRoles` | `""` | Comma-separated list of role names that won't trigger warning |

---

## 🔧 How to Fix

### Step 1: Identify IsInRole usage

Find all occurrences:
- `IsInRole(`
- `РольДоступна(`

### Step 2: Determine what right is being checked

Ask: "What can users with this role DO?"

### Step 3: Replace with AccessRight

| If checking role for... | Replace with... |
|------------------------|-----------------|
| Data viewing | `AccessRight("Read", Metadata.Object)` |
| Data editing | `AccessRight("Update", Metadata.Object)` |
| Data creation | `AccessRight("Insert", Metadata.Object)` |
| Data deletion | `AccessRight("Delete", Metadata.Object)` |
| Administration | `AccessRight("Administration", Metadata)` |
| Document posting | `AccessRight("Post", Metadata.Documents.Doc)` |

### Step 4: Test

Verify the logic works correctly for all applicable roles.

---

## 🔍 Technical Details

### What Is Checked

1. Scans code for `IsInRole` method calls
2. Reports each occurrence
3. Allows exceptions via parameter

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.IsInRoleCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 488](https://its.1c.ru/db/v8std/content/488/hdoc)
