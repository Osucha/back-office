# IDE i MCP

Back Office udostępnia serwer MCP pod adresem `<public URL>/mcp`. Asystenci kodowania w Twoim IDE (Claude Code,
Cursor, VS Code) mogą z niego korzystać, aby sprawdzić, co czeka na Ciebie, odczytać artefakty uruchomień,
rozstrzygać bramki i uruchamiać workflowy – zawsze z Twoimi własnymi uprawnieniami. Skrypty używają tych
samych osobistych tokenów dostępu względem REST API.

**Kogo to dotyczy:** każdego użytkownika, który chce mieć Back Office w IDE lub w skryptach; administratora
platformy w zakresie czasu życia tokenów i przełącznika MCP.

<a id="pat"></a>
## Utwórz osobisty token dostępu

Serwer MCP akceptuje **wyłącznie osobiste tokeny dostępu (PAT)** (`Authorization: Bearer bo_pat_...`).
Ciasteczko sesji lub token CI są odrzucane z `401` `personal-token-required`.

1. Otwórz **Moje konto** (**My account**) (`/account`) → **Osobiste tokeny dostępu**
   (**Personal access tokens**). Potrzebujesz `tokens.personal`, które nadaje domyślna rola `member`.
2. Naciśnij **Utwórz token** (**Create token**).
3. **Nazwa** (**Name**): do czego służy token, na przykład `cursor-laptop`.
4. **Wygasa za (dni)** (**Expires in (days)**): maksymalnie do limitu platformy (**Ustawienia platformy**
   (**Platform settings**) → **Osobiste tokeny dostępu** → **Maksymalny czas życia (dni)**
   (**Maximum lifetime (days)**), domyślnie 90, nigdy więcej niż 365; domyślna wartość serwera
   `BACKOFFICE_AUTH_PERSONAL_TOKEN_MAX_DAYS`).
5. **Ogranicz do uprawnień (opcjonalnie)** (**Limit to permissions (optional)**): wybierz tylko to, czego
   potrzebuje asystent. Token nigdy nie może mieć więcej uprawnień niż Ty i z czasem podąża za Twoimi
   uprawnieniami (gdy stracisz rolę, token również ją traci).

   | Zastosowanie | Uprawnienia do zaznaczenia |
   |---|---|
   | Odczyt uruchomień, artefaktów i wskaźników | `project.view` |
   | + zatwierdzanie lub odsyłanie bramek do poprawy | `project.view`, `run.approve` |
   | + uruchamianie workflowów | `project.view`, `run.start` |
   | + przeszukiwanie biblioteki zadań | `library.view` |

   Zawężony token zachowuje dostęp do wszystkich projektów tylko wtedy, gdy podzbiór zawiera
   `admin.projects.all`.
6. **Zezwól na decyzje bramek przez MCP** (**Allow gate decisions via MCP**): pozostaw wyłączone, chyba że
   chcesz zatwierdzać lub odrzucać bramki z IDE. Bez tej opcji narzędzie MCP `decide_gate` jest odrzucane
   (a odmowa trafia do audytu), niezależnie od uprawnień tokenu. Powodem jest prompt injection: agent w Twoim
   IDE czyta artefakty, przeglądy i treść zgłoszeń, które może napisać każdy, a taki tekst może kazać
   agentowi zatwierdzić bramkę. Gdy opcja jest włączona, nie wyłączaj w IDE potwierdzania narzędzi
   destrukcyjnych (`decide_gate` jest oznaczone jako destrukcyjne), rozstrzygaj tylko bramki, które
   obejrzałeś, i pamiętaj, że zasada czterech oczu nadal obowiązuje. Opcja jest widoczna na liście tokenów
   jako **Decyzje bramek przez MCP** (**MCP gate decisions**) i nie da się jej później zmienić: utwórz nowy
   token.
7. **Zezwól na edycję workflowów przez MCP** (**Allow workflow edits via MCP**): pozostaw wyłączone, chyba
   że chcesz, aby agent w IDE tworzył i zmieniał workflowy. Bez tej opcji narzędzia MCP `create_workflow`
   i `update_workflow` są odrzucane, niezależnie od uprawnień tokenu, bo agent z tym tokenem mógłby
   samodzielnie zmienić workflow (na przykład usunąć bramkę ludzką). Opcja jest widoczna na liście tokenów
   jako **Edycja workflowów przez MCP** (**MCP workflow edits**) i, tak jak opcja bramek, nie da się jej
   później zmienić.
