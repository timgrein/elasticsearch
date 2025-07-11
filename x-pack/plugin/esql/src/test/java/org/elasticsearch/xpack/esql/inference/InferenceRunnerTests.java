/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.inference;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.inference.ModelConfigurations;
import org.elasticsearch.inference.ServiceSettings;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.xpack.core.inference.action.GetInferenceModelAction;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.logical.inference.InferencePlan;
import org.elasticsearch.xpack.esql.plugin.EsqlPlugin;
import org.junit.After;
import org.junit.Before;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class InferenceRunnerTests extends ESTestCase {
    private TestThreadPool threadPool;

    @Before
    public void setThreadPool() {
        threadPool = new TestThreadPool(
            getTestClass().getSimpleName(),
            new FixedExecutorBuilder(
                Settings.EMPTY,
                EsqlPlugin.ESQL_WORKER_THREAD_POOL_NAME,
                between(1, 10),
                1024,
                "esql",
                EsExecutors.TaskTrackingConfig.DEFAULT
            )
        );
    }

    @After
    public void shutdownThreadPool() {
        terminate(threadPool);
    }

    public void testResolveInferenceIds() throws Exception {
        InferenceRunner inferenceRunner = new InferenceRunner(mockClient(), threadPool);
        List<InferencePlan<?>> inferencePlans = List.of(mockInferencePlan("rerank-plan"));
        SetOnce<InferenceResolution> inferenceResolutionSetOnce = new SetOnce<>();

        inferenceRunner.resolveInferenceIds(inferencePlans, ActionListener.wrap(inferenceResolutionSetOnce::set, e -> {
            throw new RuntimeException(e);
        }));

        assertBusy(() -> {
            InferenceResolution inferenceResolution = inferenceResolutionSetOnce.get();
            assertNotNull(inferenceResolution);
            assertThat(inferenceResolution.resolvedInferences(), contains(new ResolvedInference("rerank-plan", TaskType.RERANK)));
            assertThat(inferenceResolution.hasError(), equalTo(false));
        });
    }

    public void testResolveMultipleInferenceIds() throws Exception {
        InferenceRunner inferenceRunner = new InferenceRunner(mockClient(), threadPool);
        List<InferencePlan<?>> inferencePlans = List.of(
            mockInferencePlan("rerank-plan"),
            mockInferencePlan("rerank-plan"),
            mockInferencePlan("completion-plan")
        );
        SetOnce<InferenceResolution> inferenceResolutionSetOnce = new SetOnce<>();

        inferenceRunner.resolveInferenceIds(inferencePlans, ActionListener.wrap(inferenceResolutionSetOnce::set, e -> {
            throw new RuntimeException(e);
        }));

        assertBusy(() -> {
            InferenceResolution inferenceResolution = inferenceResolutionSetOnce.get();
            assertNotNull(inferenceResolution);

            assertThat(
                inferenceResolution.resolvedInferences(),
                contains(
                    new ResolvedInference("rerank-plan", TaskType.RERANK),
                    new ResolvedInference("completion-plan", TaskType.COMPLETION)
                )
            );
            assertThat(inferenceResolution.hasError(), equalTo(false));
        });
    }

    public void testResolveMissingInferenceIds() throws Exception {
        InferenceRunner inferenceRunner = new InferenceRunner(mockClient(), threadPool);
        List<InferencePlan<?>> inferencePlans = List.of(mockInferencePlan("missing-plan"));

        SetOnce<InferenceResolution> inferenceResolutionSetOnce = new SetOnce<>();

        inferenceRunner.resolveInferenceIds(inferencePlans, ActionListener.wrap(inferenceResolutionSetOnce::set, e -> {
            throw new RuntimeException(e);
        }));

        assertBusy(() -> {
            InferenceResolution inferenceResolution = inferenceResolutionSetOnce.get();
            assertNotNull(inferenceResolution);

            assertThat(inferenceResolution.resolvedInferences(), empty());
            assertThat(inferenceResolution.hasError(), equalTo(true));
            assertThat(inferenceResolution.getError("missing-plan"), equalTo("inference endpoint not found"));
        });
    }

    @SuppressWarnings({ "unchecked", "raw-types" })
    private Client mockClient() {
        Client client = mock(Client.class);
        doAnswer(i -> {
            Runnable sendResponse = () -> {
                GetInferenceModelAction.Request request = i.getArgument(1, GetInferenceModelAction.Request.class);
                ActionListener<ActionResponse> listener = (ActionListener<ActionResponse>) i.getArgument(2, ActionListener.class);
                ActionResponse response = getInferenceModelResponse(request);

                if (response == null) {
                    listener.onFailure(new ResourceNotFoundException("inference endpoint not found"));
                } else {
                    listener.onResponse(response);
                }
            };

            if (randomBoolean()) {
                sendResponse.run();
            } else {
                threadPool.schedule(
                    sendResponse,
                    TimeValue.timeValueNanos(between(1, 1_000)),
                    threadPool.executor(EsqlPlugin.ESQL_WORKER_THREAD_POOL_NAME)
                );
            }

            return null;
        }).when(client).execute(eq(GetInferenceModelAction.INSTANCE), any(), any());
        return client;
    }

    private static ActionResponse getInferenceModelResponse(GetInferenceModelAction.Request request) {
        GetInferenceModelAction.Response response = mock(GetInferenceModelAction.Response.class);

        if (request.getInferenceEntityId().equals("rerank-plan")) {
            when(response.getEndpoints()).thenReturn(List.of(mockModelConfig("rerank-plan", TaskType.RERANK)));
            return response;
        }

        if (request.getInferenceEntityId().equals("completion-plan")) {
            when(response.getEndpoints()).thenReturn(List.of(mockModelConfig("completion-plan", TaskType.COMPLETION)));
            return response;
        }

        return null;
    }

    private static ModelConfigurations mockModelConfig(String inferenceId, TaskType taskType) {
        return new ModelConfigurations(inferenceId, taskType, randomIdentifier(), mock(ServiceSettings.class));
    }

    private static InferencePlan<?> mockInferencePlan(String inferenceId) {
        InferencePlan<?> plan = mock(InferencePlan.class);
        when(plan.inferenceId()).thenReturn(Literal.keyword(Source.EMPTY, inferenceId));
        return plan;
    }
}
