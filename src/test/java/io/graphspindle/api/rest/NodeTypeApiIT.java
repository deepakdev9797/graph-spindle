package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import io.graphspindle.support.ApiClient;
import io.graphspindle.support.ApiClient.Response;
import io.graphspindle.support.KnowledgeApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Node-type and relationship-type CRUD with guardrails (ADR-024, ADR-025). */
class NodeTypeApiIT extends AbstractNeo4jIT {

    static final String RECIPE = """
            {"name":"Recipe","description":"A cooking recipe","synonyms":["dish"],"examples":["pad thai"],
             "properties":[
               {"name":"cuisine","type":"STRING","maxLength":50,"description":"style of cooking"},
               {"name":"difficulty","type":"ENUM","values":["EASY","MEDIUM","HARD"],"description":"how hard it is"}]}
            """;

    private ApiClient api;
    private KnowledgeApi k;

    @BeforeEach
    void brains() {
        api = api();
        k = new KnowledgeApi(api);
        k.brain("cook");
        k.brain("dev");
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("name").asString()));
        return out;
    }

    private static JsonNode byName(JsonNode array, String name) {
        for (JsonNode n : array) {
            if (n.get("name").asString().equals(name)) {
                return n;
            }
        }
        return null;
    }

    @Test
    void globalViewListsTheYamlCoreTypes() {
        Response types = api.get("/api/v1/node-types");
        assertThat(types.status()).isEqualTo(200);
        assertThat(types.body().size()).isEqualTo(15);
        JsonNode person = byName(types.body(), "Person");
        assertThat(person.get("core").asBoolean()).isTrue();
        assertThat(names(person.get("properties"))).contains("role", "email", "phone", "birthday", "notes");
        assertThat(person.get("synonyms").toString()).contains("human");
        Response one = api.get("/api/v1/node-types/Organization");
        assertThat(names(one.body().get("properties"))).contains("homepage", "industry", "location");
        assertThat(api.get("/api/v1/node-types/Nope").status()).isEqualTo(404);
        Response rels = api.get("/api/v1/relationship-types");
        assertThat(rels.body().size()).isEqualTo(28);
        assertThat(api.get("/api/v1/relationship-types/PART_OF").body().get("extraTargetsBySource").toString())
                .contains("Task");
        assertThat(api.get("/api/v1/relationship-types/ABOUT").body().get("targetTypes").toString())
                .contains("Any");
    }

    @Test
    void crudOfACustomNodeTypeIsScopedToOneBrain() {
        Response created = api.post("/api/v1/brains/cook/node-types", RECIPE);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        assertThat(created.etag()).isEqualTo("\"2\"");
        assertThat(created.headers().getLocation().toString()).endsWith("/api/v1/brains/cook/node-types/Recipe");
        assertThat(created.body().get("core").asBoolean()).isFalse();
        assertThat(created.body().get("enabled").asBoolean()).isTrue();
        assertThat(names(created.body().get("properties"))).containsExactly("cuisine", "difficulty");

        Response list = api.get("/api/v1/brains/cook/node-types");
        assertThat(list.etag()).isEqualTo("\"2\"");
        assertThat(names(list.body())).contains("Person", "Recipe").hasSize(16);
        // the other brain does not see it
        assertThat(names(api.get("/api/v1/brains/dev/node-types").body())).doesNotContain("Recipe").hasSize(15);
        assertThat(api.get("/api/v1/brains/dev/node-types/Recipe").status()).isEqualTo(404);
        // it reached the resolved vocabulary used for ingestion and prompts
        assertThat(api.get("/api/v1/brains/cook/vocabulary").body().get("entityTypes").toString())
                .contains("Recipe").contains("dish");

        // update: new description, extra property; If-Match is honoured
        String update = """
                {"description":"A cooking recipe with steps","properties":[
                  {"name":"cuisine","type":"STRING","maxLength":80,"description":"style of cooking"},
                  {"name":"difficulty","type":"ENUM","values":["EASY","MEDIUM","HARD","EXPERT"],"description":"how hard"},
                  {"name":"servings","type":"NUMBER","min":1,"max":100,"description":"number of servings"}]}
                """;
        assertThat(api.put("/api/v1/brains/cook/node-types/Recipe", update, "\"1\"").status()).isEqualTo(412);
        Response updated = api.put("/api/v1/brains/cook/node-types/Recipe", update, "\"2\"");
        assertThat(updated.status()).as(String.valueOf(updated.body())).isEqualTo(200);
        assertThat(updated.etag()).isEqualTo("\"3\"");
        assertThat(updated.body().get("description").asString()).isEqualTo("A cooking recipe with steps");
        assertThat(updated.body().get("synonyms").toString()).contains("dish");
        assertThat(names(updated.body().get("properties"))).containsExactly("cuisine", "difficulty", "servings");

        // rename is allowed while unused
        Response renamed2 = api.put("/api/v1/brains/cook/node-types/Recipe", "{\"name\":\"Formula\"}", null);
        assertThat(renamed2.status()).as(String.valueOf(renamed2.body())).isEqualTo(200);
        assertThat(api.get("/api/v1/brains/cook/node-types/Recipe").status()).isEqualTo(404);

        Response deleted = api.delete("/api/v1/brains/cook/node-types/Formula");
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(api.get("/api/v1/brains/cook/node-types/Formula").status()).isEqualTo(404);
        assertThat(api.delete("/api/v1/brains/cook/node-types/Formula").status()).isEqualTo(404);
        assertThat(api.post("/api/v1/brains/nope/node-types", RECIPE).status()).isEqualTo(404);
    }

    @Test
    void descriptionAndPropertyTypesAreRequired() {
        Response noDescription = api.post("/api/v1/brains/cook/node-types", "{\"name\":\"Recipe\"}");
        assertThat(noDescription.status()).isEqualTo(400);
        assertThat(noDescription.body().get("detail").asString()).contains("description is required");
        Response badName = api.post("/api/v1/brains/cook/node-types", "{\"name\":\"recipe\",\"description\":\"x\"}");
        assertThat(badName.status()).isEqualTo(400);
        Response noType = api.post("/api/v1/brains/cook/node-types", """
                {"name":"Recipe","description":"d","properties":[{"name":"cuisine","description":"c"}]}""");
        assertThat(noType.status()).isEqualTo(400);
        assertThat(noType.body().get("detail").asString()).contains("type is required");
        Response noPropDescription = api.post("/api/v1/brains/cook/node-types", """
                {"name":"Recipe","description":"d","properties":[{"name":"cuisine","type":"STRING"}]}""");
        assertThat(noPropDescription.status()).isEqualTo(400);
        Response enumWithoutValues = api.post("/api/v1/brains/cook/node-types", """
                {"name":"Recipe","description":"d","properties":[{"name":"level","type":"ENUM","description":"l"}]}""");
        assertThat(enumWithoutValues.status()).isEqualTo(400);
        assertThat(enumWithoutValues.body().get("issues").toString()).contains("ENUM needs");
        Response tooLong = api.post("/api/v1/brains/cook/node-types", """
                {"name":"Recipe","description":"d","properties":[{"name":"notes","type":"STRING","maxLength":501,
                 "description":"n"}]}""");
        assertThat(tooLong.status()).isEqualTo(400);
        Response numberRangeOnString = api.post("/api/v1/brains/cook/node-types", """
                {"name":"Recipe","description":"d","properties":[{"name":"size","type":"STRING","min":1,
                 "description":"n"}]}""");
        assertThat(numberRangeOnString.status()).isEqualTo(400);
        Response unknownField = api.post("/api/v1/brains/cook/node-types", """
                {"name":"Recipe","description":"d","colour":"red"}""");
        assertThat(unknownField.status()).isEqualTo(400);
        assertThat(api.get("/api/v1/brains/cook").body().get("configVersion").asLong()).isEqualTo(1);
    }

    @Test
    void similarNamesAreRejectedWithAHint() {
        Response developer = api.post("/api/v1/brains/cook/node-types",
                "{\"name\":\"Developer\",\"description\":\"a software developer\"}");
        assertThat(developer.status()).isEqualTo(409);
        assertThat(developer.code()).isEqualTo("CONFLICT_STATE");
        assertThat(developer.body().get("similarTo").asString()).isEqualTo("Person.role");
        assertThat(developer.body().get("hint").asString()).contains("Person.role");

        Response people = api.post("/api/v1/brains/cook/node-types",
                "{\"name\":\"Persons\",\"description\":\"people\"}");
        assertThat(people.status()).isEqualTo(409);
        assertThat(people.body().get("similarTo").asString()).isEqualTo("Person");
        assertThat(people.body().get("match").asString()).isEqualTo("SINGULAR_PLURAL");

        Response typo = api.post("/api/v1/brains/cook/node-types", "{\"name\":\"Projekt\",\"description\":\"p\"}");
        assertThat(typo.status()).isEqualTo(409);
        assertThat(typo.body().get("similarTo").asString()).isEqualTo("Project");
        assertThat(typo.body().get("match").asString()).isEqualTo("EDIT_DISTANCE");

        Response synonym = api.post("/api/v1/brains/cook/node-types", "{\"name\":\"Company\",\"description\":\"c\"}");
        assertThat(synonym.status()).isEqualTo(409);
        assertThat(synonym.body().get("similarTo").asString()).isEqualTo("Organization");

        Response duplicate = api.post("/api/v1/brains/cook/node-types", "{\"name\":\"PERSON\",\"description\":\"p\"}");
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("ALREADY_EXISTS");
        assertThat(api.post("/api/v1/brains/cook/node-types", RECIPE).status()).isEqualTo(201);
        Response again = api.post("/api/v1/brains/cook/node-types", RECIPE);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ALREADY_EXISTS");
        Response plural = api.post("/api/v1/brains/cook/node-types", "{\"name\":\"Recipes\",\"description\":\"r\"}");
        assertThat(plural.status()).isEqualTo(409);
        // custom synonyms count too
        Response dish = api.post("/api/v1/brains/cook/node-types", "{\"name\":\"Dishes\",\"description\":\"d\"}");
        assertThat(dish.status()).isEqualTo(409);
        assertThat(dish.body().get("similarTo").asString()).isEqualTo("Recipe");
        // ... but only in their own brain
        assertThat(api.post("/api/v1/brains/dev/node-types", "{\"name\":\"Dishes\",\"description\":\"d\"}").status())
                .isEqualTo(201);

        Response rel = api.post("/api/v1/brains/cook/relationship-types", """
                {"name":"MEMBERS_OF","description":"m","sourceTypes":["Person"],"targetTypes":["Organization"]}""");
        assertThat(rel.status()).isEqualTo(409);
        assertThat(rel.body().get("similarTo").asString()).isEqualTo("MEMBER_OF");
        Response relSynonym = api.post("/api/v1/brains/cook/relationship-types", """
                {"name":"EMPLOYED_BY","description":"m","sourceTypes":["Person"],"targetTypes":["Organization"]}""");
        assertThat(relSynonym.status()).isEqualTo(409);
        assertThat(relSynonym.body().get("hint").asString()).contains("MEMBER_OF");
        // bulk PUT /vocabulary applies the guardrail to new names too
        Response bulk = api.put("/api/v1/brains/dev/vocabulary",
                "{\"customEntityTypes\":[{\"name\":\"Humans\",\"description\":\"h\"}]}", null);
        assertThat(bulk.status()).isEqualTo(409);
        assertThat(bulk.body().get("similarTo").asString()).isEqualTo("Person");
    }

    @Test
    void customTypeCountIsCapped() {
        for (int i = 0; i < 20; i++) {
            String name = "Zq" + (char) ('a' + i) + "x" + (char) ('a' + i) + "w" + (char) ('a' + i) + "v";
            Response r = api.post("/api/v1/brains/cook/node-types", "{\"name\":\"" + name + "\",\"description\":\"t\"}");
            assertThat(r.status()).as(name + " " + r.body()).isEqualTo(201);
        }
        Response over = api.post("/api/v1/brains/cook/node-types", "{\"name\":\"Qwrtplk\",\"description\":\"g\"}");
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().get("issues").toString()).contains("LIMIT_EXCEEDED");
    }

    @Test
    void coreTypesAreReadOnlyExceptEnabledAndExtraProperties() {
        Response desc = api.put("/api/v1/brains/cook/node-types/Person", "{\"description\":\"someone\"}", null);
        assertThat(desc.status()).isEqualTo(400);
        assertThat(desc.body().get("detail").asString()).contains("read-only");
        assertThat(api.delete("/api/v1/brains/cook/node-types/Person").status()).isEqualTo(400);
        Response noPerson = api.put("/api/v1/brains/cook/node-types/Person", "{\"enabled\":false}", null);
        assertThat(noPerson.status()).isEqualTo(400);
        assertThat(noPerson.body().get("issues").toString()).contains("CANNOT_DISABLE");

        Response disabled = api.put("/api/v1/brains/cook/node-types/Place", "{\"enabled\":false}", null);
        assertThat(disabled.status()).as(String.valueOf(disabled.body())).isEqualTo(200);
        assertThat(disabled.body().get("enabled").asBoolean()).isFalse();
        assertThat(api.get("/api/v1/brains/cook/vocabulary").body().get("entityTypes").toString())
                .doesNotContain("\"Place\"");
        assertThat(byName(api.get("/api/v1/brains/dev/node-types").body(), "Place").get("enabled").asBoolean())
                .isTrue();
        assertThat(api.put("/api/v1/brains/cook/node-types/Place", "{\"enabled\":true}", null).body().get("enabled")
                .asBoolean()).isTrue();

        // GET-modify-PUT round trip: core properties unchanged plus one extra property
        JsonNode person = api.get("/api/v1/brains/cook/node-types/Person").body();
        String props = person.get("properties").toString();
        String extra = props.substring(0, props.length() - 1)
                + ",{\"name\":\"github\",\"type\":\"URL\",\"description\":\"GitHub profile\"}]";
        Response withExtra = api.put("/api/v1/brains/cook/node-types/Person", "{\"properties\":" + extra + "}", null);
        assertThat(withExtra.status()).as(String.valueOf(withExtra.body())).isEqualTo(200);
        JsonNode github = byName(withExtra.body().get("properties"), "github");
        assertThat(github.get("core").asBoolean()).isFalse();
        assertThat(byName(withExtra.body().get("properties"), "email").get("core").asBoolean()).isTrue();
        assertThat(names(api.get("/api/v1/brains/dev/node-types/Person").body().get("properties")))
                .doesNotContain("github");
        // a value for the extra property is accepted for that brain only
        String self = k.selfId("cook");
        k.value("cook", self, "github", "https://github.com/example");
        assertThat(k.assertValue("dev", k.selfId("dev"), "github", "https://github.com/example").status())
                .isEqualTo(400);
        // changing a core property is refused
        Response changeCore = api.put("/api/v1/brains/cook/node-types/Person",
                "{\"properties\":[{\"name\":\"email\",\"type\":\"STRING\",\"maxLength\":10}]}", null);
        assertThat(changeCore.status()).isEqualTo(400);
        assertThat(changeCore.body().get("detail").asString()).contains("Person.email");
        // removing the extra property while it is in use is refused
        Response remove = api.put("/api/v1/brains/cook/node-types/Person", "{\"properties\":[]}", null);
        assertThat(remove.status()).isEqualTo(409);
        assertThat(remove.body().get("inUse").toString()).contains("Person.github");
    }

    @Test
    void typesAndPropertiesInUseCannotBeRemovedOrNarrowed() {
        assertThat(api.post("/api/v1/brains/cook/node-types", RECIPE).status()).isEqualTo(201);
        String curry = k.entity("cook", "Recipe", "Green curry");
        k.value("cook", curry, "difficulty", "HARD");

        Response delete = api.delete("/api/v1/brains/cook/node-types/Recipe");
        assertThat(delete.status()).isEqualTo(409);
        assertThat(delete.body().get("inUse").toString()).contains("Recipe");
        Response rename = api.put("/api/v1/brains/cook/node-types/Recipe", "{\"name\":\"Formula\"}", null);
        assertThat(rename.status()).isEqualTo(409);

        // narrowing an enum in use (HARD removed)
        Response narrow = api.put("/api/v1/brains/cook/node-types/Recipe", """
                {"properties":[{"name":"cuisine","type":"STRING","maxLength":50,"description":"style"},
                 {"name":"difficulty","type":"ENUM","values":["EASY","MEDIUM"],"description":"how hard"}]}""", null);
        assertThat(narrow.status()).isEqualTo(409);
        assertThat(narrow.body().get("inUse").toString()).contains("Recipe.difficulty");
        // removing the property in use
        Response drop = api.put("/api/v1/brains/cook/node-types/Recipe", """
                {"properties":[{"name":"cuisine","type":"STRING","maxLength":50,"description":"style"}]}""", null);
        assertThat(drop.status()).isEqualTo(409);
        // narrowing an unused property (cuisine) and widening the used one is fine
        Response ok = api.put("/api/v1/brains/cook/node-types/Recipe", """
                {"properties":[{"name":"cuisine","type":"STRING","maxLength":20,"description":"style"},
                 {"name":"difficulty","type":"ENUM","values":["EASY","MEDIUM","HARD","EXPERT"],"description":"h"}]}""",
                null);
        assertThat(ok.status()).as(String.valueOf(ok.body())).isEqualTo(200);

        // relationship types: in use cannot be deleted or narrowed
        Response rel = api.post("/api/v1/brains/cook/relationship-types", """
                {"name":"INGREDIENT_OF","description":"is an ingredient of","synonyms":["used in"],
                 "sourceTypes":["Concept","Technology"],"targetTypes":["Recipe"]}""");
        assertThat(rel.status()).as(String.valueOf(rel.body())).isEqualTo(201);
        assertThat(rel.body().get("cardinality").asString()).isEqualTo("MANY");
        String basil = k.entity("cook", "Concept", "basil");
        k.relation("cook", basil, "INGREDIENT_OF", curry);
        assertThat(api.delete("/api/v1/brains/cook/relationship-types/INGREDIENT_OF").status()).isEqualTo(409);
        Response narrowRel = api.put("/api/v1/brains/cook/relationship-types/INGREDIENT_OF",
                "{\"sourceTypes\":[\"Technology\"]}", null);
        assertThat(narrowRel.status()).isEqualTo(409);
        Response one = api.put("/api/v1/brains/cook/relationship-types/INGREDIENT_OF",
                "{\"cardinality\":\"ONE\"}", null);
        assertThat(one.status()).isEqualTo(409);
        Response widen = api.put("/api/v1/brains/cook/relationship-types/INGREDIENT_OF",
                "{\"description\":\"goes into\",\"sourceTypes\":[\"Concept\",\"Technology\",\"Artifact\"]}", null);
        assertThat(widen.status()).as(String.valueOf(widen.body())).isEqualTo(200);
        assertThat(widen.body().get("sourceTypes").toString()).contains("Artifact");
        // a type referenced by a custom relationship type cannot be deleted
        assertThat(api.delete("/api/v1/brains/cook/node-types/Recipe").body().toString()).contains("INGREDIENT_OF");
    }

    @Test
    void relationshipTypeCrudAndCoreEnableDisable() {
        Response created = api.post("/api/v1/brains/cook/relationship-types", """
                {"name":"MENTORS","description":"mentors","sourceTypes":["Person"],"targetTypes":["Person"],
                 "cardinality":"MANY"}""");
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        assertThat(created.body().get("core").asBoolean()).isFalse();
        assertThat(names(api.get("/api/v1/brains/cook/relationship-types").body())).contains("MENTORS").hasSize(29);
        assertThat(names(api.get("/api/v1/brains/dev/relationship-types").body())).doesNotContain("MENTORS");
        assertThat(api.post("/api/v1/brains/cook/relationship-types", """
                {"name":"COACHES","description":"c","sourceTypes":["Person"],"targetTypes":["Person"],
                 "confidenceCap":0.5}""").status()).isEqualTo(400);
        assertThat(api.post("/api/v1/brains/cook/relationship-types", """
                {"name":"COACHES","sourceTypes":["Person"],"targetTypes":["Person"]}""").status()).isEqualTo(400);
        assertThat(api.post("/api/v1/brains/cook/relationship-types", """
                {"name":"COACHES","description":"c","sourceTypes":["Robot"],"targetTypes":["Person"]}""").status())
                .isEqualTo(400);

        Response renamed = api.put("/api/v1/brains/cook/relationship-types/MENTORS", "{\"name\":\"COACHES\"}", null);
        assertThat(renamed.status()).as(String.valueOf(renamed.body())).isEqualTo(200);
        assertThat(renamed.body().get("name").asString()).isEqualTo("COACHES");
        assertThat(api.delete("/api/v1/brains/cook/relationship-types/COACHES").status()).isEqualTo(204);

        Response disable = api.put("/api/v1/brains/cook/relationship-types/RELATED_TO", "{\"enabled\":false}", null);
        assertThat(disable.status()).as(String.valueOf(disable.body())).isEqualTo(200);
        assertThat(disable.body().get("enabled").asBoolean()).isFalse();
        assertThat(api.get("/api/v1/brains/cook/vocabulary").body().get("relationPredicates").toString())
                .doesNotContain("RELATED_TO");
        assertThat(api.get("/api/v1/brains/dev/vocabulary").body().get("relationPredicates").toString())
                .contains("RELATED_TO");
        assertThat(api.put("/api/v1/brains/cook/relationship-types/RELATED_TO", "{\"enabled\":true}", null).body()
                .get("enabled").asBoolean()).isTrue();
        Response coreChange = api.put("/api/v1/brains/cook/relationship-types/USES",
                "{\"description\":\"consumes\"}", null);
        assertThat(coreChange.status()).isEqualTo(400);
        assertThat(api.delete("/api/v1/brains/cook/relationship-types/USES").status()).isEqualTo(400);
        assertThat(api.put("/api/v1/brains/cook/relationship-types/NOPE", "{\"enabled\":true}", null).status())
                .isEqualTo(404);
    }

    @Test
    void openApiDocumentsTheEndpoints() {
        JsonNode paths = k.get("/v3/api-docs").get("paths");
        for (String p : List.of("/api/v1/node-types", "/api/v1/node-types/{type}", "/api/v1/relationship-types",
                "/api/v1/relationship-types/{name}", "/api/v1/brains/{brainId}/node-types",
                "/api/v1/brains/{brainId}/node-types/{type}", "/api/v1/brains/{brainId}/relationship-types",
                "/api/v1/brains/{brainId}/relationship-types/{name}")) {
            assertThat(paths.has(p)).as(p).isTrue();
        }
        JsonNode one = paths.get("/api/v1/brains/{brainId}/node-types/{type}");
        assertThat(one.has("get") && one.has("put") && one.has("delete")).isTrue();
        assertThat(paths.get("/api/v1/brains/{brainId}/node-types").has("post")).isTrue();
    }
}
