/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.action.admin.indices.alias.IndicesAliasesClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesResponse.AliasActionResult;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.ClusterStateTaskExecutorUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.rest.action.admin.indices.AliasesNotFoundException;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.index.IndexVersionUtils;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;
import static org.elasticsearch.cluster.metadata.DataStreamTestHelper.newInstance;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

public class MetadataIndexAliasesServiceTests extends ESTestCase {
    private static TestThreadPool threadPool;
    private ClusterService clusterService;
    private MetadataIndexAliasesService service;
    private ProjectId projectId;

    @BeforeClass
    public static void setupThreadPool() {
        threadPool = new TestThreadPool(getTestClass().getName());
    }

    @Before
    public void setupServices() {
        clusterService = ClusterServiceUtils.createClusterService(threadPool);
        service = new MetadataIndexAliasesService(clusterService, null, xContentRegistry());
        projectId = randomProjectIdOrDefault();
    }

    @After
    public void closeClusterService() throws Exception {
        clusterService.close();
    }

    @AfterClass
    public static void tearDownThreadPool() {
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
        threadPool = null;
    }

    public void testAddAndRemove() {
        // Create a state with a single index
        String index = randomAlphaOfLength(5);
        ClusterState before = createIndex(clusterStateBuilder().build(), index);

        // Add an alias to it
        ClusterState after = service.applyAliasActions(
            before.projectState(projectId),
            singletonList(new AliasAction.Add(index, "test", null, null, null, null, null))
        );
        IndexAbstraction alias = after.metadata().getProject(projectId).getIndicesLookup().get("test");
        assertNotNull(alias);
        assertThat(alias.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        assertThat(alias.getIndices(), contains(after.metadata().getProject(projectId).index(index).getIndex()));
        assertAliasesVersionIncreased(index, before, after);
        assertThat(
            after.metadata().getProject(projectId).aliasedIndices("test"),
            contains(after.metadata().getProject(projectId).index(index).getIndex())
        );

        // Remove the alias from it while adding another one
        before = after;
        after = service.applyAliasActions(
            before.projectState(projectId),
            Arrays.asList(new AliasAction.Remove(index, "test", null), new AliasAction.Add(index, "test_2", null, null, null, null, null))
        );
        assertNull(after.metadata().getProject(projectId).getIndicesLookup().get("test"));
        assertThat(after.metadata().getProject(projectId).aliasedIndices("test"), empty());
        alias = after.metadata().getProject(projectId).getIndicesLookup().get("test_2");
        assertNotNull(alias);
        assertThat(alias.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        assertThat(alias.getIndices(), contains(after.metadata().getProject(projectId).index(index).getIndex()));
        assertAliasesVersionIncreased(index, before, after);
        assertThat(
            after.metadata().getProject(projectId).aliasedIndices("test_2"),
            contains(after.metadata().getProject(projectId).index(index).getIndex())
        );

        // Now just remove on its own
        before = after;
        after = service.applyAliasActions(
            before.projectState(projectId),
            singletonList(new AliasAction.Remove(index, "test_2", randomBoolean()))
        );
        assertNull(after.metadata().getProject(projectId).getIndicesLookup().get("test"));
        assertThat(after.metadata().getProject(projectId).aliasedIndices("test"), empty());
        assertNull(after.metadata().getProject(projectId).getIndicesLookup().get("test_2"));
        assertThat(after.metadata().getProject(projectId).aliasedIndices("test_2"), empty());
        assertAliasesVersionIncreased(index, before, after);
    }

    public void testMustExist() {
        // Create a state with a single index
        String index = randomAlphaOfLength(5);
        ClusterState before = createIndex(clusterStateBuilder().build(), index);

        // Add an alias to it
        ClusterState after = service.applyAliasActions(
            before.projectState(projectId),
            singletonList(new AliasAction.Add(index, "test", null, null, null, null, null))
        );
        IndexAbstraction alias = after.metadata().getProject(projectId).getIndicesLookup().get("test");
        assertNotNull(alias);
        assertThat(alias.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        assertThat(alias.getIndices(), contains(after.metadata().getProject(projectId).index(index).getIndex()));
        assertAliasesVersionIncreased(index, before, after);

        // Remove the alias from it with mustExist == true while adding another one
        before = after;
        after = service.applyAliasActions(
            before.projectState(projectId),
            Arrays.asList(new AliasAction.Remove(index, "test", true), new AliasAction.Add(index, "test_2", null, null, null, null, null))
        );
        assertNull(after.metadata().getProject(projectId).getIndicesLookup().get("test"));
        alias = after.metadata().getProject(projectId).getIndicesLookup().get("test_2");
        assertNotNull(alias);
        assertThat(alias.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        assertThat(alias.getIndices(), contains(after.metadata().getProject(projectId).index(index).getIndex()));
        assertAliasesVersionIncreased(index, before, after);

        // Now just remove on its own
        before = after;
        after = service.applyAliasActions(
            before.projectState(projectId),
            singletonList(new AliasAction.Remove(index, "test_2", randomBoolean()))
        );
        assertNull(after.metadata().getProject(projectId).getIndicesLookup().get("test"));
        assertNull(after.metadata().getProject(projectId).getIndicesLookup().get("test_2"));
        assertAliasesVersionIncreased(index, before, after);

        // Show that removing non-existing alias with mustExist == true fails
        final ClusterState finalCS = after;
        final AliasesNotFoundException iae = expectThrows(
            AliasesNotFoundException.class,
            () -> service.applyAliasActions(finalCS.projectState(projectId), singletonList(new AliasAction.Remove(index, "test_2", true)))
        );
        assertThat(iae.getMessage(), containsString("aliases [test_2] missing"));
    }

    public void testMultipleIndices() {
        final var length = randomIntBetween(2, 8);
        final Set<String> indices = Sets.newHashSetWithExpectedSize(length);
        ClusterState before = clusterStateBuilder().build();
        final var addActions = new ArrayList<AliasAction>(length);
        for (int i = 0; i < length; i++) {
            final String index = randomValueOtherThanMany(v -> indices.add(v) == false, () -> randomAlphaOfLength(8));
            before = createIndex(before, index);
            addActions.add(new AliasAction.Add(index, "alias-" + index, null, null, null, null, null));
        }
        final ClusterState afterAddingAliasesToAll = service.applyAliasActions(before.projectState(projectId), addActions);
        assertAliasesVersionIncreased(indices.toArray(new String[0]), before, afterAddingAliasesToAll);

        // now add some aliases randomly
        final Set<String> randomIndices = Sets.newHashSetWithExpectedSize(length);
        final var randomAddActions = new ArrayList<AliasAction>(length);
        for (var index : indices) {
            if (randomBoolean()) {
                randomAddActions.add(new AliasAction.Add(index, "random-alias-" + index, null, null, null, null, null));
                randomIndices.add(index);
            }
        }
        final ClusterState afterAddingRandomAliases = service.applyAliasActions(
            afterAddingAliasesToAll.projectState(projectId),
            randomAddActions
        );
        assertAliasesVersionIncreased(randomIndices.toArray(new String[0]), afterAddingAliasesToAll, afterAddingRandomAliases);
        assertAliasesVersionUnchanged(
            Sets.difference(indices, randomIndices).toArray(new String[0]),
            afterAddingAliasesToAll,
            afterAddingRandomAliases
        );
    }

    public void testChangingWriteAliasStateIncreasesAliasesVersion() {
        final String index = randomAlphaOfLength(8);
        final ClusterState before = createIndex(clusterStateBuilder().build(), index);

        final ClusterState afterAddWriteAlias = service.applyAliasActions(
            before.projectState(projectId),
            singletonList(new AliasAction.Add(index, "test", null, null, null, true, null))
        );
        assertAliasesVersionIncreased(index, before, afterAddWriteAlias);

        final ClusterState afterChangeWriteAliasToNonWriteAlias = service.applyAliasActions(
            afterAddWriteAlias.projectState(projectId),
            singletonList(new AliasAction.Add(index, "test", null, null, null, false, null))
        );
        assertAliasesVersionIncreased(index, afterAddWriteAlias, afterChangeWriteAliasToNonWriteAlias);

        final ClusterState afterChangeNonWriteAliasToWriteAlias = service.applyAliasActions(
            afterChangeWriteAliasToNonWriteAlias.projectState(projectId),
            singletonList(new AliasAction.Add(index, "test", null, null, null, true, null))
        );
        assertAliasesVersionIncreased(index, afterChangeWriteAliasToNonWriteAlias, afterChangeNonWriteAliasToWriteAlias);
    }

    public void testAddingAliasMoreThanOnceShouldOnlyIncreaseAliasesVersionByOne() {
        final String index = randomAlphaOfLength(8);
        final ClusterState before = createIndex(clusterStateBuilder().build(), index);

        // add an alias to the index multiple times
        final int length = randomIntBetween(2, 8);
        final var addActions = new ArrayList<AliasAction>(length);
        for (int i = 0; i < length; i++) {
            addActions.add(new AliasAction.Add(index, "test", null, null, null, null, null));
        }
        final ClusterState afterAddingAliases = service.applyAliasActions(before.projectState(projectId), addActions);

        assertAliasesVersionIncreased(index, before, afterAddingAliases);
    }

    public void testAliasesVersionUnchangedWhenActionsAreIdempotent() {
        final String index = randomAlphaOfLength(8);
        final ClusterState before = createIndex(clusterStateBuilder().build(), index);

        // add some aliases to the index
        final int length = randomIntBetween(1, 8);
        final var aliasNames = new HashSet<String>();
        final var addActions = new ArrayList<AliasAction>(length);
        for (int i = 0; i < length; i++) {
            final String aliasName = randomValueOtherThanMany(v -> aliasNames.add(v) == false, () -> randomAlphaOfLength(8));
            addActions.add(new AliasAction.Add(index, aliasName, null, null, null, null, null));
        }
        final ClusterState afterAddingAlias = service.applyAliasActions(before.projectState(projectId), addActions);

        // now perform a remove and add for each alias which is idempotent, the resulting aliases are unchanged
        final var removeAndAddActions = new ArrayList<AliasAction>(2 * length);
        for (final var aliasName : aliasNames) {
            removeAndAddActions.add(new AliasAction.Remove(index, aliasName, null));
            removeAndAddActions.add(new AliasAction.Add(index, aliasName, null, null, null, null, null));
        }
        final ClusterState afterRemoveAndAddAlias = service.applyAliasActions(
            afterAddingAlias.projectState(projectId),
            removeAndAddActions
        );
        assertAliasesVersionUnchanged(index, afterAddingAlias, afterRemoveAndAddAlias);
    }

    public void testSwapIndexWithAlias() {
        // Create "test" and "test_2"
        ClusterState before = createIndex(clusterStateBuilder().build(), "test");
        before = createIndex(before, "test_2");

        // Now remove "test" and add an alias to "test" to "test_2" in one go
        ClusterState after = service.applyAliasActions(
            before.projectState(projectId),
            Arrays.asList(new AliasAction.Add("test_2", "test", null, null, null, null, null), new AliasAction.RemoveIndex("test"))
        );
        IndexAbstraction alias = after.metadata().getProject(projectId).getIndicesLookup().get("test");
        assertNotNull(alias);
        assertThat(alias.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        assertThat(alias.getIndices(), contains(after.metadata().getProject(projectId).index("test_2").getIndex()));
        assertAliasesVersionIncreased("test_2", before, after);
    }

    public void testAddAliasToRemovedIndex() {
        // Create "test"
        ClusterState before = createIndex(clusterStateBuilder().build(), "test");

        // Attempt to add an alias to "test" at the same time as we remove it
        IndexNotFoundException e = expectThrows(
            IndexNotFoundException.class,
            () -> service.applyAliasActions(
                before.projectState(projectId),
                Arrays.asList(new AliasAction.Add("test", "alias", null, null, null, null, null), new AliasAction.RemoveIndex("test"))
            )
        );
        assertEquals("test", e.getIndex().getName());
    }

    public void testRemoveIndexTwice() {
        // Create "test"
        ClusterState before = createIndex(clusterStateBuilder().build(), "test");

        // Try to remove an index twice. This should just remove the index once....
        ClusterState after = service.applyAliasActions(
            before.projectState(projectId),
            Arrays.asList(new AliasAction.RemoveIndex("test"), new AliasAction.RemoveIndex("test"))
        );
        assertNull(after.metadata().getProject(projectId).getIndicesLookup().get("test"));
    }

    public void testAddWriteOnlyWithNoExistingAliases() {
        ClusterState before = createIndex(clusterStateBuilder().build(), "test");

        ClusterState after = service.applyAliasActions(
            before.projectState(projectId),
            List.of(new AliasAction.Add("test", "alias", null, null, null, false, null))
        );
        assertFalse(after.metadata().getProject(projectId).index("test").getAliases().get("alias").writeIndex());
        assertNull(after.metadata().getProject(projectId).getIndicesLookup().get("alias").getWriteIndex());
        assertAliasesVersionIncreased("test", before, after);

        after = service.applyAliasActions(
            before.projectState(projectId),
            List.of(new AliasAction.Add("test", "alias", null, null, null, null, null))
        );
        assertNull(after.metadata().getProject(projectId).index("test").getAliases().get("alias").writeIndex());
        assertThat(
            after.metadata()
                .getProject(projectId)
                .index(after.metadata().getProject(projectId).getIndicesLookup().get("alias").getWriteIndex()),
            equalTo(after.metadata().getProject(projectId).index("test"))
        );
        assertAliasesVersionIncreased("test", before, after);

        after = service.applyAliasActions(
            before.projectState(projectId),
            List.of(new AliasAction.Add("test", "alias", null, null, null, true, null))
        );
        assertTrue(after.metadata().getProject(projectId).index("test").getAliases().get("alias").writeIndex());
        assertThat(
            after.metadata()
                .getProject(projectId)
                .index(after.metadata().getProject(projectId).getIndicesLookup().get("alias").getWriteIndex()),
            equalTo(after.metadata().getProject(projectId).index("test"))
        );
        assertAliasesVersionIncreased("test", before, after);
    }

    public void testAddWriteOnlyWithExistingWriteIndex() {
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder("test")
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        IndexMetadata.Builder indexMetadata2 = IndexMetadata.builder("test2")
            .putAlias(AliasMetadata.builder("alias").writeIndex(true).build())
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        ClusterState before = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .projectMetadata(Map.of(projectId, ProjectMetadata.builder(projectId).put(indexMetadata).put(indexMetadata2).build()))
            )
            .build();

        ClusterState after = service.applyAliasActions(
            before.projectState(projectId),
            List.of(new AliasAction.Add("test", "alias", null, null, null, null, null))
        );
        assertNull(after.metadata().getProject(projectId).index("test").getAliases().get("alias").writeIndex());
        assertThat(
            after.metadata()
                .getProject(projectId)
                .index(after.metadata().getProject(projectId).getIndicesLookup().get("alias").getWriteIndex()),
            equalTo(after.metadata().getProject(projectId).index("test2"))
        );
        assertAliasesVersionIncreased("test", before, after);
        assertAliasesVersionUnchanged("test2", before, after);

        Exception exception = expectThrows(
            IllegalStateException.class,
            () -> service.applyAliasActions(
                before.projectState(projectId),
                List.of(new AliasAction.Add("test", "alias", null, null, null, true, null))
            )
        );
        assertThat(exception.getMessage(), startsWith("alias [alias] has more than one write index ["));
    }

    public void testSwapWriteOnlyIndex() {
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder("test")
            .putAlias(AliasMetadata.builder("alias").writeIndex(true).build())
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        IndexMetadata.Builder indexMetadata2 = IndexMetadata.builder("test2")
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        ClusterState before = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .projectMetadata(Map.of(projectId, ProjectMetadata.builder(projectId).put(indexMetadata).put(indexMetadata2).build()))
            )
            .build();

        Boolean unsetValue = randomBoolean() ? null : false;
        List<AliasAction> swapActions = Arrays.asList(
            new AliasAction.Add("test", "alias", null, null, null, unsetValue, null),
            new AliasAction.Add("test2", "alias", null, null, null, true, null)
        );
        Collections.shuffle(swapActions, random());
        ClusterState after = service.applyAliasActions(before.projectState(projectId), swapActions);
        assertThat(after.metadata().getProject(projectId).index("test").getAliases().get("alias").writeIndex(), equalTo(unsetValue));
        assertTrue(after.metadata().getProject(projectId).index("test2").getAliases().get("alias").writeIndex());
        assertThat(
            after.metadata()
                .getProject(projectId)
                .index(after.metadata().getProject(projectId).getIndicesLookup().get("alias").getWriteIndex()),
            equalTo(after.metadata().getProject(projectId).index("test2"))
        );
        assertAliasesVersionIncreased("test", before, after);
        assertAliasesVersionIncreased("test2", before, after);
    }

    public void testAddWriteOnlyWithExistingNonWriteIndices() {
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder("test")
            .putAlias(AliasMetadata.builder("alias").writeIndex(randomBoolean() ? null : false).build())
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        IndexMetadata.Builder indexMetadata2 = IndexMetadata.builder("test2")
            .putAlias(AliasMetadata.builder("alias").writeIndex(randomBoolean() ? null : false).build())
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        IndexMetadata.Builder indexMetadata3 = IndexMetadata.builder("test3")
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        ClusterState before = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .projectMetadata(
                        Map.of(
                            projectId,
                            ProjectMetadata.builder(projectId).put(indexMetadata).put(indexMetadata2).put(indexMetadata3).build()
                        )
                    )
            )
            .build();

        assertNull(before.metadata().getProject(projectId).getIndicesLookup().get("alias").getWriteIndex());

        ClusterState after = service.applyAliasActions(
            before.projectState(projectId),
            List.of(new AliasAction.Add("test3", "alias", null, null, null, true, null))
        );
        assertTrue(after.metadata().getProject(projectId).index("test3").getAliases().get("alias").writeIndex());
        assertThat(
            after.metadata()
                .getProject(projectId)
                .index(after.metadata().getProject(projectId).getIndicesLookup().get("alias").getWriteIndex()),
            equalTo(after.metadata().getProject(projectId).index("test3"))
        );
        assertAliasesVersionUnchanged("test", before, after);
        assertAliasesVersionUnchanged("test2", before, after);
        assertAliasesVersionIncreased("test3", before, after);
    }

    public void testAddWriteOnlyWithIndexRemoved() {
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder("test")
            .putAlias(AliasMetadata.builder("alias").build())
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        IndexMetadata.Builder indexMetadata2 = IndexMetadata.builder("test2")
            .putAlias(AliasMetadata.builder("alias").build())
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        ClusterState before = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .projectMetadata(Map.of(projectId, ProjectMetadata.builder(projectId).put(indexMetadata).put(indexMetadata2).build()))
            )
            .build();

        assertNull(before.metadata().getProject(projectId).index("test").getAliases().get("alias").writeIndex());
        assertNull(before.metadata().getProject(projectId).index("test2").getAliases().get("alias").writeIndex());
        assertNull(before.metadata().getProject(projectId).getIndicesLookup().get("alias").getWriteIndex());

        ClusterState after = service.applyAliasActions(
            before.projectState(projectId),
            Collections.singletonList(new AliasAction.RemoveIndex("test"))
        );
        assertNull(after.metadata().getProject(projectId).index("test2").getAliases().get("alias").writeIndex());
        assertThat(
            after.metadata()
                .getProject(projectId)
                .index(after.metadata().getProject(projectId).getIndicesLookup().get("alias").getWriteIndex()),
            equalTo(after.metadata().getProject(projectId).index("test2"))
        );
        assertAliasesVersionUnchanged("test2", before, after);
    }

    public void testAddWriteOnlyValidatesAgainstMetadataBuilder() {
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder("test")
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        IndexMetadata.Builder indexMetadata2 = IndexMetadata.builder("test2")
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        ClusterState before = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .projectMetadata(Map.of(projectId, ProjectMetadata.builder(projectId).put(indexMetadata).put(indexMetadata2).build()))
            )
            .build();

        Exception exception = expectThrows(
            IllegalStateException.class,
            () -> service.applyAliasActions(
                before.projectState(projectId),
                Arrays.asList(
                    new AliasAction.Add("test", "alias", null, null, null, true, null),
                    new AliasAction.Add("test2", "alias", null, null, null, true, null)
                )
            )
        );
        assertThat(exception.getMessage(), startsWith("alias [alias] has more than one write index ["));
    }

