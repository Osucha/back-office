# Flota runnerów

Flota runnerów to miejsce, w którym wykonują się zadania agentów: pule runner-controllerów w Twoich klastrach
Kubernetes (lub w Dockerze na laptopie), które pobierają zadania z Back Office przez HTTPS. Back Office nigdy
nie dostaje poświadczeń do tych klastrów. Ten rozdział opisuje pule, rejestrację runnera, polityki sandboxów
i widok zadań agentów. Jak wykonuje się pojedyncze zadanie, opisuje [agent-execution.md](agent-execution.md).

**Kogo to dotyczy:** administratora platformy (`fleet.manage`), operatora klastra runnerów; właścicieli
projektów w zakresie polityki sandboxa projektu.

<a id="pools"></a>
## Pule

Pula to grupa kontrolerów wykonujących ten sam rodzaj zadań: jeden klaster, region, węzły GPU, jeden klient.
Otwórz **Administracja** (**Administration**) → **Flota runnerów** (**Runner fleet**) (`/admin/fleet/pools`;
podgląd wymaga `fleet.view`, zmiany – `fleet.manage`). Zakładki **Pule** (**Pools**), **Runnery**
(**Runners**) i **Zadania agentów** (**Agent jobs**) pokazują flotę.

**Utwórz pulę**

1. **Nowa pula** (**New pool**).
2. **Pula** (**Pool**): **Id puli** (**Pool id**) (używane w wartościach chartu i etykietach zadań; nie można
   go zmienić), **Nazwa** (**Name**), **Opis** (**Description**), **Etykiety** (**Labels**) (na przykład
   `region=eu`, `gpu=true`; zadania wybierają pule według tych etykiet przez selektor puli w polityce).
3. **Pojemność i obrazy** (**Capacity and images**): **Liczba rozgrzanych sandboxów** (**Warm pool size**)
   (wstępnie uruchomione sandboxy na szablon, tylko backend Agent Sandbox), **Maks. liczba równoległych
   zadań** (**Max concurrent jobs**) w całej puli, **Dozwolone obrazy** (**Allowed images**) (wzorce z `*`,
   na przykład `registry.example.com/back-office/runner:*`; puste = tylko obraz z pułapu lub obraz platformy),
   **Projekty** (**Projects**), które mogą używać puli (puste = wszystkie).
4. **Pułap polityki** (**Policy ceiling**): maksimum, jakie może dostać zadanie w tej puli: izolacja, zasoby
   i czas, lista dozwolonych hostów sieciowych, narzędzia, obraz środowiska i dozwolone nazwy sekretów,
   limity kosztu i tokenów na zadanie.
5. **Utwórz pulę** (**Create pool**).

Puli z zadaniami w kolejce lub w trakcie wykonywania nie można usunąć. Zmiana pułapu obowiązuje od następnej
dzierżawy; zadania w kolejce, które już się w nim nie mieszczą, kończą się błędem `policy-violation: ...`.

<a id="add-runner"></a>
## Dodaj runner do puli

**Wymagania w klastrze wykonawczym** (sprawdź je poleceniem
`deploy/scripts/check-runner-cluster.sh --standard gvisor --high kata --url https://backoffice.example.com`):

1. Kubernetes 1.29+ i CNI, które **egzekwuje** NetworkPolicy (Calico, Cilium, GKE Dataplane V2, AKS Cilium,
   EKS VPC CNI z włączonymi network policies). Bez tego lista dozwolonych ruchu wychodzącego nie jest
   egzekwowana.
2. RuntimeClass `gvisor` dla izolacji STANDARD (GKE Sandbox lub `runsc` na węzłach), opcjonalnie Kata dla
   HIGH (AKS `kata-vm-isolation`, gdzie indziej kata-deploy).
3. Zalecane: CRD i kontroler Agent Sandbox v1.0.4, raz na klaster:
   `kubectl apply -f https://github.com/kubernetes-sigs/agent-sandbox/releases/download/v1.0.4/sandbox-with-extensions.yaml`
   (lub `agentSandbox.install=true` w dokładnie jednym release). Bez nich backend `K8S_JOB` uruchamia zwykłe
   Joby, bez puli rozgrzanych sandboxów.
