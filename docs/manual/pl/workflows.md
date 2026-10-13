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
| Paleta (po lewej) | Węzły **Sterowanie** (**Control**) i **Zadania** (**Tasks**) z biblioteki zadań, pogrupowane według interfejsu, z polem wyszukiwania. Szablon należący do projektu ma ikonę projektu. |
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

### Domyślne workflowy

Każdy nowy projekt z repozytorium dostaje trzy workflowy (projekt bez repozytorium dostaje zamiast nich
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

<a id="nodes"></a>
## Rodzaje węzłów

| Węzeł | Przeznaczenie | Porty wyjściowe |
|---|---|---|
| **Start** | Wejście; dokładnie jeden na workflow. | `out` |
| **Zadanie** (**Task**) | Wykonuje szablon zadania z biblioteki (zob. niżej). | porty szablonu, zwykle `success`, `failure` |
| **Decyzja** (**Decision**) | Kieruje uruchomienie według warunków na stanie uruchomienia (zob. [Decyzje](workflows.md#decisions)). | jeden na gałąź plus domyślny |
| **Bramka decyzyjna** (**Human gate**) | Zatrzymuje uruchomienie, dopóki zatwierdzający nie wybierze opcji (zob. [Ustawienia bramki](workflows.md#gates-config)). | jeden na opcję (domyślnie `approve`, `revise`, `reject`) plus `timeout` |
| **Oczekiwanie na CI** (**Wait for CI**) | Zatrzymuje uruchomienie, dopóki Twoje CI nie wyśle sygnału. | `success`, `fail`, `timeout` |
| **Koniec** (**End**) | Kończy uruchomienie wynikiem **Sukces** (**Success**) lub **Porażka** (**Failure**). W uruchomieniu wystartowanym z CI rozstrzyga o bramce CI. | brak |
| **Notatka** (**Note**) | Karteczka; nigdy się nie wykonuje, nie można jej połączyć. | brak |

Każdy węzeł ma **Nazwę** (**Name**) i **Id** (slug; niezmienny po zapisie, bo odwołują się do niego
uruchomienia i logi). Węzły zadania, decyzji, bramki i CI mają też **Maks. liczbę wizyt** (**Max
visits**; ochrona przed pętlą, domyślnie 3) i **Limit czasu (s)** (**Timeout (s)**). Gdy węzeł miałby
wystartować więcej razy, niż pozwala **Maks. liczba wizyt**, uruchomienie kończy się niepowodzeniem.

**Niepołączone porty.** Wymagany port musi być połączony: `success` zadania, każda gałąź decyzji,
pierwsza opcja bramki, `success` oczekiwania na CI, `out` węzła Start. Jeśli uruchomienie wychodzi przez
niepołączony port `failure`, `fail` lub `timeout`, kończy się niepowodzeniem; przez niepołączony
`reject` — zostaje anulowane. Walidacja ostrzega o pozostałych niepołączonych portach.

### Węzły zadań

Wybierz **Szablon**. Pod nim widać rodowód szablonu, wykonawcę, interfejsy i porty oraz **Otwórz
w bibliotece** (**Open in library**). **Nadpisania** (**Overrides**) zmieniają szablon tylko dla tego
węzła:

| Nadpisanie | Skutek |
|---|---|
| **Profil modelu** (**Model profile**) | Model dla tego węzła zamiast modelu z szablonu lub domyślnego modelu projektu. |
| **Prompt użytkownika** (**User prompt**) | **Dziedziczony** (**Inherited**), **Dopisz** (**Append**; dodawany po prompcie szablonu) lub **Zastąp** (**Replace**). |
| **Dopisek do promptu systemowego** (**System prompt append**) | Dodawany do promptu systemowego szablonu. |
| **Dodaj umiejętności** (**Add skills**), **Usuń umiejętności** (**Remove skills**) | Modyfikują listę umiejętności szablonu. |
| **Konfiguracja** (**Config**) | Ustawienia wykonawcy, takie jak polecenie weryfikacji, nazwa gałęzi czy opcje merge requestu; scalane z konfiguracją szablonu. |

**Szablony Spec Kit** (**Spec Kit: specify**, **plan**, **tasks** i szablony, które je rozszerzają) pojawiają się
w palecie tylko wtedy, gdy projekt ma włączony [Spec Kit](projects.md#spec-kit); w przeciwnym razie krok Spec Kit
jest błędem walidacji, a uruchomienia nie startują. **Szablony Jiry** mają sekcję **Połączenie Jira** z ustawieniem
**Działaj jako**: połączenie systemowe projektu, wskazane połączenie albo osoba, która zatwierdza zapis lub
rozpoczyna uruchomienie ([Działaj jako](integrations-jira.md#jira-act-as)).

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

<a id="step-repositories"></a>
## Repozytoria kroku

Kroki programistyczne (szablony **Implement** i **Spec Kit**) uruchamiają agenta, który pracuje
w repozytoriach Git projektu ([Repozytoria](projects.md#repositories)). Pozostałe kroki (wywołania modelu,
Jira, bramki, oczekiwanie na CI) nie używają repozytoriów, więc ich właściwości nie mają sekcji repozytoriów.

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

**Przestrzeń robocza.** Każde repozytorium jest klonowane do osobnego katalogu nazwanego jego kluczem:
`/workspace/service`, `/workspace/docs`, … Agent zaczyna w głównym repozytorium kroku (w głównym
repozytorium projektu, jeśli krok go używa, w przeciwnym razie w pierwszym z listy) i dostaje informację,
jakie repozytoria istnieją, gdzie są, jakie mają przeznaczenie i gałąź bazową oraz czy może je zmieniać.

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

Węzły zadań, które zapisują coś poza Back Office, proszą człowieka o zatwierdzenie dokładnie tego zapisu,
zanim do niego dojdzie. Obecnie są to zadania Jiry **Notify Jira** (komentarz) i **Transition issue**
(zmiana statusu); paleta oznacza je jako zapisujące w Jirze. Zatwierdzenie jest wbudowane w krok, więc nie
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
2. Wpisz **Klucz zgłoszenia Jira (opcjonalnie)** (**Jira issue key (optional)**), na przykład
   `SHOP-123`. Workflowy, które pobierają zgłoszenie, go wymagają.
3. Opcjonalnie wpisz **Parametry (JSON, opcjonalnie)** (**Parameters (JSON, optional)**); wyrażenia
   odczytują je jako `input.parameters['name']`.
4. Kliknij **Uruchom**. Otworzy się strona uruchomienia.

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

Uruchomienie nie wystartuje (`409`), gdy krok Jiry w workflowie nie ma połączenia, którego mógłby użyć;
komunikat wskazuje krok. Zapis workflowu tylko o tym ostrzega
([Ostrzeżenia i błędy](integrations-jira.md#jira-tasks)). Uruchomienie nie wystartuje też wtedy, gdy krok
programistyczny wskazuje repozytorium, którego projekt nie ma, albo potrzebuje głównego repozytorium, którego
projekt nie ma ([Repozytoria kroku](#step-repositories)).
