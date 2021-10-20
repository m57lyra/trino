/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.operator;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.stats.CounterStat;
import io.airlift.stats.Distribution;
import io.airlift.units.Duration;
import io.trino.Session;
import io.trino.execution.Lifespan;
import io.trino.execution.TaskId;
import io.trino.memory.QueryContextVisitor;
import io.trino.memory.context.LocalMemoryContext;
import io.trino.memory.context.MemoryTrackingContext;
import org.joda.time.DateTime;

import javax.annotation.concurrent.ThreadSafe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.airlift.units.DataSize.succinctBytes;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toList;

@ThreadSafe
public class PipelineContext
{
    private final TaskContext taskContext;
    private final Executor notificationExecutor;
    private final ScheduledExecutorService yieldExecutor;
    private final int pipelineId;

    private final boolean inputPipeline;
    private final boolean outputPipeline;
    private final boolean partitioned;

    private final List<DriverContext> drivers = new CopyOnWriteArrayList<>();

    private final AtomicInteger totalSplits = new AtomicInteger();
    private final AtomicLong totalSplitsWeight = new AtomicLong();
    private final AtomicInteger completedDrivers = new AtomicInteger();
    private final AtomicLong completedSplitsWeight = new AtomicLong();

    private final AtomicReference<DateTime> executionStartTime = new AtomicReference<>();
    private final AtomicReference<DateTime> lastExecutionStartTime = new AtomicReference<>();
    private final AtomicReference<DateTime> lastExecutionEndTime = new AtomicReference<>();

    private final Distribution queuedTime = new Distribution();
    private final Distribution elapsedTime = new Distribution();

    private final AtomicLong totalScheduledTime = new AtomicLong();
    private final AtomicLong totalCpuTime = new AtomicLong();
    private final AtomicLong totalBlockedTime = new AtomicLong();

    private final CounterStat physicalInputDataSize = new CounterStat();
    private final CounterStat physicalInputPositions = new CounterStat();
    private final AtomicLong physicalInputReadTime = new AtomicLong();

    private final CounterStat internalNetworkInputDataSize = new CounterStat();
    private final CounterStat internalNetworkInputPositions = new CounterStat();

    private final CounterStat rawInputDataSize = new CounterStat();
    private final CounterStat rawInputPositions = new CounterStat();

    private final CounterStat processedInputDataSize = new CounterStat();
    private final CounterStat processedInputPositions = new CounterStat();

    private final CounterStat outputDataSize = new CounterStat();
    private final CounterStat outputPositions = new CounterStat();

    private final AtomicLong physicalWrittenDataSize = new AtomicLong();

    private final ConcurrentMap<Integer, OperatorStats> operatorSummaries = new ConcurrentHashMap<>();

    private final MemoryTrackingContext pipelineMemoryContext;

    public PipelineContext(int pipelineId, TaskContext taskContext, Executor notificationExecutor, ScheduledExecutorService yieldExecutor, MemoryTrackingContext pipelineMemoryContext, boolean inputPipeline, boolean outputPipeline, boolean partitioned)
    {
        this.pipelineId = pipelineId;
        this.inputPipeline = inputPipeline;
        this.outputPipeline = outputPipeline;
        this.partitioned = partitioned;
        this.taskContext = requireNonNull(taskContext, "taskContext is null");
        this.notificationExecutor = requireNonNull(notificationExecutor, "notificationExecutor is null");
        this.yieldExecutor = requireNonNull(yieldExecutor, "yieldExecutor is null");
        this.pipelineMemoryContext = requireNonNull(pipelineMemoryContext, "pipelineMemoryContext is null");
        // Initialize the local memory contexts with the ExchangeOperator tag as ExchangeOperator will do the local memory allocations
        pipelineMemoryContext.initializeLocalMemoryContexts(ExchangeOperator.class.getSimpleName());
    }

    public TaskContext getTaskContext()
    {
        return taskContext;
    }

    public TaskId getTaskId()
    {
        return taskContext.getTaskId();
    }

    public int getPipelineId()
    {
        return pipelineId;
    }