4. Wychodzący ruch HTTPS z klastra do publicznego URL-a Back Office; nic przychodzącego.
5. Obrazy, które klaster może pobrać: `runner-controller`, `egress-proxy` i obraz(y) agenta wymienione
   w **Dozwolonych obrazach** puli.
6. Opcjonalnie: KEDA 2.x do autoskalowania na podstawie kolejki.

**Rejestracja**

1. Na zakładce **Pule** naciśnij **Dodaj runner** (**Add runner**) w wierszu puli.
2. W oknie **Dodaj runner do** (**Add a runner to**) *pula* naciśnij **Utwórz token rejestracyjny**
   (**Create registration token**). Token (`bo_rrt_...`) jest jednorazowy i wygasa po 24 godzinach; jest
   wyświetlany tylko raz. Utworzenie kolejnego nie unieważnia tego.
3. **1. Zapisz wartości chartu** (**1. Save the chart values**) jako `runner-<pool>.values.yaml`:

```yaml
backOffice:
  url: https://backoffice.example.com
  poolId: eu-1
registration:
  token: bo_rrt_...          # single use; or registration.existingSecret (key RUNNER_REGISTRATION_TOKEN)
backend: auto                # AGENT_SANDBOX when the CRDs exist, else K8S_JOB
```

4. **2. Zainstaluj chart runnera** (**2. Install the runner chart**):

```sh
helm upgrade --install bo-runner-eu-1 deploy/helm/back-office-runner \
  --namespace bo-runner-eu-1 --create-namespace \
  -f runner-eu-1.values.yaml \
  --set controller.image.repository=registry.example.com/back-office/runner-controller \
  --set controller.image.tag=<version> \
  --set egressProxy.image=registry.example.com/back-office/egress-proxy:<version>
```

   W produkcji trzymaj token poza plikiem wartości: utwórz
   `kubectl -n bo-runner-eu-1 create secret generic bo-runner-registration --from-literal=RUNNER_REGISTRATION_TOKEN=bo_rrt_...`
   i ustaw `registration.existingSecret=bo-runner-registration`. Warianty chartu: `values-gvisor.yaml`,
   `values-kata.yaml`, `values-dev.yaml`, `values-aws|gcp|azure|onprem.yaml`.
