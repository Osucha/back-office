# Polish UI glossary

The terms the Polish UI uses (ADR 0026; conventions in `docs/guides/frontend-development.md`,
"Internationalisation"). Everyone who translates a feature area follows this table, so the same
English word is the same Polish word on every screen and in the manual (`docs/manual/pl`, which the
UI labels must match: the manual writes "**Uruchom** (**Start run**)").

## Style

- **Tone**: concise, professional, friendly; like good Polish SaaS products. Address the user as
  "Ty" (capitalised in direct address: "Twoja kolej", "Twoje konto", "Poproś administratora…").
- **Buttons and menu items** are imperatives: "Zapisz", "Uruchom", "Zatwierdź", "Odrzuć",
  "Utwórz projekt", "Dodaj runner", "Spróbuj ponownie". Never infinitives ("Zapisać").
- **Titles and labels**: sentence case, as in English ("Ustawienia platformy", not "Ustawienia
  Platformy"). Field labels are nouns ("Nazwa", "Klucz projektu Jira").
- **Gender-neutral** wording for the user: "Zalogowano Cię jako…", "Nastąpi wylogowanie…",
  "Uruchomienie rozpoczęte przez Ciebie"; avoid "zalogowany/zalogowana", "zapisałem".
- **Status chips** (the baton words) stay lower case, as in English: "agent pracuje", "czeka na CI",
  "Twoja kolej", "gotowe", "błąd".
- **Numbers, dates, durations** come from the formatters (`intlLocale()`, Angular pipes); never write
  them into a message. Costs keep the `$` prefix with a decimal comma ("$0,042"); durations use
  "ms / s / min / h / d" with a space ("4 min 12 s").
- **Quotes** „…” in Polish text; the ellipsis character "…" in "Uruchom…", "Szukaj lub przejdź do…".
- **Plurals** always as ICU with `one`, `few`, `many`, `other` (other = fractions, usually the
  genitive singular form): `{count, plural, one {# workflow} few {# workflowy} many {# workflowów}
  other {# workflowu}}`. 1 workflow · 2–4, 22–24 workflowy · 0, 5–21, 25… workflowów.
- Keep identifiers, permission keys, HTTP codes, file names, keyboard keys and product names
  unchanged (`project.manage`, `.gitlab-ci.yml`, `Ctrl K`, Jira, GitLab, Keycloak, Claude Code).

## Terms

| English | Polish | Notes |
|---|---|---|
| Back Office | Back Office | product name, never translated |
| abstract (template) | abstrakcyjny | "Szablony abstrakcyjne można tylko rozszerzać" |
| accountable (role) | odpowiedzialny | "Odpowiedzialny: owner" |
| agent | agent (pl. agenci) | "agent pracuje" |
| agent job | zadanie agenta (pl. zadania agentów) | runner fleet |
| alert (budget) | alert | "alert budżetu", "Alert przy (%)" |
| allowed projects (connection) | dozwolone projekty | field label "Dozwolone projekty" (Jira project keys a connection may touch); not the network allowlist |
| allowlist (network) | lista dozwolonych hostów | "Dozwolone hosty" |
| API key set / configured | klucz API ustawiony / klucz API skonfigurowany | "Klucz API ustawiony" in Settings → LLM providers, "Klucz API skonfigurowany" in the model drawer; "brak klucza API" (no API key) |
| API token (Jira) | token API | connection field "Token API"; "klasyczny token API" (classic API token); "token API z zakresami" (scoped API token, ADR 0035), "Zakresy tokenu", "Identyfikator chmury witryny" (cloud id) |
| act as (Jira node) | działaj jako | designer "Działaj jako": "Połączenie systemowe projektu", "Wskazane połączenie", "Osoba, która zatwierdza zapis" / "Osoba, która rozpoczyna uruchomienie" (ADR 0035) |
| approval before write (Jira) | zatwierdzenie przed zapisem | ADR 0033; designer section "Zatwierdzenie przed zapisem w Jirze" (Approval before writing to Jira); values **Wymagane** / **Niewymagane** (Required / Not required); "Kto zatwierdza" (Who approves) |
| approve / approved | zatwierdź / zatwierdzone | gate decision |
| approver | zatwierdzający | "u zatwierdzającego" (baton) |
| artefact | artefakt | |
| assign (a step) / assigned to / assignee | przypisz / przypisane do / osoba przypisana | run-time assignment of a waiting gate (ADR 0028): "Przypisz…", "Przypisane do: Ann", "Przypisane do Ciebie przez …"; "Kto rozstrzyga ten krok" (Who decides this step) |
| attempt | próba | "próba 2" |
| attestation | atestacja | provenance |
| audit log | dziennik audytu | |
| automatic (connection pick) | automatycznie | designer picker "Automatycznie (zasada najmniejszych uprawnień)" = no pinned connection |
| baton | pałeczka | only where the UI says baton; the holder = "posiadacz pałeczki" |
| back-off (polling) | wstrzymanie (ponowienie po przerwie) | intake watcher after Jira errors: the poll interval doubles up to 1 h; "Błędy i wstrzymanie" |
| baseline (intake watcher) | punkt startowy | the first poll only sets the starting point; the backlog never starts runs |
| bearer token | token typu Bearer | CI tokens |
| benchmark | benchmark (pl. benchmarki) | |
| blocked (holder) | zablokowane | legend; as a chip use "błąd" / "odrzucone" |
| blocking / advisory (CI mode) | blokujący / doradczy | |
| bottleneck | wąskie gardło | insights |
| branch (decision) | gałąź | git branch also "gałąź" |
| budget | budżet | "Budżet projektu", "budżet przekroczony" |
| built in | wbudowany | library items, roles |
| cancel (action) / cancelled | anuluj / anulowane | |
| canvas | kanwa | "Odtworzenie na kanwie" (canvas replay) |
| capabilities (model) | możliwości | catalogue tags: Programowanie, Rozumowanie, Długi kontekst, Narzędzia, Obraz, Tani |
| capability (connection) | możliwość (pl. możliwości) | ADR 0030: Read issues / Comment on issues / Move issues = "Odczyt zgłoszeń" / "Komentowanie zgłoszeń" / "Przenoszenie zgłoszeń"; "możliwości zapisu" (write capabilities); "Wymagane możliwości" (required capabilities) |
| caps (cost / token) | limity | "Limity kosztu i tokenów na zadanie", "bez limitu kosztu" |
| catalogue (model) | katalog | |
| ceiling (pool policy) | pułap | "pułap polityki puli" |
| checklist | lista kontrolna | |
| CI, CI execution | CI, wykonanie CI (pl. wykonania CI) | |
| CI entrypoint | punkt wejścia CI | |
| CI snippet | fragment GitLab CI | project CI tab |
| CI token | token CI | |
| coding harness | agent programistyczny | OpenCode / Claude Code |
| coding job (evaluation task kind) | zadanie programistyczne | vs "prompt" |
| command palette | paleta poleceń | |
| Connection expiring (notification type) | Wygasające połączenie | desktop notification type (ADR 0030 amendment); the Your turn card says "Token Jiry wkrótce wygaśnie" / "Token Jiry wygasł" |
| connection / Connections | połączenie (pl. połączenia) / Połączenia | credential registry (ADR 0030): page and tab "Połączenia", "Dodaj połączenie", "Połączenie Jira" (designer section), "połączenie platformy" / "połączenie projektu"; "Testuj połączenie" in Settings stays |
| constitution (Spec Kit) | konstytucja | |
| personal connection | połączenie osobiste | My account → Connections (ADR 0035); "przez połączenie osobiste" (via personal connection) |
| copy (button) | kopiuj | button label; "Skopiuj token teraz" stays as an instruction in sentences |
| cost | koszt | |
| cost per day | koszt dzienny | Costs chart |
| create | utwórz | |
| dashboard / Home | strona główna | the Home page |
| decide / decision | rozstrzygnij / decyzja | "Potrzebna decyzja", "bramka rozstrzygnięta" |
| delete | usuń | |
| density (comfortable / compact) | gęstość (wygodna / kompaktowa) | preference values "Wygodna" / "Kompaktowa" |
| designer (workflow) | projektant workflowów | |
| discard (unsaved edits) | porzuć zmiany | not "odrzuć" (= reject a gate) |
| display name | wyświetlana nazwa | users and model profiles |
| done | gotowe | baton holder |
| draft | szkic | |
| duplicate (action) | duplikuj | |
| edge | krawędź | workflow designer |
| effective permissions | uprawnienia efektywne | admin user page tab; not "efektywne uprawnienia" |
| effective policy | efektywna polityka | agent job panel; "Efektywna dla tego projektu" (Effective for this project) on the project Sandbox tab |
| egress | ruch wychodzący | sandbox |
| egress denied | zablokowany ruch wychodzący | agent job panel |
| entrypoint | punkt wejścia | |
| ephemeral storage | pamięć tymczasowa (dysk) | sandbox policy resources |
| evaluation | ewaluacja | |
| excess permissions / over-privileged | uprawnienia nadmiarowe / ma więcej uprawnień niż potrzeba | connection test chips: granted / missing / excess = "przyznane" / "brakujące" / "nadmiarowe"; designer warning "ma więcej uprawnień niż potrzeba" |
| executor | wykonawca | "Dowolny wykonawca"; executor names: Implementacja, Pobierz zgłoszenie, Powiadom, Zmień status zgłoszenia |
| extends / implements | rozszerza / implementuje | template columns |
| external write | zapis zewnętrzny | a write outside Back Office (Jira comment, transition); "zapisuje w Jirze" for the palette mark |
| finding (review) | uwaga (pl. uwagi) | severities: blokujące / poważne / drobne / kosmetyczne (Blocker / Major / Minor / Nit) |
| fleet (runner fleet) | flota runnerów | |
| forbid self-approval | zabroń samozatwierdzania | gate option (manual) |
| forecast | prognoza | |
| gate | bramka | "Przejrzyj bramkę", "bramka zaliczona / niezaliczona" |
| Git URL | URL repozytorium Git | project form |
| grant (admin) | nadanie (pl. nadania) | Administration → Nadania |
| group | grupa | |
| hard stop | twarda blokada | budgets; never "twarde zatrzymanie" |
| heartbeat | sygnał życia | "brak sygnału życia" |
| holder (baton) | posiadacz (pałeczki) | "Filtruj według posiadacza pałeczki" |
| holder (runs column) | kto trzyma | runs table, as in the manual |
| human gate (node kind) | bramka decyzyjna | node kind and status label; a gate in prose is "bramka" |
| human wait / CI wait | oczekiwanie na ludzi / oczekiwanie na CI | insights |
| idle | bezczynne | baton legend |
| in flight | w toku | Home section |
| inbox | skrzynka | the page itself is "Twoja kolej" |
| insights | wskaźniki | |
| integration | integracja | |
| interface (task) | interfejs (zadania) | |
| invite / invited | zaproś / zaproszony | |
| isolation | izolacja | sandbox |
| isolation levels Standard / High / Dev | standardowa / wysoka / deweloperska (izolacja) | UI labels "Standardowa" / "Wysoka" / "Deweloperska"; STANDARD / HIGH / DEV stay as technical values in code and policy-key tables; runtime names (gVisor, Kata, runc) stay |
| issue (Jira) | zgłoszenie | "klucz zgłoszenia" (issue key) |
| issue tracker | system zgłoszeń | |
| Jira intake watcher | monitor zgłoszeń Jira | ADR 0033; section title "Monitor zgłoszeń Jira"; fields "Włączony", "Interwał odpytywania (minuty)", "Workflow do uruchomienia"; poll = "odpytanie" |
| job | zadanie | runner job |
| judge / judging / judge model | ocena / ocenianie / model oceniający | evaluation; "Ocena" for the judge tab and the suite weight, "Model oceniający" for the model; never "sędzia" |
| landed (today) | zakończone (dziś) | "Zakończone dziś" |
| last used | ostatnie użycie | token lists (personal and CI) |
| lead time | czas realizacji | |
| leaderboard | ranking | |
| leased | przydzielone | job queue / work item state; in prose about the queue the noun "dzierżawa" (lease) may stay |
| least privilege | zasada najmniejszych uprawnień | connections, manual |
| ledger (usage) | rejestr | Costs |
| legacy credential | poświadczenie ze zmiennych środowiskowych | the `JIRA_*` env-var connection; "Przejdź na połączenia z ograniczonym zakresem" (migrate to scoped connections); avoid "starsze" alone |
| library (task library) | biblioteka (zadań) | |
| limit | limit | "Limit miesięczny", "Podnieś limit" |
| lineage | rodowód | template inheritance chain |
| live updates | aktualizacje na żywo | |
| manual | podręcznik | in-app help |
| max output tokens | maks. liczba tokenów wyjściowych | model profile default options |
| member / members | członek / członkowie | project |
| merge request | merge request | not translated |
| model / model profile | model / profil modelu | |
| node | węzeł | workflow node |
| operations | operacje | admin page |
| override(s) | nadpisanie (pl. nadpisania) | task node overrides |
| owner / maintainer / developer / approver / viewer | właściciel / maintainer / deweloper / zatwierdzający / obserwator | built-in project roles; UI names "Właściciel", "Maintainer", "Deweloper", "Zatwierdzający", "Obserwator" (`roleName()`, `core/i18n/catalog-labels.ts`); keys (`owner`, …) stay in code font |
| platform administrator / member / evaluator / library curator / auditor | administrator platformy / członek / ewaluator / kurator biblioteki / audytor | built-in platform roles; UI names "Administrator platformy", "Członek", "Ewaluator", "Kurator biblioteki", "Audytor" |
| project manager | kierownik projektów (pl. kierownicy projektów) | built-in platform role `project-manager` (ADR 0028): "Kierownik projektów", "Projekty tworzą kierownicy projektów" |
| palette (designer) | paleta | not the command palette |
| Pareto frontier | front Pareto | |
| pass rate | odsetek zaliczonych | |
| pending | oczekuje | |
| permission | uprawnienie | descriptions of the catalogue are noun phrases: "Odczyt dziennika audytu", "Tworzenie i edycja …", "Podgląd …" (`permissionDescription()`) |
| personal access token (PAT) | osobisty token dostępu (PAT) | |
| pin (connection on a node) | przypnij / przypięte | designer: "Przypnij", "Przypięte połączenie" |
| pipeline | pipeline (pl. pipeline'y) | CI pipeline |
| platform settings | ustawienia platformy | |
| policy (sandbox) | polityka (sandboxa) | |
| Poll now (intake watcher) | Sprawdź teraz | button; schedules an immediate poll |
| pool | pula (runnerów) | |
| pool selector | selektor puli | |
| port | port | node port |
| preferences | preferencje | |
| prefix (token) | prefiks | |
| project | projekt | |
| prompt | prompt | |
| provenance | pochodzenie | run tab "Pochodzenie" |
| provider (LLM, identity) | dostawca (LLM, tożsamości) | |
| queued | w kolejce | |
| reader connection | połączenie do odczytu | a connection with only Read issues; the writer is "połączenie do zapisu" |
| reasoning effort | poziom rozumowania | levels: Brak, Niski, Średni, Wysoki, Bardzo wysoki, Maksymalny |
| recovery codes | kody odzyskiwania | MFA; "Utwórz nowe kody odzyskiwania" (Create new recovery codes) |
| registration token | token rejestracyjny | runner fleet |
| reject / rejected | odrzuć / odrzucone | |
| relay (runs column) | sztafeta | runs table; the strip itself is "ścieżka sztafety" |
| relay track | ścieżka sztafety | the strip of segments |
| repetition | powtórzenie | benchmarks |
| replace token | zastąp token | connection secret rotation: "Zastąp token" |
| replica | replika | operations |
| repository | repozytorium | |
| resolve / resolved preview | rozwiąż / podgląd rozwiązanego zadania | |
| retry | ponów | "Ponów" button, "próba" for an attempt |
| review (CI, gate) | przegląd | "Przejrzyj bramkę", "Otwórz przegląd" |
| revoke (runner, CI token) | unieważnij / unieważniony | |
| rework loop | pętla poprawek | insights |
| role | rola | |
| rubric | kryteria oceny | |
| run / start run (verb) | uruchom | "Uruchom…" in the palette, "Rozpocznij uruchomienie" in dialogs |
| run (noun) | uruchomienie (pl. uruchomienia) | "Uruchomienia" page |
| run success rate | skuteczność uruchomień | insights |
| runner | runner (pl. runnery, gen. runnerów) | |
| sample project key (connection check) | przykładowy klucz projektu | "Przykładowy klucz projektu do sprawdzenia"; "Testuj w projekcie Jira" |
| sandbox | sandbox (gen. sandboxa) | "Polityka sandboxa" |
| save / save changes | zapisz / zapisz zmiany | "Wszystkie zmiany zapisane", "Niezapisane zmiany" |
| scope | zakres | library item scope; Atlassian token scopes ("zakresy tokenu", "zakresy szczegółowe" = granular scopes) |
| system connection (project) | połączenie systemowe | Project → Connections "Połączenie systemowe", "Kolejne połączenia dla kroków tego projektu" (ADR 0035) |
| secret | sekret | |
| send back for revision | odeślij do poprawy | gate decision |
| session | sesja | |
| settings | ustawienia | |
| shadow (project template) | przesłonić | "przesłania szablon globalny" |
| sign in / sign out | zaloguj się / wyloguj | page title "Logowanie" |
| signal (CI) | sygnał | "Wyślij sygnał ręcznie" |
| sign-in method: local / identity provider / both | lokalne / dostawca tożsamości / lokalne i {provider} | "Lokalne i Keycloak"; never "Oba" |
| skill | umiejętność (pl. umiejętności) | Agent Skills |
| spec gate | bramka specyfikacji | |
| spend / usage | wydatki / użycie | |
| spent in (month) | wydano w miesiącu | "Wydano w miesiącu: październik 2026" on budget cards |
| statement / subject (attestation) | oświadczenie / przedmiot | provenance tab |
| step | krok | run timeline |
| succeeded / failed | sukces / błąd | status words; "zakończone błędem" in sentences |
| suite (task suite) | zestaw zadań | |
| task / task template | zadanie / szablon zadania | |
| template | szablon | |
| Test before saving (connection draft) | Testuj przed zapisem | add/edit connection dialog; nothing is stored |
| test (connection) / verification | testuj / sprawdzenie | button "Testuj" (as "Testuj połączenie" in Settings); the result is "wynik sprawdzenia", "Ostatnio sprawdzono" (last verified); see also verify |
| theme (system / light / dark) | motyw (systemowy / jasny / ciemny) | |
| timed out | przekroczono czas | |
| timeline | oś czasu | run tab |
| token (LLM usage) | token (pl. tokeny) | "tokeny: wej. 12k · wyj. 1,5k" |
| token expiry date / expiring soon / expired | data wygaśnięcia tokenu / wkrótce wygaśnie / wygasł | connections |
| tool (agent tool) | narzędzie | "Dozwolone narzędzia", "Narzędzia wymagające zgody" |
| trigger | wyzwalacz | workflow triggers |
| two-factor authentication (MFA) | uwierzytelnianie dwuskładnikowe (MFA) | |
| user | użytkownik | |
| validate | waliduj | designer |
| verdict (review) | werdykt | approve / comment / request changes: zatwierdzenie / komentarz / prośba o zmiany |
| verify | zweryfikuj | attestation, MFA code; not "weryfikuj". A connection check is "Testuj" / "sprawdzenie" (see test) |
| wait for CI (node kind) | oczekiwanie na CI | node kind and status label; the baton / status chip "waiting for CI" = "czeka na CI" |
| warm pool (size) | rozgrzane sandboxy (liczba rozgrzanych sandboxów) | |
| work item / dead items | element pracy / martwe elementy | operations |
| workflow | workflow (pl. workflowy, gen. pl. workflowów) | |
| write confirmation / confirm a write | zatwierdzenie zapisu / zatwierdź zapis | ADR 0033 inbox item; "Agent chce skomentować SHOP-42", "Agent chce przenieść SHOP-42 do In Review"; desktop notification title "Potwierdź zapis w Jirze" (Confirm a Jira write); manual sections "Zatwierdzanie zapisów zewnętrznych" / "Zatwierdzanie przed zapisem zewnętrznym" |
| your turn | Twoja kolej | page, counter and baton word |

When a term is missing, look it up in `docs/manual/pl` first (the translators record "**Polski**
(**English**)" pairs), then add it here in the same change as the translation.
