# Magazyn, podpisy i obserwowalność

Trzy elementy infrastruktury, od których Back Office zależy poza bazą danych: magazyn obiektów na duże
treści, klucz podpisujący Ed25519 stojący za atestacjami pochodzenia (provenance) i przydziałami zadań dla
runnerów oraz eksport telemetrii do Langfuse lub dowolnego backendu OpenTelemetry.

**Kogo to dotyczy:** administratora platformy.

<a id="storage"></a>
## Magazyn obiektów

Duże treści trafiają do magazynu blobów; PostgreSQL przechowuje tylko metadane i małe treści. Do magazynu
trafiają: artefakty powyżej 64 kB, diffy, logi runnerów, wyniki agentów powyżej 64 kB oraz pliki przesyłane
z sandboxów (artefakty i logi powyżej 2 MB, wysyłane przez presigned URL-e `PUT`).

| Typ | Zastosowanie | Uwagi |
|---|---|---|
| `s3` | każda instalacja z więcej niż jedną repliką; domyślny w Helm | AWS S3, MinIO, Ceph RGW, Cloudflare R2, GCS (XML interop), bramy S3 |
| `filesystem` | tylko jedna replika: laptop, testy, instalacja na jednej VM; domyślny bez Helm | linki presigned są serwowane przez API pod `/blobs/...` |

| Ustawienie | Zmienna | Wartość Helm |
|---|---|---|
| Typ | `BACKOFFICE_STORAGE_TYPE` | `storage.type` (`s3`) |
| Endpoint (puste = AWS) | `BACKOFFICE_STORAGE_S3_ENDPOINT` | `storage.s3.endpoint` (wyliczany przy `minio.enabled`) |
| Endpoint wpisywany do presigned URL-i | `BACKOFFICE_STORAGE_S3_PRESIGN_ENDPOINT` | `api.extraEnv` |
| Region | `BACKOFFICE_STORAGE_S3_REGION` | `storage.s3.region` (`us-east-1`; R2 `auto`) |
| Bucket (musi istnieć) | `BACKOFFICE_STORAGE_S3_BUCKET` | `storage.s3.bucket` |
| Adresowanie path-style | `BACKOFFICE_STORAGE_S3_PATH_STYLE` | `storage.s3.pathStyle` (`true` dla MinIO, Ceph i większości bram) |
| Statyczne poświadczenia | `BACKOFFICE_STORAGE_S3_ACCESS_KEY` / `_SECRET_KEY` | `storage.s3.accessKey`/`secretKey` → Secret `<release>-storage` lub `storage.s3.existingSecret` |
| Szyfrowanie po stronie serwera | `BACKOFFICE_STORAGE_S3_SSE` (`NONE`, `S3`, `KMS`) | `storage.s3.sse` |
| Poprawka sum kontrolnych dla GCS / bram | `AWS_REQUEST_CHECKSUM_CALCULATION=when_required` | `storage.s3.checksumWhenRequired` |
| Katalog systemu plików | `BACKOFFICE_STORAGE_FILESYSTEM_ROOT` | `storage.filesystem.path` (+ PVC) |
| Klucz podpisujący linki systemu plików | `BACKOFFICE_STORAGE_FILESYSTEM_SIGNING_KEY` | `api.extraEnv` z Secretu |

**Poświadczenia z minimalnymi uprawnieniami** (AWS IAM; inne magazyny mają odpowiedniki):

1. Na AWS preferuj workload identity: pozostaw access key i secret key puste i nadaj kontu usługowemu
   api/workera rolę IAM (IRSA lub EKS Pod Identity). GCS przez interfejs S3 zawsze wymaga klucza HMAC.
