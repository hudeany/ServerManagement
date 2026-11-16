# ServerManagement

**Watch and maintain a set of servers from one desktop window: SSH users and their keys, logins, URLs, software versions and logs.**

ServerManagement is a Java Swing desktop application. Every server gets its own card that shows, and lets you change:

| | |
|---|---|
| 🔑 **SSH users** | The authorized public keys of each managed SSH user, compared with the keys you expect. Missing and surplus keys are highlighted, and corrections are written to the server directly. |
| 🔐 **Logins** | HTTP Basic and OIDC (e.g. Keycloak) logins to web applications, checked with one click. |
| 🌐 **URLs** | Reachability and response time of any number of URLs. |
| 📦 **Versions** | Versions of installed software, read from REST services, files or shell commands and extracted with JSON path, XPath, regex or properties keys. |
| 📜 **Logs** | A live SSH log viewer (`tail -F`) with search, regex and a filter mode. |

One click on **Check server status** checks all servers. Each table is then colored by its worst result (🟢 OK, 🟡 warning, 🔴 error), and the servers with errors are listed.

---

## Features

### Central SSH key management
- Keep one list of **known public keys** with an id and a readable name
- Assign keys to the managed SSH users of every server
- See at a glance which users have **missing** or **surplus** keys
- Transfer keys to a server, drop them from a server, or take over unknown keys from a server into the known keys
- Add a new key to **all users on all servers** in one step, or remove a key everywhere

### Version detection
A version entry combines a **location** (where the data comes from) and a **path** (how to find the version in it):

| Location | Example |
|---|---|
| HTTP(S) without login | `https://host/app/actuator/info` |
| HTTP(S) with a configured login (GET or POST, OIDC or Basic) | `POST:https://monitor@host/rest/versions/getVersionData` |
| File or directory via SSH/SFTP | `ssh:///etc/os-release` |
| Command via SSH | `ssh-cmd:// java -version 2>&1` |

| Path | Example |
|---|---|
| JSON path (RFC 9535, with filters and functions) | `json:$.versionData[?(@.name=='nexInsure')].version` |
| XPath | `xml:/project/version` |
| Regular expression (whole match or group) | `regex(1):version "([^"]+)"` |
| Properties key | `properties:VERSION_ID` |

Within one check, each location is loaded once, each OIDC login runs once and SSH connections are reused. Many versions from the same REST response therefore cost only one request.

### OIDC login without a browser
ServerManagement logs in to OpenID Connect protected applications the way a simple browser would. It follows redirects, keeps cookies, fills in the identity provider's login form (also two-step and `form_post` flows) and picks up the CSRF token of the application. The session is then used to read version data from the application's REST endpoints.

### Secure by default
- Passwords of logins and SSH admins are stored **encrypted (AES-256-GCM)** with a local master key file
- TLS certificates and host names are always verified, against the Java trust store or your own keystore
- Per-login proxy settings, including a direct connection

---

## Getting started

### Requirements
- Java 17 or newer
- A desktop environment (no headless mode)

### Start

```sh
java -jar ServerManagement.jar [serversConfigurationFile]
```

Without a parameter, the configuration is stored in `~/.ServerManagement/.ServerManagement.servers.config`.

| Command | Description |
|---|---|
| `java -jar ServerManagement.jar help` | Show the command line help |
| `java -jar ServerManagement.jar version` | Show the installed version |
| `java -jar ServerManagement.jar update [username [password]]` | Check for an online update and install it |

### First steps
1. **Add server**: enter a name and the host (`hostname` or `hostname:port`)
2. **Admindata**: set the SSH admin (user name and private key file or password) used for all SSH access
3. **Read available users**: take over the SSH users of the server
4. **Manage public keys**: add the public keys you want to manage, then double-click a user to assign them
5. Add logins, URLs, versions and logs as needed
6. **Check server status**, and **Save server settings** to keep your changes

> 💾 **Back up the master key file** (`<configuration file>.masterkey`) together with your configuration. Without it, the stored passwords cannot be decrypted anymore.

---

## Documentation

The complete manual is available in the application (**Help → Manual**) in English and German. It covers all location and path forms, caching, TLS and proxy settings in detail.

---

## Disclaimer

ServerManagement accesses your servers via SSH and HTTP(S) and **can change data on them**, e.g. the `authorized_keys` of SSH users. It is intended for experimental use only, and any use is at your own risk. The developer accepts **no liability whatsoever**, neither for the correct function nor for any damage resulting from the use of the program, such as lost access to a server.

## Contact

Suggestions for improvement and bug reports are welcome: **servermanagement@soderer.de**

*Author: Andreas Soderer*
