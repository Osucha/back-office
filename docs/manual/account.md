# Your account

**My account** (`/account`, also in the avatar menu) holds your profile, preferences, sign-in security,
personal access tokens, the IDE connection and an overview of your access. It is one scrolling page with
a section index on the left; `?section=` in the URL jumps to a section.

| Section | What you do there |
|---|---|
| **Profile** | See your name, e-mail, groups and this session. |
| **Preferences** | Theme, density, rows per page, default project. |
| **Notifications** | Desktop notifications and sounds (see [Desktop notifications](#notifications)). |
| **Security** | Sign-in method, password, two-factor authentication, recent sign-ins. |
| **Personal access tokens** | Create and revoke tokens for scripts and your IDE. |
| **Connect your IDE** | Set up the MCP server in Claude Code, Cursor or VS Code. |
| **My access** | Your platform permissions and your projects with roles. |

**Sign out** is in the page header and in the avatar menu.

<a id="profile"></a>
## Profile and preferences

**Profile** shows **Name**, **E-mail**, **Groups** and **This session**: when you signed in and when the
session ends at the latest. An administrator manages your name, e-mail and groups (see
[Administration](administration.md#users)).

A session also ends after a period without activity; the app warns you and offers to sign in again.

**Preferences**:

| Preference | Options |
|---|---|
| **Theme** | **System** (follows your operating system), **Light**, **Dark**. Also `Alt T` or **Toggle theme** in the command palette. |
| **Density** | **Comfortable** or **Compact** rows. Applies right away, in this browser. |
| **Rows per page** | 10 to 200; longer lists scroll virtually. |
| **Default project** | Selected on the rail when you sign in. |

Click **Save preferences**.

**My access** lists your **Platform permissions** and, under **Projects**, each project with your roles
and the number of permissions they give you. If you hold `admin.projects.all`, you have every project
permission in every project. To get more access, ask a project owner (project roles) or an administrator
(platform roles, see [Roles and grants](administration.md#roles-grants)).

<a id="notifications"></a>
## Desktop notifications and sounds

Back Office can tell you on your desktop when something needs you, with a short, quiet sound of its own.
Notifications appear while a Back Office tab is open in your browser, also when the tab is in the
background or the window is minimised. They are not sent when every Back Office tab is closed. They are
always *in addition* to the counters in the app (the amber **Your turn** counter, the activity dock):
nothing is announced only by a notification or only by a sound.

### Turn them on

1. Open **My account** > **Notifications** (or click your avatar and choose **Desktop notifications**).
2. Switch on **Desktop notifications**. Your browser asks whether Back Office may show notifications:
   choose **Allow**. The browser asks only once; it never asks on its own.
3. Choose what you want to hear about and click **Preview** next to a type to hear its sound.
4. Click **Send a test notification** to check that your system shows it.

| Setting | What it does | Default |
|---|---|---|
| **Your turn** | A gate you can decide starts waiting, or someone assigns one to you. The most useful one. A step that wants to write to Jira and waits for your confirmation is announced here too, with its own title "Confirm a Jira write" and a line such as "Agent wants to comment on SHOP-42" ([Confirming external writes](your-turn.md#write-confirmations)). | on |
| **Run failed** | A run you started, or a run in a project you own, failed. | on |
| **Run landed** | Such a run finished successfully. | off |
| **Budget alert** | A budget you can raise reached its alert threshold or its limit. | on |
| **CI failed** | A CI execution or review gate in one of your projects failed. | off |
| **Connection expiring** | The token of a Jira connection you can manage expires within 14 days or has expired; once per expiry date ([Token expiry](integrations-jira.md#jira-token-expiry)). | on for administrators (`admin.connections.manage`), off for everyone else |
| **Only when the tab is not in view** | Nothing pops up while you are looking at Back Office (another tab, another window or a minimised browser count as "not in view"). | on |
| **Play a sound** / **Volume** | Each type has its own short tone: a rising two-note handoff for *your turn*, a light three-note rise when a run lands, a soft falling pair when something failed, a muted double tap for budgets. Previews play even when the sound is off. | on, 60 % |
| **Quiet hours** | No notifications and no sounds between two times of day (your computer's clock); can span midnight, e.g. 22:00 to 07:00. | off |

Clicking a notification brings the Back Office tab to the front and opens the gate, write confirmation,
run, CI execution, budget or connection. Several open Back Office tabs show one notification, not one per tab. The settings are saved at
once, in this browser, for your account; on another computer or browser you set them again. Notifications
show only names and keys (project, issue, workflow, step), never agent output or secrets.

### If your browser blocks notifications

When the section says that your browser blocks notifications, you (or a policy of your organisation)
refused them earlier. The browser does not ask again; allow them in the site settings and reload the
page:

| Browser | Steps |
|---|---|
| **Chrome** | Click the icon left of the address (site information) > **Notifications** > **Allow**. Or **Settings** > **Privacy and security** > **Site settings** > **Notifications**. |
| **Edge** | Click the padlock left of the address > **Permissions for this site** > **Notifications** > **Allow**. Or **Settings** > **Cookies and site permissions** > **Notifications**. |
| **Firefox** | Click the permissions icon left of the address and clear **Blocked: Send notifications**; or **Settings** > **Privacy & Security** > **Permissions** > **Notifications** > **Settings…**. |
| **Safari** (macOS) | **Safari** > **Settings** > **Websites** > **Notifications**: set the Back Office address to **Allow**. |

Browsers show notifications only for secure addresses (`https://`, or `localhost` on your own computer).
If the section says that the browser cannot show them, use a current browser and the `https://` address.

### If notifications do not appear

- **Windows**: Focus assist / **Do not disturb** (Settings > System > Notifications) hides them; also
  check that notifications from your browser (Chrome, Edge, Firefox) are on in the same place.
- **macOS**: a **Focus** mode (Control Centre) hides them; in **System Settings** > **Notifications** allow
  notifications for your browser (style *Alerts* keeps them on screen until you act).
- **Linux**: the desktop's do-not-disturb setting.
- The settings **Only when the tab is not in view** and **Quiet hours** hold notifications back on purpose.
- No sound: the browser plays sounds only after you have clicked or typed in the page once; check the
  volume setting and that the tab is not muted.

<a id="mfa"></a>
## Sign-in security and two-factor authentication

**Security** shows your **Sign-in method** and when you last signed in.

- **Single sign-on accounts** (Keycloak, Entra ID, Google, GitLab): **Password and two-factor** are
  managed by your identity provider; **Open … account** takes you there. Back Office stores no password
  for you.
- **Local accounts** (e-mail and password): you manage the password and two-factor authentication here.
  Which method you have is set by an administrator (see [Sign-in](integrations-identity.md#local-accounts)).

**Recent sign-ins** lists **When**, **Method**, **Result** and **From** for your last sign-ins. A sign-in
you do not recognise is a reason to change your password and tell an administrator.

### Change your password (local accounts)

1. Click **Change password**.
2. Enter the **Current password** and the new password twice. The hints under the field show the policy:
   at least 12 characters, not a common password, not containing your e-mail name.
3. Click **Change password**. Your other sessions are signed out.

### Set up two-factor authentication (local accounts)

Two-factor authentication asks for a code from your phone after your password, so a stolen password alone
is useless.

1. Click **Set up two-factor authentication** (or **Set up MFA** in the command palette).
2. **Scan the QR code** with an authenticator app such as 1Password, Google Authenticator, Microsoft
   Authenticator or Aegis. If you cannot scan, copy the setup key.
3. **Enter the code from the app** and click **Turn on two-factor authentication**.
4. **Save your recovery codes**: copy or download the 10 codes, tick **I saved these codes somewhere
   safe** and click **Done, I saved them**. They are shown only once; each one signs you in once without
   your phone.

From then on, signing in asks for a 6-digit code after your password; a recovery code works instead.

| Action | Effect |
|---|---|
| **Create new recovery codes** | Confirm with your password. The old codes stop working; save the 10 new ones. |
| **Turn off** | Confirm with your password. Signing in needs only the password; recovery codes stop working. Unavailable when the platform requires two-factor authentication. |

If you lose your phone and your recovery codes, an administrator can reset two-factor authentication for
you. When the platform requires two-factor authentication for local accounts, you are asked to set it up
right after signing in, before you can use anything else.

<a id="tokens"></a>
## Personal access tokens

A personal access token (`bo_pat_…`) lets a script, the CLI or your IDE act as you without a browser
session. It has your permissions, optionally narrowed, and stops working when it expires, when you revoke
it, or when your account is deactivated. Creating tokens needs `tokens.personal` (every **Member** has
it).

### Steps

1. In **Personal access tokens**, click **Create token**.
2. Enter a **Name** (for example `release script`).
3. Set **Expires in (days)**: required, at most 365 days or the lower limit your administrator set.
4. Optionally **Limit to permissions**: empty means all of your permissions. A token never has more than
   you.
5. Click **Create token**, copy the token (it is shown only once) and click **I copied the token**.

Use it as `Authorization: Bearer bo_pat_…`. The list shows each token's **Prefix**, **Permissions**,
**Expires**, **Last used** and **State** (active, expired, revoked); **Revoke** stops a token at once.
Create one token per script or device, so you can revoke one without breaking the others.

Personal access tokens are the only tokens the MCP server accepts. CI pipelines use project CI tokens
instead (see [Projects](projects.md#project-ci)).

## Connect your IDE

Back Office is an MCP server: your coding agent can list your turns, read specs and reviews, start
workflows and decide gates from the editor, with your permissions.

1. Create a personal access token as above; limit its permissions and expiry.
2. In **Connect your IDE**, pick your client: **Claude Code**, **Cursor** or **VS Code**.
3. Copy the snippet. For Claude Code (a terminal command) and Cursor (`.cursor/mcp.json`), replace the
   placeholder with your token; keep the Cursor file out of version control. VS Code (`.vscode/mcp.json`)
   asks for the token on first use and keeps it in its secret storage.

The section lists the tools the server offers. Revoking the token disconnects the IDE. Details per client
are in [IDE and MCP](integrations-ide.md#pat).

<a id="connections"></a>
## Connections

**My account** → **Connections** holds your **personal Jira connections**: Jira connections that act as you.
Workflow steps set to act as "the person who approves or starts them" use one of them to comment on or move
issues in your name, and only when you approve that write or start that run. Nobody else can see or use them;
administrators only see that they exist. Use a scoped API token for them. Details:
[Personal connections](integrations-jira.md#jira-personal-connections).
