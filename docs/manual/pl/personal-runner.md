# Mój komputer jako runner

Kroki agentowe (Spec Kit, implementacja, benchmarki kodowania) działają w piaskownicach [floty runnerów](runner-fleet.md).
Kroki Twoich **projektów prywatnych** ([Moja praca](my-work.md#private-projects)) możesz zamiast tego uruchamiać na
**własnym komputerze**, podobnie jak GitLab runner: mały kontroler w Dockerze na Twoim komputerze pyta Back Office o
pracę i uruchamia każdy krok w osobnym kontenerze-piaskownicy. Opcjonalnie Claude Code w tych krokach używa **Twojego
własnego logowania do Claude** zamiast bramy LLM Back Office.

<a id="personal-runner"></a>
## Co to daje

- Dostajesz jedną **osobistą pulę runnerów**. Wykonuje ona wyłącznie kroki **Twoich projektów prywatnych** w
  przebiegach **uruchomionych przez Ciebie** (także przyciskiem **Start** w Mojej pracy). Kroki projektów zespołowych i
  innych osób nigdy tam nie trafiają.
- Gdy masz osobisty runner, kroki Twoich projektów prywatnych **czekają na niego**: przy wyłączonym komputerze stoją w
  kolejce (do limitu czasu kolejki, domyślnie 24 h) i ruszają, gdy komputer wróci. Usuń osobisty runner, żeby znów
  korzystać ze wspólnej floty.
- Możesz podłączyć kilka komputerów; następny krok bierze dowolny dostępny.
- Administratorzy widzą Twoją pulę w **Administracja → Flota runnerów** (z właścicielem) i mogą ją odwołać.

<a id="connect"></a>
## Podłączenie komputera

Potrzebujesz Dockera (na Windows i macOS: Docker Desktop) i trzech obrazów: `back-office/runner-controller`,
`back-office/runner` i `back-office/egress-proxy`. Jeśli Twoja organizacja ich nie publikuje, zbuduj je raz z
repozytorium:

```bash
docker build -t back-office/runner:dev runner/
docker build -t back-office/egress-proxy:dev backend/runner-controller/egress-proxy/
docker build -t back-office/runner-controller:dev -f backend/runner-controller/Dockerfile backend/
```

Następnie otwórz **Moje konto → Mój komputer jako runner**:

1. Wybierz, jak Claude Code łączy się z modelem (poniżej).
2. Kliknij **Podłącz ten komputer**. Back Office tworzy Twoją pulę i **jednorazowy token rejestracji** (ważny 24
   godziny, pokazywany raz) oraz buduje z nim polecenie `docker run` dla bash lub PowerShell.
3. Gdy używasz własnego logowania do Claude (patrz niżej), ustaw je w terminalu w zmiennej (nie opuszcza Twojego
   komputera i nie jest częścią polecenia): `CLAUDE_CODE_OAUTH_TOKEN`. Tokenów git nie ustawia się na runnerze:
   tokeny repozytoriów projektu trafiają do każdego zadania zaszyfrowane do klucza Twojego runnera
   ([Tokeny dostępu do repozytoriów w projekcie](integrations-git.md#git-project-tokens)). Runner uruchomiony
   wcześniej z `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH` nadal używa ich dla repozytoriów bez tokenu projektu.
4. Uruchom polecenie w tym samym terminalu. Kontroler rejestruje się, zapisuje swój token runnera w
   `/var/lib/back-office-runner` (w systemie plików Dockera) i wstaje razem z Dockerem. Token rejestracji nie jest już
   potrzebny; żeby podłączyć kolejny komputer, kliknij ponownie **Podłącz ten komputer**.

Komputer pojawia się w **Podłączonych komputerach** jako **online** w ciągu minuty. **Odłącz** unieważnia jego token
(kroki, które wykonuje, wracają do kolejki); kontener zatrzymasz poleceniem `docker rm -f back-office-runner`.

Gdy samo Back Office działa na tym samym komputerze (testy lokalne), polecenie łączy się z nim przez
`host.docker.internal` i pozwala proxy wyjściowemu łączyć się z adresami prywatnymi. Piaskownice wołają Back Office
pod adresem `backoffice.runner.api-url` (`RUNNER_API_URL`); dla Back Office uruchomionego z profilem `local` jest to
`http://host.docker.internal:8080`.

<a id="credentials"></a>
## Claude Code z własnym logowaniem

- **Moje własne logowanie do Claude (subskrypcja)**: Claude Code w piaskownicy loguje się tokenem Twojego konta i
  łączy się bezpośrednio z Anthropic. Token utworzysz raz poleceniem `claude setup-token` (otwiera przeglądarkę i
  wypisuje długo ważny token); przed uruchomieniem runnera wstaw go do `CLAUDE_CODE_OAUTH_TOKEN`. Back Office nie
  mierzy tych wywołań: koszt kroku to **0** w przebiegach, budżetach i analizach, a obok widać jego
  [koszt w cenach API](costs-insights.md#api-cost) (szacunek Claude Code). Budżety i lista modeli bramy nie mają tu zastosowania.
- **Brama LLM Back Office**: jak na wspólnej flocie; Twój komputer daje tylko moc obliczeniową. Zużycie jest
  mierzone, budżety obowiązują.

Runner przy każdym zapytaniu o pracę podaje Back Office, jakich agentów kodujących ma i czy Twoje logowanie jest
ustawione (ADR 0050). Gdy pierwszy raz zgłosi Twoje logowanie, dostajesz profile modeli **Claude Opus (my login)**,
**Claude Sonnet (my login)** i **Claude Haiku (my login)**; **Konto** (**Account**) → **Moje modele** (**My models**)
pokazuje stan runnera i modele, których możesz użyć. Runner dostępny bez logowania sprawia, że te modele są
niedostępne (mówią o tym listy wyboru modeli i start uruchomienia), zamiast że zadanie nie powiedzie się później.

Kroki OpenCode zawsze używają bramy. Możesz przełączyć tryb w każdej chwili; po przełączeniu na własne logowanie
uruchom runner ponownie z nowym poleceniem (zmienna musi trafić do kontenera).

<a id="security"></a>
## Zanim zaczniesz

- **Licencja.** Sprawdź, czy Twój plan Claude lub umowa Twojej organizacji pozwala używać Claude Code w ten sposób
  (automatyczne kroki uruchamiane z innego systemu). W razie wątpliwości zapytaj osobę, która administruje Waszą
  licencją Claude, albo użyj bramy.
- **Twoje logowanie jest w piaskownicy.** Claude Code potrzebuje tokenu, więc agent mógłby go odczytać. Używaj
  własnego logowania tylko z repozytoriami i zgłoszeniami, którym ufasz; piaskownica nadal łączy się wyłącznie z Back
  Office, Twoim serwerem git, rejestrami pakietów i `api.anthropic.com` (resztę blokuje proxy wyjściowe). Patrz
  [Prompt injection](agent-execution.md#prompt-injection).
- **Dostęp do Dockera.** Kontroler steruje Twoim demonem Dockera (żeby uruchamiać piaskownice). Uruchamiaj go tylko na
  własnym komputerze. Backend DOCKER używa runc, nie gVisor: izolacja jest słabsza niż na wspólnej flocie.
