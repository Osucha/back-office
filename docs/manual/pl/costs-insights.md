# Koszty, budżety i wskaźniki

Każde wywołanie modelu i zadanie agenta jest księgowane w rejestrze zużycia wraz z kosztem, budżety
zatrzymują wydatki, zanim Cię zaskoczą, a wskaźniki pokazują, gdzie upływa czas między zgłoszeniem
a scaloną zmianą oraz który agent najtaniej doprowadza pracę do akceptacji. Ten rozdział opisuje stronę **Koszty**
(**Costs**), budżety, koszt zaakceptowanego wyniku i strony **Wskaźniki** (**Insights**).

<a id="costs"></a>
## Koszty

**Koszty** (`/costs`) pokazują rejestr zużycia: ile kosztowało każde wywołanie modelu i zadanie agenta,
dla jakiej pracy i z jakim modelem.

| Kto co widzi | Uprawnienie |
|---|---|
| Wszystkie projekty i wszystkie benchmarki | `usage.view` (role **Audytor**, **Auditor**, i **Administrator platformy**, **Platform administrator**) |
| Projekty, w których masz `project.usage.view` | role projektowe **Właściciel**, **Maintainer**, **Deweloper**, **Zatwierdzający** |

Pasek boczny pokazuje **Koszty** tylko wtedy, gdy masz jedno z tych uprawnień.

### Jak czytać stronę

| Część | Zawartość |
|---|---|
| Nagłówek | Stan budżetu wybranego projektu lub platformy, z linkiem do jego zmiany lub podglądu. |
| **Łącznie** (**Total**) | Koszt w okresie, z liczbą **Wywołań** (**Calls**), **Tokenami wejściowymi** (**Input tokens**), **Tokenami wyjściowymi** (**Output tokens**) i **Odczytem z cache** (**Cache read**). |
| **Koszt dzienny** (**Cost per day**) | Wykres dla okresu. |
| Zestawienie | Grupowane **Wg modelu** (**By model**), **Wg projektu** (**By project**), **Wg benchmarku** (**By benchmark**), **Wg rodzaju pracy** (**By kind of work**) lub **Wg dnia** (**By day**). |
| **Rejestr** (**Ledger**) | Jeden wiersz na wpis: **Kiedy** (**When**), **Model**, **Praca** (**Work**; uruchomienie i krok, próba benchmarku, przegląd, test), **Tokeny** (**Tokens**), **Czas trwania** (**Duration**), **Koszt** (**Cost**). |

### Filtrowanie

- Okresy: **7 dni**, **30 dni** (domyślnie), **Ten miesiąc** (**This month**), **90 dni** lub zakres dat.
- **Wszystkie projekty** (**All projects**) zawęża do jednego projektu.
- Przeszukuj rejestr po modelu, kroku, uruchomieniu lub benchmarku.

