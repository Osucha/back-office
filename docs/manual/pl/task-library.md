# Biblioteka zadań

Biblioteka zadań przechowuje to, co robią agenci i jak pracują, niezależnie od workflowów, które z niej
korzystają. **Biblioteka** (**Library**) ma trzy grupy (ADR 0055, 0056):

- **Zadania** (**Tasks**): **Szablony**, **Umiejętności**, **Reguły** i **Agenci**, opisane w tym rozdziale;
- **Workflowy** (**Workflows**): workflowy współdzielone między projektami ([Biblioteka workflowów](workflow-library.md));
- **Źródła** (**Sources**): materiały, które kroki mogą podłączyć ([Źródła](#sources)).

## Jak to się łączy

| Element | Odpowiednik | Służy do |
|---|---|---|
| Szablon zadania | zlecenie: **co** zrobić | opisania pracy i niczego więcej: kategoria, jedno **Polecenie** (**Instructions**) z [parametrami](#parameters), konfiguracja, wyniki i umiejętności, których praca wymaga; może mieć jednego rodzica |
| Agent | wykonawca: **kto** pracuje i **jak** | nazwania sposobu pracy: harness (**Odpowiedź modelu**, **OpenCode** albo **Claude Code**), opcjonalnie model, **Instrukcje**, **Reguły**, **Umiejętności** i **Serwery MCP** ([Agenci](#agents)) |
| Reguła | stały rozkaz | zapisania tego, czego agent musi zawsze przestrzegać; jest zawsze w kontekście agenta |
| Umiejętność | rozdział podręcznika | dania agentowi wiedzy lub procedury, którą otwiera, gdy zadanie jej wymaga (plik `SKILL.md`) |
| Krok workflowu | zadanie na tablicy | połączenia jednego z drugim: szablon, agent, który wykonuje pracę (i opcjonalnie model), skąd każdy parametr szablonu bierze wartość, oraz uwagi dla tego kroku ([Węzły zadań](workflows.md#task-nodes)) |
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

Węzeł zadania w workflowie odwołuje się do szablonu po kluczu i przechowuje najwyżej uwagi dla swojego kroku.
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
podobnie abstrakcyjne, które mogą być bazą dla innych, ale nigdy nie są wykonywane. Filtruj tekstem, kategorią
i rodzajem agenta, którego wymaga szablon (**Wymaga agenta kodującego** / **Dowolny agent**, **Needs a coding agent**
/ **Any agent**; puste: **Dowolny rodzaj agenta**), oraz przełącznikiem **Szablony globalne** / **Globalne + KEY**
(**Global templates** / **Global + KEY**), aby uwzględnić własne szablony projektu.

Szablon to wyłącznie praca (ADR 0061). Nie wskazuje agenta ani modelu: to [krok workflowu](workflows.md#task-nodes)
wybiera, kto wykona pracę, więc ten sam szablon można wykonać różnymi agentami i ich porównać
([Ewaluacja](evaluation.md)). Co szablon nadal mówi o wykonawcy:

- **Wymaga agenta kodującego** (**Needs a coding agent**; znacznik tylko do odczytu na liście, w edytorze, w palecie
  projektanta i w kroku): jego konfiguracja, razem z konfiguracją rodziców, ma fazę Spec Kit `phase`, `push` albo
  `prepareOnly`. Taka praca wymaga agenta z harnessem **OpenCode** albo **Claude Code** (agent kodujący w sandboxie na
  runnerze, z repozytorium; może wypchnąć gałąź i otworzyć merge request). Znacznika nie ustawiasz; wynika
  z konfiguracji. Bez niego pracę może wykonać dowolny agent, także agent z harnessem **Odpowiedź modelu** (**Model
  answer**; jedno wywołanie modelu wewnątrz Back Office, bez repozytorium; może zwrócić ustrukturyzowany JSON
  i pewność).
- **Wymagane umiejętności** (**Required skills**): [umiejętności](#skills), których wymaga praca (zob. [Wymagane
  umiejętności](#required-skills)).

Szablon nie ma reguł: reguły to normy zachowania i należą do repozytorium, projektu i agenta. To, czego wymaga jedno
zadanie („odpowiedz po polsku”, „zmieniaj tylko katalog `docs`”), należy do **Polecenia** szablonu. Czwarte źródło
norm tylko zwiększyłoby ryzyko sprzeczności ([Gdy źródła są sprzeczne](#precedence)).

Szablon, którego wymagane [parametry](#parameters) nie mają jeszcze wartości, ma znacznik **wymaga parametrów**
(**needs parameters**): to zadanie do uzupełnienia w kroku workflowu. Pozostałe to gotowe klocki.

### Kroki: tworzenie szablonu

1. Kliknij **Nowy szablon** (**New template**) (wymaga `library.manage` albo `library.project.manage` dla
   szablonu projektu).
2. **Typ** (**Type**): **Nazwa**, **Opis**, **Na bazie** (**Based on**; opcjonalny, jeden rodzic: szablon
   dziedziczy jego polecenie, parametry i konfigurację, a zmienia tylko to, co ustawisz), **Kategoria** (**Category**;
   puste oznacza dziedziczoną), **Zakres** (**Globalny** lub **Projektowy** z wyborem **Projektu**) i **Wymagane
   umiejętności** (**Required skills**; umiejętności, których wymaga praca, dziedziczone i sumowane wzdłuż łańcucha;
   zob. [Wymagane umiejętności](#required-skills)). Nie ma tu agenta ani profilu modelu: wybiera je krok workflowu.
   Gdy konfiguracja wymaga agenta kodującego, nagłówek edytora pokazuje znacznik **Wymaga agenta kodującego**
   (po najechaniu widać powód).
3. **Polecenie** (**Instructions**): jeden tekst, który mówi, co zrobić i kiedy wynik jest wystarczająco dobry
   (kryteria akceptacji). Szablon z rodzicem najpierw wybiera **Dopisz do rodzica** (**Append to parent**; jego tekst
   idzie po tekście rodzica) albo **Zastąp rodzica** (**Replace parent**; liczy się tylko jego tekst). Nie piszesz
   roli („Jesteś recenzentem”): rola należy do agenta. Nie wklejasz też danych: platforma sama dodaje zgłoszenie,
   wyniki wcześniejszych kroków, dane podane przy starcie, uwagi recenzenta i odpowiedzi właściciela (zob.
   [Polecenie i to, co dostaje agent](#instructions)). To, czego wymaga to zadanie, zapisz w poleceniu.
4. **Parametry** (**Parameters**): dane wejściowe pracy (zob. [Parametry](#parameters)). Kliknij **Dodaj parametr**
   (**Add parameter**), potem ustaw nazwę, etykietę, typ, **Wymagany**, opcjonalną wartość domyślną i tekst
   pomocy. O tym, skąd pochodzi wartość, decyduje krok workflowu: wartość ustawiona w kroku, pole formularza startu
   albo wynik wcześniejszego kroku. Szablon oparty na innym pokazuje też **Wartości parametrów** (**Parameter
   values**): wartości parametrów jego rodziców.
5. **Konfiguracja** (**Configuration**): formularz z ustawieniami pracy, dla odpowiedzi modelu i agentów kodujących
   razem (dla agenta kodującego: polecenie weryfikacji i przygotowania, push, merge request, artefakty do zebrania,
   narzędzia agenta, faza Spec Kit, ...). Faza Spec Kit (`phase`), `push` albo `prepareOnly` (tylko przygotowanie) sprawiają, że
   szablon wymaga agenta kodującego. Ustaw tylko to, co różni się od szablonu bazowego; puste pola są dziedziczone. **Surowy JSON**
   (**Raw JSON**) pokazuje te same ustawienia jako JSON, także klucze, których formularz nie zna.
   **Zaawansowane: artefakty, schemat wyjścia** (**Advanced: artefacts, output schema**; zwinięte; większość
   szablonów niczego tu nie potrzebuje): **Abstrakcyjny** (**Abstract**; tylko baza dla innych szablonów, nigdy
   samodzielny krok), **Produkuje** (**Produces**) i **Konsumuje** (**Consumes**) (nazwy artefaktów, które krok
   zapisuje i odczytuje; kontrola workflowu ostrzega, gdy krok czyta coś, czego nikt nie zapisuje), **Pole pewności**
   (**Confidence field**; pole odpowiedzi kopiowane do `state.confidence` na potrzeby decyzji takich jak
   `confidence < 0.7`), **Tagi** (**Tags**) i **Schemat wyjścia** (**Output schema**; kształt ustrukturyzowanej
   odpowiedzi: model odpowiada według niego, agent kodujący zapisuje odpowiedź do `structured-answer.json`, zob.
   [Agenci](#agents)). Szablon nie ma ustawień sandboxa: sandbox ustawia się w projekcie
   ([Polityka sandboxa](projects.md#sandbox-policy)).
6. Kliknij **Utwórz szablon** (**Create template**) (lub **Zapisz szablon**, **Save template**, dla
   istniejącego).

<a id="instructions"></a>
### Polecenie i to, co dostaje agent

Szablon ma jeden tekst **Polecenie** (**Instructions**, ADR 0062). Nie ma już promptu systemowego ani promptu
użytkownika: stała rama wywołania (kto pracuje, według jakich reguł, gdzie) należy do agenta, a szablon, który
pisał ją jeszcze raz, tylko powtarzał swojego agenta albo mu przeczył. Platforma składa każde wywołanie tak samo:

| Agent | Rama (kto, jakie reguły, jak odpowiedzieć) | Zadanie |
|---|---|---|
| Odpowiedź modelu | prompt systemowy: [środowisko agenta](#environment) (preambuła platformy z tym, jak zadawać pytania, instrukcje agenta, konstytucja projektu, reguły), jego umiejętności, potem format odpowiedzi (schemat wyjścia) | wiadomość: **Polecenie** szablonu z uwagami kroku, potem **standardowe sekcje danych** |
| OpenCode, Claude Code | dokument środowiska w [domu](#agent-home) agenta, tam też jego umiejętności | prompt: ta sama wiadomość, potem format odpowiedzi (gdy szablon ma schemat wyjścia) i lista repozytoriów kroku |

Standardowe sekcje danych, każda tylko wtedy, gdy ma treść, a polecenie nie umieszcza jej samo za pomocą jej
znacznika:

| Sekcja | Znacznik, którym umieścisz ją sam |
|---|---|
| zgłoszenie (klucz, tytuł, opis, kryteria akceptacji) | `{{issue.*}}`, na przykład `{{issue.summary}}` |
| każdy artefakt, który szablon konsumuje (**Konsumuje**), diff na końcu | `{{artifacts.<nazwa>}}`, na przykład `{{artifacts.spec}}` |
| każda wartość parametru podana przy starcie, której polecenie nie używa | `{{params.<nazwa>}}` |
| uwagi recenzenta do tego kroku | `{{feedback}}` |
| odpowiedzi właściciela na pytania agenta | `{{answers}}` |

Polecenie może więc być samym zadaniem: „Sprawdź zmianę względem specyfikacji. Każde wymaganie dostaje MET,
PARTIAL albo MISSING; jedno MISSING oznacza werdykt REQUEST_CHANGES.” Szablon, który pisze gdzieś `{{issue.key}}`
albo `{{artifacts.spec}}`, nie dostaje drugiej kopii tej sekcji. Wartości z przebiegu (zgłoszenie, artefakty, dane
wpisane przy starcie, uwagi, odpowiedzi) zawsze docierają do agenta jako oznaczone bloki danych, nigdy jako
polecenia ([Prompt injection](agent-execution.md#prompt-injection)).

Inne znaczniki: `{{params.<nazwa>}}` ([parametr](#parameters)), `{{project.*}}`, `{{input.ci.*}}`,
`{{decision.comment}}` (komentarz **Zatwierdź**), `{{feedbackHistory}}` (wszystkie uwagi przebiegu). Sekcja
`{{#artifacts.review}}...{{/artifacts.review}}` zachowuje swój tekst tylko wtedy, gdy wartość istnieje
(`{{^path}}...{{/path}}` tylko wtedy, gdy jej nie ma). `{{feedback}}` zawiera wyłącznie uwagi do tego kroku:
komentarz opcji bramki, która odsyła pracę (**Popraw** (**Revise**), **Ponów** (**Retry**) albo każda opcja inna
niż **Zatwierdź** i **Odrzuć**), trafia do następnego uruchomionego kroku, zostaje przy nim na kolejne próby
i nigdy nie dociera do kroków po nim. Konstytucji projektu ani reguł szablon nie wstawia: każdy krok dostaje je
w środowisku swojego agenta.

Krok workflowu może dopisać do polecenia **uwagi** albo, rzadko, je zastąpić
([Polecenie dla tego kroku](workflows.md#task-nodes)).

### Scalanie z rodzicem

| Pole | Reguła |
|---|---|
| Kategoria | Wygrywa najbliższa w łańcuchu. |
| Schemat wyjścia, pole pewności | Wygrywa wartość potomka, jeśli jest ustawiona. |
| Polecenie | **Dopisz do rodzica** dodaje tekst potomka po tekście rodzica; **Zastąp rodzica** używa tylko tekstu potomka. Pusty tekst zachowuje polecenie rodzica. |
| Parametry | Suma według nazwy; parametr dziecka zastępuje parametr rodzica o tej samej nazwie. |
| Wartości parametrów | Wygrywa najbliższy szablon, który ustawia wartość. |
| Konfiguracja | Scalana klucz po kluczu; wygrywa potomek; `null` usuwa klucz. |
| Produkuje, konsumuje, tagi, wymagane umiejętności | Suma. |

Reguły i umiejętności nie są dziedziczone po szablonach: krok dostaje te, które ma jego agent. To, czy szablon
wymaga agenta kodującego, wynika ze scalonej konfiguracji.

<a id="required-skills"></a>
### Wymagane umiejętności

**Wymagane umiejętności** (**Required skills**) wskazują umiejętności, których wymaga praca, na przykład
`user-story-format` dla **Write user story**. Szablon nikomu ich nie daje: krok dostaje dokładnie umiejętności
swojego agenta. Służą do trzech rzeczy:

- dla upuszczonego szablonu projektant wstępnie wybiera agenta, który ma ich najwięcej
  ([Węzły zadań](workflows.md#task-nodes));
- krok, którego agent którejś nie ma, dostaje ostrzeżenie (**Brakująca umiejętność**, **Missing skill**); i tak się
  wykona;
- panel projektanta [Agenci w tym workflowie](workflows.md#agent-audit) pokazuje brakujące dla każdego agenta
  i dodaje je przyciskiem **Dodaj do agenta** (**Add to agent**), raz, przed jakimkolwiek uruchomieniem.

Umiejętności celowo nie są dodawane w czasie wykonania: środowisko agenta różniłoby się wtedy od kroku do kroku,
a porównanie jednego agenta w różnych krokach i benchmarkach przestałoby być uczciwe.

<a id="parameters"></a>
### Parametry

Czasem szablon jest gotowym klockiem („napisz user story”), czasem zadaniem z miejscem na konfigurację
(„podsumuj materiał dla tego odbiorcy”). Parametry to dane wejściowe pracy (ADR 0058, 0062): typowane pola, które
polecenie i ustawienia tekstowe czytają jako `{{params.<nazwa>}}`. Szablon tylko je deklaruje; krok workflowu mówi,
skąd pochodzi każda wartość.

| Typ | Wartość |
|---|---|
| **Jedna linia** (**Single line**) | krótki tekst |
| **Tekst wieloliniowy** (**Multi-line text**) | dłuższy tekst, na przykład cały opis zadania |
| **Liczba** (**Number**) | liczba |
| **Tak / nie** (**Yes / no**) | przełącznik |
| **Wybór** (**Choice**) | jedna z podanych opcji (parametr potrzebuje co najmniej jednej) |
| **Klucz zgłoszenia Jira** (**Jira issue key**) | klucz zgłoszenia, na przykład `SHOP-123`; pytany przy starcie jest kluczem zgłoszenia uruchomienia |

Parametr ma nazwę (zaczyna się literą; litery, cyfry i `_`, najwyżej 64 znaki), etykietę (najwyżej 200 znaków),
typ, **Wymagany**, opcjonalną wartość domyślną i opcjonalny tekst pomocy (najwyżej 1 000 znaków).

<a id="parameter-sources"></a><a id="run-inputs"></a>
#### Skąd pochodzi wartość

Wybiera krok workflowu, osobno dla każdego parametru ([Węzły zadań](workflows.md#task-nodes)):

| Źródło w kroku | Wartość |
|---|---|
| **Wartość** (**Value**) | wartość ustawiona w kroku, ustalona przy projektowaniu workflowu |
| **Zapytaj przy starcie** (**Ask at start**) | wpisuje ją osoba, która uruchamia przebieg, w formularzu startu; pole ma etykietę, typ, **Wymagany** i pomoc parametru |
| **Wynik wcześniejszego kroku** (**Result of an earlier step**) | treść artefaktu, który wytworzył wcześniejszy krok, wskazanego nazwą (na przykład `spec`) |
| brak | wartość najbliższego szablonu w łańcuchu (presetu, zob. niżej), w przeciwnym razie wartość domyślna parametru |

Gdy przebieg nie ma wartości dla parametru pytanego przy starcie albo powiązanego z wynikiem, obowiązuje wartość
presetu albo domyślna. Wymagany parametr bez żadnej wartości blokuje krok: kontrola workflowu go wskazuje,
a uruchomienie nie wystartuje; podgląd nadal działa. Szablony, których wymagane parametry nie mają wartości, mają
znacznik **wymaga parametrów** na liście i w palecie projektanta. Karta **Parametry** podglądu pokazuje każdy
parametr z wartością i jej źródłem (szablon, wartość domyślna albo „krok musi ją ustawić”).

Wartości pochodzące z przebiegu (wpisane przy starcie albo wzięte z wyniku) pochodzą spoza projektu workflowu:
agent dostaje je jako oznaczone dane, nigdy jako polecenia. Wartości podane przy starcie, których polecenie nie
używa, i tak docierają do agenta, każda w sekcji pod swoją etykietą ([Polecenie i to, co dostaje
agent](#instructions)).

**Szablony nie deklarują już danych wejściowych uruchomienia.** Do ADR 0062 szablon deklarował obok parametrów
własne „dane wejściowe uruchomienia” (run inputs). Szablon nie może wiedzieć, czy wartość ustawia projektant, czy
osoba uruchamiająca przebieg, więc teraz wybiera to krok: parametr z **Zapytaj przy starcie** to dawna dana
wejściowa. Aktualizacja zamieniła każdą daną wejściową w parametr o tej samej nazwie (typ **Jedna linia**, **Tekst
wieloliniowy** albo **Klucz zgłoszenia Jira**; `-` w nazwie zamieniony na `_`) i w każdym istniejącym kroku tego
szablonu ustawiła mu **Zapytaj przy starcie**, więc formularze startu się nie zmieniły. Większość wbudowanych
szablonów ma parametr **Materiał wejściowy** (**Input material**, `input`, wieloliniowy, opcjonalny), o który
domyślne workflowy pytają przy starcie.

**Utwórz preset** (**Create preset**). W edytorze zapisanego szablonu przycisk **Utwórz preset** (na dole)
otwiera nowy szablon, który rozszerza ten i ustawia tylko **Wartości parametrów**. Zapisz go pod własną nazwą, na
przykład „Podsumowanie dla komitetu sterującego”, a pojawi się w palecie jako gotowy klocek obok rodzica.

### Podgląd rozwiązanego zadania

Prawa strona edytora pokazuje **Podgląd rozwiązanego zadania** (**Resolved preview**): dokładnie to, co
otrzymuje agent po dziedziczeniu i przesłonięciu przez projekt, dla biblioteki globalnej lub wybranego
projektu. Pokazuje rodowód i kategorię oraz karty **Polecenie** (scalone wzdłuż łańcucha), **Umiejętności**
i **Reguły**, **Konfiguracja** i **Parametry**. Ponieważ szablon nie ma agenta, **Umiejętności**
i **Reguły** pokazują środowisko agenta, którego dostałby krok bez własnego agenta (**OpenCode**, gdy szablon wymaga
agenta kodującego, w przeciwnym razie **Odpowiedź modelu**; reguły automatyczne są oznaczone); krok z innym agentem
dostaje środowisko tamtego agenta. Jeśli wymagany parametr nie ma wartości, podgląd to wskazuje, a zadanie nie może
działać, dopóki tego nie poprawisz. Podgląd odzwierciedla zapisaną wersję; niezapisane zmiany są wymienione jako
różnice.

### Kroki: zmiana zadania dla jednego projektu

1. W wierszu szablonu kliknij **Dostosuj dla projektu** (**Customize for project**).
2. Otworzy się nowy szablon z zakresem **Projektowy** i szablonem globalnym jako rodzicem; zachowuje klucz
   szablonu globalnego, więc zastępuje go w tym projekcie. Wybierz projekt i zmień tylko to, co się różni, na przykład dopisz akapit do
   polecenia. (Agenta wybiera się w kroku workflowu, nie tutaj.)
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
swoje stałe instrukcje, poza repozytorium. Wiadomość zadania nie powtarza reguł, więc agent, który [kontynuuje
swoją sesję](workflows.md#continue-session), nie dostaje ich drugi raz.

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
| **Rodzaj** (**Kind**; harness) | **Odpowiedź modelu**: jedno wywołanie modelu wewnątrz Back Office, bez narzędzi i repozytorium. **OpenCode** lub **Claude Code**: sesja agenta kodującego w sandboxie runnera, w [katalogu przebiegu](workflows.md#run-directory). |
| **Model** | Opcjonalny. Puste oznacza model kroku albo ustawienia domyślne dla jego rodzaju: projektu, potem osoby uruchamiającej, potem platformy. |
| **Instrukcje** (**Instructions**) | Rola agenta i sposób pracy, w markdownie, na przykład „Jesteś uważnym recenzentem. Każde znalezisko wyjaśniaj z plikiem i linią.” Mogą używać wartości `{{project.*}}` i trafiają po preambule platformy. |
| **Reguły** (**Rules**) | [Reguły](#rules), których agent zawsze przestrzega, po regułach automatycznych. |
| **Umiejętności** (**Skills**) | [Umiejętności](#skills), które agent otwiera, gdy ich potrzebuje. |
| **Serwery MCP** (**MCP servers**) | Zdalne serwery MCP (nazwa i adres `http://` lub `https://`), z których agent może korzystać. Tylko agenci kodujący: odpowiedź modelu nie ma narzędzi. Zob. [Serwery MCP agenta](#agent-mcp). |
| **Dostęp** (**Access**) | Tylko agenci kodujący: co agent może zmieniać w repozytoriach swoich kroków: **Pełny** (**Full**, domyślnie), **Tylko odczyt** (**Read only**) albo **Tylko te ścieżki** (**Only these paths**). Zob. [Dostęp agenta](#agent-access). |
| **Katalog** (**Directory**) | Tylko agenci kodujący: **Katalog przebiegu (wspólny)** (**Run directory (shared)**, domyślnie) albo **Osobny katalog** (**Separate directory**). Zob. [Dostęp agenta](#agent-access). |
| **Sesja** (**Session**) | Tylko agenci kodujący: **Na krok (powtórzenia kontynuują)** (**Per step (repeats continue)**, domyślnie), **Jedna na przebieg** (**One per run**) albo **Zawsze od nowa** (**Always fresh**). Zob. [Sesje agenta](workflows.md#continue-session). |
| **Nazwa**, **Opis**, **Zakres**, **Tagi** | Jak w regułach; klucz powstaje z nazwy. |

Lista pokazuje dla każdego agenta **Rodzaj**, **Model**, **Dostęp** (**Access**), **Sesję** (**Session**) i **Środowisko** (**Environment**:
ile ma reguł i umiejętności). Własnych agentów projektu i globalnych, z których może korzystać, pokazuje też karta
projektu **Agenci** ([Agenci projektu](projects.md#project-agents)).

Środowisko ustawia się raz, na agencie (ADR 0060): krok wybiera agenta, ale nie dodaje ani nie pomija jego
umiejętności. Na wariant utwórz innego agenta (albo kopię projektową).

Agenci wbudowani (żaden nie ma własnego modelu; każdy wymienia reguły `agent-ground-rules` i `ask-dont-guess`):

| Agent | Rodzaj | Umiejętności | Pasuje do |
|---|---|---|---|
| **Odpowiedź modelu** (**Model answer**, `model-answer`) | Odpowiedź modelu | brak | `llm-task`; krok bez własnego agenta, którego szablon nie wymaga agenta kodującego |
| **Analyst** (`analyst`) | Odpowiedź modelu | `user-story-format`, `use-case-format`, `adr-format`, `definition-of-ready-done`, `build-guardrails` | szablony analizy, specyfikacji i planowania (`write-user-story`, `write-use-case`, `write-adr`, `impact-analysis`, `plan-task-list`, `clarify-spec`) |
| **Reviewer** (`reviewer`) | Odpowiedź modelu | `security-review-checklist`, `definition-of-ready-done` | `code-review`, `review-against-spec` |
| **OpenCode** (`opencode`) | OpenCode | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | fazy Spec Kit, `implement-opencode`, `implement-task-list`, `prepare-workspace`; krok bez własnego agenta, którego szablon wymaga agenta kodującego |
| **Claude Code** (`claude-code`) | Claude Code | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | `agent-task`, dowolna praca z kodem |

**Wymagane umiejętności** szablonów wbudowanych pasują do tych agentów, więc projektant wybiera ich wstępnie.
**Analyst** i **Reviewer** mają krótkie instrukcje swojej roli (nazwy wpisów wbudowanych są po angielsku).
Agentów wbudowanych nie można zmieniać; **Dostosuj dla projektu** tworzy kopię projektową, którą możesz zmienić
(na przykład inny model albo reguły stosu projektu). Agenci mają te same uprawnienia, zakresy i audyt co reguły.
Szablony nie wskazują agentów, więc żaden szablon nie blokuje usunięcia agenta; kroki workflowów, które nadal go
wskazują, pokazują **Nieznany agent** (**Unknown agent**) w panelu [Agenci w tym workflowie](workflows.md#agent-audit)
i nie mogą się wykonać, dopóki nie wybierzesz innego agenta.

**Kto wykonuje krok** (ADR 0061): **Agent** wybrany w kroku w projektancie. Krok bez własnego agenta (na przykład
zapisany, zanim pojawili się agenci) wykonuje **OpenCode**, gdy jego szablon wymaga agenta kodującego, w przeciwnym
razie **Odpowiedź modelu**; projektant pokazuje to jako **Domyślnie: {agent}** (**Default: {agent}**). Gdy
upuszczasz szablon na tablicę, projektant wstępnie wybiera agenta za Ciebie ([Węzły zadań](workflows.md#task-nodes)).

**Jaki model**: **Profil modelu** kroku, w przeciwnym razie **Model** agenta, a potem ustawienia domyślne dla rodzaju
agenta (projekt, osoba uruchamiająca, platforma). Szablony nie mają modelu.

**Jak krok działa**, wynika z agenta i szablonu:

| Agent | Szablon | Krok |
|---|---|---|
| Odpowiedź modelu | każdy, który nie wymaga agenta kodującego | jedno wywołanie modelu: ustrukturyzowana odpowiedź, pewność i ochrona przed wstrzyknięciem promptu |
| OpenCode lub Claude Code | z fazą Spec Kit `phase` w konfiguracji | faza Spec Kit |
| OpenCode lub Claude Code | każdy inny | zadanie agenta: push i merge request zgodnie z konfiguracją; bez pushu jego końcowa odpowiedź staje się artefaktem `result` |

Krok nie może zostać wykonany (projektant i podgląd mówią dlaczego), gdy jego szablon wymaga agenta kodującego
(faza Spec Kit `phase`, `push: true`, `prepareOnly`), a agent kroku jest agentem odpowiedzi modelu („Szablon wymaga
agenta kodującego: ten krok nie zadziała z agentem odpowiedzi modelu.”).

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
   umiejętności”; gdy krok pozwala agentowi pytać ([Agent zadaje pytania](workflows.md#agent-questions)), także jak
   pytać;
2. **Instrukcje** agenta;
3. konstytucja projektu, jeśli projekt ją ma;
4. reguły: automatyczne (globalne, potem projektowe), potem reguły agenta.

Odpowiedź modelu dostaje go na początku promptu systemowego, potem swoje umiejętności (dopisane, bo pojedyncze
wywołanie modelu nie może otwierać plików), potem format odpowiedzi. Szablon nic nie dodaje do promptu systemowego:
jego [polecenie](#instructions) jest wiadomością zadania.

<a id="agent-home"></a>Agent kodujący dostaje własny **dom** (agent home) w [katalogu przebiegu](workflows.md#run-directory),
poza każdym repozytorium: `/workspace/.agents/<klucz agenta>/`. Tam jest wszystko, co należy do agenta (ADR 0060):

| Agent | Dom to | Zawiera |
|---|---|---|
| Claude Code | jego katalog konfiguracji (`CLAUDE_CONFIG_DIR`) | `CLAUDE.md` (środowisko, jako pamięć użytkownika Claude Code), `skills/<key>/SKILL.md`, `mcp.json` (jego serwery MCP), `settings.json` (reguły narzędzi, tylko przy polityce narzędzi albo ograniczonym dostępie) i własne pliki sesji Claude Code |
| OpenCode | jego katalog konfiguracji (`OPENCODE_CONFIG_DIR`) | `opencode.json` (wymienia `AGENTS.md` w `instructions`, do tego serwery MCP i uprawnienia edycji), `AGENTS.md` (środowisko), `skills/<key>/SKILL.md` i sesje w `data/<sesja>` |

Nic z agenta nie jest już zapisywane w repozytorium, więc nic nie trafia do commita, a dwaj agenci pracujący
w jednym katalogu przebiegu nigdy nie widzą swoich umiejętności ani pamięci. Umiejętności są zapisywane od nowa przy
każdym zadaniu, więc agent zawsze pracuje ze swoim aktualnym środowiskiem. Własne pliki `AGENTS.md` i `CLAUDE.md`
repozytorium zostają nietknięte: harness czyta je jak zwykle i są regułami projektu dla każdego agenta, obok
środowiska Back Office. OpenCode czyta też własne umiejętności repozytorium (`.opencode/skills`, `.claude/skills`):
to umiejętności projektu, dostępne dla każdego agenta OpenCode.

<a id="precedence"></a>
### Gdy źródła są sprzeczne

Agent słyszy wiele źródeł: konstytucję projektu, własne pliki `AGENTS.md` / `CLAUDE.md` repozytorium, reguły
projektu i agenta, swoje umiejętności i zadanie. Preambuła platformy podaje kolejność na wypadek, gdy sobie
przeczą (ADR 0061):

1. **konstytucja** projektu;
2. **reguły**: `AGENTS.md` / `CLAUDE.md` repozytorium, potem reguły projektu, potem reguły agenta;
3. **umiejętności**;
4. **zadanie** (polecenie szablonu i uwagi kroku).

Gdy agent nie umie rozstrzygnąć sprzeczności, pyta ([Agent zadaje pytania](workflows.md#agent-questions)) zamiast
zgadywać. Aby znaleźć sprzeczności przed uruchomieniem, użyj **Sprawdź spójność** (**Check coherence**) w panelu
[Agenci w tym workflowie](workflows.md#coherence-check).

<a id="agent-mcp"></a>
### Serwery MCP agenta

Agent kodujący może wymienić zdalne serwery MCP: nazwę (małe litery, cyfry i `-`, najwyżej 40 znaków) i adres
`http://` lub `https://`. Runner konfiguruje je dla harnessu (Claude Code `--mcp-config`, OpenCode wpisy `mcp`
typu `remote`). Dane uwierzytelniające nie są jeszcze obsługiwane: wymieniaj tylko serwery, które ich nie
potrzebują. O tym, czy serwer jest osiągalny, decyduje sandbox: jego polityka ruchu wychodzącego ([Polityka
sandboxa](projects.md#sandbox-policy)) musi dopuszczać host, inaczej proxy odrzuca połączenie, a agent widzi serwer
jako niedostępny.

<a id="agent-access"></a>
### Dostęp agenta

**Dostęp** (**Access**) mówi, co agent kodujący może zmieniać w repozytoriach swoich kroków (ADR 0060):

| Dostęp | Agent może zmieniać |
|---|---|
| **Pełny** (**Full**, domyślnie) | wszystko |
| **Tylko odczyt** (**Read only**) | nic |
| **Tylko te ścieżki** (**Only these paths**) | tylko pliki pasujące do podanych wzorców ścieżek, względem katalogu głównego repozytorium, na przykład `docs/**` (`**` dowolne katalogi, `*` w obrębie jednej nazwy; zwykła ścieżka, np. `docs`, obejmuje wszystko pod nią) |

Jest egzekwowany dwa razy:

1. **Własne reguły narzędzi harnessu**: Claude Code nie dostaje narzędzi edycji dla innych plików, a uprawnienie
   edycji OpenCode ich zabrania, więc agent wcześnie dowiaduje się, że nie może.
2. **Runner, gdy agent skończy**: tuż przed startem agenta zapisuje stan każdego repozytorium kroku, a potem cofa
   każdą zmianę spoza dostępu (dodane pliki usuwa, zmienione lub usunięte przywraca), cokolwiek agent zrobił, także
   poleceniami powłoki. Log kroku wypisuje każdą cofniętą ścieżkę, a jego podsumowanie kończy się wierszem
   `[Back Office runner] reverted N change(s) outside the agent's access (...)`. Dopiero potem wykonują się polecenie
   weryfikacji, commit i push. Pliki ignorowane przez gita nie są objęte (i tak nigdy nie trafiają do commita);
   artefakty kroku są zbierane przed cofnięciem.

Agent z dostępem **Tylko odczyt** w kroku, który wypycha, to problem: krok nie wykona się, dopóki nie zmienisz agenta
albo nie wyłączysz pushu. To, czy krok commituje i wypycha, pozostaje ustawieniem kroku; dostęp tylko zawęża, co
agent może zmieniać. Odpowiedź modelu nie ma repozytorium, więc dostęp jej nie dotyczy.

**Katalog** (**Directory**) mówi, co agent może widzieć z przebiegu (ADR 0062):

| Katalog | Agent pracuje w |
|---|---|
| **Katalog przebiegu (wspólny)** (**Run directory (shared)**, domyślnie) | [katalogu przebiegu](workflows.md#run-directory): kroki kodujące przebiegu pracują w nim jeden po drugim i widzą, co zostawiły wcześniejsze kroki, także niezacommitowaną pracę |
| **Osobny katalog** (**Separate directory**) | świeżej kopii tylko repozytoriów kroku, usuwanej po kroku; nie widzi, co zostawiły inne kroki, a jego sesja nigdy nie jest kontynuowana |

Dostęp niczego nie ukrywa: agent, który nie może widzieć reszty pracy przebiegu, potrzebuje **Osobnego katalogu**,
a taki, który w ogóle nie może widzieć kodu, także kroku z tylko tymi repozytoriami, które może widzieć
([Repozytoria kroku](workflows.md#step-repositories)). Do niezależnej kontroli utwórz agenta kodującego
z **Osobnym katalogiem** (na przykład kopię projektową **Claude Code** z dostępem **Tylko odczyt**) i wybierz go
w kroku. Do ADR 0062 katalog był ustawieniem kroku; teraz należy do agenta (co aktualizacja zrobiła z zapisanymi
krokami, opisuje [Sesje agenta](workflows.md#continue-session)).

### Kroki: tworzenie agenta

1. Kliknij **Utwórz agenta** (**Create agent**; w bibliotece albo, dla agenta projektu, na karcie projektu
   **Agenci**).
2. Wpisz **Nazwę** i **Opis**.
3. Wybierz **Rodzaj** (**Odpowiedź modelu**, **OpenCode** lub **Claude Code**) i, jeśli agent ma zawsze używać
   jednego modelu, **Model**.
4. Napisz **Instrukcje**: rolę i sposób pracy, krótko i konkretnie.
5. Wybierz **Reguły** i **Umiejętności**; dla agenta kodującego opcjonalnie dodaj **Serwery MCP** (**Dodaj serwer
   MCP**, **Add MCP server**) i ustaw **Dostęp** (dla **Tylko te ścieżki** podaj ścieżki, które agent może zmieniać,
   na przykład `docs/**`), **Katalog** i **Sesję**.
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

Krok dostaje umiejętności swojego agenta (**Umiejętności** agenta), ani więcej, ani mniej: krok nie może dodawać
ani pomijać umiejętności (ADR 0060). Szablon może wymienić umiejętności, których wymaga jego praca ([Wymagane
umiejętności](#required-skills)); krok, którego agent którejś nie ma, dostaje ostrzeżenie, a **Dodaj do agenta**
w panelu [Agenci w tym workflowie](workflows.md#agent-audit) naprawia to na agencie.

Jak umiejętność trafia do agenta:

- Kroki wykonywane przez **Odpowiedź modelu** dostają umiejętność dopisaną do promptu systemowego (pojedyncze
  wywołanie modelu nie może otwierać plików).
- Kroki wykonywane przez agenta kodującego (także fazy Spec Kit) dostają ją zapisaną w domu agenta jako
  `skills/<key>/SKILL.md` ([Środowisko agenta](#environment)), nigdy w repozytorium; Claude Code i OpenCode
  wczytują ją samodzielnie, a preambuła platformy mówi agentowi, gdzie są jego umiejętności.

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
polecenia wymieniają też umiejętność, na której opiera się zadanie („Apply the skill ...”); rób tak samo we własnych
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

- Wbudowane szablony są płaskie (ADR 0058): każdy ma jedno **Polecenie** (ADR 0062), które mówi, co zrobić i jak
  ocenić wynik, bez zdań o roli („Jesteś analitykiem ...”): rola należy do agenta. Agenta nie wskazują (ADR 0061;
  domyślne workflowy wskazują agenta w każdym kroku), a zgłoszenie, konsumowane artefakty, materiał wejściowy
  i uwagi zostawiają sekcjom danych platformy; tylko
  `implement-task-list` (i przestarzały `implement-claude-code`) rozszerzają `implement-opencode`. Sposób pracy
  (uczciwość, zakres, bezpieczeństwo, reguły podstawowe, „runner commituje i wypycha po agencie”, umiejętności
  formatów) pochodzi od agenta i jego [środowiska](#environment).
- Zadania **Spec Kit**: polecenie fazy specify zaczyna się linią `KLUCZ: podsumowanie`, potem opis zgłoszenia
  i kryteria akceptacji, potem instrukcje. Fazy plan i tasks nie wklejają specyfikacji ani planu, które
  Spec Kit czyta z repozytorium; przekazują uwagi do kroku i, w fazie plan, opcjonalny parametr
  **Wskazówki techniczne** (**Technical hints**, `techHints`, w domyślnych workflowach pytany przy starcie).
- **Implementacja** (`implement-opencode`, `implement-claude-code`): runner uruchamia `verifyCommand`
  z **Konfiguracji**, jeśli go ustawisz (domyślnie brak), i oznacza krok jako nieudany, gdy polecenie
  zakończy się kodem różnym od zera; merge request używa kluczy `mergeRequest` (true/false),
  `mergeRequestTitleTemplate` i `mergeRequestDraft`.
- **Poprawki po przeglądzie**: gdy przebieg ma artefakt `review`, polecenie implementacji zawiera go pod
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
### Zadania opisywane w kroku

Do pracy, którą opisujesz sam w węźle, służą dwa wbudowane szablony: przeciągnij klocek na kanwę i wpisz zadanie
w jego parametr **Task** (wieloliniowy) w sekcji **Parametry** panelu kroku. Bez wartości krok wykonuje zadanie
domyślne (dla **LLM task**: podsumuj materiał wejściowy; dla **Agent task**: opisz repozytorium). Kroki, które
zastąpiły prompt przed wprowadzeniem parametrów, działają dalej: aktualizacja przeniosła ten tekst do **Polecenie
dla tego kroku** → **Zastąp (zaawansowane)** (**Replace (advanced)**), które wygrywa z poleceniem szablonu.

| Szablon | Wybierz w kroku | Do czego |
|---|---|---|
| **LLM task** (`llm-task`) | agenta odpowiedzi modelu, na przykład **Odpowiedź modelu**: jedno wywołanie modelu, bez repozytorium | podsumowania, szkice, odpowiedzi na podstawie materiału wejściowego przebiegu |
| **Agent task** (`agent-task`) | agenta kodującego, takiego jak **Claude Code** albo **OpenCode**: zadanie na runnerze z klonem repozytorium projektu | analiza kodu, przeglądy, odpowiedzi na pytania o repozytorium; z `push: true` w **Konfiguracji** także zmiany na gałęzi z merge requestem |

**Agent task** wymaga agenta kodującego także bez pusha: mówi to jego konfiguracja (`codingAgent: true`), więc
projektant wybiera takiego agenta od razu.

Ich polecenie to tylko `{{params.task}}`: platforma dodaje po nim jako sekcje danych zgłoszenie (gdy przebieg je
ma), **Materiał wejściowy** (gdy krok pyta o niego przy starcie i ktoś go wpisał) i uwagi recenzenta, więc **Task**
może zawierać samo zadanie. **Agent task** domyślnie nie wypycha zmian; w projekcie
bez repozytoriów działa w pustym katalogu roboczym, a jego końcowa odpowiedź staje się artefaktem `result`.
**LLM task** działa na serwerze Back Office i wymaga skonfigurowanego dostawcy modeli (klucza API); **Agent task**
działa na runnerze, więc na [osobistym runnerze](personal-runner.md) może zamiast tego korzystać z Twojego logowania
do Claude.

Aby przeszukiwać bibliotekę z IDE, użyj narzędzia MCP `search_task_library` (zob.
[IDE i MCP](integrations-ide.md#mcp-tools)).
