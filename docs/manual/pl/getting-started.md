# Pierwsze kroki

Back Office to centrum dowodzenia, w którym agenci AI i Twoje CI wykonują pracę dostarczeniową projektu,
a człowiek jest pytany tylko wtedy, gdy trzeba podjąć decyzję. Ten rozdział wyjaśnia kilka pojęć, na
których opiera się reszta podręcznika, oraz to, co trzeba skonfigurować na początku.

<a id="what-is"></a>
## Czym jest Back Office

Proces dostarczania opisujesz raz, jako **workflow** na kanwie: pobierz zgłoszenie z Jiry, napisz
specyfikację, zaplanuj, zaimplementuj, poczekaj na CI, zrób przegląd, poproś człowieka o zatwierdzenie
scalenia. Następnie Back Office wykonuje ten workflow dla każdego zadania:

- **Agenci** piszą teksty i kod. Krótkie kroki to pojedyncze wywołanie modelu wewnątrz Back Office; kroki
  programistyczne działają jako zadania agenta w sandboxie na runnerze (zob. [Jak działają zadania agentów](agent-execution.md#lifecycle)).
- **Twoje CI** uruchamia testy i zgłasza wynik skryptem `backoffice-ci`. Back Office nigdy nie wywołuje API
  Twojego serwera Git; agent wypycha gałąź, a Twój pipeline się uruchamia (zob. [GitLab CI i inne CI](integrations-gitlab-ci.md#ci-overview)).
- **Ludzie** decydują w **bramkach**: zatwierdzają, odsyłają do poprawy albo odrzucają. Wszystko, co
  wymaga Twojej uwagi, trafia do jednej kolejki: **Twoja kolej** (**Your turn**).

Każdy wynik pokazuje, kto go wytworzył (agent i model, człowiek albo CI) i ile kosztował.

Wytwarzanie oprogramowania to ustawienie domyślne, a nie wymóg. Projekt może mieć jedno repozytorium Git,
kilka (na przykład kod i dokumentację) albo żadnego: projekt bez repozytorium obsługuje każdą pracę
wykonywaną z modelami językowymi, taką jak pisanie, analizy, tłumaczenia czy przeglądy dokumentów, z tymi
samymi bramkami, kosztami i audytem. Zaczyna od workflowu **LLM task with review**: model wykonuje opisane
przez Ciebie zadanie, a człowiek zatwierdza wynik albo odsyła go do poprawy (zob. [Projekty bez
repozytorium](projects.md#no-repository)).

<a id="concepts"></a>
## Kluczowe pojęcia

| Pojęcie | W jednym zdaniu | Gdzie je widzisz |
|---|---|---|
| Projekt | Workflowy, członkowie, tokeny CI, polityka sandboxa i budżet; repozytoria Git są opcjonalne i może ich być kilka. | **Projekty** (**Projects**), `/projects/:id` |
| Workflow | Graf węzłów (zadania, decyzje, bramki, oczekiwania na CI), po którym przechodzi uruchomienie. | Karta projektu **Workflowy** (**Workflows**), projektant |
| Uruchomienie | Jedno wykonanie workflowu, zwykle dla jednego zgłoszenia w Jirze. | **Uruchomienia** (**Runs**), `/runs/:id` |
| Węzeł | Jeden blok workflowu; uruchomienie odwiedza węzły po kolei. | Kanwa projektanta, **Oś czasu** (**Timeline**) uruchomienia |
| Bramka | Węzeł, w którym uruchomienie zatrzymuje się, dopóki zatwierdzający nie wybierze opcji. | **Twoja kolej**, panel boczny bramki |
| Szablon zadania | Zadanie wielokrotnego użytku (prompt, wykonawca, umiejętności, konfiguracja), na które wskazuje węzeł zadania. | **Biblioteka** (**Library**) → **Szablony** (**Templates**) |
| Profil modelu | Model z jego opcjami i cenami, wybierany przez zadania i benchmarki. | **Modele** (**Models**) |
| Zadanie agenta | Zadanie programistyczne wykonywane w sandboxie na runnerze. | **Oś czasu** uruchomienia; administratorzy widzą **Zadania agentów** (**Agent jobs**) |
| Budżet | Miesięczny limit wydatków platformy lub projektu. | Karta projektu **Budżet** (**Budget**), **Koszty** (**Costs**) |
| Wykonanie CI | Workflow uruchomiony z Twojego pipeline'u przez punkt wejścia. | **CI**, karta projektu **CI** |

[Słowniczek](glossary.md) definiuje każde pojęcie używane w podręczniku.

<a id="baton"></a>
## Pałeczka: kto trzyma pracę

Każde uruchomienie, krok, wykonanie CI i próba benchmarku pokazuje **znacznik pałeczki**: symbol i słowo
mówiące, kto w tej chwili trzyma pracę. To jedyny wskaźnik stanu w całej aplikacji, a stan nigdy nie jest
przekazywany wyłącznie kolorem.

| Kto trzyma | Znaczenie | Typowe słowa na znaczniku |
|---|---|---|
| agent (niebieski, wypełniony sześciokąt) | Pracuje agent. | agent pracuje, przegląd, uruchamianie |
| ty (you; bursztynowy, podniesiona dłoń) | Coś musi zrobić osoba, która może działać. | Twoja kolej |
| ci (morski, strzałka w pętli) | Uruchomienie czeka na CI projektu. | czeka na CI |
| gotowe (done; zielony, znak zaznaczenia) | Zakończone pomyślnie. | gotowe, bramka zaliczona, zatwierdzone |
| zablokowane (blocked; czerwony, znak stop) | Niepowodzenie lub odrzucenie. | nieudane, przekroczono czas, odrzucone, zakończone: porażka |
| bezczynne (idle; szary, pusty okrąg) | W kolejce, anulowane, wygasłe albo czeka na kogoś innego. | w kolejce, anulowane, u zatwierdzającego |

Tylko *Twoja kolej* jest bursztynowa i tylko wtedy, gdy to **Ty** możesz działać. Bramka czekająca na
innego zatwierdzającego pokazuje na szaro **u zatwierdzającego**. Animacje („oddychająca” kropka agenta,
obracająca się strzałka CI) zatrzymują się, gdy system prosi o ograniczenie ruchu.

**Ścieżka sztafety** to pasek segmentów, po jednym na każdy odwiedzony krok, pokolorowanych według tego,
kto trzymał dany krok. Na listach uruchomień jest mała, a na górze strony uruchomienia pełnowymiarowa,
z czasami trwania i kosztami.

<a id="first-steps"></a>
## Pierwsze kroki

### Jeśli to Ty konfigurujesz instalację

Administratorzy widzą na **Stronie głównej** (**Home**) listę kontrolną **Konfiguracja Back Office**
(**Set up Back Office**), dopóki nie wykonają wszystkich kroków (można ją ukryć przyciskiem zamknięcia).
Kroki w kolejności:

| Krok | Co zrobić |
|---|---|
| **Skonfiguruj klucz dostawcy modeli** (**Configure a model provider key**) | Klucze dostawców ustawia się na serwerze, nie w UI. **Ustawienia** (**Settings**) pokazują, którzy dostawcy są skonfigurowani, i mają przycisk **Testuj połączenie** (**Test connection**). Zob. [Dostawcy LLM](integrations-llm.md#llm-overview). |
| **Utwórz pierwszy projekt** (**Create the first project**) | Otwiera **Projekty** z formularzem **Utwórz projekt** (**Create project**). Zob. [Projekty](projects.md#create-project). |
| **Zaproś kogoś** (**Invite someone**) | Otwiera **Administracja** (**Administration**) → **Użytkownicy** (**Users**). Bramka potrzebuje zatwierdzającego; bramki, która zabrania samozatwierdzania, nie może rozstrzygnąć osoba, która uruchomiła workflow. Zob. [Administracja](administration.md#users). |
| **Wyznacz kierownika projektów** (**Name a project manager**) | Otwiera **Administracja** (**Administration**) → **Nadania** (**Grants**). Członkowie nie mogą tworzyć projektów; nadaj rolę **Kierownik projektów** (**Project manager**) osobom, które tworzą projekty i decydują, kto wykonuje który krok. Zob. [Kierownicy projektów](administration.md#project-managers). |
| **Podłącz CI** (**Connect CI**) | Otwiera kartę **CI** pierwszego projektu, gdzie tworzysz token CI. Zob. [Projekty](projects.md#project-ci) i [GitLab CI](integrations-gitlab-ci.md#ci-token). |

### Jeśli ktoś Cię zaprosił

1. Zaloguj się linkiem lub metodą logowania, którą przekazał Ci administrator (zob.
   [Logowanie](integrations-identity.md#oidc-overview)).
2. Otwórz **Projekty**. Jeśli lista jest pusta, poproś kierownika projektów lub właściciela projektu
   o dodanie Cię (projekty tworzą kierownicy projektów, zob. [Kto tworzy projekty](projects.md#who-creates)); to, co możesz
   robić w projekcie, zależy od Twojej roli projektowej (zob. [Członkowie i role](projects.md#members)).
3. Otwórz **Twoja kolej**, aby zobaczyć bramki, które możesz rozstrzygnąć. Jeśli jesteś zatwierdzającym,
   tu spędzisz większość czasu (zob. [Twoja kolej i skrzynka](your-turn.md#inbox)).
4. Aby samodzielnie rozpocząć pracę, otwórz projekt, kartę **Workflowy**, i kliknij **Uruchom**
   (**Start run**) obok workflowu (zob. [Uruchamianie workflowu](workflows.md#start-run)).

### Orientacja w aplikacji

| Element | Do czego służy |
|---|---|
| Pasek boczny (po lewej) | Na górze przełącznik projektów, potem **Strona główna**, **Twoja kolej**, **Projekty**, **Uruchomienia**, **Wskaźniki** (**Insights**), **CI**, **Biblioteka**, **Modele**, **Ewaluacja** (**Evaluation**), **Koszty**; na dole **Administracja** i **Ustawienia**. Pozycje, do których nie masz uprawnień, są ukryte. |
| Górny pasek | Ścieżka nawigacji, wyszukiwarka, bursztynowy licznik bramek, które możesz rozstrzygnąć (ukryty przy zerze), wskaźnik aktualizacji na żywo, przycisk **Aktywność** (**Activity**) i menu awatara (konto, motyw, gęstość, wylogowanie). |
| Paleta poleceń | `Ctrl K` (albo `⌘ K` lub `/`): wyszukiwanie projektów, workflowów, uruchomień po kluczu zgłoszenia lub id, szablonów oraz akcji, takich jak **Uruchom…** (**Start run…**), **Utwórz projekt**, **Przełącz motyw** (**Toggle theme**). |
| Skróty klawiszowe | `?` wyświetla skróty bieżącej strony. Na listach `j` / `k` przesuwają zaznaczenie, a `Enter` otwiera element. |
| Panel aktywności | `.` otwiera strumień zdarzeń na żywo dla bieżącego projektu lub wszystkich projektów. |

Strony aktualizują się na żywo, gdy je oglądasz. Nie ma powiadomień e-mail ani na czacie: to, co wymaga
Twojej uwagi, pojawia się w **Twoja kolej** i w bursztynowym liczniku. Workflow może dodać komentarz do
zgłoszenia w Jirze zadaniem `notify-jira` (zob. [Jira](integrations-jira.md#jira-tasks)).
