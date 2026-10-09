# Twoja kolej i skrzynka

Wszystko, co wymaga człowieka, trafia do jednej kolejki: bramki do rozstrzygnięcia, zapisy w Jirze do
zatwierdzenia, nieudane uruchomienia do ponowienia, nieudane przeglądy CI, budżety wymagające uwagi
i wygasające tokeny Jiry. Ten rozdział opisuje **Stronę główną** (**Home**), stronę **Twoja kolej**
(**Your turn**), sposób rozstrzygania bramki i zatwierdzania zapisu zewnętrznego.

<a id="home"></a>
## Strona główna

`/home` to strona, na którą trafiasz po zalogowaniu. Najpierw odpowiada na pytanie: „czy coś na mnie
czeka?”.

| Sekcja | Co pokazuje |
|---|---|
| **Twoja kolej** | Pierwsze elementy Twojej kolejki jako karty, od najpilniejszych. Gdy jest ich więcej, **Wszystkie (N) w Twojej kolejce** (**All N in Your turn**) otwiera pełną stronę. „Nic na Ciebie teraz nie czeka.” to normalny, spokojny stan. |
| **W toku** (**In flight**) | Niezakończone uruchomienia pogrupowane jako **Agenci pracują** (**Agents working**), **Czeka na CI** (**Waiting for CI**), **U innych zatwierdzających** (**With other approvers**) i **W kolejce** (**Queued**), każde z miniaturową ścieżką sztafety, kosztem i wiekiem. **Wszystkie uruchomienia w toku** (**All runs in flight**) otwiera **Uruchomienia** (**Runs**) z filtrem na agenta i CI. |
| **Konfiguracja Back Office** (**Set up Back Office**) | Tylko dla administratorów, dopóki kroki konfiguracji nie zostaną wykonane (zob. [Pierwsze kroki](getting-started.md#first-steps)). |
| **Zakończone dziś** (**Landed today**) | Uruchomienia zakończone dziś pomyślnie, z czasem trwania i kosztem. |
| **Koszt w tym tygodniu** (**Cost this week**) | Wydatki z ostatnich siedmiu dni i suma z bieżącego miesiąca, z linkiem do **Kosztów** (**Costs**). Widoczne, jeśli możesz oglądać zużycie. |

Klawiatura: `j` / `k` przechodzą między kartami, `Enter` otwiera kartę, a `a` otwiera bramkę pod kursorem
na jej liście kontrolnej. Bramki nigdy nie zatwierdza się ze Strony głównej; `a` tylko otwiera panel
bramki.

<a id="inbox"></a>
## Strona Twoja kolej

`/inbox` (pasek boczny: **Twoja kolej**) to pełna kolejka. Przełącznik u góry ma cztery widoki:

| Widok | Zawartość |
|---|---|
| **Czeka na Ciebie** (**Needs you**) | Twoja kolejka (poniżej). Liczba to liczba elementów. |
| **U innych** (**With others**) | Otwarte bramki Twoich projektów, które musi rozstrzygnąć ktoś inny. Możesz je otworzyć do odczytu, ale nie rozstrzygać. |
| **Rozstrzygnięte** (**Decided**) | Rozstrzygnięte bramki wraz z tym, kto je rozstrzygnął. |
| **Wygasłe** (**Expired**) | Bramki, którym upłynął czas, zanim ktokolwiek je rozstrzygnął. |

### Co trafia do Twojej kolejki

| Element | Kiedy się pojawia | Główna akcja |
|---|---|---|
| Bramka | Otwarta bramka, którą możesz rozstrzygnąć. | **Przejrzyj bramkę** (**Review gate**) (`a`) |
| Zatwierdzenie zapisu | Krok chce zapisać coś w Jirze, a Ty możesz to zatwierdzić, na przykład „Agent chce skomentować SHOP-42” ([Zatwierdzanie zapisów zewnętrznych](#write-confirmations)). | Otwórz je, potem **Zatwierdź** lub **Odrzuć** |
| Wygasający token Jiry | Połączenie, którym zarządzasz, ma token wygasający w ciągu 14 dni lub już wygasły: „Token Jiry połączenia <nazwa> wygasa za 5 dni” / „… wygasł”. | Otwórz połączenie, **Zastąp token** (**Replace token**) |
| Nieudane uruchomienie | Uruchomienie, które nie powiodło się w ciągu ostatnich 7 dni, a które sam uruchomiłeś lub możesz ponowić (`run.start`). | **Otwórz uruchomienie** (**Open run**) |
| Nieudany przegląd CI | Wykonanie CI jednego z Twoich projektów, które nie powiodło się lub którego bramka przeglądu nie przeszła, w ciągu ostatnich 3 dni. Błąd CI widoczny już jako nieudane uruchomienie nie jest pokazywany drugi raz. | **Otwórz przegląd** (**Open review**) |
| Budżet | Miesięczny budżet, którym zarządzasz, przekroczył próg alertu lub limit. | **Przejrzyj budżet** (**Review budget**) albo **Podnieś limit** (**Raise the limit**) po przekroczeniu |

Kolejność: najpierw bramki i zatwierdzenia zapisu, potem nieudane uruchomienia i przekroczone budżety,
potem nieudane CI, na końcu alerty budżetowe i wygasające tokeny; w obrębie grupy najpierw to, co czeka
najdłużej. Każda karta pokazuje co,
gdzie (klucz projektu · workflow lub zgłoszenie · węzeł), dlaczego Ty (na przykład „Odpowiedzialny:
właściciel · 2 wymagane punkty · pewność agenta 72%”) i jak długo czeka.

Bursztynowy licznik na górnym pasku i na pasku bocznym liczy tylko bramki, które możesz rozstrzygnąć.

Do bramki można też prowadzić bezpośrednim linkiem: `/inbox/:approvalId` otwiera ją w panelu bocznym.

<a id="gates"></a>
## Rozstrzyganie bramki

Bramka to punkt workflowu, w którym uruchomienie zatrzymuje się i czeka na człowieka. Kto może ją
rozstrzygnąć, określa się na bramce w workflowie (zob. [Ustawienia bramki](workflows.md#gates-config)).

### Kroki

1. Otwórz bramkę: kliknij jej kartę, naciśnij `a` na Stronie głównej lub na stronie **Twoja kolej** albo kliknij
   **Przejrzyj bramkę** na stronie uruchomienia. Bramka otwiera się w panelu bocznym; `Esc` go zamyka.
2. Przeczytaj górę panelu: pytanie, podsumowanie agenta i jego **Pewność agenta** (**Agent confidence**).
3. Sprawdź sekcję **Dlaczego Ty** (**Why you**): rolę **Odpowiedzialny** (**Accountable**),
   **Zatwierdzających** (**Approvers**), **Przypisane do** (**Assigned to**: wskazane osoby albo role;
   „(Ty)”, gdy to Ty, oraz kto zmienił przypisanie), **Regułę** (**Rule**), gdy osoba, która uruchomiła
   workflow, nie może go zatwierdzić, oraz link **Otwórz uruchomienie** (i **Merge request**, jeśli istnieje).
4. Przeczytaj **Artefakty** (**Artefacts**) pokazywane przez bramkę (na przykład `spec`, `plan`, `review`,
   `diff`), każdy na osobnej karcie. Aby coś poprawić samodzielnie, kliknij **Edytuj przed decyzją**
   (**Edit before deciding**), zmień tekst i użyj **Podglądu** (**Preview**) lub **Przywróć**
   (**Revert**). Edytowane artefakty są zapisywane razem z Twoją decyzją, a uruchomienie kontynuuje
   z Twoją wersją.
5. Zaznacz sprawdzone pozycje **Listy kontrolnej** (**Checklist**). Licznik pokazuje, ile wymaganych
   pozycji jest zaznaczonych.
6. W razie potrzeby napisz komentarz i kliknij przycisk decyzji.

### Przyciski decyzji

| Przycisk | Skrót | Skutek | Wymaga |
|---|---|---|---|
| **Zatwierdź** (**Approve**) (lub **Zatwierdź i scal**, **Approve and merge**) | `a` | Uruchomienie kontynuuje przez port zatwierdzenia. | Zaznaczenia wszystkich wymaganych pozycji listy kontrolnej |
| **Odeślij do poprawy** (**Send back for revision**) | `r` | Twój komentarz trafia do uwag uruchomienia, a uruchomienie cofa się, zwykle do zadania, które wytworzyło artefakt. | Komentarza |
| **Odrzuć** (**Reject**) | `x` | Uruchomienie idzie ścieżką odrzucenia; jeśli workflow jej nie ma, uruchomienie jest anulowane. | Komentarza |

Bramka może oferować inne opcje niż te trzy; każda opcja to przycisk z własną nazwą. Gdy przycisk jest
niedostępny, panel wyjaśnia słownie dlaczego, pod przyciskami, na przykład „Zatwierdzenie niedostępne:
najpierw zaznacz wszystkie wymagane pozycje listy kontrolnej.”

### Gdy nie możesz rozstrzygnąć

Stopka panelu wyświetla **Nie możesz rozstrzygnąć tej bramki.** (**You cannot decide this gate.**)
z powodem, na przykład że nie jesteś jednym z zatwierdzających albo że to Ty uruchomiłeś workflow,
a bramka zabrania samozatwierdzania. Rozstrzyganie wymaga uprawnienia projektowego `run.approve` (role
projektowe **Właściciel**, **Maintainer**, **Zatwierdzający**) oraz spełnienia reguły zatwierdzających
samej bramki. Reguła jest kopiowana w chwili, gdy uruchomienie dociera do bramki, więc późniejsze zmiany
workflowu nie zmieniają tego, kto rozstrzyga otwartą bramkę; może ją przypisać komuś innemu właściciel
lub maintainer projektu ([Przypisywanie kroku](#assign-steps)).

### Co widzisz potem

Bramka znika z Twojej kolejki, uruchomienie kontynuuje, a decyzja pojawia się na osi czasu uruchomienia
i w widoku **Rozstrzygnięte** bramki, z Twoim nazwiskiem, zaznaczonymi pozycjami i komentarzem. Każda
decyzja jest zapisywana w dzienniku audytu. Bramka z limitem czasu, której nikt nie rozstrzygnie,
przechodzi do **Wygasłych**, a uruchomienie idzie ścieżką przekroczenia czasu bramki (lub kończy się
niepowodzeniem, jeśli jej nie ma).

### Rozstrzyganie z IDE

Mając osobisty token dostępu (PAT), możesz wyświetlać swoje elementy z kolejki i rozstrzygać bramki
z poziomu agenta programistycznego przez serwer MCP (`list_my_turns`, `decide_gate`). Zob.
[IDE i MCP](integrations-ide.md#mcp-tools).

<a id="write-confirmations"></a>
## Zatwierdzanie zapisów zewnętrznych

Zanim krok workflowu zapisze coś w Jirze (komentarz z **Notify Jira**, zmianę statusu z **Transition
issue**), uruchomienie zatrzymuje się i prosi człowieka o zatwierdzenie dokładnie tego zapisu. Gdy go
widzisz, nic jeszcze nie zostało zapisane. Dlaczego: [Zatwierdzenie przed zapisem
w Jirze](integrations-jira.md#jira-write-approval).

### Co widzisz

Zatwierdzenie pojawia się w **Twoja kolej → Czeka na Ciebie** tak jak bramka, a jeśli masz włączone
powiadomienia *Twoja kolej*, także jako powiadomienie na pulpicie „Potwierdź zapis w Jirze”
([Powiadomienia na pulpicie](account.md#notifications)). Jego panel pokazuje:

- pytanie: „Agent chce skomentować SHOP-42” albo „Agent chce przenieść SHOP-42 do In Review”;
- przy komentarzu **dokładny tekst**, który zostanie dodany, w takiej postaci, w jakiej się pojawi; przy
  zmianie statusu status docelowy;
- klucz zgłoszenia, połączenie (konto Jiry), które wykona zapis, skrót SHA-256 treści oraz uruchomienie i krok;
- **Dlaczego Ty**, **Przypisane do** i **Regułę**, tak jak przy bramce.

Tekstu nie można edytować: to, co zatwierdzasz, zostanie zapisane znak w znak.

### Kroki

1. Otwórz zatwierdzenie z jego karty albo ze strony uruchomienia.
2. Przeczytaj cały tekst. Szukaj wszystkiego, co nie powinno trafić do Jiry: szczegółów wewnętrznych,
   nieznanych linków, poleceń skierowanych do ludzi lub innych narzędzi, tekstu skopiowanego ze
   zgłoszenia, który zmienia sens.
3. Opcjonalnie napisz komentarz.
4. Kliknij **Zatwierdź i zapisz** (**Approve and write**), aby zapisać dokładnie to, albo **Odrzuć (nic nie
   zapisuj)** (**Reject (write nothing)**).

| Decyzja | Skutek |
|---|---|
| **Zatwierdź i zapisz** | Krok zapisuje pokazany tekst (lub przenosi zgłoszenie), a uruchomienie kontynuuje. |
| **Odrzuć (nic nie zapisuj)** | Nic nie zostaje zapisane. Krok kończy się błędem „write rejected by <Twoje nazwisko>, nothing was written ... - <komentarz>”, a uruchomienie idzie ścieżką `failure` kroku; jeśli jej nie ma, kończy się niepowodzeniem. |

**Kto może zdecydować**: domyślnie **Właściciele** i **Maintainerzy** projektu z `run.approve`; projektant
workflowu może wskazać inne role lub osoby i zabronić decydowania osobie, która uruchomiła workflow
([Zatwierdzanie przed zapisem zewnętrznym](workflows.md#write-confirmations)). Właściciele i maintainerzy
mogą przekazać czekające zatwierdzenie komuś innemu przyciskiem **Przypisz…** (**Assign…**)
([Przypisywanie kroku](#assign-steps)).

Zatwierdzenia zapisu nie wygasają; uruchomienie czeka, aż ktoś zdecyduje albo je anuluje. Zarówno decyzja
(`RUN_WRITE_DECIDED`), jak i sam zapis (`EXTERNAL_WRITE`) trafiają do dziennika audytu ze skrótem SHA-256
tekstu.

### Wygasający token Jiry

Osoby, które mogą zarządzać połączeniem Jiry (administratorzy w przypadku połączeń platformy, właściciele
projektu w przypadku połączeń projektu), dostają też kartę, gdy token wygasa w ciągu 14 dni („Token Jiry
połączenia <nazwa> wygasa za 5 dni”) lub już wygasł („Token Jiry połączenia <nazwa> wygasł”). Otwórz połączenie i użyj **Zastąp token**
(**Replace token**); karta zniknie, gdy nowa data wygaśnięcia będzie odległa o więcej niż 14 dni. Kroki:
[Wygaśnięcie i rotacja tokenu](integrations-jira.md#jira-token-expiry).

<a id="assign-steps"></a>
## Przypisywanie kroku

W Back Office projekt tworzy kierownik projektów i to on decyduje, kto wykonuje który krok. Workflow
określa, kto rozstrzyga każdą bramkę w każdym uruchomieniu ([Ustawienia bramki](workflows.md#gates-config));
gdy krok już czeka, **Właściciel** (**Owner**) lub **Maintainer** projektu (uprawnienie projektowe
`run.assign`) może przekazać go wskazanym osobom tylko w tym uruchomieniu, na przykład gdy wskazany
zatwierdzający jest na urlopie.

### Kroki

1. Otwórz czekającą bramkę (z widoku **U innych**, **Czeka na Ciebie** albo przyciskiem **Przejrzyj
   bramkę** / **Pokaż bramkę** na stronie uruchomienia) albo zostań na stronie uruchomienia.
2. Kliknij **Przypisz…** (**Assign…**) obok **Przypisane do** w panelu bramki albo w banerze uruchomienia
   „Czeka na zatwierdzającego”.
3. W oknie **Kto rozstrzyga ten krok** (**Who decides this step**) wybierz jedną lub więcej osób w polu
   **Przypisz do** (**Assign to**). Lista zawiera tylko członków projektu, którzy mogą rozstrzygnąć tę
   bramkę: mają `run.approve`, jedną z ról bramki (jeśli są ustawione) i nie uruchomili workflowu, gdy
   bramka zabrania samozatwierdzania.
4. Opcjonalnie dodaj komentarz (na przykład powód) i kliknij **Przypisz** (**Assign**).

**Przywróć regułę workflowu** (**Back to the workflow's rule**) usuwa przypisanie z czasu uruchomienia;
znów obowiązuje reguła samej bramki.

### Co widzisz potem

- Bramka pokazuje **Przypisane do** z nowymi osobami oraz „przypisane przez” z Twoim nazwiskiem, czasem
  i komentarzem. Baner na stronie uruchomienia pokazuje „Przypisane do: …”.
- Przypisane osoby widzą bramkę na bieżąco w **Twoja kolej → Czeka na Ciebie** („Przypisane do Ciebie
  przez …”); osoby wskazane wcześniej widzą ją w widoku **U innych**.
- Sam workflow się nie zmienia; następne uruchomienie znów stosuje regułę workflowu.
- Zmiana jest zapisywana w dzienniku audytu jako `APPROVAL_ASSIGN` ze starymi i nowymi osobami.

Przypisać można tylko otwartą bramkę. Zasada czterech oczu i role bramki obowiązują zawsze: nie można
przypisać bramki komuś, kogo reguła by odrzuciła.
