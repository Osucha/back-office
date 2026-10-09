# Słowniczek

Pojęcia używane w Back Office i w tym podręczniku, w kolejności alfabetycznej. Każdy wpis prowadzi do
rozdziału, który je wyjaśnia.

**Agent** (agent): Model AI wykonujący zadanie: pojedyncze wywołanie modelu wewnątrz Back Office albo agent
programistyczny (OpenCode, Claude Code) w zadaniu agenta.

**Artefakt** (artefact): Dokument wytworzony przez krok, taki jak `spec`, `plan`, `tasks`, `review` lub `diff`. Wyświetlany
na stronie uruchomienia oraz, jeśli bramka go wymienia, w panelu bramki. Zob. [Strona uruchomienia](runs.md#run-detail).

**Atestacja** (attestation): Podpisane oświadczenie, które wiąże commit i diff ze sposobem ich powstania (model, prompty,
sandbox, koszt, zatwierdzenia). Zob. [Pochodzenie](runs.md#provenance).

**Benchmark** (benchmark): Uruchomienie zestawu zadań na kilku modelach kandydujących, oceniane przez jeden, stały model
oceniający. Zob. [Benchmarki](evaluation.md#benchmarks).

**Bramka, bramka decyzyjna** (gate, human gate): Węzeł, w którym uruchomienie zatrzymuje się, dopóki zatwierdzający nie
wybierze opcji: zatwierdź, odeślij do poprawy albo odrzuć. Zob. [Rozstrzyganie bramki](your-turn.md#gates).

**Budżet** (budget): Miesięczny limit wydatków platformy lub projektu, z progiem alertu i opcjonalną twardą blokadą.
Zob. [Budżety](costs-insights.md#budgets).

**Dziennik audytu** (audit log): Zapis każdej zmiany, decyzji, logowania i odmowy dostępu. Zob.
[Administracja](administration.md#audit).

**Interfejs, interfejs zadania** (interface, task interface): Kontrakt, który obiecuje szablon zadania: wejścia, których
potrzebuje, wyjścia, które wytwarza, i jego porty. Zob. [Interfejsy](task-library.md#interfaces).

**Konstytucja** (constitution): Zasady projektu w Markdownie, przekazywane do Spec Kit i dostępne w promptach. Zob.
[Tworzenie projektu](projects.md#create-project).

**Krawędź** (edge): Połączenie od portu wyjściowego jednego węzła do innego węzła.

**Kryteria oceny** (rubric): Kryteria, według których model oceniający ocenia odpowiedź. Zob. [Zestawy zadań](evaluation.md#suites).

**Lista kontrolna** (checklist): Pozycje Definition of Ready lub Definition of Done na bramce. Zatwierdzenie wymaga
zaznaczenia wszystkich wymaganych pozycji. Zob. [Ustawienia bramki](workflows.md#gates-config).

**Maks. liczba wizyt** (max visits): Ile razy węzeł może się wykonać w ramach jednego uruchomienia; ochrona przed
nieskończonymi pętlami. Zob. [Rodzaje węzłów](workflows.md#nodes).

**MCP (Model Context Protocol)**: Protokół, którym agent programistyczny w Twoim IDE wywołuje narzędzia Back Office
(wyświetlanie elementów kolejki, odczyt artefaktów, uruchamianie workflowów, rozstrzyganie bramek) przy użyciu
osobistego tokenu dostępu. Zob. [IDE i MCP](integrations-ide.md#mcp-tools).

**Model oceniający, ocena** (judge): Model, który ocenia próby benchmarku według kryteriów oceny. Stały w ramach
benchmarku. Zob. [Benchmarki](evaluation.md#benchmarks).

**Oczekiwanie na ludzi** (human wait): Czas, przez który uruchomienia czekają w bramkach; główny wskaźnik na stronie
Wskaźniki. Zob. [Wskaźniki](costs-insights.md#insights).

**Pałeczka** (baton): Kto w tej chwili trzyma daną pracę: agent, ty, ci, gotowe, zablokowane lub bezczynne. Wyświetlana
jako znacznik z symbolem i słowem. Zob. [Pałeczka](getting-started.md#baton).

**PAT, osobisty token dostępu** (personal access token): Token (`bo_pat_…`), który działa w Twoim imieniu, z Twoimi lub
węższymi uprawnieniami, dla skryptów i IDE. Zob. [Osobiste tokeny dostępu](account.md#tokens).

**Pewność** (confidence): Liczba od 0 do 1, którą zadanie zgłasza na temat własnego wyniku. Korzystają z niej decyzje,
na przykład by pominąć bramkę, gdy agent jest pewny. Zob. [Decyzje](workflows.md#decisions).

**Polityka sandboxa** (sandbox policy): Limity sandboxa, ustawiane warstwami (platforma, pula, projekt, szablon, węzeł),
z których każda może tylko zawężać. Zob. [Polityka sandboxa](projects.md#sandbox-policy).

**Port** (port): Nazwane wyjście węzła. Port, przez który węzeł wychodzi, decyduje, dokąd uruchomienie przejdzie dalej.

**Poziom izolacji** (isolation level): Jak silnie sandbox oddziela agenta od maszyny: Deweloperska, Standardowa (gVisor) lub
Wysoka (mikromaszyna wirtualna Kata). Zob. [Jak działają zadania agentów](agent-execution.md#isolation-levels).

**Profil modelu** (model profile): Model z jego opcjami (temperatura, poziom rozumowania) i cenami. Zadania i benchmarki
wybierają profile. Zob. [Profile modeli](evaluation.md#models).

**Projekt** (project): Workflowy, członkowie, tokeny CI, polityka sandboxa i budżet; repozytoria Git są opcjonalne
i może ich być kilka. Zob. [Projekty](projects.md#create-project).

**Próba** (attempt): Jeden model kandydujący pracujący nad jednym zadaniem benchmarku (raz na powtórzenie), oceniany
przez model oceniający. Zob. [Benchmarki](evaluation.md#benchmarks).

**Przesłanianie** (shadowing): Szablon lub umiejętność projektu o tym samym kluczu co globalny zastępuje go w tym
projekcie. Zob. [Szablony](task-library.md#templates).

**Pula, pula runnerów** (pool, runner pool): Grupa runnerów wykonujących ten sam rodzaj zadań, z pułapem sandboxa. Zob.
[Flota runnerów](runner-fleet.md#pools).

**Pułap** (ceiling): Najwięcej, na ile może pozwolić warstwa polityki sandboxa, ustalane przez warstwy powyżej
(platforma, pula). Niższe warstwy mogą go tylko zawęzić. Zob. [Polityka sandboxa](projects.md#sandbox-policy).

**Punkt wejścia** (entrypoint): Klucz, który przypisuje `backoffice-ci run <key>` w pipeline'ie do workflowu, w trybie
blokującym lub doradczym. Zob. [CI: punkty wejścia i tokeny](projects.md#project-ci).

**Rejestr, rejestr zużycia** (ledger, usage ledger): Zapis każdego wywołania modelu i zadania agenta z tokenami
i kosztem. Zob. [Koszty](costs-insights.md#costs).

**Repozytorium, repozytorium projektu** (repository, project repository): Repozytorium Git projektu z kluczem, URL,
gałęzią domyślną, przeznaczeniem (kod źródłowy, dokumentacja, inne) i opcjonalnym oznaczeniem **główne** (repozytorium,
którego używają kroki programistyczne, gdy nie wskazują żadnego). Kroki programistyczne wybierają swoje repozytoria
z dostępem do odczytu albo do wypchnięcia i merge requestu. Zob. [Repozytoria](projects.md#repositories)
i [Repozytoria kroku](workflows.md#step-repositories).

**Rodowód** (lineage): Łańcuch rodziców szablonu, od samego szablonu do korzenia. Wyświetlany w podglądzie rozwiązanego
zadania. Zob. [Szablony](task-library.md#templates).

**Rola odpowiedzialna** (accountable role): Jedna rola projektowa, która odpowiada za decyzję w bramce („A” z RACI).
Wyświetlana na karcie w kolejce i w panelu bramki. Zob. [Ustawienia bramki](workflows.md#gates-config).

**Rola projektowa** (project role): Właściciel, Maintainer, Deweloper, Zatwierdzający lub Obserwator: co członek może
robić w jednym projekcie. Zob. [Członkowie i role projektowe](projects.md#members).

**Rozwiązane zadanie** (resolved task): To, co ostatecznie otrzymuje agent: szablon scalony z rodzicami, nadpisaniami
projektu i nadpisaniami węzła. Zob. [Szablony](task-library.md#templates).

**Runner** (runner): runner-controller w Twoim klastrze, który pobiera (dzierżawi) zadania agentów z Back Office
i wykonuje każde w sandboxie. Zob. [Flota runnerów](runner-fleet.md#pools).

**Sandbox** (sandbox): Izolowany kontener lub mikromaszyna wirtualna, w której działa zadanie agenta, z ograniczonymi
zasobami, siecią i narzędziami. Zob. [Jak działają zadania agentów](agent-execution.md#lifecycle).

**Sygnał CI** (CI signal): Komunikat, który pipeline wysyła poleceniem `backoffice-ci signal <name> --status success|fail`,
aby zwolnić uruchomienie czekające na CI. Zob. [Węzły oczekiwania na CI](workflows.md#nodes).

**Szablon zadania** (task template): Zadanie wielokrotnego użytku: wykonawca, prompty, umiejętności i konfiguracja;
rozszerza jednego rodzica i implementuje interfejsy. Zob. [Szablony](task-library.md#templates).

**Ścieżka sztafety** (relay track): Pasek segmentów, po jednym na każdy odwiedzony krok, pokolorowanych według tego, kto
trzymał dany krok. Zob. [Pałeczka](getting-started.md#baton).

**Token CI** (CI token): Token projektu (`bo_ci_…`), który pozwala pipeline'owi uruchamiać workflowy i wysyłać sygnały
w jednym projekcie. Zob. [CI: punkty wejścia i tokeny](projects.md#project-ci).

**Token zadania** (job token): Jedyne poświadczenie wewnątrz sandboxa (`bo_job_…`). Działa tylko dla własnego zadania,
własnego modelu i budżetu i wygasa razem z zadaniem. Zob. [Jak działają zadania agentów](agent-execution.md#credentials).

**Twarda blokada** (hard stop): Ustawienie budżetu: po osiągnięciu limitu brama modeli odrzuca kolejne wywołania,
a zadania agentów kończą się błędem. Zob. [Budżety](costs-insights.md#budgets).

**Twoja kolej** (turn, your turn): Coś, co wymaga Twojej uwagi: bramka do rozstrzygnięcia, nieudane uruchomienie lub
przegląd CI, budżet. Zbierane w kolejce **Twoja kolej**. Zob. [Twoja kolej](your-turn.md#inbox).

**Umiejętność** (skill): Blok instrukcji wielokrotnego użytku (`SKILL.md`) dodawany do promptów lub przestrzeni roboczych
szablonów, które go wymieniają. Zob. [Umiejętności](task-library.md#skills).

**Uprawnienie** (permission): Prawo, takie jak `run.approve` lub `library.manage`, na poziomie platformy lub projektu.
Role grupują uprawnienia. Zob. [Członkowie i role projektowe](projects.md#members).

**Uruchomienie** (run): Jedno wykonanie workflowu, zwykle dla jednego zgłoszenia. Zob. [Uruchomienia](runs.md#run-list).

**Węzeł** (node): Jeden blok workflowu: Start, Zadanie, Decyzja, Bramka decyzyjna, Oczekiwanie na CI, Koniec lub Notatka.
Zob. [Rodzaje węzłów](workflows.md#nodes).

**Węzeł decyzji** (decision node): Węzeł, który kieruje uruchomienie według warunków na stanie uruchomienia. Zob.
[Decyzje](workflows.md#decisions).

**Workflow** (workflow): Proces dostarczania projektu w postaci grafu węzłów, po którym przechodzą uruchomienia. Każdy
zapis to nowa wersja. Zob. [Projektowanie workflowów](workflows.md#designer).

**Wykonanie CI** (CI execution): Uruchomienie workflowu wystartowane przez pipeline przez punkt wejścia, z wynikiem
bramki (zaliczona lub niezaliczona). Zob. [Wykonania CI](runs.md#ci-executions).

**Wykonawca** (executor): To, co wykonuje szablon zadania: LLM, Spec Kit, Implementacja, Pobierz zgłoszenie, Powiadom
lub Zmień status zgłoszenia. Zob. [Szablony](task-library.md#templates).

**Wyzwalacz** (trigger): Jak workflow ma być uruchamiany: ręcznie, przez API, przez punkt wejścia CI lub przejście
w Jirze. Zob. [Uruchamianie workflowu](workflows.md#start-run).

**Zadanie agenta** (agent job): Zadanie programistyczne lub Spec Kit wykonywane w sandboxie na runnerze, z własnym tokenem
zadania, limitami i logami. Zob. [Jak działają zadania agentów](agent-execution.md#lifecycle).

**Zatwierdzający** (approver): Osoba uprawniona do rozstrzygnięcia bramki: `run.approve` w projekcie plus własna reguła
bramki. To także nazwa roli projektowej. Zob. [Rozstrzyganie bramki](your-turn.md#gates).

**Zestaw zadań** (suite, task suite): Zbiór zadań typu prompt i zadań programistycznych, na których mierzy się modele.
Zob. [Zestawy zadań](evaluation.md#suites).
