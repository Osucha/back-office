# Dostęp agentów do gita

Agenci kodujący klonują repozytorium projektu, wypychają gałąź, a na GitLabie otwierają merge request. Robią
to ze swojego sandboxa za pomocą tokenów gita, które znajdują się **wyłącznie w klastrze runnerów**.
Płaszczyzna sterowania Back Office nie ma żadnych poświadczeń gita i nigdy nie wywołuje API SCM. Ten rozdział
opisuje konfigurację najmniejszych uprawnień (token tylko do odczytu i token do wypychania dla każdego
repozytorium), tworzenie tokenów w GitLabie lub GitHubie, ochronę gałęzi domyślnej tak, aby żaden token
agenta nie mógł scalić zmian, instalację tokenów w puli runnerów i ich rotację.

**Kogo to dotyczy:** administratora platformy lub operatora klastra runnerów; maintainera repozytoriów
w GitLabie/GitHubie.

<a id="git-overview"></a>
## Co agent robi z gitem

W każdym kroku **Spec Kit** lub **Implement** (oraz w próbach benchmarków kodowania) runner wewnątrz
sandboxa (`runner/entrypoint.py`):

1. Klonuje **URL repozytorium Git** (**Git URL**) projektu przez HTTPS (`git clone`), przełącza się na bazowy ref,
   pobiera lub tworzy gałąź uruchomienia (`git ls-remote`, `git fetch`), domyślnie `feature/<issue key>`.
2. Tworzy commity jako **Back Office Agent** `<agent@backoffice.local>`, chyba że ustawisz inną tożsamość;
   każdy commit zawiera trailer `Back-Office-Run: <runId>`, którego CI używa do sygnalizowania uruchomienia.
3. Wypycha gałąź, ale tylko wtedy, gdy **dostęp do gita** zadania to `PUSH_MR` (patrz niżej; `push` jest
   domyślnie włączone w konfiguracji zadania).
4. W krokach **Implement** z włączonym `mergeRequest` (domyślnie) dodaje push options GitLaba:
   `-o merge_request.create -o merge_request.target=<default branch> -o merge_request.title=...`
   (`description`, `draft`, jeśli skonfigurowano). GitLab otwiera lub aktualizuje merge request i wypisuje
   jego URL, który Back Office zapisuje w uruchomieniu. Runner **nigdy** nie wysyła opcji, które scalają
   (`merge_request.merge_when_pipeline_succeeds`, `merge_request.auto_merge`), usuwają gałąź źródłową lub
   pomijają CI, i nigdy nie wypycha z `--force`.

Uwierzytelnianie: wbudowany credential helper gita odpowiada nazwą użytkownika `oauth2` i tokenem odczytanym
z pliku `/var/run/backoffice/git/token`. Jest używany tylko przy clone, fetch, ls-remote oraz (w zadaniach
PUSH_MR) push; token nigdy nie jest zapisywany w `.git/config`, w środowisku agenta ani w logach.

Nic nie wywołuje API GitLaba ani GitHuba, więc żaden token nie potrzebuje zakresu API.

