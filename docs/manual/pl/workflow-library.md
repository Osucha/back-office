# Biblioteka workflowów

Workflow, który sprawdził się w jednym projekcie, nie musi być budowany od zera w kolejnym. Opublikuj go w
**Bibliotece**, dodaj stamtąd do innych projektów i zobacz, jak działa w każdym projekcie, który go uruchamia:
jak często kończy się sukcesem, jak często za pierwszym razem, ile trwa i ile kosztuje jeden sukces.
Ten rozdział opisuje strony **Biblioteka → Workflowy** oraz akcje biblioteki w zakładce **Workflowy** projektu.

<a id="library-workflows"></a>
## Workflowy w bibliotece

**Biblioteka → Workflowy** pokazuje wspólne workflowy z ich najnowszą wersją, projektami, które ich używają,
i najważniejszymi liczbami z wybranego okresu (7, 30 lub 90 dni albo bieżący kwartał):

| Kolumna | Znaczenie |
|---|---|
| Używany w | projekty (które widzisz) z powiązaną kopią |
| Uruchomienia | uruchomienia workflowu rozpoczęte w okresie |
| Sukces | uruchomienia zakończone końcem typu sukces, spośród zakończonych |
| Za pierwszym razem | udane uruchomienia bez decyzji „do poprawy” i bez ponowionego kroku, spośród zakończonych |
| Mediana czasu | połowa zakończonych uruchomień trwała krócej, łącznie z oczekiwaniem |
| Koszt / sukces | koszt udanych uruchomień z rejestru kosztów podzielony przez ich liczbę |

Kliknij wiersz, żeby otworzyć workflow. Zakładka **Skuteczność** pokazuje liczby szczegółowo, **Graf**
pokazuje dowolną wersję na kanwie tylko do odczytu, **Wersje** mówią, co zmieniło się w każdej wersji i skąd
ją opublikowano, a **Projekty** pokazują powiązane kopie: którą wersję śledzą, czy jest nowsza i czy zostały
zmienione lokalnie.

### Kto może co zrobić

| Chcesz | Uprawnienie |
|---|---|
| Widzieć workflowy biblioteki i ich metryki | `library.view` (każdy **Członek**); metryki liczą tylko uruchomienia projektów, które widzisz |
| Opublikować workflow w bibliotece | `library.manage` (rola **Kurator biblioteki**) oraz `workflow.edit` w projekcie źródłowym |
| Zmienić nazwę, tagi, wycofać lub usunąć workflow biblioteki | `library.manage` |
| Dodać workflow biblioteki do projektu, zaktualizować lub odłączyć kopię | `workflow.edit` w tym projekcie (oraz `library.view`) |

<a id="publish"></a>
## Publikowanie workflowu

1. Zaprojektuj i uruchamiaj workflow w projekcie, aż działa tak, jak chcesz, i zapisz go.
2. W zakładce **Workflowy** projektu otwórz menu workflowu (**⋯**) i wybierz **Opublikuj w bibliotece**.
3. Wybierz **Nowy workflow w bibliotece** (nazwa, klucz, opis, tagi) albo **Nowa wersja istniejącego**.
   Workflow powiązany już z biblioteką domyślnie proponuje swoją kolejną wersję.
4. Napisz jednym zdaniem, co się zmieniło. Widać to w historii wersji obok metryk każdej wersji.

Czego się **nie** publikuje, bo należy do projektu: repozytoriów kroku, połączenia Jira przypiętego do kroku
(tryby *połączenie systemowe* i *użytkownik działający* zostają), imiennie wskazanych zatwierdzających bramek i
kroków zapisu (role zatwierdzających zostają) oraz nazwy, wyzwalaczy i stanu włączenia workflowu. Kroki muszą
używać **globalnych** szablonów zadań: szablon projektowy trzeba najpierw opublikować w globalnej bibliotece
zadań. Opublikowane wersje nigdy się nie zmieniają; publikacja niezmienionego grafu jest odrzucana.

<a id="adopt"></a>
## Dodawanie workflowu do projektu

