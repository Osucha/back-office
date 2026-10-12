# Sign-in (OIDC and local accounts)

People sign in to Back Office either through your identity provider (OpenID Connect) or with a local
account (e-mail, password, optional authenticator app). Back Office decides what each person may do; the
provider only says who they are and, optionally, which groups they belong to. This chapter configures the
OIDC client for Keycloak, Microsoft Entra ID, Google and GitLab, and sets up local accounts.

**Who needs this:** platform administrator; identity-provider administrator.

<a id="oidc-overview"></a>
## How OIDC sign-in works

The API is a confidential client (backend for frontend): it runs the authorization-code flow with PKCE,
keeps the tokens server-side and gives the browser only the session cookie `BO_SESSION`.

| Value to register at the provider | Value |
|---|---|
| Client type | Confidential web client, authorization code flow, client secret |
| Redirect URI | `<public URL>/login/oauth2/code/oidc`, for example `https://backoffice.example.com/login/oauth2/code/oidc` |
| Post-logout redirect URI | exactly `BACKOFFICE_PUBLIC_URL`, for example `https://backoffice.example.com` |
| Scopes | `openid profile email` (+ a groups scope where the provider needs one) |
| Claims used | `sub`, `iss`, `email`, `email_verified`, `name`, `preferred_username`, groups claim |

**Configuration** (Helm `back-office` chart; every variable also accepts the short alias in brackets):

| Setting | Variable | Helm value | Default |
|---|---|---|---|
| Issuer URL | `BACKOFFICE_AUTH_OIDC_ISSUER_URI` (`OIDC_ISSUER_URI`) | `auth.oidc.issuerUri` | empty = OIDC off |
| Client id | `BACKOFFICE_AUTH_OIDC_CLIENT_ID` (`OIDC_CLIENT_ID`) | `auth.oidc.clientId` | `back-office` |
| Client secret | `BACKOFFICE_AUTH_OIDC_CLIENT_SECRET` (`OIDC_CLIENT_SECRET`) | `auth.oidc.existingSecret` + `existingSecretKey` (`client-secret`), or `auth.oidc.clientSecret` | required |
| Scopes | `BACKOFFICE_AUTH_OIDC_SCOPES` (`OIDC_SCOPES`) | `auth.oidc.scopes` | `openid,profile,email` |
| Name on the sign-in page | `BACKOFFICE_AUTH_OIDC_PROVIDER_NAME` (`OIDC_PROVIDER_NAME`) | `api.extraEnv` | `Keycloak` |
| Provider account page link | `BACKOFFICE_AUTH_OIDC_ACCOUNT_URL` (`OIDC_ACCOUNT_URL`) | `api.extraEnv` | derived for Keycloak |
| Groups claim | `BACKOFFICE_AUTH_GROUPS_CLAIM` (`OIDC_GROUPS_CLAIM`) | `auth.groupsClaim` | `groups` |
| Default of "synchronise groups" | `BACKOFFICE_AUTH_SYNC_GROUPS` (`BACKOFFICE_SYNC_GROUPS`) | `api.extraEnv` | `false` |
| Registration | `BACKOFFICE_AUTH_REGISTRATION` (`BACKOFFICE_REGISTRATION`) | `auth.registration` | `INVITE_ONLY` |
| Allowed e-mail domains | `BACKOFFICE_AUTH_ALLOWED_EMAIL_DOMAINS` (`BACKOFFICE_ALLOWED_EMAIL_DOMAINS`) | `auth.allowedEmailDomains` | empty |
| First administrators | `BACKOFFICE_AUTH_BOOTSTRAP_ADMINS` (`BACKOFFICE_BOOTSTRAP_ADMINS`) | `auth.bootstrapAdmins` | empty |
| Session idle / absolute timeout | `BACKOFFICE_AUTH_SESSION_IDLE_TIMEOUT` / `_ABSOLUTE_TIMEOUT` | `auth.session.idleTimeout` / `absoluteTimeout` | `PT30M` / `PT12H` |

