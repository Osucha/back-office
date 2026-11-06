# Modele i ewaluacja

Profile modeli to katalog modeli, z którego korzystają zadania i benchmarki, wraz z ich opcjami i cenami.
Ewaluacja mierzy te modele, a także agentów, którzy ich używają, na pracy takiej jak Twoja, dzięki czemu
wybierasz je na podstawie liczb, a nie opinii. Ten rozdział opisuje stronę **Modele** (**Models**), zestawy zadań i benchmarki.

| Chcesz | Uprawnienie | Rola wbudowana |
|---|---|---|
| Przeglądać profile modeli | `models.view` | **Członek** (**Member**) |
| Tworzyć, edytować, usuwać i testować profile | `models.manage` | **Administrator platformy** (**Platform administrator**) |
| Przeglądać zestawy zadań, benchmarki i rankingi | `evaluation.view` | **Członek** |
| Tworzyć zestawy i zadania; tworzyć, uruchamiać i anulować benchmarki | `evaluation.run` | **Ewaluator** (**Evaluator**) |

<a id="models"></a>
## Profile modeli

**Modele** (`/models`) wyświetlają profile z kolumnami **Dostawca** (**Provider**), **Id modelu**
(**Model id**), cena **Wej. / wyj. za MTok** (**In / out per MTok**), **Kontekst** (**Context**),
**Ostatni test** (**Last test**) i **Włączony** (**Enabled**). Nie ma predefiniowanych profili: tworzysz
je z modeli oferowanych przez podłączonych dostawców (zob. [Dostawcy LLM](integrations-llm.md#llm-overview)).
Profile, których dostawca nie jest podłączony, są ukryte; wiersz pod tabelą podaje ich liczbę, a **Pokaż je**
(**Show them**) przywraca je, żeby poprawić połączenie albo je usunąć.

Pod profilami sekcja **Dostępne u dostawców** (**Available from providers**) wyświetla modele, które każdy
podłączony dostawca oferuje w tej chwili, pobrane na żywo (odświeżane co dziesięć minut albo przyciskiem
odświeżania). OpenRouter publikuje też ceny i okna kontekstu; jego modele `:free` są oznaczone jako
**darmowy**, a przełącznik **Tylko darmowe** (**Free only**) pokazuje tylko je. **Dodaj jako profil** (**Add
as profile**) otwiera formularz profilu wypełniony dostawcą, połączeniem, id modelu, nazwą, cenami i oknem
kontekstu; modele, które mają już profil, są oznaczone **profil istnieje**.

Profil to model plus jego opcje. Dwa profile mogą wskazywać ten sam model z różnymi opcjami, na przykład
wysokim i niskim poziomem rozumowania, dzięki czemu możesz zmierzyć, czy mocniejsze ustawienie jest warte
swojej ceny.

### Kroki: tworzenie profilu

1. Kliknij **Dodaj jako profil** przy modelu w sekcji **Dostępne u dostawców** albo **Utwórz profil**
   (**Create profile**) dla modelu, którego dostawca nie wymienia (albo zduplikuj istniejący profil z jego
   wiersza).
2. Wpisz **Wyświetlaną nazwę** (**Display name**), **Dostawcę** i **Id modelu** dokładnie w takiej
   postaci, jakiej oczekuje API dostawcy.
3. **Opcje domyślne** (**Default options**): **Temperatura** (**Temperature**), **Maks. liczba tokenów
   wyjściowych** (**Max output tokens**), **Poziom rozumowania** (**Reasoning effort**) i **Dodatkowe opcje
   (JSON)** (**Extra options (JSON)**) przekazywane dostawcy bez zmian.
4. **Cennik** (**Pricing**) w USD za milion tokenów: **Wejście** (**Input**) i **Wyjście** (**Output**)
   (wymagane), **Odczyt z cache** (**Cache read**) i **Zapis do cache** (**Cache write**).
5. **Katalog** (**Catalogue**): **Możliwości** (**Capabilities**; tagi informacyjne), **Okno kontekstu
   (tokeny)** (**Context window (tokens)**), **Notatki** (**Notes**).
6. Pozostaw **Włączony**. Wyłączone profile są ukryte w listach wyboru kroków i benchmarków.
7. Kliknij **Utwórz profil**, a następnie **Testuj profil** (**Test profile**): wysyła on krótki prompt
   i pokazuje opóźnienie i koszt albo błąd zwrócony przez dostawcę.

Wszystkie kwoty kosztów w Back Office są wyliczane z tych cen. Profil z ceną zastępczą 0 sprawia, że model
wygląda na darmowy; sprawdź ceny, zanim zaufasz **Kosztom** (**Costs**) lub rankingowi.

### Gdzie używa się profili

| Miejsce | Ustawienie |
|---|---|
| Projekt | **Domyślny profil modelu** i **Profil modelu do przeglądów** (zob. [Projekty](projects.md#create-project)) |
| Szablon zadania | **Profil modelu** (zob. [Biblioteka zadań](task-library.md#templates)) |
| Węzeł zadania | Nadpisanie **Profil modelu** (zob. [Projektowanie workflowów](workflows.md#nodes)) |
| Benchmark | modele kandydatów (agent z modelem) i model oceniający |

Profil węzła wygrywa z profilem szablonu, a ten z domyślnym profilem projektu.

<a id="model-availability"></a>
### Których modeli może użyć krok

Model jest albo **platformowy** (połączenie z dostawcą dodane przez administratora, wywoływany przez API, dostępny
dla wszystkich i liczony do budżetów), albo **osobisty** (dziś: Claude Code z Twoim własnym logowaniem na Twoim
osobistym runnerze, dostępny tylko dla Ciebie). Modele osobiste działają tylko w Twoich prywatnych projektach
i Twoich benchmarkach; modele platformowe działają wszędzie, także w projektach prywatnych.

Wszędzie, gdzie wybierasz model (krok w projektancie workflowów, model domyślny i model przeglądu projektu, okno
uruchamiania), Back Office pokazuje modele w dwóch grupach, **Platformowe** (**Platform**) i **Osobiste**
(**Personal**), z drogą wywołania (**API** albo **Twoje logowanie**). Model, którego nie da się tam użyć, jest
wyszarzony z podanym powodem, na przykład:

| Powód | Co zrobić |
|---|---|
| połączenie z dostawcą nie jest skonfigurowane albo nie ma klucza API | podłącz dostawcę w Ustawieniach (administrator) albo wybierz inny model |
| działa tylko w projektach prywatnych | w projektach wspólnych użyj modelu platformowego |
| wymaga Claude Code jako agenta kodującego | ustaw agenta kodującego kroku na Claude Code |
| brak osobistego runnera albo nie używa Twojego logowania | skonfiguruj go w Moje konto, Osobisty runner |

Osobisty runner offline to tylko ostrzeżenie: zadania czekają, aż się połączy.

**Profile osobiste.** Każdy, kto widzi modele, może tworzyć profile dla siebie: przy **Utwórz profil** (**Create
profile**) wybierz **Osobisty (tylko Ty)** (**Personal (only you)**). Tylko Ty je widzisz, zmieniasz, testujesz
i używasz, w swoich prywatnych projektach i własnych benchmarkach; administratorzy tworzą profile **Platformowe**
(**Platform**) dla wszystkich. Profile z logowaniem Claude Code są zawsze osobiste.

**Modele domyślne.** Gdy ani krok, ani jego agent, ani jego szablon, ani projekt nie wskazuje modelu, Back Office bierze Twój
własny model domyślny dla tego rodzaju kroku (**Kroki LLM** albo **Kroki kodujące**), a potem domyślny model
platformy ustawiony przez administratora. Ustawisz je na stronie **Modele** (**Models**) w sekcji **Modele
domyślne** (**Default models**). Model z logowaniem Claude Code może być domyślny dla obu: kroki LLM dostają wtedy
odpowiedź od Claude Code na Twoim osobistym runnerze.

**Plan modeli** workflowu pokazuje dla każdego kroku model, którego użyłoby uruchomienie, i skąd pochodzi (krok,
jego agent, jego szablon, model domyślny projektu albo model przeglądu). Projektant oznacza ostrzeżeniem kroki z modelem,
którego nie możesz użyć, a uruchomienie nie wystartuje, dopóki któryś krok nie ma dostępnego modelu; okno
uruchamiania wypisuje kroki i powód. Narzędzia MCP `get_model_options` i `get_model_plan` dają agentowi w IDE te
same informacje.

<a id="claude-code-login"></a>
### Claude Code z własnym logowaniem

Jeśli Twój osobisty runner loguje Claude Code Twoją własną subskrypcją Claude (**Moje własne logowanie do
Claude (subskrypcja)**, zob. [Osobisty runner](personal-runner.md#credentials)), możesz go testować w benchmarku bez klucza API. W sekcji **Dostępne
u dostawców** (**Available from providers**) źródło **Claude Code (Twoje logowanie)** (**Claude Code (your
login)**) oferuje `opus`, `sonnet` i `haiku`; **Dodaj jako profil** (**Add as profile**) tworzy profil
oznaczony **Logowanie Claude Code** (**Claude Code login**). Rzadko jest to potrzebne: gdy Twój osobisty runner
pierwszy raz zgłosi Twoje logowanie, Back Office sam tworzy profile **Claude Opus (my login)**, **Claude Sonnet (my
login)** i **Claude Haiku (my login)**. Taki profil:

- działa jako Claude Code na osobistym runnerze osoby, która tworzy benchmark albo uruchamia workflow, z jej
  logowaniem;
- wykonuje zadania i kroki programistyczne jako agent (w benchmarku musi być modelem agenta Claude Code, a benchmark
  wymaga **Mojego osobistego runnera** jako miejsca wykonania), a zadania typu prompt, kroki LLM i ocenę modelu
  oceniającego jako jedną odpowiedź: Back Office wysyła krótkie zadanie na Twój osobisty runner, gdzie Claude Code
  odpowiada bez narzędzi;
- działa tylko w Twoich prywatnych projektach i własnych benchmarkach;
- nic nie kosztuje w Back Office (płaci subskrypcja), ale zużywa jej limity, a każda odpowiedź trwa kilka sekund
  dłużej niż wywołanie API (runner uruchamia do niej Claude Code).

**Testuj profil** (**Test profile**) i **Sprawdź modele** (**Check models**) nie wywołują dla niego modelu:
sprawdzają, czy Twój osobisty runner istnieje, używa Twojego logowania i jest dostępny. Claude Code dodaje
własne instrukcje i czas uruchomienia, więc porównuj taki model z modelami API po ocenach, nie po czasach.

Token subskrypcji (`sk-ant-oat...`, z `claude setup-token`) nie jest kluczem API: połączenie z dostawcą
Anthropic go odrzuca.

<a id="suites"></a>
## Zestawy zadań

Zestaw zadań to praca, na której mierzy się modele: zgłoszenia, specyfikacje, przeglądy i zadania
programistyczne takie jak Twoje. **Ewaluacja** (**Evaluation**) → **Zestawy zadań** (**Task suites**,
`/evaluation/suites`) wyświetla je. Zestaw startowy **Starter: SDLC tasks** pokazuje strukturę; skopiuj
go i zastąp treść własną pracą. Jego pięć zadań programistycznych działa na Spring PetClinic
(`https://github.com/spring-projects/spring-petclinic`, przypięty commit, weryfikacja `./mvnw -B test` w około
minutę) z ukrytymi testami akceptacyjnymi, więc pierwszy benchmark działa bez własnego repozytorium. Zadania
startowe, których nie edytowałeś, są przy następnym uruchomieniu aktualizowane do bieżącej wersji.

### Kroki: tworzenie zestawu i dodawanie zadań

1. Kliknij **Utwórz zestaw** (**Create suite**), wpisz **Nazwę**, **Opis** i **Wagi oceny zadań
   programistycznych** (**Coding score weights**: **Weryfikacja**, **Verification**, i **Ocena**,
   **Judge**, sumujące się do 1), a następnie kliknij **Utwórz zestaw**.
2. Otwórz zestaw i kliknij **Dodaj zadanie** (**Add task**).
3. Wypełnij **Nazwę**, **Kategorię** (**Category**), **Trudność** (**Difficulty**) i **Rodzaj** (**Kind**):
   - **Prompt**: jedno wywołanie modelu z **Instrukcjami (markdown)** (**Instructions (markdown)**)
     i opcjonalnym **Kontekstem** (**Context**); model oceniający ocenia odpowiedź według **Kryteriów oceny
     (markdown)** (**Rubric (markdown)**) i opcjonalnej **Odpowiedzi wzorcowej** (**Reference answer**).
   - **Zadanie programistyczne** (**Coding job**): działa w zadaniu agenta. Ustaw **Repozytorium**
     (**Repository**): **URL do klonowania** (**Clone URL**), **Bazowa referencja** (**Base ref**),
     **Polecenie przygotowania** (**Setup command**) i **Polecenie weryfikacji** (**Verify command**; kod
     wyjścia 0 oznacza zaliczenie). Agent pracuje na gałęzi, która nigdy nie jest wypychana.
     **Sprawdzenia akceptacyjne (JSON)** (**Acceptance checks (JSON)**) są ukryte przed agentem: `files`
     (ścieżka → treść, na przykład testy akceptacyjne) są zapisywane tylko na czas polecenia weryfikacji
     i potem usuwane, `protectedPaths` (wzorce ścieżek git, np. `src/test/**`) muszą pozostać niezmienione,
     a `requireChanges` oblewa przebieg, który niczego nie zmienił.
4. W sekcji **Wykonanie** (**Execution**) ustaw **Limit czasu (sekundy)** (**Timeout (seconds)**)
   i **Wagę** (**Weight**; względna waga w agregatach zestawu).
5. Sprawdź kartę **Podgląd** (**Preview**), a potem kliknij **Dodaj zadanie**.

Z menu strony zestawu użyj **Edytuj zestaw** (**Edit suite**) i **Usuń zestaw** (**Delete suite**);
**Uruchom benchmark tego zestawu** (**Benchmark this suite**) tworzy dla niego nowy benchmark.

### Jak napisać dobre kryteria oceny

- Nazwij od 3 do 5 kryteriów, każde oceniane w skali 0–10, z jednym zdaniem opisującym, jak wygląda 10.
- Wprost umieść w kryteriach to, co odrzuciłbyś na przeglądzie, na przykład „bez nazw technologii”.
- Podaj limity za decydujące błędy, na przykład „błędna przyczyna źródłowa ogranicza to kryterium do 3”.
- Podaj odpowiedź wzorcową, gdy zadanie ma znaną odpowiedź. Model oceniający traktuje ją jako wiążącą co do
  faktów (przyczyny, celowo wprowadzone błędy, reguły); inne sformułowania czy równie dobre projekty są w porządku.
- Oceniaj tylko to, o co prosiły instrukcje.

Dobre zestawy mają od 8 do 20 zadań z różnych kategorii i poziomów trudności, co najmniej dwa na
kategorię. Zadania programistyczne wymagają repozytorium, które runner może sklonować, i polecenia
weryfikacji, które przechodzi na bazowej referencji. Zadanie programistyczne bez ukrytych testów
akceptacyjnych mierzy głównie, czy agent niczego nie zepsuł: napisz testy, które nie przechodzą na bazowej
referencji i przechodzą z rozwiązaniem wzorcowym, i sprawdź oba przypadki przed dodaniem.

**Punktacja.** Model oceniający ocenia każde kryterium w skali 0–10, najpierw uzasadnienie; ocena zadania to
średnia × 10, liczona przez Back Office. Zadania typu prompt są zaliczone od 70. Zadania programistyczne
dostają `verification × (passed ? 100 : 0) + judge × judge score` z wagami zestawu i są zaliczone tylko wtedy,
gdy weryfikacja (razem z testami akceptacyjnymi) przeszła, a ocena wynosi co najmniej 70. Przy zadaniach
programistycznych model oceniający czyta diff względem bazowej referencji, a nie tylko podsumowanie agenta.

<a id="benchmarks"></a>
## Benchmarki

Benchmark uruchamia zestaw na kilku kandydatach, oceniany przez jeden, stały model oceniający. **Kandydat** to agent
z modelem, na przykład **Claude Code** z Claude Sonnet, **OpenCode** z tańszym modelem albo Twój własny agent
**Analyst** z jego instrukcjami i regułami ([Agenci](task-library.md#agents)). Benchmark porównuje więc nie tylko
modele, ale całych agentów: harness, model i środowisko.
**Ewaluacja** → **Benchmarki** (**Benchmarks**, `/evaluation/benchmarks`) wyświetla je z informacją, kto
je trzyma, zestawem, **Kandydatami** (**Candidates**), postępem prób i kosztem.

**Który kandydat robi które zadanie.** Zadania z promptem trafiają do agentów odpowiedzi modelu (agentów bez
harnessu, takich jak **Odpowiedź modelu**, **Analyst** czy **Reviewer**; ich środowiskiem jest prompt systemowy),
zadania programistyczne do agentów programistycznych (**Claude Code**, **OpenCode** albo Twoich; działają ze swoim
środowiskiem w zadaniu runnera: instrukcjami, regułami, umiejętnościami i serwerami MCP, jak w kroku workflowu, zob.
[Środowisko agenta](task-library.md#environment)). Każda inna para, zadanie programistyczne dla agenta odpowiedzi
modelu albo zadanie z promptem dla agenta programistycznego, nie tworzy próby i jest liczona jako **Nie dotyczy**
(**Not applicable**). Żeby porównać agentów na obu rodzajach zadań, dodaj po jednym kandydacie każdego rodzaju.

### Kroki: uruchamianie benchmarku

1. Kliknij **Utwórz benchmark** (**Create benchmark**).
2. Wpisz **Nazwę** i wybierz **Zestaw zadań** (**Task suite**). Opcjonalnie wybierz **Zadania**
   (**Tasks**); puste oznacza wszystkie zadania.
3. W sekcji **Kandydaci** (**Candidates**) wybierz dla każdego kandydata **Agenta** (**Agent**) i **Model**,
   a **Dodaj kandydata** (**Add candidate**) dodaje następnego. Model jest opcjonalny, gdy agent ma własny model:
   pole pokazuje wtedy „Model agenta: X” („Agent's model: X”). Agent bez modelu go potrzebuje („Ten agent nie ma
   własnego modelu: wybierz go.”). Nie można wybrać modeli, których dostawca nie ma klucza; tego samego agenta
   z tym samym modelem nie można dodać dwa razy. Pod każdym kandydatem formularz podaje, ile wybranych zadań nie
   dotyczy jego agenta.
4. Wybierz **Model oceniający** (**Judge model**). Użyj najmocniejszego modelu, na jaki Cię stać, i nigdy
   żadnego z modeli kandydatów.
5. Ustaw **Powtórzenia** (**Repetitions**; 1–5); dwa lub trzy pozwalają zmierzyć, jak stabilne są wyniki.
6. Gdy zadania obejmują zadania programistyczne, wybierz, gdzie mają działać, w polu **Zadania kodujące
   uruchamiaj na** (**Coding tasks run on**): **Współdzielone runnery** (**Shared runners**, flota platformy)
   albo **Mój osobisty runner** (**My personal runner**, Twój komputer, zob.
   [Osobisty runner](personal-runner.md)). Przy każdej opcji widać, ile runnerów jest online; nieskonfigurowanej
   nie da się wybrać, a gdy żaden nie jest online, próby kodujące czekają na połączenie runnera (najwyżej 24
   godziny). Zadania promptowe nie potrzebują runnera. [Model z logowaniem Claude Code](#claude-code-login)
   działa tylko jako model agenta Claude Code i na Twoim osobistym runnerze; formularz mówi, co zmienić.
7. Kliknij **Utwórz i uruchom** (**Create and start**) albo **Zapisz jako szkic** (**Save as draft**),
   a później **Uruchom benchmark** (**Start benchmark**) na jego stronie. **Utwórz i uruchom** najpierw wysyła
   jednolinijkowy test do modelu każdego kandydata i modelu oceniającego (**Sprawdź modele**, **Check models**, robi
   tylko to): modele, które nie odpowiadają, są wypisane z powodem podanym przez dostawcę, na przykład brak
   dostępu do modelu albo wyczerpany limit dzienny. Usuń je albo kliknij **Uruchom mimo to** (**Start anyway**).

Nie ma już wyboru agenta programistycznego dla całego benchmarku: harness przynosi agent kandydata. Benchmarki
utworzone, zanim kandydatami byli agenci (tylko z modelami, także przez starsze API), działają jak dotąd: każdy
model jako **Odpowiedź modelu** w zadaniach z promptem i jako agent harnessu benchmarku w zadaniach
programistycznych, bez środowiska agenta. Ich strony pokazują jeden wiersz na model i agenta, na przykład
`Model answer · Claude Sonnet` i `OpenCode · Claude Sonnet`.

Formularz pokazuje, ile prób zaplanuje benchmark (zadania × kandydaci × powtórzenia, bez par, których to nie
dotyczy, każda dodatkowo raz oceniana). Każda próba kosztuje tokeny kandydata plus tokeny modelu oceniającego; próby programistyczne na
najmocniejszych modelach mogą kosztować po kilka dolarów każda. Formularz ostrzega, gdy model oceniający jest też
kandydatem (oceniałby własne odpowiedzi) i gdy zadania programistyczne wskazują przykładowe repozytorium, np.
`gitlab.example.com`, które nie istnieje.

### W trakcie działania

Próby są planowane z góry i wykonywane po kilka naraz dla każdego dostawcy. Jeśli operator skonfigurował
limity zapytań dostawców, wywołania benchmarku zostawiają rezerwę dla kroków workflowów i przeglądów,
a próba, która trafi na limit, wraca później do kolejki. Ponowienie po błędzie samego modelu oceniającego
zachowuje odpowiedź kandydata i pyta tylko oceniającego. Postęp aktualizuje się na żywo. **Anuluj
benchmark** (**Cancel benchmark**) zatrzymuje próby w kolejce i działające zadania; zakończone próby
zachowują wyniki. Pojedyncze nieudane próby nie powodują niepowodzenia benchmarku.

Niektóre błędy powtarzają się przy każdym kolejnym wywołaniu: brak dostępu do modelu, zły klucz, wyczerpany
limit dzienny dostawcy albo połączenia. Po pierwszym takim błędzie czekające próby tego modelu kończą się od
razu z komunikatem „skipped, ... failed: ...”, zamiast każda wywoływać dostawcę; gdy tak zawiedzie model
oceniający, kończą się wszystkie czekające próby. Gdy próby kodujące czekają, bo w miejscu, do którego
benchmark je wysyła, żaden runner nie jest online, strona benchmarku o tym informuje.

### Odczytywanie wyników

Strona benchmarku ma karty **Ranking** (**Leaderboard**) i **Próby · N** (**Attempts · N**); jej nagłówek wymienia
**Kandydatów** (**Candidates**).

| Kolumna rankingu | Znaczenie |
|---|---|
| **Kandydat** (**Candidate**) | Jeden wiersz na kandydata, `agent · model`, na przykład `Claude Code · Claude Sonnet`. |
| **Wynik** (**Score**) | Średnia ocena z udanych prób. |
| **Odsetek zaliczonych** (**Pass rate**) | Udział zaliczonych prób (programistyczne: weryfikacja i testy akceptacyjne przechodzą, a ocena wynosi co najmniej 70; prompt: ocena 70 lub więcej). Próby nieudane i przekraczające czas liczą się jako niezaliczone. |
| **Średni koszt** (**Mean cost**), **Łącznie** (**Total**) | Koszt kandydata na próbę i ogółem. |
| **Koszt zaakceptowanego wyniku** (**Cost per accepted result**) | **Łącznie** / zaliczone próby: nieudane próby opłacają te zaliczone ([Koszt zaakceptowanego wyniku](costs-insights.md#cost-per-accepted)). Puste bez zaliczonej próby. Według tej liczby porównuj kandydatów. |
| **Wynik / $** (**Score / $**) | Średni wynik na dolara średniego kosztu. |
| **Czas p50 / p95** (**Time p50 / p95**) | Czas odpowiedzi samego kandydata (odpowiedź modelu albo zadanie agenta od zlecenia do wyniku), bez modelu oceniającego, kolejki i ponowień, z udanych prób: połowa była szybsza niż p50, 95% szybsze niż p95. Poniżej 20 prób p95 to po prostu najwolniejsza próba; podpowiedź mówi, z ilu prób policzono czasy. |
| **Stabilność** (**Stability**) | Odchylenie standardowe wyniku między powtórzeniami; im niższe, tym stabilniej. |
| **Nie dotyczy** (**Not applicable**) | Zadania benchmarku, których ten kandydat nie może wykonać (zadanie programistyczne dla agenta odpowiedzi modelu, zadanie z promptem dla agenta programistycznego); kolumna jest widoczna tylko wtedy, gdy takie są. |

**Wynik a koszt** (**Score vs cost**) pokazuje na wykresie każdego kandydata; kandydat, którego inny bije
zarówno wynikiem, jak i kosztem, jest zdominowany. Przekroje **Ogółem** (**Overall**), **Wg kategorii**
(**By category**), **Wg trudności** (**By difficulty**) i **Wg zadania** (**By task**) powtarzają tabelę
dla każdego wycinka. Na karcie **Próby** szukaj zadań lub kandydatów, filtruj po zadaniu i modelu i otwórz próbę,
aby przeczytać jej agenta i model, wynik, kryteria i uzasadnienie modelu oceniającego oraz wynik weryfikacji. Zanim
wyciągniesz wnioski, przeczytaj najgorszą próbę każdego kandydata: niska ocena to często problem z kryteriami oceny.

### Wykorzystanie wyniku

1. Ustaw najtańszego kandydata, który spełnia Twoje wymagania, w węzłach zadań tego rodzaju pracy: jego agenta jako
   **Agenta** kroku, a jego model jako model kroku (albo agenta) ([Węzły zadań](workflows.md#task-nodes)), albo jego
   model jako **Domyślny profil modelu** projektu.
2. Mocniejszego kandydata zostaw tylko w tych węzłach zadań, w których benchmark pokazał realną różnicę.
3. Wybieraj zwycięski profil (model plus poziom rozumowania) i agenta (instrukcje, reguły, umiejętności), a nie
   tylko model.
4. Sprawdź wybór na prawdziwych zgłoszeniach: przepuść je przez zmieniony workflow i przez workflow referencyjny
   i porównaj w [Agenci: koszt i skuteczność](costs-insights.md#agent-insights).
5. Uruchom benchmark ponownie, gdy dostawca wyda nowy model lub zmieni ceny albo gdy zmienisz agenta.

Wydatki na benchmarki widać w **Kosztach** w przekroju **Wg benchmarku** (**By benchmark**) (zob.
[Koszty](costs-insights.md#costs)).
