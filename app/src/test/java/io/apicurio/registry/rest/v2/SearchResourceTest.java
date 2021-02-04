/*
 * Copyright 2020 Red Hat
 * Copyright 2020 IBM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.registry.rest.v2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.apicurio.registry.AbstractResourceTestBase;
import io.apicurio.registry.rest.v2.beans.EditableMetaData;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.utils.tests.TestUtils;
import io.quarkus.test.junit.QuarkusTest;

/**
 * @author eric.wittmann@gmail.com
 */
@QuarkusTest
public class SearchResourceTest extends AbstractResourceTestBase {

    @Test
    public void testSearchByGroup() throws Exception {
        String artifactContent = resourceToString("openapi-empty.json");
        String group = UUID.randomUUID().toString();

        // Create 5 artifacts in the UUID group
        for (int idx = 0; idx < 5; idx++) {
            String title = "Empty API " + idx;
            String artifactId = "Empty-" + idx;
            this.createArtifact(group, artifactId, ArtifactType.OPENAPI, artifactContent.replaceAll("Empty API", title));
            waitForArtifact(group, artifactId);
        }
        // Create 3 artifacts in some other group
        for (int idx = 0; idx < 5; idx++) {
            String artifactId = "Empty-" + idx;
            this.createArtifact("SearchResourceTest", artifactId, ArtifactType.OPENAPI, artifactContent);
            waitForArtifact(group, artifactId);
        }

        given()
            .when()
                .queryParam("group", group)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(5));
    }

    @Test
    public void testSearchByName() throws Exception {
        String group = UUID.randomUUID().toString();
        String name = UUID.randomUUID().toString();
        String artifactContent = resourceToString("openapi-empty.json");

        // Two with the UUID name
        for (int idx = 0; idx < 2; idx++) {
            String artifactId = "Empty-" + idx;
            this.createArtifact(group, artifactId, ArtifactType.OPENAPI, artifactContent.replaceAll("Empty API", name));
            waitForArtifact(group, artifactId);
        }
        // Three with a different name
        for (int idx = 2; idx < 5; idx++) {
            String artifactId = "Empty-" + idx;
            this.createArtifact(group, artifactId, ArtifactType.OPENAPI, artifactContent);
            waitForArtifact(group, artifactId);
        }

        given()
            .when()
                .queryParam("name", name)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(2));
    }

    @Test
    public void testSearchByDescription() throws Exception {
        String group = UUID.randomUUID().toString();
        String description = "The description is "+ UUID.randomUUID().toString();
        String artifactContent = resourceToString("openapi-empty.json");

        // Two with the UUID description
        for (int idx = 0; idx < 2; idx++) {
            String artifactId = "Empty-" + idx;
            this.createArtifact(group, artifactId, ArtifactType.OPENAPI, artifactContent.replaceAll("An example API design using OpenAPI.", description));
            waitForArtifact(group, artifactId);
        }
        // Three with the default description
        for (int idx = 2; idx < 5; idx++) {
            String artifactId = "Empty-" + idx;
            this.createArtifact(group, artifactId, ArtifactType.OPENAPI, artifactContent);
            waitForArtifact(group, artifactId);
        }

        given()
            .when()
                .queryParam("description", description)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(2));
    }

    @Test
    public void testSearchByLabels() throws Exception {
        String group = UUID.randomUUID().toString();
        String artifactContent = resourceToString("openapi-empty.json");

        // Create 5 artifacts with various labels
        for (int idx = 0; idx < 5; idx++) {
            String title = "Empty API " + idx;
            String artifactId = "Empty-" + idx;
            this.createArtifact(group, artifactId, ArtifactType.OPENAPI, artifactContent.replaceAll("Empty API", title));
            waitForArtifact(group, artifactId);

            List<String> labels = new ArrayList<>(2);
            labels.add("testSearchByLabels");
            labels.add("testSearchByLabels-" + idx);

            // Update the artifact meta-data
            EditableMetaData metaData = new EditableMetaData();
            metaData.setName(title);
            metaData.setDescription("Some description of an API");
            metaData.setLabels(labels);
            given()
                .when()
                    .contentType(CT_JSON)
                    .pathParam("groupId", group)
                    .pathParam("artifactId", artifactId)
                    .body(metaData)
                    .put("/v2/groups/{groupId}/artifacts/{artifactId}/meta")
                .then()
                    .statusCode(204);
        }

        TestUtils.retry(() -> {
            given()
                .when()
                    .queryParam("labels", "testSearchByLabels")
                    .get("/v2/search/artifacts")
                .then()
                    .statusCode(200)
                    .body("count", equalTo(5));
        });

        TestUtils.retry(() -> {
            given()
                .when()
                    .queryParam("labels", "testSearchByLabels-2")
                    .get("/v2/search/artifacts")
                .then()
                    .statusCode(200)
                    .body("count", equalTo(1));
        });
    }

    @Test
    public void testOrderBy() throws Exception {
        String group = UUID.randomUUID().toString();
        String artifactContent = resourceToString("openapi-empty.json");

        for (int idx = 0; idx < 5; idx++) {
            String artifactId = "Empty-" + idx;
            String name = "empty-" + idx;
            this.createArtifact(group, artifactId, ArtifactType.OPENAPI, artifactContent.replaceAll("Empty API", name));
            waitForArtifact(group, artifactId);
        }

        given()
            .when()
                .queryParam("orderby", "name")
                .queryParam("order", "asc")
                .queryParam("group", group)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(5))
                .body("artifacts[0].name", equalTo("empty-0"));

        given()
            .when()
                .queryParam("orderby", "name")
                .queryParam("order", "desc")
                .queryParam("group", group)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(5))
                .body("artifacts[0].name", equalTo("empty-4"));

        given()
            .when()
                .queryParam("orderby", "createdOn")
                .queryParam("order", "asc")
                .queryParam("group", group)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(5))
                .body("artifacts[0].name", equalTo("empty-0"));

        given()
            .when()
                .queryParam("orderby", "createdOn")
                .queryParam("order", "desc")
                .queryParam("group", group)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(5))
                .body("artifacts[0].name", equalTo("empty-4"));
    }

    @Test
    public void testLimitAndOffset() throws Exception {
        String group = UUID.randomUUID().toString();
        String artifactContent = resourceToString("openapi-empty.json");

        for (int idx = 0; idx < 20; idx++) {
            String artifactId = "Empty-" + idx;
            String name = "empty-" + idx;
            this.createArtifact(group, artifactId, ArtifactType.OPENAPI, artifactContent.replaceAll("Empty API", name));
            waitForArtifact(group, artifactId);
        }

        given()
            .when()
                .queryParam("orderby", "createdOn")
                .queryParam("order", "asc")
                .queryParam("limit", 5)
                .queryParam("group", group)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(20))
                .body("artifacts.size()", equalTo(5))
                .body("artifacts[0].name", equalTo("empty-0"));

        given()
            .when()
                .queryParam("orderby", "createdOn")
                .queryParam("order", "asc")
                .queryParam("limit", 15)
                .queryParam("group", group)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(20))
                .body("artifacts.size()", equalTo(15))
                .body("artifacts[0].name", equalTo("empty-0"));

        given()
            .when()
                .queryParam("orderby", "createdOn")
                .queryParam("order", "asc")
                .queryParam("limit", 5)
                .queryParam("offset", 10)
                .queryParam("group", group)
                .get("/v2/search/artifacts")
            .then()
                .statusCode(200)
                .body("count", equalTo(20))
                .body("artifacts.size()", equalTo(5))
                .body("artifacts[0].name", equalTo("empty-10"));

    }

}
