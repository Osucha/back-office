# Logowanie (OIDC i konta lokalne)

Użytkownicy logują się do Back Office przez Twojego dostawcę tożsamości (OpenID Connect) albo kontem lokalnym
(e-mail, hasło, opcjonalnie aplikacja uwierzytelniająca). To Back Office decyduje, co każda osoba może robić;
dostawca mówi tylko, kim ona jest i, opcjonalnie, do jakich grup należy. Ten rozdział opisuje konfigurację
klienta OIDC dla Keycloaka, Microsoft Entra ID, Google i GitLaba oraz konfigurację kont lokalnych.

**Kogo to dotyczy:** administratora platformy; administratora dostawcy tożsamości.

<a id="oidc-overview"></a>
## Jak działa logowanie OIDC

API jest klientem poufnym (backend for frontend): realizuje przepływ authorization code z PKCE, przechowuje
tokeny po stronie serwera i przekazuje przeglądarce tylko ciasteczko sesji `BO_SESSION`.

| Wartość do zarejestrowania u dostawcy | Wartość |
|---|---|
| Typ klienta | Poufny klient webowy, przepływ authorization code, client secret |
| Redirect URI | `<public URL>/login/oauth2/code/oidc`, na przykład `https://backoffice.example.com/login/oauth2/code/oidc` |
| Post-logout redirect URI | dokładnie `BACKOFFICE_PUBLIC_URL`, na przykład `https://backoffice.example.com` |
| Zakresy (scopes) | `openid profile email` (+ zakres grup, jeśli dostawca go wymaga) |
| Używane claimy | `sub`, `iss`, `email`, `email_verified`, `name`, `preferred_username`, claim grup |

**Konfiguracja** (chart Helm `back-office`; każda zmienna akceptuje też krótki alias podany w nawiasie):

| Ustawienie | Zmienna | Wartość Helm | Domyślnie |
|---|---|---|---|
| URL issuera | `BACKOFFICE_AUTH_OIDC_ISSUER_URI` (`OIDC_ISSUER_URI`) | `auth.oidc.issuerUri` | puste = OIDC wyłączone |
| Client id | `BACKOFFICE_AUTH_OIDC_CLIENT_ID` (`OIDC_CLIENT_ID`) | `auth.oidc.clientId` | `back-office` |
| Client secret | `BACKOFFICE_AUTH_OIDC_CLIENT_SECRET` (`OIDC_CLIENT_SECRET`) | `auth.oidc.existingSecret` + `existingSecretKey` (`client-secret`) lub `auth.oidc.clientSecret` | wymagane |
| Zakresy | `BACKOFFICE_AUTH_OIDC_SCOPES` (`OIDC_SCOPES`) | `auth.oidc.scopes` | `openid,profile,email` |
| Nazwa na stronie logowania | `BACKOFFICE_AUTH_OIDC_PROVIDER_NAME` (`OIDC_PROVIDER_NAME`) | `api.extraEnv` | `Keycloak` |
| Link do strony konta u dostawcy | `BACKOFFICE_AUTH_OIDC_ACCOUNT_URL` (`OIDC_ACCOUNT_URL`) | `api.extraEnv` | wyliczany dla Keycloaka |
| Claim grup | `BACKOFFICE_AUTH_GROUPS_CLAIM` (`OIDC_GROUPS_CLAIM`) | `auth.groupsClaim` | `groups` |
| Domyślna wartość „synchronizuj grupy” | `BACKOFFICE_AUTH_SYNC_GROUPS` (`BACKOFFICE_SYNC_GROUPS`) | `api.extraEnv` | `false` |
| Rejestracja | `BACKOFFICE_AUTH_REGISTRATION` (`BACKOFFICE_REGISTRATION`) | `auth.registration` | `INVITE_ONLY` |
| Dozwolone domeny e-mail | `BACKOFFICE_AUTH_ALLOWED_EMAIL_DOMAINS` (`BACKOFFICE_ALLOWED_EMAIL_DOMAINS`) | `auth.allowedEmailDomains` | puste |
| Pierwsi administratorzy | `BACKOFFICE_AUTH_BOOTSTRAP_ADMINS` (`BACKOFFICE_BOOTSTRAP_ADMINS`) | `auth.bootstrapAdmins` | puste |
| Limit bezczynności / bezwzględny limit sesji | `BACKOFFICE_AUTH_SESSION_IDLE_TIMEOUT` / `_ABSOLUTE_TIMEOUT` | `auth.session.idleTimeout` / `absoluteTimeout` | `PT30M` / `PT12H` |

