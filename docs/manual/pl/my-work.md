# Moja praca

Back Office działa na dwa sposoby jednocześnie. W **projekcie zespołowym** manager projektuje proces i decyduje,
o których krokach rozstrzygają ludzie, a które wykonują agenci; monitor zgłoszeń Jira projektu sam uruchamia
workflowy ([Monitor zgłoszeń Jira](integrations-jira.md#jira-intake)). **Moja praca** jest na drugi przypadek:
zespół pracuje, jak chce, a Ty automatyzujesz w Back Office **swoją** część. Twoje reguły obserwują zgłoszenia Jira
przypisane do Ciebie, każde nowe pojawia się w **Mojej pracy** z powiadomieniem, a nic nie startuje, dopóki nie
klikniesz **Start**.

<a id="my-work"></a>
## Jak to działa

1. Twoje workflowy trzyma **projekt prywatny** (poniżej) albo projekt zespołu, w którym możesz uruchamiać workflowy.
2. **Osobiste połączenie Jira** pozwala Back Office czytać Jirę **jako Ty** (**Moje konto → Połączenia**,
   [Połączenia osobiste](integrations-jira.md#jira-personal-connections)); wystarczy token do odczytu.
3. **Reguła** mówi, które zgłoszenia są Twoje (JQL), jak często sprawdzać i jaki workflow proponować.
4. Każde zgłoszenie, które pierwszy raz pojawi się w wynikach reguły, staje się **propozycją**: klucz, podsumowanie,
   typ, status. Kliknij **Start**, żeby uruchomić proponowany workflow, przycisk zamiany, żeby uruchomić inny, albo
   **Pomiń**. Pominiętą propozycję można przywrócić i uruchomić później.

Uruchomienie z propozycji jest Twoje, tak jakbyś kliknął **Uruchom** w projekcie: dostaje klucz zgłoszenia, a kroki
Jira ustawione na działanie jako osoba uruchamiająca używają Twojego osobistego połączenia.

<a id="private-projects"></a>
## Projekty prywatne

Na stronie **Projekty** przycisk **Utwórz projekt prywatny** tworzy projekt, którego jesteś jedynym członkiem (może to
każdy użytkownik; projekty zespołowe tworzą kierownicy projektów). Wszystko inne działa jak w projekcie zespołowym:
repozytoria albo ich brak, workflowy z kanwy lub z [biblioteki workflowów](workflow-library.md), budżety,
uruchomienia. Projekt ma oznaczenie **prywatny** i nie można dodawać do niego członków. Administratorzy platformy
nadal go widzą, jak każdy projekt.

<a id="rules"></a>
## Reguły

**Moja praca → Reguły → Nowa reguła**:

| Pole | Znaczenie |
|---|---|
| Nazwa | dla Ciebie, widoczna przy każdej propozycji |
| Czytaj Jirę jako | jedno z Twoich osobistych połączeń z prawem **Odczyt zgłoszeń** |
| JQL | które zgłoszenia są Twoje. Domyślne `assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC` znajduje otwarte zgłoszenia przypisane do Ciebie; `currentUser()` to konto Twojego połączenia. Zawęź je dowolnie, np. `AND project = SHOP` albo `AND status in ("To Do", "Selected")` |
| Sprawdzaj co | od 1 do 1440 minut (domyślnie 5) |
| Proponowany workflow | workflow, który możesz uruchomić; projekty prywatne na początku listy |
| Zaproponuj też zgłoszenia, które pasują już teraz | przy pierwszym sprawdzeniu: włączone = każde pasujące zgłoszenie zostanie zaproponowane raz; wyłączone = tylko te, które zaczną pasować później |

**Zgłoszenie jest proponowane raz na regułę**, gdy pierwszy raz pojawi się w wynikach, bez względu na powód: zostało
przypisane do Ciebie, weszło w status, o który pyta JQL, albo zostało utworzone. Jeśli zniknie z wyników i wróci, nie
zostanie zaproponowane ponownie. Każde sprawdzenie czyta najwyżej 50 ostatnio zmienionych zgłoszeń z JQL i tylko ich
klucz, podsumowanie, typ, status i priorytet; podsumowanie widzisz tylko Ty i reguła nigdy nie wysyła go do modelu
(zgłoszenie czyta krok **Pobierz zgłoszenie** uruchomienia, gdy je wystartujesz).

**Sprawdź teraz** uruchamia sprawdzenie od razu. Gdy Jira zawodzi (błędny JQL, wygasły token, limity), reguła pokazuje
błąd i sprawdza rzadziej, aż zadziała; reguła, której połączenie usunięto, zatrzymuje się z podaniem powodu.

<a id="notifications"></a>
## Powiadomienia

Gdy reguła znajdzie nowe zgłoszenia, Back Office pokazuje tylko Tobie powiadomienie pulpitu **Nowe zadanie Jira:
SHOP-42** (albo liczbę zadań), jeśli powiadomienia są włączone, a typ **Nowe zadania Jira** zaznaczony w **Moje konto →
Powiadomienia** ([Powiadomienia pulpitu](account.md#notifications)). Kliknięcie otwiera **Moją pracę**. Zakładka
**Nowe** odświeża się na żywo.

### Kto może co zrobić

| Chcesz | Uprawnienie |
|---|---|
| Korzystać z Mojej pracy (reguły, propozycje) | `connections.personal` (każdy **Członek**) |
| Utworzyć projekt prywatny | `projects.create.personal` (każdy **Członek**) |
| Uruchomić propozycję | `run.start` w projekcie workflowu (masz je jako właściciel swojego projektu prywatnego) |

Twoje reguły i propozycje widzisz tylko Ty; ich zmiany trafiają do dziennika audytu (`INTAKE_RULE_*`, `INTAKE_PROPOSAL_*`).
