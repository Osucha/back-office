# Biblioteka zadań

Biblioteka zadań przechowuje to, co robią agenci i jak pracują, niezależnie od workflowów, które z niej
korzystają. **Biblioteka** (**Library**) ma trzy grupy (ADR 0055, 0056):

- **Zadania** (**Tasks**): **Szablony**, **Umiejętności**, **Reguły** i **Agenci**, opisane w tym rozdziale;
- **Workflowy** (**Workflows**): workflowy współdzielone między projektami ([Biblioteka workflowów](workflow-library.md));
- **Źródła** (**Sources**): materiały, które kroki mogą podłączyć ([Źródła](#sources)).

## Jak to się łączy

| Element | Odpowiednik | Służy do |
|---|---|---|
| Szablon zadania | zlecenie: **co** zrobić | opisania pracy: kategoria, prompty z [parametrami](#parameters), konfiguracja, wyniki, dane wejściowe uruchomienia i agent, który domyślnie ją wykonuje; może mieć jednego rodzica |
| Agent | wykonawca: **kto** pracuje i **jak** | nazwania sposobu pracy: harness (**Odpowiedź modelu**, **OpenCode** albo **Claude Code**), opcjonalnie model, **Instrukcje**, **Reguły**, **Umiejętności** i **Serwery MCP** ([Agenci](#agents)) |
| Reguła | stały rozkaz | zapisania tego, czego agent musi zawsze przestrzegać; jest zawsze w kontekście agenta |
| Umiejętność | rozdział podręcznika | dania agentowi wiedzy lub procedury, którą otwiera, gdy zadanie jej wymaga (plik `SKILL.md`) |
| Krok workflowu | zadanie na tablicy | połączenia jednego z drugim: szablon, agent i wartości parametrów szablonu ([Węzły zadań](workflows.md#task-nodes)) |
| Rozwiązane zadanie | instancja | tego, co ostatecznie otrzymuje agent: szablon scalony z przodkami, nadpisaniami projektu i nadpisaniami kroku, w środowisku jego agenta |

Szablon zmienia się, gdy zmienia się praca; agent zmienia się, gdy szukasz tańszego albo lepszego sposobu pracy.
Rozdzielenie ich pozwala wykonać to samo zadanie różnymi agentami i porównać ich ([Ewaluacja](evaluation.md)) bez
ruszania zadania (ADR 0057).

Praca, która nie wymaga modelu (pobranie zgłoszenia Jira, komentarz do niego, zmiana jego statusu, uruchomienie
workflowów, podsumowanie projektu), nie należy do biblioteki: to węzeł **akcji** w projektancie workflowów
([Akcje](workflows.md#actions)).

Kluczy nie wpisujesz: szablon, umiejętność, reguła i agent dostają klucz z nazwy przy tworzeniu (na przykład **Analiza
zgłoszenia** daje `analiza-zgloszenia`), a klucz zostaje, gdy zmienisz nazwę. Workflowy, MCP i API posługują się
kluczem; edytory pokazują go drobno w sekcji **Zaawansowane**.

Węzeł zadania w workflowie odwołuje się do szablonu po kluczu i nigdy sam nie przechowuje promptu.
Edycja szablonu zmienia więc każdy workflow, który go używa, od następnego uruchomienia. Uruchomienia
w toku zachowują to, co już rozwiązały.

### Kto może co zmieniać

| Chcesz | Biblioteka globalna | Własne wpisy jednego projektu |
|---|---|---|
| Przeglądać szablony, umiejętności, reguły, agentów i podgląd | `library.view` (każdy **Członek**, **Member**) | `project.view` w projekcie |
| Tworzyć, edytować lub usuwać szablony, umiejętności, reguły i agentów | `library.manage` (rola **Kurator biblioteki**, **Library curator**) | `library.project.manage` (**Właściciel**, **Maintainer** projektu) |

Bez odpowiedniego uprawnienia edytory otwierają się w trybie tylko do odczytu. Każda zmiana jest
zapisywana w dzienniku audytu, a listy pokazują, kto ostatnio zmienił wpis.

<a id="templates"></a>
## Szablony

**Biblioteka** → **Zadania** → **Szablony** (**Templates**, `/library/templates`) wyświetla szablony pogrupowane
według **Kategorii** (**Category**): Analiza, Specyfikacja, Planowanie, Implementacja, Przegląd, Jira, Operacje
i Ogólne (szablony bez kategorii). Projektant workflowów grupuje paletę tak samo. Szablony wbudowane są oznaczone;
podobnie abstrakcyjne, które mogą być bazą dla innych, ale nigdy nie są wykonywane. Filtruj tekstem, kategorią i agentem
oraz przełącznikiem **Szablony globalne** / **Globalne + KEY** (**Global templates** /
**Global + KEY**), aby uwzględnić własne szablony projektu.

Szablon opisuje pracę; jego **Agent** mówi, kto domyślnie ją wykonuje (zob. [Agenci](#agents)): agent z harnessem
**Odpowiedź modelu** (**Model answer**; jedno wywołanie modelu wewnątrz Back Office, bez repozytorium; może zwrócić
ustrukturyzowany JSON i pewność), **OpenCode** albo **Claude Code** (agent kodujący w sandboxie na runnerze,
z repozytorium; może wypchnąć gałąź i otworzyć merge request; szablon z fazą Spec Kit `phase` wykonuje tę fazę Spec
Kit). Krok workflowu może wybrać innego agenta. Szablon nie ma reguł ani umiejętności: należą do agenta.

Szablon, którego wymagane [parametry](#parameters) nie mają jeszcze wartości, ma znacznik **wymaga parametrów**
(**needs parameters**): to prompt do uzupełnienia w kroku workflowu. Pozostałe to gotowe klocki.

### Kroki: tworzenie szablonu

1. Kliknij **Nowy szablon** (**New template**) (wymaga `library.manage` albo `library.project.manage` dla
   szablonu projektu).
2. **Typ** (**Type**): **Nazwa**, **Opis**, **Na bazie** (**Based on**; opcjonalny, jeden rodzic: szablon
   dziedziczy jego prompty, parametry i konfigurację, a zmienia tylko to, co ustawisz), **Kategoria** (**Category**;
   puste oznacza dziedziczoną), **Agent** (puste oznacza dziedziczonego; puste w całym łańcuchu oznacza **Odpowiedź
   modelu**), **Zakres** (**Globalny** lub
   **Projektowy** z wyborem **Projektu**), **Profil modelu** (**Model profile**). Pod agentem sekcja **Środowisko
   agenta** (**Environment of the agent**) pokazuje tylko do odczytu, co wnosi wybrany agent: jego instrukcje,
   reguły (automatyczne są oznaczone) i umiejętności. Aby je zmienić, edytuj agenta na stronie **Agenci** albo
   wybierz innego agenta.
3. **Prompt**: wybierz **Dopisz do rodzica** (**Append to parent**) lub **Zastąp rodzica** (**Replace
   parent**), a następnie napisz **Prompt systemowy** (**System prompt**) i **Prompt użytkownika** (**User
   prompt**). Wartości szablonowe, takie jak `{{issue.summary}}`, `{{artifacts.spec}}`, `{{feedback}}`
   i `{{params.<nazwa>}}` ([parametr](#parameters)), są wypełniane w czasie wykonania. Konstytucji projektu ani
   reguł szablon nie wstawia: każdy krok dostaje je w środowisku swojego agenta. Sekcja
   `{{#artifacts.review}}...{{/artifacts.review}}` zachowuje swój tekst tylko wtedy, gdy wartość istnieje
   (`{{^path}}...{{/path}}` tylko wtedy, gdy jej nie ma), więc prompt nie pokazuje pustego nagłówka
   „Issue” czy „Feedback” w przebiegu bez zgłoszenia ani przy pierwszej próbie. `{{feedback}}` zawiera
   wyłącznie uwagi do tego kroku: komentarz opcji bramki, która odsyła pracę (**Popraw** (**Revise**),
   **Ponów** (**Retry**) albo każda opcja inna niż **Zatwierdź** i **Odrzuć**), trafia do następnego
   uruchomionego kroku, zostaje przy nim na kolejne próby i nigdy nie dociera do kroków po nim (wszystkie
   komentarze przebiegu: `{{feedbackHistory}}`). Komentarz **Zatwierdź** trafia do kolejnych promptów jako
   `{{decision.comment}}`.
4. **Parametry** (**Parameters**): pola, które wypełnia krok workflowu używający tego szablonu (zob.
   [Parametry](#parameters)). Kliknij **Dodaj parametr** (**Add parameter**), potem ustaw nazwę, etykietę
   pokazywaną w kroku, typ, **Wymagany**, opcjonalną wartość domyślną i tekst pomocy. Szablon oparty na innym
   pokazuje też **Wartości parametrów** (**Parameter values**): wartości parametrów jego rodziców.
5. **Konfiguracja** (**Configuration**): formularz z ustawieniami dla agenta szablonu (dla agenta kodującego:
   polecenie weryfikacji i przygotowania, push, merge request, artefakty do zebrania, narzędzia agenta, obszar
   roboczy, faza Spec Kit, ...). Ustaw tylko to, co różni się od szablonu bazowego; puste pola są dziedziczone. **Surowy JSON**
   (**Raw JSON**) pokazuje te same ustawienia jako JSON, także klucze, których formularz nie zna.
   **Zaawansowane: kontrakty, artefakty, schemat wyjścia, sandbox** (zwinięte; większość szablonów niczego tu nie
   potrzebuje): **Abstrakcyjny** (**Abstract**; tylko baza dla innych szablonów, nigdy samodzielny krok),
   **Produkuje** (**Produces**) i **Konsumuje** (**Consumes**) (nazwy artefaktów, które krok zapisuje i odczytuje; kontrola
   workflowu ostrzega, gdy krok czyta coś, czego nikt nie zapisuje), **Pole pewności** (**Confidence field**; pole
   odpowiedzi kopiowane do `state.confidence` na potrzeby decyzji takich jak `confidence < 0.7`), **Tagi**
   (**Tags**), **Schemat wyjścia** (**Output schema**; kształt ustrukturyzowanej odpowiedzi: model odpowiada według
   niego, agent kodujący zapisuje odpowiedź do `structured-answer.json`, zob. [Agenci](#agents)) i **Sandbox**
   (krok 7).
6. **Dane wejściowe uruchomienia** (**Run inputs**): pola, o które zadanie prosi przy starcie uruchomienia
   (zob. [Dane wejściowe uruchomienia](#run-inputs)). Kliknij **Dodaj daną wejściową** (**Add run input**),
   potem ustaw **Nazwę**, **Etykietę**, typ (**Jedna linia**, **Tekst wieloliniowy** lub **Klucz zgłoszenia
   Jira**), **Wymagane** i opcjonalny tekst pomocy; przesuwaj pola strzałkami albo je usuwaj. Nazwa zaczyna się
   literą i zawiera tylko litery, cyfry, `-` i `_` (najwyżej 64 znaki); etykieta ma najwyżej 200 znaków, tekst
   pomocy 1 000.
7. **Sandbox** (zadania na runnerze): zawęź politykę sandboxa projektu dla zadań tego szablonu, na
   przykład silniejszą izolacją lub mniejszą liczbą hostów. Pułapy projektu i puli nadal obowiązują (zob.
   [Polityka sandboxa](projects.md#sandbox-policy)).
8. Kliknij **Utwórz szablon** (**Create template**) (lub **Zapisz szablon**, **Save template**, dla
   istniejącego).

### Scalanie z rodzicem

| Pole | Reguła |
|---|---|
| Agent, kategoria | Wygrywa najbliższy w łańcuchu. |
| Profil modelu, schemat wyjścia, pole pewności | Wygrywa wartość potomka, jeśli jest ustawiona. |
| Prompt systemowy i użytkownika | Dla każdego pola osobno: **Dopisz do rodzica** dodaje tekst potomka po tekście rodzica; **Zastąp rodzica** używa tylko tekstu potomka. Puste pole zachowuje wartość rodzica. |
| Parametry | Suma według nazwy; parametr dziecka zastępuje parametr rodzica o tej samej nazwie. |
| Wartości parametrów | Wygrywa najbliższy szablon, który ustawia wartość. |
| Konfiguracja | Scalana klucz po kluczu; wygrywa potomek; `null` usuwa klucz. |
| Produkuje, konsumuje, tagi | Suma. |
| Dane wejściowe uruchomienia | Suma według nazwy; pole dziecka zastępuje pole rodzica o tej samej nazwie. |

Reguły i umiejętności nie są dziedziczone po szablonach: krok dostaje te, które ma jego agent.

<a id="parameters"></a>
### Parametry

Czasem szablon jest gotowym klockiem („napisz user story”), czasem promptem z miejscem na konfigurację
(„podsumuj materiał dla tego odbiorcy”). Parametry są tym miejscem (ADR 0058): typowane pola, które projektant
workflowu wypełnia w każdym kroku, a prompty i ustawienia tekstowe czytają jako `{{params.<nazwa>}}`.

| Typ | Wartość w kroku |
|---|---|
| **Jedna linia** (**Single line**) | krótki tekst |
| **Tekst wieloliniowy** (**Multi-line text**) | dłuższy tekst, na przykład cały opis zadania |
| **Liczba** (**Number**) | liczba |
| **Tak / nie** (**Yes / no**) | przełącznik |
| **Wybór** (**Choice**) | jedna z podanych opcji (parametr potrzebuje co najmniej jednej) |

Parametr ma nazwę (zaczyna się literą; litery, cyfry i `_`, najwyżej 64 znaki), etykietę (najwyżej 200 znaków),
typ, **Wymagany**, opcjonalną wartość domyślną i opcjonalny tekst pomocy (najwyżej 1 000 znaków).

**Skąd pochodzi wartość.** Wartość kroku, w przeciwnym razie wartość najbliższego szablonu w łańcuchu (presetu,
zob. niżej), w przeciwnym razie wartość domyślna parametru. Wymagany parametr bez żadnej z nich blokuje krok:
kontrola workflowu go wskazuje, a uruchomienie nie wystartuje; podgląd nadal działa. Szablony, których wymagane
parametry nie mają wartości, mają znacznik **wymaga parametrów** na liście i w palecie projektanta. Karta
**Parametry** podglądu pokazuje każdy parametr z wartością i jej źródłem (krok, szablon, wartość domyślna albo
„krok musi ją ustawić”).

**Utwórz preset** (**Create preset**). W edytorze zapisanego szablonu przycisk **Utwórz preset** (na dole)
otwiera nowy szablon, który rozszerza ten i ustawia tylko **Wartości parametrów**. Zapisz go pod własną nazwą, na
przykład „Podsumowanie dla komitetu sterującego”, a pojawi się w palecie jako gotowy klocek obok rodzica.

Parametry to nie dane wejściowe uruchomienia: parametr ustala się przy projektowaniu workflowu, o [daną
wejściową](#run-inputs) pyta się osobę, która uruchamia przebieg.

<a id="run-inputs"></a>
### Dane wejściowe uruchomienia

Zadanie deklaruje dane wejściowe, których potrzebuje, na przykład większość wbudowanych szablonów deklaruje
**Materiał wejściowy** (wieloliniowy, opcjonalny), a akcja **Pobierz zgłoszenie** w projektancie prosi o **Klucz
zgłoszenia Jira** (wymagany). Każde
zadanie rozszerzające szablon dziedziczy jego pola. Przy starcie uruchomienia formularz pokazuje pola wszystkich
kroków workflowu: pole jest wymagane, gdy wymaga go którykolwiek krok, a gdy dwa kroki używają tej samej nazwy,
wygrywa pierwsza etykieta (projektant ostrzega o tym). Wartości trafiają do promptu kroku w sekcji **Run input**,
jako dane; szablony mogą też czytać `{{runInputText}}` i, jak dotąd, `{{parameters.name}}`. Pole **Klucz
zgłoszenia Jira** ustawia też klucz zgłoszenia uruchomienia.

### Podgląd rozwiązanego zadania

Prawa strona edytora pokazuje **Podgląd rozwiązanego zadania** (**Resolved preview**): dokładnie to, co
otrzymuje agent po dziedziczeniu i przesłonięciu przez projekt, dla biblioteki globalnej lub wybranego
projektu. Pokazuje rodowód, agenta i kategorię oraz karty **Prompt systemowy** i **Prompt użytkownika** (własne
prompty szablonu; środowisko agenta edytor pokazuje w sekcji **Środowisko agenta**), **Umiejętności** i **Reguły**
(te agenta; reguły automatyczne są oznaczone), **Konfiguracja**, **Dane wejściowe** (**Run inputs**: scalone pola
wraz z szablonem, z którego każde jest dziedziczone) i **Parametry**. Jeśli umiejętność lub reguła, którą wymienia
agent, nie istnieje albo wymagany parametr nie ma wartości, podgląd to wskazuje, a zadanie nie może działać, dopóki
tego nie poprawisz. Podgląd odzwierciedla zapisaną wersję; niezapisane zmiany są wymienione jako różnice.

### Kroki: zmiana zadania dla jednego projektu

1. W wierszu szablonu kliknij **Dostosuj dla projektu** (**Customize for project**).
2. Otworzy się nowy szablon z zakresem **Projektowy** i szablonem globalnym jako rodzicem; zachowuje klucz
   szablonu globalnego, więc zastępuje go w tym projekcie. Wybierz projekt i zmień tylko to, co się różni, na przykład dopisz akapit do promptu
   użytkownika albo wybierz agenta projektu.
3. Zapisz. Od tej chwili workflowy tego projektu używają szablonu projektowego wszędzie, gdzie występuje
   ten klucz, także jako rodzica innych szablonów. Pozostałe projekty zachowują szablon globalny.

Szablonów wbudowanych nie można usuwać; zamiast tego rozszerzaj je w projektach. Nie można też usunąć
szablonu, który rozszerza inny szablon.

<a id="rules"></a>
## Reguły

**Biblioteka** → **Zadania** → **Reguły** (**Rules**, `/library/rules`) wyświetla to, czego agenci muszą zawsze
przestrzegać: konwencje kodu, rzeczy niedozwolone, sposób raportowania. Reguła różni się od umiejętności jednym:
**reguła jest zawsze w kontekście agenta, umiejętność agent otwiera, gdy jej potrzebuje.** Normę zapisz w regule
(„domena nigdy nie importuje Springa”), wiedzę lub procedurę w umiejętności („jak napisać ADR”).

Jak reguła trafia do agenta: jako część [środowiska agenta](#environment), dokumentu, który każdy krok dostaje
przed swoim zadaniem. Odpowiedź modelu znajduje go w prompcie systemowym; OpenCode i Claude Code czytają go jako
swoje stałe instrukcje, poza repozytorium. Prompt zadania nie powtarza reguł, więc krok, który [kontynuuje
sesję](workflows.md#continue-session), nie dostaje ich drugi raz.

Które reguły dostaje krok:

| Reguła | Obowiązuje |
|---|---|
| **Stosuj automatycznie w każdym kroku**, globalna | każdy krok każdego projektu |
| **Stosuj automatycznie w każdym kroku**, projektowa | każdy krok tego projektu |
| Nieautomatyczna | kroki wykonywane przez agenta, który wymienia ją w **Regułach** |

Najpierw idą reguły automatyczne (globalne, potem projektowe), potem reguły agenta. Reguła automatyczna obowiązuje
w każdym kroku swojego zakresu; żaden szablon ani agent nie może jej pominąć. Reguła projektowa o kluczu reguły
globalnej zastępuje ją w tym projekcie (tworzy się ją przez **Dostosuj dla projektu**); gdy kopia projektowa nie
jest automatyczna, globalna reguła automatyczna jest w tym projekcie wyłączona.

### Kroki: tworzenie reguły

1. Kliknij **Utwórz regułę** (**Create rule**).
2. Wpisz **Nazwę**, **Opis**, **Zakres** (**Globalny** lub **Projektowy**), **Tagi** i, jeśli każdy krok zakresu ma
   jej przestrzegać, włącz **Stosuj automatycznie w każdym kroku** (**Apply automatically to every step**).
3. Napisz regułę w markdownie: krótkie, konkretne, sprawdzalne zdania.
4. Kliknij **Utwórz regułę**.

Reguły, którą wymienia agent, nie można usunąć: najpierw usuń ją z tych agentów. Reguły wbudowane:
`agent-ground-rules` i `ask-dont-guess` (wymienia je każdy wbudowany agent), `kotlin-hexagonal` i `angular-ng-zorro`
(konwencje kodu samego Back Office: skopiuj je do reguły projektowej dla własnego stosu i ustaw ją jako automatyczną).

<a id="agents"></a>
## Agenci

**Biblioteka** → **Zadania** → **Agenci** (**Agents**, `/library/agents`) wyświetla tych, którzy mogą wykonywać
pracę zadań (ADR 0056, 0057). Strona mówi to jednym zdaniem: „Agent to ten, kto pracuje, i to, jak pracuje:
harness, model, instrukcje, reguły i umiejętności. Szablon zadania mówi, co zrobić; krok workflow łączy jedno
z drugim.”

**Po co tworzyć agentów.** Agent nadaje nazwę sposobowi pracy: „dokładny recenzent na mocnym modelu”, „tani
programista z regułami naszego stosu”, „analityk piszący w naszych formatach”. Wybierasz go w dowolnym kroku,
niezależnie od szablonu. Ponieważ agent jest kompletny (harness, model, instrukcje, reguły, umiejętności, serwery
MCP), możesz wykonać to samo zadanie dwoma agentami i uczciwie porównać koszt i jakość ([Ewaluacja](evaluation.md));
szablon pozostaje ten sam.

| Pole | Znaczenie |
|---|---|
| **Rodzaj** (**Kind**; harness) | **Odpowiedź modelu**: jedno wywołanie modelu wewnątrz Back Office, bez narzędzi i repozytorium. **OpenCode** lub **Claude Code**: sesja agenta kodującego w sandboxie runnera, z obszarem roboczym kroku. |
| **Model** | Opcjonalny. Puste oznacza model kroku albo ustawienia domyślne dla jego rodzaju: projektu, potem osoby uruchamiającej, potem platformy. |
| **Instrukcje** (**Instructions**) | Rola agenta i sposób pracy, w markdownie, na przykład „Jesteś uważnym recenzentem. Każde znalezisko wyjaśniaj z plikiem i linią.” Mogą używać wartości `{{project.*}}` i trafiają po preambule platformy. |
| **Reguły** (**Rules**) | [Reguły](#rules), których agent zawsze przestrzega, po regułach automatycznych. |
| **Umiejętności** (**Skills**) | [Umiejętności](#skills), które agent otwiera, gdy ich potrzebuje. |
| **Serwery MCP** (**MCP servers**) | Zdalne serwery MCP (nazwa i adres `http://` lub `https://`), z których agent może korzystać. Tylko agenci kodujący: odpowiedź modelu nie ma narzędzi. Zob. [Serwery MCP agenta](#agent-mcp). |
| **Nazwa**, **Opis**, **Zakres**, **Tagi** | Jak w regułach; klucz powstaje z nazwy. |

Lista pokazuje dla każdego agenta **Rodzaj**, **Model** i **Środowisko** (**Environment**: ile ma reguł
i umiejętności).

Agenci wbudowani (żaden nie ma własnego modelu; każdy wymienia reguły `agent-ground-rules` i `ask-dont-guess`):

| Agent | Rodzaj | Umiejętności | Używają go |
|---|---|---|---|
| **Odpowiedź modelu** (**Model answer**, `model-answer`) | Odpowiedź modelu | brak | `llm-task`; każdy krok, który nie wskazuje agenta |
| **Analyst** (`analyst`) | Odpowiedź modelu | `user-story-format`, `use-case-format`, `adr-format`, `definition-of-ready-done`, `build-guardrails` | szablony analizy, specyfikacji i planowania (`write-user-story`, `write-use-case`, `write-adr`, `impact-analysis`, `plan-task-list`, `clarify-spec`) |
| **Reviewer** (`reviewer`) | Odpowiedź modelu | `security-review-checklist`, `definition-of-ready-done` | `code-review`, `review-against-spec` |
| **OpenCode** (`opencode`) | OpenCode | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | fazy Spec Kit, `implement-opencode`, `implement-task-list`, `prepare-workspace` |
| **Claude Code** (`claude-code`) | Claude Code | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | `agent-task` |

**Analyst** i **Reviewer** mają krótkie instrukcje swojej roli (nazwy wpisów wbudowanych są po angielsku).
Agentów wbudowanych nie można zmieniać; **Dostosuj dla projektu** tworzy kopię projektową, którą możesz zmienić
(na przykład inny model albo reguły stosu projektu). Agenci mają te same uprawnienia, zakresy i audyt co reguły.
Agenta, którego używa szablon, nie można usunąć: najpierw wybierz w tych szablonach innego agenta.

**Kto wykonuje krok**: **Agent** wybrany w kroku w projektancie, w przeciwnym razie **Agent** szablonu
(dziedziczony, wygrywa najbliższy w łańcuchu), w przeciwnym razie **Odpowiedź modelu**.

**Jaki model**: **Profil modelu** kroku, w przeciwnym razie agenta, w przeciwnym razie szablonu, a potem ustawienia
domyślne (projekt, osoba uruchamiająca, platforma).

**Jak krok działa**, wynika z agenta i szablonu:

| Agent | Szablon | Krok |
|---|---|---|
| Odpowiedź modelu | każdy, który nie wymaga agenta kodującego | jedno wywołanie modelu: ustrukturyzowana odpowiedź, pewność i ochrona przed wstrzyknięciem promptu |
| OpenCode lub Claude Code | z fazą Spec Kit `phase` w konfiguracji | faza Spec Kit |
| OpenCode lub Claude Code | każdy inny | zadanie agenta: push i merge request zgodnie z konfiguracją; bez pushu jego końcowa odpowiedź staje się artefaktem `result` |

Krok nie może zostać wykonany (projektant i podgląd mówią dlaczego), gdy szablon wymaga agenta kodującego (faza
Spec Kit `phase`, `push: true`, `prepareOnly`), a dostaje **Odpowiedź modelu**.

**Agent kodujący odpowiadający według schematu.** Szablon ze **Schematem wyjścia** działa także z agentem
kodującym. Agent dostaje polecenie, by zapisać odpowiedź jako jeden obiekt JSON w pliku `structured-answer.json`
w swoim katalogu roboczym. Runner zbiera plik (artefakt `structured-answer`) i nie dodaje go do commita; Back Office
sprawdza go względem schematu jak odpowiedź modelu, kopiuje pole pewności i zapisuje pola wymienione w
**Produkuje** jako artefakty. Brak pliku albo niepoprawny plik oznacza nieudany krok wraz z powodami.

<a id="environment"></a>
### Środowisko agenta

Przed swoim zadaniem każdy krok dostaje jeden dokument opisujący, gdzie i jak pracuje, w tej kolejności:

1. **preambuła platformy**, ustalona przez Back Office dla rodzaju agenta: czym jest Back Office, uczciwość, zakres,
   bezpieczeństwo; dla odpowiedzi modelu „bez repozytorium, bez narzędzi, przy schemacie tylko JSON”; dla agenta
   kodującego „runner commituje i wypycha, nigdy nie osłabiaj testu, cytuj ścieżki plików, gdzie są Twoje
   umiejętności”;
2. **Instrukcje** agenta;
3. konstytucja projektu, jeśli projekt ją ma;
4. reguły: automatyczne (globalne, potem projektowe), potem reguły agenta.

Każdy harness dostaje go w swojej postaci:

| Agent | Gdzie trafia środowisko | Umiejętności |
|---|---|---|
| Odpowiedź modelu | prompt systemowy: środowisko, potem prompt systemowy szablonu, potem umiejętności | dopisane do promptu systemowego |
| OpenCode | `/workspace/.backoffice-agent/AGENTS.md`, wymieniony w `instructions` generowanego `opencode.json` | `.opencode/skills/<key>/SKILL.md` |
| Claude Code | pamięć użytkownika `$CLAUDE_CONFIG_DIR/CLAUDE.md` | `.claude/skills/<key>/SKILL.md` |

Środowisko jest zapisywane poza każdym repozytorium. Własne pliki `AGENTS.md` i `CLAUDE.md` repozytorium zostają
nietknięte, a harness czyta je jak zwykle, więc instrukcje dla agentów zapisane w Twoim projekcie działają obok
instrukcji Back Office. Katalogi umiejętności są wyłączone z gita, więc nic nie trafia do commita.

<a id="agent-mcp"></a>
### Serwery MCP agenta

Agent kodujący może wymienić zdalne serwery MCP: nazwę (małe litery, cyfry i `-`, najwyżej 40 znaków) i adres
`http://` lub `https://`. Runner konfiguruje je dla harnessu (Claude Code `--mcp-config`, OpenCode wpisy `mcp`
typu `remote`). Dane uwierzytelniające nie są jeszcze obsługiwane: wymieniaj tylko serwery, które ich nie
potrzebują. O tym, czy serwer jest osiągalny, decyduje sandbox: jego polityka ruchu wychodzącego ([Polityka
sandboxa](projects.md#sandbox-policy)) musi dopuszczać host, inaczej proxy odrzuca połączenie, a agent widzi serwer
jako niedostępny.

### Kroki: tworzenie agenta

1. Kliknij **Utwórz agenta** (**Create agent**).
2. Wpisz **Nazwę** i **Opis**.
3. Wybierz **Rodzaj** (**Odpowiedź modelu**, **OpenCode** lub **Claude Code**) i, jeśli agent ma zawsze używać
   jednego modelu, **Model**.
4. Napisz **Instrukcje**: rolę i sposób pracy, krótko i konkretnie.
5. Wybierz **Reguły** i **Umiejętności**; dla agenta kodującego opcjonalnie dodaj **Serwery MCP** (**Dodaj serwer
   MCP**, **Add MCP server**).
6. Ustaw **Zakres** (**Globalny** lub **Projekt**) i **Tagi**.
7. Kliknij **Utwórz agenta**.

Aby porównać harnessy, modele albo sposoby pracy na tym samym zadaniu, utwórz agenta dla każdego wariantu (na
przykład „Claude Code z modelem X” i „OpenCode z modelem Y”, te same reguły i umiejętności) i wybierz go w kroku.
Zmierz warianty jako kandydatów [benchmarku](evaluation.md#benchmarks) i na prawdziwych uruchomieniach w
[Agenci: koszt i skuteczność](costs-insights.md#agent-insights): każdy krok zadania zapisuje agenta i model, z którym
działał.

<a id="skills"></a>
## Umiejętności

**Biblioteka** → **Zadania** → **Umiejętności** (**Skills**, `/library/skills`) wyświetla wiedzę i procedury,
które agent otwiera, gdy zadanie ich wymaga, z kolumnami **Zakres**, **Tagi**, **Rozmiar** (**Size**)
i **Zaktualizowano**. Umiejętność stosuje konwencję Agent Skills: nagłówek frontmatter z `name` i `description`,
a potem krótkie, konkretne, ponumerowane kroki.

Krok dostaje umiejętności swojego agenta (**Umiejętności** agenta). W projektancie krok może jeszcze dodać
umiejętności tylko dla siebie (**Dodaj umiejętności do umiejętności agenta**, **Add skills to the agent's**) albo
niektóre pominąć (**Pomiń umiejętności agenta**, **Drop skills of the agent**); gdzie indziej agent je zachowuje.

Jak umiejętność trafia do agenta:

- Kroki wykonywane przez **Odpowiedź modelu** dostają umiejętność dopisaną do promptu systemowego (pojedyncze
  wywołanie modelu nie może otwierać plików).
- Kroki wykonywane przez agenta kodującego (także fazy Spec Kit) dostają ją zapisaną jako
  `.claude/skills/<key>/SKILL.md` i `.opencode/skills/<key>/SKILL.md`, które Claude Code i OpenCode
  wczytują samodzielnie; preambuła platformy mówi agentowi, gdzie są.

### Kroki: tworzenie umiejętności

1. Kliknij **Utwórz umiejętność** (**Create skill**).
2. Wpisz **Nazwę**, **Opis**, **Zakres** (**Globalny** lub **Projektowy**) i **Tagi**.
3. Napisz treść na karcie **SKILL.md**; sprawdź ją na karcie **Podgląd** (**Preview**).
4. Kliknij **Utwórz umiejętność**.

Umiejętność projektowa o kluczu umiejętności globalnej zastępuje ją dla agentów tego projektu, bez
zmiany żadnego agenta. Umiejętności, którą wymienia agent, nie można usunąć: najpierw usuń ją z tych agentów;
umiejętności wbudowanych nie można usuwać.

**Opis** pisz w formie „Use when ...” („Użyj, gdy ...”): po nim agent decyduje, czy otworzyć umiejętność.
To, czego zadanie musi zawsze przestrzegać, należy do [reguły](#rules), nie do umiejętności. Niektóre wbudowane
prompty wymieniają też umiejętność, na której opiera się zadanie („Apply the skill ...”); rób tak samo we własnych
szablonach, gdy jedna umiejętność jest dla zadania kluczowa.

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

Back Office zawiera szablony faz Spec Kit, implementacji, `clarify-spec`, `code-review`, `analyse-issue` oraz
zadań SDLC, takich jak `impact-analysis`, `write-user-story`, `plan-task-list`, `implement-task-list`
i `review-against-spec`, ogólne `llm-task` i `agent-task`, a także umiejętności, reguły i pięciu wbudowanych
[agentów](#agents). Wbudowane szablony, umiejętności i reguły możesz edytować; Back Office nigdy nie nadpisuje zmienionego wpisu. Dawne szablony **Fetch issue**,
**Notify Jira**, **Transition issue**, **Start workflow** i **Project digest** nie należą już do biblioteki: są
[akcjami](workflows.md#actions) w projektancie, a workflowy, które ich używały, działają dalej.

Szablon z tagiem `deprecated` działa dalej w krokach, które go używają, ale projektant, paleta MCP i lista (chyba
że włączysz **Pokaż przestarzałe**, **Show deprecated**) już go nie proponują. Przestarzałe są teraz (ADR 0054):
`implement-claude-code` (to `implement-opencode` z agentem Claude Code; użyj `implement-opencode`,
`implement-task-list` albo `agent-task` i wybierz w kroku agenta **Claude Code**), `analyse-issue` (użyj
`impact-analysis`) oraz, od ADR 0058, abstrakcyjne `base-agent`, `base-llm` i `base-coding-agent`: ich tekst jest
teraz preambułą platformy w [środowisku agenta](#environment), wbudowane szablony już ich nie rozszerzają, a zostają
tylko dla Twoich szablonów, które to robią.

<a id="built-in-updates"></a>
### Aktualizacje biblioteki wbudowanej

Przy każdym starcie Back Office porównuje każdy wbudowany szablon, umiejętność, regułę i agenta z wersją,
którą dostarcza, pole po polu. Wpis wbudowany, którego nikt nie edytował (nadal zapisany przez system,
a w przypadku szablonu nadal w wersji 1), jest zastępowany wersją dostarczoną, gdy się różnią; wpis
zapisany przez człowieka albo kopia z tym samym kluczem, która nie jest wbudowana, zostaje bez zmian. Aby
dostać dostarczoną wersję zmienionego wpisu, porównaj go z **Podglądem rozwiązanego zadania** na świeżej
instalacji i przenieś to, czego potrzebujesz, albo usuń swoją niewbudowaną kopię i uruchom ponownie.

### Jak zbudowane są zadania wbudowane

- Wbudowane szablony są płaskie (ADR 0058): mówią, co zrobić, i wskazują swojego agenta; tylko
  `implement-task-list` (i przestarzały `implement-claude-code`) rozszerzają `implement-opencode`. Sposób pracy
  (uczciwość, zakres, bezpieczeństwo, reguły podstawowe, „runner commituje i wypycha po agencie”, umiejętności
  formatów) pochodzi od agenta i jego [środowiska](#environment).
- Zadania **Spec Kit**: prompt fazy specify zaczyna się linią `KLUCZ: podsumowanie`, potem opis zgłoszenia
  i kryteria akceptacji, potem instrukcje. Fazy plan i tasks nie wklejają specyfikacji ani planu, które
  Spec Kit czyta z repozytorium; przekazują uwagi do kroku i, w fazie plan, opcjonalną daną wejściową
  **Wskazówki techniczne** (`techHints`).
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
  i `use-case`. Akcja **Pobierz zgłoszenie** czyta podsumowanie, opis, kryteria akceptacji i etykiety, bez
  komentarzy i załączników.
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

Do pracy, którą opisujesz sam w węźle, służą dwa wbudowane szablony: przeciągnij klocek na kanwę i wpisz zadanie
w jego parametr **Task** (wieloliniowy) w sekcji **Parametry** panelu kroku. Bez wartości krok wykonuje zadanie
domyślne (dla **LLM task**: podsumuj materiał wejściowy; dla **Agent task**: opisz repozytorium). Kroki, które
przed wprowadzeniem parametrów zastąpiły prompt przez **Nadpisania** (**Overrides**) → **Zastąp** (**Replace**),
działają dalej; zastąpiony prompt wygrywa.

| Szablon | Kto go wykonuje | Do czego |
|---|---|---|
| **LLM task** (`llm-task`) | agent **Odpowiedź modelu**: jedno wywołanie modelu, bez repozytorium | podsumowania, szkice, odpowiedzi na podstawie materiału wejściowego przebiegu |
| **Agent task** (`agent-task`) | agent kodujący na runnerze (domyślnie **Claude Code**; innego wybierzesz w polu **Agent** kroku), z klonem repozytorium projektu | analiza kodu, przeglądy, odpowiedzi na pytania o repozytorium; z `push: true` w **Konfiguracji** także zmiany na gałęzi z merge requestem |

Oba wstawiają do promptu systemowego zgłoszenie (gdy przebieg je ma), daną wejściową **Materiał wejściowy** i uwagi
recenzenta (każde tylko wtedy, gdy istnieje), więc **Task** może zawierać samo zadanie. **Agent task** domyślnie nie wypycha zmian; w projekcie
bez repozytoriów działa w pustym katalogu roboczym, a jego końcowa odpowiedź staje się artefaktem `result`.
**LLM task** działa na serwerze Back Office i wymaga skonfigurowanego dostawcy modeli (klucza API); **Agent task**
działa na runnerze, więc na [osobistym runnerze](personal-runner.md) może zamiast tego korzystać z Twojego logowania
do Claude.

Aby przeszukiwać bibliotekę z IDE, użyj narzędzia MCP `search_task_library` (zob.
[IDE i MCP](integrations-ide.md#mcp-tools)).