Rejestracja, domeny, synchronizacja grup i czas życia tokenów to tylko wartości domyślne: po zapisaniu
w **Administracja** (**Administration**) → **Ustawienia platformy** (**Platform settings**) wygrywają
zapisane wartości ([administration.md](administration.md#platform-settings)).

**Kto dostaje konto** przy pierwszym logowaniu: zaproszony użytkownik z tym samym adresem e-mail, adres
wymieniony wśród bootstrap admins (otrzymuje też `platform-admin`), każdy z dozwolonej domeny albo każdy, gdy
rejestracja jest **Otwarta** (**Open**). Następnie użytkownik zostaje powiązany z `iss` + `sub` dostawcy.

**Weryfikacja e-maila.** Pierwsze logowanie jest odrzucane (`email-not-verified`) tylko wtedy, gdy dostawca
wysyła `email_verified=false`. Gdy tego claimu **nie ma**, Back Office ufa claimowi `email` takiemu, jaki jest.
Ma to znaczenie dla Entra ID, który nie wysyła `email_verified`: zobacz [Microsoft Entra ID](#entra).

**Przetestuj**

1. Po zmianie wartości zrestartuj API; dokument discovery dostawcy jest odczytywany przy pierwszym logowaniu.
2. Otwórz `<public URL>/login`: przycisk ma etykietę **Kontynuuj przez** (**Continue with**) i nazwę dostawcy.
3. Zaloguj się jako bootstrap admin; **Administracja** → **Dziennik audytu** (**Audit log**) pokazuje
   `LOGIN_SUCCESS` albo `LOGIN_FAILURE` z przyczyną ([troubleshooting.md](troubleshooting.md#login)).

**Rotacja client secretu**: utwórz drugi sekret u dostawcy, jeśli pozwala na dwa (Entra, Google), albo
wygeneruj go ponownie (Keycloak, GitLab); zaktualizuj Secret wskazany w `auth.oidc.existingSecret`;
zrestartuj `deploy/back-office-api` i `deploy/back-office-worker`. Istniejące sesje pozostają ważne; client
secretu używają tylko nowe logowania.

<a id="keycloak"></a>
## Keycloak

Chart Helm nie wdraża Keycloaka; uruchom własny (pełnym przykładem jest realm
`deploy/keycloak/realm-back-office.json`, używany przez lokalne środowisko docker-compose) i zarejestruj klienta:

1. Konsola administracyjna → wybierz realm → **Clients** → **Create client**: typ **OpenID Connect**,
   **Client ID** `back-office` → **Next**.
2. **Capability config**: **Client authentication** On, **Standard flow** On, **Direct access grants** Off
   → **Next**.
3. **Login settings**: **Valid redirect URIs** `https://backoffice.example.com/login/oauth2/code/oidc`,
   **Valid post logout redirect URIs** `https://backoffice.example.com`, **Web origins** `+` → **Save**.
4. Zakładka **Advanced** → **Proof Key for Code Exchange Code Challenge Method** `S256` (Back Office zawsze
   wysyła PKCE).
5. Zakładka **Credentials** → skopiuj **Client secret**.
6. Grupy (opcjonalnie): **Client scopes** → `back-office-dedicated` → **Add mapper** → **By configuration** →
   **Group Membership**: **Token Claim Name** `groups`, **Full group path** Off, **Add to ID token** On,
   **Add to userinfo** On.
7. Użytkownicy muszą mieć zweryfikowany e-mail (**Users** → użytkownik → **Email verified** On).
8. Issuer: `https://sso.example.com/realms/<realm>`. Musi być osiągalny z podów API **i** równy `iss`
   w tokenach (ustawienia hostname Keycloaka, `KC_HOSTNAME`).

Rotacja: **Credentials** → **Regenerate**, a następnie natychmiast zaktualizuj Secret (stary sekret przestaje
działać).
Dokumentacja: https://www.keycloak.org/docs/latest/server_admin/#_oidc_clients

<a id="entra"></a>
## Microsoft Entra ID

1. Microsoft Entra admin center (entra.microsoft.com) → **Identity** → **Applications** →
   **App registrations** → **New registration**. Potrzebujesz roli Application Developer lub wyższej.
2. **Name** `Back Office`; **Supported account types**: **Accounts in this organizational directory only
   (Single tenant)**; **Redirect URI**: platforma **Web**, `https://backoffice.example.com/login/oauth2/code/oidc`
   → **Register**.
3. **Authentication** → w sekcji **Web** dodaj `https://backoffice.example.com` jako drugi redirect URI
   (używany jako cel po wylogowaniu).
4. **Certificates & secrets** → **Client secrets** → **New client secret**, ważność do 24 miesięcy →
   skopiuj **Value** (nie Secret ID).
5. **Token configuration** → **Add optional claim** → **ID** → **email** (zaakceptuj uprawnienie `email`
   Microsoft Graph). Użytkownicy muszą mieć wypełniony atrybut **Email** (mail).
6. Grupy (opcjonalnie): **Token configuration** → **Add groups claim** → **Security groups** (lub **Groups
   assigned to the application** w dużych katalogach), ID token: **Group ID**. Entra wysyła **object id**
   grup: używaj tych GUID-ów jako grupy zewnętrznej w grupach Back Office.
7. **API permissions**: delegowane uprawnienia Microsoft Graph `openid`, `profile`, `email` (oraz domyślne
   `User.Read`); udziel zgody administratora (admin consent), jeśli Twój tenant tego wymaga.
8. Back Office: issuer `https://login.microsoftonline.com/<tenant id>/v2.0`, client id = **Application
   (client) ID**, `OIDC_PROVIDER_NAME=Microsoft`, `OIDC_ACCOUNT_URL=https://myaccount.microsoft.com`.

**Uwaga dotycząca bezpieczeństwa.** Entra nie wysyła `email_verified`, więc Back Office ufa claimowi `email`,
gdy wiąże zaproszonego użytkownika przy pierwszym logowaniu. Przy rejestracji single-tenant claim pochodzi
z Twojego własnego katalogu, który kontrolują administratorzy. Nigdy nie używaj issuera multi-tenant
(`common`, `organizations`): inny tenant mógłby przedstawić dowolny adres e-mail. Preferuj zaproszenia zamiast
rejestracji **Otwartej** i dozwolonych domen.

**Ograniczenia**: użytkownik należący do ponad 200 grup nie dostaje claimu grup („overage” w Entra);
synchronizacja grup usuwa wtedy jego zsynchronizowane członkostwa. Używaj **Groups assigned to the
application**.
Rotacja: dodaj nowy client secret, przełącz się na niego, a następnie usuń stary, zanim wygaśnie.
Dokumentacja: https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app

<a id="google-oidc"></a>
## Google

1. Konsola Google Cloud → **Google Auth Platform** (dawniej **APIs & Services** → **OAuth consent
   screen**): skonfiguruj **Branding**, a w **Audience** wybierz **Internal**, jeśli logować się mogą tylko
   użytkownicy Twojego Google Workspace.
2. **Clients** → **Create client** → **Web application**; **Authorized redirect URIs**
   `https://backoffice.example.com/login/oauth2/code/oidc` → **Create**; skopiuj client id i secret.
3. Back Office: issuer `https://accounts.google.com`, `OIDC_PROVIDER_NAME=Google`,
   `OIDC_ACCOUNT_URL=https://myaccount.google.com`.
4. Google nie wysyła claimu grup: pozostaw synchronizację grup wyłączoną. Przy audience **External** ograniczaj
   rejestracje zaproszeniami lub **Dozwolonymi domenami e-mail** (**Allowed e-mail domains**).

**Ograniczenia**: Google nie ma endpointu end-session, więc wylogowanie kończy tylko sesję Back Office.
Dokumentacja: https://developers.google.com/identity/openid-connect/openid-connect

<a id="gitlab-oidc"></a>
## GitLab jako dostawca tożsamości

1. Utwórz aplikację należącą do grupy (przetrwa odejście pracowników): **Group** → **Settings** →
   **Applications** → **Add new application** (dla całej instancji: **Admin** → **Applications**).
2. **Redirect URI** `https://backoffice.example.com/login/oauth2/code/oidc`, zaznaczone **Confidential**,
   zakresy **openid**, **profile**, **email** → **Save application**; skopiuj **Application ID** i **Secret**.
3. Back Office: issuer `https://gitlab.example.com` (lub `https://gitlab.com`), `OIDC_PROVIDER_NAME=GitLab`,
   `OIDC_GROUPS_CLAIM=groups_direct` do synchronizacji grup (pełne ścieżki grup, na przykład `acme/platform`).
4. Rotacja: **Renew secret** w aplikacji, a następnie natychmiast zaktualizuj Secret.

Dokumentacja: https://docs.gitlab.com/integration/openid_connect_provider/

<a id="local-accounts"></a>
## Konta lokalne

Konta lokalne działają z dostawcą tożsamości lub bez niego: w małych instalacjach, jako konta awaryjne
(break-glass) i usługowe albo dla zewnętrznych współpracowników. Logowanie lokalne tworzy taką samą sesję
jak OIDC.

| Ustawienie | Zmienna | Wartość Helm |
|---|---|---|
| Logowanie lokalne wł./wył. (twardy przełącznik) | `BACKOFFICE_AUTH_LOCAL_ENABLED` | `auth.local.enabled` (`true`) |
| Klucz tajny (base64 z 32 bajtów) | `BACKOFFICE_AUTH_LOCAL_SECRET_KEY` | generowany do `<release>-app` i zachowywany albo `auth.local.secretKey` |
| Pierwszy administrator bez dostawcy | `BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | `auth.local.bootstrapAdminEmail` / `bootstrapAdminPassword` |
| Czas ważności linku do ustawienia hasła | `BACKOFFICE_AUTH_LOCAL_RESET_LINK_VALIDITY` | `auth.local.resetLinkValidity` (`PT24H`) |

**Klucz tajny** szyfruje sekrety aplikacji uwierzytelniających (TOTP) oraz podpisuje kody odzyskiwania
i kroki MFA. API nie uruchomi się bez niego, dopóki logowanie lokalne jest włączone. Wygeneruj go poleceniem
`openssl rand -base64 32` (Git Bash w Windows zawiera `openssl`; PowerShell 7:
`[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`).
Jego zmiana unieważnia każdą zarejestrowaną aplikację uwierzytelniającą i każdy kod odzyskiwania: użytkownicy
muszą zarejestrować się ponownie (**Resetuj MFA** (**Reset MFA**) przy użytkowniku), więc rotuj go tylko po
wycieku.

**Pierwszy administrator bez dostawcy**

1. Pozostaw issuer pusty, ustaw bootstrapowy e-mail i hasło, uruchom na **pustej** bazie danych.
2. Zaloguj się na `/login` e-mailem i hasłem; najpierw musisz zmienić hasło.
3. Następnie usuń obie zmienne; są ignorowane, gdy istnieje już jakikolwiek użytkownik.

**Dodawanie osób**: Back Office nie wysyła e-maili. **Administracja** → **Użytkownicy** (**Users**) →
**Zaproś użytkownika** (**Invite user**), wybierz metodę logowania **Lokalne** (**Local**) (lub **Lokalne
i Keycloak** (**Local and Keycloak**), z nazwą Twojego dostawcy); panel pokaże jednorazowy **Link do ustawienia hasła** (**Set-password link**) ważny 24 godziny.
Przekaż go zaufanym kanałem. Przy zapomnianym haśle użyj **Wydaj link do ustawienia hasła**
(**Issue set-password link**) przy użytkowniku. Polityka haseł, **Wymagaj MFA dla kont lokalnych**
(**Require MFA for local accounts**) i blokada konta znajdują się w **Ustawieniach platformy**.
Szczegóły: [administration.md](administration.md#users), [account.md](account.md#mfa).