8. Naciśnij **Utwórz token**, skopiuj token (`bo_pat_` + 40 znaków) i naciśnij **Token skopiowany**
   (**I copied the token**). Jest wyświetlany tylko raz; Back Office przechowuje wyłącznie jego skrót.

Token działa też z REST: `curl -H "Authorization: Bearer bo_pat_..." https://backoffice.example.com/api/v1/me`
(nagłówek CSRF nie jest potrzebny).

**Rotacja**: utwórz nowy token, wpisz go do konfiguracji IDE, a następnie **Unieważnij** (**Revoke**) stary
na tej samej stronie (lista pokazuje **Ostatnie użycie** (**Last used**)). Unieważnienie tokenu
i dezaktywacja Twojego użytkownika działają od następnego żądania. Utworzenie i unieważnienie trafiają do
dziennika audytu (`PAT_CREATE`, `PAT_REVOKE`).

**Trzymaj token poza repozytoriami.** Zamiast pliku projektu, który mógłby zostać zacommitowany, używaj
plików konfiguracyjnych na poziomie użytkownika, zmiennych środowiskowych lub okna IDE do wpisywania
sekretów.

**Moje konto** → **Połącz swoje IDE** (**Connect your IDE**) pokazuje poniższe fragmenty z uzupełnionym
adresem Twojego Back Office.

<a id="claude-code"></a>
## Claude Code

1. Umieść token w zmiennej środowiskowej swojej powłoki, na przykład `export BO_PAT=bo_pat_...`.
2. Uruchom (zakładka **Claude Code**, blok **Terminal**):

```sh
claude mcp add --transport http back-office https://backoffice.example.com/mcp --header "Authorization: Bearer $BO_PAT"
```

   Dodaj `--scope user`, aby serwer był dostępny w każdym projekcie, a nie tylko w bieżącym.
3. W Claude Code uruchom `/mcp`: `back-office` powinien mieć stan **connected** i pokazywać osiem narzędzi.
4. Zapytaj na przykład: „What is waiting for me in Back Office?”.

Claude Code przechowuje nagłówek we własnej konfiguracji (`~/.claude.json`); aby rotować token, uruchom
`claude mcp remove back-office` i dodaj serwer ponownie z nowym tokenem.
Dokumentacja: https://docs.claude.com/en/docs/claude-code/mcp

<a id="cursor"></a>
## Cursor

1. Otwórz **Cursor Settings** → **MCP** (Tools & Integrations) → **Add new MCP server** albo edytuj plik
   bezpośrednio. Użyj pliku globalnego `~/.cursor/mcp.json`, aby token nie trafił do repozytorium; etykieta
   fragmentu `.cursor/mcp.json` to wariant per projekt.
2. Wklej (zakładka **Cursor**):

```json
{
  "mcpServers": {
    "back-office": {
      "url": "https://backoffice.example.com/mcp",
      "headers": { "Authorization": "Bearer <your personal access token>" }
    }
  }
}
```

3. Zastąp wartość zastępczą tokenem i zapisz; serwer zmieni kolor na zielony w ustawieniach MCP i pokaże
   swoje narzędzia. Nowsze wersje Cursora akceptują też `"Bearer ${env:BO_PAT}"`, aby odczytać token ze
   środowiska.

Dokumentacja: https://cursor.com/docs/context/mcp

<a id="vscode"></a>
## VS Code

VS Code (z trybem agenta GitHub Copilot lub inną wtyczką obsługującą MCP) pyta o token i przechowuje go
w swoim magazynie sekretów, więc plik można bezpiecznie commitować.

1. Utwórz `.vscode/mcp.json` w workspace (lub uruchom **MCP: Open User Configuration** dla wszystkich
   workspace’ów) z zawartością (zakładka **VS Code**):

```json
{
  "inputs": [
    { "type": "promptString", "id": "back-office-token", "description": "Back Office personal access token", "password": true }
  ],
  "servers": {
    "back-office": {
      "type": "http",
      "url": "https://backoffice.example.com/mcp",
      "headers": { "Authorization": "Bearer ${input:back-office-token}" }
    }
  }
}
```