    public void testHiddenPropertyValidation() {
        ClusterState originalState = clusterStateBuilder().build();
        originalState = createIndex(originalState, "test1");
        originalState = createIndex(originalState, "test2");

        {
            // Add a non-hidden alias to one index
            ClusterState testState = service.applyAliasActions(
                originalState.projectState(projectId),
                Collections.singletonList(new AliasAction.Add("test1", "alias", null, null, null, null, randomFrom(false, null)))
            );

            // Adding the same alias as hidden to another index should throw
            Exception ex = expectThrows(IllegalStateException.class, () -> // Add a non-hidden alias to one index
            service.applyAliasActions(
                testState.projectState(projectId),
                Collections.singletonList(new AliasAction.Add("test2", "alias", null, null, null, null, true))
            ));
            assertThat(ex.getMessage(), containsString("alias [alias] has is_hidden set to true on indices"));
        }

        {
            // Add a hidden alias to one index
            ClusterState testState = service.applyAliasActions(
                originalState.projectState(projectId),
                Collections.singletonList(new AliasAction.Add("test1", "alias", null, null, null, null, true))
            );

            // Adding the same alias as non-hidden to another index should throw
            Exception ex = expectThrows(IllegalStateException.class, () -> // Add a non-hidden alias to one index
            service.applyAliasActions(
                testState.projectState(projectId),
                Collections.singletonList(new AliasAction.Add("test2", "alias", null, null, null, null, randomFrom(false, null)))
            ));
            assertThat(ex.getMessage(), containsString("alias [alias] has is_hidden set to true on indices"));
        }

        {
            // Add a non-hidden alias to one index
            ClusterState testState = service.applyAliasActions(
                originalState.projectState(projectId),
                Collections.singletonList(new AliasAction.Add("test1", "alias", null, null, null, null, randomFrom(false, null)))
            );

            // Adding the same alias as non-hidden should be OK
            service.applyAliasActions(
                testState.projectState(projectId),
                Collections.singletonList(new AliasAction.Add("test2", "alias", null, null, null, null, randomFrom(false, null)))
            );
        }

        {
            // Add a hidden alias to one index
            ClusterState testState = service.applyAliasActions(
                originalState.projectState(projectId),
                Collections.singletonList(new AliasAction.Add("test1", "alias", null, null, null, null, true))
            );

            // Adding the same alias as hidden should be OK
            service.applyAliasActions(
                testState.projectState(projectId),
                Collections.singletonList(new AliasAction.Add("test2", "alias", null, null, null, null, true))
            );
        }
    }

