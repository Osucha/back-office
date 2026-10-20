# Modele i ewaluacja

Profile modeli to katalog modeli, z którego korzystają zadania i benchmarki, wraz z ich opcjami i cenami.
Ewaluacja mierzy te modele na pracy takiej jak Twoja, dzięki czemu wybierasz je na podstawie liczb, a nie
opinii. Ten rozdział opisuje stronę **Modele** (**Models**), zestawy zadań i benchmarki.

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
| Benchmark | modele kandydujące i model oceniający |

Profil węzła wygrywa z profilem szablonu, a ten z domyślnym profilem projektu.

<a id="suites"></a>
## Zestawy zadań

Zestaw zadań to praca, na której mierzy się modele: zgłoszenia, specyfikacje, przeglądy i zadania
programistyczne takie jak Twoje. **Ewaluacja** (**Evaluation**) → **Zestawy zadań** (**Task suites**,
`/evaluation/suites`) wyświetla je. Zestaw startowy **Starter: SDLC tasks** pokazuje strukturę; skopiuj
go i zastąp treść własną pracą. Jego pięć zadań programistycznych działa na Spring PetClinic
(`https://github.com/spring-projects/spring-petclinic`, przypięty commit, weryfikacja `./mvnw -B -q test` w około
100 sekund), więc pierwszy benchmark działa bez własnego repozytorium.

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
4. W sekcji **Wykonanie** (**Execution**) ustaw **Limit czasu (sekundy)** (**Timeout (seconds)**)
   i **Wagę** (**Weight**; względna waga w agregatach zestawu).
5. Sprawdź kartę **Podgląd** (**Preview**), a potem kliknij **Dodaj zadanie**.

Z menu strony zestawu użyj **Edytuj zestaw** (**Edit suite**) i **Usuń zestaw** (**Delete suite**);
**Uruchom benchmark tego zestawu** (**Benchmark this suite**) tworzy dla niego nowy benchmark.

### Jak napisać dobre kryteria oceny

- Nazwij od 3 do 5 kryteriów, każde oceniane w skali 0–10, z jednym zdaniem opisującym, jak wygląda 10.
- Wprost umieść w kryteriach to, co odrzuciłbyś na przeglądzie, na przykład „bez nazw technologii”.
- Podaj odpowiedź wzorcową, gdy zadanie ma znaną odpowiedź; model oceniający traktuje ją jako jedną z dobrych
  odpowiedzi.
- Oceniaj tylko to, o co prosiły instrukcje.

Dobre zestawy mają od 8 do 20 zadań z różnych kategorii i poziomów trudności, co najmniej dwa na
kategorię. Zadania programistyczne wymagają repozytorium, które runner może sklonować, i polecenia
weryfikacji, które przechodzi na bazowej referencji.

**Punktacja.** Zadania typu prompt dostają ocenę modelu oceniającego (0–100) i są zaliczone od 70. Zadania
programistyczne dostają `verification × (passed ? 100 : 0) + judge × judge score`, z wagami zestawu.

<a id="benchmarks"></a>
## Benchmarki

Benchmark uruchamia zestaw na kilku modelach, oceniany przez jeden, stały model oceniający.
**Ewaluacja** → **Benchmarki** (**Benchmarks**, `/evaluation/benchmarks`) wyświetla je z informacją, kto
je trzyma, zestawem, liczbą modeli, postępem prób i kosztem.

### Kroki: uruchamianie benchmarku

1. Kliknij **Utwórz benchmark** (**Create benchmark**).
2. Wpisz **Nazwę** i wybierz **Zestaw zadań** (**Task suite**). Opcjonalnie wybierz **Zadania**
   (**Tasks**); puste oznacza wszystkie zadania.
3. Wybierz **Modele kandydujące** (**Candidate models**). Nie można wybrać profili, których dostawca nie
   ma klucza.
4. Wybierz **Model oceniający** (**Judge model**). Użyj najmocniejszego modelu, na jaki Cię stać, i nigdy
   żadnego z kandydatów.
5. Ustaw **Powtórzenia** (**Repetitions**; 1–5); dwa lub trzy pozwalają zmierzyć, jak stabilne są wyniki.
6. Dla zadań programistycznych wybierz **Agenta programistycznego** (**Coding harness**): **OpenCode**
   (działa z każdym dostawcą) lub **Claude Code** (tylko Anthropic).