2. Naciśnij **Start** nad wpisem serwera; VS Code jednorazowo zapyta o token.
3. Rotacja: uruchom **MCP: Reset Cached Inputs** (lub usuń zapisaną wartość) i uruchom serwer ponownie
   z nowym tokenem.

Dokumentacja: https://code.visualstudio.com/docs/copilot/customization/mcp-servers

<a id="mcp-tools"></a>
## Narzędzia MCP

Każde narzędzie wywołuje ten sam przypadek użycia co REST API, więc obowiązują te same kontrole uprawnień.
Odrzucone wywołanie wraca jako błąd narzędzia, a nie błąd transportu.

| Narzędzie | Działanie | Wymaga |
|---|---|---|
| `list_my_turns` | Bramki, które możesz rozstrzygnąć, i nieudane uruchomienia, które możesz ponowić, od najnowszych | `project.view` (elementy filtrowane przez `run.approve` / `run.start`) |
| `get_run` | Podsumowanie uruchomienia: status, wynik, koszt, merge request, węzły, lista artefaktów | `project.view` |
| `get_artifact` | Zawartość jednego artefaktu (spec, plan, tasks, review, diff) według id lub nazwy | `project.view` |
| `start_workflow` | Rozpoczyna uruchomienie workflowu, opcjonalnie dla klucza zgłoszenia | `run.start` |
| `decide_gate` | Zatwierdza, odsyła do poprawy lub odrzuca bramkę z komentarzem; zatwierdzenie wymaga id wymaganych pozycji listy kontrolnej | `run.approve`, reguła zatwierdzających bramki i token utworzony z opcją **Zezwól na decyzje bramek przez MCP** |
| `search_task_library` | Wyszukuje szablony zadań i umiejętności (skills) po tekście | `library.view` |
| `get_project_context` | Konstytucja, repozytorium, punkty wejścia CI, workflowy i ostatnie uruchomienia | `project.view` |
| `get_delivery_insights` | Wskaźniki dostarczania jednego projektu lub wszystkich widocznych projektów | `project.view` |
| `list_projects` | Projekty, które widzisz | `project.view` |
| `get_workflow_palette` | Z czego można zbudować workflow: węzły sterujące, szablony zadań z portami i ustawieniami, pola stanu uruchomienia | `project.view` |
| `get_workflow` | Jeden workflow z pełną definicją (węzły, krawędzie, wyzwalacze) | `project.view` |
| `validate_workflow` | Sprawdza definicję bez zapisywania i wypisuje błędy oraz ostrzeżenia | `project.view` |
| `create_workflow` | Tworzy workflow; najpierw jest sprawdzany i przy błędach nic nie zostaje zapisane | `workflow.edit` i token utworzony z opcją **Zezwól na edycję workflowów przez MCP** |
| `update_workflow` | Zastępuje definicję workflowu nową wersją; trwające uruchomienia zachowują swoją | `workflow.edit` i token utworzony z opcją **Zezwól na edycję workflowów przez MCP** |

Zawartość artefaktów, podsumowania i błędy węzłów oraz pytania bramek wracają w blokach `<untrusted-data>`
z polem `notice`: napisali je agenci, modele lub autorzy zgłoszeń, więc agent w Twoim IDE musi traktować je
jako dane, nigdy jako instrukcje. Narzędzia odczytu są oznaczone jako tylko do odczytu; `decide_gate` jest
oznaczone jako destrukcyjne i nieidempotentne, więc klienci respektujący adnotacje narzędzi MCP pytają Cię
przed jego wywołaniem.

Decyzje dotyczące bramek podjęte przez MCP są zapisywane tak samo jak decyzje w UI, z Tobą jako wykonawcą,
i pojawiają się w pochodzeniu (provenance) uruchomienia ([runs.md](runs.md#provenance)).

**Ograniczenia**

- Tylko narzędzia; serwer nie udostępnia zasobów (resources) ani promptów MCP.
- Administrator może wyłączyć serwer przez `BACKOFFICE_MCP_ENABLED=false`.
- Za dołączonym podem web ścieżka `/mcp` musi być kierowana do API tak samo jak `/api`. Jeśli Twoje IDE
  zgłasza odpowiedź HTML lub `404` z `/mcp`, zobacz [troubleshooting.md](troubleshooting.md#login).
