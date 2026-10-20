Two receptionists editing the same owner silently overwrite each other: the last save wins. Add optimistic locking to owner edits.

1. Schema: a column `version` in `owners`, integer, `NOT NULL DEFAULT 0`, in all three `src/main/resources/db/{h2,mysql,postgres}/schema.sql`. `db/h2/data.sql` and `db/mysql/data.sql` insert owners positionally: give every owners insert the seventh value `0` (or a column list). Existing rows start at version 0.
2. `Owner` gets a JPA `@Version` property named `version` with `getVersion()` / `setVersion(...)`.
3. `owners/createOrUpdateOwnerForm.html` carries it in an `<input type="hidden">` whose request parameter name is `version`; on the edit form its value is the owner's current version (`0` for the sample owners).
4. `POST /owners/{ownerId}/edit` with a stale `version` changes nothing in the database and answers with the edit form again: status 200, view `owners/createOrUpdateOwnerForm`, showing the message with key `concurrentModification` whose English text is exactly `This owner was changed by someone else. Reload the page and apply your changes again.` Never a 500. After reloading the form (current version) the save succeeds.
5. Add `concurrentModification` to `messages.properties` and every translated `messages_*.properties`.
6. First write a test that reproduces the lost update deterministically (two submits based on the same version; no sleeps, no threads) and fails on the current code, then fix it. Do not modify existing files under `src/test/java`; add new test classes. All tests green.
7. Explain the strategy and its limits in a short code comment (for example what else increments an owner's version).

Run ./mvnw -B -q spring-javaformat:apply before you finish.