7. Gdy zadania obejmują zadania programistyczne, wybierz, gdzie mają działać, w polu **Zadania kodujące
   uruchamiaj na** (**Coding tasks run on**): **Współdzielone runnery** (**Shared runners**, flota platformy)
   albo **Mój osobisty runner** (**My personal runner**, Twój komputer, zob.
   [Osobisty runner](personal-runner.md)). Przy każdej opcji widać, ile runnerów jest online; nieskonfigurowanej
   nie da się wybrać, a gdy żaden nie jest online, próby kodujące czekają na połączenie runnera (najwyżej 24
   godziny). Zadania promptowe nie potrzebują runnera.
8. Kliknij **Utwórz i uruchom** (**Create and start**) albo **Zapisz jako szkic** (**Save as draft**),
   a później **Uruchom benchmark** (**Start benchmark**) na jego stronie. **Utwórz i uruchom** najpierw wysyła
   jednolinijkowy test do każdego kandydata i modelu oceniającego (**Sprawdź modele**, **Check models**, robi
   tylko to): modele, które nie odpowiadają, są wypisane z powodem podanym przez dostawcę, na przykład brak
   dostępu do modelu albo wyczerpany limit dzienny. Usuń je albo kliknij **Uruchom mimo to** (**Start anyway**).

Formularz pokazuje, ile prób zaplanuje benchmark (zadania × modele × powtórzenia, każda dodatkowo raz
oceniana). Każda próba kosztuje tokeny kandydata plus tokeny modelu oceniającego; próby programistyczne na
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

Strona benchmarku ma karty **Ranking** (**Leaderboard**) i **Próby · N** (**Attempts · N**).

| Kolumna rankingu | Znaczenie |
|---|---|
| **Wynik** (**Score**) | Średnia ocena z udanych prób. |
| **Odsetek zaliczonych** (**Pass rate**) | Udział zaliczonych prób (programistyczne: testy przechodzą; prompt: ocena 70 lub więcej). Próby nieudane i przekraczające czas liczą się jako niezaliczone. |
| **Średni koszt** (**Mean cost**), **Łącznie** (**Total**) | Koszt kandydata na próbę i ogółem. |
| **Wynik / $** (**Score / $**) | Średni wynik na dolara średniego kosztu. |
| **Czas p50 / p95** (**Time p50 / p95**) | Czas odpowiedzi samego kandydata (odpowiedź modelu albo zadanie agenta od zlecenia do wyniku), bez modelu oceniającego, kolejki i ponowień, z udanych prób: połowa była szybsza niż p50, 95% szybsze niż p95. Poniżej 20 prób p95 to po prostu najwolniejsza próba; podpowiedź mówi, z ilu prób policzono czasy. |
| **Stabilność** (**Stability**) | Odchylenie standardowe wyniku między powtórzeniami; im niższe, tym stabilniej. |

**Wynik a koszt** (**Score vs cost**) pokazuje na wykresie każdy model; model, który inny model bije
zarówno wynikiem, jak i kosztem, jest zdominowany. Przekroje **Ogółem** (**Overall**), **Wg kategorii**
(**By category**), **Wg trudności** (**By difficulty**) i **Wg zadania** (**By task**) powtarzają tabelę
dla każdego wycinka. Na karcie **Próby** filtruj po zadaniu i modelu i otwórz próbę, aby przeczytać
wynik, kryteria i uzasadnienie modelu oceniającego oraz wynik weryfikacji. Zanim wyciągniesz wnioski, przeczytaj
najgorszą próbę każdego modelu: niska ocena to często problem z kryteriami oceny.

### Wykorzystanie wyniku

1. Ustaw najtańszy profil, który spełnia Twoje wymagania, jako **Domyślny profil modelu** projektu.
2. Mocniejszy profil zostaw tylko w tych węzłach zadań, w których benchmark pokazał realną różnicę.
3. Wybieraj zwycięski profil (model plus poziom rozumowania), a nie tylko model.
4. Uruchom benchmark ponownie, gdy dostawca wyda nowy model lub zmieni ceny.

Wydatki na benchmarki widać w **Kosztach** w przekroju **Wg benchmarku** (**By benchmark**) (zob.
[Koszty](costs-insights.md#costs)).
