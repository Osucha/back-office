# GitLab CI i inne CI

To pipeline Twojego repozytorium wywołuje Back Office: prosi o przegląd kodu merge requestu przez AI,
uruchamia dowolny workflow zmapowany na punkt wejścia i raportuje wyniki testów do uruchomienia, które czeka
na CI. Back Office nigdy nie wywołuje API GitLaba, GitHuba ani żadnego innego SCM; każda interakcja zaczyna
się w Twoim CI z projektowym tokenem CI.

**Kogo to dotyczy:** właściciela lub maintainera projektu (token, punkty wejścia) oraz osoby odpowiedzialnej
za konfigurację CI repozytorium (zmienne, `.gitlab-ci.yml`).

<a id="ci-overview"></a>
## Jak działa integracja z CI

```
your CI job                                Back Office
backoffice-ci.sh review  ── POST /api/v1/ci/executions ──▶ entrypoint "code-review" → workflow run
     │  (CI context + merge request diff)                   │ review task, decision, END (PASS/FAIL)
     │◀── poll GET /api/v1/ci/executions/{id} ──────────────┘
     └── exit 0 / 1 / 2 / 3

your test job ── backoffice-ci.sh signal tests ── POST /api/v1/ci/signals ──▶ resumes the "Wait for CI" node
```

- Klient to jeden skrypt powłoki POSIX serwowany przez samo Back Office pod adresem
  `<public URL>/ci/backoffice-ci.sh` (wymaga tylko `sh`, `curl`, `git`, `awk`). **Ustawienia** (**Settings**) →
  **Skrypt klienta CI** (**CI client script**) pokazuje URL i wersję.
- **Punkt wejścia** (entrypoint) mapuje klucz używany przez CI (na przykład `code-review`) na workflow
  projektu, z trybem: **BLOCKING** (niezaliczona bramka powoduje błąd zadania CI) lub **ADVISORY** (zadanie
  tylko raportuje).
- Wynik bramki to wynik węzła `END` workflow, więc dowolny workflow może pełnić rolę bramki.
- Wykonania są idempotentne w obrębie projektu, punktu wejścia, commita i merge requestu przez 24 h: ponowienie
  zadania zwraca to samo wykonanie (`--var force=true` wymusza nowe).
- Wyniki pojawiają się w **CI** (`/ci`) oraz w zakładce **CI** projektu w sekcji **Wykonania CI**
  (**CI executions**).

**Ograniczenia**

- Back Office niczego nie publikuje w merge requeście (nie ma poświadczeń SCM). Raport jest w logu zadania
  i na stronie uruchomienia podlinkowanej jako `Run:`.
- Przesyłany diff jest ograniczony do 2 MB (`BACKOFFICE_DIFF_MAX_BYTES`); większe diffy są obcinane.

<a id="ci-token"></a>
## Utwórz projektowy token CI

Token CI (`bo_ci_` + 40 losowych znaków) to token bearer, który ma dokładnie dwa uprawnienia w jednym
projekcie: `ci.execute` (uruchamianie wykonań punktów wejścia projektu i ich odpytywanie) oraz `ci.signal`
(wysyłanie sygnałów). Nie może odczytać projektu, jego uruchomień ani niczego innego, a ludzie nie mogą
używać tych endpointów ze swoją sesją.

1. Otwórz projekt i wybierz zakładkę **CI**. Potrzebujesz `project.ci.manage` (role projektowe **owner**
   i **maintainer**).
2. Na karcie **Tokeny CI** (**CI tokens**) naciśnij **Utwórz token** (**Create token**).
3. Wpisz **Nazwę tokenu** (**Token name**), która mówi, gdzie będzie używany, na przykład `gitlab-ci-shop`
   lub `github-actions-shop`, i naciśnij **Utwórz** (**Create**).
4. Od razu skopiuj token z okna **Utworzono token** (**Token created**): jest wyświetlany tylko raz
   i przechowywany wyłącznie jako skrót SHA-256. Naciśnij **Gotowe** (**Done**).
5. Zmapuj punkty wejścia na karcie **Punkty wejścia CI** (**CI entrypoints**): **Dodaj** (**Add**), klucz
   `code-review`, workflow przeglądu, tryb **BLOCKING** lub **ADVISORY**, a następnie **Zapisz punkty wejścia**
   (**Save entrypoints**) (wymaga `project.manage`).

Utworzenie i unieważnienie trafiają do dziennika audytu (`CI_TOKEN_CREATE`, `CI_TOKEN_REVOKE`). Twórz jeden
token na repozytorium lub na system CI, aby móc unieważnić jeden bez psucia pozostałych; kolumna
**Ostatnie użycie** (**Last used**) mówi, czy token jest nadal w użyciu.

