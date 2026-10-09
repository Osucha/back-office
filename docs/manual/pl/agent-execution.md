# Jak działają zadania agentów

Agenci kodujący (fazy Spec Kit, kroki **Implement**, próby benchmarków kodowania) nigdy nie działają wewnątrz
Back Office. Back Office umieszcza w kolejce **zadanie agenta**; runner-controller w jednym z Twoich klastrów
bierze je w dzierżawę (lease), uruchamia izolowany sandbox i raportuje wynik. Ten rozdział wyjaśnia cykl
życia, poziomy izolacji, granice sieci i poświadczeń, budżety, limity czasu oraz dowody pochodzenia, które
zostawia po sobie zadanie.

**Kogo to dotyczy:** administratora platformy i operatora klastra runnerów; osób przeprowadzających przeglądy
bezpieczeństwa.

<a id="lifecycle"></a>
## Cykl życia zadania

```
Back Office (api)                runner-controller (pool P)              sandbox pod
  │ workflow node needs an agent
  │ agent_job QUEUED (routed to pool P)
  │◀──── POST /runner/v2/jobs/lease (long poll, runner token bo_rt_) ──┤
  │ LEASED: assignment {spec, policy, job token bo_job_, specDigest,
  │         Ed25519 signature, leaseExpiresAt} ─────────────────────────▶│
  │                                  verify digest + signature + expiry
  │                                  re-apply pool ceiling, allowed images
  │                                  register job at egress proxy bo-egress-P
  │                                  create SandboxClaim / Sandbox / Job ──▶ pod starts
  │                                                                       runner reads /var/run/backoffice/job
  │◀──────────────── LLM calls via proxy, Authorization: bo_job_ ─────────────┤ (gateway /api/v1/llm/...)
  │◀──────────────── events, result, presigned uploads (bo_job_) ─────────────┤
  │◀──── heartbeat every 15 s {phase}; answer may carry cancelRequested ─┤
  │ STARTING → RUNNING                                                    agent exits
  │◀──── POST /runner/v2/jobs/{id}/complete {status, exitCode, reason,  ─┤
  │       imageDigest, egressLogDigest, egressDenied}                    sandbox + job Secret deleted
  │ SUCCEEDED / FAILED / TIMED_OUT / CANCELLED / LOST
  │ job token revoked, RUN_ADVANCE queued (the workflow continues)
  │ provenance attestation signed
```

1. **Zgłoszenie.** Węzeł workflowu (lub próba benchmarku) tworzy zadanie agenta. Back Office wyznacza
   efektywną politykę sandboxa i kieruje zadanie do puli: etykiety puli muszą pasować do `poolSelector`
   polityki, projekt musi być dozwolony, a polityka musi mieścić się w pułapie puli; wygrywają pule
   z działającym (online) runnerem i najkrótszą kolejką. Priorytet: 10 dla zadań workflowów, 0 dla
   benchmarków.
