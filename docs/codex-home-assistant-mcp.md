# Connect Codex to this Home Assistant instance

Use one canonical MCP server name on every Codex installation:
`home-assistant`. The Codex user configuration is global to that installation,
so the connection is available to every new local Codex task, in every project.

This Home Assistant endpoint currently requires OAuth. A webhook URL is still a
secret and must not be committed, pasted into issues, or put in build logs, but
the URL alone is not sufficient to use this server. Codex must complete its own
OAuth login and store the resulting credentials in that installation's encrypted
credential store.

## Set up each Codex installation

Each computer, operating-system account, or isolated Codex environment needs
its own registration and OAuth login. Do **not** copy `mcp_oauth.age`, access
tokens, refresh tokens, or another machine's `.codex` directory. Codex encrypts
those credentials locally; copying them is both unreliable and unsafe.

1. Obtain the Home Assistant MCP URL through the approved secret-sharing path.
   Treat the full URL as a credential.
2. In a terminal with the standalone Codex CLI, register the endpoint under the
   canonical name and complete the browser login:

   ```text
   codex mcp add home-assistant --url "https://<home-assistant-host>/api/webhook/<mcp-webhook-id>"
   codex mcp login home-assistant --oauth-client-registration auto
   ```

   The login command opens a Home Assistant sign-in/consent flow when needed.
   Complete it with an approved Home Assistant account; never add a bearer
   token, client secret, or custom authorization header unless the Home
   Assistant administrator has deliberately selected a different authentication
   mode.

3. If `codex` is not directly runnable from the terminal on a desktop-app
   installation, use Codex Desktop's **Add Custom MCP** flow with the same
   `home-assistant` name and URL, then complete the OAuth sign-in that Codex
   offers. Do not run the executable inside the Microsoft Store `WindowsApps`
   package folder.
4. Start a **new local Codex task**. A task that was already running does not
   gain MCP tools after a connection is added or reauthenticated.

The command below confirms the global registration. The last column should say
`enabled`; it will also say `OAuth` when the server requires a login.

```text
codex mcp list
```

## Verify without changing Home Assistant

Registration is not proof that the server is usable. Run the repository health
check from a new terminal before relying on the connection for deployment work:

```powershell
.\tools\verify-codex-home-assistant-mcp.ps1
```

The script starts an isolated, read-only Codex session and permits exactly one
clearly non-mutating Home Assistant MCP call. It prints only the verification
result, not Home Assistant state or credentials. Pass `-CodexPath` if Codex is
installed somewhere the script cannot discover:

```powershell
.\tools\verify-codex-home-assistant-mcp.ps1 -CodexPath "C:\path\to\codex.exe"
```

For the Shield work, call Home Assistant `androidtv` actions only after this
read check passes. See [Home Assistant ADB deployment](home-assistant-adb-deployment.md)
for the signed-APK upload, install, launch, and `gfxinfo` sequence.

## Recover from an authorization failure

An HTTP `401` or missing Home Assistant tools means Codex has no valid OAuth
session for that installation. Repair it in this order:

```text
codex mcp login home-assistant --oauth-client-registration auto
codex mcp list
```

Then begin a new local Codex task and run the read-only check again. Do not
create `ha-fresh`, `ha-fresh-2`, or similarly numbered replacement servers as
the normal recovery path: their separate names create separate credential
records and make future diagnosis harder.

If login cannot complete, first verify the Home Assistant server itself is
running and that the external HTTPS URL is reachable. The server's authentication
mode may have been changed by its administrator. In that case, update the
server configuration or obtain the approved connection details, then retry the
same canonical `home-assistant` entry. Do not edit encrypted OAuth storage by
hand.

## Safe handoff details

When another developer needs this connection, share only:

- The server label: `home-assistant`.
- The expected authentication type: Home Assistant OAuth.
- The entity IDs and intended Home Assistant actions required for the task.

Share the URL and all OAuth information only through an approved secret manager
or the local Codex configuration flow.
