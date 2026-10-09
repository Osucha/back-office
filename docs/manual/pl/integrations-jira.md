# Jira

Back Office odczytuje zgłoszenia z Jiry, aby rozpocząć pracę, dodaje komentarze z wynikiem i przesuwa
zgłoszenia w ich workflow. Komunikuje się z **Jira Cloud** przez REST API v3 za pomocą **połączeń**:
nazwanych poświadczeń Jiry (witryna, e-mail konta, token API), które określają, co Back Office wolno z nimi
robić. Każdy projekt ma **połączenie systemowe**, którego jego kroki Jiry używają domyślnie, oraz listę
kolejnych połączeń; krok może też działać jako **osoba, która go zatwierdza lub rozpoczyna**, przez jej własne
**połączenie osobiste** (ADR 0035). **Tokeny API z zakresami** zachowują zasadę najmniejszych uprawnień nawet
wtedy, gdy jedynym dostępnym kontem Jiry jest Twoje własne. Projekt może też pozwolić Jirze rozpoczynać pracę: **monitor zgłoszeń Jira** odpytuje Jirę
przez połączenie do odczytu i uruchamia workflow, gdy zgłoszenie trafi do wybranego statusu (ADR 0033).
Każdy komentarz i każda zmiana statusu czeka najpierw, aż człowiek zatwierdzi dokładnie ten zapis, chyba
że projektant wyłączył to dla danego kroku. Ten rozdział wyjaśnia, dlaczego tak jest, jak przygotować
konta Jiry z dokładnie potrzebnymi uprawnieniami, jak dodać i przetestować połączenia, jak kroki workflowu
je wybierają, jak zatwierdza się zapisy i jak działa monitor.

**Kogo to dotyczy:** administratora platformy (połączenia platformy, klucz szyfrowania), właściciela
projektu (połączenia projektu, połączenie systemowe, klucz projektu Jira, monitor zgłoszeń), każdego
użytkownika (połączenia osobiste), projektanta workflowów (**Działaj jako** na węźle, zatwierdzenie przed
zapisem), zatwierdzających (zatwierdzanie zapisów, wybór, kto zapisuje).

<a id="jira-overview"></a>
## Jak Back Office korzysta z Jiry

### Jak Back Office rozmawia z Jirą

Serwer Back Office wywołuje **REST API v3 Jira Cloud** bezpośrednio przez HTTPS. Nie ma serwera MCP, narzędzia
wiersza poleceń ani tokenu Jiry w piaskownicach, w których działają agenci kodujący. To świadomy wybór:

- **Każdy krok Jiry jest deterministyczny**: to jedno lub dwa znane wywołania REST (odczyt zgłoszenia, dodanie
  komentarza, przeniesienie zgłoszenia) z treścią przygotowaną przez Back Office, a nie coś, o czym decyduje
  model.
- **Zasada najmniejszych uprawnień jest egzekwowana dla każdego kroku**: zanim żądanie wyjdzie, klient, który
  dostaje krok, jest ograniczony do tego, czego krok potrzebuje, i do dozwolonych projektów Jiry połączenia. Krok
  „Fetch issue” nie może skomentować, nawet przez połączenie, które by mogło.
