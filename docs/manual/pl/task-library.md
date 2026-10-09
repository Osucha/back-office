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
   i `{{project.constitution}}`, są wypełniane w czasie wykonania.
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

## Biblioteka wbudowana

Back Office zawiera szablony faz Spec Kit, implementacji z OpenCode lub Claude Code, `clarify-spec`,
`code-review`, `analyse-issue`, zadań Jiry oraz zadań SDLC, takich jak `impact-analysis`,
`write-user-story`, `plan-task-list`, `implement-task-list` i `review-against-spec`, wraz z ich
interfejsami i umiejętnościami. Wpisy wbudowane możesz edytować; Back Office nigdy nie nadpisuje
zmienionego wpisu.

Aby przeszukiwać bibliotekę z IDE, użyj narzędzia MCP `search_task_library` (zob.
[IDE i MCP](integrations-ide.md#mcp-tools)).