2. **Dzierżawa.** Kontroler puli odpytuje w trybie long poll o tyle zadań, ile ma wolnych slotów. Przydział
   jest podpisany kluczem platformy ([integrations-other.md](integrations-other.md#signing)); kontroler
   odrzuca wszystko, czego skrót lub podpis nie przechodzi weryfikacji, więc przejęta ścieżka sieciowa nie
   może wstrzyknąć pracy.
3. **Ponowna kontrola.** Kontroler ponownie stosuje lokalnie pułap puli: obraz musi pasować do dozwolonych
   obrazów puli, zasoby i limit czasu są przycinane, a izolacja może jedynie się zaostrzyć. Naruszenia
   kończą zadanie stanem `FAILED` z `policy-violation: ...`.
4. **Wykonanie.** Sandbox dostaje pakiet zadania (spec, politykę, token zadania, URL proxy) jako pliki,
   klonuje repozytorium, uruchamia agenta, weryfikuje wynik, tworzy commit, wypycha zmiany i przesyła swój
   wynik.
5. **Zakończenie.** Kontroler raportuje, jak zakończył się sandbox; Back Office łączy to z wynikiem samego
   agenta (sandbox, który zakończył się kodem 0, nie przesławszy pomyślnego wyniku, ma stan `FAILED`),
   unieważnia token zadania, kontynuuje workflow i podpisuje atestację.

Zadania i ich stany są widoczne w **Administracja** (**Administration**) → **Flota runnerów**
(**Runner fleet**) → **Zadania agentów** (**Agent jobs**) ([runner-fleet.md](runner-fleet.md#agent-jobs)).

<a id="isolation-levels"></a>
## Poziomy izolacji

| Poziom | Środowisko uruchomieniowe | Gdzie | Zastosowanie |
|---|---|---|---|
| **Standardowa** (`STANDARD`, domyślna) | gVisor (RuntimeClass `gvisor`, handler `runsc`): jądro w przestrzeni użytkownika | Kubernetes | każde zwykłe zadanie |
| **Wysoka** (`HIGH`) | Kata Containers (RuntimeClass `kata`): mikro-VM na każdy pod | Kubernetes | niezaufane repozytoria, kod klientów |
| **Deweloperska** (`DEV`) | runc (zwykłe kontenery) | tylko backend Docker na laptopie | development |

- Wygrywa najsilniejszy poziom wskazany gdziekolwiek (platforma → pułap puli → projekt → szablon → węzeł).
  Domyślny poziom platformy to `RUNNER_PLATFORM_ISOLATION` (`STANDARD`; `DEV` tylko w profilu local).
- Na Kubernetesie **DEV jest podnoszony do STANDARD**. W backendzie Docker STANDARD i HIGH **nie są
  egzekwowane** (tylko runc); informują o tym notatki zadania.
- Zadanie jest odrzucane, gdy jego RuntimeClass nie istnieje w klastrze. Nazwy RuntimeClass dla danej
  platformy: `runtimeClasses.standard` / `runtimeClasses.high` w charcie runnera (GKE Sandbox `gvisor`, AKS
  `kata-vm-isolation`).

**Utwardzenie poda** (na każdym poziomie): użytkownik inny niż root 10001, główny system plików tylko do
odczytu, brak eskalacji uprawnień, odebrane wszystkie capabilities, seccomp `RuntimeDefault`, brak tokenu
konta usługowego, brak service links, requests = limits dla CPU, pamięci i ephemeral storage, limit pid,
`/workspace` i `/tmp` jako `emptyDir` o ograniczonym rozmiarze, jeden kontener i brak sidecara.

<a id="network"></a>
## Sieć: domyślna blokada z proxy ruchu wychodzącego na pulę

- Pody sandboxów **nie przyjmują ruchu przychodzącego** i mogą łączyć się tylko z DNS klastra oraz z proxy
  ruchu wychodzącego swojej puli (`bo-egress-<pool>`, Stripe Smokescreen) na porcie 4750. Egzekwuje to
  NetworkPolicy, więc CNI klastra musi egzekwować NetworkPolicies.
- Proxy to jedyny pod z wyjściem do internetu. Każde zadanie uwierzytelnia się w nim własnym hasłem proxy
  i dostaje własną listę dozwolonych: hosty z efektywnego `network.allow` oraz zawsze host Back Office
  (brama LLM, callbacki) i host z **URL repozytorium Git** (**Git URL**) projektu.
- Proxy nigdy nie łączy się z zakresami prywatnymi (`10/8`, `172.16/12`, `192.168/16`), zakresem metadanych
  chmury `169.254/16` ani `100.64/10`, chyba że wpiszesz CIDR w `egressProxy.allowedCidrs` (samodzielnie
  hostowany GitLab, Nexus, Back Office wewnątrz VPC).
- Hosty są dopasowywane po nazwie; porty są ignorowane; dozwolony jest tylko wiodący wildcard `*.`.
- Każda decyzja jest logowana; odrzucone hosty pojawiają się w zadaniu jako **Zablokowany ruch wychodzący**
  (**Egress denied**), a skrót pełnego logu trafia do atestacji.

Dodawaj host na najwęższym poziomie, który go potrzebuje (szablon lub zakładka **Sandbox** projektu); musi on
być też objęty pułapem puli i polityką platformy, w przeciwnym razie zostanie odfiltrowany przy zawężaniu.
Zobacz [runner-fleet.md](runner-fleet.md#sandbox-policies).

<a id="credentials"></a>
## Poświadczenia: co sandbox ma, a czego nie

| Sandbox ma | Zakres i czas życia |
|---|---|
| Token zadania `bo_job_...` | Ma go tylko runner (w pamięci, plik jest usuwany): przesyła zdarzenia, wynik i pliki zadania. Unieważniany po zakończeniu zadania; wygasa razem z dzierżawą; zadanie ponownie umieszczone w kolejce dostaje nowy |
| Token agenta `bo_job_...` (tylko brama) | Jedyne poświadczenie agenta: wywołuje bramę LLM tylko dla modelu zadania (plus wzorców z `RUNNER_EXTRA_ALLOWED_MODELS`) w granicach budżetów i limitu żądań zadania; odrzucany przez wszystkie callbacki, więc agent nie może przesłać ani podrobić wyniku. Unieważniany razem z zadaniem |
| Token gita (z Secretu gita puli) | Runner wczytuje go do pamięci i usuwa plik; używany tylko przez polecenia gita, które runner uruchamia przed agentem i po nim ([integrations-git.md](integrations-git.md#git-overview)) |
| URL proxy z hasłem przypisanym do zadania | Ważny tylko w proxy swojej puli, usuwany po zakończeniu |

| Sandbox nigdy nie ma | Gdzie pozostaje |
|---|---|
| Kluczy dostawców LLM | pody api/workera; dodaje je brama |
| Tokenu Jiry, client secretu OIDC, kluczy podpisujących, poświadczeń bazy danych i magazynu | pody api/workera |
| Tokenu runnera `bo_rt_` | przestrzeń nazw kontrolera, nigdy przestrzeń nazw sandboxów |
| Tokenów CI, osobistych tokenów dostępu | w bazie danych jako skróty |
| Poświadczeń Kubernetesa | token konta usługowego nie jest montowany |

Wewnątrz sandboxa proces agenta (OpenCode lub Claude Code) widzi tylko token agenta: kluczy dostawców,
tokenu gita i tokenu zadania nie ma ani w jego środowisku, ani na dysku, a proces runnera jest niedostępny
dla agenta (oznaczony jako non-dumpable). Uprawnienia narzędzi z polityki
(`tools.allow` / `deny` / `ask`) są przekładane na własne ustawienia uprawnień agenta; „ask” w zadaniach
bez nadzoru liczy się jako deny. To dodatkowa warstwa obrony (defence in depth): granicą jest sandbox.

**Ograniczenia**: z runner-controllerem starszym niż ADR 0029 pliki tokenów pozostają czytelne (pole
`result.security` zadania pokazuje `gitTokenFileRemoved: false`); aktualizuj kontroler i obraz runnera razem.
Mimo to ograniczaj tokeny gita wąsko. Pole
polityki `secrets` (nazwy Secretów dozwolone w puli) jest rozwiązywane i zapisywane, ale nie jest jeszcze
montowane w sandboxach.

<a id="budgets-in-sandbox"></a>
## Budżety w sandboxie

Brama sprawdza każde wywołanie agenta przed jego przekazaniem, na podstawie szacunku (rozmiar żądania ÷ 4
tokenów wejściowych + do 4096 tokenów wyjściowych po cenach katalogowych), a po wywołaniu księguje
rzeczywiste zużycie.

| Zakres | Gdzie ustawiany | Skutek po wyczerpaniu |
|---|---|---|
| Zadanie | `maxCostUsd`, `maxTokens` w polityce sandboxa (szablon, projekt lub węzeł) | `402` dla tego zadania |
| Projekt, miesięcznie | zakładka **Budżet** (**Budget**) projektu (`project.manage`) z **Twardą blokadą** (**Hard stop**) | `402` dla każdego wywołania agenta w projekcie |
| Platforma, miesięcznie | **Ustawienia platformy** (**Platform settings**) → budżet (`admin.settings.manage`) z **Twardą blokadą** | `402` dla każdego wywołania agenta |

Odpowiedź to HTTP **402** `budget-exceeded` z `scope` (`JOB`, `PROJECT`, `PLATFORM`). Agent się zatrzymuje,
a zadanie kończy się błędem z przyczyną `budget-exceeded: <why>`. Podnieś limit (lub wyłącz twardą
blokadę) i ponów węzeł. Z powodu szacunku wywołanie może zostać odrzucone nieco przed osiągnięciem
limitu. Szczegóły: [costs-insights.md](costs-insights.md#budgets).

<a id="timeouts"></a>
## Limity czasu, anulowanie i utracone zadania

| Licznik | Domyślnie | Wynik |
|---|---|---|
| Limit czasu zadania | `timeoutSeconds` kroku/szablonu, w przeciwnym razie `RUNNER_DEFAULT_TIMEOUT` (`PT60M`); ograniczony przez politykę | kontroler usuwa sandbox po limicie + 120 s → `TIMED_OUT` / `DeadlineExceeded` |
| Limit czasu w kolejce | 24 h (`RUNNER_QUEUE_TIMEOUT`) | nigdy nie wzięte w dzierżawę → `TIMED_OUT` / `queue-timeout` |
| Dzierżawa | 60 s, przedłużana każdym heartbeatem (15 s) | brak heartbeatu → jednokrotne ponowne umieszczenie w kolejce, w przeciwnym razie `LOST` |
| Start sandboxa | błędy pobierania obrazu lub konfiguracji kontenera przez 90 s; stan pending przez 15 min | `FAILED` / `ImagePullBackOff`, `CreateContainerConfigError`, `StartTimeout` |

- **Anulowanie**: anulowanie uruchomienia (lub **Anuluj zadanie** (**Cancel job**) przy zadaniu agenta,
  `fleet.manage`) ustawia `cancelRequested`; kontroler widzi to w odpowiedzi na kolejny heartbeat, usuwa
  sandbox i raportuje `CANCELLED`.
- **LOST**: kontroler przestał wysyłać heartbeaty (awaria węzła, crash, sieć). Zadanie jest jednokrotnie
  ponownie umieszczane w kolejce z nowym tokenem, chyba że wykonywało zadanie wypychające zmiany: wtedy
  kończy się stanem `LOST`, bo drugie wykonanie mogłoby wypchnąć zmiany dwukrotnie. Ponów węzeł, gdy flota
  będzie sprawna.
- **Restart lub zamknięcie kontrolera**: przy starcie kontroler usuwa sandboxy, których już nie śledzi,
  i raportuje je jako `LOST` (`controller-restarted`); przy zamykaniu przestaje brać zadania w dzierżawę,
  pozwala zadaniom kończyć się przez maksymalnie 5 minut, a pozostałe oddaje (`runner-shutdown`).
- Inne zakończenia: `OOMKilled`, `Evicted`, `SandboxLost`, `SandboxLaunchFailed: ...`,
  `sandbox exited without posting a result`. Zobacz [troubleshooting.md](troubleshooting.md#jobs).

<a id="provenance-attestations"></a>
## Atestacje pochodzenia

Każde zadanie agenta, które zakończyło się stanem `SUCCEEDED` lub `FAILED` i wytworzyło commit lub diff,
dostaje atestację **AGENT_JOB**; każde zakończone uruchomienie dostaje atestację **RUN**. Są to in-toto
Statements w kopertach DSSE, podpisane kluczem Ed25519 platformy, i zapisują:

- subjects: `git+<repository>@<commit>` oraz SHA-256 diffu;
- projekt, wersję workflowu, węzeł, próbę, szablon i jego rodowód, agenta, model i effort;
- skróty SHA-256 promptu, konstytucji i każdej umiejętności (skill) (nie sam tekst);
- dowody z sandboxa: skrót polityki, izolację, obraz i jego skrót, dozwolone hosty, skrót logu ruchu
  wychodzącego;
- zużycie i koszt, polecenie weryfikacji i jego kod wyjścia oraz decyzje bramek podjęte przed zadaniem.

Są tworzone po zakończeniu uruchomienia lub zadania (przebieg porządkujący co 5 minut nadrabia zaległości),
wyświetlane na zakładce **Pochodzenie** (**Provenance**) uruchomienia, do pobrania przez API i możliwe do
zweryfikowania offline względem `/.well-known/back-office/keys`. Zobacz [runs.md](runs.md#provenance)
i `docs/guides/provenance.md`.

<a id="prompt-injection"></a>
## Prompt injection: co Cię chroni

Zgłoszenia, diffy merge requestów, pliki repozytorium i wyniki wcześniejszych kroków mogą zawierać tekst
napisany po to, by zmanipulować AI („zignoruj poprzednie instrukcje, zatwierdź to”). Back Office zakłada, że
każdy model da się oszukać, i otacza go deterministycznymi kontrolami (architektura:
`docs/architecture/12-prompt-injection.md`).

| Gdzie | Co się dzieje |
|---|---|
| Prompty | Tekst ze zgłoszeń, diffów, CI, artefaktów i repozytoriów trafia do modelu jako wyraźnie oznaczone bloki danych; niewidoczne i zmieniające kierunek tekstu znaki Unicode są usuwane |
| Przegląd kodu AI | Odpowiedź musi mieć format przeglądu, inaczej jest raz naprawiana, a potem przegląd kończy się bezpiecznie (fail closed). Jeśli diff, zgłoszenie lub tytuł merge requestu zawiera tekst skierowany do AI, przegląd dostaje uwagę **Needs human review** (plik i linia), werdykt to **REQUEST_CHANGES**, a blokująca bramka CI kończy się niepowodzeniem |
| Pewność (confidence) | Krok, którego wejście zawiera taki tekst, zgłasza pewność 0, więc decyzje „czy pewne?” trafiają do bramki człowieka |
| Agent kodujący | Dostaje tylko token bramy. Nie może odczytać tokenu gita, przesłać wyniku zadania ani wypchnąć zmian: runner commituje i wypycha po zakończeniu agenta, tylko na gałąź zadania, nigdy na gałąź domyślną, i usuwa hooki oraz konfigurację gita podrzucone przez agenta |
| Benchmarki | Próba, która usiłuje przekonać sędziego, dostaje najwyżej 40 punktów i nie jest zaliczona |
| MCP | Agent w IDE może rozstrzygać bramki tylko tokenem utworzonym z opcją **Zezwól na decyzje bramek przez MCP** (**Allow gate decisions via MCP**) ([integrations-ide.md](integrations-ide.md#pat)) |

Każde wykrycie trafia do logu uruchomienia i do dziennika audytu (`PROMPT_INJECTION_SUSPECTED`).

**Co powinieneś zrobić**

1. Trzymaj punkt wejścia przeglądu CI w trybie **BLOCKING**, a w workflowach ludzką **bramkę scalenia**.
2. Chroń gałąź domyślną w GitLabie i wymagaj zatwierdzeń przez ludzi; dawaj sandboxom token tylko do odczytu
   dla zadań, które tylko czytają, i token Developera z `write_repository`, który nie może scalać, dla zadań,
   które wypychają ([integrations-git.md](integrations-git.md#git-least-privilege)).
3. Gdy przegląd mówi **Needs human review**, otwórz wskazany plik i linię. Jeśli tekst jest uprawniony (na
   przykład kod promptów), zatwierdź merge request samodzielnie; projekt, w którym taki tekst pojawia się
   często, może ustawić `injectionGuard` na `FLAG` (tylko zgłaszanie) lub `OFF` (tylko log) w swoim szablonie
   przeglądu.
4. Zwracaj uwagę na ostrzeżenie projektanta „routes only on model output”: decyzja oparta na `confidence` lub
   `review`, która prowadzi do kroku implementacji bez bramki człowieka, może zostać sterowana przez autora
   zgłoszenia.
5. Przeglądaj zmiany umiejętności (skills), szablonów i konstytucji jak kod: są częścią każdego promptu.