Koszty są wyliczane z cen w profilach modeli (zob. [Profile modeli](evaluation.md#models)). Dla zadań
Claude Code księgowany jest koszt zgłoszony przez agenta, a szacunek z cennika jest zachowywany do
porównania. Strona główna pokazuje **Koszt w tym tygodniu**; projekty pokazują koszt z ostatnich 30 dni;
uruchomienia i kroki pokazują własny koszt.

<a id="api-cost"></a>
### Koszt w cenach API

Obok kosztu (pieniędzy wydanych przez Back Office) każdy krok, przebieg i metryka workflowu ma też **koszt w cenach
API**: tę samą pracę w cennikach dostawców. Dla pracy przez bramę LLM obie wartości są równe. Różnią się dla kroków
opłaconych przez subskrypcję, np. Claude Code z Twoim własnym logowaniem na [osobistym runnerze](personal-runner.md#credentials):
wtedy koszt to 0, a koszt w cenach API to szacunek Claude Code. Tam, gdzie się różnią, UI pokazuje `$0.00 · API $0.05`,
a metryki workflowów mają kartę **W cenach API na udany przebieg**. Służy do porównywania zadań i workflowów niezależnie
od tego, kto płaci; budżety i strona kosztów liczą tylko wydane pieniądze.

<a id="cost-per-accepted"></a>
### Koszt zaakceptowanego wyniku

Back Office istnieje po to, żeby znajdować tańsze sposoby na zaakceptowaną pracę: to samo zadanie wykonane przez
różnych agentów i różne workflowy, mierzone w ten sam sposób. Najważniejszą liczbą jest tu **koszt zaakceptowanego
wyniku**: wszystko, co wydano, łącznie z porażkami i poprawkami, podzielone przez to, co zaakceptowano. Tani agent,
który w połowie przypadków zawodzi, nie jest tani; ta liczba to pokazuje.

| Poziom | Zaakceptowane oznacza | Koszt zaakceptowanego wyniku | Gdzie go widać |
|---|---|---|---|
| Krok zadania | wykonanie, które się udało, jest ostatnim wykonaniem swojego kroku w uruchomieniu, a uruchomienie zakończyło się sukcesem | koszt wszystkich wykonań / zaakceptowane wykonania | [Wskaźniki → Agenci](#agent-insights) |
| Workflow | udane uruchomienie (dotarło do udanego końca, jego bramki zostały zatwierdzone) | koszt wszystkich uruchomień / udane uruchomienia | **Koszt zaakceptowanego wyniku** (**Cost per accepted result**) w metrykach workflowu ([Mierzenie workflowów](workflow-library.md#metrics)) i na liście biblioteki |
| Benchmark | zaliczona próba | łączny koszt kandydata / zaliczone próby | kolumna rankingu **Koszt zaakceptowanego wyniku** (**Cost per accepted result**, [Benchmarki](evaluation.md#benchmarks)) |

Krok, który zawiódł i został ponowiony albo który bramka odesłała do poprawy, opłaca więc wykonanie, które ostatecznie
zaakceptowano; nieudane uruchomienie opłacają uruchomienia udane.

Czas ludzi nie jest przeliczany na pieniądze: Back Office wie, jak długo uruchomienie czekało na ludzi, a nie jak
długo pracowali. Zamiast tego metryki workflowu pokazują obok kosztu **Oczekiwanie na ludzi** (**Human wait**)
i **Za pierwszym razem** (**First-pass rate**). Scalenie w GitLabie też nie jest śledzone: zaakceptowanym wynikiem
jest udane uruchomienie (z zatwierdzonymi bramkami).

<a id="budgets"></a>
## Budżety

Budżet ogranicza wydatki w miesiącu kalendarzowym (UTC). Do wydatków liczy się każdy wpis rejestru:
wywołania agentów przez bramę modeli, kroki workflowów, przeglądy i benchmarki.

| Budżet | Gdzie | Kto go ustawia |
|---|---|---|
| Platformy | **Administracja** (**Administration**) → **Ustawienia platformy** (**Platform settings**), **Budżet platformy** (**Platform budget**) | `admin.settings.manage` |
| Projektu | Karta projektu **Budżet** (**Budget**) | `project.manage` (**Właściciel**, **Maintainer** projektu) |
| Na zadanie agenta | **Maks. koszt (USD)** i **Maks. liczba tokenów** w polityce sandboxa | projekt, szablon zadania |

Budżet platformy to pułap nad wszystkimi projektami; budżety projektów obowiązują w jego ramach, a agentów
zatrzymuje najpierw ten bardziej restrykcyjny.

### Kroki: ustawianie budżetu

1. Otwórz kartę **Budżet** projektu (lub sekcję **Budżet platformy** w **Ustawieniach platformy**).
2. Włącz **Limit miesięczny** (**Monthly limit**). Wyłączony oznacza brak limitu: wydatki są tylko
   raportowane.
3. Wpisz **Limit (USD)** i **Alert przy (%)** (**Alert at (%)**) (domyślnie 80).
4. Wybierz **Twardą blokadę** (**Hard stop**).
5. Kliknij **Zapisz budżet projektu** (**Save project budget**) (lub **Zapisz budżet platformy**, **Save
   platform budget**).

### Co się dzieje, gdy wydatki rosną

| Stan | Znacznik | Co się dzieje |
|---|---|---|
| Brak limitu | bez limitu | Wydatki są tylko raportowane. |
| Poniżej progu alertu | w budżecie | Nic. |
| Alert | alert budżetu | W **Twoja kolej** (**Your turn**) pojawia się karta budżetu u każdego, kto może zmienić budżet, na przykład „Budżet SHOP na poziomie 82%: 410,00 USD z 500,00 USD”, z prognozą na koniec miesiąca. |
| Przekroczony, z **Twardą blokadą** | budżet przekroczony · agenci zatrzymani | Brama modeli odrzuca kolejne wywołania (HTTP 402). Zadania agentów zatrzymują się i kończą błędem `budget-exceeded`. Karta w kolejce informuje: „Agenci są zatrzymani do czasu podniesienia limitu lub końca miesiąca” i oferuje **Podnieś limit** (**Raise the limit**). |
| Przekroczony, bez twardej blokady | budżet przekroczony | Praca trwa dalej; przekroczenie jest raportowane i pojawia się karta w kolejce. |

Znacznik jest bursztynowy (Twoja kolej), gdy możesz zmienić budżet, a w przeciwnym razie szary lub
czerwony.

Karta **Budżet** pokazuje **Wydano w miesiącu** (**Spent in**) dla danego miesiąca, pasek ze znacznikiem alertu
i znacznikiem prognozy oraz wiersz **Prognoza** (**Forecast**). Prognoza to liniowa ekstrapolacja
dotychczasowej części miesiąca. Budżety zerują się na początku każdego miesiąca. Sposób egzekwowania
budżetów wewnątrz sandboxa opisuje [Jak działają zadania agentów](agent-execution.md#budgets-in-sandbox).

<a id="insights"></a>
## Wskaźniki

**Wskaźniki** (`/insights`) pokazują metryki dostarczania dla projektów, które możesz oglądać
(`project.view`), na podstawie uruchomień utworzonych w wybranym okresie. Przełącznik pod tytułem,
**Dostarczanie / Agenci** (**Delivery / Agents**), przechodzi między tą stroną a stroną [Agenci](#agent-insights)
(`/insights/agents`).

### Filtrowanie

- **Wszystkie widoczne projekty** (**All visible projects**) albo jeden projekt.
- Okresy: **7 dni**, **30 dni** (domyślnie), **90 dni**, **Ten kwartał** (**This quarter**).

### Główne wskaźniki

| Wskaźnik | Znaczenie |
|---|---|
| **Oczekiwanie na ludzi** (**Human wait**) | Czas, przez który uruchomienia czekały w bramkach: czas, który ludzie są winni agentom. To najważniejszy wskaźnik. |
| **Czas realizacji** (**Lead time**) | Od pierwszego uruchomienia dla zgłoszenia do merge requestu (p50). |
| **Oczekiwanie na CI** (**CI wait**) | Czas, przez który uruchomienia czekały na CI projektu (p50). |
| **Skuteczność uruchomień** (**Run success rate**) | Uruchomienia, które dotarły do udanego końca, spośród wszystkich zakończonych. |
| **Koszt udanego uruchomienia** (**Cost per successful run**) | Koszt udanych uruchomień według rejestru, a także koszt na merge request i suma. |

### Pozostała część strony

| Sekcja | Zawartość |
|---|---|
| **Wąskie gardło** (**Bottleneck**) | Bramka z najdłuższym czasem oczekiwania p90 i workflow, w którym się znajduje. **Otwórz bramkę w projektancie** (**Open the gate in the designer**) przenosi do niej. Skraca go mniejsza liczba zatwierdzających, lista kontrolna, którą szybciej się zaznacza, lub dyżur roli odpowiedzialnej. |
| **Uruchomienia i oczekiwanie na ludzi tygodniowo** (**Runs and human wait per week**) | Udane i nieudane uruchomienia oraz godziny oczekiwania na ludzi w kolejnych tygodniach. |
| **Skuteczność agentów wg szablonu** (**Agent success by template**) | Zadania agentów według szablonu zadania: **Zadania** (**Jobs**), **Sukces** (**Success**), **Koszt**. |
| **Skuteczność agentów wg modelu** (**Agent success by model**) | To samo według modelu. |
| **Decyzje w bramkach** (**Gate decisions**) | Jak często wybierano każdą opcję (zatwierdź, odeślij do poprawy, odrzuć). |

Pusta strona oznacza, że żadne uruchomienie w tym okresie nie dotarło jeszcze do bramki ani do CI;
**Pokaż 90 dni** (**Show 90 days**) poszerza okres. Te same wskaźniki są dostępne w IDE przez narzędzie
MCP `get_delivery_insights` (zob. [IDE i MCP](integrations-ide.md#mcp-tools)).

### Ograniczenia

- Metryki obejmują uruchomienia **utworzone** w okresie; długie uruchomienie, które wystartowało
  wcześniej, nie jest liczone.
- Oczekiwania są liczone w chwili zakończenia; bramka, która wciąż jest otwarta, jeszcze nic nie dodaje.
- Decyzje w bramkach nie są rozbijane według roli zatwierdzającego.

<a id="agent-insights"></a>
## Agenci: koszt i skuteczność

**Wskaźniki** → **Agenci** (**Insights** → **Agents**, `/insights/agents`, w palecie poleceń także **Porównaj
agentów**, **Compare agents**) odpowiada na pytanie: który agent z jakim modelem najtaniej doprowadza krok zadania do
akceptacji? Strona czyta kroki zadań z uruchomień, które możesz oglądać (`project.view`), utworzonych w okresie.
Każdy krok zadania zapisuje, kto go wykonał: agenta (na przykład **Claude Code**, **OpenCode**, **Analyst** albo
jednego z Twoich) i model, z którym działał; strona uruchomienia pokazuje to samo
([Strona uruchomienia](runs.md#run-detail)).

Filtry jak na stronie dostarczania: **Wszystkie widoczne projekty** albo jeden projekt oraz okres.

| Sekcja | Zawartość |
|---|---|
| **Według agenta i modelu** (**Per agent and model**) | Jeden wiersz na agenta i model, od najtańszego zaakceptowanego wyniku. |
| **Według szablonu** (**Per template**) | Te same wiersze dla każdego szablonu zadania: ci sami agenci przy tym samym zadaniu. Porównuj w obrębie jednego szablonu; agent, który robi tylko łatwe kroki, wygląda w pierwszej tabeli na taniego. |

| Kolumna | Znaczenie |
|---|---|
| **Agent**, **Model** | Kto wykonał kroki i z jakim profilem modelu. |
| **Wykonania** (**Executions**) | Wykonania kroków zadań, łącznie z ponowieniami i poprawkami. |
| **Zaakceptowane** (**Accepted**) | Wykonania, które się udały, były ostatnim wykonaniem swojego kroku w uruchomieniu, a uruchomienie zakończyło się sukcesem. |
| **Za pierwszym razem** (**First-pass rate**) | Zaakceptowane wykonania, które były pierwszą próbą kroku / zaakceptowane wykonania. |
| **Koszt** (**Cost**) | Koszt wszystkich wykonań (z kosztem w cenach API tam, gdzie płaciła subskrypcja, zob. [Koszt w cenach API](#api-cost)). |
| **Koszt zaakceptowanego wyniku** (**Cost per accepted result**) | **Koszt** / **Zaakceptowane**: nieudane próby i poprawki opłacają zaakceptowane. **Brak zaakceptowanego wyniku** (**No accepted result yet**), gdy nic nie zaakceptowano. |
| **Mediana czasu** (**Median time**) | Mediana czasu trwania wykonania. |

Wykonania kroków zadań sprzed zapisywania agentów nie mają agenta; strona podaje, ile ich jest („... nie mają
zapisanego agenta ... i nie są liczone”). Pusta strona oznacza, że żadne uruchomienie w tym okresie nie wykonało
kroku zadania; **Pokaż 90 dni** (**Show 90 days**) poszerza okres.

Te same liczby są dostępne w IDE przez narzędzie MCP `get_agent_insights` (zob.
[IDE i MCP](integrations-ide.md#mcp-tools)).

### Kroki: porównanie agentów na tych samych zgłoszeniach

1. Każdy projekt z repozytorium ma workflow **Reference: one Claude Code session**: jedna sesja Claude Code
   implementuje zgłoszenie od początku do końca i otwiera merge request, człowiek go przegląda
   ([Domyślne workflowy](workflows.md#default-workflows)). To punkt odniesienia.
2. Przepuść te same zgłoszenia przez workflow referencyjny i przez tańsze workflowy albo przez ten sam workflow
   z tańszymi agentami i modelami w krokach zadań ([Węzły zadań](workflows.md#task-nodes)).
3. Porównaj kroki w **Wskaźniki** → **Agenci**, **Według szablonu**, a całe workflowy w ich metrykach: **Koszt
   zaakceptowanego wyniku**, **Za pierwszym razem** i **Oczekiwanie na ludzi** obok siebie
   ([Mierzenie workflowów](workflow-library.md#metrics)).
4. Zostaw tańszy sposób tam, gdzie jego koszt zaakceptowanego wyniku jest niższy bez dłuższego oczekiwania na ludzi;
   benchmark mierzy tych samych agentów na przygotowanych zadaniach, zanim wypróbujesz ich na prawdziwych
   zgłoszeniach ([Benchmarki](evaluation.md#benchmarks)).