5. Dodaj poświadczenia gita dla agentów: [integrations-git.md](integrations-git.md#git-install).
6. **Weryfikacja**: kontroler loguje `registered as runner ...`; **Runnery** pokazują go jako **online**
   (widziany w ciągu ostatnich 90 s) z wersją, backendami i runtime classes; workflow z krokiem **Implement**
   tworzy `SandboxClaim` lub Job w `bo-runner-eu-1-sandboxes`.

Przy rejestracji kontroler wymienia token rejestracyjny na token runnera (`bo_rt_...`) i zapisuje go
w Secrecie `<fullname>-token` (klucz `runnerToken`, `tokenSecret.name`) we **własnej** przestrzeni nazw,
nigdy w przestrzeni nazw sandboxów. Kolejne starty używają go ponownie; token rejestracyjny można wtedy usunąć.

**Unieważnienie lub wymiana runnera**: **Runnery** → **Unieważnij** (**Revoke**) natychmiast unieważnia token
runnera; zadania, które trzymał, wracają do kolejki. Aby ponownie zarejestrować tę samą instalację: utwórz
nowy token rejestracyjny, wpisz go do Secretu rejestracyjnego, usuń Secret `<fullname>-token`, zrestartuj
kontroler
(`kubectl -n bo-runner-eu-1 rollout restart deployment -l app.kubernetes.io/name=back-office-runner-controller`).

**Laptop**: profil compose `full` uruchamia runner-controller z backendem Docker; wpisz token do
`RUNNER_REGISTRATION_TOKEN` w `deploy/compose/.env` (pula `default`).

**Skalowanie**: `maxConcurrent` sandboxów na replikę kontrolera (domyślnie 5 w charcie);
`autoscaling.type=keda` skaluje repliki na podstawie kolejki puli (`/api/v1/runner/v2/pools/<pool>/queue`).
Zwymiaruj pulę węzłów dla sandboxów na `maxReplicas × maxConcurrent` podów. Więcej:
`docs/guides/runner-fleet-install.md`, `docs/runbooks/scaling.md`.

<a id="sandbox-policies"></a>
## Polityki sandboxów

Każde zadanie działa według efektywnej polityki wyznaczanej z pięciu poziomów, **wyłącznie przez
zawężanie**: ustawienia domyślne platformy → pułap puli → projekt → szablon zadania → węzeł workflowu.
Niższy poziom może ograniczyć, ale nigdy nie rozszerzyć.

| Pole | Jak łączą się poziomy |
|---|---|
| Izolacja | wygrywa najsilniejsza: DEV < STANDARD (gVisor) < HIGH (Kata) |
| CPU, pamięć, ephemeral storage, pids, limit czasu, `maxCostUsd`, `maxTokens` | minimum |
| Lista dozwolonych hostów sieciowych | część wspólna z każdym wyższym poziomem (`*.` = jedna etykieta DNS); host Back Office i host gita są zawsze dodawane |
| Narzędzia allow / deny / ask | allow: część wspólna; deny i ask: suma (ask = deny bez nadzoru) |
| Sekrety | żądane nazwy ograniczone do nazw dozwolonych w puli |
| Obraz | wygrywa najbardziej szczegółowy poziom; musi pasować do **Dozwolonych obrazów** puli |
| Selektor puli | suma; sprzeczne wartości są błędem |

**Poziom projektu**: otwórz projekt → zakładka **Sandbox**. **Polityka projektu** (**Project policy**)
edytuje poziom projektu (wymaga `project.manage`; pozostali widzą ją tylko do odczytu); **Efektywna dla tego
projektu** (**Effective for this project**) pokazuje, co zadanie dostaje dzisiaj i który poziom ustawił każde
pole. Naciśnij **Zapisz politykę sandboxa** (**Save sandbox policy**). Szablony i węzły zawężają ją dalej
w bibliotece zadań i w projektancie.

**Poziom platformy**: właściwości serwera `backoffice.runner.platform-policy.*` (izolacja przez
`RUNNER_PLATFORM_ISOLATION`, domyślnie STANDARD) oraz `RUNNER_EXTRA_ALLOWED_MODELS` dla modeli, które może
wywoływać każde zadanie. Szczegóły: `docs/guides/runner-fleet.md`.

<a id="agent-jobs"></a>
## Zadania agentów

**Administracja** → **Flota runnerów** → **Zadania agentów** pokazuje każde zadanie przekazane flocie:
**Stan** (**State**), **Praca** (**Work**) (projekt, uruchomienie, węzeł), **Model**, **Pula** (**Pool**),
**Czas trwania** (**Duration**), **Koszt** (**Cost**), **W kolejce** (**Queued**). Filtruj po puli
i statusie. Otwarcie zadania pokazuje jego przyczynę, runner i sandbox, hosty z **Zablokowanego ruchu
wychodzącego** (**Egress denied**) oraz **Efektywną politykę** (**Effective policy**) ze źródłem każdego pola.
**Anuluj zadanie** (**Cancel job**) (wymaga `fleet.manage`) zatrzymuje aktywne zadanie.

| Stan | Znaczenie |
|---|---|
| `QUEUED` | czeka na runner; przyczyna mówi dlaczego, gdy żadna pula nie pasuje lub żaden runner nie jest online |
| `LEASED` | kontroler je pobrał, jeszcze bez heartbeatu |
| `STARTING`, `RUNNING` | sandbox się uruchamia / agent pracuje |
| `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `CANCELLED` | zakończone; zadania zakończone błędem mają przyczynę |
| `LOST` | kontroler przestał wysyłać heartbeaty, a zadanie mogło już wypchnąć zmiany |

Zakładka **Pule** pokazuje dla każdej puli działające runnery oraz zadania w kolejce i w trakcie wykonywania.
Alert `BackOfficeRunnerPoolWithoutRunners` uruchamia się, gdy zadania czekają w kolejce przez 10 minut,
a żaden runner nie jest online. Objawy i rozwiązania: [troubleshooting.md](troubleshooting.md#jobs).