W projekcie kliknij **Dodaj z biblioteki** (obok **Nowy workflow**) albo otwórz workflow w bibliotece i kliknij
**Użyj w projekcie**. Wybierz workflow, wersję (domyślnie najnowszą) i, jeśli chcesz, inną nazwę. Projekt
dostaje **powiązaną kopię**: zwykły workflow, który się uruchamia, może być startowany z CI i zmieniany w
projektancie workflowów, oznaczony kluczem i wersją z biblioteki (np. `issue-to-mr v3`). Części lokalne ustaw w projektancie workflowów:
repozytoria kroków, połączenie Jira i imiennie wskazanych zatwierdzających. Wycofane workflowy nie są
proponowane.

<a id="update-detach"></a>
## Nowe wersje, zmiany lokalne i odłączanie

Gdy biblioteka ma nowszą wersję, kopia pokazuje **dostępna v4**. Wybierz **Aktualizuj z biblioteki** w menu
workflowu: kroki, decyzje i bramki nowej wersji zastępują stare, a lokalne części kroków o tym samym id,
nazwa, wyzwalacze i stan włączenia zostają. Workflow dostaje nową wersję, jak przy każdym zapisie; trwające
uruchomienia zachowują wersję, z którą wystartowały.

Kopia, której graf zmieniono w projekcie, jest oznaczona **zmieniony lokalnie** (przesuwanie węzłów na kanwie
i zmiana części lokalnych się nie liczą). Aktualizacja takiej kopii zastępuje Twoje zmiany, więc Back Office
najpierw pyta. Jeśli zmiana jest dobra dla wszystkich, opublikuj ją jako nową wersję w bibliotece.

**Odłącz od biblioteki** usuwa powiązanie: workflow zostaje taki, jaki jest, nie dostaje kolejnych wersji, a
jego przyszłe uruchomienia nie liczą się do metryk biblioteki. Dawne uruchomienia zachowują wersję, którą wykonały.

<a id="metrics"></a>
## Mierzenie workflowów

Każde uruchomienie powiązanej kopii zapisuje workflow i wersję z biblioteki, którą wykonał, więc metryki są dokładne
także po zmianie kopii lub biblioteki. Zakładka **Skuteczność** workflowu biblioteki pokazuje:

- **Skuteczność**: odsetek sukcesów (udane spośród zakończonych; nieudane i anulowane obniżają wynik),
  odsetek „za pierwszym razem” (udane bez poprawek i ponowień) oraz pętle poprawek na uruchomienie.
- **Wydajność**: mediana i p90 czasu do zakończenia (z oczekiwaniem), mediana **czasu aktywnego** (czas do
  zakończenia minus oczekiwanie na ludzi przy bramkach i na CI: tyle zajmują agenci i kroki), oczekiwanie na
  ludzi, koszt na uruchomienie i na udane uruchomienie, tokeny.
- **Według wersji**, od najnowszej, każda porównana z poprzednią wersją, która ma zakończone uruchomienia: zielona
  strzałka to poprawa (sukcesy wyżej o co najmniej 5 punktów, czas lub koszt niżej o co najmniej 10 %),
  czerwona to pogorszenie. Wersje z mniej niż 5 zakończonymi uruchomieniami mają znak `*`: poczekaj na więcej uruchomień,
  zanim wyciągniesz wnioski.
- **Według projektu**: te same liczby dla każdego projektu, żeby zobaczyć, gdzie workflow sobie nie radzi.
- **Kroki**: wykonania, porażki, mediana i p90 czasu, koszt i wybrane porty (np. jak często bramka
  odpowiedziała `approve` lub `revise`). Krok, który często zawodzi albo generuje większość kosztu, jest
  wyróżniony.

Uruchomienia kopii zmienionych lokalnie są liczone, a ich liczba jest pokazana, więc wiadomo, czy różnicę zrobiła
wersja, czy zmiany w projekcie. Każdy workflow projektu, powiązany czy nie, ma ten sam widok dla własnych
wersji: ikona wykresu w zakładce **Workflowy** projektu.

Definicje wszystkich liczb: [docs/architecture/11-governance.md](../../architecture/11-governance.md#workflow-metrics).
