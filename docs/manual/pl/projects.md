# Projekty

Projekt wiąże swoje repozytoria Git (żadne, jedno lub kilka), opcjonalny projekt w Jirze i konstytucję
z workflowami, członkami, tokenami CI, polityką sandboxa i budżetem. Ten rozdział opisuje tworzenie
projektu, jego repozytoria i wszystkie karty strony projektu.

<a id="create-project"></a>
## Tworzenie projektu

<a id="who-creates"></a>
### Kto tworzy projekty

Projekty tworzą **kierownicy projektów**, a nie każdy użytkownik: potrzebujesz uprawnienia platformowego
`projects.create`, które mają wbudowana rola **Kierownik projektów** (**Project manager**,
`project-manager`) i administratorzy platformy. Domyślna rola **Członek** (**Member**) go nie ma. Jeśli
nie widzisz przycisku **Utwórz projekt**, strona Projekty pokazuje „Projekty tworzą kierownicy
projektów”: poproś kierownika projektów o utworzenie projektu albo właściciela projektu o dodanie Cię do
istniejącego. Administratorzy nadają tę rolę w **Administracja → Nadania** (**Administration → Grants**)
([Kierownicy projektów](administration.md#project-managers)).

Kierownik, który tworzy projekt, zostaje jego **Właścicielem**, dodaje zespół z rolami projektowymi
([Członkowie i role projektowe](#members)) i decyduje, kto wykonuje który krok: w workflowie
([Ustawienia bramki](workflows.md#gates-config)) oraz, dla czekającego kroku, przyciskiem **Przypisz…**
(**Assign…**) ([Przypisywanie kroku](your-turn.md#assign-steps)).

### Kroki

1. Otwórz **Projekty** (**Projects**) i kliknij **Utwórz projekt** (**Create project**) (albo użyj
   **Utwórz projekt** w palecie poleceń).
2. Wypełnij formularz:

| Pole | Znaczenie |
|---|---|
| **Kod skrócony** (**Short code**) | Własny kod projektu w Back Office: 2–16 wielkich liter, cyfr lub podkreśleń, na przykład `SHOP`. Opcjonalny: puste pole oznacza kod utworzony z nazwy. Pojawia się w loginach CI, narzędziach MCP i zmiennej szablonu `{{project.key}}`; można go później zmienić w **Ustawieniach** (dotychczasowe przebiegi zachowują stary kod). To **nie** jest klucz Jira. |
| **Nazwa**, **Opis** | Wyświetlane na karcie i stronie projektu. |
| **Repozytoria** (**Repositories**) | Opcjonalne. Repozytoria Git, które agenci klonują i do których wypycha zmiany [Dostarcz zmiany](workflows.md#deliver-changes); każde ma klucz, URL, gałąź domyślną i przeznaczenie. W projekcie bez kodu zostaw listę pustą. Zob. [Repozytoria](#repositories). Back Office nigdy nie wywołuje API Twojego serwera Git; to Twoje CI wywołuje Back Office. Agenci potrzebują poświadczeń Git na runnerze (zob. [Dostęp agentów do gita](integrations-git.md#git-overview)). |
| Przełącznik systemu zgłoszeń (**Jira** / **brak**) | Włącz, gdy workflowy korzystają z akcji Jiry (pobranie zgłoszenia, komentarz do zgłoszenia, zmiana statusu). Poświadczenia Jiry to [połączenia](#project-connections). |
| **Klucz projektu Jira** (**Jira project key**) | Wymagany przy włączonej Jirze: klucz projektu w Jirze, czyli przedrostek kluczy zgłoszeń (`LFV` w `LFV-123`). Projektant używa go, aby sprawdzić, które połączenie dostanie węzeł Jiry, a monitor zgłoszeń czyta ten projekt. Nie zależy od kodu skróconego. |
| **Filtr JQL** (**JQL filter**) | Zawęża zgłoszenia, na które reaguje **monitor zgłoszeń Jira**, na przykład `issuetype = Story AND labels = ai`. Nie ogranicza uruchomień rozpoczynanych ręcznie, z CI ani przez MCP. |
| **Monitor zgłoszeń Jira** (**Jira intake watcher**; karta Ustawienia, po utworzeniu projektu) | Pozwala Jirze uruchamiać workflowy: gdy zgłoszenie trafi do statusu (albo pojawi się nowe zgłoszenie), wybrany workflow startuje z jego kluczem. **Monitoruj Jirę i uruchamiaj workflowy**, **Sprawdzaj co (minuty)**, **Workflow do uruchomienia**, karta stanu i **Sprawdź teraz** (**Poll now**); wymaga `project.manage` i połączenia do odczytu. Zob. [Monitor zgłoszeń Jira](integrations-jira.md#jira-intake). |
| **Domyślny profil modelu** (**Default model profile**) | Używany przez zadania oparte na modelu, które nie wskazują profilu. |
| **Profil modelu do przeglądów** (**Review model profile**) | Używany przez zadania przeglądu kodu; puste oznacza profil domyślny. |
| **Konstytucja (markdown)** (**Constitution (markdown)**) | Opcjonalna. Zasady właściciela projektu. Każdy agent projektu dostaje je jako część swojego [środowiska](task-library.md#environment) (polecenia mogą je też czytać jako `{{project.constitution}}`). |

3. Kliknij **Utwórz projekt**.

### Co widzisz potem

Zostajesz **Właścicielem** (**Owner**) projektu. Co jeszcze powstaje, zależy od repozytoriów:

- **Z co najmniej jednym repozytorium**: workflowy **Issue to reviewed MR**, **CI code review**, **Change request
  (delta)** i **Reference: one Claude Code session** oraz punkt wejścia CI `code-review` (zob. [Domyślne
  workflowy](workflows.md#default-workflows)). Możesz je edytować, duplikować lub usuwać.
- **Bez repozytorium**: jeden workflow startowy, **LLM task with review**, i żadnego punktu wejścia CI
  (zob. [Projekty bez repozytorium](#no-repository)).

<a id="repositories"></a>
## Repozytoria

Projekt może nie mieć repozytorium, mieć jedno albo kilka, na przykład kod usługi, jej dokumentację
i kontrakt API. Workflow wymienia te, które klonuje jego przebieg, w swoich [Źródłach](workflows.md#workflow-sources);
agenci w krokach programistycznych je czytają i zmieniają, a wypycha do nich tylko [Dostarcz
zmiany](workflows.md#deliver-changes). Wszystkie pozostałe kroki (wywołania modelu, Jira, bramki, oczekiwanie na CI)
działają bez nich.

Listę edytujesz w formularzu projektu (**Utwórz projekt**) albo na karcie projektu **Źródła**
(**Sources**). Wymaga `project.manage`.

| Pole | Znaczenie |
|---|---|
| **Klucz** (**Key**) | Krótka nazwa: małe litery, cyfry i myślniki, zaczyna się literą (do 31 znaków), na przykład `service` albo `docs`. Unikalna w projekcie. Źródła workflowu i dostarczenia odwołują się nią do repozytorium i jest to katalog, w którym agent je znajdzie (`/workspace/<klucz>`). |
| **URL** | URL repozytorium Git, które runner klonuje (i do którego wypycha dostarczenie). |
| **Gałąź domyślna** (**Default branch**) | Domyślnie `main`. Gałąź, od której agenci zaczynają i do której kierowane są merge requesty. Nic nigdy do niej nie jest wypychane. |
| **Przeznaczenie** (**Purpose**) | **Kod źródłowy** (**Source code**), **Dokumentacja** (**Documentation**) lub **Inne** (**Other**). Etykieta widoczna w projektancie i w uruchomieniach; agent też ją dostaje. |
| **Opis** (**Description**) | Opcjonalny. Co jest w repozytorium, dla ludzi i dla agenta (agent dostaje go jako dane, nie jako polecenia). |
| **Główne** (**Primary**) | Co najwyżej jedno. Repozytorium, którego używa workflow, gdy jego źródła są puste. Opcjonalne. |

1. Kliknij **Dodaj repozytorium** (**Add repository**) i wypełnij wiersz.
2. Oznacz jedno repozytorium jako **Główne**, jeśli większość workflowów w nim pracuje, albo nie oznaczaj
   żadnego, jeśli każdy workflow ma wymieniać swoje źródła.
3. Kliknij **Zapisz** (**Save**) (albo **Utwórz projekt**).

**Główne czy nie.** Gdy projekt ma główne repozytorium, workflowy napisane dla jednego repozytorium działają
bez zmian: workflow z pustymi **Źródłami** używa głównego. Bez głównego repozytorium każdy workflow z krokami
programistycznymi albo dostarczeniem musi wymienić swoje źródła ([Źródła workflowu](workflows.md#workflow-sources));
projektant pokazuje workflowy, które tego nie robią.
Projekty utworzone przed wprowadzeniem tej funkcji mają swoje repozytorium jako `main`, z przeznaczeniem
**Kod źródłowy**, oznaczone jako główne.

**Późniejsze zmiany listy.** Usunięcie repozytorium albo zmiana jego klucza może zepsuć workflowy, które go
używają. Projektant pokazuje błąd, a uruchomienie, którego źródła wskazują repozytorium, którego projekt już nie ma,
nie wystartuje; komunikat podaje listę repozytoriów projektu.

**Poświadczenia.** Każde repozytorium potrzebuje tokenu do odczytu oraz, jeśli workflow do niego dostarcza, tokenu do
wypychania ([Dostęp agentów do gita](integrations-git.md#git-least-privilege)). Joby agentów dostają tylko token do
odczytu; token do wypychania dostaje tylko job [Dostarcz zmiany](workflows.md#deliver-changes).

<a id="sources"></a>
### Karta Źródła

Karta projektu **Źródła** (**Sources**) zbiera wszystko, co workflowy projektu mogą sklonować: po lewej
repozytoria projektu (edytor opisany wyżej, zapisywany przyciskiem **Zapisz zmiany**), po prawej
[źródła z biblioteki](task-library.md#sources) wspólne dla wszystkich projektów, tutaj tylko do odczytu.
Każdy workflow wybiera swoje źródła w projektancie ([Źródła workflowu](workflows.md#workflow-sources)).

<a id="no-repository"></a>
### Projekty bez repozytorium

Back Office powstał z myślą o wytwarzaniu oprogramowania, ale obsługuje każdą pracę wykonywaną z modelami
językowymi: pisanie, analizy, tłumaczenia, przeglądy dokumentów. Utwórz projekt z pustą listą
**Repozytoria**. Dostanie on workflow startowy **LLM task with review**:

1. **Start** → **Zadanie LLM** (szablon `llm-task`): opisz, co model ma zrobić, w parametrze
   **Task** węzła (w sekcji **Parametry**; puste oznacza zadanie domyślne: podsumuj materiał wejściowy). Parametr **Materiał wejściowy** (**Input
   material**), o który węzeł pyta przy starcie, trafia do modelu jako materiał (dane, nie polecenia).
2. → **Result ok?** (bramka): **approve** kończy uruchomienie sukcesem, **revise** odsyła Twój komentarz do
   zadania, **reject** kończy uruchomienie porażką.

Uruchom go przyciskiem **Uruchom** (**Start run**) i wpisz tekst do opracowania w polu **Materiał
wejściowy** ([Uruchamianie workflowu](workflows.md#start-run)). Kroków programistycznych nie da się użyć,
dopóki projekt nie ma repozytorium. Gdy dodasz pierwsze repozytorium, workflowy domyślne i punkt wejścia
`code-review` zostaną dodane jednorazowo (chyba że workflowy o tych nazwach już istnieją).

## Strona projektu

`/projects/:id` pokazuje klucz, repozytoria (klucz, przeznaczenie, oznaczenie głównego), klucz Jiry, autora projektu i Twoje role w nim. Pod
nagłówkiem: uruchomienia **W toku** (**In flight**) według tego, kto je trzyma, **Otwarte bramki**
(**Open gates**), wykonania CI z ostatnich 7 dni i koszt z ostatnich 30 dni. **Nowy workflow**
(**New workflow**) otwiera projektanta.

Karty, każda dostępna przez `?tab=` w URL:

| Karta | Zawartość | Zmiana wymaga |
|---|---|---|
| **Workflowy** (**Workflows**) | Workflowy projektu z wyzwalaczami, stanem i datą ostatniej zmiany; **Uruchom** (**Start run**), **Edytuj** / **Podgląd** (**Edit** / **View**) i menu dodatkowych akcji (**Duplikuj workflow**, **Usuń workflow**). Gdy brakuje workflowów domyślnych (projekty utworzone, zanim istniały): **Dodaj brakujące workflowy domyślne (N)** (**Add missing default workflows (N)**, [Domyślne workflowy](workflows.md#default-workflows)). | `workflow.edit` (uruchamianie: `run.start`) |
| **Źródła** (**Sources**) | Repozytoria projektu i źródła z biblioteki (zob. [Karta Źródła](#sources)). | `project.manage` |
| **Agenci** (**Agents**) | Agenci, których mogą używać kroki projektu: globalni i własni agenci projektu; **Utwórz agenta** (**Create agent**), **Importuj z biblioteki** (**Import from library**) (zob. [Agenci projektu](#project-agents)). | `library.project.manage` |
| **Uruchomienia** (**Runs**) | Uruchomienia projektu (zob. [Uruchomienia](runs.md#run-list)). | — |
| **CI** | Punkty wejścia, tokeny CI, fragment konfiguracji GitLab CI i wykonania CI. | `project.manage`, `project.ci.manage` |
| **Członkowie** (**Members**) | Użytkownicy i grupy z ich rolami projektowymi. | `project.members.manage` |
| **Połączenia** (**Connections**) | Połączenia Jiry projektu i połączenia platformy, z których projekt korzysta awaryjnie (zob. [Połączenia projektu](#project-connections)). | `project.connections.manage` |
| **Sandbox** | Polityka sandboxa projektu i to, co dziś dostaje zadanie. | `project.manage` |
| **Budżet** (**Budget**) | Miesięczny budżet projektu. | `project.manage` |
| **Ustawienia** (**Settings**) | Formularz projektu opisany wyżej (bez repozytoriów, które są na karcie **Źródła**) z konstytucją, [monitor zgłoszeń Jira](integrations-jira.md#jira-intake) i **Usuń ten projekt**. | `project.manage` |

<a id="project-agents"></a>
## Agenci projektu

Karta **Agenci** (**Agents**) pokazuje, kto może pracować nad krokami tego projektu (ADR 0060): globalnych agentów
biblioteki i własnych agentów projektu, z kolumnami **Zakres** (**Scope**: **Ten projekt** albo **Globalny**),
**Rodzaj** (**Kind**), **Model**, **Dostęp** (**Access**) i **Środowisko** (**Environment**: ile reguł
i umiejętności). Agent projektu z kluczem globalnego agenta ma dopisek „zastępuje globalnego agenta”, a ten globalny
agent „zastąpiony w tym projekcie”. Czym jest agent i jakie ma pola: [Agenci](task-library.md#agents).

Z uprawnieniem `library.project.manage` w projekcie:

- **Utwórz agenta** (**Create agent**) otwiera formularz agenta z zakresem ustawionym na ten projekt.
- **Importuj z biblioteki** (**Import from library**) wybiera globalnego agenta i tworzy jego kopię projektową
  z **tym samym kluczem**, a potem otwiera ją do edycji (na przykład inny model, reguły projektu, węższy **Dostęp**).
  Kopia zastępuje globalnego agenta w krokach tego projektu bez zmiany żadnego workflowu; inne projekty nadal używają
  globalnego. To samo robi **Dostosuj dla projektu** w bibliotece.

<a id="members"></a>
## Członkowie i role projektowe

To, co możesz robić w projekcie, wynika z Twojej roli projektowej. Uprawnienia użytkownika to suma ról
z bezpośredniego członkostwa i z każdej grupy, która jest członkiem projektu.

| Rola | Może |
|---|---|
| **Właściciel** (**Owner**, `owner`) | Wszystko w projekcie, łącznie z członkami, tokenami CI, połączeniami, przypisywaniem czekających kroków (`run.assign`) i usunięciem projektu. |
| **Maintainer** (`maintainer`) | Wszystko to, co właściciel, poza zarządzaniem członkami i połączeniami (łącznie z przypisywaniem czekających kroków). |
| **Deweloper** (**Developer**, `developer`) | Przeglądać, edytować workflowy, uruchamiać, ponawiać i anulować uruchomienia, ręcznie wysyłać sygnały CI, widzieć koszty. |
| **Zatwierdzający** (**Approver**, `approver`) | Przeglądać, rozstrzygać bramki, widzieć koszty. |
| **Obserwator** (**Viewer**, `viewer`) | Przeglądać projekt, jego workflowy, uruchomienia i wykonania CI. |

Posiadacze uprawnienia platformowego `admin.projects.all` (na przykład administratorzy platformy) mają
wszystkie uprawnienia projektowe w każdym projekcie. Administratorzy mogą tworzyć kolejne role (zob.
[Role i nadania](administration.md#roles-grants)).

### Dodawanie członka

1. Otwórz projekt, kartę **Członkowie**. Potrzebujesz `project.members.manage` (rola **Właściciel**).
2. W sekcji **Dodaj członka** (**Add member**) wybierz użytkownika lub grupę, wyszukaj po nazwie lub
   adresie e-mail, wybierz rolę i kliknij **Dodaj** (**Add**).
3. Aby zmienić rolę, wybierz inną w kolumnie **Rola** (**Role**). Aby usunąć członka, użyj przycisku
   usuwania w ostatniej kolumnie.

Zmiany obowiązują od następnego żądania członka i są zapisywane w dzienniku audytu. Znajdziesz tylko
użytkowników, którzy istnieją w Back Office; zapraszanie nowych osób to zadanie administratora (zob.
[Administracja](administration.md#users)).

<a id="project-ci"></a>
## CI: punkty wejścia i tokeny

Twój pipeline komunikuje się z Back Office skryptem `backoffice-ci` i tokenem CI projektu. Back Office
nigdy nie uruchamia Twojego CI; robi to wypchnięcie gałęzi. Pełna konfiguracja jest opisana w
[GitLab CI i inne CI](integrations-gitlab-ci.md#ci-overview).

**Punkty wejścia CI** (**CI entrypoints**) przypisują klucz do workflowu: `backoffice-ci run <key>`
w pipeline'ie uruchamia ten workflow z kontekstem CI (merge request, gałąź, commit).

| Kolumna | Znaczenie |
|---|---|
| **Klucz** | Nazwa używana przez pipeline, na przykład `code-review`. |
| **Workflow** | Workflow do uruchomienia. |
| **Tryb** (**Mode**) | **Blokujący** (**Blocking**) oznacza zadanie CI jako nieudane, gdy uruchomienie kończy się porażką; **Doradczy** (**Advisory**) nigdy tego nie robi. |
| **Diff** | Wysyła diff merge requestu lub gałęzi jako artefakt `diff`. |
| **Repozytorium** (**Repository**) | Opcjonalne. Tylko zadania CI z tego repozytorium projektu mogą uruchomić punkt wejścia; puste = dowolne repozytorium projektu. |

Kliknij **Dodaj**, wypełnij wiersz i kliknij **Zapisz punkty wejścia** (**Save entrypoints**) (albo
**Odrzuć zmiany**, **Discard**). Wymaga `project.manage`.

Back Office rozpoznaje, w którym repozytorium działa zadanie CI, po jego URL (wysyłanym przez
`backoffice-ci` od wersji 2.1.0) i przekazuje klucz do uruchomienia jako parametr `repository`. Zadanie
z repozytorium, którego projekt nie zna, jest odrzucane z listą repozytoriów projektu
([GitLab CI i inne CI](integrations-gitlab-ci.md#ci-overview)).

**Tokeny CI** (`bo_ci_…`) pozwalają pipeline'owi uruchamiać workflowy i wysyłać sygnały tylko w tym
projekcie.

1. Kliknij **Utwórz token** (**Create token**), wpisz **Nazwę tokenu** (**Token name**) (na przykład
   `gitlab-ci`) i kliknij **Utwórz** (**Create**).
2. Skopiuj token od razu: jest wyświetlany tylko raz i przechowywany jako skrót (hash).
3. Dodaj go w CI jako chronioną, maskowaną zmienną `BACKOFFICE_CI_TOKEN` (zob.
   [Zmienne CI](integrations-gitlab-ci.md#ci-variables)).
4. Kliknij **Gotowe** (**Done**). Lista pokazuje prefiks oraz daty utworzenia i ostatniego użycia;
   **Unieważnij** (**Revoke**) wyłącza token.

Wymaga `project.ci.manage` (rola **Właściciel** lub **Maintainer**).

**Fragment GitLab CI** (**GitLab CI snippet**) to gotowy fragment `.gitlab-ci.yml` ze zmiennymi do
ustawienia; **Kopiuj** (**Copy**) umieszcza go w schowku. **Wykonania CI** (**CI executions**) pokazują,
co uruchomił Twój pipeline. Strona **CI** na pasku bocznym (`/ci`) pokazuje wykonania CI wszystkich Twoich
projektów; nieudane pojawia się w **Twoja kolej** (zob. [Twoja kolej](your-turn.md#inbox)).

<a id="spec-kit"></a>
### Spec Kit (usunięty)

Back Office nie uruchamia już GitHub Spec Kit (ADR 0063): nie ma przełącznika projektu **Spec Kit**, karty **Spec
Kit** z przyciskiem **Dodaj workflowy Spec Kit** ani ustawienia platformy **Zezwalaj na Spec Kit**. Wbudowane szablony
**Write specification**, **Write plan** i **Write task list** zlecają agentowi kodującemu zapisanie tych samych
dokumentów w repozytorium (`specs/<funkcja>/spec.md`, `plan.md`, `tasks.md`), a **Issue to reviewed MR** ich używa,
więc dostaje go każdy projekt z repozytorium. Kroki dawnych szablonów Spec Kit działają teraz jako te szablony
([Workflowy zapisane przed ADR 0063](workflows.md#task-nodes)). Konstytucja zostaje: to zasady właściciela projektu
dla każdego agenta. Agent nadal może sam użyć Spec Kit, gdy przynosi go Twoje repozytorium albo umiejętności agenta.

<a id="project-connections"></a>
## Połączenia

Karta **Połączenia** (**Connections**) przechowuje poświadczenia, których kroki workflowów projektu używają
do Jiry, w dwóch częściach:

- **Połączenia Jira tego projektu**: **połączenie systemowe**, domyślne dla każdego kroku Jiry, wybierane
  spośród własnych połączeń projektu i połączeń platformy ([Administracja](administration.md#connections)),
  oraz uporządkowana lista kolejnych połączeń, których mogą używać kroki. Krok, którego połączeniu systemowemu
  brakuje możliwości, bierze połączenie z najmniejszymi uprawnieniami z tej listy
  ([Połączenie systemowe](integrations-jira.md#jira-system-connection)). Bez połączenia systemowego kroki
  wybierają spośród wszystkich połączeń projektu i platformy.
- **Połączenia tego projektu**: własne konta Jiry projektu.

- **Dodaj połączenie** (**Add connection**), edycja, **Zastąp token** (**Replace token**), **Testuj**
  (**Test**), **Testuj przed zapisem** (**Test before saving**) i usuwanie wymagają
  `project.connections.manage`: tylko rola **Właściciel** (nie **Maintainer**). Każdy z `project.view`
  widzi listę, ale nigdy tokenów.
- Właściciele dostają kartę w **Twoja kolej**, gdy token połączenia projektu wygasa w ciągu 14 dni
  ([Wygaśnięcie i rotacja tokenu](integrations-jira.md#jira-token-expiry)).
- Przygotuj dwa konta Jiry z dokładnie potrzebnymi uprawnieniami, do odczytu i do zapisu, i dodaj dla
  każdego połączenie: [Zasada najmniejszych uprawnień](integrations-jira.md#jira-least-privilege),
  [Dodaj połączenie](integrations-jira.md#jira-connections).
- **Dozwolone projekty** (**Allowed projects**) ograniczają połączenie do wybranych kluczy projektów Jiry;
  projektant porównuje je z **Kluczem projektu Jira** z karty **Ustawienia**.
- **Testuj** pokazuje brakujące i nadmiarowe uprawnienia Jiry
  ([Przetestuj połączenie](integrations-jira.md#jira-test)).
- Projektanci wybierają dla każdego węzła Jiry, jako kto **działa**: połączenie systemowe, wskazane połączenie
  albo osoba, która go zatwierdza lub rozpoczyna, przez jej własne połączenie osobiste
  ([Działaj jako](integrations-jira.md#jira-act-as)).
- Przy własnym koncie Jiry i braku konta usługowego używaj tokenów API z zakresami
  ([Tokeny API z zakresami](integrations-jira.md#jira-scoped-token)).

Usunięcie projektu usuwa jego połączenia.

<a id="sandbox-policy"></a>
## Polityka sandboxa

Agenci programistyczni działają w sandboxie, którego limity ustawia się warstwami: platforma, pula
runnerów, projekt. Każda warstwa może jedynie zawęzić to, na co pozwalają warstwy wyżej. Karta **Sandbox**
ustawia warstwę projektu i pokazuje wynik. Szablony i kroki workflowu nie mają ustawień sandboxa (ADR 0062):
sandbox jest cechą projektu, więc każde zadanie projektu dostaje te same limity (limit czasu kroku może zadanie
tylko skrócić). Szczegóły:
[Jak działają zadania agentów](agent-execution.md#isolation-levels) i [Flota runnerów](runner-fleet.md#sandbox-policies).

| Sekcja | Co ustawiasz |
|---|---|
| **Izolacja** (**Isolation**) | **Standardowa** (**Standard**; gVisor, domyślna dla kodu pisanego przez model), **Wysoka** (**High**; osobna mikromaszyna wirtualna Kata dla każdego zadania, dla niezaufanych repozytoriów) albo **Deweloperska** (**Dev**; zwykły kontener, tylko do lokalnego developmentu). Poziomy słabsze niż pułap są wyłączone. |
| **Zasoby i czas** (**Resources and time**) | **CPU**, **Pamięć** (**Memory**), **Pamięć tymczasowa (dysk)** (**Ephemeral storage**), **Procesy** (**Processes**), **Limit czasu (minuty)** (**Timeout (minutes)**). |
| **Lista dozwolonych hostów** (**Network allowlist**) | **Dozwolone hosty** (**Allowed hosts**), z którymi agent może się łączyć (`host` lub `host:port`, `*` zastępuje jeden segment nazwy). Brama modeli i hosty Git repozytoriów zadania są dodawane zawsze; wszystko inne jest blokowane i raportowane. |
| **Narzędzia** (**Tools**) | **Dozwolone narzędzia** (**Allowed tools**), **Zabronione narzędzia** (**Denied tools**), **Narzędzia wymagające zgody** (**Tools that ask**) (wzorce poleceń powłoki; zakaz zawsze wygrywa; zadania bez nadzoru traktują „pytaj” jak zakaz). |
| **Środowisko i sekrety** (**Environment and secrets**) | **Obraz runnera** (**Runner image**), **Polecenie przygotowania** (**Setup command**), **Cache** (**Caches**), **Nazwy sekretów** (**Secret names**) (tylko nazwy; wartości rozwiązuje klaster runnerów). |
| **Limity kosztu i tokenów na zadanie** (**Cost and token caps per job**) | **Maks. koszt (USD)** (**Max cost (USD)**) i **Maks. liczba tokenów** (**Max tokens**), egzekwowane przez bramę modeli. |
| **Selektor puli** (**Pool selector**) | **Etykiety, które musi mieć pula runnerów** (**Labels the runner pool must have**), aby kierować zadania projektu do określonych pul. |

Puste pola są dziedziczone. Zablokowane etykiety i podpowiedzi „najwyżej” wynikają z pułapu. **Efektywna
dla tego projektu** (**Effective for this project**) pokazuje, co dziś dostaje zadanie, z odznaką
wskazującą warstwę, która ustawiła każdą wartość. Kliknij **Zapisz politykę sandboxa** (**Save sandbox
policy**) (albo **Odrzuć zmiany**, **Discard changes**). Wymaga `project.manage`; pozostali widzą kartę
tylko do odczytu.

<a id="project-budget"></a>
## Budżet projektu

Karta **Budżet** ogranicza, ile agenci projektu mogą wydać w miesiącu kalendarzowym (UTC). Każde
wywołanie modelu w projekcie, łącznie z krokami workflowów i przeglądami, jest sprawdzane względem
budżetu przed wywołaniem i po nim. Dodatkowo obowiązuje budżet platformy; agentów zatrzymuje najpierw ten
bardziej restrykcyjny.

1. Włącz **Limit miesięczny** (**Monthly limit**) i wpisz **Limit (USD)**.
2. Ustaw **Alert przy (%)** (**Alert at (%)**) (domyślnie 80).
3. Wybierz **Twardą blokadę** (**Hard stop**): włączona oznacza, że po osiągnięciu limitu agenci są
   odrzucani, a ich zadania kończą się błędem `budget-exceeded`; wyłączona oznacza, że praca trwa dalej,
   a przekroczenie jest raportowane.
4. Kliknij **Zapisz budżet projektu** (**Save project budget**).

Góra karty pokazuje **Wydano w miesiącu** (**Spent in**) dla bieżącego miesiąca, pasek ze znacznikiem alertu
i prognozą oraz wiersz **Prognoza** (**Forecast**) na koniec miesiąca. Przekroczenie progu alertu lub
limitu umieszcza kartę budżetu w **Twoja kolej** u każdego, kto może zmienić budżet. Wymaga
`project.manage`; członkowie z `project.usage.view` widzą kartę tylko do odczytu. Zob. też
[Koszty, budżety i wskaźniki](costs-insights.md#budgets).

## Usuwanie projektu

Karta **Ustawienia** → **Usuń ten projekt** (**Delete this project**) → **Usuń projekt KEY** (**Delete
project KEY**). Usuwa to workflowy, tokeny CI, połączenia i historię projektu. Przycisk widzą właściciele. Back Office
odmawia usunięcia, dopóki są aktywne uruchomienia: najpierw je anuluj.
