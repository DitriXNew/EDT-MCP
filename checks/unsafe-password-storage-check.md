# unsafe-password-storage-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `unsafe-password-storage-check` |
| **Title** | Avoid storing passwords in the infobase |
| **Description** | Checks for password storage attributes in metadata |
| **Severity** | `CRITICAL` |
| **Type** | `VULNERABILITY` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **metadata attributes** that appear to store **passwords** in the database. Storing passwords in the infobase is a security vulnerability.

### Why This Is Important

- **Security risk**: Passwords can be accessed by administrators
- **Data breach**: Database dumps expose passwords
- **Compliance**: Violates security standards (GDPR, PCI-DSS)
- **Best practices**: Passwords should never be stored in plain text

---

## ❌ Error Example

### Error Message

```
Avoid storing passwords in the infobase
```

### Noncompliant Configuration

```
Catalog: Users
└── Attributes
    └── Password                     ❌ Password in DB!
        └── Type: String
        
InformationRegister: ServiceCredentials
└── Resources
    ├── Username: String
    └── Password: String             ❌ Password in DB!
    
Catalog: Integrations
└── Attributes
    ├── APIKey: String               ❌ Sensitive credential!
    └── SecretToken: String          ❌ Sensitive credential!
```

---

## ✅ Compliant Solution

### Use Platform Security Mechanisms

```
// ✅ Use SecureStorage (1C:Enterprise)
// Don't create Password attributes in metadata

// Store sensitive data in platform secure storage:
SecureDataStorage.Write(StorageKey, PasswordValue);

// Retrieve when needed:
StoredPassword = SecureDataStorage.Read(StorageKey);
```

### Use External Authentication

```
// ✅ Use platform authentication
// Don't store passwords yourself

User authentication options:
├── Platform user management
├── OS authentication
├── OpenID/OAuth
└── Active Directory
```

---

## 📋 Why Password Storage Is Dangerous

### Visibility Issues

```
Passwords in database visible to:
├── Database administrators
├── Users with data access rights
├── Backup administrators
├── Anyone with SQL access
├── Data migration tools
└── Reporting tools
```

### Attack Scenarios

```
If passwords stored in DB:
├── SQL injection → Dump users table
├── Backup theft → Extract passwords
├── Admin access → View all passwords
├── Data export → Passwords in files
└── Logs → May contain password data
```

---

## 📋 Secure Alternatives

### 1. Platform Authentication

```
// Use 1C:Enterprise user management
// Platform handles password hashing
// No password attribute needed in metadata

InfoBaseUsers.CreateUser()
InfoBaseUsers.CurrentUser()
```

### 2. Secure Storage API

```bsl
// Store credentials securely
Procedure SaveServiceCredentials(ServiceName, Username, Password)
    SecureDataStorage.Write(ServiceName + "_User", Username);
    SecureDataStorage.Write(ServiceName + "_Pass", Password);
EndProcedure

// Retrieve credentials
Function GetServiceCredentials(ServiceName)
    Result = New Structure;
    Result.Insert("Username", SecureDataStorage.Read(ServiceName + "_User"));
    Result.Insert("Password", SecureDataStorage.Read(ServiceName + "_Pass"));
    Return Result;
EndFunction
```

### 3. OAuth/Token-Based Authentication

```bsl
// Instead of storing passwords:
// Store only access tokens with limited scope
// Tokens can be rotated and revoked

Catalog: ExternalServices
└── Attributes
    ├── ServiceURL
    ├── ClientID (not secret)
    └── (AccessToken stored in SecureStorage, not in attribute)
```

### 4. Password Hashing (If Must Store)

```bsl
// If password MUST be stored (e.g., for hash comparison):
// Store HASH, not password!

Function HashPassword(Password, Salt)
    // Use strong hash algorithm
    DataHashing = New DataHashing(HashFunction.SHA256);
    DataHashing.Append(Salt + Password);
    Return DataHashing.HashSum;
EndFunction

// Store only: PasswordHash, Salt
// Never store: Password
```

---

## 📋 Attribute Names to Avoid

### Suspicious Attribute Names

| Name | Risk |
|------|------|
| Password | Direct password storage |
| UserPassword | Direct password storage |
| AccessKey | Credential storage |
| SecretKey | Secret storage |
| APIKey | Credential storage |
| Token (persistent) | May be sensitive |
| Credential | Sensitive data |
| Secret | Sensitive data |

---

## 📋 Legitimate Use Cases

### When Credentials Are Needed

```
// For external service integration:
// Use session-based tokens, not persistent passwords

// For batch jobs:
// Use service accounts with platform authentication

// For API access:
// Store tokens in SecureStorage, not attributes
```

### Secure Design Pattern

```
Catalog: ExternalServices
└── Attributes
    ├── ServiceName         ✅ OK
    ├── ServiceURL          ✅ OK
    ├── IsActive            ✅ OK
    └── (No password here)  ✅ 
    
// Credentials stored separately in SecureStorage
// Accessed only when needed, not visible in forms
```

---

## 🔧 How to Fix

### Step 1: Remove password attributes

Delete attributes storing passwords.

### Step 2: Implement secure storage

Use platform SecureStorage or external vault.

### Step 3: Update code

Modify code to use secure storage methods.

### Step 4: Clear existing data

Remove passwords from database if any stored.

---

## 📋 Migration Steps

### Moving From Password Attributes

```bsl
// Migration procedure:
Procedure MigratePasswordsToSecureStorage()
    Query = New Query;
    Query.Text = "SELECT Ref, Username, Password FROM Catalog.Services";
    
    Selection = Query.Execute().Select();
    While Selection.Next() Do
        // Move to secure storage
        StorageKey = "Service_" + String(Selection.Ref.UUID());
        SecureDataStorage.Write(StorageKey, Selection.Password);
    EndDo;
    
    // Then: Remove Password attribute from metadata
    // And clear any existing data
EndProcedure
```

---

## 📋 Security Standards Compliance

### Requirements

| Standard | Requirement |
|----------|-------------|
| GDPR | Protect personal data including credentials |
| PCI-DSS | Never store passwords in clear text |
| SOC 2 | Secure credential management |
| ISO 27001 | Information security controls |

---

## 🔍 Technical Details

### What Is Checked

1. Attribute names containing "password", "secret", etc.
2. Type analysis for credential patterns
3. Catalog and document attributes

### Check Implementation Class

```
com.e1c.v8codestyle.md.check.UnsafePasswordStorageCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.md/src/com/e1c/v8codestyle/md/check/
```

---

## 📚 References

- [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [Information Security Standards](https://its.1c.ru/db/v8std)
