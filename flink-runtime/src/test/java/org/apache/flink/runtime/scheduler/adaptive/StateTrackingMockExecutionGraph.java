/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.scheduler.adaptive;

import org.apache.flink.api.common.ArchivedExecutionConfig;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.accumulators.Accumulator;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.accumulators.AccumulatorSnapshot;
import org.apache.flink.runtime.accumulators.StringifiedAccumulatorResult;
import org.apache.flink.runtime.checkpoint.CheckpointCoordinator;
import org.apache.flink.runtime.checkpoint.CheckpointIDCounter;
import org.apache.flink.runtime.checkpoint.CheckpointStatsSnapshot;
import org.apache.flink.runtime.checkpoint.CheckpointStatsTracker;
import org.apache.flink.runtime.checkpoint.CheckpointsCleaner;
import org.apache.flink.runtime.checkpoint.CompletedCheckpointStore;
import org.apache.flink.runtime.checkpoint.MasterTriggerRestoreHook;
import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutor;
import org.apache.flink.runtime.executiongraph.AccessExecution;
import org.apache.flink.runtime.executiongraph.ErrorInfo;
import org.apache.flink.runtime.executiongraph.Execution;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.executiongraph.ExecutionGraph;
import org.apache.flink.runtime.executiongraph.ExecutionJobVertex;
import org.apache.flink.runtime.executiongraph.ExecutionVertex;
import org.apache.flink.runtime.executiongraph.IntermediateResult;
import org.apache.flink.runtime.executiongraph.IntermediateResultPartition;
import org.apache.flink.runtime.executiongraph.JobStatusListener;
import org.apache.flink.runtime.executiongraph.JobVertexInputInfo;
import org.apache.flink.runtime.executiongraph.TaskExecutionStateTransition;
import org.apache.flink.runtime.executiongraph.failover.ResultPartitionAvailabilityChecker;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.runtime.jobgraph.JobType;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.tasks.CheckpointCoordinatorConfiguration;
import org.apache.flink.runtime.metrics.groups.JobManagerJobMetricGroup;
import org.apache.flink.runtime.query.KvStateLocationRegistry;
import org.apache.flink.runtime.rest.messages.JobPlanInfo;
import org.apache.flink.runtime.scheduler.InternalFailuresListener;
import org.apache.flink.runtime.scheduler.VertexParallelismStore;
import org.apache.flink.runtime.scheduler.exceptionhistory.TestingAccessExecution;
import org.apache.flink.runtime.scheduler.strategy.SchedulingTopology;
import org.apache.flink.runtime.scheduler.strategy.TestingSchedulingTopology;
import org.apache.flink.runtime.state.CheckpointStorage;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.util.OptionalFailure;
import org.apache.flink.util.SerializedValue;
import org.apache.flink.util.TernaryBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Mocked ExecutionGraph which (partially) tracks the job status, and provides some basic mocks to
 * create an {@link org.apache.flink.runtime.executiongraph.ArchivedExecutionGraph} from this
 * ExecutionGraph.
 */
class StateTrackingMockExecutionGraph implements ExecutionGraph {
    private static final Logger LOG =
            LoggerFactory.getLogger(StateTrackingMockExecutionGraph.class);

    private JobStatus state = JobStatus.INITIALIZING;
    private JobType jobType = JobType.STREAMING;
    private final CompletableFuture<JobStatus> terminationFuture = new CompletableFuture<>();
    private final JobID jobId = new JobID();
    private static final ArchivedExecutionConfig archivedExecutionConfig =
            new ExecutionConfig().archive();
    private final Map<ExecutionAttemptID, TestingAccessExecution> executions = new HashMap<>();

    private void transitionToState(JobStatus targetState) {
        if (!state.isTerminalState()) {
            this.state = targetState;
        } else {
            LOG.warn(
                    "Trying to transition into state {} while being in terminal state {}",
                    targetState,
                    state);
        }
    }

    // ---- methods to control the mock

    void completeTerminationFuture(JobStatus finalStatus) {
        terminationFuture.complete(finalStatus);
        transitionToState(finalStatus);
    }

    // ---- interface implementations

    @Override
    public boolean updateState(TaskExecutionStateTransition state) {
        return true;
    }

    @Override
    public JobStatus getState() {
        return state;
    }

    @Override
    public JobType getJobType() {
        return jobType;
    }

    @Override
    public CompletableFuture<JobStatus> getTerminationFuture() {
        return terminationFuture;
    }

    @Override
    public void cancel() {
        transitionToState(JobStatus.CANCELLING);
    }

    @Override
    public void failJob(Throwable cause, long timestamp) {
        transitionToState(JobStatus.FAILING);
    }

    @Override
    public void suspend(Throwable suspensionCause) {
        transitionToState(JobStatus.SUSPENDED);
    }

    @Override
    public void transitionToRunning() {
        transitionToState(JobStatus.RUNNING);
    }

    // --- interface implementations: methods for creating an archived execution graph

    @Override
    public Iterable<ExecutionJobVertex> getVerticesTopologically() {
        return Collections.emptyList();
    }

    @Override
    public Map<JobVertexID, ExecutionJobVertex> getAllVertices() {
        return StreamSupport.stream(getVerticesTopologically().spliterator(), false)
                .collect(Collectors.toMap(ExecutionJobVertex::getJobVertexId, v -> v));
    }

