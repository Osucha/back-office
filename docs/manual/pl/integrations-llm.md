# Dostawcy LLM

Back Office wywołuje modele językowe w krokach LLM workflowów, w przeglądach kodu i w benchmarkach, a także
przekazuje wywołania agentów kodujących z sandboxów przez swoją bramę LLM (LLM gateway). Klucze dostawców to
konfiguracja serwera przechowywana wyłącznie w podach API i workera. Ten rozdział pokazuje, gdzie utworzyć
każdy klucz, jakie są najwęższe uprawnienia, gdzie go umieścić, jak go przetestować i jak go rotować.

**Kogo to dotyczy:** administratora platformy.

<a id="llm-overview"></a>
## Jak używane są klucze dostawców

**Dodawaj dostawców w Back Office** (ADR 0045): **Modele** → **Dostawcy LLM** (`/models/providers`, wymaga
`admin.settings.manage`) → **Dodaj dostawcę**. Wybierz typ, nazwę i klucz (np. `openrouter`), bazowy URL, jeśli typ go
wymaga, i wklej klucz API. **Szybki start** uzupełnia OpenRouter, Groq, GitHub Models, Mistral, Google Gemini, Ollamę
albo LM Studio. **Testuj i pokaż modele** wywołuje dostawcę i wyświetla identyfikatory jego modeli: skopiuj jeden do
profilu modelu. Połączeń jednego typu może być kilka, np. OpenRouter i Groq; profil modelu wybiera jedno w polu
**Połączenie** (puste = pierwsze połączenie danego typu).

- Klucz jest zapisywany zaszyfrowany kluczem tajnym serwera (`BACKOFFICE_SECRETS_KEY`, tym samym co połączenia Jiry)
  i nigdy więcej nie jest pokazywany; zostaw pole puste, żeby go zachować, albo zaznacz **Usuń zapisany klucz**. Bez
  skonfigurowanego klucza tajnego nie da się zapisać kluczy.
- Zmiany działają bez restartu (na innych replikach w ciągu 30 sekund).
- **Zapytań na minutę** i **Zapytań na dzień** (opcjonalne, ADR 0046) to limity, których pilnuje Back Office: liczy
  każde zapytanie przez połączenie (kroki workflowów, benchmarki, agenci w piaskownicach) i zatrzymuje się na limicie,
  zanim dostawca zacznie odrzucać. Po osiągnięciu limitu dziennego wywołania od razu kończą się błędem aż do 00:00 UTC,
  więc benchmark pomija pozostałe próby modeli z tego połączenia. Szybki start OpenRouter wpisuje 20 i 50, czyli
  darmowy poziom bez wykupionych kredytów (1000 dziennie po zakupie 10 kredytów); lista pokazuje dzisiejszą liczbę.
- **Zmienne środowiskowe** opisane niżej nadal działają jako rezerwa: typ dostawcy bez żadnego połączenia z nich
  korzysta, a strona pokazuje je tylko do odczytu. Preferuj połączenia w interfejsie; zmienne zostaw tylko tam, gdzie
  już ustawia je narzędzie wdrożeniowe.

| Dostawca | Wartość `Provider` | Aktywny, gdy | Zmienne | Wartość Helm (`secrets.providerKeys.*`) |
|---|---|---|---|---|
| Anthropic (Claude) | `ANTHROPIC` | ustawiono klucz | `ANTHROPIC_API_KEY` | `anthropicApiKey` |
| OpenAI | `OPENAI` | ustawiono klucz | `OPENAI_API_KEY` | `openaiApiKey` |
| Google Gemini | `GOOGLE` | ustawiono klucz | `GOOGLE_GENAI_API_KEY` | `googleGenaiApiKey` |
| Mistral | `MISTRAL` | ustawiono klucz | `MISTRAL_API_KEY` | `mistralApiKey` |
| Ollama | `OLLAMA` | ustawiono bazowy URL (domyślnie `http://localhost:11434`) | `OLLAMA_BASE_URL` | `ollamaBaseUrl` |
| Endpoint zgodny z OpenAI | `OPENAI_COMPATIBLE` | ustawiono bazowy URL **i** klucz | `OPENAI_COMPATIBLE_BASE_URL`, `OPENAI_COMPATIBLE_API_KEY` | `openaiCompatibleBaseUrl`, `openaiCompatibleApiKey` |

