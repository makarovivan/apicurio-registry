/*
 * Copyright 2020 Red Hat
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

package io.apicurio.registry.utils.serde.strategy;

import io.apicurio.registry.client.RegistryRestClient;
import io.apicurio.registry.rest.beans.ArtifactMetaData;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.utils.serde.SchemaCache;


/**
 * @author Ales Justin
 */
public class FindBySchemaIdStrategy<T> extends CheckPeriodIdStrategy<T> {

    /**
     * @see io.apicurio.registry.utils.serde.strategy.CheckPeriodIdStrategy#findIdInternal(io.apicurio.registry.client.RegistryRestClient, java.lang.String, io.apicurio.registry.types.ArtifactType, java.lang.Object, SchemaCache)
     */
    @Override
    long findIdInternal(RegistryRestClient client, String artifactId, ArtifactType artifactType, T schema, SchemaCache<T> cache) {
        ArtifactMetaData amd = client.getArtifactMetaDataByContent(artifactId, true, toStream(schema));
        Long id = amd.getGlobalId();
        populateCache(schema, id, cache);
        return id;
    }

}