    public boolean isInputPipeline()
    {
        return inputPipeline;
    }

    public boolean isOutputPipeline()
    {
        return outputPipeline;
    }

    public DriverContext addDriverContext()
    {
        return addDriverContext(Lifespan.taskWide(), 0);
    }

    public DriverContext addDriverContext(Lifespan lifespan, long splitWeight)
    {
        checkArgument(partitioned || splitWeight == 0, "Only partitioned splits should have weights");
        DriverContext driverContext = new DriverContext(
                this,
                notificationExecutor,
                yieldExecutor,
                pipelineMemoryContext.newMemoryTrackingContext(),
                lifespan,
                splitWeight);
        drivers.add(driverContext);
        return driverContext;
    }

    public Session getSession()
    {
        return taskContext.getSession();
    }

    public void splitsAdded(int count, long weightSum)
    {
        checkArgument(count >= 0 && weightSum >= 0);
        totalSplits.addAndGet(count);
        if (partitioned && weightSum != 0) {
            totalSplitsWeight.addAndGet(weightSum);
        }
    }

    public void driverFinished(DriverContext driverContext)
    {
        requireNonNull(driverContext, "driverContext is null");

        if (!drivers.remove(driverContext)) {
            throw new IllegalArgumentException("Unknown driver " + driverContext);
        }

        // always update last execution end time
        lastExecutionEndTime.set(DateTime.now());

        DriverStats driverStats = driverContext.getDriverStats();

        completedDrivers.getAndIncrement();
        if (partitioned) {
            completedSplitsWeight.addAndGet(driverContext.getSplitWeight());
        }

        queuedTime.add(driverStats.getQueuedTime().roundTo(NANOSECONDS));
        elapsedTime.add(driverStats.getElapsedTime().roundTo(NANOSECONDS));

        totalScheduledTime.getAndAdd(driverStats.getTotalScheduledTime().roundTo(NANOSECONDS));
        totalCpuTime.getAndAdd(driverStats.getTotalCpuTime().roundTo(NANOSECONDS));

        totalBlockedTime.getAndAdd(driverStats.getTotalBlockedTime().roundTo(NANOSECONDS));

        // merge the operator stats into the operator summary
        List<OperatorStats> operators = driverStats.getOperatorStats();
        for (OperatorStats operator : operators) {
            operatorSummaries.merge(operator.getOperatorId(), operator, OperatorStats::add);
        }

        physicalInputDataSize.update(driverStats.getPhysicalInputDataSize().toBytes());
        physicalInputPositions.update(driverStats.getPhysicalInputPositions());
        physicalInputReadTime.getAndAdd(driverStats.getPhysicalInputReadTime().roundTo(NANOSECONDS));

        internalNetworkInputDataSize.update(driverStats.getInternalNetworkInputDataSize().toBytes());
        internalNetworkInputPositions.update(driverStats.getInternalNetworkInputPositions());

        rawInputDataSize.update(driverStats.getRawInputDataSize().toBytes());
        rawInputPositions.update(driverStats.getRawInputPositions());

        processedInputDataSize.update(driverStats.getProcessedInputDataSize().toBytes());
        processedInputPositions.update(driverStats.getProcessedInputPositions());

        outputDataSize.update(driverStats.getOutputDataSize().toBytes());
        outputPositions.update(driverStats.getOutputPositions());

        physicalWrittenDataSize.getAndAdd(driverStats.getPhysicalWrittenDataSize().toBytes());
    }

    public void start()
    {
        DateTime now = DateTime.now();
        executionStartTime.compareAndSet(null, now);
        // always update last execution start time
        lastExecutionStartTime.set(now);

        taskContext.start();
    }

    public void failed(Throwable cause)
    {
        taskContext.failed(cause);
    }

    public boolean isDone()
    {
        return taskContext.isDone();
    }

    public synchronized ListenableFuture<Void> reserveSpill(long bytes)
    {
        return taskContext.reserveSpill(bytes);
    }

    public synchronized void freeSpill(long bytes)
    {
        checkArgument(bytes >= 0, "bytes is negative");
        taskContext.freeSpill(bytes);
    }