- Chart zapisuje te wartości w Secrecie `<release>-provider-keys`, odczytywanym tylko przez pody api i workera
  (`envFrom`). W produkcji utwórz Secret samodzielnie, z dokładnie tymi nazwami kluczy, i ustaw
  `secrets.providerKeys.create=false` oraz `secrets.providerKeys.existingSecret=<name>` (albo użyj External
  Secrets, `externalSecrets.providerKeys.remoteKey`). Docker Compose: `deploy/compose/.env`.
- **Ustawienia** (**Settings**) → **Dostawcy LLM** (**LLM providers**) pokazuje, które typy dostawców są
  skonfigurowane (połączeniem albo zmiennymi środowiskowymi); w **Modelach** (**Models**) testujesz profil modelu.
- **Sandboxy nigdy nie dostają klucza dostawcy.** Agent kodujący otrzymuje token przypisany do zadania
  (`bo_job_...`) i wywołuje bramę pod `<public URL>/api/v1/llm/anthropic` lub `/api/v1/llm/openai/v1`. Brama
  sprawdza listę dozwolonych modeli i budżet zadania, dodaje prawdziwy klucz i księguje zużycie
  ([agent-execution.md](agent-execution.md#credentials)). Claude Code działa więc na Twoim kluczu Anthropic,
  a OpenCode na kluczu tego dostawcy, którego używa model kroku.
- Klucze ze zmiennych środowiskowych są odczytywane przy starcie podów; ich zmiana wymaga restartu api i workera.

**Dobre praktyki dla każdego dostawcy**

1. Jeden klucz na środowisko (dev, staging, prod) i na instalację, nazwany tak, aby go rozpoznać, na przykład
   `back-office-prod`.
2. Ustaw limit wydatków u dostawcy **oprócz** budżetów Back Office
   ([costs-insights.md](costs-insights.md#budgets)). Budżety Back Office zatrzymują wywołania agentów, a limit
   u dostawcy jest Twoim zabezpieczeniem, gdyby coś ominęło Back Office.
3. Twórz klucze w ramach organizacji lub konta usługowego, a nie konta osoby.

**Testowanie dowolnego dostawcy**

1. **Ustawienia** → **Dostawcy LLM**: dostawca pokazuje **Klucz API ustawiony**.
2. **Modele** (wymaga `models.manage`): na profilu tego dostawcy naciśnij przycisk testu
   (**Wyślij krótki prompt i zmierz opóźnienie oraz koszt** (**Send a tiny prompt and measure latency and
   cost**)). Odpowiedź zawiera opóźnienie i koszt albo błąd dostawcy.
3. Dla agentów: uruchom krok workflowu używający profilu; koszt uruchomienia pojawi się w **Kosztach**
   (**Costs**).

**Rotacja dowolnego klucza** (bez przestoju):

1. Utwórz nowy klucz u dostawcy; zachowaj stary.
2. Podmień wartość w Secrecie (`helm upgrade --set secrets.providerKeys.<key>=...` albo `kubectl apply` na
   własnym Secrecie).
3. `kubectl -n back-office rollout restart deploy/back-office-api deploy/back-office-worker` (chart
   restartuje pody automatycznie tylko wtedy, gdy sam renderuje Secret).
4. Przetestuj profil w **Modelach**, a następnie usuń stary klucz u dostawcy.

<a id="personal-keys"></a>
## Twoje własne klucze API

Każdy, kto widzi modele, może dodać **własne** połączenie z dostawcą, z własnym kluczem API: **Modele** (**Models**)
→ **Dostawcy LLM** (**LLM providers**) → **Dodaj mój klucz API** (**Add my API key**); administratorzy wybierają
**Dostępne dla: Osobiste (tylko Ty, Twój własny klucz API)** (**Available to: Personal (only you, your own API
key)**). Działa jak połączenie platformowe, z tymi różnicami (ADR 0050):

- Tylko Ty je widzisz, zmieniasz i używasz; administratorzy też go nie widzą. Klucz jest przechowywany
  zaszyfrowany jak każdy inny.
- Modele z niego są oferowane w **Dostępne u dostawców** (**Available from providers**) z oznaczeniem **Osobiste**
  (**Personal**), a profile z nich utworzone są profilami osobistymi (zob.
  [evaluation.md](evaluation.md#model-availability)).
- Obsługuje tylko Twoją pracę: uruchomienia, które startujesz w swoich prywatnych projektach, Twoje benchmarki
  i Twoje testy modeli. Wywołanie dla cudzej pracy jest odrzucane, a model bez wskazanego połączenia nigdy nie
  przechodzi na nie.
- Klucze połączeń są unikalne w całej instalacji, także wśród ukrytych połączeń innych użytkowników; wybierz klucz
  w rodzaju `openrouter-twojeimie`.

<a id="anthropic"></a>
## Anthropic

1. Zaloguj się do Claude Console (platform.claude.com, dawniej console.anthropic.com) kontem organizacji
   z rolą admin lub developer.
2. Utwórz dedykowany **workspace** dla Back Office (**Settings** → **Workspaces** → **Create workspace**),
   aby jego klucze, limity zapytań i limit wydatków były oddzielone od innych zastosowań.
3. **Settings** → **API keys** → **Create key**, wybierz workspace, nazwa `back-office-prod`, skopiuj klucz
   (`sk-ant-...`).
4. W ustawieniach workspace’u ustaw miesięczny **limit wydatków** (spend limit).
5. Umieść go w `ANTHROPIC_API_KEY` / `secrets.providerKeys.anthropicApiKey`.

Klucze Anthropic nie mają modelu uprawnień drobniejszego niż workspace; to workspace wyznacza zakres.
Claude Code wykonuje też wywołania w tle małym modelem: zezwól na niego w zadaniach agentów przez
`RUNNER_EXTRA_ALLOWED_MODELS=anthropic/claude-haiku-*` (`backoffice.runner.extra-allowed-models`), w przeciwnym
razie brama odpowie `model-not-allowed`.
Dokumentacja: https://docs.claude.com/en/api/overview

<a id="openai"></a>
## OpenAI

1. Zaloguj się na platform.openai.com → wybierz lub utwórz **projekt** dla Back Office (**Settings** →
   **Projects** → **Create project**).
2. **Settings** → **Project** → **API keys** → **Create new secret key**. Owned by: **Service account**
   (przetrwa odejście pracowników), project: projekt Back Office.
3. **Permissions**: **Restricted**, a następnie nadaj **Write** dla **Model capabilities**
   (`/v1/chat/completions`, używane zarówno przez klienta w aplikacji, jak i przez bramę) oraz **Read** dla
   **Models**; resztę pozostaw na **None**.
4. Skopiuj klucz (`sk-proj-...` / `sk-svcacct-...`).
5. W **Settings** → **Project** → **Limits** ustaw miesięczny budżet i, jeśli chcesz, listę dozwolonych modeli.
6. Umieść go w `OPENAI_API_KEY` / `secrets.providerKeys.openaiApiKey`.

Dokumentacja: https://platform.openai.com/docs/api-reference/authentication

<a id="google"></a>
## Google Gemini

1. Otwórz Google AI Studio (aistudio.google.com) → **Get API key** → **Create API key** i wybierz projekt
   Google Cloud przeznaczony dla Back Office (w razie potrzeby utwórz go).
2. W konsoli Google Cloud tego projektu → **APIs & Services** → **Credentials** → klucz →
   **API restrictions** → **Restrict key** → tylko **Generative Language API**. (Ograniczenie aplikacji po IP
   jest możliwe, gdy Twoje adresy IP ruchu wychodzącego są stałe).
3. Włącz rozliczenia w projekcie i ustaw **alert budżetowy** (**Billing** → **Budgets & alerts**).
4. Umieść go w `GOOGLE_GENAI_API_KEY` / `secrets.providerKeys.googleGenaiApiKey`.

Agenci łączą się z Gemini przez endpoint bramy zgodny z OpenAI
(`generativelanguage.googleapis.com/v1beta/openai`) tym samym kluczem.
Dokumentacja: https://ai.google.dev/gemini-api/docs/api-key

<a id="mistral"></a>
## Mistral

1. Zaloguj się na console.mistral.ai i wybierz workspace dla Back Office (lub utwórz go).
2. **API keys** → **Create new key**, nazwa `back-office-prod`, opcjonalnie data wygaśnięcia; skopiuj klucz.
3. W ustawieniach rozliczeń workspace’u ustaw miesięczny limit zużycia.
4. Umieść go w `MISTRAL_API_KEY` / `secrets.providerKeys.mistralApiKey`.

Dokumentacja: https://docs.mistral.ai/getting-started/quickstart/

<a id="ollama"></a>
## Ollama

Ollama nie ma klucza API. Back Office potrzebuje tylko URL-a serwera.

1. Uruchom Ollamę tam, gdzie pody api i workera mogą się z nią połączyć, na przykład jako Service
   `http://ollama.ai-models.svc.cluster.local:11434` (Service `ollama` w namespace `ai-models`), i pobierz modele (`ollama pull qwen3-coder`).
2. Ustaw `OLLAMA_BASE_URL` / `secrets.providerKeys.ollamaBaseUrl` na ten URL **bez** `/v1`. Brama dopisuje
   `/v1` dla agentów.
3. Jeśli NetworkPolicies chartu Back Office są włączone, zezwól na ruch wychodzący z podów api i workera do
   portu Ollamy.

**Ograniczenia**: Ollama zawsze jest traktowana jako skonfigurowana z powodu domyślnego URL-a, więc
**Ustawienia** pokazują ją nawet wtedy, gdy żaden serwer nie działa; prawdę mówi test profilu w **Modelach**.
Ollama nie ma uwierzytelniania: nigdy nie wystawiaj jej poza sieć klastra.

<a id="openai-compatible"></a>
## Endpointy zgodne z OpenAI

Jeden dodatkowy endpoint obsługujący protokół chat-completions OpenAI: OpenRouter, Groq, Together, vLLM,
LM Studio, wewnętrzna brama.

1. Utwórz klucz u tego dostawcy (OpenRouter: openrouter.ai → **Settings** → **API keys**, ustaw limit kredytu
   na kluczu; Groq: console.groq.com → **API keys**; vLLM: wartość przekazana do `--api-key`).
2. Ustaw `OPENAI_COMPATIBLE_BASE_URL` na adres bazowy **wraz ze** ścieżką wersji, na przykład
   `https://openrouter.ai/api/v1` lub `https://api.groq.com/openai/v1`. Back Office dopisuje
   `/chat/completions`.
3. Ustaw `OPENAI_COMPATIBLE_API_KEY`. Dostawca jest traktowany jako skonfigurowany tylko przy obu wartościach;
   dla serwera vLLM bez uwierzytelniania wpisz dowolną niepustą wartość zastępczą.
4. Utwórz profile modeli z dostawcą **OPENAI_COMPATIBLE** i identyfikatorem modelu u dostawcy.

**Ograniczenia**: tylko jeden endpoint zgodny z OpenAI na instalację. Bazowy URL i klucz muszą pochodzić od
tego samego dostawcy (klucz OpenRouter użyty z URL-em Groq zwraca `401`).

Błędy i limity zapytań: [troubleshooting.md](troubleshooting.md#providers).
