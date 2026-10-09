# Administracja

Back Office działa według zasady deny-by-default: nikt nie może nic zrobić, dopóki nie pozwoli na to rola lub
nadanie uprawnień. Administratorzy zapraszają osoby, organizują je w grupy, decydują, co każda grupa lub osoba
może robić na platformie, dostrajają reguły logowania i budżety, czytają dziennik audytu i obserwują kondycję
platformy. Wszystko to znajduje się w nawigacji w sekcji **Administracja** (**Administration**); każda strona
pojawia się tylko u osób z odpowiednim uprawnieniem.

**Kogo to dotyczy:** administratora platformy, audytora.

| Strona | Ścieżka | Wymaga |
|---|---|---|
| **Użytkownicy** (**Users**) | `/admin/users` | `admin.users.view` (podgląd), `admin.users.manage` (zmiany) |
| **Grupy** (**Groups**) | `/admin/groups` | `admin.users.view`, `admin.users.manage` |
| **Role** (**Roles**), **Nadania** (**Grants**) | `/admin/roles`, `/admin/grants` | `admin.users.view` lub `admin.roles.manage` (podgląd), `admin.roles.manage` (zmiany) |
| **Ustawienia platformy** (**Platform settings**) | `/admin/settings` | `admin.settings.manage` |
| **Połączenia** (**Connections**) | `/admin/connections` | `integrations.view` (podgląd), `admin.connections.manage` (zmiany) ([Połączenia](#connections)) |
| **Flota runnerów** (**Runner fleet**) | `/admin/fleet` | `fleet.view`, `fleet.manage` ([runner-fleet.md](runner-fleet.md#pools)) |
| **Operacje** (**Operations**) | `/admin/operations` | `admin.settings.manage` |
| **Dziennik audytu** (**Audit log**) | `/admin/audit` | `admin.audit.view` |

Pierwszy administrator pochodzi z `BACKOFFICE_AUTH_BOOTSTRAP_ADMINS` (OIDC) lub jest lokalnym bootstrapowym
administratorem ([integrations-identity.md](integrations-identity.md#local-accounts)). Utrzymuj co najmniej
dwóch administratorów platformy; ostatniego aktywnego nie można ani dezaktywować, ani pozbawić roli.

<a id="users"></a>
## Użytkownicy

**Zaproś osobę**

1. **Użytkownicy** → **Zaproś użytkownika** (**Invite user**).
2. **E-mail** (klucz dopasowania przy pierwszym logowaniu), opcjonalnie **Wyświetlana nazwa**
   (**Display name**), **Grupy** (**Groups**), **Role platformowe** (**Platform roles**) (wymaga też
   `admin.roles.manage`) i **Członkostwa w projektach** (**Project memberships**) (wymaga
   `project.members.manage` w każdym projekcie).
3. Wybierz metodę logowania: dostawca tożsamości, **Lokalne** (**Local**) lub **Lokalne i Keycloak** (**Local and Keycloak**; zamiast Keycloak pojawia się nazwa skonfigurowanego
   dostawcy).
4. Back Office **nie wysyła e-maili**. Skopiuj z panelu **Link logowania** (**Sign-in link**) (OIDC) lub
   jednorazowy **Link do ustawienia hasła** (**Set-password link**) (konto lokalne, ważny 24 godziny) i wyślij
   go samodzielnie.

Użytkownik ma stan `INVITED`, dopóki pierwsze logowanie nie powiąże konta (OIDC: issuer + subject) i nie
zmieni stanu na `ACTIVE`. Zaproszenie istniejącego adresu e-mail jest odrzucane.

**Na stronie użytkownika**

| Akcja | Skutek |
|---|---|
| **Dostęp** (**Access**) | grupy, role platformowe, członkostwa w projektach i **Uprawnienia efektywne** (**Effective permissions**) (System / Grupy / Użytkownik / Efektywne) |
| **Metoda logowania** (**Sign-in method**) | przełączanie między dostawcą tożsamości, kontem lokalnym i obiema metodami; usunięcie metody kończy sesje użytkownika |
| **Wydaj link do ustawienia hasła** (**Issue set-password link**) | nowy jednorazowy link ważny 24 godziny; bieżące hasło przestaje działać, a sesje się kończą |
| **Resetuj MFA** (**Reset MFA**) | usuwa aplikację uwierzytelniającą i kody odzyskiwania (najpierw zweryfikuj osobę innym kanałem) |
| **Odblokuj konto** (**Unlock account**) | zdejmuje blokadę po nieudanych logowaniach |
| **Zakończ wszystkie sesje** (**End all sessions**) | wylogowuje użytkownika wszędzie |
| **Dezaktywuj użytkownika** (**Deactivate user**) | kończy sesje, unieważnia osobiste tokeny, blokuje logowanie; nic nie jest usuwane; **Aktywuj** (**Activate**) przywraca dostęp |

Nie możesz dezaktywować samego siebie.

<a id="groups"></a>
## Grupy

Grupa przenosi role platformowe i członkostwa w projektach na wszystkich swoich członków.

1. **Grupy** → nowa grupa; **Informacje** (**About**): nazwa, opis.
2. **Członkowie i role** (**Members and roles**): dodaj użytkowników i role platformowe.
3. **Dostawca tożsamości** (**Identity provider**) (opcjonalnie): nazwa **grupy zewnętrznej**
   (**external group**). Gdy włączone jest **Synchronizuj grupy z** (**Synchronise groups from**) dostawcy
   (**Ustawienia platformy** → **Rejestracja** (**Registration**)), każde logowanie ustawia członkostwo
   użytkownika w każdej grupie z grupą zewnętrzną na podstawie claimu grup dostawcy (`architects`
   i `/architects` są dopasowywane tak samo). Grupy bez grupy zewnętrznej nigdy nie są zmieniane.
4. Dodaj grupę do projektów na zakładce **Członkowie** (**Members**) projektu ([projects.md](projects.md#members)).

Grupy, która nadal jest członkiem projektu, nie można usunąć: najpierw usuń członkostwa.

<a id="roles-grants"></a>
## Role i nadania

**Rola** to nazwany zestaw uprawnień z zakresem. Role wbudowane są tworzone automatycznie, można je edytować,
ale nie można ich usunąć:

| Klucz | Nazwa | Zakres | Uprawnienia |
|---|---|---|---|
| `platform-admin` | **Administrator platformy** (**Platform administrator**) | Platforma | wszystkie uprawnienia platformowe, w tym `admin.projects.all` (każde uprawnienie projektowe wszędzie) i `admin.connections.manage` |
| `member` (nadawana wszystkim) | **Członek** (**Member**) | Platforma | `library.view`, `models.view`, `evaluation.view`, `tokens.personal`, `integrations.view` |
| `project-manager` | **Kierownik projektów** (**Project manager**) | Platforma | `member` + `projects.create`, `usage.view` |
| `evaluator` | **Ewaluator** (**Evaluator**) | Platforma | `member` + `evaluation.run` |
| `library-curator` | **Kurator biblioteki** (**Library curator**) | Platforma | `member` + `library.manage` |
| `auditor` | **Audytor** (**Auditor**) | Platforma | `admin.users.view`, `admin.audit.view`, `usage.view`, `library.view`, `models.view`, `evaluation.view` |
| `owner` | **Właściciel** (**Owner**) | Projekt | każde uprawnienie projektowe oprócz `ci.*`, łącznie z `run.assign` i `project.connections.manage` |
| `maintainer` | **Maintainer** | Projekt | `owner` bez `project.members.manage` i `project.connections.manage` (zachowuje `run.assign`) |
| `developer` | **Deweloper** (**Developer**) | Projekt | `project.view`, `workflow.edit`, `run.start`, `run.cancel`, `run.signal`, `project.usage.view` |
| `approver` | **Zatwierdzający** (**Approver**) | Projekt | `project.view`, `run.approve`, `project.usage.view` |
| `viewer` | **Obserwator** (**Viewer**) | Projekt | `project.view` |

Inne uprawnienia platformowe, które należy nadawać z rozwagą: `models.manage`, `usage.view`, `fleet.view`,
`fleet.manage`, `admin.settings.manage`, `admin.roles.manage`, `admin.users.manage`,
`admin.connections.manage`. **Role** → nowa rola
tworzy własne zestawy; roli, która jest nadal przypisana, nie można usunąć.

Interfejs pokazuje nazwy i opisy ról wbudowanych oraz opisy uprawnień w języku interfejsu (na przykład
`admin.audit.view`: „Odczyt dziennika audytu”); klucze uprawnień i ról pozostają bez zmian. Jeśli
administrator zmieni nazwę lub opis roli wbudowanej, wszędzie widać jego tekst; role własne zachowują
nazwy nadane przy tworzeniu.

**Nadania** (zakres platformy) ustawia się na trzech poziomach: **System** (każdy zalogowany; domyślna rola
`member`), **Grupa** (**Group**), **Użytkownik** (**User**). Każdy poziom zawiera role oraz pojedyncze
**Zezwól** (**Allow**) / **Odmów** (**Deny**) dla poszczególnych uprawnień.

Jak rozstrzygane jest uprawnienie:

1. Wygrywa najbliższy poziom, który cokolwiek określa: Użytkownik przed Grupą, Grupa przed Systemem.
2. W obrębie poziomu Odmów wygrywa z Zezwól, także między wszystkimi grupami użytkownika.
3. Rola liczy się jako Zezwól dla każdego ze swoich uprawnień.
4. Brak jakiegokolwiek ustawienia oznacza odmowę.

Uprawnienia projektowe pochodzą z członkostw w projektach (użytkownicy lub grupy z rolą projektową); twórca
projektu zostaje jego `owner`, a ostatniego właściciela nie można usunąć. Wynik sprawdzisz w **Uprawnieniach
efektywnych** użytkownika. Każda zmiana trafia do dziennika audytu (`GRANT_CHANGE`, `ROLE_ASSIGN`,
`ROLE_UNASSIGN`) i obowiązuje od następnego żądania użytkownika.

<a id="project-managers"></a>
## Kierownicy projektów

Użytkownicy nie tworzą każdy własnego projektu. **Kierownicy projektów** tworzą projekty i decydują, kto
wykonuje który krok; pozostali pracują w projektach, do których zostali dodani.

- Wbudowana rola platformowa **Kierownik projektów** (**Project manager**, `project-manager`) ma
  `projects.create` (oraz `usage.view`). Domyślna rola **Członek** (**Member**) go nie ma, więc zwykli
  użytkownicy widzą „Projekty tworzą kierownicy projektów” zamiast przycisku **Utwórz projekt**.
  Administratorzy platformy zawsze mogą tworzyć projekty.
- Nadaj rolę na stronie **Nadania** (**Grants**): wybierz użytkownika albo, lepiej, grupę, na przykład
  „Liderzy zespołów”, i dodaj rolę **Kierownik projektów**. Wynik sprawdzisz w **Uprawnieniach
  efektywnych** (**Effective permissions**) użytkownika.
- Kierownik projektów, który tworzy projekt, zostaje jego **Właścicielem**, dodaje członków z rolami
  projektowymi ([Członkowie i role projektowe](projects.md#members)), ustala w workflowie, kto rozstrzyga
  każdą bramkę ([Ustawienia bramki](workflows.md#gates-config)), a razem z **Maintainerami** może
  przypisać czekający krok innym osobom przyciskiem **Przypisz…** (**Assign…**; uprawnienie projektowe
  `run.assign`, [Przypisywanie kroku](your-turn.md#assign-steps)).

**Aktualizacja istniejącej instalacji**: migracja usuwa `projects.create` z zapisanych ról wbudowanych
`member`, `evaluator` i `library-curator` oraz dodaje `run.assign` do `owner` i `maintainer`; rola
**Kierownik projektów** powstaje przy starcie. Każda zmiana jest w dzienniku audytu (`ROLE_UPDATE`,
`ROLE_CREATE` przez `system`). Po aktualizacji nadaj rolę **Kierownik projektów** osobom, które mają
tworzyć projekty; lista konfiguracji na Stronie głównej przypomina o tym administratorom („Wyznacz
kierownika projektów”).

<a id="connections"></a>
## Połączenia

**Połączenia** (**Connections**, `/admin/connections`) przechowują poświadczenia systemów zewnętrznych dla
całej platformy; obecnie są to konta Jiry. Strona jest w grupie **Administracja** na pasku nawigacji
i w palecie poleceń (`Ctrl K`, **Połączenia**) dla posiadaczy `admin.connections.manage`. Projekt używa
połączenia platformy, gdy jego właściciele wybiorą je jako połączenie systemowe projektu albo dodadzą je do
listy dla jego kroków ([Połączenie systemowe](integrations-jira.md#jira-system-connection)); projekty bez
połączenia systemowego wybierają spośród wszystkich połączeń projektu i platformy to z najmniejszą liczbą
dodatkowych możliwości. Właściciele projektów dodają połączenia projektu na karcie **Połączenia** projektu
([Połączenia projektu](projects.md#project-connections)).

**Połączenia osobiste** (pod listą, dla posiadaczy `admin.connections.manage`): własne połączenia Jiry każdej
osoby z **Moje konto** → **Połączenia**, pokazywane tylko jako metadane (właściciel, nazwa, witryna,
możliwości, rodzaj tokenu, wygaśnięcie, ostatnie sprawdzenie). Administratorzy nie widzą ich e-maila ani tokenu
i nie mogą ich używać ani zmieniać; są używane tylko przy zatwierdzeniu lub rozpoczęciu uruchomienia przez ich
właściciela ([Połączenia osobiste](integrations-jira.md#jira-personal-connections)).

**Ustawienia platformy** → **Funkcje** → **Zezwalaj na Spec Kit** (domyślnie włączone) pozwala projektom
włączać Spec Kit; po wyłączeniu żaden projekt nie może uruchamiać kroków Spec Kit
([Spec Kit](projects.md#spec-kit)).

- **Podgląd**: `integrations.view` (każdy członek). **Dodaj połączenie** (**Add connection**), edycja,
  **Zastąp token** (**Replace token**), **Testuj** (**Test**), **Testuj przed zapisem** (**Test before
  saving**), usuwanie: `admin.connections.manage` (wbudowana rola `platform-admin`).
- Każde połączenie deklaruje **możliwości** (**Odczyt zgłoszeń**, **Komentowanie zgłoszeń**,
  **Przenoszenie zgłoszeń**) i opcjonalnie **Dozwolone projekty** (**Allowed projects**); krok workflowu
  używa połączenia z najmniejszą liczbą możliwości, które nadal pokrywają jego potrzeby. Utwórz połączenie
  do odczytu i połączenie do zapisu oparte na dwóch kontach Jiry z dokładnie tymi uprawnieniami:
  [Zasada najmniejszych uprawnień](integrations-jira.md#jira-least-privilege),
  [Połączenia](integrations-jira.md#jira-connections). Połączenie platformy do odczytu obsługuje też
  [monitor zgłoszeń Jira](integrations-jira.md#jira-intake) każdego projektu.
- Tokeny są tylko do zapisu i szyfrowane kluczem `BACKOFFICE_SECRETS_KEY`; bez klucza dodanie połączenia
  jest odrzucane ([Skonfiguruj Back Office](integrations-jira.md#jira-config)). Zrób kopię zapasową klucza.
- **Testuj przed zapisem** w oknach dodawania i edycji sprawdza konto w Jirze, niczego nie zapisując;
  **Testuj** sprawdza zapisane połączenie. Oba pokazują brakujące i nadmiarowe uprawnienia Jiry
  ([Przetestuj połączenie](integrations-jira.md#jira-test)).
- Lista oznacza tokeny, które **wkrótce wygasną** (30 dni) lub **wygasły**. Od 14 dni przed wygaśnięciem
  administratorzy dostają kartę w **Twoja kolej**, a raz na każdą datę wygaśnięcia także powiadomienie na
  pulpicie **Wygasające połączenie** (domyślnie włączone dla administratorów); zob.
  [Wygaśnięcie i rotacja tokenu](integrations-jira.md#jira-token-expiry).
- Dopóki ustawione są `JIRA_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN`, pojawia się tu połączenie tylko do
  odczytu **Jira (environment variables)** (poświadczenie ze zmiennych środowiskowych) ze wszystkimi
  możliwościami i informacją o migracji ([Skonfiguruj Back Office](integrations-jira.md#jira-config)).

**Aktualizacja**: migracja dodaje `project.connections.manage` do zapisanej roli wbudowanej `owner`
(`ROLE_UPDATE` przez `system`); `maintainer` celowo go nie dostaje.

<a id="platform-settings"></a>
## Ustawienia platformy

Zapisane ustawienia mają pierwszeństwo przed domyślnymi ustawieniami serwera ze zmiennych środowiskowych.

| Sekcja | Ustawienie | Domyślnie |
|---|---|---|
| **Logowanie** (**Sign-in**) | **Zezwalaj na logowanie lokalne** (**Allow local sign-in**) (nie można wyłączyć, dopóki nie skonfigurowano dostawcy tożsamości) | wł. |
| | **Wymagaj MFA dla kont lokalnych** (**Require MFA for local accounts**) | wył. |
| **Polityka haseł** (**Password policy**) | minimalna długość (8–128), sprawdzanie popularnych haseł i e-maila | 12, oba wł. |
| **Blokada konta** (**Lockout**) | liczba nieudanych prób przed blokadą, czas pierwszej blokady (podwaja się, maks. 24 h) | 5, 15 min |
| **Rejestracja** (**Registration**) | **Tylko zaproszenia** (**Invite only**) lub **Otwarta** (**Open**); **Dozwolone domeny e-mail** (**Allowed e-mail domains**); **Synchronizuj grupy z** dostawcy | tylko zaproszenia |
| **Osobiste tokeny dostępu** (**Personal access tokens**) | **Maksymalny czas życia (dni)** (**Maximum lifetime (days)**) (1–365) | 90 |
| **Budżet** (**Budget**) | miesięczny limit platformy, próg alertu, twarda blokada | brak |

Budżet platformy obejmuje każdy koszt LLM w miesiącu (wywołania agentów, kroki LLM workflowów, przeglądy,
benchmarki); przy twardej blokadzie brama odrzuca kolejne wywołania agentów
([costs-insights.md](costs-insights.md#budgets)). Klucze LLM i magazyn to konfiguracja serwera;
**Ustawienia** (**Settings**) (`/settings`) pokazują ich stan i pozwalają je przetestować. Poświadczenia
Jiry to [Połączenia](#connections).

<a id="audit"></a>
## Dziennik audytu

**Dziennik audytu** pokazuje, kto co zrobił, od najnowszych wpisów; wpisy są zapisywane w tej samej
transakcji co zmiana:

- zmiany dostępu: `USER_INVITE`, `USER_DEACTIVATE`, `GROUP_*`, `ROLE_*`, `GRANT_CHANGE`, `MEMBER_*`,
  `PAT_CREATE`/`PAT_REVOKE`, `CI_TOKEN_CREATE`/`CI_TOKEN_REVOKE`, `SETTINGS_UPDATE`;
- połączenia: `CONNECTION_CREATE`, `CONNECTION_UPDATE`, `CONNECTION_ROTATE`, `CONNECTION_DELETE`,
  `CONNECTION_VERIFY`, `CONNECTION_TEST` (test przed zapisem), `CONNECTION_EXPIRY_REMINDER`,
  `CONNECTION_SECRET_REWRAP` (nigdy z tokenem);
- Jira: `JIRA_INTAKE_UPDATE`, `JIRA_INTAKE_POLL_NOW` (monitor zgłoszeń), `RUN_WRITE_DECIDED`
  i `EXTERNAL_WRITE` (zatwierdzenie przed zapisem, ze skrótem SHA-256 zapisanego tekstu);
- logowanie: `LOGIN_SUCCESS`, `LOGIN_FAILURE` (z `reason`), `LOGOUT`, `SESSION_EXPIRED`, zdarzenia kont
  lokalnych (`ACCOUNT_LOCKED`, `PASSWORD_RESET_ISSUE`, `MFA_RESET`, ...), nigdy z sekretem;
- odrzucone żądania: `ACCESS_DENIED` z uprawnieniem i URL-em;
- praca: `WORKFLOW_UPDATE`, `RUN_START`, `RUN_GATE_DECIDED`, `ATTESTATION_CREATE`, ...;
- przeszukiwanie samego dziennika (`AUDIT_VIEW`).

Filtruj po czasie, wykonawcy, operacji, encji, projekcie, wyniku i correlation id. Każda odpowiedź API
zawiera `X-Correlation-Id`: poproś o niego użytkowników i wyszukuj po nim. Partycje audytu są domyślnie
przechowywane online przez 24 miesiące (`BACKOFFICE_RETENTION_AUDIT_RETENTION_MONTHS`); wyeksportuj starsze
partycje, zanim włączysz ich usuwanie.

<a id="operations"></a>
## Operacje

**Operacje** pokazują kondycję samej platformy:

| Karta | Pokazuje | Działaj, gdy |
|---|---|---|
| **Repliki** (**Replicas**) | każdą instancję API/workera, stan, role, wersję, ostatni kontakt; subskrybentów aktualizacji na żywo | instancja jest nieaktualna (stale) lub wersje różnią się po aktualizacji |
| **Elementy pracy** (**Work items**) | kolejkę za każdym krokiem uruchomienia: gotowe, przydzielone, wiek najstarszego gotowego; zadania agentów według stanu | najstarszy gotowy element się starzeje (brakuje workerów lub są przeciążone) |
| **Martwe elementy** (**Dead items**) | kroki, które wyczerpały wszystkie ponowienia, z rodzajem, ostatnim błędem i liczbą prób | usuń przyczynę, a następnie **Ponów** (**Retry**) |

Runbooki dla poważniejszych problemów: `docs/runbooks/work-queue.md`, `docs/runbooks/scaling.md`,
`docs/runbooks/upgrade.md`; objawy w [troubleshooting.md](troubleshooting.md#runs).
