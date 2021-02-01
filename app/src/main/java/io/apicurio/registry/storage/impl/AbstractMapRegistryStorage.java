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

package io.apicurio.registry.storage.impl;

import static io.apicurio.registry.utils.StringUtil.isEmpty;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.apicurio.registry.content.ContentHandle;
import io.apicurio.registry.content.canon.ContentCanonicalizer;
import io.apicurio.registry.content.extract.ContentExtractor;
import io.apicurio.registry.content.extract.ExtractedMetaData;
import io.apicurio.registry.storage.ArtifactAlreadyExistsException;
import io.apicurio.registry.storage.ArtifactNotFoundException;
import io.apicurio.registry.storage.ArtifactStateExt;
import io.apicurio.registry.storage.InvalidPropertiesException;
import io.apicurio.registry.storage.RegistryStorageException;
import io.apicurio.registry.storage.RuleAlreadyExistsException;
import io.apicurio.registry.storage.RuleNotFoundException;
import io.apicurio.registry.storage.VersionNotFoundException;
import io.apicurio.registry.storage.dto.ArtifactMetaDataDto;
import io.apicurio.registry.storage.dto.ArtifactSearchResultsDto;
import io.apicurio.registry.storage.dto.ArtifactVersionMetaDataDto;
import io.apicurio.registry.storage.dto.EditableArtifactMetaDataDto;
import io.apicurio.registry.storage.dto.OrderBy;
import io.apicurio.registry.storage.dto.OrderDirection;
import io.apicurio.registry.storage.dto.RuleConfigurationDto;
import io.apicurio.registry.storage.dto.SearchFilter;
import io.apicurio.registry.storage.dto.SearchFilterType;
import io.apicurio.registry.storage.dto.SearchedArtifactDto;
import io.apicurio.registry.storage.dto.SearchedVersionDto;
import io.apicurio.registry.storage.dto.StoredArtifactDto;
import io.apicurio.registry.storage.dto.VersionSearchResultsDto;
import io.apicurio.registry.types.ArtifactState;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.types.RuleType;
import io.apicurio.registry.types.provider.ArtifactTypeUtilProvider;
import io.apicurio.registry.types.provider.ArtifactTypeUtilProviderFactory;
import io.apicurio.registry.util.DtoUtil;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Base class for all map-based registry storage implementation.  Examples of
 * subclasses of this might be an in-memory impl as well as an Infinispan impl.
 *
 * @author Ales Justin
 */
public abstract class AbstractMapRegistryStorage extends AbstractRegistryStorage {

    private static final int ARTIFACT_FIRST_VERSION = 1;

    @Inject
    protected ArtifactTypeUtilProviderFactory factory;

    @Inject
    protected SecurityIdentity securityIdentity;

    protected StorageMap storage;
    protected Map<Long, TupleId> global;
    protected MultiMap<ArtifactKey, String, String> artifactRules;
    protected Map<String, String> globalRules;

    protected void beforeInit() {
    }

    @PostConstruct
    public void init() {
        beforeInit();
        storage = createStorageMap();
        global = createGlobalMap();
        globalRules = createGlobalRulesMap();
        artifactRules = createArtifactRulesMap();
        afterInit();
    }

    protected void afterInit() {
    }

    protected abstract long nextGlobalId();

    protected abstract StorageMap createStorageMap();

    protected abstract Map<Long, TupleId> createGlobalMap();

    protected abstract Map<String, String> createGlobalRulesMap();

    protected abstract MultiMap<ArtifactKey, String, String> createArtifactRulesMap();

    private Map<Long, Map<String, String>> getVersion2ContentMap(String groupId, String artifactId) throws ArtifactNotFoundException {
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        
        Map<Long, Map<String, String>> v2c = storage.get(akey);
        if (v2c == null || v2c.isEmpty()) {
            throw new ArtifactNotFoundException(groupId, artifactId);
        }
        return Collections.unmodifiableMap(v2c);
    }

    private Map<String, String> getContentMap(String groupId, String artifactId, Long version, EnumSet<ArtifactState> states) throws ArtifactNotFoundException {
        Map<Long, Map<String, String>> v2c = getVersion2ContentMap(groupId, artifactId);
        Map<String, String> content = v2c.get(version);
        if (content == null) {
            throw new VersionNotFoundException(groupId, artifactId, version);
        }

        ArtifactState state = ArtifactStateExt.getState(content);
        ArtifactStateExt.validateState(states, state, groupId, artifactId, version);

        return Collections.unmodifiableMap(content);
    }

