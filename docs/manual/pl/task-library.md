# Biblioteka zadań

Biblioteka zadań przechowuje to, co robią agenci i jak są promptowani, niezależnie od workflowów, które
z niej korzystają: szablony zadań, interfejsy, które spełniają, i umiejętności (skills) wielokrotnego
użytku. Ten rozdział opisuje trzy strony **Biblioteki** (**Library**) i sposób zmiany zadania tylko dla
jednego projektu.

## Jak to się łączy

| Element | Odpowiednik | Służy do |
|---|---|---|
| Interfejs zadania | interfejs | obiecania, jakich wejść zadanie potrzebuje, jakie wyjścia wytwarza i jakie porty ma jego węzeł |
| Szablon zadania | klasa | zdefiniowania zadania: wykonawca, prompty, umiejętności, konfiguracja; rozszerza jednego rodzica i implementuje interfejsy |
| Umiejętność | mixin | wielokrotnego użycia bloku instrukcji (pliku `SKILL.md`) w wielu szablonach |
| Rozwiązane zadanie | instancja | tego, co ostatecznie otrzymuje agent: szablon scalony z przodkami, nadpisaniami projektu i nadpisaniami węzła |

Węzeł zadania w workflowie odwołuje się do szablonu po kluczu i nigdy sam nie przechowuje promptu.
Edycja szablonu zmienia więc każdy workflow, który go używa, od następnego uruchomienia. Uruchomienia
w toku zachowują to, co już rozwiązały.

### Kto może co zmieniać

| Chcesz | Biblioteka globalna | Własne wpisy jednego projektu |
|---|---|---|
| Przeglądać szablony, interfejsy, umiejętności i podgląd | `library.view` (każdy **Członek**, **Member**) | `project.view` w projekcie |
| Tworzyć, edytować lub usuwać szablony i umiejętności | `library.manage` (rola **Kurator biblioteki**, **Library curator**) | `library.project.manage` (**Właściciel**, **Maintainer** projektu) |
| Tworzyć, edytować lub usuwać interfejsy | `library.manage`; interfejsy są zawsze globalne | — |

Bez odpowiedniego uprawnienia edytory otwierają się w trybie tylko do odczytu. Każda zmiana jest
zapisywana w dzienniku audytu, a listy pokazują, kto ostatnio zmienił wpis.

<a id="templates"></a>
## Szablony

**Biblioteka** → **Szablony** (**Templates**, `/library/templates`) wyświetla szablony z kolumnami
**Rozszerza** (**Extends**), **Implementuje** (**Implements**), **Wykonawca** (**Executor**), **Zakres**
(**Scope**) i **Zaktualizowano** (**Updated**). Szablony wbudowane są oznaczone; podobnie abstrakcyjne,
które można rozszerzać, ale nigdy wykonać. Filtruj tekstem, wykonawcą (**Dowolny wykonawca**, **Any
executor**) oraz przełącznikiem **Szablony globalne** / **Globalne + KEY** (**Global templates** /
**Global + KEY**), aby uwzględnić własne szablony projektu.

Wykonawca decyduje, gdzie działa zadanie:

| Wykonawca | Działa jako |
|---|---|
| **LLM** | Jedno wywołanie modelu wewnątrz Back Office; umiejętności trafiają do promptu systemowego; może zwrócić ustrukturyzowany JSON i pewność. |
| **Spec Kit** | Faza Spec Kit (specify, plan, tasks) w zadaniu agenta na runnerze. |
| **Implementacja** (**Implement**) | Agent programistyczny (OpenCode lub Claude Code) w zadaniu agenta; wypycha gałąź i otwiera merge request przez push options. |
| **Pobierz zgłoszenie** (**Fetch issue**), **Powiadom** (**Notify**), **Zmień status zgłoszenia** (**Transition issue**) | Wywołania Jiry: odczyt zgłoszenia, dodanie komentarza, przeniesienie zgłoszenia do statusu (zob. [Jira](integrations-jira.md#jira-tasks)). |

### Kroki: tworzenie szablonu

1. Kliknij **Nowy szablon** (**New template**) (wymaga `library.manage` albo `library.project.manage` dla
   szablonu projektu).
2. **Typ** (**Type**): **Klucz** (**Key**; slug), **Nazwa**, **Opis**, **Rozszerza** (jeden rodzic),
   **Implementuje** (interfejsy), **Wykonawca** (puste oznacza dziedziczony), **Abstrakcyjny**
   (**Abstract**), **Zakres** (**Globalny** lub **Projektowy** z wyborem **Projektu**), **Profil modelu**
   (**Model profile**).
3. **Prompt**: wybierz **Dopisz do rodzica** (**Append to parent**) lub **Zastąp rodzica** (**Replace
   parent**), a następnie napisz **Prompt systemowy** (**System prompt**) i **Prompt użytkownika** (**User
   prompt**). Wartości szablonowe, takie jak `{{issue.summary}}`, `{{artifacts.spec}}`, `{{feedback}}`
   i `{{project.constitution}}`, są wypełniane w czasie wykonania. Sekcja
   `{{#artifacts.review}}...{{/artifacts.review}}` zachowuje swój tekst tylko wtedy, gdy wartość istnieje
   (`{{^path}}...{{/path}}` tylko wtedy, gdy jej nie ma), więc prompt nie pokazuje pustego nagłówka
   „Issue” czy „Feedback” w przebiegu bez zgłoszenia ani przy pierwszej próbie. `{{feedback}}` zawiera
   wyłącznie uwagi do tego kroku: komentarz **Popraw** (**Revise**) z bramki trafia do następnego
   uruchomionego kroku, zostaje przy nim na kolejne próby i nigdy nie dociera do kroków po nim (wszystkie
   komentarze przebiegu: `{{feedbackHistory}}`).
4. **Umiejętności** (**Skills**): dodaj umiejętności, a odziedziczone umiejętności do usunięcia wpisz w **Usuń
   odziedziczone umiejętności** (**Remove inherited skills**).
5. **Kontrakt i konfiguracja** (**Contract and configuration**): **Produkuje** (**Produces**)
   i **Konsumuje** (**Consumes**) (nazwy artefaktów lub pól stanu), **Pole pewności** (**Confidence
   field**), **Tagi** (**Tags**), **Konfiguracja (JSON)** (**Config (JSON)**) oraz, dla zadań LLM, **Schemat
   wyjścia** (**Output schema**).
6. **Sandbox** (zadania na runnerze): zawęź politykę sandboxa projektu dla zadań tego szablonu, na
   przykład silniejszą izolacją lub mniejszą liczbą hostów. Pułapy projektu i puli nadal obowiązują (zob.
   [Polityka sandboxa](projects.md#sandbox-policy)).
7. Kliknij **Utwórz szablon** (**Create template**) (lub **Zapisz szablon**, **Save template**, dla
   istniejącego).

### Scalanie z rodzicem

| Pole | Reguła |
|---|---|
| Wykonawca | Wygrywa najbliższy w łańcuchu. |
| Profil modelu, schemat wyjścia, pole pewności | Wygrywa wartość potomka, jeśli jest ustawiona. |
| Prompt systemowy i użytkownika | Dla każdego pola osobno: **Dopisz do rodzica** dodaje tekst potomka po tekście rodzica; **Zastąp rodzica** używa tylko tekstu potomka. Puste pole zachowuje wartość rodzica. |
| Umiejętności | Suma wzdłuż łańcucha minus usunięte umiejętności. |
| Konfiguracja | Scalana klucz po kluczu; wygrywa potomek; `null` usuwa klucz. |
| Produkuje, konsumuje, implementuje, tagi | Suma. |

### Podgląd rozwiązanego zadania

Prawa strona edytora pokazuje **Podgląd rozwiązanego zadania** (**Resolved preview**): dokładnie to, co
otrzymuje agent po dziedziczeniu i przesłonięciu przez projekt, dla biblioteki globalnej lub wybranego
projektu. Pokazuje rodowód, wykonawcę, interfejsy i porty oraz karty **Prompt systemowy**, **Prompt
użytkownika**, **Umiejętności** i **Konfiguracja**. Jeśli implementowany interfejs nie jest spełniony,
**Kontrola interfejsu nie powiodła się** (**Interface check failed**) wymienia braki, a zadanie nie może
działać, dopóki ich nie usuniesz. Podgląd odzwierciedla zapisaną wersję; niezapisane zmiany są
wymienione jako różnice.

### Kroki: zmiana zadania dla jednego projektu

1. W wierszu szablonu kliknij **Rozszerz w projekcie** (**Extend in a project**).
2. Otworzy się nowy szablon z tym samym kluczem, zakresem **Projektowy** i szablonem globalnym jako
   rodzicem. Wybierz projekt i zmień tylko to, co się różni, na przykład dopisz regułę do promptu
   systemowego.
3. Zapisz. Od tej chwili workflowy tego projektu używają szablonu projektowego wszędzie, gdzie występuje
   ten klucz, także jako rodzica innych szablonów. Pozostałe projekty zachowują szablon globalny.

Szablonów wbudowanych nie można usuwać; zamiast tego rozszerzaj je w projektach. Nie można też usunąć
szablonu, który rozszerza inny szablon.

<a id="interfaces"></a>
## Interfejsy

**Biblioteka** → **Interfejsy** (**Interfaces**, `/library/interfaces`) wyświetla kontrakty z kolumnami
**Wejścia** (**Inputs**), **Wyjścia** (**Outputs**), **Porty** (**Ports**) i **Implementowany przez**
(**Implemented by**).

Interfejs ma **Klucz**, **Nazwę**, **Opis**, wejścia i wyjścia (każde z nazwą, rodzajem i, dla wejść,
oznaczeniem **wymagane**, **required**) oraz **Porty**: porty wyjściowe, które węzeł z tym interfejsem
pokazuje na kanwie. Szablon implementujący interfejs musi wymienić każde wymagane wejście (artefakt lub
pole stanu) w **Konsumuje** i każde wyjście w **Produkuje**; podgląd rozwiązanego zadania to sprawdza.

Kliknij **Utwórz interfejs** (**Create interface**), wypełnij pola (**Dodaj wejście**, **Add input**;
**Dodaj wyjście**, **Add output**) i kliknij **Utwórz interfejs**. Zmiana interfejsów wymaga
`library.manage`. Nie można usunąć interfejsu, który wciąż implementują szablony; interfejsów
wbudowanych nie można usuwać w ogóle.

Gdy dodasz do interfejsu wymagane wejście lub wyjście, szablony wymienione w **Implementowany przez** nie
przejdą kontroli interfejsu, dopóki nie zaktualizujesz ich pól **Konsumuje** i **Produkuje**.

<a id="skills"></a>
## Umiejętności

**Biblioteka** → **Umiejętności** (**Skills**, `/library/skills`) wyświetla bloki instrukcji
wielokrotnego użytku z kolumnami **Zakres**, **Tagi**, **Rozmiar** (**Size**) i **Zaktualizowano**.
Umiejętność stosuje konwencję Agent Skills: nagłówek frontmatter z `name` i `description`, a potem
krótkie, konkretne, ponumerowane reguły.

Jak umiejętność trafia do agenta:

- Zadania **LLM** dostają umiejętność dopisaną do promptu systemowego.
- Zadania na runnerze (**Spec Kit**, **Implementacja**) dostają ją zapisaną jako
  `.claude/skills/<key>/SKILL.md` i `.opencode/skills/<key>/SKILL.md`, które Claude Code i OpenCode
  wczytują samodzielnie.

### Kroki: tworzenie umiejętności

1. Kliknij **Utwórz umiejętność** (**Create skill**).
2. Wpisz **Klucz**, **Nazwę**, **Opis**, **Zakres** (**Globalny** lub **Projektowy**) i **Tagi**.
3. Napisz treść na karcie **SKILL.md**; sprawdź ją na karcie **Podgląd** (**Preview**).
4. Kliknij **Utwórz umiejętność**.

Umiejętność projektowa o kluczu umiejętności globalnej zastępuje ją dla szablonów tego projektu, bez
zmiany żadnego szablonu. Umiejętności używanej przez szablon nie można usunąć ani przemianować;
umiejętności wbudowanych nie można usuwać. Każdy agent dziedziczy umiejętności `agent-ground-rules`
i `ask-dont-guess` przez wbudowany szablon `base-agent`; jeśli zadanie nie może ich widzieć, usuń je
w szablonie przez **Usuń odziedziczone umiejętności**.

**Opis** pisz w formie „Use when ...” („Użyj, gdy ...”): po nim agent decyduje, czy otworzyć umiejętność.
Reguły, których zadanie musi zawsze przestrzegać, należą do jego promptu, nie tylko do umiejętności.
Agent na runnerze znajduje wszystkie umiejętności w katalogu roboczym, ale czyta tylko te, które mu
wskazano, dlatego prompty wbudowanych zadań na runnerze kończą się linią „Apply the skills: ...”; gdy
dołączasz do takiego zadania kolejną umiejętność, dopisz jej klucz do tej linii. `kotlin-hexagonal`
i `angular-ng-zorro` opisują kod samego Back Office: traktuj je jako przykłady i skopiuj do umiejętności
projektowej dla własnego stosu.

<a id="sources"></a>
## Źródła

**Biblioteka** → **Źródła** (**Sources**, `/library/sources`) wyświetla materiały, które kroki każdego
projektu mogą podłączyć: standardy do przestrzegania, szablony do wykorzystania, dokumentację referencyjną.
Na razie źródło to repozytorium Git. Listę widzi każdy z `library.view`; `library.manage` dodaje,
zmienia i usuwa źródła.

| Pole | Znaczenie |
|---|---|
| **Klucz** (**Key**) | 2-27 małych liter, cyfr i myślników, zaczyna się literą, na przykład `standards`. Kroki odwołują się po nim do źródła. Nie można go później zmienić. |
| **Nazwa** (**Name**) | To, co widać w projektancie i na karcie projektu **Źródła**. |
| **URL Gita** (**Git URL**) | Adres, który klonuje runner. Prywatne repozytoria wymagają tokenu do odczytu na runnerach, jak repozytoria projektu ([Dostęp agentów do gita](integrations-git.md#git-least-privilege)). |
| **Gałąź** (**Branch**) | Domyślnie `main`. Gałąź, która jest klonowana. |
| **Przeznaczenie** (**Purpose**) | **Standardy**, **Szablony**, **Dokumentacja**, **Kod źródłowy** albo **Inne**. Agent dostaje tę informację. |
| **Opis** (**Description**) | Czego w nim przestrzegać albo co wykorzystać. Agent dostaje go jako dane, nie jako instrukcje. |

Jak źródło trafia do agenta: w projektancie krok programistyczny zaznacza je w sekcji **Repozytoria**,
w części **Źródła z biblioteki (tylko odczyt)** ([Repozytoria kroku](workflows.md#step-repositories)).
Zadanie klonuje je do `/workspace/lib-<key>` (na przykład `/workspace/lib-standards`) tylko do odczytu:
do źródła z biblioteki nic nie jest wypychane, a agent dostaje polecenie, by się do niego stosował, a nie
je zmieniał.

Usunięcie źródła nie zmienia żadnego workflowu: kroki, które nadal je wskazują, nie przechodzą walidacji
(workflow da się zapisać tylko jako wersję roboczą), dopóki ich nie odznaczysz. Gdy publikujesz workflow do
biblioteki workflowów, źródła z biblioteki używane przez jego kroki idą razem z nim; projekty, które go
przyjmą, też je dostają.

## Biblioteka wbudowana

Back Office zawiera szablony faz Spec Kit, implementacji z OpenCode lub Claude Code, `clarify-spec`,
`code-review`, `analyse-issue`, zadań Jiry oraz zadań SDLC, takich jak `impact-analysis`,
`write-user-story`, `plan-task-list`, `implement-task-list` i `review-against-spec`, wraz z ich
interfejsami i umiejętnościami. Wpisy wbudowane możesz edytować; Back Office nigdy nie nadpisuje
zmienionego wpisu.

<a id="built-in-updates"></a>
### Aktualizacje biblioteki wbudowanej

Przy każdym starcie Back Office porównuje każdy wbudowany szablon, umiejętność i interfejs z wersją,
którą dostarcza, pole po polu. Wpis wbudowany, którego nikt nie edytował (nadal zapisany przez system,
a w przypadku szablonu nadal w wersji 1), jest zastępowany wersją dostarczoną, gdy się różnią; wpis
zapisany przez człowieka albo kopia z tym samym kluczem, która nie jest wbudowana, zostaje bez zmian. Aby
dostać dostarczoną wersję zmienionego wpisu, porównaj go z **Podglądem rozwiązanego zadania** na świeżej
instalacji i przenieś to, czego potrzebujesz, albo usuń swoją niewbudowaną kopię i uruchom ponownie.

### Jak zbudowane są zadania wbudowane

- Wspólne reguły niosą trzy szablony abstrakcyjne: `base-agent` (rola, konstytucja, uczciwość, zakres,
  bezpieczeństwo, dwie umiejętności podstawowe), `base-llm` dla zadań LLM w Back Office (bez repozytorium;
  przy schemacie wyjścia odpowiedź to wyłącznie JSON) i `base-coding-agent` dla zadań na runnerze (praca
  w repozytorium, cytowanie ścieżek plików, nigdy commit ani push: runner commituje i wypycha po agencie).
- Zadania **Spec Kit**: prompt fazy specify zaczyna się linią `KLUCZ: podsumowanie`, potem opis zgłoszenia
  i kryteria akceptacji, potem instrukcje. Fazy plan i tasks nie wklejają specyfikacji ani planu, które
  Spec Kit czyta z repozytorium; przekazują uwagi do kroku i, w fazie plan, opcjonalny parametr przebiegu
  `techHints`.
- **Implementacja** (`implement-opencode`, `implement-claude-code`): runner uruchamia `verifyCommand`
  z **Konfiguracji**, jeśli go ustawisz (domyślnie brak), i oznacza krok jako nieudany, gdy polecenie
  zakończy się kodem różnym od zera; merge request używa kluczy `mergeRequest` (true/false),
  `mergeRequestTitleTemplate` i `mergeRequestDraft`.
- **Poprawki po przeglądzie**: gdy przebieg ma artefakt `review`, prompt implementacji zawiera go pod
  nagłówkiem „Latest review findings”, więc krawędź `changes` węzła **Review ok?** przekazuje uwagi
  z przeglądu do implementacji zamiast powtarzać ten sam prompt.
- `implement-task-list` wykonuje listę zadań po kolei i kończy `work-log.md` linią `RESULT: GREEN` albo
  `RESULT: STOPPED at <zadanie>`; jego `verifyCommand` sprawdza tę linię, więc zatrzymanie na czerwonym
  teście oznacza nieudany krok. Runner zbiera plik jako artefakt `work-log` i nie dodaje go do commita.
  Aby po agencie uruchomić też pełny zestaw testów, ustaw `verifyCommand` w kroku, na przykład
  `tail -n 3 work-log.md | grep -q "^RESULT: GREEN" && ./gradlew test`.
- `code-review` podaje instrukcje przed diffem, więc diff obcięty na `maxInputChars` nigdy ich nie usuwa;
  w przebiegu z CI jako kontekstu używa tytułu i opisu merge requestu. Cały diff jest przeglądany w jednym
  wywołaniu.
- `review-against-spec` zachowuje w artefakcie `review` tabelę pokrycia wymagań (MET, PARTIAL, MISSING,
  NOT_APPLICABLE); jedno wymaganie MISSING daje werdykt REQUEST_CHANGES.
- `clarify-spec` zapisuje `clarifications` jako listę markdown (pytanie, dlaczego jest ważne, proponowana
  odpowiedź); `write-user-story` i `write-use-case` zapisują swoje dokumenty jako artefakty `user-story`
  i `use-case`. `fetch-issue` czyta podsumowanie, opis, kryteria akceptacji i etykiety, bez komentarzy
  i załączników.
- Otwarte pytania mają jeden znacznik na rodzaj dokumentu: `[NEEDS CLARIFICATION: ...]` w specyfikacji
  Spec Kit, `[?]` wszędzie indziej. Agenci nigdy nie wymyślają właściciela ani terminu, zostawiają
  identyfikatory takie jak `US-<AREA>-<NN>` i numery ADR `NNNN` jako symbole zastępcze i nigdy nie
  ustawiają statusu, na przykład Accepted.

Domyślne workflowy nowego projektu korzystają z tych szablonów; workflow **Change request (delta)**
zaczyna się od **Fetch issue**, a jego lista zadań powstaje na podstawie zatwierdzonej analizy wpływu.
Projekty utworzone wcześniej zachowują zapisane workflowy; zmień je w projektancie, jeśli chcesz tego
samego.

<a id="generic-tasks"></a>
### Zadania z dowolnym promptem

Do pracy, którą opisujesz sam w węźle, służą dwa wbudowane szablony: przeciągnij klocek na kanwę, otwórz
**Nadpisania** (**Overrides**) → **Zastąp** (**Replace**) i wpisz tam zadanie (zastępuje prompt użytkownika z szablonu).

| Szablon | Kto go wykonuje | Do czego |
|---|---|---|
| **LLM task** (`llm-task`) | jedno wywołanie modelu, bez repozytorium | podsumowania, szkice, odpowiedzi na podstawie materiału wejściowego przebiegu |
| **Agent task** (`agent-task`) | agent kodujący na runnerze (domyślnie Claude Code, `agent` w **Konfiguracji**), z klonem repozytorium projektu | analiza kodu, przeglądy, odpowiedzi na pytania o repozytorium; z `push: true` w **Konfiguracji** także zmiany na gałęzi z merge requestem |

Oba wstawiają do promptu systemowego zgłoszenie (gdy przebieg je ma), parametr przebiegu `input` i uwagi
recenzenta (każde tylko wtedy, gdy istnieje), więc zastąpiony prompt może zawierać samo zadanie. **Agent task** domyślnie nie wypycha zmian; w projekcie
bez repozytoriów działa w pustym katalogu roboczym, a jego końcowa odpowiedź staje się artefaktem `result`.
**LLM task** działa na serwerze Back Office i wymaga skonfigurowanego dostawcy modeli (klucza API); **Agent task**
działa na runnerze, więc na [osobistym runnerze](personal-runner.md) może zamiast tego korzystać z Twojego logowania
do Claude.

Aby przeszukiwać bibliotekę z IDE, użyj narzędzia MCP `search_task_library` (zob.
[IDE i MCP](integrations-ide.md#mcp-tools)).
