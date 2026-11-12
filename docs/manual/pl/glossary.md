# Słowniczek

Pojęcia używane w Back Office i w tym podręczniku, w kolejności alfabetycznej. Każdy wpis prowadzi do
rozdziału, który je wyjaśnia.

**Agenci w tym workflowie** (agents in this workflow): Panel projektanta, który dla każdego agenta używanego przez
szkic workflowu pokazuje jego kroki, reguły, umiejętności i brakujące wymagane umiejętności (z **Dodaj do agenta**)
oraz uwagi, takie jak **Brakująca umiejętność** czy **Nieznany agent**; nie wywołuje modelu. Zob.
[Agenci w tym workflowie](workflows.md#agent-audit).

**Agent** (agent): Ten, kto pracuje, i to, jak pracuje: wpis biblioteki z harnessem, opcjonalnie modelem,
instrukcjami, regułami, umiejętnościami i serwerami MCP. Bez harnessu daje pojedynczą odpowiedź modelu wewnątrz
Back Office (**Odpowiedź modelu**); z OpenCode lub Claude Code to sesja agenta kodującego w zadaniu agenta. Agenta
wybiera krok workflowu (szablony go nie wskazują), więc można porównać dwóch agentów na tym samym zadaniu.
Zob. [Agenci](task-library.md#agents).

**Akcja** (action): Węzeł workflowu, który wykonuje stałą operację bez modelu: Pobierz zgłoszenie, Skomentuj
zgłoszenie, Zmień status zgłoszenia, Uruchom workflow lub Podsumowanie projektu. Zob. [Węzły akcji](workflows.md#actions).

**Artefakt** (artefact): Dokument wytworzony przez krok, taki jak `spec`, `plan`, `tasks`, `review` lub `diff`. Wyświetlany
na stronie uruchomienia oraz, jeśli bramka go wymienia, w panelu bramki. Zob. [Strona uruchomienia](runs.md#run-detail).

**Atestacja** (attestation): Podpisane oświadczenie, które wiąże commit i diff ze sposobem ich powstania (model, prompty,
sandbox, koszt, zatwierdzenia). Zob. [Pochodzenie](runs.md#provenance).

**Benchmark** (benchmark): Uruchomienie zestawu zadań na kilku kandydatach (agentach z modelami), oceniane przez jeden,
stały model oceniający. Zob. [Benchmarki](evaluation.md#benchmarks).

**Bramka, bramka decyzyjna** (gate, human gate): Węzeł, w którym uruchomienie zatrzymuje się, dopóki zatwierdzający nie
wybierze opcji: zatwierdź, odeślij do poprawy albo odrzuć. Zob. [Rozstrzyganie bramki](your-turn.md#gates).

**Budżet** (budget): Miesięczny limit wydatków platformy lub projektu, z progiem alertu i opcjonalną twardą blokadą.
Zob. [Budżety](costs-insights.md#budgets).

**Dom agenta** (agent home): Katalog jednego agenta w katalogu przebiegu, poza każdym repozytorium
(`/workspace/.agents/<klucz agenta>/`): jego środowisko, umiejętności, konfiguracja MCP i sesje. Zob.
[Środowisko agenta](task-library.md#environment).

**Dostęp, dostęp agenta** (access): Co agent kodujący może zmieniać w repozytoriach swoich kroków: **Pełny**, **Tylko
odczyt** albo **Tylko te ścieżki**. Runner cofa inne zmiany, gdy agent skończy. Zob. [Dostęp agenta](task-library.md#agent-access).

**Dziennik audytu** (audit log): Zapis każdej zmiany, decyzji, logowania i odmowy dostępu. Zob.
[Administracja](administration.md#audit).

**Harness** (harness): Program, który prowadzi rozmowę agenta z modelem (narzędzia, kontekst, pytania): OpenCode lub
Claude Code. Agent bez harnessu daje odpowiedź modelu. Zob. [Agenci](task-library.md#agents).

**Kandydat** (candidate): To, co porównuje benchmark: agent z modelem (własnym modelem agenta, gdy żaden nie jest
wybrany), pokazywany jako `agent · model`. Zadania z promptem trafiają do agentów odpowiedzi modelu, zadania
programistyczne do agentów programistycznych. Zob. [Benchmarki](evaluation.md#benchmarks).

**Katalog przebiegu** (run directory): Jeden katalog przebiegu, w którym jego kroki kodujące pracują jeden po
drugim i widzą, co zostawiły wcześniejsze kroki; usuwany po zakończeniu przebiegu. Zob. [Katalog
przebiegu](workflows.md#run-directory).

**Konstytucja** (constitution): Zasady projektu w Markdownie, przekazywane do Spec Kit i będące częścią środowiska
każdego agenta; gdy źródła są sprzeczne, wygrywa z każdym innym. Zob. [Tworzenie projektu](projects.md#create-project)
i [Gdy źródła są sprzeczne](task-library.md#precedence).

**Koszt zaakceptowanego wyniku** (cost per accepted result): Wszystko, co wydano, łącznie z porażkami i poprawkami,
podzielone przez zaakceptowane wyniki; najważniejsza liczba przy porównywaniu agentów, workflowów i kandydatów
benchmarku. Zob. [Koszt zaakceptowanego wyniku](costs-insights.md#cost-per-accepted).

**Krawędź** (edge): Połączenie od portu wyjściowego jednego węzła do innego węzła.

**Kryteria oceny** (rubric): Kryteria, według których model oceniający ocenia odpowiedź. Zob. [Zestawy zadań](evaluation.md#suites).

**Lista kontrolna** (checklist): Pozycje Definition of Ready lub Definition of Done na bramce. Zatwierdzenie wymaga
zaznaczenia wszystkich wymaganych pozycji. Zob. [Ustawienia bramki](workflows.md#gates-config).

**Maks. liczba wizyt** (max visits): Ile razy węzeł może się wykonać w ramach jednego uruchomienia; ochrona przed
nieskończonymi pętlami. Zob. [Rodzaje węzłów](workflows.md#nodes).

**MCP (Model Context Protocol)**: Protokół, którym agent programistyczny w Twoim IDE wywołuje narzędzia Back Office
(wyświetlanie elementów kolejki, odczyt artefaktów, uruchamianie workflowów, rozstrzyganie bramek) przy użyciu
osobistego tokenu dostępu. Zob. [IDE i MCP](integrations-ide.md#mcp-tools). Agent z biblioteki może też wymieniać
zdalne serwery MCP, z których korzysta w sandboxie; zob. [Serwery MCP agenta](task-library.md#agent-mcp).

**Model oceniający, ocena** (judge): Model, który ocenia próby benchmarku według kryteriów oceny. Stały w ramach
benchmarku. Zob. [Benchmarki](evaluation.md#benchmarks).

**Oczekiwanie na ludzi** (human wait): Czas, przez który uruchomienia czekają w bramkach; główny wskaźnik na stronie
Wskaźniki. Zob. [Wskaźniki](costs-insights.md#insights).

**Odpowiedź modelu** (model answer): Wbudowany agent `model-answer`: jedno wywołanie modelu wewnątrz Back Office, bez
narzędzi i repozytorium; może zwrócić ustrukturyzowany JSON i pewność. Tak działa krok bez własnego agenta, którego
szablon nie wymaga agenta kodującego. Zob. [Agenci](task-library.md#agents).

**Pałeczka** (baton): Kto w tej chwili trzyma daną pracę: agent, ty, ci, gotowe, zablokowane lub bezczynne. Wyświetlana
jako znacznik z symbolem i słowem. Zob. [Pałeczka](getting-started.md#baton).

**Parametr, parametr szablonu** (parameter, template parameter): Typowane pole szablonu (jedna linia, tekst
wieloliniowy, liczba, tak / nie, wybór), które wypełnia krok workflowu; prompty czytają je jako `{{params.<nazwa>}}`.
Wymagany parametr bez wartości blokuje krok; takie szablony mają znacznik **wymaga parametrów**. Zob.
[Parametry](task-library.md#parameters).

**PAT, osobisty token dostępu** (personal access token): Token (`bo_pat_…`), który działa w Twoim imieniu, z Twoimi lub
węższymi uprawnieniami, dla skryptów i IDE. Zob. [Osobiste tokeny dostępu](account.md#tokens).

**Pewność** (confidence): Liczba od 0 do 1, którą zadanie zgłasza na temat własnego wyniku. Korzystają z niej decyzje,
na przykład by pominąć bramkę, gdy agent jest pewny. Zob. [Decyzje](workflows.md#decisions).

**Polityka sandboxa** (sandbox policy): Limity sandboxa, ustawiane warstwami (platforma, pula, projekt, szablon, węzeł),
z których każda może tylko zawężać. Zob. [Polityka sandboxa](projects.md#sandbox-policy).

**Port** (port): Nazwane wyjście węzła. Port, przez który węzeł wychodzi, decyduje, dokąd uruchomienie przejdzie dalej.

**Poziom izolacji** (isolation level): Jak silnie sandbox oddziela agenta od maszyny: Deweloperska, Standardowa (gVisor) lub
Wysoka (mikromaszyna wirtualna Kata). Zob. [Jak działają zadania agentów](agent-execution.md#isolation-levels).

**Preset** (preset): Szablon, który rozszerza inny i ustawia tylko wartości jego parametrów; tworzy się go przyciskiem
**Utwórz preset**. Zob. [Parametry](task-library.md#parameters).

**Profil modelu** (model profile): Model z jego opcjami (temperatura, poziom rozumowania) i cenami. Agenci, kroki
workflowów i benchmarki wybierają profile; szablony nie. Zob. [Profile modeli](evaluation.md#models).

**Projekt** (project): Workflowy, członkowie, tokeny CI, polityka sandboxa i budżet; repozytoria Git są opcjonalne
i może ich być kilka. Zob. [Projekty](projects.md#create-project).

**Próba** (attempt): Jeden kandydat (agent z modelem) pracujący nad jednym zadaniem benchmarku (raz na powtórzenie),
oceniany przez model oceniający. Zob. [Benchmarki](evaluation.md#benchmarks).

**Przesłanianie** (shadowing): Szablon, umiejętność, reguła lub agent projektu o tym samym kluczu co globalny zastępuje go w tym
projekcie. Zob. [Szablony](task-library.md#templates).

**Pula, pula runnerów** (pool, runner pool): Grupa runnerów wykonujących ten sam rodzaj zadań, z pułapem sandboxa. Zob.
[Flota runnerów](runner-fleet.md#pools).

**Pułap** (ceiling): Najwięcej, na ile może pozwolić warstwa polityki sandboxa, ustalane przez warstwy powyżej
(platforma, pula). Niższe warstwy mogą go tylko zawęzić. Zob. [Polityka sandboxa](projects.md#sandbox-policy).

**Punkt wejścia** (entrypoint): Klucz, który przypisuje `backoffice-ci run <key>` w pipeline'ie do workflowu, w trybie
blokującym lub doradczym. Zob. [CI: punkty wejścia i tokeny](projects.md#project-ci).

**Pytania agenta** (agent questions): Pytania, z którymi agent zatrzymuje krok, gdy bez Twoich odpowiedzi nie wykona
go dobrze; przebieg czeka, aż odpowiesz albo anulujesz krok. Zob. [Odpowiadanie na pytania
agenta](your-turn.md#agent-questions).

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

**Sesja agenta** (agent session): Rozmowa agenta kodującego. Domyślnie krok ma własną sesję (jego powtórzenia ją
kontynuują); może też za każdym razem zaczynać nową albo kontynuować sesję wcześniejszego kroku tego samego agenta.
Zob. [Sesje agenta](workflows.md#continue-session).

**Sprawdzenie spójności** (coherence check): **Sprawdź spójność** w panelu [Agenci w tym
workflowie](workflows.md#coherence-check): na żądanie model czyta całe środowisko agenta i wypisuje sprzeczności,
niejasności i powtórzenia z cytatami. Jedno wywołanie modelu, wliczane do zużycia.

**Sygnał CI** (CI signal): Komunikat, który pipeline wysyła poleceniem `backoffice-ci signal <name> --status success|fail`,
aby zwolnić uruchomienie czekające na CI. Zob. [Węzły oczekiwania na CI](workflows.md#nodes).

**Reguła** (rule): Coś, czego agent musi zawsze przestrzegać; jest zawsze w jego kontekście, a reguły automatyczne
obowiązują w każdym kroku swojego zakresu. Reguły należą do repozytorium, projektu i agenta, nigdy do szablonu. Zob.
[Reguły](task-library.md#rules).

**Szablon zadania** (task template): Opis tego, co zrobić, wielokrotnego użytku: kategoria, prompty z parametrami,
konfiguracja, wyniki i umiejętności, których wymaga praca; może mieć jednego rodzica. Nie wskazuje agenta ani modelu
(robi to krok); reguły i umiejętności należą do agenta. Zob. [Szablony](task-library.md#templates).

**Środowisko agenta** (environment of the agent): Dokument, który każdy krok dostaje przed swoim zadaniem:
preambuła platformy, instrukcje agenta, konstytucja projektu i reguły. Odpowiedź modelu dostaje go w prompcie
systemowym, OpenCode jako `AGENTS.md`, a Claude Code jako swój `CLAUDE.md` użytkownika, oba poza repozytorium. Zob.
[Środowisko agenta](task-library.md#environment).

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

**Umiejętność** (skill): Wiedza lub procedura (`SKILL.md`), którą agent otwiera, gdy zadanie jej wymaga; dostają ją
agenci, którzy ją wymieniają. Zob. [Umiejętności](task-library.md#skills).

**Uprawnienie** (permission): Prawo, takie jak `run.approve` lub `library.manage`, na poziomie platformy lub projektu.
Role grupują uprawnienia. Zob. [Członkowie i role projektowe](projects.md#members).

**Uruchomienie** (run): Jedno wykonanie workflowu, zwykle dla jednego zgłoszenia. Zob. [Uruchomienia](runs.md#run-list).

**Węzeł** (node): Jeden blok workflowu: Start, Zadanie, Akcja, Decyzja, Bramka decyzyjna, Oczekiwanie na CI, Koniec lub Notatka.
Zob. [Rodzaje węzłów](workflows.md#nodes).

**Węzeł decyzji** (decision node): Węzeł, który kieruje uruchomienie według warunków na stanie uruchomienia. Zob.
[Decyzje](workflows.md#decisions).

**Workflow** (workflow): Proces dostarczania projektu w postaci grafu węzłów, po którym przechodzą uruchomienia. Każdy
zapis to nowa wersja. Zob. [Projektowanie workflowów](workflows.md#designer).

**Workflow referencyjny** (reference workflow): Workflow domyślny **Reference: one Claude Code session**: jedna sesja
Claude Code implementuje zgłoszenie od początku do końca i otwiera merge request, człowiek go przegląda. Punkt
odniesienia, z którym porównuje się tańsze workflowy i agentów. Zob. [Domyślne workflowy](workflows.md#default-workflows).

**Wykonanie CI** (CI execution): Uruchomienie workflowu wystartowane przez pipeline przez punkt wejścia, z wynikiem
bramki (zaliczona lub niezaliczona). Zob. [Wykonania CI](runs.md#ci-executions).

**Wymaga agenta kodującego** (needs a coding agent): Znacznik tylko do odczytu szablonu, którego konfiguracja ma fazę
Spec Kit `phase`, `push` albo `prepareOnly`: jego pracę może wykonać tylko agent OpenCode lub Claude Code. Bez niego
może ją wykonać dowolny agent. Zob. [Szablony](task-library.md#templates).

**Wymagane umiejętności** (required skills): Umiejętności, których wymaga praca szablonu. Kierują wyborem agenta kroku
w projektancie i dają ostrzeżenie, gdy agentowi którejś brakuje; nigdy nie dodają umiejętności w czasie wykonania.
Zob. [Wymagane umiejętności](task-library.md#required-skills).

**Wyzwalacz** (trigger): Jak workflow ma być uruchamiany: ręcznie, przez API, przez punkt wejścia CI lub przejście
w Jirze. Zob. [Uruchamianie workflowu](workflows.md#start-run).

**Zaakceptowany wynik** (accepted result): Praca, która liczy się jako wykonana. Wykonanie kroku zadania, które się
udało, jest ostatnim wykonaniem swojego kroku w uruchomieniu, a uruchomienie zakończyło się sukcesem; udane
uruchomienie; zaliczona próba benchmarku. Zob. [Koszt zaakceptowanego wyniku](costs-insights.md#cost-per-accepted).

**Zadanie agenta** (agent job): Zadanie programistyczne lub Spec Kit wykonywane w sandboxie na runnerze, z własnym tokenem
zadania, limitami i logami. Zob. [Jak działają zadania agentów](agent-execution.md#lifecycle).

**Zatwierdzający** (approver): Osoba uprawniona do rozstrzygnięcia bramki: `run.approve` w projekcie plus własna reguła
bramki. To także nazwa roli projektowej. Zob. [Rozstrzyganie bramki](your-turn.md#gates).

**Zestaw zadań** (suite, task suite): Zbiór zadań typu prompt i zadań programistycznych, na których mierzy się modele.
Zob. [Zestawy zadań](evaluation.md#suites).
