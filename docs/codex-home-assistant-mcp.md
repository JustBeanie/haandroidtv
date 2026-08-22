# Connect Codex to this Home Assistant instance

This project uses a Home Assistant MCP server over a protected webhook URL.
The original Home Assistant setup can involve browser-based pairing, but the
resulting webhook URL is the runtime authorization mechanism for this instance.
Keep the instance URL, webhook ID, access token, and refresh token out of Git,
chat transcripts, and build logs.

## Configure the local Codex client

Create the MCP connection on the computer that runs Codex. Replace the
placeholders locally; do not paste the real values into this repository.

```toml
# %USERPROFILE%/.codex/config.toml
[mcp_servers.ha-fresh]
url = "https://<home-assistant-host>/api/webhook/<mcp-webhook-id>"
enabled = true
```

The webhook URL is a capability URL. Treat it like a credential even though it
does not look like a password. Do not add a bearer token, custom headers, or
an OAuth section unless Home Assistant explicitly asks for one: this instance
successfully initializes from the protected webhook URL alone.

If starting from a supported standalone Codex CLI, add the server with a fresh
label:

```text
codex mcp add ha-fresh --url "https://<home-assistant-host>/api/webhook/<mcp-webhook-id>"
```

Begin a **new local Codex task** after saving the server. A running task does
not receive tools from a server that becomes ready mid-task.

## Verify the connection

Configuration alone is not proof of a usable connection. In the new task,
verify that Home Assistant tools are present (normally named
`mcp__ha_fresh__...`) and make a harmless read-only call before using ADB or
calling any Home Assistant action.

For the Shield work, use the Home Assistant `androidtv` actions only after
that read succeeds. See [Home Assistant ADB deployment](home-assistant-adb-deployment.md)
for the signed-APK upload, install, launch, and `gfxinfo` sequence.

## Stale OAuth configuration recovery

The observed failure mode for this instance is a stale OAuth session:

```text
OAuth token refresh failed: server returned empty error response
```

When this happens, Codex intentionally omits the old `ha` server from the task
tool catalog because its saved OAuth session is no longer ready. Do not delete
or edit `mcp_oauth.age` manually: it is encrypted credential storage and may
contain other server sessions.

### When `Authenticate` is not shown

Some Codex Desktop versions retain the failed `ha` session as neither ready
nor logged out. Its settings page then shows only the transport fields—URL,
optional bearer-token variable, and headers—and no **Authenticate** button.
That is expected for this failure state. Leave those fields unchanged and do
**not** uninstall the old connection first.

Instead, return to the MCP server list and create a temporary fresh server:

1. Select **Back**, then **Add → Custom MCP**.
2. Name it `ha-fresh` (the new name is important: OAuth storage is associated
   with the server name).
3. Enter the same protected webhook URL. Leave the optional bearer-token and
   header fields blank unless this instance was explicitly configured to use
   them.
4. Save it. No **Authenticate** button is expected for this protected-webhook
   configuration.
5. Start a new local task and verify `mcp__ha_fresh__...` tools are available.

Only after `ha-fresh` is working should the old `ha` connection be removed.
Keeping it until verification preserves the original protected endpoint and
configuration as a fallback.

On Windows, do not run the `codex.exe` inside the Microsoft Store package
directory (`Program Files\\WindowsApps`). It is owned by the package and a
normal terminal will report **Access is denied**. Use the Codex Desktop
connection UI or an independently installed standalone Codex CLI for the
login command.

After re-authorizing, restart the local Codex client if it does not load the
server automatically, start a new local task, and repeat the verification
above. The repair is complete only when `ha` tools are actually present and a
read-only Home Assistant call succeeds.

## Safe handoff details

When another model or developer needs to use this connection, share only:

- The server label: `ha`.
- The fact that it uses a Home Assistant MCP webhook and browser OAuth.
- The entity IDs and intended Home Assistant actions required for the task.

Share the webhook URL and OAuth information only through an approved secret
manager or the local Codex configuration flow.