**Rotacja** (tokeny CI nie wygasają, więc rotuj je według harmonogramu, na przykład co rok, oraz zawsze,
gdy odchodzi ktoś z dostępem do zmiennych CI): utwórz drugi token, podmień zmienną CI, uruchom pipeline,
sprawdź, że **Ostatnie użycie** przeszło na nowy token, a następnie naciśnij **Unieważnij** (**Revoke**) przy
starym. Unieważnienie działa natychmiast.

<a id="ci-variables"></a>
## Ustaw zmienne CI/CD

| Zmienna | Wymagana | Wartość |
|---|---|---|
| `BACKOFFICE_URL` | tak | Publiczny URL Back Office osiągalny z Twoich runnerów CI, na przykład `https://backoffice.example.com` |
| `BACKOFFICE_CI_TOKEN` | tak | Token `bo_ci_...` |
| `BACKOFFICE_REVIEW_TIMEOUT` | nie | Liczba sekund oczekiwania na uruchomienie (domyślnie `1800`) |
| `BACKOFFICE_POLL_INTERVAL` | nie | Liczba sekund między odpytaniami (domyślnie `10`) |
| `BACKOFFICE_ADVISORY` | nie | `true`: nigdy nie kończ zadania błędem, niezależnie od trybu punktu wejścia czy błędu |
| `BACKOFFICE_DIFF_MAX_BYTES` | nie | Limit przesyłanego diffu (domyślnie `2097152`) |
| `BACKOFFICE_IMAGE` | nie | Obraz zadania z `sh`, `curl`, `git` (domyślnie `alpine:3.20`, który je doinstalowuje) |
| `BACKOFFICE_SCRIPT_URL` | nie | Skąd pobrać klienta (domyślnie `$BACKOFFICE_URL/ci/backoffice-ci.sh`) |
| `BACKOFFICE_TESTS_JOB` | nie | Nazwa Twojego zadania testowego, pokazywana w komunikacie sygnału |