    public void testSimultaneousHiddenPropertyValidation() {
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder("test")
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        IndexMetadata.Builder indexMetadata2 = IndexMetadata.builder("test2")
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1);
        ClusterState before = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .projectMetadata(Map.of(projectId, ProjectMetadata.builder(projectId).put(indexMetadata).put(indexMetadata2).build()))
            )
            .build();

        {
            // These should all be fine
            applyHiddenAliasMix(before, null, null);
            applyHiddenAliasMix(before, false, false);
            applyHiddenAliasMix(before, false, null);
            applyHiddenAliasMix(before, null, false);

            applyHiddenAliasMix(before, true, true);
        }

        {
            Exception exception = expectThrows(
                IllegalStateException.class,
                () -> applyHiddenAliasMix(before, true, randomFrom(false, null))
            );
            assertThat(exception.getMessage(), startsWith("alias [alias] has is_hidden set to true on indices ["));
        }

        {
            Exception exception = expectThrows(
                IllegalStateException.class,
                () -> applyHiddenAliasMix(before, randomFrom(false, null), true)
            );
            assertThat(exception.getMessage(), startsWith("alias [alias] has is_hidden set to true on indices ["));
        }
    }

    public void testAliasesForDataStreamBackingIndicesNotSupported() {
        long epochMillis = randomLongBetween(1580536800000L, 1583042400000L);
        String dataStreamName = "foo-stream";
        String backingIndexName = DataStream.getDefaultBackingIndexName(dataStreamName, 1, epochMillis);
        IndexMetadata indexMetadata = IndexMetadata.builder(backingIndexName)
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(1)
            .build();
        ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(
                Metadata.builder()
                    .projectMetadata(
                        Map.of(
                            projectId,
                            ProjectMetadata.builder(projectId)
                                .put(indexMetadata, true)
                                .put(newInstance(dataStreamName, singletonList(indexMetadata.getIndex())))
                                .build()
                        )
                    )
            )
            .build();

        IllegalArgumentException exception = expectThrows(
            IllegalArgumentException.class,
            () -> service.applyAliasActions(
                state.projectState(projectId),
                singletonList(new AliasAction.Add(backingIndexName, "test", null, null, null, null, null))
            )
        );
        assertThat(
            exception.getMessage(),
            is(
                "The provided index ["
                    + backingIndexName
                    + "] is a backing index belonging to data "
                    + "stream [foo-stream]. Data stream backing indices don't support alias operations."
            )
        );
    }

    public void testDataStreamAliases() {
        ProjectMetadata project = DataStreamTestHelper.getProjectWithDataStreams(
            List.of(new Tuple<>("logs-foobar", 1), new Tuple<>("metrics-foobar", 1)),
            List.of()
        );

        ClusterState result = service.applyAliasActions(
            projectStateFromProject(project),
            List.of(
                new AliasAction.AddDataStreamAlias("foobar", "logs-foobar", null, null),
                new AliasAction.AddDataStreamAlias("foobar", "metrics-foobar", null, null)
            )
        );
        assertThat(result.metadata().getProject(project.id()).dataStreamAliases().get("foobar"), notNullValue());
        assertThat(
            result.metadata().getProject(project.id()).dataStreamAliases().get("foobar").getDataStreams(),
            containsInAnyOrder("logs-foobar", "metrics-foobar")
        );

        result = service.applyAliasActions(
            result.projectState(project.id()),
            List.of(new AliasAction.RemoveDataStreamAlias("foobar", "logs-foobar", null))
        );
        assertThat(result.metadata().getProject(project.id()).dataStreamAliases().get("foobar"), notNullValue());
        assertThat(
            result.metadata().getProject(project.id()).dataStreamAliases().get("foobar").getDataStreams(),
            containsInAnyOrder("metrics-foobar")
        );

        result = service.applyAliasActions(
            result.projectState(project.id()),
            List.of(new AliasAction.RemoveDataStreamAlias("foobar", "metrics-foobar", null))
        );
        assertThat(result.metadata().getProject(project.id()).dataStreamAliases().get("foobar"), nullValue());
    }

    public void testDataStreamAliasesWithWriteFlag() {
        ProjectMetadata project = DataStreamTestHelper.getProjectWithDataStreams(
            List.of(new Tuple<>("logs-http-emea", 1), new Tuple<>("logs-http-nasa", 1)),
            List.of()
        );

        ClusterState result = service.applyAliasActions(
            projectStateFromProject(project),
            List.of(
                new AliasAction.AddDataStreamAlias("logs-http", "logs-http-emea", true, null),
                new AliasAction.AddDataStreamAlias("logs-http", "logs-http-nasa", null, null)
            )
        );
        assertThat(result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http"), notNullValue());
        assertThat(
            result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http").getDataStreams(),
            containsInAnyOrder("logs-http-nasa", "logs-http-emea")
        );
        assertThat(
            result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http").getWriteDataStream(),
            equalTo("logs-http-emea")
        );

        result = service.applyAliasActions(
            projectStateFromProject(project),
            List.of(
                new AliasAction.AddDataStreamAlias("logs-http", "logs-http-emea", false, null),
                new AliasAction.AddDataStreamAlias("logs-http", "logs-http-nasa", true, null)
            )
        );
        assertThat(result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http"), notNullValue());
        assertThat(
            result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http").getDataStreams(),
            containsInAnyOrder("logs-http-nasa", "logs-http-emea")
        );
        assertThat(
            result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http").getWriteDataStream(),
            equalTo("logs-http-nasa")
        );

        result = service.applyAliasActions(
            result.projectState(project.id()),
            List.of(new AliasAction.RemoveDataStreamAlias("logs-http", "logs-http-emea", null))
        );
        assertThat(result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http"), notNullValue());
        assertThat(
            result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http").getDataStreams(),
            contains("logs-http-nasa")
        );
        assertThat(
            result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http").getWriteDataStream(),
            equalTo("logs-http-nasa")
        );

        result = service.applyAliasActions(
            result.projectState(project.id()),
            List.of(new AliasAction.RemoveDataStreamAlias("logs-http", "logs-http-nasa", null))
        );
        assertThat(result.metadata().getProject(project.id()).dataStreamAliases().get("logs-http"), nullValue());
    }

    public void testAddAndRemoveAliasClusterStateUpdate() throws Exception {
        // Create a state with a single index
        String index = randomAlphaOfLength(5);
        ClusterState before = createIndex(clusterStateBuilder().build(), index);
        IndicesAliasesClusterStateUpdateRequest addAliasRequest = new IndicesAliasesClusterStateUpdateRequest(
            TEST_REQUEST_TIMEOUT,
            TEST_REQUEST_TIMEOUT,
            projectId,
            List.of(new AliasAction.Add(index, "test", null, null, null, null, null)),
            List.of(AliasActionResult.buildSuccess(List.of(index), AliasActions.add().aliases("test").indices(index)))
        );
        IndicesAliasesClusterStateUpdateRequest removeAliasRequest = new IndicesAliasesClusterStateUpdateRequest(
            TEST_REQUEST_TIMEOUT,
            TEST_REQUEST_TIMEOUT,
            projectId,
            List.of(new AliasAction.Remove(index, "test", true)),
            List.of(AliasActionResult.buildSuccess(List.of(index), AliasActions.remove().aliases("test").indices(index)))
        );

        ClusterState after = ClusterStateTaskExecutorUtils.executeAndAssertSuccessful(
            before,
            service.getExecutor(),
            List.of(
                new MetadataIndexAliasesService.ApplyAliasesTask(addAliasRequest, null),
                // Repeat the same change to ensure that the clte version won't increase
                new MetadataIndexAliasesService.ApplyAliasesTask(addAliasRequest, null),
                new MetadataIndexAliasesService.ApplyAliasesTask(removeAliasRequest, null),
                new MetadataIndexAliasesService.ApplyAliasesTask(addAliasRequest, null)
            )
        );

        IndexAbstraction alias = after.metadata().getProject(projectId).getIndicesLookup().get("test");
        assertNotNull(alias);
        assertThat(alias.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        assertThat(alias.getIndices(), contains(after.metadata().getProject(projectId).index(index).getIndex()));
        assertAliasesVersionIncreased(new String[] { index }, before, after, 3);
        assertThat(
            after.metadata().getProject(projectId).aliasedIndices("test"),
            contains(after.metadata().getProject(projectId).index(index).getIndex())
        );
    }

    public void testEmptyTaskListProducesSameClusterState() throws Exception {
        String index = randomAlphaOfLength(5);
        ClusterState before = createIndex(clusterStateBuilder().build(), index);
        ClusterState after = ClusterStateTaskExecutorUtils.executeAndAssertSuccessful(before, service.getExecutor(), List.of());
        assertSame(before, after);
    }

    private ClusterState applyHiddenAliasMix(ClusterState before, Boolean isHidden1, Boolean isHidden2) {
        return service.applyAliasActions(
            before.projectState(projectId),
            Arrays.asList(
                new AliasAction.Add("test", "alias", null, null, null, null, isHidden1),
                new AliasAction.Add("test2", "alias", null, null, null, null, isHidden2)
            )
        );
    }

    private ClusterState createIndex(ClusterState state, String index) {
        IndexMetadata indexMetadata = IndexMetadata.builder(index)
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersionUtils.randomWriteVersion()))
            .numberOfShards(1)
            .numberOfReplicas(1)
            .build();
        assertThat(state.metadata().projects().keySet(), hasItem(projectId));
        return ClusterState.builder(state)
            .putProjectMetadata(ProjectMetadata.builder(state.metadata().getProject(projectId)).put(indexMetadata, false))
            .build();
    }

    private void assertAliasesVersionUnchanged(final String index, final ClusterState before, final ClusterState after) {
        assertAliasesVersionUnchanged(new String[] { index }, before, after);
    }

    private void assertAliasesVersionUnchanged(final String[] indices, final ClusterState before, final ClusterState after) {
        for (final var index : indices) {
            final long expected = before.metadata().getProject(projectId).index(index).getAliasesVersion();
            final long actual = after.metadata().getProject(projectId).index(index).getAliasesVersion();
            assertThat("index metadata aliases version mismatch", actual, equalTo(expected));
        }
    }

    private void assertAliasesVersionIncreased(final String index, final ClusterState before, final ClusterState after) {
        assertAliasesVersionIncreased(new String[] { index }, before, after);
    }

    private void assertAliasesVersionIncreased(final String[] indices, final ClusterState before, final ClusterState after) {
        assertAliasesVersionIncreased(indices, before, after, 1);
    }

    private void assertAliasesVersionIncreased(
        final String[] indices,
        final ClusterState before,
        final ClusterState after,
        final int diff
    ) {
        for (final var index : indices) {
            final long expected = diff + before.metadata().getProject(projectId).index(index).getAliasesVersion();
            final long actual = after.metadata().getProject(projectId).index(index).getAliasesVersion();
            assertThat("index metadata aliases version mismatch", actual, equalTo(expected));
        }
    }

    private ClusterState.Builder clusterStateBuilder() {
        return ClusterState.builder(ClusterName.DEFAULT).putProjectMetadata(ProjectMetadata.builder(projectId).build());
    }
}
