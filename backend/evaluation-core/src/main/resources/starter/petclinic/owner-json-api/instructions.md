Add a small JSON API for owners next to the existing HTML pages.

1. `POST /api/owners` with `Content-Type: application/json` and the body `{"firstName", "lastName", "address", "city", "telephone"}` creates an owner. Bind the body to a request DTO (e.g. a record), never to the JPA entity `Owner`. Unknown properties such as `id` or `pets` are ignored; the id is always generated.
2. Validation, the same rules as the `Owner` entity: `firstName` and `lastName` required (not blank), at most 30 characters; `address` required, at most 255; `city` required, at most 80; `telephone` required, exactly 10 digits.
3. Success: status `201 Created`, header `Location` ending with `/api/owners/{id}`, `Content-Type: application/json`, body `{"id", "firstName", "lastName", "address", "city", "telephone"}` (no `pets` property).
4. `GET /api/owners/{id}` answers `200` with the same JSON. An unknown id answers `404` with an RFC 9457 problem: `Content-Type: application/problem+json`, member `status` = 404.
5. An invalid body answers `400` with an RFC 9457 problem: `Content-Type: application/problem+json`, members `status` (400) and `title`, and an extension member `errors`: an array with one object `{"field": "<property name>", "message": "<readable message>"}` per violated constraint. Example: blank `city` and telephone `12345` give two entries, `city` and `telephone`.
6. The problem handling applies to the new API only: the HTML controllers and their behaviour under `/owners/**` stay unchanged.
7. Add MockMvc tests for 201 (status, `Location`, body), 400 (at least the telephone rule and the `errors` member) and 404.

Do not modify the existing files under `src/test/java`; add new test classes. All tests must stay green. Run ./mvnw -B -q spring-javaformat:apply before you finish.