    public static Predicate<Map.Entry<Long, Map<String, String>>> statesFilter(EnumSet<ArtifactState> states) {
        return e -> states.contains(ArtifactStateExt.getState(e.getValue()));
    }

    private Map<String, String> getLatestContentMap(String groupId, String artifactId, EnumSet<ArtifactState> states) throws ArtifactNotFoundException, RegistryStorageException {
        Map<Long, Map<String, String>> v2c = getVersion2ContentMap(groupId, artifactId);
        Stream<Map.Entry<Long, Map<String, String>>> stream = v2c.entrySet().stream();
        if (states != null) {
            stream = stream.filter(statesFilter(states));
        }
        Map<String, String> latest = stream.max((e1, e2) -> (int) (e1.getKey() - e2.getKey()))
                                           .orElseThrow(() -> new ArtifactNotFoundException(groupId, artifactId))
                                           .getValue();

        ArtifactStateExt.logIfDeprecated(groupId, artifactId, latest.get(MetaDataKeys.VERSION), ArtifactStateExt.getState(latest));

        return Collections.unmodifiableMap(latest);
    }

    private boolean artifactMatchesFilters(ArtifactMetaDataDto artifactMetaData, Set<SearchFilter> filters) {
        boolean accepted = true;
        for (SearchFilter filter : filters) {
            accepted &= artifactMatchesFilter(artifactMetaData, filter);
        }
        return accepted;
    }

    private boolean artifactMatchesFilter(ArtifactMetaDataDto artifactMetaData, SearchFilter filter) {
        SearchFilterType type = filter.getType();
        String search = filter.getValue();
        switch (type) {
            case description:
                return valueContainsSearch(search, artifactMetaData.getDescription());
            case everything:
                return valueContainsSearch(search, artifactMetaData.getDescription()) ||
                       valueIsSearch(search, artifactMetaData.getGroupId()) ||
                       valueContainsSearch(search, artifactMetaData.getLabels()) ||
                       valueContainsSearch(search, artifactMetaData.getName()) ||
                       valueContainsSearch(search, artifactMetaData.getId());// ||
//                       valueContainsSearch(search, artifactMetaData.getProperties());
            case group:
                return valueIsSearch(search, artifactMetaData.getGroupId());
            case labels:
                return valueContainsSearch(search, artifactMetaData.getLabels());
            case name:
                return valueContainsSearch(search, artifactMetaData.getName()) || valueContainsSearch(search, artifactMetaData.getId());
            case properties:
                return valueContainsSearch(search, artifactMetaData.getProperties());
        }
        return false;
    }

    private boolean valueContainsSearch(String search, Map<String, String> values) {
        // TODO add searching over properties support!
        throw new RuntimeException("Searching over properties is not yet implemented.");
    }

