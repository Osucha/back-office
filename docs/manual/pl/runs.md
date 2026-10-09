# Uruchomienia

Uruchomienie to jedno wykonanie workflowu, zwykle dla jednego zgłoszenia w Jirze. Ten rozdział opisuje
listę uruchomień, stronę uruchomienia, ponawianie i anulowanie, ręczne wysyłanie sygnału CI oraz
podpisane pochodzenie (provenance) uruchomienia.

<a id="run-list"></a>
## Lista uruchomień

**Uruchomienia** (**Runs**, `/runs`) wyświetlają na żywo wszystkie uruchomienia projektów, które możesz
oglądać. Każda strona projektu ma tę samą listę na karcie **Uruchomienia**.

| Kolumna | Znaczenie |
|---|---|
| **Kto trzyma** (**Holder**) | Znacznik pałeczki: kto teraz trzyma uruchomienie (zob. [Pałeczka](getting-started.md#baton)). |
| **Uruchomienie** (**Run**) | Klucz zgłoszenia (lub początek id uruchomienia) oraz workflow z wersją. |
| **Sztafeta** (**Relay**) | Miniaturowa ścieżka sztafety: jeden segment na każdy odwiedzony krok, pokolorowany według tego, kto go trzymał. |
| **Projekt** (**Project**) | Klucz projektu (tylko na stronie **Uruchomienia**). |
| **Uruchomione przez** (**Started by**) | Osoba, token CI lub API. |
| **Koszt** (**Cost**) | Dotychczasowy koszt modeli i agentów. |
| **Wiek** (**Age**) | Czas od utworzenia uruchomienia; najedź kursorem, aby zobaczyć dokładny czas. |

### Filtrowanie

- Wyszukuj po kluczu zgłoszenia, id uruchomienia lub nazwie workflowu.
- Znaczniki „kto trzyma” (agent, ty, ci, gotowe, zablokowane, bezczynne) filtrują według tego, kto trzyma
  uruchomienie.
- Gotowe filtry: **W toku** (**In flight**; agent i CI), **Nieudane** (**Failed**), **Uruchomione przeze
  mnie** (**Started by me**).
- **Wszystkie projekty** (**All projects**) zawęża do jednego projektu; potem **Wszystkie workflowy**
  (**All workflows**) zawęża do jednego workflowu.

Filtry są zapisywane w URL, więc możesz dodać przefiltrowaną listę do zakładek lub ją udostępnić. `j` / `k`
przechodzą między wierszami, a `Enter` otwiera uruchomienie. Długie listy przewijają się wirtualnie;
liczba wierszy na stronę pochodzi z Twoich preferencji (zob. [Twoje konto](account.md#profile)).

<a id="run-detail"></a>
## Strona uruchomienia

`/runs/:runId` pokazuje jedno uruchomienie. Nagłówek zawiera znacznik pałeczki, informację, kto
uruchomił workflow, koszt oraz pełną ścieżkę sztafety z etykietami, czasami trwania i kosztami; kliknij
segment, aby zaznaczyć dany krok.

### Banery

| Baner | Znaczenie |
|---|---|
| **Czeka na Ciebie (zatwierdzający)** (**Waiting for you (approver)**) / **Czeka na zatwierdzającego** (**Waiting for an approver**) | Uruchomienie zatrzymało się w bramce. **Przejrzyj bramkę** (**Review gate**) (lub **Pokaż bramkę**, **View gate**) otwiera panel bramki (zob. [Rozstrzyganie bramki](your-turn.md#gates)). |
| **Oczekiwanie na sygnał CI** (**Waiting for CI signal**) *nazwa* | Uruchomienie czeka, aż Twój pipeline wyśle `backoffice-ci signal <name> --status success|fail`. |
| **Błąd w węźle** (**Failed at**) *węzeł* | Węzeł, który zakończył się błędem, i komunikat błędu. |

### Karty

| Karta | Zawartość |
|---|---|
| **Oś czasu** (**Timeline**) | Po lewej: wszystkie kroki po kolei, z numerem próby, portem, przez który krok wyszedł, autorem wyniku, czasem trwania, kosztem i błędami. Po prawej: zaznaczony krok (lub **Całe uruchomienie**, **Whole run**) z podsumowaniem, pewnością i faktami oraz kartami **Artefakty (N)** (**Artefacts (N)**), **Logi** (**Logs**) i, po przeglądzie kodu, **Przegląd** (**Review**). |
| **Odtworzenie na kanwie** (**Canvas replay**) | Wersja workflowu, z którą wystartowało uruchomienie, ze ścieżką, którą przeszło. |
| **Wejście** (**Input**) | Wejście uruchomienia: klucz zgłoszenia, parametry i, dla uruchomień z CI, kontekst CI. |
| **Pochodzenie** (**Provenance**) | Podpisane atestacje uruchomienia (zob. [Pochodzenie](runs.md#provenance)). |

- **Artefakty** to dokumenty wytworzone przez kroki: `spec`, `plan`, `tasks`, `review`, `diff` i inne.
  Wybierz artefakt, aby go przeczytać; **Kopiuj** (**Copy**) kopiuje treść.
- **Logi** pokazują **Ten krok** (**This step**) lub **Wszystkie kroki** (**All steps**). Włącz
  **Śledź** (**Follow**), aby pozostać przy najnowszym wierszu; podczas trwania uruchomienia widoczny jest
  znacznik transmisji na żywo.
- **Przegląd** pokazuje werdykt, ocenę i uwagi z przeglądu kodu, a dla uruchomień z CI także **Otwórz
  przegląd CI** (**Open CI review**).

### Statusy

| Status | Pałeczka | Znaczenie |
|---|---|---|
| Oczekujące (Pending) | bezczynne, w kolejce | Utworzone, jeszcze nie wystartowało. |
| W trakcie (Running) | agent lub ci | Wykonuje się krok. |
| Wstrzymane (Waiting) | ty, u zatwierdzającego lub ci | Zatrzymane w bramce lub w oczekiwaniu na CI. |
| Zakończone (Succeeded) | gotowe (lub zablokowane, „zakończone: porażka”) | Dotarło do węzła Koniec. Węzeł Koniec z wynikiem Porażka kończy uruchomienie z wynikiem negatywnym. |
| Nieudane (Failed) | zablokowane, nieudane | Krok zakończył się błędem; uruchomienie można ponowić. |
| Anulowane (Cancelled) | bezczynne, anulowane | Anulowane przez człowieka albo przez odrzucenie bez ścieżki odrzucenia. |

<a id="repository-results"></a>
## Wyniki dla repozytoriów

Krok programistyczny może pracować w kilku repozytoriach projektu ([Repozytoria
kroku](workflows.md#step-repositories)). Strona uruchomienia pokazuje, co stało się w każdym z nich:
w faktach zaznaczonego kroku, a dla **Całego uruchomienia** jako najnowszy wynik każdego repozytorium ze
wszystkich kroków:

| Kolumna | Znaczenie |
|---|---|
| **Repozytorium** (**Repository**) | Klucz, przeznaczenie (**Kod źródłowy**, **Dokumentacja**, **Inne**) i oznaczenie głównego repozytorium. |
| **Dostęp** (**Access**) | **Odczyt** (**Read**) lub **Wypchnięcie i merge request** (**Push and merge request**). |
| **Gałąź** (**Branch**) | Gałąź wypchnięta przez agenta (albo, przy **Odczycie**, gałąź, na której pracował). |
| **Commit** | Commit po kroku, w skrócie; dla repozytoriów, do których wypychano, wypchnięty commit. |
| **Zmiany** (**Changes**) | Statystyka diffu: zmienione pliki, dodane i usunięte wiersze. |
| **Merge request** | Link do merge requestu otwartego przez wypchnięcie, jeśli powstał. |
| **Wypchnięto** (**Pushed**) | Tak tylko wtedy, gdy gałąź została wypchnięta do tego repozytorium. Repozytoria z dostępem **Odczyt** nigdy nie są wypychane. |
| **Błąd** (**Error**) | Dlaczego klonowanie, commit lub wypchnięcie w tym repozytorium się nie powiodło, jeśli tak się stało. |

Merge request w nagłówku uruchomienia (i ten, do którego odwołują się decyzje i bramki) to merge request
głównego repozytorium albo pierwszy wypchnięty, jeśli do głównego nic nie wypchnięto. Kroki wykonane przez
starsze runnery pokazują jeden wiersz, dla głównego repozytorium. Nieudane wypchnięcie do jednego
repozytorium nie cofa wypchnięć do pozostałych; sprawdź kolumnę **Błąd** i **Logi** kroku.

<a id="retry-cancel"></a>
## Ponawianie, anulowanie i ręczne wysyłanie sygnału

### Ponów nieudane uruchomienie

**Ponów od nieudanego kroku** (**Retry from failed step**) pojawia się przy nieudanym uruchomieniu, jeśli
masz `run.start`. Uruchomienie kontynuuje od kroku, który się nie powiódł; zakończone kroki nie są
powtarzane. Jeśli silnik oznaczył błąd jako niepodlegający ponowieniu, przycisk wyjaśnia to w podpowiedzi.
Najpierw usuń przyczynę: brakujący klucz dostawcy, limit sandboxa, uszkodzony szablon albo pętlę, która
osiągnęła **Maks. liczbę wizyt** (zob. [Rozwiązywanie problemów](troubleshooting.md#runs)).

Nieudane uruchomienia z ostatnich 7 dni, które uruchomiłeś lub możesz ponowić, są też w **Twoja kolej**.

### Anuluj uruchomienie

**Anuluj uruchomienie** (**Cancel run**) pojawia się przy aktywnym uruchomieniu, jeśli masz `run.cancel`
(role **Właściciel**, **Maintainer**, **Deweloper**). Potwierdź w oknie dialogowym: działające zadania
agentów zostają zatrzymane, a uruchomienie oznaczone jako anulowane. Tej operacji nie można cofnąć.

### Wyślij sygnał CI ręcznie

Gdy uruchomienie czeka na CI, a Ty masz `run.signal`, baner CI zawiera mały formularz: nazwę sygnału,
**Sukces** (**Success**) lub **Błąd** (**Fail**), opcjonalny komunikat i **Wyślij sygnał ręcznie**
(**Send signal manually**). Użyj go, gdy pipeline nie może zgłosić wyniku, na przykład po awarii CI.
Uruchomienie wychodzi przez odpowiedni port. Ręczne sygnały są zapisywane w dzienniku audytu.

<a id="provenance"></a>
## Pochodzenie

Gdy kończy się zadanie agenta, które wytworzyło commit lub diff, oraz gdy kończy się uruchomienie,
Back Office podpisuje **atestację**: oświadczenie, które wiąże commit (po jednym na każde repozytorium, do
którego wypchnięto zmiany) i diff ze sposobem ich powstania:
modelem, szablonem i promptami (jako skróty), polityką sandboxa i obrazem, dozwolonymi hostami, tokenami
i kosztem, wynikiem weryfikacji oraz tym, kto zatwierdził bramki po drodze. Jest podpisywana kluczem
platformy (Ed25519, koperta DSSE, oświadczenie in-toto). Każdy, kto ma klucz publiczny, może sprawdzić,
że nie została zmieniona. Nie dowodzi ona, że zmiana jest poprawna.

### Kroki

1. Otwórz uruchomienie, kartę **Pochodzenie**. Atestacje pojawiają się chwilę po zakończeniu zadania lub
   uruchomienia.
2. Wybierz jedną: **Uruchomienie** (**Run**) lub **Zadanie agenta · węzeł** (**Agent job · node**).
3. Przeczytaj **Podmioty** (**Subjects**; commit i diff z ich skrótami), **Kluczowe fakty** (**Key
   facts**), **Dozwolone hosty** (**Allowed hosts**) i **Zatwierdzenia** (**Approvals**); **Oświadczenie**
   (**Statement**) pokazuje pełną podpisaną treść.
4. Kliknij **Zweryfikuj** (**Verify**), aby sprawdzić podpis na serwerze, albo **Pobierz .intoto.jsonl**
   (**Download .intoto.jsonl**), aby zweryfikować go offline kluczami publicznymi z
   `/.well-known/back-office/keys`.

Oglądanie atestacji wymaga `project.view`. Weryfikacja offline i obsługa kluczy są opisane w
[Magazyn, podpisy i obserwowalność](integrations-other.md#signing) oraz
[Jak działają zadania agentów](agent-execution.md#provenance-attestations).

<a id="ci-executions"></a>
## Wykonania CI

Uruchomienie wystartowane przez Twój pipeline ma też wykonanie CI: **CI** na pasku bocznym (`/ci`)
wyświetla je dla wszystkich Twoich projektów, z wynikiem bramki (zaliczona lub niezaliczona, **doradczy**,
gdy nigdy nie oblewa zadania CI), punktem wejścia, repozytorium projektu, w którym działał pipeline, merge requestem lub gałęzią, commitem, kosztem
i wiekiem. Strona szczegółów pokazuje podsumowanie przeglądu, uwagi, kontekst CI (merge request, ref,
commit, pipeline i zadanie CI) oraz zmienione pliki.