Registration, domains, group sync and token lifetime are only defaults: once saved in **Administration** →
**Platform settings** the saved values win ([administration.md](administration.md#platform-settings)).

**Who gets an account** at the first sign-in: an invited user with the same e-mail, an address listed in
the bootstrap admins (they also receive `platform-admin`), anyone in an allowed domain, or anyone when
registration is **Open**. Afterwards the user is bound to the provider's `iss` + `sub`.

**E-mail verification.** A first sign-in is refused (`email-not-verified`) only when the provider sends
`email_verified=false`. When the claim is **absent**, Back Office trusts the `email` claim as it is. That
matters for Entra ID, which does not send `email_verified`: see [Microsoft Entra ID](#entra).

**Test it**

1. Restart the API after changing values; the provider's discovery document is read at the first sign-in.
2. Open `<public URL>/login`: the button reads **Continue with** and the provider name.
3. Sign in with a bootstrap admin; **Administration** → **Audit log** shows `LOGIN_SUCCESS`, or
   `LOGIN_FAILURE` with the reason ([troubleshooting.md](troubleshooting.md#login)).

**Rotate the client secret**: create a second secret at the provider where it allows two (Entra, Google),
or regenerate it (Keycloak, GitLab); update the Secret named in `auth.oidc.existingSecret`; restart
`deploy/back-office-api` and `deploy/back-office-worker`. Existing sessions stay valid; only new sign-ins
use the client secret.

<a id="keycloak"></a>
## Keycloak

The Helm chart does not deploy Keycloak; run your own (the realm `deploy/keycloak/realm-back-office.json`
used by the local docker-compose setup is a complete example) and register the client:

1. Admin console → select the realm → **Clients** → **Create client**: type **OpenID Connect**, **Client ID**
   `back-office` → **Next**.
2. **Capability config**: **Client authentication** On, **Standard flow** On, **Direct access grants** Off
   → **Next**.
3. **Login settings**: **Valid redirect URIs** `https://backoffice.example.com/login/oauth2/code/oidc`,
   **Valid post logout redirect URIs** `https://backoffice.example.com`, **Web origins** `+` → **Save**.
4. **Advanced** tab → **Proof Key for Code Exchange Code Challenge Method** `S256` (Back Office always
   sends PKCE).
5. **Credentials** tab → copy the **Client secret**.
6. Groups (optional): **Client scopes** → `back-office-dedicated` → **Add mapper** → **By configuration** →
   **Group Membership**: **Token Claim Name** `groups`, **Full group path** Off, **Add to ID token** On,
   **Add to userinfo** On.
7. Users need a verified e-mail (**Users** → user → **Email verified** On).
8. Issuer: `https://sso.example.com/realms/<realm>`. It must be reachable from the API pods **and** equal
   the `iss` in tokens (Keycloak hostname settings, `KC_HOSTNAME`).

Rotation: **Credentials** → **Regenerate**, then update the Secret at once (the old secret stops working).
Docs: https://www.keycloak.org/docs/latest/server_admin/#_oidc_clients

<a id="entra"></a>
## Microsoft Entra ID

1. Microsoft Entra admin center (entra.microsoft.com) → **Identity** → **Applications** →
   **App registrations** → **New registration**. You need the Application Developer role or higher.
2. **Name** `Back Office`; **Supported account types**: **Accounts in this organizational directory only
   (Single tenant)**; **Redirect URI**: platform **Web**, `https://backoffice.example.com/login/oauth2/code/oidc`
   → **Register**.
3. **Authentication** → under **Web** add `https://backoffice.example.com` as a second redirect URI (used as
   the post-logout target).
4. **Certificates & secrets** → **Client secrets** → **New client secret**, expiry up to 24 months → copy
   the **Value** (not the Secret ID).
5. **Token configuration** → **Add optional claim** → **ID** → **email** (accept the Microsoft Graph
   `email` permission). Users need the **Email** (mail) attribute filled.
6. Groups (optional): **Token configuration** → **Add groups claim** → **Security groups** (or **Groups
   assigned to the application** for large directories), ID token: **Group ID**. Entra sends group
   **object ids**: use those GUIDs as the external group of Back Office groups.
7. **API permissions**: Microsoft Graph delegated `openid`, `profile`, `email` (and the default
   `User.Read`); grant admin consent if your tenant requires it.
8. Back Office: issuer `https://login.microsoftonline.com/<tenant id>/v2.0`, client id = **Application
   (client) ID**, `OIDC_PROVIDER_NAME=Microsoft`, `OIDC_ACCOUNT_URL=https://myaccount.microsoft.com`.

**Security note.** Entra does not send `email_verified`, and Back Office then trusts the `email` claim when
it binds an invited user at the first sign-in. With a single-tenant registration the claim comes from your
own directory, which administrators control. Never use a multi-tenant (`common`, `organizations`) issuer:
another tenant could present any e-mail address. Prefer invitations over **Open** registration and allowed
domains.

**Limits**: a user in more than 200 groups gets no groups claim (Entra "overage"); group sync then removes
their synchronised memberships. Use **Groups assigned to the application**.
Rotation: add a new client secret, switch, then delete the old one before it expires.
Docs: https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app

<a id="google-oidc"></a>
## Google

1. Google Cloud console → **Google Auth Platform** (formerly **APIs & Services** → **OAuth consent
   screen**): set up **Branding**, and under **Audience** choose **Internal** if only your Google Workspace
   users may sign in.
2. **Clients** → **Create client** → **Web application**; **Authorized redirect URIs**
   `https://backoffice.example.com/login/oauth2/code/oidc` → **Create**; copy the client id and secret.
3. Back Office: issuer `https://accounts.google.com`, `OIDC_PROVIDER_NAME=Google`,
   `OIDC_ACCOUNT_URL=https://myaccount.google.com`.
4. Google sends no groups claim: leave group sync off. With an **External** audience restrict sign-ups with
   invitations or **Allowed e-mail domains**.

**Limits**: Google has no end-session endpoint, so signing out ends only the Back Office session.
Docs: https://developers.google.com/identity/openid-connect/openid-connect

<a id="gitlab-oidc"></a>
## GitLab as identity provider

1. Create an application owned by a group (survives people leaving): **Group** → **Settings** →
   **Applications** → **Add new application** (instance-wide: **Admin** → **Applications**).
2. **Redirect URI** `https://backoffice.example.com/login/oauth2/code/oidc`, **Confidential** ticked, scopes
   **openid**, **profile**, **email** → **Save application**; copy **Application ID** and **Secret**.
3. Back Office: issuer `https://gitlab.example.com` (or `https://gitlab.com`), `OIDC_PROVIDER_NAME=GitLab`,
   `OIDC_GROUPS_CLAIM=groups_direct` for group sync (group full paths, for example `acme/platform`).
4. Rotation: **Renew secret** on the application, then update the Secret at once.

Docs: https://docs.gitlab.com/integration/openid_connect_provider/

<a id="local-accounts"></a>
## Local accounts

Local accounts work with or without an identity provider: for small installations, break-glass and
service accounts, or external collaborators. A local sign-in creates the same session as OIDC.

| Setting | Variable | Helm value |
|---|---|---|
| Local sign-in on/off (hard switch) | `BACKOFFICE_AUTH_LOCAL_ENABLED` | `auth.local.enabled` (`true`) |
| Secret key (base64 of 32 bytes) | `BACKOFFICE_AUTH_LOCAL_SECRET_KEY` | generated into `<release>-app` and kept, or `auth.local.secretKey` |
| First admin without a provider | `BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | `auth.local.bootstrapAdminEmail` / `bootstrapAdminPassword` |
| Set-password link lifetime | `BACKOFFICE_AUTH_LOCAL_RESET_LINK_VALIDITY` | `auth.local.resetLinkValidity` (`PT24H`) |

**The secret key** encrypts authenticator (TOTP) secrets and signs recovery codes and MFA steps. The API
does not start without it while local sign-in is enabled. Generate it with `openssl rand -base64 32`
(Git Bash on Windows ships `openssl`; PowerShell 7:
`[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`).
Changing it invalidates every enrolled authenticator and recovery code: users must enrol again
(**Reset MFA** on the user), so rotate only after a leak.

**First administrator without a provider**

1. Leave the issuer empty, set the bootstrap e-mail and password, start on an **empty** database.
2. Sign in on `/login` with e-mail and password; you must change the password first.
3. Remove both variables afterwards; they are ignored once any user exists.

**Adding people**: Back Office sends no e-mail. **Administration** → **Users** → **Invite user**, choose the
sign-in method **Local** (or **Both**); the panel shows a one-time **Set-password link** valid 24 hours.
Hand it over through a channel you trust. For a forgotten password use **Issue set-password link** on the
user. Password policy, **Require MFA for local accounts** and lockout are in **Platform settings**.
Details: [administration.md](administration.md#users), [account.md](account.md#mfa).