    public LocalMemoryContext localSystemMemoryContext()
    {
        return pipelineMemoryContext.localSystemMemoryContext();
    }

    public void moreMemoryAvailable()
    {
        drivers.forEach(DriverContext::moreMemoryAvailable);
    }

    public boolean isPerOperatorCpuTimerEnabled()
    {
        return taskContext.isPerOperatorCpuTimerEnabled();
    }

    public boolean isCpuTimerEnabled()
    {
        return taskContext.isCpuTimerEnabled();
    }

    public CounterStat getProcessedInputDataSize()
    {
        CounterStat stat = new CounterStat();
        stat.merge(processedInputDataSize);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getInputDataSize());
        }
        return stat;
    }

    public CounterStat getInputPositions()
    {
        CounterStat stat = new CounterStat();
        stat.merge(processedInputPositions);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getInputPositions());
        }
        return stat;
    }

    public CounterStat getOutputDataSize()
    {
        CounterStat stat = new CounterStat();
        stat.merge(outputDataSize);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getOutputDataSize());
        }
        return stat;
    }

    public CounterStat getOutputPositions()
    {
        CounterStat stat = new CounterStat();
        stat.merge(outputPositions);
        for (DriverContext driver : drivers) {
            stat.merge(driver.getOutputPositions());
        }
        return stat;
    }

    public long getPhysicalWrittenDataSize()
    {
        return drivers.stream()
                .mapToLong(DriverContext::getPhysicalWrittenDataSize)
                .sum();
    }

    public PipelineStatus getPipelineStatus()
    {
        return getPipelineStatus(drivers.iterator(), totalSplits.get(), completedDrivers.get(), getActivePartitionedSplitsWeight(), partitioned);
    }

    private long getActivePartitionedSplitsWeight()
    {
        if (partitioned) {
            return totalSplitsWeight.get() - completedSplitsWeight.get();
        }
        return 0;
    }

    public PipelineStats getPipelineStats()
    {
        // check for end state to avoid callback ordering problems
        if (taskContext.getState().isDone()) {
            DateTime now = DateTime.now();
            executionStartTime.compareAndSet(null, now);
            lastExecutionStartTime.compareAndSet(null, now);
            lastExecutionEndTime.compareAndSet(null, now);
        }

        int completedDrivers = this.completedDrivers.get();
        List<DriverContext> driverContexts = ImmutableList.copyOf(this.drivers);
        int totalSplits = this.totalSplits.get();
        PipelineStatus pipelineStatus = getPipelineStatus(driverContexts.iterator(), totalSplits, completedDrivers, getActivePartitionedSplitsWeight(), partitioned);

        int totalDrivers = completedDrivers + driverContexts.size();

        Distribution queuedTime = this.queuedTime.duplicate();
        Distribution elapsedTime = this.elapsedTime.duplicate();

        long totalScheduledTime = this.totalScheduledTime.get();
        long totalCpuTime = this.totalCpuTime.get();
        long totalBlockedTime = this.totalBlockedTime.get();

        long physicalInputDataSize = this.physicalInputDataSize.getTotalCount();
        long physicalInputPositions = this.physicalInputPositions.getTotalCount();

        long internalNetworkInputDataSize = this.internalNetworkInputDataSize.getTotalCount();
        long internalNetworkInputPositions = this.internalNetworkInputPositions.getTotalCount();

        long rawInputDataSize = this.rawInputDataSize.getTotalCount();
        long rawInputPositions = this.rawInputPositions.getTotalCount();

        long processedInputDataSize = this.processedInputDataSize.getTotalCount();
        long processedInputPositions = this.processedInputPositions.getTotalCount();
        long physicalInputReadTime = this.physicalInputReadTime.get();

        long outputDataSize = this.outputDataSize.getTotalCount();
        long outputPositions = this.outputPositions.getTotalCount();

        long physicalWrittenDataSize = this.physicalWrittenDataSize.get();

        List<DriverStats> drivers = new ArrayList<>();

        TreeMap<Integer, OperatorStats> operatorSummaries = new TreeMap<>(this.operatorSummaries);
        ListMultimap<Integer, OperatorStats> runningOperators = ArrayListMultimap.create();
        for (DriverContext driverContext : driverContexts) {
            DriverStats driverStats = driverContext.getDriverStats();
            drivers.add(driverStats);

            queuedTime.add(driverStats.getQueuedTime().roundTo(NANOSECONDS));
            elapsedTime.add(driverStats.getElapsedTime().roundTo(NANOSECONDS));

            totalScheduledTime += driverStats.getTotalScheduledTime().roundTo(NANOSECONDS);
            totalCpuTime += driverStats.getTotalCpuTime().roundTo(NANOSECONDS);
            totalBlockedTime += driverStats.getTotalBlockedTime().roundTo(NANOSECONDS);

            List<OperatorStats> operators = driverContext.getOperatorStats();
            for (OperatorStats operator : operators) {
                runningOperators.put(operator.getOperatorId(), operator);
            }

            physicalInputDataSize += driverStats.getPhysicalInputDataSize().toBytes();
            physicalInputPositions += driverStats.getPhysicalInputPositions();
            physicalInputReadTime += driverStats.getPhysicalInputReadTime().roundTo(NANOSECONDS);

            internalNetworkInputDataSize += driverStats.getInternalNetworkInputDataSize().toBytes();
            internalNetworkInputPositions += driverStats.getInternalNetworkInputPositions();

            rawInputDataSize += driverStats.getRawInputDataSize().toBytes();
            rawInputPositions += driverStats.getRawInputPositions();

            processedInputDataSize += driverStats.getProcessedInputDataSize().toBytes();
            processedInputPositions += driverStats.getProcessedInputPositions();

            outputDataSize += driverStats.getOutputDataSize().toBytes();
            outputPositions += driverStats.getOutputPositions();

            physicalWrittenDataSize += driverStats.getPhysicalWrittenDataSize().toBytes();
        }

        // merge the running operator stats into the operator summary
        for (Integer operatorId : runningOperators.keySet()) {
            List<OperatorStats> runningStats = runningOperators.get(operatorId);
            if (runningStats.isEmpty()) {
                continue;
            }
            OperatorStats current = operatorSummaries.get(operatorId);
            OperatorStats combined;
            if (current != null) {
                combined = current.add(runningStats);
            }
            else {
                combined = runningStats.get(0);
                if (runningStats.size() > 1) {
                    combined = combined.add(runningStats.subList(1, runningStats.size()));
                }
            }
            operatorSummaries.put(operatorId, combined);
        }

        Set<DriverStats> runningDriverStats = drivers.stream()
                .filter(driver -> driver.getEndTime() == null && driver.getStartTime() != null)
                .collect(toImmutableSet());
        ImmutableSet<BlockedReason> blockedReasons = runningDriverStats.stream()
                .flatMap(driver -> driver.getBlockedReasons().stream())
                .collect(toImmutableSet());

        boolean fullyBlocked = !runningDriverStats.isEmpty() && runningDriverStats.stream().allMatch(DriverStats::isFullyBlocked);

        return new PipelineStats(
                pipelineId,

                executionStartTime.get(),
                lastExecutionStartTime.get(),
                lastExecutionEndTime.get(),

                inputPipeline,
                outputPipeline,

                totalDrivers,
                pipelineStatus.getQueuedDrivers(),
                pipelineStatus.getQueuedPartitionedDrivers(),
                pipelineStatus.getQueuedPartitionedSplitsWeight(),
                pipelineStatus.getRunningDrivers(),
                pipelineStatus.getRunningPartitionedDrivers(),
                pipelineStatus.getRunningPartitionedSplitsWeight(),
                pipelineStatus.getBlockedDrivers(),
                completedDrivers,

                succinctBytes(pipelineMemoryContext.getUserMemory()),
                succinctBytes(pipelineMemoryContext.getRevocableMemory()),
                succinctBytes(pipelineMemoryContext.getSystemMemory()),

                queuedTime.snapshot(),
                elapsedTime.snapshot(),

                new Duration(totalScheduledTime, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                new Duration(totalCpuTime, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                new Duration(totalBlockedTime, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                fullyBlocked,
                blockedReasons,

                succinctBytes(physicalInputDataSize),
                physicalInputPositions,
                new Duration(physicalInputReadTime, NANOSECONDS).convertToMostSuccinctTimeUnit(),

                succinctBytes(internalNetworkInputDataSize),
                internalNetworkInputPositions,

                succinctBytes(rawInputDataSize),
                rawInputPositions,

                succinctBytes(processedInputDataSize),
                processedInputPositions,

                succinctBytes(outputDataSize),
                outputPositions,

                succinctBytes(physicalWrittenDataSize),

                ImmutableList.copyOf(operatorSummaries.values()),
                drivers);
    }

    public <C, R> R accept(QueryContextVisitor<C, R> visitor, C context)
    {
        return visitor.visitPipelineContext(this, context);
    }

    public <C, R> List<R> acceptChildren(QueryContextVisitor<C, R> visitor, C context)
    {
        return drivers.stream()
                .map(driver -> driver.accept(visitor, context))
                .collect(toList());
    }

    @VisibleForTesting
    public MemoryTrackingContext getPipelineMemoryContext()
    {
        return pipelineMemoryContext;
    }

    private static PipelineStatus getPipelineStatus(Iterator<DriverContext> driverContextsIterator, int totalSplits, int completedDrivers, long activePartitionedSplitsWeight, boolean partitioned)
    {
        int runningDrivers = 0;
        int blockedDrivers = 0;
        long runningPartitionedSplitsWeight = 0L;
        long blockedPartitionedSplitsWeight = 0L;
        // When a split for a partitioned pipeline is delivered to a worker,
        // conceptually, the worker would have an additional driver.
        // The queuedDrivers field in PipelineStatus is supposed to represent this.
        // However, due to implementation details of SqlTaskExecution, it may defer instantiation of drivers.
        //
        // physically queued drivers: actual number of instantiated drivers whose execution hasn't started
        // conceptually queued drivers: includes assigned splits that haven't been turned into a driver
        int physicallyQueuedDrivers = 0;
        while (driverContextsIterator.hasNext()) {
            DriverContext driverContext = driverContextsIterator.next();
            if (!driverContext.isExecutionStarted()) {
                physicallyQueuedDrivers++;
            }
            else if (driverContext.isFullyBlocked()) {
                blockedDrivers++;
                if (partitioned) {
                    blockedPartitionedSplitsWeight += driverContext.getSplitWeight();
                }
            }
            else {
                runningDrivers++;
                if (partitioned) {
                    runningPartitionedSplitsWeight += driverContext.getSplitWeight();
                }
            }
        }

        int queuedDrivers;
        int queuedPartitionedSplits;
        int runningPartitionedSplits;
        long queuedPartitionedSplitsWeight;
        if (partitioned) {
            queuedDrivers = totalSplits - runningDrivers - blockedDrivers - completedDrivers;
            if (queuedDrivers < 0) {
                // It is possible to observe negative here because inputs to the above expression was not taken in a snapshot.
                queuedDrivers = 0;
            }
            queuedPartitionedSplitsWeight = activePartitionedSplitsWeight - runningPartitionedSplitsWeight - blockedPartitionedSplitsWeight;
            if (queuedDrivers == 0 || queuedPartitionedSplitsWeight < 0) {
                // negative or inconsistent count vs weight inputs might occur
                queuedPartitionedSplitsWeight = 0;
            }
            queuedPartitionedSplits = queuedDrivers;
            runningPartitionedSplits = runningDrivers;
        }
        else {
            queuedDrivers = physicallyQueuedDrivers;
            queuedPartitionedSplits = 0;
            queuedPartitionedSplitsWeight = 0;
            runningPartitionedSplits = 0;
            runningPartitionedSplitsWeight = 0;
        }

        return new PipelineStatus(queuedDrivers, runningDrivers, blockedDrivers, queuedPartitionedSplits, queuedPartitionedSplitsWeight, runningPartitionedSplits, runningPartitionedSplitsWeight);
    }
}