    private boolean valueContainsSearch(String search, List<String> values) {
        if (values != null) {
            for (String value : values) {
                if (valueIsSearch(search, value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean valueContainsSearch(String search, String value) {
        return value != null && StringUtils.containsIgnoreCase(value, search.toLowerCase());
    }

    private boolean valueIsSearch(String search, String value) {
        return value != null && StringUtils.equalsIgnoreCase(value, search.toLowerCase());
    }

    private Optional<ArtifactMetaDataDto> getOptionalArtifactMetadata(ArtifactKey artifactKey) {
        try {
            return Optional.of(getArtifactMetaData(artifactKey.getGroupId(), artifactKey.getArtifactId()));
        } catch (ArtifactNotFoundException ex) {
            return Optional.empty();
        }
    }

    public static StoredArtifactDto toStoredArtifact(Map<String, String> content) {
        return StoredArtifactDto.builder()
                             .content(ContentHandle.create(MetaDataKeys.getContent(content)))
                             .version(Long.parseLong(content.get(MetaDataKeys.VERSION)))
                             .globalId(Long.parseLong(content.get(MetaDataKeys.GLOBAL_ID)))
                             .build();
    }

    protected BiFunction<String, Map<Long, Map<String, String>>, Map<Long, Map<String, String>>> lookupFn() {
        return (id, m) -> (m == null) ? new ConcurrentHashMap<>() : m;
    }

    protected ArtifactMetaDataDto createOrUpdateArtifact(String groupId, String artifactId, ArtifactType artifactType, ContentHandle contentHandle, boolean create, long globalId) {
        return createOrUpdateArtifact(groupId, artifactId, artifactType, contentHandle, create, globalId, System.currentTimeMillis());
    }

    protected ArtifactMetaDataDto createOrUpdateArtifact(String groupId, String artifactId, ArtifactType artifactType, ContentHandle content, boolean create, long globalId, long creationTime)
            throws ArtifactAlreadyExistsException, ArtifactNotFoundException, RegistryStorageException {
        if (artifactId == null) {
            if (!create) {
                throw new ArtifactNotFoundException(groupId, "Null artifactId!");
            }
            artifactId = UUID.randomUUID().toString();
        }

        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        Map<Long, Map<String, String>> v2c = storage.compute(akey);

        if (create && v2c.size() > 0) {
            throw new ArtifactAlreadyExistsException(groupId, artifactId);
        }

        if (!create && v2c.size() == 0) {
            storage.remove(akey); // remove, as we just "computed" empty map
            throw new ArtifactNotFoundException(groupId, artifactId);
        }

        long version = v2c.keySet().stream().max(Long::compareTo).orElse(0L) + 1;
        long prevVersion = version - 1;

        Map<String, String> contents = new ConcurrentHashMap<>();
        MetaDataKeys.putContent(contents, content.bytes());
        contents.put(MetaDataKeys.VERSION, Long.toString(version));
        contents.put(MetaDataKeys.GLOBAL_ID, String.valueOf(globalId));
        contents.put(MetaDataKeys.ARTIFACT_ID, artifactId);
        contents.put(MetaDataKeys.GROUP_ID, groupId);

        String creationTimeValue = String.valueOf(creationTime);
        contents.put(MetaDataKeys.CREATED_ON, creationTimeValue);
        contents.put(MetaDataKeys.MODIFIED_ON, creationTimeValue);

        contents.put(MetaDataKeys.CREATED_BY, securityIdentity.getPrincipal().getName());

        contents.put(MetaDataKeys.TYPE, artifactType.value());
        ArtifactStateExt.applyState(contents, ArtifactState.ENABLED);

        // Carry over some meta-data from the previous version on an update.
        if (!create) {
            Map<String, String> prevContents = v2c.get(prevVersion);
            if (prevContents != null) {
                if (prevContents.containsKey(MetaDataKeys.NAME)) {
                    contents.put(MetaDataKeys.NAME, prevContents.get(MetaDataKeys.NAME));
                }
                if (prevContents.containsKey(MetaDataKeys.DESCRIPTION)) {
                    contents.put(MetaDataKeys.DESCRIPTION, prevContents.get(MetaDataKeys.DESCRIPTION));
                }
            }
        }

        ArtifactTypeUtilProvider provider = factory.getArtifactTypeProvider(artifactType);
        ContentExtractor extractor = provider.getContentExtractor();
        ExtractedMetaData emd = extractor.extract(content);
        if (extractor.isExtracted(emd)) {
            if (!isEmpty(emd.getName())) {
                contents.put(MetaDataKeys.NAME, emd.getName());
            }
            if (!isEmpty(emd.getDescription())) {
                contents.put(MetaDataKeys.DESCRIPTION, emd.getDescription());
            }
        }

        // Store in v2c -- make sure version is unique!!
        storage.createVersion(akey, version, contents);

        // Also store in global
        global.put(globalId, new TupleId(groupId, artifactId, version));

        final ArtifactMetaDataDto artifactMetaDataDto = MetaDataKeys.toArtifactMetaData(contents);

        //Set the createdOn based on the first version metadata.
        if (artifactMetaDataDto.getVersion() != ARTIFACT_FIRST_VERSION) {
            ArtifactVersionMetaDataDto firstVersionContent = getArtifactVersionMetaData(groupId, artifactId, ARTIFACT_FIRST_VERSION);
            artifactMetaDataDto.setCreatedOn(firstVersionContent.getCreatedOn());
        }

        return artifactMetaDataDto;
    }

    protected Map<String, String> getContentMap(long id) {
        TupleId mapping = global.get(id);
        if (mapping == null) {
            throw new ArtifactNotFoundException(null, String.valueOf(id));
        }
        Map<String, String> content = getContentMap(mapping.getGroupId(), mapping.getId(), mapping.getVersion(), ArtifactStateExt.ACTIVE_STATES);
        if (content == null) {
            throw new ArtifactNotFoundException(null, String.valueOf(id));
        }
        ArtifactStateExt.logIfDeprecated(mapping.getGroupId(), mapping.getId(), content.get(MetaDataKeys.VERSION), ArtifactStateExt.getState(content));
        return content; // already unmodifiable
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#updateArtifactState(java.lang.String, java.lang.String, io.apicurio.registry.types.ArtifactState)
     */
    @Override
    public void updateArtifactState(String groupId, String artifactId, ArtifactState state) {
        updateArtifactState(groupId, artifactId, null, state);
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#updateArtifactState(java.lang.String, java.lang.String, java.lang.Integer, io.apicurio.registry.types.ArtifactState)
     */
    @Override
    public void updateArtifactState(String groupId, String artifactId, Integer version, ArtifactState state)
            throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        Map<String, String> content = null;
        if (version == null) {
            content = getLatestContentMap(groupId, artifactId, null);
            version = Integer.parseInt(content.get(MetaDataKeys.VERSION));
        }
        int fVersion = version;
        if (content == null) {
            content = getContentMap(groupId, artifactId, version.longValue(), null);
        }
        
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        ArtifactStateExt.applyState(s -> storage.put(akey, fVersion, MetaDataKeys.STATE, s.name()), content, state);
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#createArtifact(java.lang.String, java.lang.String, io.apicurio.registry.types.ArtifactType, io.apicurio.registry.content.ContentHandle)
     */
    @Override
    public CompletionStage<ArtifactMetaDataDto> createArtifact(String groupId, String artifactId,
            ArtifactType artifactType, ContentHandle content)
            throws ArtifactAlreadyExistsException, RegistryStorageException {
        try {
            ArtifactMetaDataDto amdd = createOrUpdateArtifact(groupId, artifactId, artifactType, content, true, nextGlobalId());
            return CompletableFuture.completedFuture(amdd);
        } catch (ArtifactNotFoundException e) {
            throw new RegistryStorageException("Invalid state", e);
        }
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#createArtifactWithMetadata(java.lang.String, java.lang.String, io.apicurio.registry.types.ArtifactType, io.apicurio.registry.content.ContentHandle, io.apicurio.registry.storage.dto.EditableArtifactMetaDataDto)
     */
    @Override
    public CompletionStage<ArtifactMetaDataDto> createArtifactWithMetadata(String groupId, String artifactId,
            ArtifactType artifactType, ContentHandle content, EditableArtifactMetaDataDto metadata)
            throws ArtifactAlreadyExistsException, RegistryStorageException {
        try {
            ArtifactMetaDataDto amdd = createOrUpdateArtifact(groupId, artifactId, artifactType, content, true, nextGlobalId());
            updateArtifactMetaData(groupId, artifactId, metadata);
            DtoUtil.setEditableMetaDataInArtifact(amdd, metadata);
            return CompletableFuture.completedFuture(amdd);
        } catch (ArtifactNotFoundException e) {
            throw new RegistryStorageException("Invalid state", e);
        }
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#deleteArtifact(java.lang.String, java.lang.String)
     */
    @Override
    public SortedSet<Long> deleteArtifact(String groupId, String artifactId)
            throws ArtifactNotFoundException, RegistryStorageException {
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        Map<Long, Map<String, String>> v2c = storage.remove(akey);
        if (v2c == null) {
            throw new ArtifactNotFoundException(groupId, artifactId);
        }
        v2c.values().forEach(m -> {
            long globalId = Long.parseLong(m.get(MetaDataKeys.GLOBAL_ID));
            global.remove(globalId);
        });
        this.deleteArtifactRulesInternal(groupId, artifactId);
        return new TreeSet<>(v2c.keySet());
    }
    
    /**
     * @see io.apicurio.registry.storage.RegistryStorage#deleteArtifacts(java.lang.String)
     */
    @Override
    public void deleteArtifacts(String groupId) throws RegistryStorageException {
        storage.keySet().stream().filter(key -> groupId.equals(key.getGroupId())).forEach(key -> {
            this.deleteArtifact(key.getGroupId(), key.getArtifactId());
        });
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#getArtifact(java.lang.String, java.lang.String)
     */
    @Override
    public StoredArtifactDto getArtifact(String groupId, String artifactId)
            throws ArtifactNotFoundException, RegistryStorageException {
        return toStoredArtifact(getLatestContentMap(groupId, artifactId, ArtifactStateExt.ACTIVE_STATES));
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#updateArtifact(java.lang.String, java.lang.String, io.apicurio.registry.types.ArtifactType, io.apicurio.registry.content.ContentHandle)
     */
    @Override
    public CompletionStage<ArtifactMetaDataDto> updateArtifact(String groupId, String artifactId, ArtifactType artifactType, ContentHandle content)
            throws ArtifactNotFoundException, RegistryStorageException {
        try {
            ArtifactMetaDataDto amdd = createOrUpdateArtifact(groupId, artifactId, artifactType, content, false, nextGlobalId());
            return CompletableFuture.completedFuture(amdd);
        } catch (ArtifactAlreadyExistsException e) {
            throw new RegistryStorageException("Invalid state", e);
        }
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#updateArtifactWithMetadata(java.lang.String, java.lang.String, io.apicurio.registry.types.ArtifactType, io.apicurio.registry.content.ContentHandle, io.apicurio.registry.storage.dto.EditableArtifactMetaDataDto)
     */
    @Override
    public CompletionStage<ArtifactMetaDataDto> updateArtifactWithMetadata(String groupId, String artifactId,
            ArtifactType artifactType, ContentHandle content, EditableArtifactMetaDataDto metadata)
            throws ArtifactNotFoundException, RegistryStorageException {
        try {
            ArtifactMetaDataDto amdd = createOrUpdateArtifact(groupId, artifactId, artifactType, content, false, nextGlobalId());
            updateArtifactMetaData(groupId, artifactId, metadata);
            DtoUtil.setEditableMetaDataInArtifact(amdd, metadata);
            return CompletableFuture.completedFuture(amdd);
        } catch (ArtifactAlreadyExistsException e) {
            throw new RegistryStorageException("Invalid state", e);
        }
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#getArtifactIds(java.lang.Integer)
     */
    @Override
    public Set<String> getArtifactIds(Integer limit) {
        if (limit != null) {
            return storage.keySet()
                    .stream()
                    .map(key -> key.getArtifactId())
                    .limit(limit)
                    .collect(Collectors.toSet());
        } else {
            return storage.keySet()
                    .stream()
                    .map(key -> key.getArtifactId())
                    .collect(Collectors.toSet());
        }
    }
    
    private Set<ArtifactKey> getArtifactKeys() {
        return storage.keySet();
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#searchArtifacts(java.util.Set, io.apicurio.registry.storage.dto.OrderBy, io.apicurio.registry.storage.dto.OrderDirection, int, int)
     */
    @Override
    public ArtifactSearchResultsDto searchArtifacts(Set<SearchFilter> filters, OrderBy orderBy, OrderDirection orderDirection, int offset, int limit) {
        final LongAdder itemsCount = new LongAdder();
        final List<SearchedArtifactDto> matchedArtifacts = getArtifactKeys()
                .stream()
                .map(this::getOptionalArtifactMetadata)
                .filter(Optional::isPresent)
                .filter(amd -> artifactMatchesFilters(amd.get(), filters))
                .map(Optional::get)
                .peek(artifactId -> itemsCount.increment())
                .sorted(SearchUtil.comparator(orderBy, orderDirection))
                .skip(offset)
                .limit(limit)
                .map(SearchUtil::buildSearchedArtifact)
                .collect(Collectors.toList());

        final ArtifactSearchResultsDto artifactSearchResults = new ArtifactSearchResultsDto();
        artifactSearchResults.setArtifacts(matchedArtifacts);
        artifactSearchResults.setCount(itemsCount.intValue());

        return artifactSearchResults;
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#getArtifactMetaData(java.lang.String, java.lang.String)
     */
    @Override
    public ArtifactMetaDataDto getArtifactMetaData(String groupId, String artifactId) throws ArtifactNotFoundException, RegistryStorageException {
        final Map<String, String> content = getLatestContentMap(groupId, artifactId, ArtifactStateExt.ACTIVE_STATES);

        final ArtifactMetaDataDto artifactMetaDataDto = MetaDataKeys.toArtifactMetaData(content);
        if (artifactMetaDataDto.getVersion() != ARTIFACT_FIRST_VERSION) {
            ArtifactVersionMetaDataDto firstVersionContent = getArtifactVersionMetaData(groupId, artifactId, ARTIFACT_FIRST_VERSION);
            artifactMetaDataDto.setCreatedOn(firstVersionContent.getCreatedOn());
        }

        final SortedSet<Long> versions = getArtifactVersions(groupId, artifactId);
        if (artifactMetaDataDto.getVersion() != versions.last()) {
            final ArtifactVersionMetaDataDto artifactVersionMetaDataDto = getArtifactVersionMetaData(groupId, artifactId, versions.last());
            artifactMetaDataDto.setModifiedOn(artifactVersionMetaDataDto.getCreatedOn());
        }

        return artifactMetaDataDto;
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#getArtifactVersionMetaData(java.lang.String, java.lang.String, boolean, io.apicurio.registry.content.ContentHandle)
     */
    @Override
    public ArtifactVersionMetaDataDto getArtifactVersionMetaData(String groupId, String artifactId, boolean canonical,
            ContentHandle content) throws ArtifactNotFoundException, RegistryStorageException {
        ArtifactMetaDataDto metaData = getArtifactMetaData(groupId, artifactId);
        ArtifactTypeUtilProvider provider = factory.getArtifactTypeProvider(metaData.getType());
        ContentCanonicalizer canonicalizer = provider.getContentCanonicalizer();

        byte[] contentToCompare;
        if (canonical) {
            ContentHandle canonicalContent = canonicalizer.canonicalize(content);
            contentToCompare = canonicalContent.bytes();
        } else {
            contentToCompare = content.bytes();
        }

        Map<Long, Map<String, String>> map = getVersion2ContentMap(groupId, artifactId);
        for (Map<String, String> cMap : map.values()) {
            ContentHandle candidateContent = ContentHandle.create(MetaDataKeys.getContent(cMap));
            byte[] candidateBytes;
            if (canonical) {
                ContentHandle canonicalCandidateContent = canonicalizer.canonicalize(candidateContent);
                candidateBytes = canonicalCandidateContent.bytes();
            } else {
                candidateBytes = candidateContent.bytes();
            }
            if (Arrays.equals(contentToCompare, candidateBytes)) {
                ArtifactStateExt.logIfDeprecated(groupId, artifactId, cMap.get(MetaDataKeys.VERSION), ArtifactStateExt.getState(cMap));
                return MetaDataKeys.toArtifactVersionMetaData(cMap);
            }
        }
        throw new ArtifactNotFoundException(groupId, artifactId);
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#getArtifactMetaData(long)
     */
    @Override
    public ArtifactMetaDataDto getArtifactMetaData(long id) throws ArtifactNotFoundException, RegistryStorageException {
        Map<String, String> content = getContentMap(id);
        return MetaDataKeys.toArtifactMetaData(content);
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#updateArtifactMetaData(java.lang.String, java.lang.String, io.apicurio.registry.storage.dto.EditableArtifactMetaDataDto)
     */
    @Override
    public void updateArtifactMetaData(String groupId, String artifactId, EditableArtifactMetaDataDto metaData)
            throws ArtifactNotFoundException, RegistryStorageException, InvalidPropertiesException {
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        if (metaData.getName() != null) {
            storage.put(akey, MetaDataKeys.NAME, metaData.getName());
        }
        if (metaData.getDescription() != null) {
            storage.put(akey, MetaDataKeys.DESCRIPTION, metaData.getDescription());
        }
        if (metaData.getLabels() != null && !metaData.getLabels().isEmpty()) {
            storage.put(akey, MetaDataKeys.LABELS, String.join(",", metaData.getLabels()));
        }
        if (metaData.getProperties() != null && !metaData.getProperties().isEmpty()) {
            try {
                storage.put(akey, MetaDataKeys.PROPERTIES, new ObjectMapper().writeValueAsString(metaData.getProperties()));
            } catch (JsonProcessingException e) {
                throw new InvalidPropertiesException(MetaDataKeys.PROPERTIES + " could not be processed for storage.", e);
            }
        }
    }

    @Override
    public List<RuleType> getArtifactRules(String groupId, String artifactId) throws ArtifactNotFoundException, RegistryStorageException {
        // check if the artifact exists
        getVersion2ContentMap(groupId, artifactId);
        // get the rules
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        Set<String> arules = artifactRules.keys(akey);
        return arules.stream().map(RuleType::fromValue).collect(Collectors.toList());
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#createArtifactRuleAsync(java.lang.String, java.lang.String, io.apicurio.registry.types.RuleType, io.apicurio.registry.storage.dto.RuleConfigurationDto)
     */
    @Override
    public CompletionStage<Void> createArtifactRuleAsync(String groupId, String artifactId, RuleType rule, RuleConfigurationDto config)
            throws ArtifactNotFoundException, RuleAlreadyExistsException, RegistryStorageException {
        // check if artifact exists
        getVersion2ContentMap(groupId, artifactId);
        // create a rule for the artifact
        String cdata = config.getConfiguration();
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        String prevValue = artifactRules.putIfAbsent(akey, rule.name(), cdata == null ? "" : cdata);
        if (prevValue != null) {
            throw new RuleAlreadyExistsException(rule);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void deleteArtifactRules(String groupId, String artifactId) throws ArtifactNotFoundException, RegistryStorageException {
        // check if artifact exists
        getVersion2ContentMap(groupId, artifactId);
        this.deleteArtifactRulesInternal(groupId, artifactId);
    }

    /**
     * Internal delete of artifact rules without checking for existence of artifact first.
     * @param groupId
     * @param artifactId
     * @throws RegistryStorageException
     */
    protected void deleteArtifactRulesInternal(String groupId, String artifactId) throws RegistryStorageException {
        // delete rules
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        artifactRules.remove(akey);
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#getArtifactRule(java.lang.String, java.lang.String, io.apicurio.registry.types.RuleType)
     */
    @Override
    public RuleConfigurationDto getArtifactRule(String groupId, String artifactId, RuleType rule)
            throws ArtifactNotFoundException, RuleNotFoundException, RegistryStorageException {
        // check if artifact exists
        getVersion2ContentMap(groupId, artifactId);
        // get artifact rule
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        String config = artifactRules.get(akey, rule.name());
        if (config == null) {
            throw new RuleNotFoundException(rule);
        }
        return new RuleConfigurationDto(config);
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#updateArtifactRule(java.lang.String, java.lang.String, io.apicurio.registry.types.RuleType, io.apicurio.registry.storage.dto.RuleConfigurationDto)
     */
    @Override
    public void updateArtifactRule(String groupId, String artifactId, RuleType rule, RuleConfigurationDto config)
            throws ArtifactNotFoundException, RuleNotFoundException, RegistryStorageException {
        // check if artifact exists
        getVersion2ContentMap(groupId, artifactId);
        // update a rule for the artifact
        String cdata = config.getConfiguration();
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        String prevValue = artifactRules.putIfPresent(akey, rule.name(), cdata == null ? "" : cdata);
        if (prevValue == null) {
            throw new RuleNotFoundException(rule);
        }
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#deleteArtifactRule(java.lang.String, java.lang.String, io.apicurio.registry.types.RuleType)
     */
    @Override
    public void deleteArtifactRule(String groupId, String artifactId, RuleType rule)
            throws ArtifactNotFoundException, RuleNotFoundException, RegistryStorageException {
        // check if artifact exists
        getVersion2ContentMap(groupId, artifactId);
        // delete a rule for the artifact
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        String prevValue = artifactRules.remove(akey, rule.name());
        if (prevValue == null) {
            throw new RuleNotFoundException(rule);
        }
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#getArtifactVersions(java.lang.String, java.lang.String)
     */
    @Override
    public SortedSet<Long> getArtifactVersions(String groupId, String artifactId) throws ArtifactNotFoundException, RegistryStorageException {
        Map<Long, Map<String, String>> v2c = getVersion2ContentMap(groupId, artifactId);
        // TODO -- always new TreeSet ... optimization?!
        return new TreeSet<>(v2c.keySet());
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#searchVersions(java.lang.String, java.lang.String, int, int)
     */
    @Override
    public VersionSearchResultsDto searchVersions(String groupId, String artifactId, int offset, int limit) throws ArtifactNotFoundException, RegistryStorageException {
        final VersionSearchResultsDto versionSearchResults = new VersionSearchResultsDto();
        final Map<Long, Map<String, String>> v2c = getVersion2ContentMap(groupId, artifactId);
        final LongAdder itemsCount = new LongAdder();
        final List<SearchedVersionDto> artifactVersions = v2c.keySet().stream()
                .peek(version -> itemsCount.increment())
                .sorted(Long::compareTo)
                .skip(offset)
                .limit(limit)
                .map(version -> MetaDataKeys.toArtifactVersionMetaData(v2c.get(version)))
                .map(SearchUtil::buildSearchedVersion)
                .collect(Collectors.toList());

        versionSearchResults.setVersions(artifactVersions);
        versionSearchResults.setCount(itemsCount.intValue());

        return versionSearchResults;
    }

    @Override
    public StoredArtifactDto getArtifactVersion(long id) throws ArtifactNotFoundException, RegistryStorageException {
        Map<String, String> content = getContentMap(id);
        return toStoredArtifact(content);
    }

    @Override
    public StoredArtifactDto getArtifactVersion(String groupId, String artifactId, long version) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        Map<String, String> content = getContentMap(groupId, artifactId, version, ArtifactStateExt.ACTIVE_STATES);
        return toStoredArtifact(content);
    }

    @Override
    public void deleteArtifactVersion(String groupId, String artifactId, long version) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        deleteArtifactVersionInternal(groupId, artifactId, version);
    }

    // internal - so we don't call sub-classes method
    private void deleteArtifactVersionInternal(String groupId, String artifactId, long version) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        Long globalId = storage.remove(akey, version);
        if (globalId == null) {
            throw new VersionNotFoundException(groupId, artifactId, version);
        }
        // remove from global as well
        global.remove(globalId);
    }

    @Override
    public ArtifactVersionMetaDataDto getArtifactVersionMetaData(String groupId, String artifactId, long version)
            throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        Map<String, String> content = getContentMap(groupId, artifactId, version, null);
        return MetaDataKeys.toArtifactVersionMetaData(content);
    }

    @Override
    public void updateArtifactVersionMetaData(String groupId, String artifactId, long version, EditableArtifactMetaDataDto metaData)
            throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        if (metaData.getName() != null) {
            storage.put(akey, version, MetaDataKeys.NAME, metaData.getName());
        }
        if (metaData.getDescription() != null) {
            storage.put(akey, version, MetaDataKeys.DESCRIPTION, metaData.getDescription());
        }
        if (metaData.getLabels() != null && !metaData.getLabels().isEmpty()) {
            storage.put(akey, version, MetaDataKeys.LABELS, String.join(",", metaData.getLabels()));
        }
        if (metaData.getProperties() != null && !metaData.getProperties().isEmpty()) {
            try {
                storage.put(akey, version, MetaDataKeys.PROPERTIES, new ObjectMapper().writeValueAsString(metaData.getProperties()));
            } catch (JsonProcessingException e) {
                throw new InvalidPropertiesException(MetaDataKeys.PROPERTIES + " could not be processed for storage.", e);
            }
        }
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#deleteArtifactVersionMetaData(java.lang.String, java.lang.String, long)
     */
    @Override
    public void deleteArtifactVersionMetaData(String groupId, String artifactId, long version) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        ArtifactKey akey = new ArtifactKey(groupId, artifactId);
        storage.remove(akey, version, MetaDataKeys.NAME);
        storage.remove(akey, version, MetaDataKeys.DESCRIPTION);
        storage.remove(akey, version, MetaDataKeys.LABELS);
        storage.remove(akey, version, MetaDataKeys.PROPERTIES);
        storage.remove(akey, version, MetaDataKeys.CREATED_BY);
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#getGlobalRules()
     */
    @Override
    public List<RuleType> getGlobalRules() throws RegistryStorageException {
        return globalRules.keySet().stream().map(RuleType::fromValue).collect(Collectors.toList());
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#createGlobalRule(io.apicurio.registry.types.RuleType, io.apicurio.registry.storage.dto.RuleConfigurationDto)
     */
    @Override
    public void createGlobalRule(RuleType rule, RuleConfigurationDto config)
            throws RuleAlreadyExistsException, RegistryStorageException {
        String cdata = config.getConfiguration();
        String prevValue = globalRules.putIfAbsent(rule.name(), cdata == null ? "" : cdata);
        if (prevValue != null) {
            throw new RuleAlreadyExistsException(rule);
        }
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#deleteGlobalRules()
     */
    @Override
    public void deleteGlobalRules() throws RegistryStorageException {
        globalRules.clear();
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#getGlobalRule(io.apicurio.registry.types.RuleType)
     */
    @Override
    public RuleConfigurationDto getGlobalRule(RuleType rule) throws RuleNotFoundException, RegistryStorageException {
        String cdata = globalRules.get(rule.name());
        if (cdata == null) {
            throw new RuleNotFoundException(rule);
        }
        return new RuleConfigurationDto(cdata);
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#updateGlobalRule(io.apicurio.registry.types.RuleType, io.apicurio.registry.storage.dto.RuleConfigurationDto)
     */
    @Override
    public void updateGlobalRule(RuleType rule, RuleConfigurationDto config) throws RuleNotFoundException, RegistryStorageException {
        String rname = rule.name();
        if (!globalRules.containsKey(rname)) {
            throw new RuleNotFoundException(rule);
        }
        String cdata = config.getConfiguration();
        globalRules.put(rname, cdata == null ? "" : cdata);
    }

    /**
     * @see io.apicurio.registry.storage.RegistryStorage#deleteGlobalRule(io.apicurio.registry.types.RuleType)
     */
    @Override
    public void deleteGlobalRule(RuleType rule) throws RuleNotFoundException, RegistryStorageException {
        String prevValue = globalRules.remove(rule.name());
        if (prevValue == null) {
            throw new RuleNotFoundException(rule);
        }
    }
}