2. Zezwól tylko na bucket: `s3:ListBucket` na `arn:aws:s3:::<bucket>` (health check i usuwanie po prefiksie)
   oraz `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, `s3:AbortMultipartUpload` na `arn:aws:s3:::<bucket>/*`.
   Przy `sse: KMS` dodaj `kms:GenerateDataKey` i `kms:Decrypt` na kluczu.
3. MinIO: utwórz użytkownika i politykę z tymi samymi akcjami na buckecie (`mc admin user add`,
   `mc admin policy create`, `mc admin policy attach`). Cloudflare R2: **R2** → **Manage API tokens** →
   **Object Read & Write** dla tego jednego bucketu. GCS: **Cloud Storage** → **Settings** →
   **Interoperability** → klucz HMAC dla konta usługowego z **Storage Object Admin** na buckecie.
4. Włącz wersjonowanie bucketu na potrzeby kopii zapasowych.

**Osiągalność z sandboxów.** Agenci przesyłają pliki przez presigned URL-e wskazujące na endpoint S3 (lub
endpoint presign). Ich proxy ruchu wychodzącego musi zezwalać na ten host: dodaj go do zawsze dozwolonych
hostów runner-controllera (`runner-controller.egress.always-allow`, na przykład `extraEnv`
`RUNNER_CONTROLLER_EGRESS_ALWAYSALLOW_0=minio.example.com` w charcie runnera) oraz do
`egressProxy.allowedCidrs`, jeśli ma adres prywatny ([agent-execution.md](agent-execution.md#network)). Gdy API
łączy się z MinIO w klastrze pod nazwą wewnętrzną, ustaw `BACKOFFICE_STORAGE_S3_PRESIGN_ENDPOINT` na nazwę
publiczną.

**Test**: `/actuator/health` zawiera komponent `blobStore` (`HeadBucket` z limitem czasu 3 s albo „root
writable” dla systemu plików); `kubectl -n back-office logs deploy/back-office-api | grep blob` pokazuje błędy.

**Rotacja kluczy statycznych**: utwórz drugi klucz dla tego samego użytkownika/polityki, zaktualizuj Secret,
zrestartuj api i workera, usuń stary klucz.

**Ograniczenia**: chart Helm nie ma jeszcze wartości dla endpointu presign ani klucza podpisującego linki
systemu plików; ustaw je przez `api.extraEnv`. Bez `BACKOFFICE_STORAGE_FILESYSTEM_SIGNING_KEY` linki systemu
plików przestają działać po restarcie. Retencję treści blobów opisuje `docs/guides/configuration.md`.

<a id="signing"></a>
## Klucze podpisujące

Jeden klucz Ed25519 podpisuje atestacje pochodzenia uruchomień i zadań agentów ([runs.md](runs.md#provenance))
oraz przydziały zadań, które runner-controllery weryfikują przed uruchomieniem sandboxa. Klucze publiczne są
publikowane bez uwierzytelniania pod `<public URL>/.well-known/back-office/keys` (zbiór JWK; każdy klucz
`active` lub `retired`).

| Zmienna (indeks `n` = 0, 1, ...) | Znaczenie |
|---|---|
| `BACKOFFICE_SIGNING_KEYS_<n>_ID` | Id klucza, na przykład `bo-20261006` |
| `BACKOFFICE_SIGNING_KEYS_<n>_PRIVATE_KEY_PKCS8_BASE64` | Klucz prywatny, PKCS#8 DER, base64 (pomiń dla kluczy wycofanych) |
| `BACKOFFICE_SIGNING_KEYS_<n>_PUBLIC_KEY_X509_BASE64` | Klucz publiczny, X.509 SubjectPublicKeyInfo DER, base64 |
| `BACKOFFICE_SIGNING_KEYS_<n>_ACTIVE` | `true` dla tego jednego klucza, który podpisuje |
| `BACKOFFICE_SIGNING_ALLOW_EPHEMERAL` | `true` (local/compose): generuj jednorazowy klucz, gdy żaden nie jest ustawiony; chart ustawia `false` |

**Instalacja**

1. Domyślnie (Helm): `signing.generate=true` sprawia, że Job hooka pre-install jednorazowo tworzy Secret
   `<release>-signing`; nigdy nie jest on nadpisywany i zostaje zachowany przy odinstalowaniu. Nic nie musisz
   robić.
2. Własny klucz: `deploy/scripts/gen-signing-key.sh --secret back-office-signing --namespace back-office`
   (wymaga `openssl` 1.1.1+ i `kubectl`), a następnie ustaw `signing.existingSecret=back-office-signing`.
   Bez `--secret` skrypt wypisuje cztery linie do pliku `.env`.
3. Zrób kopię zapasową Secretu: utrata klucza prywatnego nie psuje starych atestacji (weryfikują je klucze
   publiczne), ale klucz, nad którym nie potrafisz udowodnić kontroli, jest bezwartościowy jako dowód.

**Test**: `curl https://backoffice.example.com/.well-known/back-office/keys` wypisuje klucz; po zakończeniu
uruchomienia zakładka **Pochodzenie** (**Provenance**) na stronie uruchomienia pokazuje jego atestacje.

**Rotacja** (co rok albo natychmiast po wycieku):

1. `deploy/scripts/gen-signing-key.sh --secret back-office-signing -n back-office --index 1 --merge` dodaje
   klucz 1 do Secretu.
2. Ustaw stary klucz jako nieaktywny: zmień `BACKOFFICE_SIGNING_KEYS_0_ACTIVE` na `false`.
3. `kubectl -n back-office rollout restart deploy/back-office-api deploy/back-office-worker`.
4. Zachowaj na zawsze publiczną część starego klucza w Secrecie, aby wcześniejsze atestacje nadal dawały się
   zweryfikować. Runner-controllery pobierają nowy klucz razem ze swoją konfiguracją (co 5 minut,
   a natychmiast po napotkaniu nieznanego id klucza).

Runbook: `docs/runbooks/upgrade.md` („Signing key rotation”). Weryfikacja offline: `docs/guides/provenance.md`.

<a id="observability"></a>
## Obserwowalność: Langfuse, OpenTelemetry, Prometheus

**Langfuse (ślady LLM).** Back Office eksportuje ślady (traces) przez OTLP/HTTP; Langfuse je przyjmuje.

1. W Langfuse otwórz projekt dla Back Office → **Settings** → **API Keys** → **Create new API keys**;
   skopiuj klucz publiczny (`pk-lf-...`) i klucz tajny (`sk-lf-...`).
   Dokumentacja: https://langfuse.com/docs/opentelemetry/get-started
2. Zbuduj wartość nagłówka: `Basic ` + base64 z `pk-lf-...:sk-lf-...`
   (`printf %s "pk-lf-...:sk-lf-..." | base64 -w0`).
3. Skonfiguruj:

| Ustawienie | Zmienna | Wartość Helm |
|---|---|---|
| Eksport śladów | `OTEL_TRACES_ENABLED=true` | wyliczane z `langfuse.enabled` lub `otel.enabled` |
| Endpoint | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, na przykład `https://cloud.langfuse.com/api/public/otel/v1/traces` | `otel.tracesEndpoint` (dołączony Langfuse: wyliczany) |
| Nagłówek Authorization | `OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION` | `langfuseAuth.publicKey` + `secretKey` albo `langfuseAuth.existingSecret` (pełna wartość nagłówka), albo `otel.headersAuthorization` |
| Linki do śladów w UI | `LANGFUSE_ENABLED`, `LANGFUSE_URL` | wyliczane dla dołączonego subchartu; zewnętrzny Langfuse: `api.extraEnv` |
| Próbkowanie | `TRACING_SAMPLING` | `api.config.tracingSampling` (`1.0`) |

4. **Ustawienia** (**Settings**) → **Langfuse** → **Testuj połączenie** (**Test connection**) sprawdza, czy
   `LANGFUSE_URL/api/public/health` odpowiada. Nie sprawdza kluczy: potwierdź, że ślady testowego
   uruchomienia docierają do Langfuse.
5. Rotacja: utwórz nowe klucze w Langfuse, podmień Secret z nagłówkiem, zrestartuj api i workera, usuń stare
   klucze.

Prompty i odpowiedzi modeli nie są logowane, chyba że `AI_LOG_PROMPTS` / `AI_LOG_COMPLETIONS` mają wartość
`true` (tylko w środowisku deweloperskim).

**Dowolny backend OpenTelemetry**: `otel.enabled=true`, `otel.tracesEndpoint` (i opcjonalnie
`otel.metricsEnabled`, `otel.logsEnabled` z ich endpointami) wskazujące na Twój collector; przykładowy
collector to `deploy/observability/otel-collector.yaml`.

**Prometheus**: każdy pod serwuje `/actuator/prometheus` (runner-controllery na porcie `8090`). Chart może
wygenerować `ServiceMonitor`, reguły alertów (błędy i opóźnienia API, zablokowane elementy pracy, pule
z zadaniami w kolejce i bez runnera, budżety) oraz dashboardy Grafany: `metrics.serviceMonitor`,
`metrics.prometheusRule`, `metrics.dashboards`.