W GitLabie (stan na 2026; dokumentacja: https://docs.gitlab.com/ci/variables/):

1. Otwórz projekt repozytorium → **Settings** → **CI/CD** → **Variables** → **Add variable**. Potrzebujesz
   roli Maintainer w projekcie GitLaba. Zmienna na poziomie grupy (**Group** → **Settings** → **CI/CD**)
   też działa, gdy kilka repozytoriów współdzieli jeden projekt Back Office.
2. **Key** `BACKOFFICE_CI_TOKEN`, **Value** – token.
3. **Visibility**: **Masked and hidden** (wartości nie da się już nigdy odsłonić w UI) lub **Masked**.
   Tokeny `bo_ci_` spełniają reguły maskowania GitLaba.
4. **Flags**: pozostaw **Protect variable** **niezaznaczone**. GitLab przekazuje zmienne chronione tylko do
   pipeline’ów chronionych gałęzi i tagów, a zadanie przeglądu działa w pipeline’ach merge requestów gałęzi
   funkcjonalnych, natomiast zadania sygnałów działają na gałęziach wypychanych przez agentów. Chronionego
   tokenu po prostu tam nie ma i zadanie zatrzymuje się z `BACKOFFICE_CI_TOKEN is required`. Pozostaw
   **Expand variable reference** niezaznaczone.
5. W ten sam sposób dodaj `BACKOFFICE_URL` (widoczność **Visible**).

Chroń token innymi środkami: ogranicz, kto ma rolę Maintainer w projekcie GitLaba, i pamiętaj, że token CI
może jedynie uruchamiać punkty wejścia tego projektu i wysyłać sygnały.

<a id="ci-template"></a>
## Dodaj szablon GitLaba

Plik repozytorium `ci-templates/backoffice.gitlab-ci.yml` zawiera gotowy szablon. Dołącz go z projektu,
w którym znajduje się Back Office, lub skopiuj:

```yaml
include:
  - project: 'mos/back-office'
    ref: main
    file: '/ci-templates/backoffice.gitlab-ci.yml'

# optional
backoffice:review:
  variables:
    BACKOFFICE_ADVISORY: "true"          # report only
  # allow_failure: { exit_codes: [1] }   # tolerate a failed gate, still fail on errors (2) and timeouts (3)
```

Zakładka **CI** projektu generuje też **Fragment GitLab CI** (**GitLab CI snippet**) z jednym zadaniem na
każdy punkt wejścia i jednym zadaniem sygnału na każdy sygnał `Wait for CI` używany przez workflowy projektu;
**Kopiuj** (**Copy**) go do `.gitlab-ci.yml`.

| Zadanie | Kiedy działa | Co robi |
|---|---|---|
| `backoffice:review` | pipeline’y merge requestów (`CI_PIPELINE_SOURCE == "merge_request_event"`), etap `test` | `backoffice-ci.sh review` |
| `backoffice:signal-tests` | pipeline’y gałęzi, których commit HEAD ma trailer `Back-Office-Run:`, etap `.post`, przy sukcesie | `signal tests --status success` |
| `backoffice:signal-tests-failed` | jak wyżej, przy błędzie | `signal tests --status fail` |

Szablon ustawia `GIT_DEPTH: "0"`, aby baza scalenia z gałęzią docelową była dostępna dla diffu. Zadania
sygnałów kończą się kodem 0 bez żadnych wywołań na gałęziach wypychanych przez ludzi (brak trailera).

**Kody wyjścia skryptu**

| Kod | Znaczenie |
|---|---|
| `0` | bramka **PASS** albo tryb **ADVISORY**, albo `BACKOFFICE_ADVISORY=true`; dla `signal`: dostarczono (lub nic nie czekało) |
| `1` | bramka **FAIL** (lub wykonanie zakończyło się błędem bez bramki) w trybie **BLOCKING** |
| `2` | błąd transportu, `401`/`403`, nieznany punkt wejścia (`404`) lub błąd użycia |
| `3` | upłynął `BACKOFFICE_REVIEW_TIMEOUT`, zanim uruchomienie się zakończyło |

**Przetestuj**

1. Otwórz merge request; zadanie `backoffice:review` wypisze raport z `Gate: PASS` lub `FAIL` oraz linkiem
   `Run:`.
2. W Back Office wykonanie pojawi się w **CI**, a **Ostatnie użycie** przy tokenie się zmieni.
3. Z laptopa: `export BACKOFFICE_URL=... BACKOFFICE_CI_TOKEN=bo_ci_...`, pobierz skrypt i uruchom
   `sh backoffice-ci.sh run code-review --wait` w checkoucie gałęzi funkcjonalnej.

<a id="ci-other"></a>
## GitHub Actions i inne systemy CI

Ten sam skrypt działa bez zmian w każdym CI, które potrafi uruchomić `sh`. Rozpoznaje GitLaba (zmienne `CI_*`)
i GitHub Actions (`GITHUB_*` oraz payload zdarzenia); wszędzie indziej zgłasza dostawcę `OTHER` i odczytuje
commit oraz gałąź z gita.

**GitHub Actions**

1. Repozytorium → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**: nazwa
   `BACKOFFICE_CI_TOKEN`, wartość – token. Na zakładce **Variables** dodaj `BACKOFFICE_URL`. Sekrety
   środowisk (environment secrets) też działają, jeśli zadanie deklaruje środowisko.
   Dokumentacja: https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions
2. Dodaj workflow:

```yaml
on:
  pull_request:
jobs:
  review:
    runs-on: ubuntu-latest
    env:
      BACKOFFICE_URL: ${{ vars.BACKOFFICE_URL }}
      BACKOFFICE_CI_TOKEN: ${{ secrets.BACKOFFICE_CI_TOKEN }}
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - run: curl -fsSL "$BACKOFFICE_URL/ci/backoffice-ci.sh" -o backoffice-ci.sh
      - run: sh backoffice-ci.sh review
```

Sekrety nie są przekazywane do workflowów wyzwalanych z forków; przeglądy pull requestów z forków wymagają
innego wyzwalacza, który należy starannie rozważyć, bo token zostałby wystawiony na działanie kodu z forka.

**Inne CI (Jenkins, Bitbucket Pipelines, Azure Pipelines, ...)**: zapisz token w magazynie sekretów CI,
wyeksportuj go jako `BACKOFFICE_CI_TOKEN` dla kroku, zrób checkout z pełną historią, a następnie uruchom
`sh backoffice-ci.sh run <entrypoint> --wait --diff auto`. Kody wyjścia interpretuj jak powyżej.

**Sygnały z Twojego własnego zadania testowego** (dowolne CI):

```sh
if git log -1 --format=%B | grep -q "^Back-Office-Run:"; then
  curl -fsSL "$BACKOFFICE_URL/ci/backoffice-ci.sh" -o backoffice-ci.sh
  sh backoffice-ci.sh signal tests --status success --message "$JOB_URL"
fi
```

Użyj `--run <runId>`, gdy commit stracił trailer (squash merge, commit po rebase).

Agenci otwierają merge requesty przez push options GitLaba; GitHub je ignoruje, zobacz
[integrations-git.md](integrations-git.md#github-token). Więcej objawów: [troubleshooting.md](troubleshooting.md#ci).
Pełna dokumentacja referencyjna: `docs/guides/ci-integration.md`.
