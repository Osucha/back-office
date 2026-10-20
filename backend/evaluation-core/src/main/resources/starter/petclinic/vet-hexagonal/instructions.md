The `vet` package (`org.springframework.samples.petclinic.vet`) mixes JPA entities, the Spring Data repository and the controller. Refactor it into ports and adapters. The acceptance tests rely on these package and type names (all under `org.springframework.samples.petclinic.vet`):

- `vet.domain`: `Vet` and `Specialty` (records or plain classes) and any other domain types. No Spring, no Jakarta (JPA, JAXB, validation), no Jackson, no Hibernate, no dependency on `petclinic.model` (the JPA mapped superclasses), `vet.application` or `vet.adapter`.
- `vet.application`: the inbound port interface `ListVets` (list all vets and one page of vets), the outbound port interface `VetCatalog`, and a service class implementing `ListVets` on top of `VetCatalog`. No dependency on adapters, JPA, Spring Data, Spring MVC or `petclinic.model`.
- `vet.adapter.in.web`: the controller (`/vets.html`, `/vets`) and the web representations; it uses `ListVets` only, never `VetCatalog` or anything in `vet.adapter.out`.
- `vet.adapter.out.persistence`: the JPA entities, the Spring Data repository and the class implementing `VetCatalog`, with an explicit mapper.
- Optionally `vet.adapter.config` for wiring. Nothing except `package-info.java` stays directly in `vet`.

Behaviour stays identical, byte for byte:
- `/vets.html?page=1` and `?page=2`: same HTML as today (leave `vets/vetList.html` unchanged); out-of-range pages still redirect to `/vets.html?page=1`.
- `GET /vets` with `Accept: application/json` returns exactly:
{"vetList":[{"firstName":"James","id":1,"lastName":"Carter","new":false,"nrOfSpecialties":0,"specialties":[]},{"firstName":"Helen","id":2,"lastName":"Leary","new":false,"nrOfSpecialties":1,"specialties":[{"id":1,"name":"radiology","new":false}]},{"firstName":"Linda","id":3,"lastName":"Douglas","new":false,"nrOfSpecialties":2,"specialties":[{"id":3,"name":"dentistry","new":false},{"id":2,"name":"surgery","new":false}]},{"firstName":"Rafael","id":4,"lastName":"Ortega","new":false,"nrOfSpecialties":1,"specialties":[{"id":2,"name":"surgery","new":false}]},{"firstName":"Henry","id":5,"lastName":"Stevens","new":false,"nrOfSpecialties":1,"specialties":[{"id":1,"name":"radiology","new":false}]},{"firstName":"Sharon","id":6,"lastName":"Jenkins","new":false,"nrOfSpecialties":0,"specialties":[]}]}
- `GET /vets` with `Accept: application/xml` returns exactly:
<?xml version="1.0" encoding="UTF-8" standalone="yes"?><vets><vetList><id>1</id><firstName>James</firstName><lastName>Carter</lastName></vetList><vetList><id>2</id><firstName>Helen</firstName><lastName>Leary</lastName><specialties><id>1</id><name>radiology</name></specialties></vetList><vetList><id>3</id><firstName>Linda</firstName><lastName>Douglas</lastName><specialties><id>3</id><name>dentistry</name></specialties><specialties><id>2</id><name>surgery</name></specialties></vetList><vetList><id>4</id><firstName>Rafael</firstName><lastName>Ortega</lastName><specialties><id>2</id><name>surgery</name></specialties></vetList><vetList><id>5</id><firstName>Henry</firstName><lastName>Stevens</lastName><specialties><id>1</id><name>radiology</name></specialties></vetList><vetList><id>6</id><firstName>Sharon</firstName><lastName>Jenkins</lastName></vetList></vets>
  (JSON has `new` and `nrOfSpecialties`, XML does not; XML element order is id, firstName, lastName, specialties.)
- Caching unchanged: the vet lists are still cached in the cache named `vets`.

Add `com.tngtech.archunit:archunit-junit5` (test scope, version 1.4.1 or newer) to `pom.xml`: the acceptance tests use ArchUnit and do not compile without it. Write your own ArchUnit test that enforces the layering and would fail on the original structure (no rule may pass on an empty selection).

Existing tests keep their assertions. Only in `vet/VetControllerTests`, `vet/VetTests`, `PetClinicIntegrationTests`, `MySqlIntegrationTests`, `PostgresIntegrationTests` and `service/ClinicServiceTests` you may change imports, package declarations (moving the test file with its class), references to moved or renamed classes, mock targets and fixture construction. All other test files stay unchanged. All tests green.

Run ./mvnw -B -q spring-javaformat:apply before you finish.
