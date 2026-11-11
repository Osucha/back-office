# Projektowanie workflowów

Workflow to proces dostarczania projektu narysowany jako graf: zadania wykonywane przez agentów, decyzje
na podstawie wyników, bramki, w których decyduje człowiek, oraz oczekiwania na Twoje CI. Ten rozdział
opisuje projektanta, rodzaje węzłów, repozytoria kroków programistycznych, decyzje, ustawienia bramek,
zatwierdzanie przed zapisem zewnętrznym i uruchamianie workflowu.

<a id="designer"></a>
## Projektant workflowów

Otwierasz go z projektu: karta **Workflowy** (**Workflows**) → **Edytuj** (**Edit**) (lub **Podgląd**,
**View**, bez `workflow.edit`) albo **Nowy workflow** (**New workflow**) w nagłówku strony
(`/projects/:id/workflows/new`). Istniejący workflow otwiera się pod adresem `/workflows/:id/edit`.

| Obszar | Co zawiera |
|---|---|
| Nagłówek | Klucz projektu i wersję, nazwę workflowu, znacznik stanu (**poprawny**, błędy, ostrzeżenia lub **tylko do odczytu**), przełącznik **Projektant** / **JSON** (**Designer** / **JSON**), **Szablon** (**Template**; tylko nowe workflowy), cofnij i ponów, **Uporządkuj układ** (**Tidy layout**), **Waliduj** (**Validate**) oraz przyciski pokazujące lub ukrywające paletę i właściwości. |
| Paleta (po lewej) | Węzły **Sterowanie** (**Control**), **Akcje** (**Actions**; operacje Jiry i Back Office bez modelu, zob. [Węzły akcji](#actions)) i **Zadania** (**Tasks**) z biblioteki zadań, pogrupowane według kategorii (Analiza, Specyfikacja, ...), z polem wyszukiwania. Szablon należący do projektu ma ikonę projektu. |
| Kanwa | Graf. Przyciski powiększenia, **Czytelny widok od START** (**Readable view from START**), **Dopasuj wszystko** (**Fit everything**) i, od ośmiu węzłów, mapa poglądowa (**Pokaż mapę** / **Ukryj mapę**, **Show the overview** / **Hide the overview**). |
| Właściwości (po prawej) | Zaznaczony węzeł lub krawędź; gdy nic nie jest zaznaczone: **Opis** (**Description**) workflowu, przełącznik **Włączony** (**Enabled**) i **Wyzwalacze** (**Triggers**). |
| Arkusz **Walidacja** (**Validation**; na dole) | Błędy i ostrzeżenia pogrupowane według węzłów; kliknij pozycję, aby przejść do węzła. |
| Pasek akcji | **Zamknij** (**Close**), **Duplikuj workflow** (**Duplicate workflow**) oraz **Utwórz workflow** (**Create workflow**) lub **Zapisz jako nową wersję** (**Save as new version**) (`Ctrl S`). |

### Kroki: tworzenie lub zmiana workflowu

1. Dla nowego workflowu nadaj nazwę i opcjonalnie wybierz **Szablon**: **Issue to reviewed MR**, **CI code
   review** lub **Pusty (START + END)** (**Empty (START + END)**).
2. Dodaj węzły: przeciągnij je z palety na kanwę albo kliknij element palety (lub naciśnij na nim
   `Enter`).
3. Połącz węzły: przeciągnij od portu wyjściowego jednego węzła do innego węzła. Z każdego portu
   wyjściowego wychodzi najwyżej jedna krawędź. Pętle do wcześniejszych węzłów to zwykłe krawędzie.
4. Zaznacz węzeł, aby edytować go we **Właściwościach** (zob. niżej). Zaznacz krawędź, aby nadać jej
   **Etykietę** (**Label**) lub ją usunąć przyciskiem **Usuń krawędź** (**Delete edge**).
5. Popraw błędy z arkusza **Walidacja**, od razu albo później. Workflow zapisany z błędami zostaje **wersją
   roboczą** („2 błędy: zapis zachowa wersję roboczą, przebiegi nie wystartują do czasu poprawienia.”): każdy zapis
   jest jak zwykle wersją, ale z wersji roboczej nie startuje żaden przebieg (ręcznie, z CI ani z monitora Jira) i nie
   można jej opublikować w bibliotece. Lista workflowów projektu oznacza ją **wersja robocza (z błędami)**. Ostrzeżenia
   niczego nie blokują.
6. Kliknij **Utwórz workflow** lub **Zapisz jako nową wersję**.

Każdy zapis tworzy nową wersję. Uruchomienia zachowują wersję, z którą wystartowały, więc edycja nigdy
nie zakłóca pracy w toku. Wyłączenie **Włączony** blokuje nowe uruchomienia (**Uruchom**, **Start run**,
jest nieaktywne); uruchomienia w toku trwają dalej. Widok **JSON** pokazuje pełną definicję; zmiany
wprowadzone w nim obowiązują, gdy tylko JSON da się sparsować (**Kopiuj JSON**, **Formatuj JSON**).

### Skróty w projektancie

| Klawisze | Akcja |
|---|---|
| `[` / `]` | Pokaż lub ukryj paletę / właściwości |
| `Ctrl Z`, `Ctrl Shift Z` lub `Ctrl Y` | Cofnij, ponów |
| `Ctrl D` | Duplikuj zaznaczony węzeł |
| `Delete` | Usuń zaznaczony węzeł lub krawędź |
| Strzałki | Przesuń zaznaczenie wzdłuż krawędzi |
| `Esc` | Wyczyść zaznaczenie |
| `Ctrl S` | Zapisz |

<a id="default-workflows"></a>
### Domyślne workflowy

Każdy nowy projekt z repozytorium dostaje te workflowy (projekt bez repozytorium dostaje zamiast nich
workflow startowy **LLM task with review**, zob. [Projekty bez repozytorium](projects.md#no-repository)):

- **Issue to reviewed MR**: pobiera zgłoszenie, pisze specyfikację, sprawdza ją (bramka pyta Cię tylko
  wtedy, gdy pewność agenta jest niższa niż 0.8), planuje, tworzy listę zadań, implementuje, czeka na
  sygnał CI `tests`, przegląda kod, a potem bramka **Merge?** na zasadzie czterech oczu i zmiana statusu
  w Jirze **Move issue to In Review**, którą człowiek musi zatwierdzić
  ([Zatwierdzanie przed zapisem zewnętrznym](#write-confirmations)).
- **CI code review**: uruchamiany z Twojego pipeline'u przez punkt wejścia `code-review`; przegląda
  przesłany diff i zalicza albo oblewa zadanie CI.
- **Change request (delta)**: analiza wpływu, historyjka użytkownika i lista zadań, każdy ważny krok za
  bramką z listą kontrolną, a następnie implementacja, CI, przegląd i bramka scalenia.
- **Reference: one Claude Code session**: pobiera zgłoszenie, potem jeden krok zadania (szablon **Agent task**,
  `agent-task`, z agentem **Claude Code**, z wypchnięciem i merge requestem), który czyta repozytorium, implementuje
  zgłoszenie od początku do końca, uruchamia build i testy i otwiera merge request, a następnie bramka **Merge?** na
  zasadzie czterech oczu (**revise** odsyła Twój komentarz do kroku). To punkt odniesienia: przepuść te same
  zgłoszenia przez niego i przez tańsze workflowy i porównaj ich koszt zaakceptowanego wyniku
  ([Agenci: koszt i skuteczność](costs-insights.md#agent-insights)).

**Issue to reviewed MR** powstaje tylko wtedy, gdy Spec Kit jest włączony ([Spec Kit](projects.md#spec-kit)).

**Projekty utworzone, zanim istniał dany workflow domyślny**, nie dostają go automatycznie. Gdy projektowi
z repozytorium brakuje workflowów domyślnych, jego karta **Workflowy** (**Workflows**) proponuje **Dodaj brakujące
workflowy domyślne (N)** (**Add missing default workflows (N)**, `workflow.edit`): dodaje brakujące po nazwie, na
przykład **Reference: one Claude Code session**, i nigdy nie zastępuje istniejącego workflowu o tej samej nazwie.
Workflowy Spec Kit dodaje się zamiast tego z karty **Spec Kit** ([Spec Kit](projects.md#spec-kit)).

<a id="nodes"></a>
## Rodzaje węzłów

| Węzeł | Przeznaczenie | Porty wyjściowe |
|---|---|---|
| **Start** | Wejście; dokładnie jeden na workflow. | `out` |
| **Zadanie** (**Task**) | Wykonuje szablon zadania z biblioteki za pomocą agenta (zob. niżej). | `success`, `failure` |
| **Akcja** (**Action**) | Wykonuje stałą operację bez modelu: Jira, uruchamianie workflowów, podsumowanie projektu (zob. [Węzły akcji](#actions)). | `success`, `failure` |
| **Decyzja** (**Decision**) | Kieruje uruchomienie według warunków na stanie uruchomienia (zob. [Decyzje](workflows.md#decisions)). | jeden na gałąź plus domyślny |
| **Bramka decyzyjna** (**Human gate**) | Zatrzymuje uruchomienie, dopóki zatwierdzający nie wybierze opcji (zob. [Ustawienia bramki](workflows.md#gates-config)). | jeden na opcję (domyślnie `approve`, `revise`, `reject`) plus `timeout` |
| **Oczekiwanie na CI** (**Wait for CI**) | Zatrzymuje uruchomienie, dopóki Twoje CI nie wyśle sygnału. | `success`, `fail`, `timeout` |
| **Koniec** (**End**) | Kończy uruchomienie wynikiem **Sukces** (**Success**) lub **Porażka** (**Failure**). W uruchomieniu wystartowanym z CI rozstrzyga o bramce CI. | brak |
| **Notatka** (**Note**) | Karteczka; nigdy się nie wykonuje, nie można jej połączyć. | brak |

Każdy węzeł ma **Nazwę** (**Name**) i **Id** (slug; niezmienny po zapisie, bo odwołują się do niego
uruchomienia i logi). Węzły zadania, akcji, decyzji, bramki i CI mają też **Maks. liczbę wizyt** (**Max
visits**; ochrona przed pętlą, domyślnie 3) i **Limit czasu (s)** (**Timeout (s)**). Gdy węzeł miałby
wystartować więcej razy, niż pozwala **Maks. liczba wizyt**, uruchomienie kończy się niepowodzeniem.

**Niepołączone porty.** Wymagany port musi być połączony: `success` zadania lub akcji, każda gałąź decyzji,
pierwsza opcja bramki, `success` oczekiwania na CI, `out` węzła Start. Jeśli uruchomienie wychodzi przez
niepołączony port `failure`, `fail` lub `timeout`, kończy się niepowodzeniem; przez niepołączony
`reject` — zostaje anulowane. Walidacja ostrzega o pozostałych niepołączonych portach.

<a id="task-nodes"></a>
### Węzły zadań

Krok zadania łączy trzy rzeczy: **szablon** (co zrobić), **agenta** (kto pracuje i jak) oraz wartości
**parametrów** szablonu ([Biblioteka zadań](task-library.md#agents)).

Wybierz **Szablon** (przestarzałe szablony nie są proponowane; krok, który już go używa, ma znacznik
**Przestarzały**, **Deprecated**; szablony ze znacznikiem **wymaga parametrów**, **needs parameters**, oczekują
wartości w kroku). Pod nim widać rodowód szablonu, agenta i kategorię oraz **Otwórz w bibliotece** (**Open in
library**).

**Parametry** (**Parameters**; widoczne, gdy szablon je ma) pokazują [parametry](task-library.md#parameters)
szablonu jako formularz: pola tekstowe, liczby, przełącznik dla **Tak / nie**, listę dla **Wyboru**. Puste oznacza
wartość szablonu albo domyślną, pokazaną na szaro. Wymagany parametr bez żadnej wartości to błąd walidacji: workflow
da się zapisać tylko jako wersję roboczą, a uruchomienie nie wystartuje.

**Co dostaje ten krok** (**What this step gets**, zwinięte) pokazuje szablon z nadpisaniami tego kroku: agenta,
profil modelu, prompt systemowy i użytkownika, reguły i umiejętności (te agenta), konfigurację i dane wejściowe
przebiegu, dokładnie to, co otrzymuje model lub agent kodujący, zanim przebieg wypełni wartości. **Ostrzeżenia**
(**Warnings**) w tym miejscu nie zatrzymują kroku, na przykład umiejętność wymagana przez szablon (**Wymagane
umiejętności**, **Required skills**), której agent kroku nie ma.
**Nadpisania** (**Overrides**) zmieniają szablon tylko dla tego węzła:

| Nadpisanie | Skutek |
|---|---|
| **Agent** | Kto wykonuje ten krok zamiast agenta szablonu: **Odpowiedź modelu**, **Analyst**, **Reviewer**, **OpenCode**, **Claude Code** albo inny agent z Twojej biblioteki ([Agenci](task-library.md#agents)). Puste = agent szablonu. Z agentem przychodzą jego instrukcje, reguły, umiejętności, serwery MCP i dostęp. Krok pokazuje problem, gdy agent nie pasuje do szablonu (na przykład faza Spec Kit albo `push` z **Odpowiedzią modelu** albo agent z dostępem **Tylko odczyt** w kroku, który wypycha). |
| **Katalog** (**Directory**) | Tylko agenci kodujący: **Katalog przebiegu (wspólny)** (**Run directory (shared)**, domyślnie) albo **Osobny katalog** (**Separate directory**) ([Katalog przebiegu](#run-directory)). |
| **Sesja agenta** (**Agent session**) | Tylko agenci kodujący: **Własna (powtórzenia kontynuują)** (**Own (repeats continue)**, domyślnie), **Nowa za każdym razem** (**New each time**) albo **Kontynuuj sesję kroku …** (**Continue the session of step …**) ([Sesje agenta](#continue-session)). |
| **Agent może zadawać pytania** (**Agent may ask questions**), **Rundy pytań** (**Question rounds**) | Domyślnie włączone, 2 rundy: agent może zatrzymać się z pytaniami, a przebieg czeka na Twoje odpowiedzi ([Agent zadaje pytania](#agent-questions)). |
| **Profil modelu** (**Model profile**) | Model dla tego węzła zamiast modelu agenta, szablonu lub ustawień domyślnych. |
| **Prompt użytkownika** (**User prompt**) | **Dziedziczony** (**Inherited**), **Dopisz** (**Append**; dodawany po prompcie szablonu) lub **Zastąp** (**Replace**). |
| **Dopisek do promptu systemowego** (**System prompt append**) | Dodawany do promptu systemowego szablonu. |
| **Konfiguracja** (**Config**) | Ustawienia dla agenta kroku, takie jak polecenie weryfikacji, nazwa gałęzi czy opcje merge requestu; scalane z konfiguracją szablonu. |

Krok nie dodaje już ani nie pomija umiejętności swojego agenta (ADR 0060): środowisko agenta ustawia się raz, na
agencie. Kroki zapisane z taką zmianą działają z umiejętnościami agenta; gdy krok potrzebuje innych umiejętności,
wybierz albo utwórz agenta, który je ma.

Agent kodujący był dawniej kluczem konfiguracji `agent` (`CLAUDE_CODE`, `OPENCODE`); ten klucz już nie istnieje.
Zapisane workflowy działają dalej: `agent` kroku jest odczytywany jako jego **Agent** (**Claude Code**, **OpenCode**),
a następny zapis utrwala go w tej postaci.

**Szablony Spec Kit** (**Spec Kit: specify**, **plan**, **tasks** i szablony, które je rozszerzają) pojawiają się
w palecie tylko wtedy, gdy projekt ma włączony [Spec Kit](projects.md#spec-kit); w przeciwnym razie krok Spec Kit
jest błędem walidacji, a uruchomienia nie startują.

<a id="actions"></a>
### Węzły akcji

Akcje to stałe operacje, które nigdy nie wywołują modelu (ADR 0056). Paleta wyświetla je we własnej grupie
**Akcje** (**Actions**); każda ma swoje ustawienia, wartości domyślne, dane wejściowe uruchomienia, o które prosi,
oraz porty `success` i `failure`.

| Akcja | Robi |
|---|---|
| **Pobierz zgłoszenie** (**Fetch issue**) | Wczytuje zgłoszenie Jira przebiegu do artefaktu `issue` i `state.issue`; przy starcie uruchomienia prosi o **Klucz zgłoszenia Jira**. |
| **Skomentuj zgłoszenie** (**Comment on issue**) | Dodaje komentarz do zgłoszenia po zatwierdzeniu przez człowieka ([Zatwierdzanie przed zapisem zewnętrznym](#write-confirmations)). |
| **Zmień status zgłoszenia** (**Transition issue**) | Przenosi zgłoszenie do statusu po zatwierdzeniu przez człowieka. |
| **Uruchom workflow** (**Start workflow**) | Uruchamia inne workflowy projektu (zob. niżej). |
| **Podsumowanie projektu** (**Project digest**) | Zapisuje artefakt `digest` (zob. niżej). |

Akcje Jiry mają sekcję **Połączenie Jira** z ustawieniem **Działaj jako**: połączenie systemowe projektu, wskazane
połączenie albo osoba, która zatwierdza zapis lub rozpoczyna uruchomienie
([Działaj jako](integrations-jira.md#jira-act-as)); paleta pokazuje możliwości, których każda wymaga, i oznacza dwie
zapisujące w Jirze. Ich ustawienia opisuje rozdział [Akcje Jiry w workflowach](integrations-jira.md#jira-tasks).

Dawniej były to szablony zadań (`fetch-issue`, `notify-jira`, `transition-issue`, `start-workflow`,
`project-digest`). Zapisane workflowy, które ich używają, działają dalej: taki krok jest odczytywany jako akcja
z wartościami domyślnymi dawnego szablonu i konfiguracją kroku (prompt **Zastąp** kroku **Notify Jira** staje się
komentarzem), a następny zapis utrwala akcję.

Dwie akcje to operacje Back Office (ADR 0052):

- **Uruchom workflow** (**Start workflow**) uruchamia inny workflow projektu, jedno uruchomienie na każdy element
  artefaktu JSON (**Parametry z** (**Parameters from**), na przykład `proposals` napisane przez agenta), a pola
  elementu stają się parametrami uruchomienia (`title`, `idea`, ...). Musi stać bezpośrednio za bramką: agent
  proponuje, Ty akceptujesz, Back Office uruchamia. Przy **Wyborze** (**Selection**) `DECISION` uruchamiane są tylko
  elementy, których numery wpiszesz w komentarzu na bramce (`1, 3`; `all` uruchamia wszystkie); przy `ALL` wszystkie.
  Uruchomione przebiegi są wymienione w jego artefakcie `started-runs`. Przebiegi są uruchamiane w imieniu osoby, która podjęła decyzję na bramce
  (obowiązują jej uprawnienia i osobiste profile modeli).
- **Podsumowanie projektu** (**Project digest**) zapisuje artefakt `digest`: co działo się w projekcie od parametru
  uruchomienia `since` (dostaje go uruchomienie z harmonogramu) - uruchomienia innych workflowów z wynikiem, czasem
  i kosztem, kroki uruchomione więcej niż raz albo nieudane wraz z błędami, decyzje na bramkach z komentarzami oraz
  najnowsze pipeline'y **Gałęzi pipeline'ów** (**Pipeline branches**) z nieudanymi jobami (wymaga tokenu
  repozytorium z CI_READ). Krok agenta po nim czyta `{{artifacts.digest}}`.

Prompty są szablonami: `{{issue.summary}}`, `{{artifacts.spec}}`, `{{feedback}}` (wszystkie komentarze
z poprawek), `{{project.constitution}}`, `{{mr.url}}`. Brakująca wartość jest wstawiana jako pusta
i dodaje ostrzeżenie do logu uruchomienia. Ponieważ węzły odwołują się do szablonów po kluczu, zmiana
szablonu zmienia każdy workflow, który go używa, od jego następnego uruchomienia (zob.
[Biblioteka zadań](task-library.md#templates)).

### Węzły oczekiwania na CI

Ustaw nazwę **Sygnału** (**Signal**) (na przykład `tests`) i opcjonalny **Limit czasu (sekundy)**
(**Timeout (seconds)**). Twój pipeline wysyła `backoffice-ci signal tests --status success|fail`;
uruchomienie wychodzi przez `success` lub `fail` albo przez `timeout`. Zob.
[GitLab CI](integrations-gitlab-ci.md#ci-overview).

Po włączeniu **Obserwuj pipeline** (**Watch pipeline**) Back Office sam obserwuje pipeline, a Twoje CI nic nie wysyła:
co minutę odczytuje najnowszy pipeline gałęzi, którą uruchomienie wypchnęło (w **Repozytorium** kroku, puste = główne),
tokenem projektu tylko do odczytu z uprawnieniem CI_READ
([Tokeny dostępu do repozytoriów](integrations-git.md#git-project-tokens)). Gdy pipeline się powiedzie, uruchomienie
wychodzi przez `success`; gdy się nie powiedzie, przez `fail`, a następny krok dostaje nieudane joby (poza tymi, którym
wolno się nie udać) z ostatnimi liniami ich logów jako uwagi do poprawki. Połącz `fail` z powrotem z krokiem
implementacji, a agent poprawi to, co zgłasza CI; **Maks. odwiedzin** kroku ogranicza pętlę. Krok implementacji, który
kończy dziennik pracy linią `RESULT: PARTIAL` (wszystko, co dało się sprawdzić w sandboxie, przeszło, reszta wymaga
CI), powinien stać przed takim węzłem.

<a id="step-repositories"></a>
## Repozytoria kroku

Kroki programistyczne (kroki zadań wykonywane przez agenta kodującego, **OpenCode** lub **Claude Code**, także fazy
Spec Kit) pracują w repozytoriach Git projektu ([Repozytoria](projects.md#repositories)). Pozostałe kroki
(odpowiedzi modelu, akcje, bramki, oczekiwanie na CI) nie używają repozytoriów, więc ich właściwości nie mają
sekcji repozytoriów.

Zaznacz krok programistyczny; jego właściwości mają sekcję **Repozytoria** (**Repositories**):

| Ustawienie | Znaczenie |
|---|---|
| **Użyj głównego repozytorium** (**Use the primary repository**; domyślnie) | Krok pracuje w głównym repozytorium projektu, z dostępem **Odczyt**, gdy **push** jest wyłączony, i **Wypchnięcie i merge request**, gdy jest włączony. Tak działa każdy workflow napisany dla jednego repozytorium. |
| Lista repozytoriów | **Dodaj repozytorium** (**Add repository**) wybiera repozytorium projektu po kluczu (z jego przeznaczeniem i URL); dodaj tyle, ile krok potrzebuje. Usuwasz je przyciskiem usuwania. |
| **Dostęp** (**Access**; dla każdego repozytorium) | **Odczyt** (**Read**): agent może sklonować i czytać repozytorium, nic nie jest wypychane. **Wypchnięcie i merge request** (**Push and merge request**): zmiany agenta są wypychane na nową gałąź i otwierany jest merge request do gałęzi domyślnej repozytorium. |

**Najmniejsze uprawnienia.** Daj każdemu repozytorium najniższy dostęp, jakiego krok potrzebuje: **Odczyt**
dla kontraktu, materiałów referencyjnych czy dokumentacji, do których agent tylko zagląda, a **Wypchnięcie
i merge request** tylko tam, gdzie musi coś zmienić. Runner dostaje token tylko do odczytu dla repozytoriów
z dostępem **Odczyt**, a token do wypychania tylko dla repozytoriów z dostępem **Wypchnięcie i merge
request** ([Dostęp agentów do gita](integrations-git.md#git-least-privilege)).

**Nigdy nie scala.** Agenci wypychają gałąź i otwierają merge request; nigdy nie scalają, nigdy nie
wypychają do gałęzi domyślnej i nigdy nie wypychają z wymuszeniem (force push). Scala człowiek (albo reguły
zatwierdzania Twojego serwera Git). Gdy krok wypycha do kilku repozytoriów, w każdym używa tej samej nazwy
gałęzi i każde dostaje własny merge request.

**Katalogi.** Każde repozytorium jest klonowane do osobnego katalogu nazwanego jego kluczem:
`/workspace/service`, `/workspace/docs`, … (w [katalogu przebiegu](#run-directory) albo we własnym katalogu kroku,
gdy pracuje on w **Osobnym katalogu**). Agent zaczyna w głównym repozytorium kroku (w głównym
repozytorium projektu, jeśli krok go używa, w przeciwnym razie w pierwszym z listy) i dostaje informację,
jakie repozytoria istnieją, gdzie są, jakie mają przeznaczenie i gałąź bazową oraz czy może je zmieniać.

**Źródła z biblioteki.** W części **Źródła z biblioteki (tylko odczyt)** krok zaznacza źródła
[biblioteki](task-library.md#sources): standardy, szablony, materiały referencyjne wspólne dla wszystkich
projektów. Każde jest klonowane tylko do odczytu do `/workspace/lib-<key>`, a agent dostaje polecenie, by
się do niego stosował, a nie je zmieniał. Zaznaczenie źródła, gdy krok używa głównego repozytorium,
przełącza krok na własną listę, zaczynającą się od głównego repozytorium; powrót do **Użyj głównego
repozytorium** usuwa zaznaczone źródła.

**Komunikaty walidacji** (w arkuszu **Walidacja** i przy starcie uruchomienia):

| Komunikat dotyczy | Co zrobić |
|---|---|
| projekt nie ma repozytorium | Dodaj repozytorium do projektu albo użyj kroku, który nie jest programistyczny. |
| brak głównego repozytorium i brak repozytoriów w kroku | Dodaj repozytoria do kroku albo oznacz jedno repozytorium projektu jako **Główne**. |
| nieznany klucz repozytorium | Komunikat podaje klucze projektu; wybierz jeden z nich (repozytorium mogło zostać usunięte lub przemianowane). |
| repozytorium wymienione dwa razy | Usuń duplikat. |
| dostęp inny niż odczyt lub wypchnięcie i merge request | Popraw wartość (w widoku **JSON**: `READ` lub `PUSH_MR`). |
| wypchnięcie i merge request przy wyłączonym **push** | Włącz **push** w **Konfiguracji** albo ustaw dostęp **Odczyt**. |
| repozytoria w kroku, który nie jest programistyczny | Usuń je; repozytoriów używają tylko kroki programistyczne. |
| (ostrzeżenie) jeden krok wypycha do repozytorium dokumentacji i repozytorium kodu źródłowego | Dozwolone; sprawdź, czy jeden agent rzeczywiście ma zmieniać oba. |

Błędy blokują zapis. Uruchomienie, którego kroki mają takie błędy (na przykład repozytorium usunięto
z projektu po zapisaniu workflowu), nie wystartuje; komunikat wskazuje krok. W widoku **JSON** ustawienie to
`config.repositories: [{"key": "docs", "access": "READ"}]`.

<a id="run-directory"></a><a id="workspaces"></a>
## Katalog przebiegu i sesje agentów

Każdy przebieg ma jeden **katalog przebiegu** (ADR 0060). Kroki kodujące przebiegu pracują w nim jeden po drugim:
każdy krok widzi, co zostawiły wcześniejsze kroki, także niezacommitowaną pracę, a agenci trzymają tam swoje sesje.
Niczego nie definiujesz ani nie nazywasz: *implementacja* → *weryfikacja* → z powrotem do *implementacji* po prostu
kontynuują nawzajem swoją pracę.

**Polecenie przygotowania.** We właściwościach workflowu (kliknij puste płótno) **Polecenie przygotowania**
(**Setup command**), na przykład `npm ci`, wykonuje się raz w katalogu przebiegu przed pierwszym krokiem kodującym,
a ponownie tylko po zmianie. Puste = bez przygotowania. Własne `setupCommand` kroku w **Konfiguracji** nadal wykonuje
się przed tym krokiem.

**Katalog kroku.** Krok kodujący (wykonywany przez agenta kodującego) ma w **Nadpisaniach** pole **Katalog**
(**Directory**):

| Wybór | Znaczenie |
|---|---|
| **Katalog przebiegu (wspólny)** (**Run directory (shared)**, domyślnie) | Krok pracuje w katalogu przebiegu. Jego repozytoria są tam klonowane raz; późniejsze kroki używają tych klonów i wszystkiego, co w nich zostało. |
| **Osobny katalog** (**Separate directory**) | Świeża kopia tylko repozytoriów tego kroku, usuwana po kroku. Do izolacji: agent, który nie może widzieć reszty pracy przebiegu (albo, z innymi **Repozytoriami**, w ogóle kodu). Taki krok zawsze zaczyna nową sesję agenta. |

**Agenci na zmianę.** Na przykład *implementacja* (push włączony) → *weryfikacja* (push wyłączony) → decyzja →
z powrotem do *implementacji*. Każdy agent kontynuuje własną sesję i widzi, co zostawił drugi: weryfikujący czyta
niezacommitowane zmiany implementującego, implementujący znajduje notatki weryfikującego. Krok bez pushu zostawia
swoje zmiany niezacommitowane dla następnego kroku; krok wypychający commituje wszystko w swoich repozytoriach
i wypycha gałąź uruchomienia.

**Co dzieje się na runnerze.** Katalog przebiegu żyje na runnerze, który wykonał pierwszy krok kodujący, jako
wolumen Dockera albo żądanie wolumenu (PVC) w Kubernetes; kroki kodujące przebiegu wykonują się tam jeden po drugim.
Przed każdym krokiem runner sprawdza każde ponownie użyte repozytorium i zapisuje jego konfigurację gita od nowa, więc
nic, co poprzedni agent podłożył w `.git`, się nie uruchomi.

**Po zakończeniu uruchomienia** (udanym, nieudanym lub anulowanym) katalog przebiegu jest usuwany; po nieudanym
uruchomieniu zostaje na 24 godziny, żeby **Ponów** (**Retry**) kontynuował w nim. Katalog przebiegu użyty później
zaczyna pusty (wypchnięta gałąź uruchomienia zostaje).

**Uwaga na zaufanie.** Wszystkie kroki przebiegu mogą czytać i zmieniać wszystko w katalogu przebiegu, także domy
i historię sesji innych agentów. Do niezależnej weryfikacji użyj kroku z **Osobnym katalogiem**.

**Gdzie są pliki agenta.** Każdy agent przebiegu ma w katalogu przebiegu swój **dom** (agent home), poza każdym
repozytorium: dokument środowiska, umiejętności, konfigurację MCP i sesje ([Środowisko agenta](task-library.md#environment)).
Nic z agenta nie jest zapisywane w repozytorium, więc dwaj agenci w jednym katalogu przebiegu nigdy nie widzą swoich
umiejętności ani pamięci. Własne `AGENTS.md` i `CLAUDE.md` repozytorium to reguły projektu dla każdego agenta.

**Dawne przestrzenie robocze.** Do ADR 0060 projekt definiował przestrzenie robocze uruchomień (karta projektu
**Przestrzenie robocze**, **Workspaces**), kroki wybierały jedną z nich, a płótno rysowało wokół nich obszar. Już ich
nie ma, a istniejące workflowy zostały przeniesione: polecenie przygotowania przestrzeni stało się **Poleceniem
przygotowania** workflowów, których kroki z niej korzystały, a jej źródła stały się **Repozytoriami** tych kroków,
które nie miały własnych. Zapisana przestrzeń kroku jest pomijana (każdy krok pracuje w katalogu przebiegu); kroki,
które współdzieliły rolę agenta, nadal współdzielą sesję. Wbudowane zadanie **Prepare workspace**
(`prepare-workspace`, przestarzałe) klonuje repozytoria kroku do katalogu przebiegu i wykonuje przygotowanie bez
agenta; nie jest potrzebne, pierwszy krok kodujący też przygotowuje katalog przebiegu.

<a id="continue-session"></a>
### Sesje agenta

Krok kodujący ma w **Nadpisaniach** pole **Sesja agenta** (**Agent session**):

- **Własna (powtórzenia kontynuują)** (**Own (repeats continue)**, domyślnie): pierwsze wykonanie kroku zaczyna
  sesję; gdy krok wykonuje się ponownie w tym samym przebiegu (poprawki po przeglądzie, **Popraw** (**Revise**) na
  bramce, odpowiedzi na jego pytania), agent kontynuuje własną rozmowę i pamięta, co zrobił.
- **Nowa za każdym razem** (**New each time**): każde wykonanie zaczyna nową rozmowę.
- **Kontynuuj sesję kroku …** (**Continue the session of step …**): wybierz wcześniejszy krok kodujący. Ten krok
  kontynuuje sesję agenta tamtego kroku, na przykład *implementacja*, potem *popraw to, co znalazł przegląd*, a agent
  zachowuje kontekst bez ponownego czytania repozytorium (ADR 0057).

Kontynuowana sesja wymaga **tego samego agenta**: sesja jest przechowywana w domu agenta. Lista wyboru wyszarza kroki
innego agenta i kroki z **Osobnym katalogiem**. Agent wznawia rozmowę (Claude Code `--resume`, OpenCode `--continue`)
i widzi pliki, które zostawił. Jego reguły i instrukcje są w środowisku, nie w prompcie, więc nie dostaje ich drugi
raz. Projektant, a potem jeszcze start uruchomienia, odrzucają krok, którego **Kontynuuj sesję kroku …** wskazuje
krok, który nie istnieje albo nie jest krokiem agenta kodującego; w widoku **JSON** ustawienia to `config.session`
(`OWN`, `NEW`, `CONTINUE`) z `config.continueSession: <id węzła>` oraz `config.directory` (`RUN`, `SEPARATE`).

<a id="agent-questions"></a>
### Agent zadaje pytania

Gdy agent nie wykona dobrze kroku bez Twoich odpowiedzi, zatrzymuje się z pytaniami zamiast zgadywać (ADR 0060).
Agent kodujący zapisuje je w pliku `agent-questions.json` w swoim katalogu roboczym (zbierany, nigdy nie trafia do
commita); odpowiedź modelu odpowiada `{"agentQuestions": [...]}` zamiast swojej odpowiedzi. Każdy agent dostaje
instrukcję, jak pytać.

Przebieg wtedy czeka: krok pokazuje **czeka na Twoje odpowiedzi** (**waiting for your answers**), a na stronie
**Twoja kolej** (**Your turn**) pojawia się karta **Agent pyta** (**The agent asks**) z pytaniami (każde z powodem agenta, **Dlaczego:**)
i rundą, na przykład **Runda 1 z 2** (**Round 1 of 2**) ([Odpowiadanie na pytania agenta](your-turn.md#agent-questions)).
**Odpowiedz i kontynuuj** (**Answer and continue**) wykonuje krok ponownie: Twoje odpowiedzi trafiają do jego promptu
(sekcja „Answers of the owner”), a agent kodujący kontynuuje własną sesję. **Anuluj krok** (**Cancel the step**)
kończy krok niepowodzeniem i przebieg idzie jego ścieżką `failure`.

| Ustawienie (w **Nadpisaniach**) | Znaczenie |
|---|---|
| **Agent może zadawać pytania** (**Agent may ask questions**) | Domyślnie włączone. Wyłączone: agent dostaje informację, że nie może pytać, i ma zapisać otwarte pytania w swoim wyniku. Uruchomienia wystartowane przez CI zostawiają je włączone i czekają jak na bramce. |
| **Rundy pytań** (**Question rounds**) | Domyślnie 2 (1-10): ile razy krok może zatrzymać się z pytaniami. Agent, który pyta znowu po ostatniej rundzie, kończy krok niepowodzeniem; jego pytania trafiają do logu kroku. |

<a id="decisions"></a>
## Decyzje

Węzeł **Decyzja** ma **Gałęzie** (**Branches**), każdą z id (jej portem), etykietą i wyrażeniem. Gałęzie
są sprawdzane po kolei; wygrywa pierwsza prawdziwa. Jeśli żadna nie jest prawdziwa, uruchomienie wychodzi
przez **Gałąź domyślną** (**Default branch**); jeśli jest pusta, uruchomienie kończy się niepowodzeniem.

Wyrażenia to warunki tylko do odczytu na stanie uruchomienia. Edytor sprawdza każde z nich podczas
pisania i oznacza je jako poprawne albo wyjaśnia problem. **Pola stanu dostępne w wyrażeniach** (**State
fields available in expressions**) wymienia wszystkie pola.

| Pole | Zawiera |
|---|---|
| `confidence` | Ostatnią pewność zgłoszoną przez zadanie (0–1) albo nic. |
| `lastOutcome` | `SUCCESS` lub `FAILURE` poprzedniego węzła. |
| `review.verdict`, `review.score`, `review.blockers` | Ostatni przegląd kodu. |
| `artifacts['spec']` | Artefakt albo nic, jeśli nie został wytworzony. |
| `issue.priority`, `issue.labels` | Zgłoszenie z Jiry, po zadaniu pobierającym. |
| `input.ci.mergeRequest.targetBranch` | Kontekst CI uruchomienia wystartowanego z CI. |
| `ci.status`, `decision.option` | Ostatni sygnał CI i ostatnia decyzja w bramce. |

Przykłady: `confidence >= 0.8`, `review.verdict != 'REQUEST_CHANGES'`,
`review.blockers == 0 and review.score >= 70`, `input.ci.mergeRequest.targetBranch == 'main'`.

Pola są puste, dopóki coś ich nie wypełni: `confidence >= 0.8` jest fałszywe, zanim jakiekolwiek zadanie
zgłosi pewność.

<a id="gates-config"></a>
## Ustawienia bramki

Zaznacz węzeł **Bramka decyzyjna**, aby ustawić:

| Ustawienie | Znaczenie |
|---|---|
| **Pytanie** (**Question**) | O co pytany jest zatwierdzający; może używać wartości szablonowych, takich jak `{{issue.key}}`. |
| **Opcje (porty)** (**Options (ports)**) | Przyciski decyzji i porty wyjściowe, domyślnie `approve`, `revise`, `reject`. |
| **Pokazywane artefakty** (**Show artefacts**) | Nazwy artefaktów wyświetlanych i edytowalnych w panelu bramki (na przykład `spec`, `review`, `diff`). |
| **Limit czasu (sekundy)** | Po tym czasie bramka wygasa, a uruchomienie wychodzi przez `timeout`. |

**Kto rozstrzyga ten krok** (**Who decides this step**) określa, kto może rozstrzygać, w każdym
uruchomieniu workflowu (przypisanie na poziomie definicji; decyduje o nim kierownik projektu,
projektując workflow):

| Ustawienie | Znaczenie |
|---|---|
| **Role projektowe** (**Project roles**) | Role uprawnione do rozstrzygania. Puste: każdy z `run.approve` w projekcie. |
| **Wskazane osoby** (**Named people**) | Tylko te osoby mogą rozstrzygać; każda potrzebuje też jednej z ról projektowych, jeśli są ustawione. Puste: każdy w tych rolach. |
| **Rola odpowiedzialna** (**Accountable role**) | Jedna rola odpowiedzialna, pokazywana na karcie w kolejce i w panelu. Jeśli ustawiono role projektowe, musi być jedną z nich. |
| **Zabroń samozatwierdzania** (**Forbid self-approval**) | Osoba, która uruchomiła workflow, nie może rozstrzygnąć tej bramki. Uruchomienia wystartowane przez CI nie mają ludzkiego inicjatora, więc nikt nie jest wykluczony. |

Właściciel lub maintainer projektu może przypisać krok, który już czeka, innym osobom tylko w tym
uruchomieniu, przyciskiem **Przypisz…** (**Assign…**) przy bramce
([Przypisywanie kroku](your-turn.md#assign-steps)); workflow się nie zmienia.

**Lista kontrolna** (**Checklist**) zawiera pozycje Definition of Ready lub Definition of Done: id, tekst
i przełącznik **Wymagana** (**Required**). Użyj **Dodaj pozycję listy kontrolnej** (**Add checklist
item**). **Zatwierdź** (**Approve**) pozostaje nieaktywne, dopóki wszystkie wymagane pozycje nie są
zaznaczone; odesłanie do poprawy i odrzucenie nie wymagają zaznaczeń. Definition of Ready umieszczaj na
bramkach przed rozpoczęciem pracy, a Definition of Done na bramkach przed scaleniem.

Gdy uruchomienie zostaje odesłane przyciskiem **Odeślij do poprawy** (**Send back for revision**),
komentarz trafia do `{{feedback}}`, pewność jest zerowana, a uruchomienie idzie krawędzią `revise`,
zwykle do zadania, które napisało artefakt.

<a id="write-confirmations"></a>
## Zatwierdzanie przed zapisem zewnętrznym

Węzły akcji, które zapisują coś poza Back Office, proszą człowieka o zatwierdzenie dokładnie tego zapisu,
zanim do niego dojdzie. Obecnie są to akcje Jiry **Skomentuj zgłoszenie** (**Comment on issue**) i **Zmień status
zgłoszenia** (**Transition issue**); paleta oznacza je jako zapisujące w Jirze. Zatwierdzenie jest wbudowane w krok, więc nie
dodajesz dla niego bramki.

Zaznacz taki węzeł; jego właściwości mają sekcję **Zatwierdzenie przed zapisem w Jirze** (**Approval
before writing to Jira**):

| Ustawienie | Znaczenie |
|---|---|
| **Zatwierdzenie** (**Approval**) | **Wymagane zatwierdzenie (zalecane)** (**Approval required (recommended)**; domyślnie) albo **Zapisuj bez zatwierdzenia** (**Write without approval**). Węzły zapisane, zanim pojawiło się to ustawienie, liczą się jako wymagające zatwierdzenia. |
| **Kto zatwierdza** (**Who approves**) | Obowiązująca reguła. Domyślnie posiadacze `run.approve` z rolą projektową **Właściciel** lub **Maintainer**, rola odpowiedzialna **Właściciel**, oznaczone „(domyślnie)”. Zmienisz ją polami **Role projektowe**, **Wskazane osoby**, **Rola odpowiedzialna** i **Zabroń samozatwierdzania**, dokładnie jak **Kto rozstrzyga ten krok** na bramce ([Ustawienia bramki](#gates-config)); reguła bez ról i osób oznacza każdego z `run.approve`. **Przywróć domyślne** usuwa własną regułę węzła. |

W czasie uruchomienia krok raz renderuje komentarz lub status docelowy, uruchomienie czeka, a uprawnione
osoby decydują w **Twoja kolej** ([Zatwierdzanie zapisów zewnętrznych](your-turn.md#write-confirmations)).
Właściciele i maintainerzy mogą przekazać czekające zatwierdzenie innym osobom przyciskiem **Przypisz…**,
tak jak bramkę.

- **Zatwierdź** (**Approve**): krok zapisuje dokładnie pokazany tekst i wychodzi przez `success`.
- **Odrzuć** (**Reject**): nic nie zostaje zapisane; krok wychodzi przez **`failure`** z komunikatem
  „write rejected by <nazwa>, nothing was written ...”. Połącz `failure` (na przykład z węzłem **Koniec**
  z wynikiem **Porażka** albo z zadaniem, które napisało tekst, aby go poprawiło); jeśli port nie jest połączony,
  uruchomienie kończy się niepowodzeniem.

**Walidacja**: przy **Zapisuj bez zatwierdzenia** węzeł dostaje ostrzeżenie „this step writes to Jira (...) without
anybody confirming the exact write first; text from issues or model output can reach it (prompt
injection) - keep approval REQUIRED unless the content is fixed” (krok zapisuje w Jirze bez zatwierdzenia;
może do niego trafić tekst zgłoszenia lub wynik modelu). Nie blokuje ono zapisu. Zostaw wymagane zatwierdzenie zawsze,
gdy tekst zawiera wynik modelu lub pola zgłoszenia (`{{artifacts.*}}`, `{{issue.*}}`, `{{feedback}}`).
Nieznana wartość **Zatwierdzenia** w widoku JSON jest błędem.

Szczegóły, wpisy audytu i wpływ na domyślny workflow:
[Zatwierdzenie przed zapisem w Jirze](integrations-jira.md#jira-write-approval).

<a id="start-run"></a>
## Uruchamianie workflowu

Potrzebujesz `run.start` w projekcie (role **Właściciel**, **Maintainer**, **Deweloper**), a workflow
musi być włączony.

1. Otwórz projekt, kartę **Workflowy**, i kliknij **Uruchom** obok workflowu. Możesz też nacisnąć
   `Ctrl K`, wybrać **Uruchom…** (**Start run…**) i wskazać workflow.
2. Wypełnij formularz. Jego pola to **dane wejściowe uruchomienia** (**run inputs**), które kroki workflowu
   deklarują w bibliotece zadań (zob. [Dane wejściowe uruchomienia](task-library.md#run-inputs)): pole
   wieloliniowe na dłuższy tekst, na przykład **Materiał wejściowy** (**Input material**), i pole jednoliniowe
   na krótkie wartości. Pola oznaczone `*` są wymagane; każde pole przyjmuje najwyżej 20 000 znaków. Pole
   **Klucz zgłoszenia Jira** (na przykład `SHOP-123`) pojawia się tylko wtedy, gdy workflow ma krok Jira.
3. Opcjonalnie: otwórz **Zaawansowane (JSON)** (**Advanced (JSON)**), aby dodać dodatkowe parametry jako
   obiekt JSON, na przykład `{"depth": 2}`. Służy do wartości, których żaden krok nie deklaruje; klucz
   zgłoszenia i pola formularza ustawiasz w formularzu.
4. Kliknij **Uruchom**. Otworzy się strona uruchomienia.

Wyrażenia odczytują wartości jako `input.parameters['name']`; klucz zgłoszenia to `input.issueKey`. Start
przez REST API (`POST /api/v1/workflows/:id/runs`, także z osobistym tokenem dostępu) jest sprawdzany tak samo:
brak wymaganego pola albo klucz zgłoszenia o innej postaci niż `ABC-123` kończy się odmową z komunikatem, który
nazywa pole. Krok Jira, który czyta klucz z innego pola (**issueKeyFrom**), nie czyni klucza zgłoszenia wymaganym.
Starty z CI, z obserwatora zgłoszeń
Jira, z Twojego osobistego przyjmowania pracy i przez narzędzie MCP `start_workflow` nie są sprawdzane.

W projektancie węzeł **Start** pokazuje dane wejściowe uruchomienia workflowu tylko do odczytu; zmieniasz je
w zadaniach biblioteki.

Inne sposoby uruchomienia: Twój pipeline przez punkt wejścia CI (zob. [Projekty](projects.md#project-ci)),
REST API (`POST /api/v1/workflows/:id/runs`) i narzędzie MCP `start_workflow` z IDE (zob.
[IDE i MCP](integrations-ide.md#mcp-tools)).

Lista **Wyzwalacze** workflowu (**Ręcznie (przycisk w UI)**, **Wywołanie API**, **Punkt wejścia CI**,
**Jira (monitor zgłoszeń)**; **Manual (UI button)**, **API call**, **CI entrypoint**, **Jira (intake watcher)**)
dokumentuje, jak workflow ma być uruchamiany; trzech pierwszych Back Office nie egzekwuje. Z wyzwalacza
**Jira (monitor zgłoszeń)** korzysta **monitor zgłoszeń Jira** projektu: ze **statusem** (na przykład
`Ready for AI`) zgłoszenie, które trafi do tego statusu, uruchamia workflow ze swoim kluczem; bez statusu
robi to nowe zgłoszenie zgodne z filtrem JQL projektu. Monitor włącza się osobno w każdym projekcie i może
uruchamiać tylko workflow z tym wyzwalaczem ([Monitor zgłoszeń Jira](integrations-jira.md#jira-intake)).

Wyzwalacz **Harmonogram** (**Schedule**, `SCHEDULE`, ADR 0052) sam uruchamia workflow **co N minut** (co najmniej
5) od rozpoczęcia jego ostatniego uruchomienia, nigdy gdy któreś jego uruchomienie jeszcze trwa ani gdy workflow jest
wyłączony. Uruchomienie dostaje parametr `since` (kiedy rozpoczęło się poprzednie), więc obserwator zgłasza tylko
nowe rzeczy. Uruchomienie odbywa się w imieniu osoby, która ostatnio zapisała workflow: obowiązuje jej uprawnienie
`run.start` i jej osobiste profile modeli, więc harmonogram może korzystać z Twojego profilu modelu z subskrypcją.

Uruchomienie nie wystartuje (`409`), gdy krok Jiry w workflowie nie ma połączenia, którego mógłby użyć;
komunikat wskazuje krok. Zapis workflowu tylko o tym ostrzega
([Ostrzeżenia i błędy](integrations-jira.md#jira-tasks)). Uruchomienie nie wystartuje też wtedy, gdy krok
programistyczny wskazuje repozytorium, którego projekt nie ma, albo potrzebuje głównego repozytorium, którego
projekt nie ma ([Repozytoria kroku](#step-repositories)).