- **Zapisy czekają na człowieka** ([zatwierdzenie](#jira-write-approval)): Back Office zna dokładne żądanie,
  pokazuje je, zapisuje jego odcisk i po zatwierdzeniu wysyła dokładnie to.
- **Nic dodatkowego do uruchamiania i chronienia**: żadnego dodatkowego procesu ani poświadczenia; tokeny są
  zaszyfrowane w bazie i odszyfrowywane na serwerze tylko na czas jednego wywołania.
- **Da się to przetestować**: każda reguła (które połączenie, która tożsamość, jaka kontrola) jest pokryta
  testami automatycznymi.

**Kiedy MCP miałoby sens.** Gdyby agent AI miał samodzielnie przeglądać Jirę (szukać powiązanych zgłoszeń
w trakcie implementacji), byłoby to narzędzie dla modelu, a nie krok workflowu. Bezpieczny sposób to wąskie
narzędzie, które prosi Back Office o wykonanie wyszukiwania: obowiązują te same reguły połączeń, ograniczenia
możliwości, dozwolone projekty, audyt i, dla zapisów, to samo zatwierdzenie, a wynik trafia do modelu jako dane
niezaufane. Back Office nigdy nie przekazuje agentowi poświadczeń Atlassian MCP ani tokenu Jiry: zmanipulowany
agent (prompt injection) mógłby go użyć do wszystkiego, na co pozwala token, niewidocznie i bez zatwierdzenia.

### Co wywołuje Back Office

Back Office nigdy nie odbiera webhooków z Jiry. Jira jest wywoływana, gdy wykonuje się węzeł workflowu
jednego z trzech typów zadań Jiry, gdy monitor zgłoszeń projektu odpytuje Jirę albo gdy połączenie jest
zapisywane lub testowane:

| Co się wykonuje | Wywołanie Jiry | Wymagana możliwość połączenia | Uprawnienia projektu Jira, które za nią stoją |
|---|---|---|---|
| Zadanie **Fetch issue** (executor `FETCH_ISSUE`) | `GET /rest/api/3/issue/{key}` z polami summary, description, type, priority, status, labels, components oraz polem kryteriów akceptacji | **Odczyt zgłoszeń** (**Read issues**, `ISSUE_READ`) | **Browse Projects** |
| Zadanie **Notify Jira** (executor `NOTIFY`) | `POST /rest/api/3/issue/{key}/comment`, po zatwierdzeniu przez człowieka ([zatwierdzenie](#jira-write-approval)) | **Komentowanie zgłoszeń** (**Comment on issues**, `ISSUE_COMMENT`) | **Browse Projects**, **Add Comments** |
| Zadanie **Transition issue** (executor `TRANSITION_ISSUE`) | `GET`, a następnie `POST /rest/api/3/issue/{key}/transitions`, po zatwierdzeniu przez człowieka | **Przenoszenie zgłoszeń** (**Move issues**, `ISSUE_TRANSITION`) i **Odczyt zgłoszeń** | **Browse Projects**, **Transition Issues** |
| **Monitor zgłoszeń Jira** (**Jira intake watcher**) projektu ([monitor](#jira-intake)) | `GET /rest/api/3/search/jql` z polami status, updated, created i historią zmian (changelog) | **Odczyt zgłoszeń** (`ISSUE_READ`) | **Browse Projects** |
| Zapis połączenia, **Testuj** (**Test**), **Testuj przed zapisem** (**Test before saving**) | `GET /rest/api/3/myself`, `GET /rest/api/3/mypermissions` | żadna | żadne (wystarczy dowolne ważne konto) |

Wszystkie wywołania używają uwierzytelniania HTTP Basic z e-mailem konta i tokenem API połączenia. Token
klasyczny jest wysyłany do URL-a witryny (`https://acme.atlassian.net`), token z zakresami do bramy API Atlassian
`https://api.atlassian.com/ex/jira/<identyfikator chmury>` ([tokeny z zakresami](#jira-scoped-token)). Linki do
zgłoszeń zawsze używają URL-a witryny (kod: `projects-adapters/.../adapter/out/jira/JiraIssueTracker.kt`). Klucz zgłoszenia
uruchomienia pochodzi z danych wejściowych (**Rozpocznij uruchomienie** (**Start run**) z kluczem
zgłoszenia, narzędzie MCP `start_workflow`, zmienna CI) albo od monitora zgłoszeń.

**Ograniczenia**

- Tylko Jira Cloud. Osobiste tokeny dostępu Jira Data Center / Server (Bearer) **nie są obsługiwane**,
  a Data Center nie udostępnia REST API v3 w sposób, jakiego oczekuje adapter.
- Tokeny API Atlassian: **z zakresami** (zalecane) albo **klasyczne** ([który wybrać](#jira-token)). Aplikacje
  OAuth nie są obsługiwane.
- Pole **Filtr JQL** (**JQL filter**) w formularzu projektu zawęża to, co widzi monitor zgłoszeń. Nie
  ogranicza uruchomień rozpoczynanych ręcznie, z CI ani przez MCP.
- Komentarze i przeniesienia są wykonywane jako konto połączenia, którego używa krok. Krok ustawiony na
  działanie jako osoba, która go zatwierdza lub rozpoczyna, zapisuje jako ta osoba, przez jej własne połączenie
  osobiste, i dopiero po jej zatwierdzeniu ([Działaj jako](#jira-act-as)).

<a id="jira-least-privilege"></a>
## Zasada najmniejszych uprawnień: konto do odczytu i konto do zapisu

**Nie można utworzyć dedykowanych kont?** Wiele firm nie pozwala na dodatkowe konta Jiry. Wtedy użyj własnego
konta z **tokenami API z zakresami** ([kroki](#jira-scoped-token)): token z zakresami może tylko to, na co
pozwalają jego zakresy, niezależnie od tego, co może Twoje konto, więc token do odczytu z zakresami odczytu nie
zapisze, choć Ty możesz. Utwórz jeden token z zakresami do odczytu i jeden do zapisu, dodaj je jako dwa
połączenia, a połączenie do odczytu wybierz jako [połączenie systemowe](#jira-system-connection) projektu.
Pozostała część tej sekcji opisuje klasyczną konfigurację z dedykowanymi kontami.

Klasyczny token API Jiry dziedziczy **wszystkie** uprawnienia swojego konta; Atlassian nie pozwala zawęzić
klasycznego tokenu. Jeśli jedno konto odczytuje, komentuje i zmienia statusy we wszystkich projektach, to
krok, który tylko odczytuje zgłoszenie, ma prawo zapisu wszędzie, a wyciek tokenu, błąd albo wynik agenta
sterowany przez prompt injection może dotknąć każdego zgłoszenia widocznego dla konta. **Zasada
najmniejszych uprawnień** oznacza więc **konta z najmniejszymi uprawnieniami**: każde konto Jiry dostaje
tylko uprawnienia potrzebne do swojej pracy, a Back Office używa najsłabszego konta, które wystarcza.

Back Office pomaga na trzy sposoby:

- każde połączenie deklaruje **możliwości**, a krok dostaje tylko połączenie, którego możliwości pokrywają
  jego potrzeby, i to z najmniejszą liczbą dodatkowych możliwości ([jak kroki wybierają](#jira-tasks));
- klient Jiry przekazany krokowi jest ograniczony do możliwości tego kroku i do **Dozwolonych projektów**
  (**Allowed projects**) połączenia: krok **Fetch issue** nie doda komentarza, nawet jeśli działa przez
  połączenie do zapisu;
- zapis lub test połączenia pyta Jirę, jakie uprawnienia konto naprawdę ma, i pokazuje uprawnienia
  **brakujące** oraz **nadmiarowe** ([Testuj](#jira-test)).

### Zalecana konfiguracja

| Konto Jiry | Uprawnienia w projekcie Jira (przez rolę projektową) | Połączenie w Back Office | Możliwości |
|---|---|---|---|
| `backoffice-jira-reader@example.com` | tylko **Browse Projects** | `Jira reader` | **Odczyt zgłoszeń** |
| `backoffice-jira-writer@example.com` | **Browse Projects**, **Add Comments**, **Transition Issues** | `Jira writer` | **Komentowanie zgłoszeń**, **Przenoszenie zgłoszeń**, **Odczyt zgłoszeń** |

- Użyj **osobnych kont** do odczytu i do zapisu. Możliwości połączenia ograniczają to, co Back Office robi
  z tokenem; tylko uprawnienia konta w Jirze ograniczają to, co można zrobić tokenem po wycieku.
- Nadaj uprawnienia przez rolę projektową, która ma **dokładnie** te uprawnienia, i tylko w projektach Jiry,
  do których połączenie jest przeznaczone. Nie używaj ról, które mogą edytować, usuwać, przypisywać lub
  administrować.
- Nigdy nie używaj konta osoby ani administratora.
- Jeśli komentarze i zmiany statusu mają pochodzić z różnych kont, podziel połączenie do zapisu na dwa
  (**Komentowanie zgłoszeń** + **Odczyt zgłoszeń**, **Przenoszenie zgłoszeń** + **Odczyt zgłoszeń**).

W tej konfiguracji workflow **Fetch issue → … → Notify Jira → Transition issue** używa konta do odczytu
w pierwszym kroku, a konta do zapisu w dwóch ostatnich, i projektant nie pokazuje ostrzeżeń. Połączenie do
zapisu ma też **Odczyt zgłoszeń**, bo **Transition issue** najpierw odczytuje dostępne przejścia; bez tego
krok zmiany statusu nie znalazłby połączenia, które pokrywa obie możliwości. [Monitor zgłoszeń](#jira-intake)
również używa połączenia do odczytu i bez niego odmawia odpytywania Jiry.

<a id="jira-token"></a>
## Utwórz tokeny API

Atlassian oferuje dwa rodzaje tokenów API. Back Office obsługuje oba:

| | Token API z zakresami (zalecany) | Klasyczny token API |
|---|---|---|
| Tworzony w | id.atlassian.com → Security → API tokens → **Create API token with scopes** | ta sama strona → **Create API token** |
| Może | tylko to, na co pozwalają jego zakresy | wszystko, co może jego konto |
| Najmniejsze uprawnienia | przez zakresy, także na własnym koncie | tylko przez dedykowane konto z niewieloma uprawnieniami w Jirze |
| Back Office wywołuje | `https://api.atlassian.com/ex/jira/<identyfikator chmury>` | URL witryny |
| Przy zapisie | identyfikator chmury jest znajdowany automatycznie; zakresy są sprawdzane | potwierdzasz „ten token może wszystko, co może jego konto” |

<a id="jira-scoped-token"></a>
### Token API z zakresami (zalecany)

1. Zaloguj się do Atlassian kontem, jako które ma działać Back Office. Gdy nie można utworzyć dedykowanego konta,
   Twoje własne konto wystarczy: zakresy ograniczają token.
2. Otwórz https://id.atlassian.com/manage-profile/security/api-tokens (**Account settings** → **Security** →
   **API tokens**) i wybierz **Create API token with scopes**.
3. Nadaj nazwę, na przykład `back-office-reader`, i datę wygaśnięcia (od 1 do 365 dni).
4. Wybierz aplikację **Jira**.
5. Zaznacz dokładnie zakresy potrzebne do tego, co ma robić połączenie. Okno dodawania w Back Office je wymienia
   i wypełnia przyciskiem **Użyj zalecanych zakresów** (**Use the recommended scopes**):

   | Połączenie może | Szczegółowe zakresy do zaznaczenia (zalecane) | Albo zakresy klasyczne |
   |---|---|---|
   | (zawsze, by rozpoznać konto) | `read:user:jira` | `read:jira-user` |
   | **Odczyt zgłoszeń** | `read:issue:jira`, `read:issue-details:jira`, `read:jql:jira`, `read:issue-meta:jira`, `read:field-configuration:jira`, `read:status:jira`, `read:issue.changelog:jira`, `read:field:jira`, `read:avatar:jira` | `read:jira-work` |
   | **Komentowanie zgłoszeń** | `write:comment:jira`, `read:comment:jira`, `read:comment.property:jira`, `read:project:jira`, `read:project-role:jira`, `read:group:jira`, `read:avatar:jira` | `read:jira-work`, `write:jira-work` |
   | **Przenoszenie zgłoszeń** | `write:issue:jira`, `write:issue.property:jira`, `read:issue.transition:jira`, `read:status:jira`, `read:field-configuration:jira` | `read:jira-work`, `write:jira-work` |

   Wybieraj zakresy szczegółowe: `write:jira-work` pozwala tokenowi także tworzyć, edytować i usuwać zgłoszenia,
   o czym ostrzega test. Przenoszenie zgłoszeń wymaga `write:issue:jira`, który pozwala też edytować zgłoszenia;
   Back Office i tak tylko je przenosi, po zatwierdzeniu przez człowieka. Atlassian czasem przegrupowuje zakresy:
   jeśli **Testuj** zgłosi brakujące zakresy dla jakiegoś wywołania, dodaj zakres podany w odpowiedzi Jiry.
6. Utwórz token i skopiuj go (jest pokazywany raz). Zanotuj datę wygaśnięcia.
7. W Back Office dodaj połączenie z **Rodzaj tokenu API** = **Token API z zakresami**, wklej token i podaj te same
   zakresy. **Identyfikator chmury** zostaw pusty: Back Office odczyta go z
   `https://<twoja-witryna>.atlassian.net/_edge/tenant_info` przy zapisie (wpisz go ręcznie tylko wtedy, gdy Twoja
   sieć blokuje ten adres).

Dokumentacja: https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/
oraz lista zakresów https://developer.atlassian.com/cloud/jira/platform/scopes-for-oauth-2-3LO-and-forge-apps/

### Klasyczny token API z dedykowanymi kontami

Powtórz dla każdego konta (do odczytu, do zapisu).

1. **Utwórz konto.** W Atlassian Administration (admin.atlassian.com) → **Directory** → **Users** →
   **Invite user** wpisz e-mail konta (na przykład `backoffice-jira-reader@example.com`) i nadaj mu dostęp
   do produktu **Jira**. Konto potrzebuje miejsca w licencji Jiry; konto bez dostępu do produktu dostaje
   `401`/`403` przy każdym wywołaniu.
   Dokumentacja: https://support.atlassian.com/user-management/docs/invite-users/
2. **Utwórz rolę projektową tylko z potrzebnymi uprawnieniami** (projekty company-managed; administrator
   Jiry robi to raz). **Settings** (koło zębate) → **System** → **Project roles**: dodaj role *Back Office
   reader* i *Back Office writer*. W schemacie uprawnień projektów Jiry (**Settings** → **Issues** →
   **Permission schemes** → **Permissions**) nadaj **Browse Projects** obu rolom, a **Add Comments**
   i **Transition Issues** tylko roli do zapisu. Sprawdź, czy konta nie dostają więcej przez grupy (na
   przykład *jira-software-users*), którym schemat nadaje uprawnienia.
   Dokumentacja: https://support.atlassian.com/jira-cloud-administration/docs/manage-project-permissions/
3. **Dodaj konto do roli** w każdym projekcie Jiry, w którym ma pracować: **Project settings** →
   **People** → **Add people**, wybierz rolę. Projekty team-managed mają mniej precyzyjne role: dla konta
   do odczytu wystarczy *Viewer*, dla konta do zapisu *Member* (Member może też tworzyć i edytować
   zgłoszenia, co **Testuj** pokaże jako uprawnienia nadmiarowe). Jeśli zgłoszenia mają poziomy
   bezpieczeństwa (issue security levels), konto musi mieć też prawo widzieć te poziomy.
4. **Utwórz klasyczny token API.** Zaloguj się do Atlassian **jako to konto**, otwórz
   https://id.atlassian.com/manage-profile/security/api-tokens (**Account settings** → **Security** →
   **Create and manage API tokens**), wybierz **Create API token** (nie **Create API token with scopes**),
   nadaj mu nazwę, np. `back-office-prod-reader`, wybierz datę wygaśnięcia (Atlassian ogranicza ważność
   tokenów API do roku) i skopiuj token. Jest wyświetlany tylko raz.
   Dokumentacja: https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/
5. **Zachowaj datę wygaśnięcia**: wpiszesz ją w połączeniu, a Back Office przypomni o niej przed terminem.

**Token klasyczny ma wszystkie uprawnienia swojego konta**, dlatego samo konto musi mieć tylko uprawnienia
opisane wyżej. Zapis tokenu klasycznego wymaga potwierdzenia dokładnie tego („Rozumiem: ten klasyczny token może
w Jirze wszystko, co może jego konto”); połączenia zapisane, zanim pojawiło się to pytanie, liczą się jako
potwierdzone. Przy własnym koncie wybierz [token z zakresami](#jira-scoped-token).

<a id="jira-connections"></a>
## Połączenia

**Połączenia** (**Connections**) przechowują poświadczenia Jiry. Są trzy zakresy:

| Zakres | Gdzie | Kto może zmieniać | Kto widzi listę | Kto używa |
|---|---|---|---|---|
| Platforma | **Administracja** → **Połączenia** (**Administration** → **Connections**; także w palecie poleceń) | `admin.connections.manage` (rola `platform-admin`) | `integrations.view` (każdy członek) | projekty, które je wybiorą ([połączenie systemowe](#jira-system-connection)), kroki, które je wskażą |
| Projekt | strona projektu → karta **Połączenia** (**Connections**) | `project.connections.manage` (tylko rola **Właściciel**, nie **Maintainer**) | `project.view` (każdy członek projektu) | tylko ten projekt |
| Osobiste | **Moje konto** → **Połączenia** | właściciel (`connections.personal`, każdy członek) | tylko właściciel; administratorzy widzą, że istnieje | tylko przy działaniu samego właściciela ([połączenia osobiste](#jira-personal-connections)) |

O tym, którego połączenia używa krok, decydują [połączenie systemowe](#jira-system-connection) projektu
i ustawienie [Działaj jako](#jira-act-as) kroku. Usunięcie projektu usuwa jego połączenia; usunięcie połączenia
usuwa je z połączenia systemowego i z listy każdego projektu. Każda zmiana trafia do dziennika audytu (`CONNECTION_CREATE`, `CONNECTION_UPDATE`,
`CONNECTION_ROTATE`, `CONNECTION_DELETE`, `CONNECTION_VERIFY`, `CONNECTION_TEST`); token nigdy nie pojawia
się w dzienniku audytu, API, interfejsie ani logach.

### Dodaj połączenie

1. Otwórz **Administracja** → **Połączenia** (platforma) albo projekt → **Połączenia** (projekt), a następnie
   **Dodaj połączenie** (**Add connection**).
2. **Typ** (**Type**): **Jira**.
3. **Nazwa** (**Name**): to, co ludzie widzą w projektancie i w logu uruchomienia, na przykład `Jira reader`.
4. **URL witryny** (**Site URL**): `https://<twoja-witryna>.atlassian.net` (tylko https).
5. **E-mail konta** (**Account e-mail**): e-mail konta Jiry, do którego należy token.
6. **Rodzaj tokenu API** (**Kind of API token**): **Token API z zakresami (zalecany)** albo **Klasyczny token
   API** ([który wybrać](#jira-token)).
7. **Token API** (**API token**): token. Pole jest tylko do zapisu: po zapisaniu formularz pokazuje jedynie, że
   token jest ustawiony.
   - Z zakresami: **Zakresy tokenu** (zakresy zaznaczone przy tworzeniu; **Użyj zalecanych zakresów** wypełnia je
     dla wybranych możliwości; okno od razu ostrzega, gdy zakresy nie pokrywają możliwości lub zawierają
     niepotrzebne zakresy zapisu) i opcjonalnie **Identyfikator chmury witryny** (pusty: znajdowany automatycznie
     przy zapisie).
   - Klasyczny: zaznacz **Rozumiem: ten klasyczny token może w Jirze wszystko, co może jego konto**. Bez tego
     zapis nie jest możliwy.
8. **Możliwości** (**Capabilities**): zaznacz, do czego wolno użyć połączenia. Okno wyjaśnia każdą
   możliwość i pokazuje instrukcję krok po kroku dla strony Jiry, dopasowaną do Twojego wyboru.

   | Możliwość | Pozwala Back Office | Uprawnienia Jiry potrzebne kontu |
   |---|---|---|
   | **Odczyt zgłoszeń** (**Read issues**, `ISSUE_READ`) | wczytywać zgłoszenia (**Fetch issue**, **Transition issue**) | Browse Projects |
   | **Komentowanie zgłoszeń** (**Comment on issues**, `ISSUE_COMMENT`) | dodawać komentarze (**Notify Jira**) | Browse Projects, Add Comments |
   | **Przenoszenie zgłoszeń** (**Move issues**, `ISSUE_TRANSITION`) | zmieniać status zgłoszeń (**Transition issue**) | Browse Projects, Transition Issues |

   **Komentowanie zgłoszeń** i **Przenoszenie zgłoszeń** to możliwości zapisu. Zaznacz tylko to, do czego
   konto jest przeznaczone.
9. **Dozwolone projekty** (**Allowed projects**) (opcjonalnie): klucze projektów Jiry, których połączenie
   może dotykać, na przykład `SHOP`, `WEB`. Puste pole oznacza dowolny projekt widoczny dla konta. Gdy są
   ustawione, połączenie nie zostanie wybrane dla zgłoszenia z innego projektu Jiry, a krok, który mimo to
   spróbuje, zostanie odrzucony.
10. **Klucz projektu do testu** (**Project key for the test**) (tylko gdy **Dozwolone projekty** są puste):
   przykładowy klucz projektu Jiry używany do sprawdzenia uprawnień.
11. **Data wygaśnięcia tokenu** (**Token expiry date**): data wygaśnięcia wybrana w Atlassian. Back Office
    przypomni o niej 14 dni wcześniej ([Wygaśnięcie tokenu](#jira-token-expiry)).
12. **Testuj przed zapisem** (**Test before saving**; zalecane): Back Office sprawdza wersję roboczą
    w Jirze i pokazuje konto oraz uprawnienia przyznane, brakujące i nadmiarowe, niczego nie zapisując
    ([Testuj](#jira-test)). Poprawiaj konto lub możliwości, aż nie będzie brakujących ani nadmiarowych
    uprawnień.
13. **Zapisz** (**Save**). Back Office od razu testuje połączenie ponownie. Nieudany test nie blokuje
    zapisu, ale połączenie z błędem zostanie wybrane tylko wtedy, gdy nic innego się nie nadaje.

**Dodaj połączenie** odpowiada „BACKOFFICE_SECRETS_KEY is not configured”, gdy operator nie ustawił klucza
szyfrowania ([Skonfiguruj Back Office](#jira-config)).

<a id="jira-system-connection"></a>
### Połączenie systemowe i lista połączeń projektu

Na karcie **Połączenia** projektu sekcja **Połączenia Jira tego projektu** (zmieniają ją właściciele; widzi ją
każdy z dostępem do projektu):

- **Połączenie systemowe**: połączenie, którego używa każdy krok Jiry projektu, chyba że krok wybierze inaczej.
  Wybierasz je spośród własnych połączeń projektu i połączeń platformy. Nadal jest sprawdzane dla każdego kroku:
  krok, który potrzebuje możliwości, której połączenie systemowe nie ma (na przykład **Przenoszenie zgłoszeń**,
  gdy połączenie systemowe tylko czyta), albo zgłoszenie spoza jego **Dozwolonych projektów**, bierze zamiast
  niego połączenie z najmniejszymi uprawnieniami z listy poniżej; projektant i log uruchomienia to pokazują.
- **Kolejne połączenia dla kroków tego projektu**: uporządkowana lista innych połączeń projektu lub platformy,
  których mogą używać kroki projektu, na przykład połączenie do zapisu obok systemowego połączenia do odczytu.
  Dodawaj, zmieniaj kolejność lub usuwaj, a potem **Zapisz połączenia Jira**.
- **Brak** połączenia systemowego: kroki wybierają spośród wszystkich połączeń projektu i platformy to
  z najmniejszą liczbą dodatkowych możliwości (dotychczasowa reguła; poświadczenie ze zmiennych środowiskowych na
  końcu).

Połączenia osobiste nigdy się tu nie pojawiają. Zmiana trafia do audytu jako `PROJECT_JIRA_CONNECTIONS_UPDATE`.

**Po aktualizacji** każdy projekt, który już używał Jiry, dostał połączenie systemowe automatycznie: spośród jego
własnych połączeń i połączeń platformy, które mogą dotykać jego projektu Jiry, to, które może czytać i ma
najmniej możliwości (dalej: projekt przed platformą, wynik ostatniego testu, nazwa); każde inne z tych połączeń
trafiło na listę kolejnych połączeń, w tej samej kolejności. Dzięki temu kroki używają tych samych połączeń co
wcześniej. Projekty bez Jiry oraz projekty, których jedynym poświadczeniem jest to ze zmiennych środowiskowych, nie
mają połączenia systemowego i zachowują dotychczasową regułę.

<a id="jira-personal-connections"></a>
### Połączenia osobiste

**Połączenie osobiste** to połączenie z Jirą, które utrzymujesz dla siebie w **Moje konto** → **Połączenia**.
Pozwala krokom workflowu komentować i przenosić zgłoszenia **jako Ty** w Jirze, zamiast jako konto wspólne.

- **Tylko przy Twoim działaniu.** Jest używane tylko wtedy, gdy krok ustawiono na działanie jako osoba, która go
  zatwierdza lub rozpoczyna ([Działaj jako](#jira-act-as)), i tylko przy **Twoim** zatwierdzeniu zapisu albo
  w uruchomieniu, które **Ty** rozpocząłeś, albo do odczytu zgłoszeń Twoich własnych reguł w [Mojej pracy](my-work.md#rules)
  (tylko odczyt, ADR 0038). Nigdy nie używa go monitor zgłoszeń projektu, CI ani uruchomienie kogoś innego,
  chyba że to Ty zatwierdzasz ten zapis.
- **Nikt inny go nie widzi ani nie używa.** Inni użytkownicy nie mogą go odczytać, przetestować ani zmienić
  (dostają „nie znaleziono”). Administratorzy widzą w **Administracja** → **Połączenia** → **Połączenia
  osobiste**, że istnieje (nazwa, witryna, możliwości, rodzaj tokenu, właściciel, wygaśnięcie, ostatni test),
  nigdy e-maila ani tokenu, i nie mogą go używać.
- **Użyj tokenu z zakresami** ([kroki](#jira-scoped-token)): to Twoje własne konto, więc to zakresy ograniczają
  token do „komentowania” albo „odczytu”.
- Przypomnienia o wygaśnięciu trafiają do Ciebie (karta w **Twoja kolej**, powiadomienie na pulpicie).
- Dziennik audytu zapisuje zapisy przez nie jako „via personal connection of <Twoje imię i nazwisko>”.

<a id="jira-token-expiry"></a>
### Wygaśnięcie i rotacja tokenu

Lista oznacza token jako **wkrótce wygaśnie** (**expiring soon**) 30 dni przed datą wygaśnięcia i jako
**wygasł** (**expired**) po niej. Dodatkowo Back Office przypomina osobom, które mogą zmienić połączenie,
gdy token wygasa w ciągu **14 dni** lub już wygasł:

- karta w **Twoja kolej**: „Token Jiry połączenia <nazwa> wygasa za 5 dni” albo „Token Jiry połączenia <nazwa> wygasł”,
  z kontem i datą, prowadząca do połączenia; widać ją, dopóki token nie zostanie zastąpiony
  ([Twoja kolej](your-turn.md#write-confirmations)). Połączenia platformy widzą posiadacze
  `admin.connections.manage`, połączenia projektu posiadacze `project.connections.manage` (właściciele
  projektu);
- raz na każdą datę wygaśnięcia codzienne sprawdzenie (domyślnie o 07:00 UTC) wysyła powiadomienie na
  pulpicie typu **Wygasające połączenie** (**Connection expiring**), domyślnie włączone dla
  administratorów i wyłączone dla pozostałych ([Powiadomienia na pulpicie](account.md#notifications)),
  oraz zapisuje w dzienniku audytu wpis `CONNECTION_EXPIRY_REMINDER`.

Bez **Daty wygaśnięcia tokenu** w połączeniu nie ma przypomnień. Rotuj token przed tą datą, a natychmiast,
jeśli wycieknie:

1. Zaloguj się do Atlassian jako konto połączenia i utwórz nowy token klasyczny ([kroki](#jira-token)).
2. W Back Office otwórz połączenie, wybierz **Zastąp token** (**Replace token**), wklej nowy token, ustaw
   nową **Datę wygaśnięcia tokenu** i zapisz. Połączenie jest testowane ponownie; następny krok Jiry używa
   już nowego tokenu. Restart nie jest potrzebny.
3. Unieważnij stary token na stronie **API tokens** konta.

Edycja pozostałych pól zachowuje zapisany token.

<a id="jira-config"></a>
## Skonfiguruj Back Office

Połączenia są przechowywane w bazie danych; ich tokeny są szyfrowane kluczem, który dostarcza operator.

| Ustawienie | Zmienna środowiskowa | Wartość Helm (chart `back-office`) | Sekret |
|---|---|---|---|
| Klucz szyfrowania sekretów połączeń (base64 z 32 bajtów) | `BACKOFFICE_SECRETS_KEY` | `secrets.connectionsKey` (generowany i zachowywany przy aktualizacjach, gdy jest pusty, a `secrets.create=true`) lub klucz `BACKOFFICE_SECRETS_KEY` w `secrets.existingSecret` | tak |
| Identyfikator tego klucza | `BACKOFFICE_SECRETS_KEY_ID` | `secrets.connectionsKeyId` (domyślnie `k1`) | nie |
| Pole niestandardowe kryteriów akceptacji, na przykład `customfield_10042` | `JIRA_ACCEPTANCE_CRITERIA_FIELD` | `integrations.jira.acceptanceCriteriaField` | nie |

Klucz wygenerujesz poleceniem `openssl rand -base64 32` (PowerShell 7:
`$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Fill($b); [Convert]::ToBase64String($b)`).
Aby go zrotować, ustaw nowy klucz z nowym `BACKOFFICE_SECRETS_KEY_ID`, a stary przenieś do
`BACKOFFICE_SECRETS_PREVIOUS_KEYS` (`id:base64`); worker przy starcie ponownie szyfruje klucze danych, po
czym stary klucz można usunąć. Szczegóły dla operatorów: `docs/guides/configuration.md`, sekcja
„Connection secrets”. **Zrób kopię zapasową
klucza**: jeśli go stracisz, trzeba będzie ponownie wpisać token każdego połączenia. Gdy istnieją połączenia,
a klucza brakuje, API i worker nie startują i wyświetlają komunikat wskazujący `BACKOFFICE_SECRETS_KEY`.
Po utracie klucza uruchom platformę raz z nowym kluczem i `BACKOFFICE_SECRETS_ALLOW_UNREADABLE=true`, użyj
**Zastąp token** (**Replace token**) przy każdym połączeniu, a potem usuń ten przełącznik.

Pole kryteriów akceptacji jest globalne: dotyczy wszystkich połączeń.

### Znajdź identyfikator pola kryteriów akceptacji

Jeśli Twoje zgłoszenia przechowują kryteria akceptacji w polu niestandardowym, Back Office może udostępnić je
promptom jako `issue.acceptanceCriteria`.

1. Jako administrator Jiry otwórz **Settings** (koło zębate) → **Issues** → **Custom fields**, znajdź pole,
   otwórz **⋯** → **Edit details** (lub **Contexts and default value**). URL w przeglądarce zawiera `id=10042`.
2. Wartość dla Back Office to `customfield_` plus ta liczba: `customfield_10042`.
3. Alternatywa bez uprawnień administratora: `GET https://acme.atlassian.net/rest/api/3/field`
   z e-mailem i tokenem połączenia zwraca listę wszystkich pól z ich `id` i `name`.

### Włącz Jirę w projekcie

1. Otwórz projekt → zakładka **Ustawienia** (**Settings**) (wymaga `project.manage`) i przewiń do
   **System zgłoszeń (opcjonalnie)** (**Issue tracker (optional)**).
2. Przełącz przełącznik na **Jira**.
3. **Klucz projektu Jira** (**Jira project key**): klucz projektu w Jirze (na przykład `SHOP`); puste pole
   oznacza „taki sam jak klucz projektu Back Office”. Projektant używa go, aby sprawdzić, które połączenie
   dostałby węzeł Jiry (względem **Dozwolonych projektów**); w czasie uruchomienia liczy się klucz
   rzeczywistego zgłoszenia.
4. **Filtr JQL** (**JQL filter**) (opcjonalnie): zawęża zgłoszenia, na które reaguje
   [monitor zgłoszeń Jira](#jira-intake).
5. **Zapisz zmiany** (**Save changes**). Zmiana trafia do dziennika audytu.
6. Opcjonalnie włącz na tej samej karcie **Monitor zgłoszeń Jira** (osobna karta obok formularza; [Monitor zgłoszeń Jira](#jira-intake)).

Zobacz [projects.md](projects.md#create-project) i [Połączenia projektu](projects.md#project-connections).

### Poświadczenie ze zmiennych środowiskowych (starsza konfiguracja)

Przed wprowadzeniem połączeń jedno konto Jiry konfigurowano zmiennymi środowiskowymi:

| Zmienna środowiskowa | Wartość Helm |
|---|---|
| `JIRA_URL` | `integrations.jira.url` |
| `JIRA_EMAIL` | `integrations.jira.email` |
| `JIRA_API_TOKEN` | `secrets.integrations.jiraApiToken` lub klucz `JIRA_API_TOKEN` w `secrets.integrations.existingSecret` |

Nadal działają. Gdy wszystkie trzy są ustawione, lista platformy pokazuje połączenie tylko do odczytu
**Jira (environment variables)** oznaczone jako poświadczenie ze zmiennych środowiskowych, ze **wszystkimi
trzema możliwościami**. Nie można go edytować w interfejsie (zmień zmienne i zrestartuj pody), wynik jego
testu jest pokazywany, ale nie zapisywany, i jest wybierane zawsze **na końcu**, po każdym nadającym się
połączeniu projektu i platformy. Ponieważ może wszystko, każdy węzeł Jiry, który go używa, pokazuje
ostrzeżenie „ma więcej uprawnień niż potrzeba”. **Ustawienia** (**Settings**) oraz **Administracja** →
**Połączenia** pokazują informację o migracji do połączeń z ograniczonym zakresem.

**Migracja do połączeń z ograniczonym zakresem**

1. Utwórz konta Jiry do odczytu i do zapisu oraz ich tokeny ([kroki](#jira-token)).
2. Dodaj dwa połączenia, jako połączenia platformy albo w projektach ([Dodaj połączenie](#jira-connections)),
   i **Testuj** każde, aż żadne nie pokazuje brakujących uprawnień.
3. Otwórz w projektancie każdy workflow z zadaniami Jiry i zwaliduj go: każdy węzeł Jiry powinien pokazywać
   połączenie inne niż **Jira (environment variables)** i żadnego błędu.
4. Usuń `JIRA_API_TOKEN` (oraz `JIRA_URL`, `JIRA_EMAIL`) z Secretu lub `deploy/compose/.env` i zrestartuj
   API i workera (`helm upgrade` robi to sam, gdy Secret należy do chartu). Zostaw
   `JIRA_ACCEPTANCE_CRITERIA_FIELD`.
5. Unieważnij stary token w Atlassian.

<a id="jira-test"></a>
## Przetestuj połączenie

Naciśnij **Testuj** (**Test**) przy połączeniu (wymaga prawa do jego zmiany). Back Office testuje też
automatycznie, gdy połączenie jest tworzone, edytowane lub gdy token zostaje zastąpiony.

**Tokeny z zakresami** są testowane przez bramę API i inaczej: Back Office porównuje podane zakresy
z możliwościami (**brakujące zakresy** to błąd, **zakresy zapisu, których nie potrzebuje żadna możliwość**,
i `write:jira-work` to ostrzeżenia), a potem wywołuje wywołania odczytu potrzebne każdej możliwości w pierwszym
sprawdzanym projekcie: wyszukanie jednego zgłoszenia (**Odczyt zgłoszeń**), komentarze tego zgłoszenia
(**Komentowanie zgłoszeń**) i jego przejścia (**Przenoszenie zgłoszeń**). Odpowiedź Jiry `401`/`403` oznacza, że
tokenowi brakuje zakresów tego wywołania. Test nigdy niczego nie zapisuje. To, co może samo Twoje konto ponad
możliwości, jest pokazywane tylko informacyjnie: token ograniczają zakresy.

**Testuj przed zapisem** (**Test before saving**) w oknach **Dodaj połączenie** i edycji wykonuje ten sam
test na tym, co wpisano, zanim cokolwiek zostanie zapisane: dla nowego połączenia albo zmienionych pól
istniejącego (bez nowego tokenu używany jest token zapisany). Token jest przechowywany tylko w pamięci na
czas testu; nic nie trafia do bazy danych, a lista połączeń się nie zmienia. Test jest zapisywany
w dzienniku audytu jako `CONNECTION_TEST`, nigdy z tokenem. Użyj go, aby sprawdzić uprawnienia nowego
konta, zanim jakikolwiek krok będzie mógł wybrać to połączenie.

Test:

1. wywołuje `GET /rest/api/3/myself`, aby sprawdzić e-mail i token, i pokazuje wyświetlaną nazwę konta;
2. dla każdego z **Dozwolonych projektów** (a gdy ich nie ma, dla **Klucza projektu do testu**) pyta Jirę,
   które z tych uprawnień ma konto: Browse Projects, Add Comments, Transition Issues, Edit Issues, Delete
   Issues, Assign Issues, Create Issues, Administer Projects, Manage Watchers;
3. porównuje odpowiedź z możliwościami i pokazuje etykiety dla każdego projektu Jiry: **przyznane**
   (**granted**), **brakujące** (**missing**) i **nadmiarowe** (**excess**).

| Wynik | Znaczenie | Co zrobić |
|---|---|---|
| **OK** | konto ma dokładnie to, czego potrzebują możliwości | nic |
| Uprawnienia **brakujące** (status: błąd) | możliwość nie może działać, na przykład **Komentowanie zgłoszeń** bez Add Comments; kroki, które jej potrzebują, zakończą się błędem | dodaj uprawnienie do roli projektowej konta w tym projekcie Jiry albo odznacz możliwość |
| Uprawnienia **nadmiarowe** (status: ostrzeżenie, „ma więcej uprawnień niż potrzeba”) | konto może więcej, niż potrzebują możliwości, na przykład „to konto może też usuwać zgłoszenia / administrować projektem” | użyj dedykowanego konta tylko z wymienionymi uprawnieniami; do tego czasu połączenie działa |
| Brak projektu do sprawdzenia (ostrzeżenie) | brak **Dozwolonych projektów** i **Klucza projektu do testu** | wpisz jedno z nich, inaczej uprawnień nie da się sprawdzić |

| Komunikat | Znaczenie | Rozwiązanie |
|---|---|---|
| Uwierzytelnianie nie powiodło się (`401`) | błędna para e-mail/token, token wygasł lub został unieważniony | **Zastąp token** |
| Witryna nieosiągalna | DNS, proxy lub zapora między Back Office a witryną albo błędny URL witryny | sprawdź URL; zezwól na ruch wychodzący z podów API i workera do `*.atlassian.net` (oraz `api.atlassian.com` dla tokenów z zakresami) |
| Nie znaleziono projektu (`404`) | klucz nie istnieje lub konto nie może przeglądać projektu | popraw klucz; dodaj konto do roli projektowej |
| `HTTP 403` | konto nie ma dostępu do produktu Jira | nadaj mu dostęp do Jiry w Atlassian Administration |
| Sekret niedostępny | tokenu nie da się odszyfrować (klucz szyfrowania zmieniono lub utracono) | przywróć klucz albo **Zastąp token** |
| Brakujące zakresy | tokenowi z zakresami brakuje zakresów jakiejś możliwości (w deklaracji albo wywołanie odpowiedziało `401`/`403`) | utwórz nowy token z brakującymi zakresami, **Zastąp token** i popraw **Zakresy tokenu** |
| Zakresy zapisu, których nie potrzebuje żadna możliwość | token z zakresami może zapisywać więcej, niż wymaga połączenie | utwórz token bez nich |
| Szeroki zakres `write:jira-work` | klasyczny zakres zapisu pozwala też tworzyć, edytować i usuwać zgłoszenia | użyj zakresów szczegółowych |
| Nie znaleziono identyfikatora chmury (przy zapisie) | `<witryna>/_edge/tenant_info` jest nieosiągalne albo to nie jest witryna Jira Cloud | sprawdź URL witryny; wpisz identyfikator chmury ręcznie |

Wynik i jego czas są zapisywane w połączeniu (poza poświadczeniem ze zmiennych środowiskowych) i wpływają na
to, które połączenie dostanie krok.

<a id="jira-tasks"></a>
## Zadania Jiry w workflowach

Biblioteka zadań zawiera trzy szablony Jiry; przeciągnij je na kanwę jako węzły zadań
([workflows.md](workflows.md#nodes)). Paleta pokazuje, jakich możliwości potrzebuje każdy z nich, i oznacza
dwa, które zapisują w Jirze.

| Szablon | Konfiguracja | Potrzebuje | Działanie |
|---|---|---|---|
| **Fetch issue** (`fetch-issue`) | `issueKeyFrom`: `input` (domyślnie) lub nazwa parametru uruchomienia | **Odczyt zgłoszeń** | Wczytuje zgłoszenie, zapisuje je jako artefakt `issue` i w `state.issue`; kończy się błędem, gdy uruchomienie nie ma klucza zgłoszenia |
| **Notify Jira** (`notify-jira`) | `messageTemplate` (wymagane): treść komentarza, szablon oparty na stanie uruchomienia; `approval` (domyślnie `REQUIRED`) i `approvers` | **Komentowanie zgłoszeń** | Renderuje komentarz, czeka, aż człowiek go zatwierdzi ([zatwierdzenie](#jira-write-approval)), a potem dodaje dokładnie ten tekst jako komentarz do `state.issue.key`, a w przeciwnym razie do klucza zgłoszenia uruchomienia |
| **Transition issue** (`transition-issue`) | `toStatus` (wymagane), na przykład `In Review`; `approval` (domyślnie `REQUIRED`) i `approvers` | **Przenoszenie zgłoszeń** + **Odczyt zgłoszeń** | Czeka, aż człowiek zatwierdzi zmianę statusu, a potem pobiera listę dostępnych przejść zgłoszenia i wykonuje pierwsze, którego **nazwa statusu docelowego** lub **nazwa przejścia** jest równa `toStatus` (bez rozróżniania wielkości liter) |

<a id="jira-act-as"></a>
### Działaj jako: którego połączenia używa krok

Zaznacz węzeł Jiry w projektancie; sekcja **Połączenie Jira** (**Jira connection**) pokazuje potrzebne
możliwości i ustawienie **Działaj jako** (**Act as**):

| Działaj jako | Zapisywane jako `jiraConnection` | Krok używa |
|---|---|---|
| **Połączenie systemowe projektu** (domyślnie) | brak albo `SYSTEM` | [połączenia systemowego](#jira-system-connection) projektu. Musi mieć możliwości kroku i dopuszczać projekt Jiry zgłoszenia (prefiks klucza zgłoszenia: `SHOP` dla `SHOP-123`); w przeciwnym razie nadające się połączenie z listy projektu z **najmniejszą liczbą dodatkowych możliwości** (remis: projekt przed platformą, potem ostatni test OK, ostrzeżenie, nigdy nietestowane, błąd, potem nazwa). Bez połączenia systemowego: ta sama reguła dla wszystkich połączeń projektu i platformy, poświadczenie ze zmiennych środowiskowych na końcu. |
| **Wskazane połączenie** | identyfikator połączenia | dokładnie tego połączenia projektu lub platformy; musi pokrywać możliwości kroku i projekt Jiry zgłoszenia, inaczej krok kończy się błędem. Połączeń osobistych nie można wybrać. |
| **Osoba, która zatwierdza zapis** (kroki zapisu) / **Osoba, która rozpoczyna uruchomienie** (kroki odczytu) | `ACTING_USER` | własnego [połączenia osobistego](#jira-personal-connections) tej osoby (tego z najmniejszą liczbą dodatkowych możliwości). Dla zapisu wymagającego zatwierdzenia to zatwierdzający; dla odczytów i zapisów bez zatwierdzenia osoba, która rozpoczęła uruchomienie. |

Dodatkowe opcje:

- **Bez połączenia osobistego można użyć połączenia systemowego** (`fallbackToSystem`, dla **Osoba, która…**):
  gdy osoba nie ma pasującego połączenia osobistego (albo nikt nie rozpoczął uruchomienia, jak przy monitorze
  zgłoszeń lub CI), odczyty używają połączenia systemowego, a zatwierdzający może wybrać połączenie systemowe
  w zatwierdzeniu. Bez tej opcji krok wtedy kończy się błędem, a zatwierdzający może tylko odrzucić.
- **Zatwierdzający może zamiast tego zapisać własnym połączeniem osobistym** (`allowActingUser`, dla kroków
  zapisu na połączeniu systemowym): zatwierdzenie proponuje zatwierdzającemu zapis jako on sam.

Sekcja pokazuje też, którego połączenia krok użyłby teraz (dla **Osoba, która…** z opcją zastępczą: na które
połączenie systemowe by przeszedł). **Osoba, która rozpoczyna uruchomienie** w workflowie z wyzwalaczem monitora
zgłoszeń dostaje ostrzeżenie: uruchomienia rozpoczęte przez monitor nie mają takiej osoby.

### Ostrzeżenia i błędy

| Gdzie | Komunikat | Znaczenie |
|---|---|---|
| Projektant (walidacja, zapis) | ostrzeżenie „no Jira connection with capabilities [...]; the workflow can be saved, but runs refuse to start until a connection exists” | żadne połączenie się nie nadaje: dodaj połączenie albo rozszerz jego **Dozwolone projekty**. Zapis działa (instalacje bez Jiry mogą zachować domyślne workflowy) |
| Projektant | ostrzeżenie przy przypiętym połączeniu, które już nie istnieje | przypięte połączenie usunięto: przypnij inne albo usuń przypięcie; zapis działa, uruchomienia nie wystartują |
| Projektant | błąd przy przypiętym połączeniu | przypiętemu połączeniu brakuje możliwości lub projektu Jiry albo identyfikator połączenia jest nieprawidłowy; zapis jest zablokowany |
| Projektant | ostrzeżenie „uses a connection with more rights than needed (...)” (ma więcej uprawnień niż potrzeba) | dozwolone, ale utwórz połączenie tylko z potrzebnymi możliwościami |
| Projektant, log uruchomienia | „the system connection ... lacks ...; uses ... from the project's connection list” | połączenie systemowe nie wykona tego kroku; używane jest inne połączenie z listy |
| Log uruchomienia, błąd węzła | „the step acts as the person who approves or starts it, but this run was not started by a person ...” | **Działaj jako** = osoba, a uruchomienie przyszło z monitora zgłoszeń lub CI; włącz opcję zastępczą albo rozpocznij uruchomienie samodzielnie |
| **Uruchom**, log uruchomienia | „<imię i nazwisko> has no personal Jira connection with capabilities [...]” | dodaj je w **Moje konto** → **Połączenia** albo zezwól na opcję zastępczą |
| Projektant | ostrzeżenie „this step writes to Jira (...) without anybody confirming the exact write first ...” | **Zatwierdzenie przed zapisem w Jirze** ustawiono na niewymagane ([zatwierdzenie](#jira-write-approval)) |
| **Uruchom** (także CI, MCP, monitor zgłoszeń) | odmowa (`409`) „workflow X cannot start: ... (manual: integrations-jira#jira-connections)” | krok Jiry w workflowie nie ma połączenia, którego mógłby użyć; komunikat wskazuje krok. Dodaj połączenie i uruchom ponownie |
| Log uruchomienia | `INFO Jira connection: Jira reader (project) for ISSUE_READ` | którego połączenia użył krok i jaki ma zakres |
| Log uruchomienia | `WARN ... uses a connection with more rights than needed (extra: ISSUE_COMMENT, ISSUE_TRANSITION) - create a connection with only ISSUE_READ` | krok wykonał się przez połączenie z uprawnieniami nadmiarowymi |
| Log uruchomienia, błąd węzła | „no Jira connection with capabilities [ISSUE_READ] for project SHOP (Jira project SHOP); add one under Project → Connections or Administration → Connections” | w czasie uruchomienia nic się nie nadaje; węzeł kończy się błędem (port `failure`) |

Komunikaty logu uruchomienia są po angielsku; interfejs projektanta pokazuje ich polskie odpowiedniki.

Zmienne szablonu przydatne w komentarzach: `{{run.short}}`, `{{run.workflow}}`, `{{node.name}}`,
`{{issue.key}}`, `{{issue.summary}}`, `{{branch}}`, `{{mr.url}}`, `{{project.name}}`.

Uwagi:

- Przejście działa tylko ze statusu, który ma przejście do statusu docelowego. Odwzoruj kolejność
  w workflowie (na przykład **To Do** → **In Progress** przed **In Review**).
- Przejście z wymaganym polem na ekranie kończy się błędem: nie ustawiaj w przejściu pól obowiązkowych albo
  ustaw wartość domyślną w Jirze.
- Nieudane wywołanie Jiry powoduje błąd węzła (port `failure`); odpowiedzi `5xx` i `429` są oznaczane jako
  możliwe do ponowienia. Podłącz port błędu, jeśli awaria Jiry nie może zatrzymać dostarczania.
- Tekst z Jiry w Atlassian Document Format jest konwertowany do Markdownu na potrzeby promptów; komentarze
  są konwertowane z powrotem.

<a id="jira-write-approval"></a>
## Zatwierdzenie przed zapisem w Jirze

Komentarza ani zmiany statusu w Jirze nie da się cofnąć z poziomu Back Office, a ich treść może pochodzić
z wyniku modelu albo z tekstu zgłoszenia napisanego przez kogoś spoza zespołu (prompt injection, zob.
[Jak działają zadania agentów](agent-execution.md#prompt-injection)). Dlatego **Notify Jira** i **Transition
issue** proszą człowieka o zatwierdzenie dokładnie tego zapisu, zanim do niego dojdzie (ADR 0033).

### Jak to działa

1. Gdy uruchomienie dochodzi do kroku, Back Office renderuje zapis **jeden raz**: tekst komentarza po
   wstawieniu wartości szablonu i oczyszczeniu albo status docelowy.
2. Sprawdza, czy istnieje połączenie, przez które można zapisać. Jeśli go nie ma, krok od razu kończy się
   błędem: nikt nie jest proszony o zatwierdzenie zapisu, który i tak nie mógłby się odbyć.
3. Uruchomienie zatrzymuje się przed zapisem, a w **Twoja kolej** pojawia się **zatwierdzenie zapisu**
   dla osób, które mogą o nim zdecydować: „Agent chce skomentować SHOP-42” z dokładną treścią komentarza
   albo „Agent chce przenieść SHOP-42 do In Review”. Uruchomienie pokazuje przy kroku „czeka na
   zatwierdzenie”.
4. **Zatwierdź** (**Approve**): Back Office zapisuje **dokładnie** to, co zostało pokazane. **Kto zapisuje
   w Jirze** (**Who writes to Jira**) w zatwierdzeniu mówi, przez jaką tożsamość: połączenie systemowe (tam
   nazwane) albo **Ty, przez swoje połączenie osobiste**. Gdy krok działa jako zatwierdzający, to Twoje własne
   połączenie; gdy krok dopuszcza oba, możesz przełączyć. Jeśli nie masz pasującego połączenia osobistego,
   zatwierdzenie to mówi, prowadzi do **Moje konto** → **Połączenia** i proponuje połączenie systemowe tylko
   wtedy, gdy krok dopuszcza tę opcję zastępczą; w przeciwnym razie możesz tylko odrzucić. Po zatwierdzeniu nic
   nie jest renderowane ponownie.
5. **Odrzuć** (**Reject**): nic nie zostaje zapisane. Krok wychodzi przez port `failure` z komunikatem
   „write rejected by <nazwa>, nothing was written (...) - <komentarz>”. Jeśli `failure` nie jest
   połączony, uruchomienie kończy się niepowodzeniem, jak przy każdym nieudanym kroku. Połącz `failure`
   z węzłem **Koniec** z wynikiem **Porażka** albo z krokiem, który poprawi tekst.

Zatwierdzenie zapisu ma tylko te dwie opcje: tekstu nie można edytować, bo zatwierdzony tekst musi być
tekstem zapisanym. Aby go zmienić, odrzuć z komentarzem i pozwól workflowowi go poprawić. Zatwierdzenia
zapisu nigdy nie wygasają; **Limit czasu (s)** węzła ogranicza wykonanie, a nie oczekiwanie.

Jak je rozstrzygnąć: [Zatwierdzanie zapisów zewnętrznych](your-turn.md#write-confirmations).

### Ustawienie na węźle

Zaznacz w projektancie węzeł **Notify Jira** lub **Transition issue**. Sekcja **Zatwierdzenie przed
zapisem w Jirze** (**Approval before writing to Jira**) jego właściwości pokazuje:

| Ustawienie | Znaczenie |
|---|---|
| **Zatwierdzenie** (**Approval**) | **Wymagane** (**Required**; domyślnie): człowiek zatwierdza każdy zapis. **Niewymagane** (**Not required**): krok zapisuje od razu. Workflowy zapisane przed wprowadzeniem tej funkcji działają jak przy **Wymagane**. |
| **Kto zatwierdza** (**Who approves**) | Tak jak **Kto rozstrzyga ten krok** na bramce ([Ustawienia bramki](workflows.md#gates-config)): **Role projektowe**, **Wskazane osoby**, **Rola odpowiedzialna**, **Zabroń samozatwierdzania**. Puste: posiadacze `run.approve` z rolą projektową **Właściciel** lub **Maintainer**; rola odpowiedzialna **Właściciel**. |

Właściciele i maintainerzy mogą przekazać czekające zatwierdzenie innym osobom w tym uruchomieniu
przyciskiem **Przypisz…** (**Assign…**; `run.assign`, [Przypisywanie kroku](your-turn.md#assign-steps)).
Zasada czterech oczu i role nadal obowiązują.

Przy **Niewymagane** projektant pokazuje na węźle ostrzeżenie: „this step writes to Jira (...) without
anybody confirming the exact write first; text from issues or model output can reach it (prompt
injection) - keep approval REQUIRED unless the content is fixed” (krok zapisuje w Jirze bez
zatwierdzenia; może do niego trafić tekst zgłoszenia lub wynik modelu). Log uruchomienia powtarza je jako
wiersz `WARN` przy każdym zapisie. Wybieraj **Niewymagane** tylko dla stałego tekstu, na przykład
„Uruchomienie rozpoczęte”, którego nie zmieni żaden model ani żadne zgłoszenie.

### Audyt

| Wpis | Kiedy | Szczegóły |
|---|---|---|
| `RUN_WRITE_DECIDED` | ktoś zatwierdza lub odrzuca | akcja, klucz zgłoszenia, status docelowy, `contentSha256` pokazanego tekstu |
| `EXTERNAL_WRITE` | krok zapisuje (lub próbuje) | akcja, zgłoszenie, połączenie, `contentSha256`, zatwierdzenie `APPROVED` lub `NOT_REQUIRED`, kto zatwierdził, wynik `WRITTEN` lub `FAILED`, tożsamość `SYSTEM` lub `PERSONAL`, „via personal connection of <imię i nazwisko>” |

Skróty SHA-256 pozwalają audytorowi wykazać, że tekst zapisany w Jirze jest tekstem, który zatwierdzono.

### Domyślny workflow

**Issue to reviewed MR** kończy się krokiem **Move issue to In Review** (**Transition issue**). Teraz
domyślnie prosi on o zatwierdzenie: po bramce **Merge?** właściciele i maintainerzy projektu dostają
zatwierdzenie zapisu „Agent chce przenieść SHOP-42 do In Review”. Aby zachować dawne działanie bez
nadzoru, ustaw na tym węźle **Zatwierdzenie** na **Niewymagane** i zapisz nową wersję.

<a id="jira-intake"></a>
## Monitor zgłoszeń Jira

**Monitor zgłoszeń Jira** (**Jira intake watcher**) pozwala Jirze rozpoczynać pracę: gdy zgłoszenie
trafi do wybranego statusu (na przykład *Ready for AI*) albo gdy powstanie nowe zgłoszenie, Back Office
uruchamia dla niego workflow z kluczem zgłoszenia. Każdy projekt ma jeden monitor. Monitor odpytuje Jirę;
nie potrzebuje webhooka ani połączenia przychodzącego z Atlassian, więc działa także za zaporą.

### Czego potrzebujesz

1. **Połączenia do odczytu**: połączenia z **Odczytem zgłoszeń**, które nadaje się dla klucza projektu
   Jira projektu. Monitor używa wyłącznie takiego połączenia i bez niego odmawia odpytywania. Najlepiej,
   aby było to dedykowane konto do odczytu (zob. **Konto do odczytu dla monitora** niżej); połączenie do
   zapisu też zadziała, ale pokaże ostrzeżenie o zasadzie najmniejszych uprawnień, a monitor i tak może
   przez nie tylko czytać.
2. **Workflowu z wyzwalaczem Jira.** W projektancie, bez zaznaczonego węzła, dodaj w **Wyzwalaczach**
   wyzwalacz **Jira (monitor zgłoszeń)** (**Jira (intake watcher)**, zapisywany jako `JIRA_TRANSITION`)
   ([Uruchamianie workflowu](workflows.md#start-run)):
   - ze **statusem** (na przykład `Ready for AI`): zgłoszenie, które **trafi** do tego statusu, uruchamia
     jedno uruchomienie;
   - bez statusu: **nowe** zgłoszenie, utworzone po starcie monitora, uruchamia jedno uruchomienie.

   Workflow musi być włączony i powinien zaczynać się od **Fetch issue**, aby uruchomienie odczytało
   zgłoszenie.
3. **Włączonej Jiry w projekcie**, z **Kluczem projektu Jira** i opcjonalnie **Filtrem JQL** na karcie
   **Ustawienia** projektu ([Włącz Jirę w projekcie](#jira-config)). Filtr zawęża, które zgłoszenia się
   liczą, na przykład `issuetype = Story AND labels = ai`. `ORDER BY` w filtrze jest pomijane.

### Włącz monitor

Wymaga `project.manage` (**Właściciel** lub **Maintainer** projektu).

1. Otwórz projekt → karta **Ustawienia** (**Settings**); karta **Monitor zgłoszeń Jira** jest obok
   formularza projektu.
2. Włącz przełącznik **Monitoruj Jirę i uruchamiaj workflowy** (**Watch Jira and start runs**).
3. **Sprawdzaj co (minuty)** (**Poll every (minutes)**): od 1 do 1440, domyślnie 5.
4. **Workflow do uruchomienia** (**Workflow to start**): workflow tego projektu z wyzwalaczem **Jira
   (monitor zgłoszeń)**; lista pokazuje przy każdym workflowie, co go uruchamia („gdy zgłoszenie przejdzie
   do Ready for AI”, „dla nowych zgłoszeń”) i wyszarza workflowy bez tego wyzwalacza. Wybór workflowu bez tego wyzwalacza zostanie odrzucony: „workflow X has no JIRA_TRANSITION
   trigger; add one in the designer (with the status that starts a run, or empty for new issues)”.
5. Zapisz. Zmiana trafia do dziennika audytu jako `JIRA_INTAKE_UPDATE`.

**Pierwsze odpytanie tylko ustala punkt startowy.** Zgłoszenia, które już są w danym statusie albo już
istnieją, nigdy nie uruchamiają workflowu; liczą się tylko zmiany po tej chwili. Tak samo jest po
wyłączeniu i ponownym włączeniu monitora oraz po zmianie workflowu do uruchomienia. Aby uruchomić workflow
dla zgłoszenia z backlogu, przenieś je poza status i z powrotem albo użyj **Uruchom** z jego kluczem.

### O co Back Office pyta Jirę

Każde odpytanie wysyła jedno wyszukiwanie (`GET /rest/api/3/search/jql`):

```
project = "SHOP" AND (<filtr JQL>) AND status CHANGED TO "Ready for AI" AFTER -12m ORDER BY updated ASC
project = "SHOP" AND (<filtr JQL>) AND created >= -12m ORDER BY updated ASC
```

Pierwszy wiersz dotyczy wyzwalacza ze statusem, drugi nowych zgłoszeń; bez **Filtru JQL** część
w nawiasie jest pomijana. `-12m` to czas od ostatniego udanego odpytania plus dwie minuty zakładki (co
najmniej 2 minuty, najwyżej 7 dni). Czas względny nie zależy od strefy czasowej konta Jiry. Gdy połączenie
ma **Dozwolone projekty**, dokłada wokół zapytania `project in (...) AND (...)`. Monitor odczytuje najwyżej
100 zgłoszeń na odpytanie (strony po 50) i tylko ich klucz, status, czas utworzenia i aktualizacji oraz
zmiany statusu z historii. Nigdy nie odczytuje tytułu, opisu ani komentarzy: tekst zgłoszenia trafia do
uruchomienia wyłącznie przez krok **Fetch issue**, jako dane niezaufane.

### Jedno uruchomienie na przejście

Każde uruchomienie jest chronione rezerwacją, którą może zająć tylko jedna replika Back Office:

- zgłoszenie, które trafia do statusu, jest rozpoznawane po wpisie historii tej zmiany, więc uruchamia
  najwyżej jedno uruchomienie na przejście. Jeśli opuści status i później znów do niego trafi, to nowe
  przejście i nowe uruchomienie;
- nowe zgłoszenie uruchamia najwyżej jedno uruchomienie, raz na zawsze.

Jeśli uruchomienie nie może wystartować (na przykład krok Jiry w workflowie nie ma połączenia), rezerwacja
jest zwalniana, a błąd pojawia się przy monitorze; następne odpytanie próbuje ponownie. Na jedno
odpytanie startuje najwyżej 20 uruchomień; przy większej liczbie zdarzeń następne odpytanie kontynuuje od
miejsca, w którym skończyło poprzednie.

Ograniczenie: jeśli Jira nie zwróci historii zgłoszenia, zgłoszenie, które opuści status i wróci do niego
między dwoma odpytaniami, może zostać policzone tylko raz.

Uruchomienia rozpoczęte przez monitor mają wykonawcę **Jira intake watcher** (monitor zgłoszeń Jira)
i wyzwalacz `JIRA` oraz pojawiają się w **Uruchomieniach** jak każde inne. Ich start jest zapisywany
w dzienniku audytu jako `RUN_START` z `trigger=JIRA` i rezerwacją (`intakeDedupeKey`).

### Karta stanu

Sekcja pokazuje stan monitora każdemu z `project.view`:

| Pole | Znaczenie |
|---|---|
| Stan | włączony lub wyłączony |
| Workflow do uruchomienia | workflow i to, co go uruchamia: **gdy zgłoszenie przejdzie do** `Ready for AI` albo **dla nowych zgłoszeń** |
| Zapytanie wysyłane do Jiry | zapytanie, które wysyła Back Office (pokazane dla jednego interwału) |
| Połączenie do odczytu | połączenie, którego użyłby monitor, ze stanem **OK**, **ma więcej uprawnień niż potrzeba** (działa, ale ma możliwości zapisu) lub **brak** (monitor odmawia odpytywania) |
| Ostatnie sprawdzenie, Ostatnie udane, Następne sprawdzenie | czas ostatniej próby, ostatniego udanego sprawdzenia i następnego zaplanowanego |
| Ostatnie sprawdzenie nie powiodło się, Nieudane sprawdzenia z rzędu | dlaczego ostatnie sprawdzenie się nie udało i ile razy z rzędu (następne czeka wtedy dłużej) |
| Uruchomione workflowy | łączna liczba uruchomień rozpoczętych przez monitor |
| Problemy | co uniemożliwia działanie monitora, na przykład brak wyzwalacza, wyłączony workflow albo brak połączenia do odczytu |

**Sprawdź teraz** (**Poll now**; wymaga `project.manage`, monitor musi być włączony) planuje odpytanie
od razu, zamiast czekać na interwał; worker wykonuje je w ciągu kilku sekund. Zapisywane w dzienniku
audytu jako `JIRA_INTAKE_POLL_NOW`.

### Błędy i wstrzymanie

Gdy Jira odpowiada `429` (limit zapytań), `5xx` lub innym błędem, monitor zapisuje błąd i odczekuje dłużej
przed kolejnym odpytaniem (ponowienie po przerwie): interwał podwaja się z każdym kolejnym niepowodzeniem,
najwyżej do godziny. Gdy Jira wyśle `Retry-After`, monitor czeka co najmniej tyle. Pierwsze udane
odpytanie przywraca zwykły interwał. Nie wpływa to na kroki uruchomień.

| Komunikat przy monitorze | Znaczenie | Rozwiązanie |
|---|---|---|
| „refused: no Jira connection with capabilities [ISSUE_READ] ...” | żadne połączenie z **Odczytem zgłoszeń** nie nadaje się dla klucza Jira projektu | dodaj połączenie do odczytu (**Konto do odczytu dla monitora** niżej) albo rozszerz **Dozwolone projekty** |
| „workflow X has no JIRA_TRANSITION trigger” | wyzwalacz usunięto z workflowu | dodaj wyzwalacz **Jira (monitor zgłoszeń)** i zapisz nową wersję |
| „workflow X is disabled” | workflow do uruchomienia jest wyłączony | włącz go albo wybierz inny workflow |
| „the workflow to start no longer exists; choose another one” | workflow usunięto | wybierz inny workflow |
| „Jira answered HTTP 401: ...” | token konta do odczytu wygasł lub został unieważniony | **Zastąp token** ([rotacja](#jira-token-expiry)) |
| „Jira answered HTTP 400: ...” | **Filtr JQL** nie jest poprawnym JQL albo nazwa statusu nie istnieje | popraw filtr lub status wyzwalacza |
| „Jira answered HTTP 429: ...” | limit zapytań Jiry | nic; monitor sam zwalnia. Jeśli się powtarza, zwiększ interwał |
| „workflow X cannot start: ...” | krok Jiry w workflowie nie ma połączenia, którego mógłby użyć | dodaj połączenie; następne odpytanie spróbuje ponownie |
| ostrzeżenie „ma więcej uprawnień niż potrzeba” | połączenie do odczytu może też zapisywać | użyj dedykowanego połączenia do odczytu |

### Konto do odczytu dla monitora

Monitor potrzebuje **Browse Projects** i niczego więcej. Jeśli konto do odczytu już istnieje
([kroki](#jira-token)), dodaj dla niego połączenie i przejdź do kroku 5. W przeciwnym razie, z udziałem
administratora organizacji Atlassian i administratora Jiry:

1. **Utwórz dedykowane konto.** admin.atlassian.com → **Directory** → **Users** → **Invite user**: e-mail
   na przykład `backoffice-jira-reader@acme.com`, dostęp tylko do produktu **Jira**, bez ról
   administracyjnych.
2. **Utwórz grupę i dodaj do niej konto.** admin.atlassian.com → **Directory** → **Groups** → **Create
   group**: nazwa `backoffice-readers`; następnie **Add group members** i dodaj konto.
3. **Nadaj tylko Browse Projects** w każdym projekcie Jiry, który ma czytać monitor:
   - **Projekt company-managed**: **Project settings** → **Permissions** pokazuje schemat uprawnień. Jako
     administrator Jiry otwórz **Settings** (koło zębate) → **Issues** → **Permission schemes**; edytuj
     schemat albo, jeśli dzielą go inne projekty, najpierw go skopiuj (**Copy**) i przypisz kopię do
     projektu (**Project settings** → **Permissions** → **Actions** → **Use a different scheme**). Przy
     **Browse Projects** wybierz **Update** (lub **Grant access**) i dodaj grupę `backoffice-readers`. Nie
     nadawaj grupie niczego więcej.
   - **Projekt team-managed**: **Project settings** → **Access** → **Add people**, dodaj konto z rolą
     **Viewer**. W zależności od planu Jiry Viewer może też komentować; **Testuj** pokaże wtedy Add
     Comments jako uprawnienie nadmiarowe. Tam, gdzie dostępne są role własne (**Access** → **Manage
     roles**), utwórz rolę tylko z przeglądaniem.

   Upewnij się, że grupa (ani konto przez inną grupę, na przykład `jira-software-users`) **nie** dostaje
   **Add Comments**, **Transition Issues**, **Edit Issues**, **Create Issues**, **Assign Issues**,
   **Delete Issues**, **Administer Projects** ani **Manage Watchers**. Jeśli zgłoszenia mają poziomy
   bezpieczeństwa (issue security levels), konto musi mieć prawo je widzieć.
4. **Utwórz token API jako to konto.** Zaloguj się jako `backoffice-jira-reader@acme.com`, otwórz
   id.atlassian.com → **Security** → **API tokens** (https://id.atlassian.com/manage-profile/security/api-tokens)
   → **Create API token** (klasyczny, nie **with scopes**), nadaj nazwę, na przykład `back-office-reader`,
   wybierz datę wygaśnięcia, skopiuj token (wyświetlany tylko raz) i zanotuj datę.
5. **Dodaj połączenie w Back Office.** Projekt → **Połączenia** (albo **Administracja** → **Połączenia**
   dla wszystkich projektów) → **Dodaj połączenie**: typ **Jira**, URL witryny, e-mail konta, token,
   tylko możliwość **Odczyt zgłoszeń**, **Dozwolone projekty** = klucz projektu Jira (na przykład `SHOP`),
   **Data wygaśnięcia tokenu**. Kliknij **Testuj przed zapisem**: musi pokazać **Browse Projects** jako
   przyznane i brak uprawnień nadmiarowych. Następnie **Zapisz**.

Karta stanu monitora powinna teraz pokazywać to połączenie ze stanem **OK**.

## Rozwiązywanie problemów

| Objaw | Przyczyna | Rozwiązanie |
|---|---|---|
| Węzeł kończy się błędem „no Jira connection with capabilities [...]” | żadne połączenie projektu ani platformy nie ma tej możliwości albo **Dozwolone projekty** wykluczają projekt Jiry zgłoszenia | dodaj połączenie z tą możliwością ([Dodaj połączenie](#jira-connections)) albo dopisz klucz do **Dozwolonych projektów** |
| Węzeł kończy się błędem przy przypiętym połączeniu | przypięte połączenie usunięto, straciło możliwość albo nie dopuszcza projektu Jiry zgłoszenia | przypnij inne połączenie albo usuń przypięcie |
| Ostrzeżenie projektanta „ma więcej uprawnień niż potrzeba” | najlepsze dopasowanie ma dodatkowe możliwości (często to poświadczenie ze zmiennych środowiskowych) | utwórz połączenie tylko z potrzebnymi możliwościami |
| `HTTP 401` w kroku | token wygasł lub został unieważniony | **Zastąp token** |
| `HTTP 403` / `404` w kroku | konto nie może przeglądać projektu lub zgłoszenia (rola, issue security) | dodaj konto do roli projektowej; **Testuj** pokaże brakujące uprawnienie |
| `has no transition to status X` | żadne przejście z bieżącego statusu nie prowadzi do `X` | popraw `toStatus` albo workflow w Jirze |
| **Dodaj połączenie**: „BACKOFFICE_SECRETS_KEY is not configured” | operator nie ustawił klucza szyfrowania | ustaw go ([Skonfiguruj Back Office](#jira-config)) |
| API lub worker nie startuje: brak klucza sekretów lub nieznany identyfikator klucza | istnieją połączenia, a klucz nie jest skonfigurowany, albo poprzedni klucz usunięto przed ponownym opakowaniem | przywróć `BACKOFFICE_SECRETS_KEY` / `BACKOFFICE_SECRETS_PREVIOUS_KEYS` |
| **Testuj** pokazuje „Sekret niedostępny” | tokenu nie da się odszyfrować skonfigurowanymi kluczami | przywróć klucz albo **Zastąp token** |
| **Uruchom** odrzucone: „workflow X cannot start: ...” | krok Jiry nie ma połączenia, którego mógłby użyć (zapis workflowu tylko ostrzegał) | dodaj połączenie wskazane w komunikacie ([Dodaj połączenie](#jira-connections)) |
| Uruchomienie czeka na **Notify Jira** / **Transition issue** | zapis czeka na zatwierdzenie | rozstrzygnij je w **Twoja kolej** ([Zatwierdzanie zapisów zewnętrznych](your-turn.md#write-confirmations)) |
| Krok zakończony błędem „write rejected by ..., nothing was written” | zatwierdzający odrzucił zapis | przeczytaj komentarz, popraw tekst, uruchom ponownie; połącz `failure`, jeśli uruchomienie ma iść dalej |
| Monitor zgłoszeń niczego nie uruchamia | pierwsze odpytanie (punkt startowy), brak wyzwalacza, zbyt wąski filtr albo „refused: ...” | zob. [Monitor zgłoszeń Jira](#jira-intake), karta stanu i komunikaty |
| **Zatwierdź** jest wyłączone: „you have no personal Jira connection ...” | krok zapisuje jako zatwierdzający, a Ty nie masz połączenia dla tej możliwości i projektu Jiry | dodaj [połączenie osobiste](#jira-personal-connections) albo poproś projektanta o opcję zastępczą z połączeniem systemowym |
| Każde połączenie pokazuje „ma więcej uprawnień niż potrzeba” | klasyczne tokeny kont osobistych | użyj [tokenów z zakresami](#jira-scoped-token) |

Powiązane: [troubleshooting.md](troubleshooting.md#runs) – uruchomienia, które kończą się błędem w kroku Jiry.
