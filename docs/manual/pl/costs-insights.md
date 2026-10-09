# Koszty, budżety i wskaźniki

Każde wywołanie modelu i zadanie agenta jest księgowane w rejestrze zużycia wraz z kosztem, budżety
zatrzymują wydatki, zanim Cię zaskoczą, a wskaźniki pokazują, gdzie upływa czas między zgłoszeniem
a scaloną zmianą. Ten rozdział opisuje stronę **Koszty** (**Costs**), budżety i stronę **Wskaźniki**
(**Insights**).

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
(`project.view`), na podstawie uruchomień utworzonych w wybranym okresie.

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
