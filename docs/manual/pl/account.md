# Twoje konto

**Moje konto** (**My account**; `/account`, dostępne też w menu awatara) zawiera Twój profil,
preferencje, zabezpieczenia logowania, osobiste tokeny dostępu, połączenie z IDE i przegląd Twoich
uprawnień. To jedna przewijana strona ze spisem sekcji po lewej; `?section=` w URL przenosi do sekcji.

| Sekcja | Co tam robisz |
|---|---|
| **Profil** (**Profile**) | Sprawdzasz imię i nazwisko, e-mail, grupy i bieżącą sesję. |
| **Preferencje** (**Preferences**) | Motyw, gęstość, liczba wierszy na stronę, domyślny projekt. |
| **Powiadomienia** (**Notifications**) | Powiadomienia na pulpicie i dźwięki (zob. [Powiadomienia na pulpicie](#notifications)). |
| **Bezpieczeństwo** (**Security**) | Metoda logowania, hasło, uwierzytelnianie dwuskładnikowe, ostatnie logowania. |
| **Osobiste tokeny dostępu** (**Personal access tokens**) | Tworzysz i unieważniasz tokeny dla skryptów i IDE. |
| **Podłącz IDE** (**Connect your IDE**) | Konfigurujesz serwer MCP w Claude Code, Cursorze lub VS Code. |
| **Moje uprawnienia** (**My access**) | Twoje uprawnienia platformowe oraz projekty z rolami. |

**Wyloguj** (**Sign out**) znajduje się w nagłówku strony i w menu awatara.

<a id="profile"></a>
## Profil i preferencje

**Profil** pokazuje **Imię i nazwisko** (**Name**), **E-mail**, **Grupy** (**Groups**) i **Bieżącą
sesję** (**This session**): kiedy się zalogowałeś i kiedy najpóźniej sesja się skończy. Imieniem
i nazwiskiem, e-mailem i grupami zarządza administrator (zob. [Administracja](administration.md#users)).

Sesja kończy się też po okresie bezczynności; aplikacja ostrzega o tym i proponuje ponowne zalogowanie.

**Preferencje**:

| Preferencja | Opcje |
|---|---|
| **Motyw** (**Theme**) | **Systemowy** (**System**; zgodny z systemem operacyjnym), **Jasny** (**Light**), **Ciemny** (**Dark**). Także `Alt T` lub **Przełącz motyw** (**Toggle theme**) w palecie poleceń. |
| **Gęstość** (**Density**) | **Wygodna** (**Comfortable**) lub **Kompaktowa** (**Compact**). Działa od razu, w tej przeglądarce. |
| **Wierszy na stronę** (**Rows per page**) | Od 10 do 200; dłuższe listy przewijają się wirtualnie. |
| **Domyślny projekt** (**Default project**) | Wybierany na pasku bocznym po zalogowaniu. |

Kliknij **Zapisz preferencje** (**Save preferences**).

**Moje uprawnienia** wymieniają Twoje **Uprawnienia platformowe** (**Platform permissions**) oraz, w sekcji
**Projekty** (**Projects**), każdy projekt z Twoimi rolami i liczbą uprawnień, które dają. Jeśli masz
`admin.projects.all`, masz wszystkie uprawnienia projektowe w każdym projekcie. Aby uzyskać szerszy
dostęp, poproś właściciela projektu (role projektowe) lub administratora (role platformowe, zob.
[Role i nadania](administration.md#roles-grants)).

<a id="notifications"></a>
## Powiadomienia na pulpicie i dźwięki

Back Office może dać Ci znać na pulpicie, że coś na Ciebie czeka, krótkim, cichym dźwiękiem własnego
autorstwa. Powiadomienia pojawiają się, gdy karta Back Office jest otwarta w przeglądarce, także gdy karta
jest w tle albo okno jest zminimalizowane. Nie są wysyłane, gdy wszystkie karty Back Office są zamknięte.
Zawsze *uzupełniają* liczniki w aplikacji (bursztynowy licznik **Twoja kolej**, panel aktywności): nic
nie jest sygnalizowane wyłącznie powiadomieniem ani wyłącznie dźwiękiem.

### Włączanie

1. Otwórz **Moje konto** > **Powiadomienia** (**Notifications**) albo kliknij awatar i wybierz
   **Powiadomienia na pulpicie** (**Desktop notifications**).
2. Włącz **Powiadomienia na pulpicie**. Przeglądarka zapyta, czy Back Office może wyświetlać
   powiadomienia: wybierz **Zezwalaj** (**Allow**). Przeglądarka pyta tylko raz i nigdy sama z siebie.
3. Wybierz, o czym chcesz wiedzieć, i kliknij **Odsłuchaj** (**Preview**) przy typie, aby usłyszeć jego
   dźwięk.
4. Kliknij **Wyślij powiadomienie testowe** (**Send a test notification**), aby sprawdzić, czy system
   je pokazuje.

| Ustawienie | Działanie | Domyślnie |
|---|---|---|
| **Twoja kolej** (**Your turn**) | Zaczyna czekać bramka, o której możesz zdecydować, albo ktoś Ci ją przypisał. Najbardziej przydatne. Tutaj zgłaszany jest też krok, który chce zapisać coś w Jirze i czeka na Twoje zatwierdzenie, z osobnym tytułem „Potwierdź zapis w Jirze” (**Confirm a Jira write**) i wierszem w rodzaju „Agent chce skomentować SHOP-42” ([Zatwierdzanie zapisów zewnętrznych](your-turn.md#write-confirmations)). | wł. |
| **Uruchomienie nieudane** (**Run failed**) | Uruchomienie rozpoczęte przez Ciebie albo w projekcie, którego jesteś właścicielem, zakończyło się błędem. | wł. |
| **Uruchomienie zakończone** (**Run landed**) | Takie uruchomienie zakończyło się powodzeniem. | wył. |
| **Alert budżetu** (**Budget alert**) | Budżet, który możesz podnieść, osiągnął próg alertu albo limit. | wł. |
| **Błąd CI** (**CI failed**) | Wykonanie CI albo bramka przeglądu w jednym z Twoich projektów nie przeszła. | wył. |
| **Wygasające połączenie** (**Connection expiring**) | Token połączenia Jiry, którym możesz zarządzać, wygasa w ciągu 14 dni lub już wygasł; raz na każdą datę wygaśnięcia ([Wygaśnięcie tokenu](integrations-jira.md#jira-token-expiry)). | wł. dla administratorów (`admin.connections.manage`), wył. dla pozostałych |
| **Tylko gdy karta nie jest na widoku** (**Only when the tab is not in view**) | Nic nie wyskakuje, gdy patrzysz na Back Office (inna karta, inne okno albo zminimalizowana przeglądarka to „nie na widoku”). | wł. |
| **Odtwarzaj dźwięk** (**Play a sound**) / **Głośność** (**Volume**) | Każdy typ ma własny krótki ton: wznoszące się dwa dźwięki „przekazania pałeczki” dla *Twojej kolei*, lekkie trzy wznoszące się dźwięki, gdy uruchomienie się zakończy, miękka opadająca para, gdy coś się nie udało, przytłumione podwójne stuknięcie dla budżetów. Odsłuch działa także przy wyłączonym dźwięku. | wł., 60 % |
| **Godziny ciszy** (**Quiet hours**) | Bez powiadomień i dźwięków między dwiema godzinami (zegar Twojego komputera); mogą obejmować północ, np. od 22:00 do 07:00. | wył. |

Kliknięcie powiadomienia przenosi kartę Back Office na wierzch i otwiera bramkę, zatwierdzenie zapisu,
uruchomienie, wykonanie CI, budżet albo połączenie. Kilka otwartych kart Back Office pokazuje jedno powiadomienie, a nie jedno na kartę.
Ustawienia zapisują się od razu, w tej przeglądarce, dla Twojego konta; na innym komputerze lub w innej
przeglądarce ustawiasz je ponownie. Powiadomienia zawierają tylko nazwy i klucze (projekt, zgłoszenie,
workflow, krok), nigdy wyników pracy agentów ani sekretów.

### Gdy przeglądarka blokuje powiadomienia

Jeśli sekcja informuje, że przeglądarka blokuje powiadomienia, wcześniej je odrzuciłeś (albo zrobiła to
polityka Twojej organizacji). Przeglądarka nie zapyta ponownie; zezwól na nie w ustawieniach witryny
i odśwież stronę:

| Przeglądarka | Kroki |
|---|---|
| **Chrome** | Kliknij ikonę po lewej stronie adresu (informacje o witrynie) > **Powiadomienia** > **Zezwalaj**. Albo **Ustawienia** > **Prywatność i bezpieczeństwo** > **Ustawienia witryn** > **Powiadomienia**. |
| **Edge** | Kliknij kłódkę po lewej stronie adresu > **Uprawnienia dla tej witryny** > **Powiadomienia** > **Zezwalaj**. Albo **Ustawienia** > **Pliki cookie i uprawnienia witryny** > **Powiadomienia**. |
| **Firefox** | Kliknij ikonę uprawnień po lewej stronie adresu i usuń **Zablokowano: wysyłanie powiadomień**; albo **Ustawienia** > **Prywatność i bezpieczeństwo** > **Uprawnienia** > **Powiadomienia** > **Ustawienia…**. |
| **Safari** (macOS) | **Safari** > **Ustawienia** > **Witryny** > **Powiadomienia**: ustaw adres Back Office na **Pozwalaj**. |

Przeglądarki pokazują powiadomienia tylko dla bezpiecznych adresów (`https://` albo `localhost` na
własnym komputerze). Jeśli sekcja informuje, że przeglądarka nie może ich pokazać, użyj aktualnej
przeglądarki i adresu `https://`.

### Gdy powiadomienia się nie pojawiają

- **Windows**: Asystent skupienia / **Nie przeszkadzać** (Ustawienia > System > Powiadomienia) je ukrywa;
  sprawdź tam też, czy powiadomienia przeglądarki (Chrome, Edge, Firefox) są włączone.
- **macOS**: tryb **Skupienie** (Centrum sterowania) je ukrywa; w **Ustawieniach systemowych** >
  **Powiadomienia** zezwól na powiadomienia przeglądarki (styl *Alerty* zostawia je na ekranie do czasu
  reakcji).
- **Linux**: ustawienie „nie przeszkadzać” środowiska graficznego.
- Ustawienia **Tylko gdy karta nie jest na widoku** i **Godziny ciszy** celowo wstrzymują powiadomienia.
- Brak dźwięku: przeglądarka odtwarza dźwięki dopiero po pierwszym kliknięciu lub naciśnięciu klawisza na
  stronie; sprawdź głośność i czy karta nie jest wyciszona.

<a id="mfa"></a>
## Bezpieczeństwo logowania i uwierzytelnianie dwuskładnikowe

**Bezpieczeństwo** pokazuje Twoją **Metodę logowania** (**Sign-in method**) i czas ostatniego logowania.

- **Konta jednokrotnego logowania (SSO)** (Keycloak, Entra ID, Google, GitLab): **Hasłem
  i uwierzytelnianiem dwuskładnikowym** (**Password and two-factor**) zarządza Twój dostawca tożsamości;
  **Otwórz konto …** (**Open … account**) przenosi do niego. Back Office nie przechowuje Twojego hasła.
- **Konta lokalne** (e-mail i hasło): hasłem i uwierzytelnianiem dwuskładnikowym zarządzasz tutaj. To,
  jaką metodę masz, ustala administrator (zob. [Logowanie](integrations-identity.md#local-accounts)).

**Ostatnie logowania** (**Recent sign-ins**) wymieniają **Kiedy** (**When**), **Metodę** (**Method**),
**Wynik** (**Result**) i **Skąd** (**From**) dla Twoich ostatnich logowań. Logowanie, którego nie
rozpoznajesz, to powód, by zmienić hasło i powiadomić administratora.

### Zmiana hasła (konta lokalne)

1. Kliknij **Zmień hasło** (**Change password**).
2. Wpisz **Obecne hasło** (**Current password**) i dwukrotnie nowe hasło. Podpowiedzi pod polem pokazują
   zasady: co najmniej 12 znaków, nie może to być popularne hasło ani zawierać nazwy z Twojego adresu
   e-mail.
3. Kliknij **Zmień hasło**. Twoje pozostałe sesje zostaną wylogowane.

### Konfiguracja uwierzytelniania dwuskładnikowego (konta lokalne)

Uwierzytelnianie dwuskładnikowe wymaga po haśle kodu z telefonu, więc samo skradzione hasło jest
bezużyteczne.

1. Kliknij **Skonfiguruj uwierzytelnianie dwuskładnikowe** (**Set up two-factor authentication**) (lub
   **Skonfiguruj MFA**, **Set up MFA**, w palecie poleceń).
2. **Zeskanuj kod QR** (**Scan the QR code**) aplikacją uwierzytelniającą, taką jak 1Password, Google
   Authenticator, Microsoft Authenticator czy Aegis. Jeśli nie możesz zeskanować kodu, skopiuj klucz
   konfiguracyjny.
3. **Wpisz kod z aplikacji** (**Enter the code from the app**) i kliknij **Włącz uwierzytelnianie
   dwuskładnikowe** (**Turn on two-factor authentication**).
4. **Zapisz kody odzyskiwania** (**Save your recovery codes**): skopiuj lub pobierz 10 kodów, zaznacz
   **Kody są zapisane w bezpiecznym miejscu** (**I saved these codes somewhere safe**) i kliknij **Gotowe,
   kody zapisane** (**Done, I saved them**). Są wyświetlane tylko raz; każdy z nich pozwala jednorazowo
   zalogować się bez telefonu.

Od tej chwili logowanie wymaga po haśle 6-cyfrowego kodu; zamiast niego zadziała kod odzyskiwania.

| Akcja | Skutek |
|---|---|
| **Utwórz nowe kody odzyskiwania** (**Create new recovery codes**) | Potwierdź hasłem. Stare kody przestają działać; zapisz 10 nowych. |
| **Wyłącz** (**Turn off**) | Potwierdź hasłem. Logowanie wymaga tylko hasła; kody odzyskiwania przestają działać. Niedostępne, gdy platforma wymaga uwierzytelniania dwuskładnikowego. |

Jeśli zgubisz telefon i kody odzyskiwania, administrator może zresetować Twoje uwierzytelnianie
dwuskładnikowe. Gdy platforma wymaga uwierzytelniania dwuskładnikowego dla kont lokalnych, zaraz po
zalogowaniu musisz je skonfigurować, zanim skorzystasz z czegokolwiek innego.

<a id="tokens"></a>
## Osobiste tokeny dostępu

Osobisty token dostępu (PAT, `bo_pat_…`) pozwala skryptowi, CLI lub IDE działać w Twoim imieniu bez sesji
przeglądarki. Ma Twoje uprawnienia, opcjonalnie zawężone, i przestaje działać, gdy wygaśnie, gdy go
unieważnisz lub gdy Twoje konto zostanie dezaktywowane. Tworzenie tokenów wymaga `tokens.personal` (ma je
każdy **Członek**, **Member**).

### Kroki

1. W sekcji **Osobiste tokeny dostępu** kliknij **Utwórz token** (**Create token**).
2. Wpisz **Nazwę** (**Name**) (na przykład `release script`).
3. Ustaw **Wygasa za (dni)** (**Expires in (days)**): pole wymagane, najwyżej 365 dni lub niższy limit
   ustawiony przez administratora.
4. Opcjonalnie użyj **Ogranicz do uprawnień** (**Limit to permissions**): puste oznacza wszystkie Twoje
   uprawnienia. Token nigdy nie ma więcej uprawnień niż Ty.
5. Kliknij **Utwórz token**, skopiuj token (jest wyświetlany tylko raz) i kliknij **Token skopiowany**
   (**I copied the token**).

Używaj go jako `Authorization: Bearer bo_pat_…`. Lista pokazuje dla każdego tokenu **Prefiks** (**Prefix**),
**Uprawnienia** (**Permissions**), **Wygasa** (**Expires**), **Ostatnie użycie** (**Last used**) i **Stan**
(**State**: aktywny, wygasły, unieważniony); **Unieważnij** (**Revoke**) natychmiast wyłącza token. Twórz
osobny token dla każdego skryptu lub urządzenia, aby móc unieważnić jeden bez psucia pozostałych.

Osobiste tokeny dostępu to jedyne tokeny akceptowane przez serwer MCP. Pipeline'y CI używają zamiast nich
tokenów CI projektu (zob. [Projekty](projects.md#project-ci)).

## Podłączanie IDE

Back Office jest serwerem MCP: Twój agent programistyczny może z poziomu edytora, z Twoimi uprawnieniami,
wyświetlać Twoje elementy z kolejki, czytać specyfikacje i przeglądy, uruchamiać workflowy i rozstrzygać
bramki.

1. Utwórz osobisty token dostępu jak wyżej; ogranicz jego uprawnienia i czas ważności.
2. W sekcji **Podłącz IDE** wybierz klienta: **Claude Code**, **Cursor** lub **VS Code**.
3. Skopiuj fragment konfiguracji. Dla Claude Code (polecenie terminala) i Cursora (`.cursor/mcp.json`)
   zastąp symbol zastępczy swoim tokenem; plik Cursora trzymaj poza systemem kontroli wersji. VS Code
   (`.vscode/mcp.json`) prosi o token przy pierwszym użyciu i przechowuje go w swoim magazynie sekretów.

Sekcja wymienia narzędzia udostępniane przez serwer. Unieważnienie tokenu odłącza IDE. Szczegóły dla
poszczególnych klientów: [IDE i MCP](integrations-ide.md#pat).

<a id="connections"></a>
## Połączenia

**Moje konto** → **Połączenia** zawiera Twoje **osobiste połączenia z Jirą**: połączenia, które działają jako Ty.
Kroki workflowu ustawione na działanie jako „osoba, która je zatwierdza lub rozpoczyna” używają jednego z nich,
aby komentować lub przenosić zgłoszenia w Twoim imieniu, i tylko wtedy, gdy zatwierdzasz ten zapis lub
rozpoczynasz to uruchomienie. Nikt inny ich nie widzi ani nie używa; administratorzy widzą jedynie, że istnieją.
Używaj dla nich tokenu API z zakresami. Szczegóły:
[Połączenia osobiste](integrations-jira.md#jira-personal-connections).
