# Rozwiązywanie problemów

Objawy, ich typowe przyczyny i rozwiązania, w skrócie na podstawie runbooków z `docs/runbooks/`. Zacznij od
dziennika audytu (**Administracja** (**Administration**) → **Dziennik audytu** (**Audit log**), wyszukaj po
`X-Correlation-Id` z nieudanej odpowiedzi) i logów API (`kubectl -n back-office logs deploy/back-office-api`).

**Kogo to dotyczy:** administratora platformy, właściciela projektu, każdego, komu nie działa uruchomienie
lub logowanie.

<a id="login"></a>
## Logowanie i dostęp

| Objaw | Przyczyna | Rozwiązanie |
|---|---|---|
| `/login?error=not-invited` | dostawca uwierzytelnił osobę, ale nie pasuje żadne zaproszenie, dozwolona domena ani otwarta rejestracja | **Użytkownicy** (**Users**) → **Zaproś użytkownika** (**Invite user**), dodaj domenę w **Ustawienia platformy** (**Platform settings**) → **Rejestracja** (**Registration**) albo dla pierwszego administratora ustaw `BACKOFFICE_AUTH_BOOTSTRAP_ADMINS` i zrestartuj |
| `error=email-not-verified` | dostawca wysłał `email_verified=false` | zweryfikuj adres u dostawcy (Keycloak: użytkownik → **Email verified**) |
| `error=email-missing` | brak claimu `email` | zażądaj zakresu `email`; Entra ID: dodaj opcjonalny claim `email` ([integrations-identity.md](integrations-identity.md#entra)) |
| `error=identity-conflict` | e-mail jest już powiązany z inną tożsamością u dostawcy (użytkownik utworzony ponownie, zmiana dostawcy) | DBA czyści powiązanie: `issuer`, `subject` na null, status `INVITED` (`docs/runbooks/cannot-log-in.md`) |
| `error=deactivated` | konto dezaktywowane | **Użytkownicy** → użytkownik → **Aktywuj** (**Activate**) |
| Pętla przekierowań po logowaniu u dostawcy | ciasteczko `BO_SESSION` jest odrzucane: TLS terminowany na ingressie bez `X-Forwarded-Proto: https` albo UI i API w różnych witrynach (sites) | przekazuj nagłówki forwarded; serwuj UI, `/api`, `/oauth2`, `/login` z jednego originu; `BACKOFFICE_AUTH_COOKIE_SECURE=false` tylko do developmentu po zwykłym HTTP |
| Dostawca zgłasza „Invalid parameter: redirect_uri” | redirect URI nie jest zarejestrowany dokładnie (schemat, host, port) | zarejestruj `<public URL>/login/oauth2/code/oidc` |
| `error=invalid-id-token` | rozjazd zegarów ponad 60 s lub niezgodność issuera (`iss` w tokenach różni się od skonfigurowanego issuera) | włącz NTP; ustaw identyczny URL issuera dla API i przeglądarki |
| `invalid-token-response` | błędny client secret lub API nie może połączyć się z endpointem tokenów dostawcy | popraw Secret; sprawdź ruch wychodzący i zaufanie TLS z poda API |
| Nikt nie może się zalogować, brak przycisku dostawcy | issuer jest pusty, więc OIDC jest wyłączone | ustaw issuer albo zaloguj się lokalnie |
| `403` z `"code":"csrf"` przy zapisach | brak nagłówka `X-XSRF-TOKEN` (proxy go usuwa) | popraw proxy; skrypty używają osobistego tokenu zamiast sesji |
| `403` z `"permission": "..."` | osoba nie ma tego uprawnienia | **Użytkownicy** → użytkownik → **Uprawnienia efektywne** (**Effective permissions**); Odmów na poziomie użytkownika lub grupy wygrywa z rolami |
| Wylogowanie po krótkim czasie | limit bezczynności (24 h) lub bezwzględny limit (24 h od zalogowania), dezaktywacja, sesje zakończone przez administratora | zachowanie oczekiwane; dostosuj `BACKOFFICE_AUTH_SESSION_*` |
| Logowanie lokalne `401 invalid-credentials` | błędne hasło, blokada, niedozwolona metoda, hasło nigdy nieustawione (prawdziwa przyczyna jest w dzienniku audytu) | **Odblokuj konto** (**Unlock account**), **Metoda logowania** (**Sign-in method**), **Wydaj link do ustawienia hasła** (**Issue set-password link**) |
| `429 too-many-attempts` | 20 prób z jednego IP w ciągu 5 minut | odczekaj czas z `Retry-After` |
| Skrypt dostaje `401` `invalid-token` | osobisty token wygasł lub został unieważniony, właściciel dezaktywowany | utwórz nowy token ([integrations-ide.md](integrations-ide.md#pat)) |
| IDE: `401` `personal-token-required` z `/mcp` | to nie jest token `bo_pat_` (sesja, token CI, brak tokenu) | użyj osobistego tokenu dostępu |
| IDE dostaje HTML lub `404` z `/mcp` | pod web lub ingress serwuje `/mcp` jako część UI | kieruj `/mcp` (i `/.well-known/back-office/`) do serwisu API tak samo jak `/api` |

Pełny runbook: `docs/runbooks/cannot-log-in.md`.

<a id="jobs"></a>
## Zadania agentów i runnery

| Objaw (przyczyna w zadaniu) | Przyczyna | Rozwiązanie |
|---|---|---|
| `QUEUED`, `no online runner in pool <id>` | żaden kontroler puli nie zgłosił się w ciągu ostatnich 90 s | sprawdź pod i logi kontrolera, wychodzący HTTPS do Back Office oraz to, czy runner nie został unieważniony |
| `QUEUED`, `no runner pool can run this job` | etykiety puli, lista projektów lub dozwolone obrazy nie pasują do polityki zadania | popraw pulę albo politykę szablonu/projektu; zadania są ponownie kierowane co 10 s |
| `QUEUED`, brak przyczyny | pula lub runner wykorzystuje pełną pojemność | zwiększ **Maks. liczbę równoległych zadań** (**Max concurrent jobs**) lub `maxConcurrent`, dodaj repliki |
| `registration failed: 401` w logu kontrolera | token rejestracyjny został już użyty, wygasł (24 h) lub dotyczy innej puli | **Dodaj runner** (**Add runner**) → nowy token |
| Runner online, zadania od razu kończą się błędem `policy-violation: ...` | obrazu nie ma w **Dozwolonych obrazach** (**Allowed images**), brak RuntimeClass, obniżony pułap | popraw pulę lub zainstaluj RuntimeClass |
| `FAILED` / `ImagePullBackOff` | klaster nie może pobrać obrazu agenta | poświadczenia rejestru (`imagePullSecrets`), nazwa obrazu |
| `FAILED` / `StartTimeout`, pod `Pending` | żaden węzeł nie oferuje RuntimeClass lub zasobów | pula węzłów z gVisor/Kata, `runtimeClasses.scheduling`, pojemność |
| `FAILED` / `OOMKilled` | za mały limit pamięci | zwiększ `resources.memory` w polityce (w granicach pułapu) |
| `TIMED_OUT` / `DeadlineExceeded` | zadanie przekroczyło swój limit czasu | zwiększ `timeoutSeconds`, podziel zadanie |
| `TIMED_OUT` / `queue-timeout` | nic nie wzięło go w dzierżawę przez 24 h | zobacz wiersze `QUEUED`, a następnie ponów węzeł |
| `LOST` | kontroler przestał wysyłać heartbeaty, a zadanie mogło już wypchnąć zmiany | sprawdź węzeł i kontroler; ponów węzeł |
| `FAILED` / `lease-not-acknowledged` | 5 dzierżaw bez heartbeatu | kontroler nie może uruchamiać sandboxów: RuntimeClass, obraz, RBAC |
| `budget-exceeded: ...` | osiągnięto twardy limit zadania, projektu lub platformy | podnieś limit lub wyłącz twardą blokadę, ponów ([agent-execution.md](agent-execution.md#budgets-in-sandbox)) |
| Log agenta: `model-not-allowed` (403) | agent poprosił o model inny niż model zadania (model działający w tle Claude Code) | `RUNNER_EXTRA_ALLOWED_MODELS=anthropic/claude-haiku-*` |
| **Zablokowany ruch wychodzący** (**Egress denied**) zawiera host potrzebny do builda | hosta nie ma na efektywnej liście dozwolonych | dodaj go do polityki projektu lub szablonu, w granicach pułapu puli |
| `git clone failed` / `git push failed` | brak tokenu lub token bez `write_repository`/zapisu Contents, gałąź chroniona, push rules, prywatny host gita spoza `egressProxy.allowedCidrs` | [integrations-git.md](integrations-git.md#gitlab-token) |
| `the receiving end does not support push options` | repozytorium nie jest na GitLabie | ustaw `mergeRequest` na `false` w węźle |
| Wypchnięto, ale brak URL-a merge requestu | rola tokenu poniżej Developer, brak gałęzi docelowej, MR już istniał | popraw rolę lub gałąź |

Runbooki: `docs/runbooks/agent-jobs.md`, `docs/runbooks/runner-job-failed.md`.

<a id="runs"></a>
## Uruchomienia, które stoją w miejscu

| Objaw | Przyczyna | Rozwiązanie |
|---|---|---|
| Długo `RUNNING` na kroku agenta | zadanie agenta czeka w kolejce lub jest wykonywane | otwórz zadanie w **Zadaniach agentów** (**Agent jobs**) i skorzystaj z tabeli powyżej |
| `RUNNING` po restarcie API | odtwarzanie wznawia od ostatniego punktu kontrolnego i ponownie wykonuje przerwany węzeł | poczekaj; jeśli pojawi się `Resume request without a valid checkpoint`, anuluj i rozpocznij od nowa |
| `WAITING` na CI, a sygnał został wysłany | nazwa sygnału różni się od nazwy w węźle albo nie udało się dopasować uruchomienia (brak trailera `Back-Office-Run`, squash) | wyślij poprawną nazwę lub `--run <id>`; osoba z `run.signal` może wysłać sygnał ze strony uruchomienia |
| `WAITING` na osobę, a w skrzynce nic nie ma | bramka ma regułę zatwierdzających, której nie spełniasz, albo upłynął limit czasu bramki | sprawdź zatwierdzających bramki; zobacz `docs/runbooks/stuck-pipeline-run.md` |
| Krok Jiry kończy się błędem `HTTP 401/403/404` lub „no Jira connection with capabilities” | wygasły token, licencja, uprawnienie projektowe albo brak połączenia z potrzebną możliwością | [integrations-jira.md](integrations-jira.md#jira-test), [połączenia](integrations-jira.md#jira-connections) |
| `has no transition to status X` | brak przejścia z bieżącego statusu do `X` | popraw `toStatus` lub workflow w Jirze |
| Krok zakończony błędem, widoczny w **Operacje** (**Operations**) → **Martwe elementy** (**Dead items**) | krok wyczerpał wszystkie ponowienia | usuń przyczynę, a następnie **Ponów** (**Retry**) |
| Najstarszy gotowy element pracy wciąż się starzeje | nie działa żaden worker lub wszystkie są zajęte | sprawdź pody workerów; przeskaluj workery (`docs/runbooks/work-queue.md`) |
| UI nadal pokazuje zakończone uruchomienie jako aktywne | strumień aktualizacji na żywo został przerwany (limit czasu proxy dla `text/event-stream`) | przeładuj stronę; zwiększ read timeout ingressu |

<a id="providers"></a>
## Dostawcy LLM

| Objaw | Przyczyna | Rozwiązanie |
|---|---|---|
| **Ustawienia** (**Settings**) pokazują **Brak klucza API** (**no API key**) | brak klucza w Secrecie provider-keys albo pod wystartował, zanim go ustawiono | popraw Secret, zrestartuj api i workera |
| `provider is not configured on this server` | jak wyżej albo endpoint zgodny z OpenAI ma tylko URL lub tylko klucz | ustaw obie wartości |
| `HTTP 401` | błędny, unieważniony lub źle wpisany klucz (końcowy znak nowej linii) | zrotuj klucz ([integrations-llm.md](integrations-llm.md#llm-overview)) |
| `HTTP 403` przy ważnym kluczu | projekt/workspace klucza nie ma dostępu do modelu albo ograniczonym uprawnieniom brakuje chat completions | nadaj dostęp do modelu lub uprawnienie u dostawcy |
| `HTTP 429` | limit zapytań lub limit wydatków u dostawcy | zmniejsz `LLM_CONCURRENCY_PER_PROVIDER` / `EVAL_CONCURRENCY_PER_PROVIDER`, podnieś poziom (tier) u dostawcy |
| `HTTP 5xx` | awaria u dostawcy | ponawiane automatycznie; ponów węzeł później |
| Model not found / bad request | błędne id modelu w profilu, model wycofany | popraw profil w **Modelach** (**Models**) i przetestuj go |
| Ollama nieosiągalna | `OLLAMA_BASE_URL` nieosiągalny z podów API, NetworkPolicy | popraw URL lub zezwól na ruch wychodzący |
| Wywołania zatrzymują się z `402 budget-exceeded` | osiągnięto twardy budżet | podnieś budżet lub wyłącz twardą blokadę |

Użyj przycisku testu w **Modelach**: jeśli test się udaje, a prawdziwe wywołania nie, różnicą jest rozmiar,
ustrukturyzowane wyjście lub współbieżność, a nie poświadczenia. Runbook: `docs/runbooks/provider-errors.md`.

<a id="ci"></a>
## Integracja z CI

| Objaw | Przyczyna | Rozwiązanie |
|---|---|---|
| `BACKOFFICE_CI_TOKEN is required` | zmienna nie jest widoczna dla zadania: oznaczona **Protect variable**, a gałąź nie jest chroniona, błędny zakres środowiska, nie przekazana do kroku GitHuba | odznacz **Protect variable**, pozostaw **Masked** ([integrations-gitlab-ci.md](integrations-gitlab-ci.md#ci-variables)) |
| Kod wyjścia 2, `HTTP 401` | token unieważniony lub źle wpisany albo osobisty token zamiast tokenu CI | utwórz nowy token CI |
| Kod wyjścia 2, `HTTP 403` | token należy do innego projektu | użyj tokenu tego projektu |
| Kod wyjścia 2, `HTTP 404` nieznany punkt wejścia | punkt wejścia nie jest zmapowany na zakładce **CI** projektu albo `BACKOFFICE_URL` wskazuje gdzie indziej | zmapuj go, popraw URL |
| `Diff: empty` | płytki klon bez gałęzi docelowej | `GIT_DEPTH: "0"` / `fetch-depth: 0` |
| Kod wyjścia 3 | upłynął `BACKOFFICE_REVIEW_TIMEOUT` | otwórz link `Run:`; zwiększ limit czasu lub użyj szybszego modelu |
| Wykonanie `FAILED` z `Error:` | błąd dostawcy lub workflowu | zobacz [Dostawcy LLM](#providers) i stronę uruchomienia |
| Sygnał: „no run is waiting” | uruchomienie już poszło dalej, nazwa się różni, brak trailera | przekaż `--run <id>` i poprawną nazwę |
| Przy ponowieniu zwracany jest ten sam przegląd | wykonania są idempotentne per commit przez 24 h | `--var force=true` |

Przewodnik: `docs/guides/ci-integration.md`.