    @Override
    public Map<String, SerializedValue<OptionalFailure<Object>>> getAccumulatorsSerialized() {
        return Collections.emptyMap();
    }

    @Override
    public long getStatusTimestamp(JobStatus status) {
        return 0L;
    }

    @Override
    public JobPlanInfo.Plan getPlan() {
        return new JobPlanInfo.Plan("", "", "", new ArrayList<>());
    }

    @Override
    public String getStreamGraphJson() {
        return null;
    }

    @Override
    public void setPlan(JobPlanInfo.Plan jsonPlan) {}

    @Override
    public JobID getJobID() {
        return jobId;
    }

    @Override
    public String getJobName() {
        return "testJob";
    }

    @Nullable
    @Override
    public ErrorInfo getFailureInfo() {
        return null;
    }

    @Nullable
    @Override
    public ArchivedExecutionConfig getArchivedExecutionConfig() {
        return archivedExecutionConfig;
    }

    @Override
    public boolean isStoppable() {
        return false;
    }

    @Nullable
    @Override
    public CheckpointCoordinatorConfiguration getCheckpointCoordinatorConfiguration() {
        return null;
    }

    @Nullable
    @Override
    public CheckpointStatsSnapshot getCheckpointStatsSnapshot() {
        return null;
    }

    @Override
    public Optional<String> getStateBackendName() {
        return Optional.empty();
    }

    @Override
    public Optional<String> getCheckpointStorageName() {
        return Optional.empty();
    }

    @Override
    public TernaryBoolean isChangelogStateBackendEnabled() {
        return TernaryBoolean.fromBoolean(false);
    }

    @Override
    public Optional<String> getChangelogStorageName() {
        return Optional.empty();
    }

    @Override
    public int getPendingOperatorCount() {
        return 0;
    }

    @Override
    public StringifiedAccumulatorResult[] getAccumulatorResultsStringified() {
        return new StringifiedAccumulatorResult[0];
    }

    @Override
    public Iterable<ExecutionVertex> getAllExecutionVertices() {
        return Collections.emptyList();
    }

    @Override
    public Map<ExecutionAttemptID, Execution> getRegisteredExecutions() {
        return Collections.emptyMap();
    }

    @Override
    public void start(@Nonnull ComponentMainThreadExecutor jobMasterMainThreadExecutor) {}

    @Override
    public void setInternalTaskFailuresListener(
            InternalFailuresListener internalTaskFailuresListener) {}

    // -- remaining interface implementations: all unsupported

    @Override
    public SchedulingTopology getSchedulingTopology() {
        return new TestingSchedulingTopology();
    }

    @Override
    public void enableCheckpointing(
            CheckpointCoordinatorConfiguration chkConfig,
            List<MasterTriggerRestoreHook<?>> masterHooks,
            CheckpointIDCounter checkpointIDCounter,
            CompletedCheckpointStore checkpointStore,
            StateBackend checkpointStateBackend,
            CheckpointStorage checkpointStorage,
            CheckpointStatsTracker statsTracker,
            CheckpointsCleaner checkpointsCleaner,
            String changelogStorage) {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public CheckpointCoordinator getCheckpointCoordinator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public KvStateLocationRegistry getKvStateLocationRegistry() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Configuration getJobConfiguration() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Throwable getFailureCause() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExecutionJobVertex getJobVertex(JobVertexID id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getNumberOfRestarts() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<IntermediateDataSetID, IntermediateResult> getAllIntermediateResults() {
        throw new UnsupportedOperationException();
    }

    @Override
    public IntermediateResultPartition getResultPartitionOrThrow(IntermediateResultPartitionID id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, OptionalFailure<Accumulator<?, ?>>> aggregateUserAccumulators() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void attachJobGraph(
            List<JobVertex> topologicallySorted, JobManagerJobMetricGroup jobManagerJobMetricGroup)
            throws JobException {
        throw new UnsupportedOperationException();
    }

    @Override
    public JobStatus waitUntilTerminal() throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean transitionState(JobStatus current, JobStatus newState) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void incrementRestarts() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void initFailureCause(Throwable t, long timestamp) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAccumulators(AccumulatorSnapshot accumulatorSnapshot) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerJobStatusListener(JobStatusListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResultPartitionAvailabilityChecker getResultPartitionAvailabilityChecker() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getNumFinishedVertices() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    public ComponentMainThreadExecutor getJobMasterMainThreadExecutor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void initializeJobVertex(
            ExecutionJobVertex ejv,
            long createTimestamp,
            Map<IntermediateDataSetID, JobVertexInputInfo> jobVertexInputInfos)
            throws JobException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void notifyNewlyInitializedJobVertices(List<ExecutionJobVertex> vertices) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addNewJobVertices(
            List<JobVertex> sortedJobVertices,
            JobManagerJobMetricGroup jobManagerJobMetricGroup,
            VertexParallelismStore newVerticesParallelismStore)
            throws JobException {
        throw new UnsupportedOperationException();
    }

    public void registerExecution(TestingAccessExecution execution) {
        executions.put(execution.getAttemptId(), execution);
    }

    @Override
    public Optional<String> findVertexWithAttempt(ExecutionAttemptID attemptId) {
        return Optional.of("dummy");
    }

    @Override
    public Optional<AccessExecution> findExecution(ExecutionAttemptID attemptId) {
        return Optional.ofNullable(executions.get(attemptId));
    }
}
