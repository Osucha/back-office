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
| accepted (result) | zaakceptowane / zaakceptowany wynik | ADR 0059 agent insights column "Zaakceptowane" (Accepted); "Brak zaakceptowanego wyniku" (No accepted result yet); see cost per accepted result |
| access (agent) | dostęp | ADR 0060 agent field and column "Dostęp" (Access): "Pełny" (Full) / "Tylko odczyt" (Read only) / "Tylko te ścieżki" (Only these paths), list chip "Tylko {paths}"; "Ścieżki, które agent może zmieniać" (Paths the agent may change); runner log line `[Back Office runner] reverted N change(s) ...` stays English |
| accountable (role) | odpowiedzialny | "Odpowiedzialny: owner" |
| Add missing default workflows (N) | Dodaj brakujące workflowy domyślne (N) | ADR 0059 button in the project's workflows list; "workflowy domyślne" = default workflows |
| Add to agent | Dodaj do agenta | ADR 0061 button in "Agenci w tym workflowie" for a missing required skill; for a global agent the confirmation "Skopiować {agent} do tego projektu?" (Copy {agent} into this project?), button "Skopiuj i dodaj" (Copy and add); message "Dodano umiejętność {skill} do agenta {agent}" |
| agent | agent (pl. agenci) | "agent pracuje"; library entry (ADR 0056, 0057): page "Agenci", "Utwórz agenta", "Zapisz agenta", step setting "Agent"; page subtitle „Agent to ten, kto pracuje, i to, jak pracuje: harness, model, instrukcje, reguły i umiejętności. Szablon zadania mówi, co zrobić; krok workflow łączy jedno z drugim.” Built-in agent names (Analyst, Reviewer) are data and stay English |
| agent home | dom agenta | ADR 0060; manual only: the folder `/workspace/.agents/<agent key>/` of an agent in the run directory; on first use "dom agenta (agent home)" |
| agent insights | wskaźniki agentów | ADR 0059 route title "Wskaźniki agentów"; page title "Agenci: koszt i skuteczność" (Agents: cost and effectiveness); Insights switcher "Dostarczanie / Agenci" (Delivery / Agents); sections "Według agenta i modelu" (Per agent and model), "Według szablonu" (Per template); palette "Porównaj agentów" (Compare agents) |
| agent job | zadanie agenta (pl. zadania agentów) | runner fleet |
| agent questions | pytania agenta | ADR 0060: step settings "Agent może zadawać pytania" (Agent may ask questions), "Rundy pytań" (Question rounds); inbox panel "Agent pyta" (The agent asks), "Dlaczego:" (Why:), buttons "Odpowiedz i kontynuuj" (Answer and continue) / "Anuluj krok" (Cancel the step), "Runda 1 z 2" (Round 1 of 2); turn card and notification "Agent ma pytania" (The agent has questions), "Odpowiedz na pytania" (Answer the questions), "Zobacz pytania" (View the questions); baton "czeka na Twoje odpowiedzi" (waiting for your answers); the prompt section "Answers of the owner" stays English |
| agent session | sesja agenta | ADR 0057/0060 step setting "Sesja agenta": "Własna (powtórzenia kontynuują)" (Own (repeats continue)) / "Nowa za każdym razem" (New each time) / "Kontynuuj sesję kroku …" (Continue the session of step …), "Wybierz krok" (Pick the step); "Nowa sesja" (New session) is gone |
| agents in this workflow | agenci w tym workflowie | ADR 0061 designer toolbar button and panel title "Agenci w tym workflowie" (Agents in this workflow), badge "{count} problem / problemy / problemów"; intro "Reguły, umiejętności i uwagi dla każdego agenta"; rows "Kroki" (Steps), "Reguły" (Rules), "Umiejętności" (Skills), "Wymagane umiejętności" (Required skills), "brak" (none); scope "Globalny" / "Ten projekt" (Global / This project); empty "Ten workflow nie ma jeszcze kroków z zadaniami."; see also audit finding, coherence check |
| alert (budget) | alert | "alert budżetu", "Alert przy (%)" |
| allowed projects (connection) | dozwolone projekty | field label "Dozwolone projekty" (Jira project keys a connection may touch); not the network allowlist |
| allowlist (network) | lista dozwolonych hostów | "Dozwolone hosty" |
| API key set / configured | klucz API ustawiony / klucz API skonfigurowany | "Klucz API ustawiony" in Settings → LLM providers, "Klucz API skonfigurowany" in the model drawer; "brak klucza API" (no API key) |
| API token (Jira) | token API | connection field "Token API"; "klasyczny token API" (classic API token); "token API z zakresami" (scoped API token, ADR 0035), "Zakresy tokenu", "Identyfikator chmury witryny" (cloud id) |
| act as (Jira node) | działaj jako | designer "Działaj jako": "Połączenie systemowe projektu", "Wskazane połączenie", "Osoba, która zatwierdza zapis" / "Osoba, która rozpoczyna uruchomienie" (ADR 0035) |
| action (node kind) | akcja (pl. akcje) | ADR 0056: palette group "Akcje"; Fetch issue / Comment on issue / Transition issue / Start workflow / Project digest = "Pobierz zgłoszenie" / "Skomentuj zgłoszenie" / "Zmień status zgłoszenia" / "Uruchom workflow" / "Podsumowanie projektu" |
| any agent | dowolny agent | ADR 0061: what a template needs when it does not need a coding agent; tag and templates filter option "Dowolny agent" (Any agent), filter placeholder "Dowolny rodzaj agenta" (Any kind of agent), aria label "Filtruj według agenta, którego wymaga szablon"; see needs a coding agent |
| approval before write (Jira) | zatwierdzenie przed zapisem | ADR 0033; designer section "Zatwierdzenie przed zapisem w Jirze" (Approval before writing to Jira); values **Wymagane** / **Niewymagane** (Required / Not required); "Kto zatwierdza" (Who approves) |
| approve / approved | zatwierdź / zatwierdzone | gate decision |
| approver | zatwierdzający | "u zatwierdzającego" (baton) |
| artefact | artefakt | |
| assign (a step) / assigned to / assignee | przypisz / przypisane do / osoba przypisana | run-time assignment of a waiting gate (ADR 0028): "Przypisz…", "Przypisane do: Ann", "Przypisane do Ciebie przez …"; "Kto rozstrzyga ten krok" (Who decides this step) |
| attempt | próba | "próba 2" |
| attestation | atestacja | provenance |
| audit finding (agents in this workflow) | uwaga (pl. uwagi) | ADR 0061 finding kinds: "Brakująca umiejętność" (Missing skill), "Wymaga agenta kodującego" (Needs a coding agent), "Agent tylko do odczytu w kroku z push" (Read-only agent on a pushing step), "Nieznana reguła" (Unknown rule), "Nieznana umiejętność" (Unknown skill), "Nieznany agent" (Unknown agent), "Dwie umiejętności o tej samej nazwie" (Two skills with the same name); load error "Nie udało się sprawdzić agentów tego workflowu."; the server's message under the title stays English |
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
| candidate (benchmark) | kandydat (pl. kandydaci) | ADR 0059: an agent with a model; "Kandydaci" (Candidates), "Dodaj kandydata" (Add candidate), "Usuń kandydata", "Kandydat {n}", leaderboard column "Kandydat"; "Model agenta: {model}" (Agent's model: …); not "model kandydujący" any more |
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
| coding agent | agent kodujący | ADR 0056/0061: an agent with a harness (OpenCode, Claude Code); palette tag "agent kodujący" (coding agent); step warning "Szablon wymaga agenta kodującego: ten krok nie zadziała z agentem odpowiedzi modelu." |
| coding harness | agent programistyczny | OpenCode / Claude Code; the benchmark form no longer has it (ADR 0059: the candidate's agent brings its harness) |
| coding job (evaluation task kind) | zadanie programistyczne | vs "prompt" |
| coherence check | sprawdzenie spójności | ADR 0061 button "Sprawdź spójność" (Check coherence); confirmation "Sprawdzić spójność agenta {agent}?"; finding severities "Sprzeczność" (Contradiction), "Niejasność" (Ambiguity), "Powtórzenie" (Redundancy); "Propozycja:" (Suggestion:); "Nie znaleziono sprzeczności." (No contradictions found.); errors "Nie ustawiono modelu dla odpowiedzi modelu: …" (409), "Sprawdzenie nie powiodło się." |
| command palette | paleta poleceń | |
| Connection expiring (notification type) | Wygasające połączenie | desktop notification type (ADR 0030 amendment); the Your turn card says "Token Jiry wkrótce wygaśnie" / "Token Jiry wygasł" |
| connection / Connections | połączenie (pl. połączenia) / Połączenia | credential registry (ADR 0030): page and tab "Połączenia", "Dodaj połączenie", "Połączenie Jira" (designer section), "połączenie platformy" / "połączenie projektu"; "Testuj połączenie" in Settings stays |
| constitution (Spec Kit) | konstytucja | |
| personal connection | połączenie osobiste | My account → Connections (ADR 0035); "przez połączenie osobiste" (via personal connection) |
| copy (button) | kopiuj | button label; "Skopiuj token teraz" stays as an instruction in sentences |
| cost | koszt | |
| cost per accepted result | koszt zaakceptowanego wyniku | ADR 0059 headline metric: agent insights, workflow metrics and library list (replaced "Koszt / sukces" and "Koszt udanego uruchomienia" there), benchmark leaderboard; the delivery insights card "Koszt udanego uruchomienia" (Cost per successful run) is a different figure and stays |
| cost per day | koszt dzienny | Costs chart |
| create | utwórz | |
| dashboard / Home | strona główna | the Home page |
| Default: {agent} (step agent) | Domyślnie: {agent} | ADR 0061 placeholder of the step's "Agent" field: the agent a step without one runs with (OpenCode when the template needs a coding agent, else Model answer); help "Kto wykonuje ten krok. Puste: OpenCode, gdy szablon wymaga agenta kodującego, w przeciwnym razie Odpowiedź modelu." Replaces "Domyślny z szablonu: {agent}" |
| decide / decision | rozstrzygnij / decyzja | "Potrzebna decyzja", "bramka rozstrzygnięta" |
| delete | usuń | |
| delivery (insights) | dostarczanie | Insights switcher "Dostarczanie / Agenci" (Delivery / Agents) |
| density (comfortable / compact) | gęstość (wygodna / kompaktowa) | preference values "Wygodna" / "Kompaktowa" |
| designer (workflow) | projektant workflowów | |
| directory (step) | katalog | ADR 0060 step setting "Katalog" (Directory): "Katalog przebiegu (wspólny)" (Run directory (shared)) / "Osobny katalog" (Separate directory); run fact "Katalog: osobny" |
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
| environment (of the agent) | środowisko agenta | ADR 0057: agents list column "Środowisko" (Environment), manual section "Środowisko agenta"; the template editor section of that name was removed by ADR 0061 (templates have no agent); see also platform preamble |
| ephemeral storage | pamięć tymczasowa (dysk) | sandbox policy resources |
| evaluation | ewaluacja | |
| excess permissions / over-privileged | uprawnienia nadmiarowe / ma więcej uprawnień niż potrzeba | connection test chips: granted / missing / excess = "przyznane" / "brakujące" / "nadmiarowe"; designer warning "ma więcej uprawnień niż potrzeba" |
| execution (of a step) | wykonanie (pl. wykonania) | agent insights column "Wykonania" (Executions); "# wykonanie / wykonania / wykonań" |
| executor | (removed) | ADR 0056 removed the template executor; use "agent" (who runs a task) or "akcja" (a node without a model) |
| extends / implements | rozszerza / implementuje | template columns |
| external write | zapis zewnętrzny | a write outside Back Office (Jira comment, transition); "zapisuje w Jirze" for the palette mark |
| first pass / first-pass rate | za pierwszym razem | workflow metrics, library list and agent insights column "Za pierwszym razem" |
| finding (review) | uwaga (pl. uwagi) | severities: blokujące / poważne / drobne / kosmetyczne (Blocker / Major / Minor / Nit) |
| fleet (runner fleet) | flota runnerów | |
| forbid self-approval | zabroń samozatwierdzania | gate option (manual) |
| forecast | prognoza | |
| gate | bramka | "Przejrzyj bramkę", "bramka zaliczona / niezaliczona" |
| Git URL | URL repozytorium Git | project form |
| grant (admin) | nadanie (pl. nadania) | Administration → Nadania |
| group | grupa | |
| harness (agent) | harness | ADR 0056: agent field; OpenCode / Claude Code, empty = model answer |
| hard stop | twarda blokada | budgets; never "twarde zatrzymanie" |
| heartbeat | sygnał życia | "brak sygnału życia" |
| help ("?" next to a label) | pomoc | ADR 0061: field descriptions sit behind a "?" tooltip; its accessible name "Pomoc" (Help) |
| holder (baton) | posiadacz (pałeczki) | "Filtruj według posiadacza pałeczki" |
| holder (runs column) | kto trzyma | runs table, as in the manual |
| human gate (node kind) | bramka decyzyjna | node kind and status label; a gate in prose is "bramka" |
| human wait / CI wait | oczekiwanie na ludzi / oczekiwanie na CI | insights |
| idle | bezczynne | baton legend |
| import from library (agent) | importuj z biblioteki | ADR 0060 project tab "Agenci" (Agents): "Utwórz agenta" (Create agent), "Importuj z biblioteki" (Import from library); "Ten projekt" / "Globalny" (This project / Global), "zastępuje globalnego agenta" / "zastąpiony w tym projekcie" |
| in flight | w toku | Home section |
| inbox | skrzynka | the page itself is "Twoja kolej" |
| insights | wskaźniki | |
| instructions (agent) | instrukcje | ADR 0057 agent field "Instrukcje" (Instructions): the agent's role and way of working |
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
| kind (agent) | rodzaj | agent field and list column "Rodzaj" (Kind) = the harness: "Odpowiedź modelu", "OpenCode", "Claude Code" |
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
| MCP server (of an agent) | serwer MCP (pl. serwery MCP) | ADR 0057 agent field "Serwery MCP" (MCP servers), "Dodaj serwer MCP", "Usuń serwer MCP" |
| member / members | członek / członkowie | project |
| merge request | merge request | not translated |
| model answer | odpowiedź modelu | built-in agent "Odpowiedź modelu" (ADR 0056) |
| model / model profile | model / profil modelu | |
| needs a coding agent | wymaga agenta kodującego | ADR 0061 read-only tag of a template (templates list, editor header, step) and templates filter option "Wymaga agenta kodującego" (Needs a coding agent); editor hint "Jego konfiguracja (faza Spec Kit, push lub tylko przygotowanie) wymaga agenta kodującego. Agenta wybiera krok workflow."; palette tag "agent kodujący"; see any agent |
| node | węzeł | workflow node |
| not applicable (benchmark) | nie dotyczy | ADR 0059 leaderboard column "Nie dotyczy": tasks a candidate's agent cannot do |
| operations | operacje | admin page |
| override(s) | nadpisanie (pl. nadpisania) | task node overrides |
| owner / maintainer / developer / approver / viewer | właściciel / maintainer / deweloper / zatwierdzający / obserwator | built-in project roles; UI names "Właściciel", "Maintainer", "Deweloper", "Zatwierdzający", "Obserwator" (`roleName()`, `core/i18n/catalog-labels.ts`); keys (`owner`, …) stay in code font |
| platform administrator / member / evaluator / library curator / auditor | administrator platformy / członek / ewaluator / kurator biblioteki / audytor | built-in platform roles; UI names "Administrator platformy", "Członek", "Ewaluator", "Kurator biblioteki", "Audytor" |
| project manager | kierownik projektów (pl. kierownicy projektów) | built-in platform role `project-manager` (ADR 0028): "Kierownik projektów", "Projekty tworzą kierownicy projektów" |
| palette (designer) | paleta | not the command palette |
| parameter (template) / parameter values | parametr (pl. parametry) / wartości parametrów | ADR 0058: "Parametry", "Wartości parametrów", "Dodaj parametr", "Wymagany"; badge "wymaga parametrów" (needs parameters, lower case like a tag); types "Jedna linia" (Single line), "Tekst wieloliniowy" (Multi-line text), "Liczba" (Number), "Tak / nie" (Yes / no), "Wybór" (Choice); not "parametry uruchomienia" (run parameters, a different thing) |
| Pareto frontier | front Pareto | |
| pass rate | odsetek zaliczonych | |
| pending | oczekuje | |
| permission | uprawnienie | descriptions of the catalogue are noun phrases: "Odczyt dziennika audytu", "Tworzenie i edycja …", "Podgląd …" (`permissionDescription()`) |
| personal access token (PAT) | osobisty token dostępu (PAT) | |
| pin (connection on a node) | przypnij / przypięte | designer: "Przypnij", "Przypięte połączenie" |
| pipeline | pipeline (pl. pipeline'y) | CI pipeline |
| platform preamble | preambuła platformy | ADR 0057: the fixed first part of the agent's environment; "tylko preambuła platformy" |
| platform settings | ustawienia platformy | |
| policy (sandbox) | polityka (sandboxa) | |
| Poll now (intake watcher) | Sprawdź teraz | button; schedules an immediate poll |
| pool | pula (runnerów) | |
| pool selector | selektor puli | |
| port | port | node port |
| preferences | preferencje | |
| prefix (token) | prefiks | |
| preset (template) | preset | ADR 0058: a template that only sets parameter values; button "Utwórz preset" (Create preset) |
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
| reference workflow | workflow referencyjny | ADR 0059; the workflow name "Reference: one Claude Code session" is data and stays English, like the other default workflow names |
| relay (runs column) | sztafeta | runs table; the strip itself is "ścieżka sztafety" |
| relay track | ścieżka sztafety | the strip of segments |
| repetition | powtórzenie | benchmarks |
| replace token | zastąp token | connection secret rotation: "Zastąp token" |
| replica | replika | operations |
| repository | repozytorium | |
| required skills (template) | wymagane umiejętności | ADR 0060 template field "Wymagane umiejętności"; a missing one is shown under "Ostrzeżenia" (Warnings); ADR 0061: palette hover "Wymagane umiejętności: {skills}", the agents panel row and finding "Brakująca umiejętność", button "Dodaj do agenta" |
| resolve / resolved preview | rozwiąż / podgląd rozwiązanego zadania | |
| retry | ponów | "Ponów" button, "próba" for an attempt |
| review (CI, gate) | przegląd | "Przejrzyj bramkę", "Otwórz przegląd" |
| revoke (runner, CI token) | unieważnij / unieważniony | |
| rework loop | pętla poprawek | insights |
| role | rola | |
| rubric | kryteria oceny | |
| rule (library) | reguła (pl. reguły) | ADR 0055/0057: library page "Reguły", "Utwórz regułę", "Stosuj automatycznie w każdym kroku"; agent field "Reguły" |
| run / start run (verb) | uruchom | "Uruchom…" in the palette, "Rozpocznij uruchomienie" in dialogs |
| run (noun) | uruchomienie (pl. uruchomienia) | "Uruchomienia" page |
| run directory | katalog przebiegu | ADR 0060; replaces "przestrzeń robocza" (run workspace, ADR 0044, removed with the project tab "Przestrzenie robocze"); help topic "Katalog przebiegu i sesje agentów" |
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
| setup command | polecenie przygotowania | workflow setting "Polecenie przygotowania" (Setup command), ADR 0060 |
| shadow (project template) | przesłonić | "przesłania szablon globalny" |
| sign in / sign out | zaloguj się / wyloguj | page title "Logowanie" |
| signal (CI) | sygnał | "Wyślij sygnał ręcznie" |
| sign-in method: local / identity provider / both | lokalne / dostawca tożsamości / lokalne i {provider} | "Lokalne i Keycloak"; never "Oba" |
| skill | umiejętność (pl. umiejętności) | Agent Skills; agent field "Umiejętności"; the step overrides "Dodaj umiejętności do umiejętności agenta" / "Pomiń umiejętności agenta" were removed in ADR 0060 |
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
| warnings (resolution check) | ostrzeżenia | "Ostrzeżenia" (Warnings) in the template preview and "What this step gets"; they do not block (ADR 0060) |
| warm pool (size) | rozgrzane sandboxy (liczba rozgrzanych sandboxów) | |
| work item / dead items | element pracy / martwe elementy | operations |
| workflow | workflow (pl. workflowy, gen. pl. workflowów) | |
| write confirmation / confirm a write | zatwierdzenie zapisu / zatwierdź zapis | ADR 0033 inbox item; "Agent chce skomentować SHOP-42", "Agent chce przenieść SHOP-42 do In Review"; desktop notification title "Potwierdź zapis w Jirze" (Confirm a Jira write); manual sections "Zatwierdzanie zapisów zewnętrznych" / "Zatwierdzanie przed zapisem zewnętrznym" |
| your turn | Twoja kolej | page, counter and baton word |

When a term is missing, look it up in `docs/manual/pl` first (the translators record "**Polski**
(**English**)" pairs), then add it here in the same change as the translation.