**Kilka repozytoriów.** Projekt może mieć kilka repozytoriów, a krok może używać kilku z nich
([Repozytoria kroku](workflows.md#step-repositories)). Runner wykonuje wtedy powyższe czynności dla każdego
repozytorium, w osobnym katalogu `/workspace/<klucz>`, z dostępem, jaki krok nadał temu repozytorium:
repozytoria z dostępem **Odczyt** są klonowane tokenem do odczytu i nigdy nie są wypychane, a repozytoria
z dostępem **Wypchnięcie i merge request** dostają token do wypychania, tę samą gałąź i własny merge request.
Zainstaluj tokeny do odczytu i do wypychania dla każdego repozytorium (lub grupy), którego używają kroki; pula
wybiera je osobno dla każdego repozytorium, dokładnie tak, jak opisano niżej. Zanim użyjesz kroków z kilkoma
repozytoriami, zaktualizuj obrazy runnerów i runner-controllery: starsze runnery klonują tylko główne
repozytorium, a starsze controllery wybierają jeden token, dla głównego repozytorium, na najwyższym poziomie
dostępu używanym przez krok.

**Ograniczenia**

- Plik tokenu znajduje się w kontenerze agenta: zdeterminowany agent mógłby go odczytać. Dlatego każde
  zadanie dostaje tylko potrzebne uprawnienia, ograniczone do jednego repozytorium (lub grupy), z krótkim
  czasem życia.
- **URL** każdego repozytorium projektu musi być adresem `https://`. Adresy SSH nie korzystają z tokenu.
- Merge requesty są tworzone tylko na GitLabie. Na innych hostach powstaje jedynie wypchnięta gałąź.

<a id="git-least-privilege"></a>
## Najmniejsze uprawnienia: token do odczytu i token do wypychania

Back Office wyznacza dla każdego zadania agenta **poziom dostępu do gita** i wysyła do puli runnerów tylko ten
poziom, nigdy sekret. Runner-controller wybiera odpowiedni token ze swojego klastra:

| Poziom | Które zadania | Token w sandboxie | Co robi runner |
|---|---|---|---|
| `NONE` | zadania bez repozytorium | brak | nic z gitem |
| `READ` | kroki z wyłączonym `push`, analiza Spec Kit bez wypychania, benchmarki kodowania | token **do odczytu** | tylko clone i fetch; wypychanie jest wyłączone (`remote.origin.pushurl` = `disabled://read-only-job`) i runner nigdy nie wypycha |
| `PUSH_MR` | kroki **Implement** i **Spec Kit** z włączonym `push` | token **do wypychania** | wypycha gałąź zadania i otwiera merge request przez push options; nigdy nie scala |

Gdzie to widać: w projektancie przepływów pole `push` węzła Implement lub Spec Kit podaje, jaki poziom daje;
w **Administracja** → **Flota runnerów** → **Zadania agentów** panel zadania pokazuje **Dostęp do gita**;
log kroku zawiera `[git] access READ: clone only, pushing is disabled`.

Co konfigurujesz dla każdego repozytorium (lub grupy):

1. **Token READ**: może klonować, nie może wypychać ([GitLab](#gitlab-read-token)).
2. **Token PUSH**: może tworzyć i wypychać gałęzie oraz otwierać merge requesty, nie może scalać do gałęzi
   domyślnej ([GitLab](#gitlab-push-token)).
3. **Chronioną gałąź domyślną**, aby token PUSH nie mógł scalić zmian bez względu na to, co wyśle
   ([GitLab](#gitlab-protect-branches)).
4. Oba tokeny jako Secrety w klastrze runnerów, przypisane do repozytorium w `gitCredentials.repoSecrets`
   ([Instalacja](#git-install)).

Jeśli zadanie READ znajdzie dla swojego repozytorium tylko token do wypychania (na przykład pula wciąż
skonfigurowana starszym pojedynczym `gitCredentials.existingSecret`), zadanie **kończy się błędem**
`policy-violation: git credential: READ job for <repository>, but ... has only a push credential`. Dodaj token
do odczytu albo ustaw `gitCredentials.allowPushCredentialForRead: true` jako świadomy, tymczasowy wyjątek.
Zadanie PUSH_MR, którego repozytorium ma tylko token do odczytu, kończy się błędem `... has no push credential`.

<a id="gitlab-token"></a>
## GitLab: jaki typ tokenu

Wybierz jeden typ tokenu, od najlepszego (UI GitLaba według stanu na 2026; jeśli menu się przesunęło,
sprawdź podlinkowaną dokumentację). Utwórz **dwa** tokeny tego typu dla każdego repozytorium lub grupy: jeden
dla READ, jeden dla PUSH.

| Token | Dostępny w | Obejmuje | Uwagi |
|---|---|---|---|
| **Project access token** | GitLab Self-Managed i Dedicated; GitLab.com Premium/Ultimate | jedno repozytorium | Tworzy użytkownika-bota, który jest członkiem tylko tego projektu. Najlepszy wybór |
| **Group access token** | jak wyżej | wszystkie projekty grupy | Dla puli obsługującej wiele repozytoriów jednej grupy |
| Personal access token **użytkownika-bota** | wszędzie, także GitLab.com Free | każdy projekt, którego bot jest członkiem | Utwórz dedykowanego użytkownika, dodaj go jako Developer do projektów i utwórz tokeny jako ten użytkownik |

Dokumentacja: project access tokens https://docs.gitlab.com/user/project/settings/project_access_tokens/,
group access tokens https://docs.gitlab.com/user/group/settings/group_access_tokens/, personal access tokens
https://docs.gitlab.com/user/profile/personal_access_tokens/.

<a id="gitlab-read-token"></a>
## GitLab: token READ (tylko klonowanie)

1. W GitLabie otwórz repozytorium → **Settings** → **Access tokens** → **Add new token**. Potrzebujesz roli
   Maintainer. (Grupa: **Group** → **Settings** → **Access tokens**.)
2. **Token name**: `back-office-read-<pula>`.
3. **Expiration date**: najwyżej **90 dni** od dziś.
4. **Select a role**: **Reporter**. Reporter może czytać kod, ale nie może wypychać do żadnej gałęzi.
5. **Select scopes**: tylko **read_repository**. Pozostaw niezaznaczone `api`, `read_api`, `write_repository`
   i wszystkie pozostałe.
6. **Create project access token** i skopiuj wartość (`glpat-...`); jest pokazywana tylko raz.

GitLab.com Free (bez project i group access tokens): zaloguj się jako użytkownik-bot (patrz niżej) → awatar →
**Edit profile** → **Access tokens** → **Add new token**, tylko zakres **read_repository**, ważność najwyżej
90 dni. Sam zakres czyni token tylko do odczytu, mimo że bot ma rolę Developer.

<a id="gitlab-push-token"></a>
## GitLab: token PUSH (gałąź, push, merge request; bez scalania)

1. Repozytorium → **Settings** → **Access tokens** → **Add new token** (lub access tokens grupy).
2. **Token name**: `back-office-push-<pula>`.
3. **Expiration date**: najwyżej **90 dni**.
4. **Select a role**: **Developer**. Developer może tworzyć i wypychać niechronione gałęzie oraz tworzyć merge
   requesty (także przez push options), ale nie może wypychać do ani scalać do chronionej gałęzi, która
   dopuszcza tylko Maintainerów. Nigdy nie używaj ról Maintainer ani Owner.
5. **Select scopes**: tylko **write_repository** (obejmuje odczyt repozytorium). **Bez** `api`, **bez**
   `read_api`: runner otwiera merge requesty przez push options gita, które nie wymagają zakresu API, a bez
   `api` token nie może przez API zatwierdzać, scalać ani zmieniać ustawień.
6. **Create project access token** i skopiuj wartość.

GitLab.com Free: utwórz dedykowanego użytkownika, np. `backoffice-bot`, dodaj go do każdego repozytorium
z rolą **Developer** (**Manage** → **Members** → **Invite members**), zaloguj się jako bot i utwórz personal
access token tylko z zakresem **write_repository**, ważny najwyżej 90 dni. Jako token READ użyj drugiego
personal access tokenu tego samego bota, tylko z zakresem **read_repository**.

**Sprawdź ustawienia repozytorium**

- Gałęzie uruchomień (`feature/...`) nie mogą być chronione przed wypychaniem przez Developerów.
- Push rules wymagające, aby autor commita był użytkownikiem GitLaba, albo podpisanych commitów, odrzucają
  commity agenta. Ustaw tożsamość commitów na e-mail bota (patrz [Instalacja](#git-install)) albo złagodź regułę.
- Gałąź docelowa (**Default branch** projektu w Back Office) musi istnieć.

<a id="gitlab-protect-branches"></a>
## GitLab: uniemożliw scalanie tokenem agenta

Runner nigdy nie prosi GitLaba o scalenie. Te ustawienia gwarantują, że nie zrobi tego nawet wykradziony
token PUSH:

1. Repozytorium → **Settings** → **Repository** → **Protected branches** → **Expand**. Dokumentacja:
   https://docs.gitlab.com/user/project/repository/branches/protected/
2. Dla gałęzi domyślnej (`main`; jeśli jej brakuje, dodaj ją przez **Add protected branch**):
   - **Allowed to merge**: **Maintainers**;
   - **Allowed to push and merge**: **No one** (lub **Maintainers**);
   - **Allowed to force push**: wyłączone;
   - **Require approval from code owners**: włączone, jeśli używasz pliku `CODEOWNERS`.
   Tak samo chroń gałęzie wydań (`release/*`).
3. Zalecane: **Settings** → **Merge requests** → **Merge checks** → włącz **Pipelines must succeed**
   i **All threads must be resolved**.
4. GitLab Premium/Ultimate: **Settings** → **Merge requests** → **Merge request approvals** → dodaj regułę
   zatwierdzania z co najmniej 1 zatwierdzeniem przez ludzi; w **Approval settings** zaznacz **Prevent
   approval by merge request creator** i **Prevent approvals by users who add commits**. Wtedy użytkownik-bot
   agenta nie może też zatwierdzić własnego merge requestu.
5. Dla nowych projektów grupy: **Group** → **Settings** → **Repository** → **Default branch** → **Initial
   default branch protection**: **Fully protected**.

Dlaczego to wystarcza: token PUSH ma rolę **Developer**, a gałąź domyślna dopuszcza scalanie tylko
**Maintainerom**. GitLab odmawia scalenia Developerowi, niezależnie od tego, czy próba przychodzi z UI, z API,
czy z push option, takiej jak `merge_request.merge_when_pipeline_succeeds`. Token nie ma zakresu `api`, więc
nie może też zmienić tych ustawień ani niczego zatwierdzić.

<a id="github-token"></a>
## GitHub: utwórz tokeny

Użyj **fine-grained personal access tokens** ograniczonych do repozytoriów, na których pracuje pula,
utworzonych przez dedykowanego użytkownika maszynowego (zalecane).

1. Zaloguj się jako użytkownik maszynowy → **Settings** → **Developer settings** → **Personal access tokens** →
   **Fine-grained tokens** → **Generate new token**.
   Dokumentacja: https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens
2. **Resource owner**: organizacja, do której należy repozytorium (organizacja może wymagać zatwierdzania
   fine-grained tokenów; właściciel zatwierdza w **Settings** → **Personal access tokens** → **Pending requests**).
3. **Expiration**: 90 dni lub mniej.
4. **Repository access**: **Only select repositories**, wybierz repozytorium.
5. **Repository permissions**:
   - token READ: **Contents: Read-only** (**Metadata: Read-only** jest dodawane automatycznie). Nic więcej.
   - token PUSH: **Contents: Read and write**. Dodaj **Workflows: Read and write** tylko wtedy, gdy agenci
     mogą zmieniać pliki w `.github/workflows/`; bez tego GitHub odrzuca takie wypchnięcia. Bez **Pull
     requests**, **Administration** i innych uprawnień.
6. **Generate token** i skopiuj go (`github_pat_...`).

GitHub akceptuje token jako hasło HTTPS z nazwą użytkownika `oauth2`, którą wysyła runner.

**Uniemożliw scalanie na GitHubie.** Contents write pozwala tokenowi wypychać do dowolnej **niechronionej**
gałęzi lub scalać do niej przez gita albo API, więc chroń gałąź domyślną: repozytorium → **Settings** →
**Rules** → **Rulesets** → **New branch ruleset** (lub **Branches** → **Add branch protection rule**), cel:
gałąź domyślna, włącz **Restrict deletions**, **Require a pull request before merging** z co najmniej 1
wymaganym zatwierdzeniem, **Require status checks to pass**, **Block force pushes** i nie dodawaj
użytkownika maszynowego do listy bypass. Użytkownik maszynowy nie może wtedy wypychać do ani scalać do gałęzi
domyślnej; GitHub nigdy też nie pozwala autorowi pull requestu go zatwierdzić.

**Pull requesty na GitHubie.** GitHub ignoruje push options GitLaba, więc agent wypycha gałąź, a uruchomienie
nie ma URL-a merge requestu. Zamiast tego:

1. W projektancie przepływów zaznacz węzeł **Implement** i ustaw w konfiguracji zadania `mergeRequest`
   na `false` (pozostaw włączone `push`) albo wprowadź zmianę w kopii szablonu na poziomie projektu
   ([task-library.md](task-library.md#templates)).
2. Otwieraj pull request z GitHub Actions przy wypchnięciu gałęzi `feature/**` (na przykład przez
   `gh pr create --fill`, co wymaga `pull-requests: write` dla `GITHUB_TOKEN` workflow) albo ręcznie.

Inne hosty (Gitea, Bitbucket, Azure Repos) działają tak samo jak GitHub: token tylko do odczytu i token
z prawem zapisu do repozytorium używane jako hasła HTTPS, ochrona gałęzi domyślnej, `mergeRequest` ustawione
na `false`.

<a id="git-project-tokens"></a>
## Tokeny dostępu do repozytoriów w projekcie

Od ADR 0051 projekt sam przechowuje tokeny swoich repozytoriów, więc nie trzeba ich instalować w każdym
runnerze. Właściciel projektu dodaje je na zakładce **Źródła** projektu, w karcie **Tokeny dostępu do
repozytoriów**. Zadania klonują i wypychają za ich pomocą: dla każdego repozytorium krok czyta najmniej uprzywilejowanym
zweryfikowanym tokenem tylko do odczytu (GIT_READ), a wypycha tokenem PUSH_BRANCH. Back Office przekazuje je
runnerowi, który wykonuje zadanie, zaszyfrowane do jego własnego klucza, raz na zadanie, tuż przed startem
sandboxa; serwer proxy, który po drodze zagląda w ruch TLS, widzi tylko szyfrogram. Repozytorium bez pasującego
tokenu korzysta z tokenów puli runnerów opisanych niżej (wycofywane; dziennik zadania mówi, którego użyto).

<a id="git-agent-tools"></a>
**Narzędzia agenta.** Krok kodujący może dać swojemu agentowi narzędzia tylko do odczytu w polu konfiguracji
**agentTools**. `gitlab-read` daje agentowi `glab`, czyli CLI GitLaba, z tokenem repozytorium zadeklarowanym jako
tylko do odczytu (CI_READ) i zweryfikowanym jako pozbawiony uprawnień do zapisu: agent może czytać pipeline'y, logi
jobów, merge requesty i issues, na przykład żeby przeanalizować nieudany pipeline, i niczego nie może zmienić. Bez
takiego tokenu dla głównego repozytorium kroku krok od razu kończy się błędem z informacją, jaki token dodać. Token
trafia tylko do agenta tego kroku; jest maskowany we wszystkich logach i artefaktach, a dziennik zadania wymienia
narzędzie.

- **Nigdy token do wypychania do czytania.** Krok, który tylko czyta, dostaje wyłącznie token zadeklarowany
  i zweryfikowany jako tylko do odczytu; gdy dla tego repozytorium jest tylko token do wypychania, używa puli.

- **Jeden token na poziom uprawnień.** Repozytorium może mieć kilka tokenów. Każdy deklaruje, do czego służy:

  | Uprawnienie | Do czego używa go Back Office | Czego wymaga GitLab |
  |---|---|---|
  | GIT_READ | klonowanie repozytorium | `read_repository`, rola Reporter |
  | CI_READ | odczyt pipeline'ów i logów jobów | `read_api`, rola Reporter |
  | PUSH_BRANCH | wypchnięcie gałęzi agenta | `write_repository`, rola Developer |
  | MR_WRITE | tworzenie i zmiana merge requestów | `api`, rola Developer |
  | MR_COMMENT | komentarze z przeglądu | `api`, rola Reporter |
  | PIPELINE_RUN | ponowne uruchomienie lub start pipeline'ów | `api`, rola Developer |

  Uprawnienia do odczytu (GIT_READ, CI_READ) mogą później trafić do agenta jako narzędzie, ale tylko gdy token
  jest zweryfikowany jako tylko do odczytu. Uprawnień do zapisu używa wyłącznie Back Office, nigdy agent.
- **Typowa konfiguracja:** token dostępu projektu z rolą Reporter i zakresami `read_repository` oraz `read_api`
  (GIT_READ, CI_READ) oraz token z rolą Developer i zakresem `write_repository` (PUSH_BRANCH). Nigdy nie używaj
  roli Maintainer: może scalać.
- **Testowany przy zapisie.** **Testuj przed zapisaniem**, każdy zapis i **Testuj ponownie** pytają GitLaba, co
  token potrafi: klonować i wypychać (przez git), jakie ma zakresy i datę ważności, jaką ma rolę w projekcie i czy
  gałąź domyślna jest chroniona. Wynik pokazuje nadane i brakujące uprawnienia oraz **uprawnienia do zapisu**
  ponad zadeklarowane, na przykład „git push”, „api scope” albo „merge into main”. Brak uprawnienia oznacza test
  nieudany; nadmiar uprawnień, niechroniona gałąź domyślna albo rola Maintainer dają ostrzeżenie. Test tylko czyta.
- **Nigdy więcej nie jest pokazywany.** Token jest szyfrowany kluczem instalacji (tak jak tokeny Jiry) i nigdy nie
  jest zwracany. Listę widzi każdy, kto widzi projekt; tokeny dodają, zmieniają, testują i usuwają tylko
  właściciele. Każda zmiana trafia do audytu (`SCM_CONNECTION_*`).
- **Ważność.** Bez podanej daty ważności używana jest ta, którą zgłasza GitLab; tokeny bliskie wygaśnięcia są
  wyróżnione.
- Token, którego repozytorium usunięto z projektu, jest oznaczony jako **osierocony** i nigdy nie jest używany.

<a id="git-install"></a>
## Zainstaluj tokeny w puli runnerów

Tokeny trafiają do klastra, w którym działa runner-controller puli, nigdy do Back Office.

**Kubernetes (chart `back-office-runner`)**

1. Ustal przestrzeń nazw sandboxów: domyślnie `<release namespace>-sandboxes`, na przykład
   `bo-runner-eu-1-sandboxes`, jeśli instalowałeś poleceniem z **Dodaj runner**. Przestrzeń nazw tworzy chart;
   utwórz Secrety po pierwszej instalacji albo utwórz przestrzeń nazw wcześniej i ustaw
   `sandboxNamespace.create=false`.
2. Utwórz po jednym Secrecie na token z kluczem `token` w **tej** przestrzeni nazw. Wczytaj wartość bez
   wyświetlania, aby nie trafiła do historii powłoki:

   ```bash
   NS=bo-runner-eu-1-sandboxes
   read -rs TOKEN   # wklej token READ (glpat-...), Enter
   kubectl -n "$NS" create secret generic git-shop-api-read --from-literal=token="$TOKEN"
   read -rs TOKEN   # wklej token PUSH, Enter
   kubectl -n "$NS" create secret generic git-shop-api-push --from-literal=token="$TOKEN"
   unset TOKEN
   kubectl -n "$NS" get secret git-shop-api-read git-shop-api-push
   ```

3. Przypisz je do repozytorium w pliku wartości puli i wykonaj upgrade:

   ```yaml
   gitCredentials:
     repoSecrets:
       # jedno repozytorium (host/ścieżka z Git URL projektu, bez .git)
       - repository: gitlab.example.com/shop/api
         read: git-shop-api-read
         push: git-shop-api-push
       # wszystkie repozytoria grupy (group access tokens)
       - repository: gitlab.example.com/shop/*
         read: git-shop-read
         push: git-shop-push
     # tożsamość commitów, np. adres użytkownika-bota (push rules)
     userName: Back Office Agent
     userEmail: backoffice-bot@example.com
   ```

   `helm upgrade --install bo-runner-eu-1 deploy/helm/back-office-runner -n bo-runner-eu-1 -f runner-eu-1.values.yaml`

| Wartość Helm | Znaczenie |
|---|---|
| `gitCredentials.repoSecrets` | lista `{repository, read, push}`. `repository`: `host/grupa/projekt`, `host/grupa/*`, `host`, `*.example.com` lub `*`; wygrywa najbardziej szczegółowy wpis. `read` = Secret dla zadań READ, `push` = Secret dla zadań PUSH_MR; każdy z nich można pominąć |
| `gitCredentials.allowPushCredentialForRead` | `false` (pozostaw): zadanie READ, które znajdzie tylko Secret do wypychania, kończy się błędem zamiast go dostać |
| `gitCredentials.existingSecret` / `hostSecrets` | starsza konfiguracja z jednym tokenem, traktowana jako tokeny **push** dla każdego repozytorium (lub hosta) bez wpisu w `repoSecrets`; controller loguje ostrzeżenie. Przejdź na `repoSecrets` |
| `gitCredentials.userName` / `gitCredentials.userEmail` | tożsamość commitów (`RUNNER_GIT_USER_NAME` / `RUNNER_GIT_USER_EMAIL`); puste = `Back Office Agent` / `agent@backoffice.local` |

4. Controller montuje w każdym sandboxie tylko Secret poziomu danego zadania (sandboxy z puli ciepłej
   dostają kopię w pamięci) pod `/var/run/backoffice/git/token`; zadania READ nigdy nie widzą tokenu PUSH.
   Repozytoria są dopasowywane po hoście i ścieżce **Git URL** projektu (małymi literami, bez portu i `.git`).
5. Host gita jest zawsze dodawany do listy dozwolonego ruchu wychodzącego zadania. Self-hosted GitLab pod
   adresem prywatnym wymaga też `egressProxy.allowedCidrs` (patrz [agent-execution.md](agent-execution.md#network)).

**Przejście z jednego tokenu** (`existingSecret` / `hostSecrets`): zadania implementacji działają dalej;
zadania READ (benchmarki, kroki z wyłączonym `push`) kończą się błędem `git credential`, dopóki nie dodasz
Secretu do odczytu w `repoSecrets`. Log startowy controllera wymienia zastępcze tokeny push, których nadal używa.

**Docker Compose (laptop, profil `full`)**: ustaw `GIT_TOKEN_READ` i `GIT_TOKEN_PUSH` (opcjonalnie
`GIT_USER_NAME`, `GIT_USER_EMAIL`) w `deploy/compose/.env`. Compose przekazuje je do runner-controllera jako
`BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH`, a ten zapisuje w każdym sandboxie ten, który odpowiada poziomowi
zadania. Starszy `GIT_TOKEN` (`BO_GIT_TOKEN`) nadal działa jako token push.
`docker compose --profile full up -d runner-controller`.

**Sprawdź działanie**

1. Uruchom przepływ z krokiem **Implement** na małym zgłoszeniu.
2. Na stronie uruchomienia log kroku pokazuje linie `[git] remote: ...`; na GitLabie ostatnia zawiera URL
   merge requestu, który pojawia się też w uruchomieniu. Panel zadania agenta pokazuje **Dostęp do gita**
   `PUSH_MR`.
3. Uruchom benchmark kodowania (lub krok z wyłączonym `push`): jego zadanie pokazuje **Dostęp do gita**
   `READ`, log zawiera `[git] access READ: clone only, pushing is disabled` i nic nie jest wypychane.
4. Błędy brzmią `git clone failed` (zły token lub URL, ruch wychodzący), `git push failed (...)` (uprawnienia,
   chroniona gałąź, push rules), `the receiving end does not support push options` (to nie GitLab: wyłącz
   `mergeRequest`) albo `policy-violation: git credential: ...` (brak Secretu do odczytu lub do wypychania dla
   tego repozytorium).

<a id="git-multi-repo"></a>
## Kilka repozytoriów w jednym zadaniu

Zadanie może pracować z kilkoma repozytoriami naraz, na przykład z usługą, którą zmienia, i z repozytorium
dokumentacji, które tylko czyta, albo z biblioteką i aplikacją, która jej używa. Każde repozytorium zadania ma
**własny** poziom dostępu, a sandbox dostaje osobny token dla każdego repozytorium, dobrany tylko do tego
repozytorium i tego poziomu:

| Repozytorium w zadaniu | Dostęp | Token, który dostaje sandbox | Co się dzieje |
|---|---|---|---|
| `src` (`gitlab.example.com/shop/api`, główne) | `PUSH_MR` | token **push** dla `shop/api` | tu pracuje agent; gałąź zadania jest wypychana, otwiera się merge request |
| `docs` (`gitlab.example.com/shop/docs`) | `READ` | token **read** dla `shop/docs` | klonowane do wglądu; lokalne zmiany są raportowane, nigdy wypychane (`remote.origin.pushurl` wyłączony) |
| `tools` (publiczne repozytorium) | `NONE` | żaden | klonowane anonimowo |

Układ sandboxa: każde repozytorium jest klonowane do `/workspace/<path>` (domyślnie jego klucz). Agent,
`setupCommand`, `verifyCommand`, Spec Kit i skille działają w repozytorium **głównym** (primary); pozostałe
leżą obok (`../docs`) i agent może je edytować. Prompt agenta kończy się krótką listą **Repositories** (klucz,
ścieżka, dostęp, cel). Zadanie bez repozytoriów startuje w pustym `/workspace`.

Tokeny instalujesz tak samo jak dla jednego repozytorium ([Instalacja](#git-install)): jeden wpis `repoSecrets`
na repozytorium (lub grupę), z Secretem dla każdego poziomu, z jakim repozytorium jest używane. Typowa
konfiguracja z repozytorium dokumentacji tylko do odczytu i repozytoriami kodu z prawem wypychania:

```yaml
gitCredentials:
  repoSecrets:
    # dokumentacja: agenci tylko ją czytają, więc celowo nie ma Secretu push
    - repository: gitlab.example.com/shop/docs
      read: git-shop-docs-read        # project access token: Reporter, read_repository
    # repozytoria kodu w grupie: odczyt dla przeglądów, push dla implementacji
    - repository: gitlab.example.com/shop/*
      read: git-shop-read             # group access token: Reporter, read_repository
      push: git-shop-push             # group access token: Developer, write_repository
```

Bardziej szczegółowy wpis `shop/docs` wygrywa z `shop/*`, więc repozytorium dokumentacji nigdy nie dostanie
tokenu push grupy. Zadanie, które by go potrzebowało, kończy się błędem, zanim powstanie sandbox, z nazwą
repozytorium: `policy-violation: repository 'docs' (gitlab.example.com/shop/docs): git credential: PUSH_MR job
for ..., but ... has no push credential`. Tak samo repozytorium READ, które ma tylko Secret push (chyba że
`allowPushCredentialForRead`).

W sandboxie każdy token leży w osobnym pliku (`/var/run/backoffice/git/<klucz>/token`), jest czytany raz
i usuwany przed startem agenta, a credential helper podaje go wyłącznie dla hosta **i** ścieżki własnego
repozytorium: token dokumentacji nigdy nie trafi do repozytorium kodu na tym samym GitLabie i odwrotnie.
Hosty wszystkich repozytoriów są dodawane do listy dozwolonego ruchu wychodzącego zadania.

Wyniki: uruchomienie pokazuje osobny wpis dla każdego repozytorium (gałąź, commit, zmienione pliki, URL merge
requestu, czy wypchnięto, błąd). Nieudane wypchnięcie jednego repozytorium nie ukrywa wyników pozostałych;
zadanie jest oznaczane jako nieudane, gdy zawiodło repozytorium `PUSH_MR`, a błąd je wskazuje, np.
`repository src: push failed: ...`.

**Docker Compose**: `GIT_TOKEN_READ` i `GIT_TOKEN_PUSH` obowiązują dla wszystkich repozytoriów; każde
repozytorium zadania dostaje ten, który odpowiada jego poziomowi.

<a id="git-rotate"></a>
## Rotacja tokenów

Rotuj oba tokeny przed wygaśnięciem (najwyżej co 90 dni); dodaj przypomnienie w kalendarzu albo korzystaj
z e-maili GitLaba o wygasaniu.

1. Utwórz nowy token jak wyżej (GitLab oferuje też **Rotate** w wierszu tokenu, co wydaje nową wartość
   i od razu unieważnia starą: używaj tego tylko wtedy, gdy możesz natychmiast zaktualizować Secret).
2. Podmień wartość Secretu:
   `read -rs TOKEN; kubectl -n <ns>-sandboxes create secret generic git-shop-api-push --from-literal=token="$TOKEN" --dry-run=client -o yaml | kubectl apply -f -; unset TOKEN`
3. Nic innego nie wymaga restartu: każdy nowy sandbox odczytuje Secret przy starcie. Zadania już działające
   zachowują stary token, więc poczekaj, aż **Administracja** → **Flota runnerów** → **Zadania agentów** nie
   będzie pokazywać żadnego uruchomionego zadania w puli.
4. Unieważnij stary token w GitLabie lub GitHubie. Powtórz dla tokenu READ.

W Docker Compose zmień `GIT_TOKEN_READ` / `GIT_TOKEN_PUSH` w `.env` i odtwórz kontener runner-controllera.
