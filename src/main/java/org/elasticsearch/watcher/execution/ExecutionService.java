/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.execution;

import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.watcher.WatcherException;
import org.elasticsearch.watcher.actions.ActionWrapper;
import org.elasticsearch.watcher.condition.Condition;
import org.elasticsearch.watcher.history.HistoryStore;
import org.elasticsearch.watcher.history.WatchRecord;
import org.elasticsearch.watcher.input.Input;
import org.elasticsearch.watcher.support.Callback;
import org.elasticsearch.watcher.support.clock.Clock;
import org.elasticsearch.watcher.throttle.Throttler;
import org.elasticsearch.watcher.transform.Transform;
import org.elasticsearch.watcher.trigger.TriggerEngine;
import org.elasticsearch.watcher.trigger.TriggerEvent;
import org.elasticsearch.watcher.trigger.TriggerService;
import org.elasticsearch.watcher.watch.Watch;
import org.elasticsearch.watcher.watch.WatchExecution;
import org.elasticsearch.watcher.watch.WatchLockService;
import org.elasticsearch.watcher.watch.WatchStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 */
public class ExecutionService extends AbstractComponent {

    private final HistoryStore historyStore;
    private final WatchExecutor executor;
    private final WatchStore watchStore;
    private final ClusterService clusterService;
    private final WatchLockService watchLockService;
    private final Clock clock;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger initializationRetries = new AtomicInteger();

    @Inject
    public ExecutionService(Settings settings, HistoryStore historyStore, WatchExecutor executor,
                            WatchStore watchStore, WatchLockService watchLockService, TriggerService triggerService,
                            ClusterService clusterService, Clock clock) {
        super(settings);
        this.historyStore = historyStore;
        this.executor = executor;
        this.watchStore = watchStore;
        this.watchLockService = watchLockService;
        this.clusterService = clusterService;
        triggerService.register(new SchedulerListener());
        this.clock = clock;
    }

    public void start(ClusterState state, Callback<ClusterState> callback) {
        if (started.get()) {
            callback.onSuccess(state);
            return;
        }

        assert executor.queue().isEmpty() : "queue should be empty, but contains " + executor.queue().size() + " elements.";
        Collection<WatchRecord> records = historyStore.loadRecords(state, WatchRecord.State.AWAITS_EXECUTION);
        if (records == null) {
            retry(callback);
            return;
        }
        if (started.compareAndSet(false, true)) {
            logger.debug("starting execution service");
            historyStore.start();
            executeRecords(records);
            logger.debug("started execution service");
        }
        callback.onSuccess(state);
    }

    public void stop() {
        if (started.compareAndSet(true, false)) {
            logger.debug("stopping execution service");
            // We could also rely on the shutdown in #updateSettings call, but
            // this is a forceful shutdown that also interrupts the worker threads in the threadpool
            List<Runnable> cancelledTasks = new ArrayList<>();
            executor.queue().drainTo(cancelledTasks);
            historyStore.stop();
            logger.debug("cancelled [{}] queued tasks", cancelledTasks.size());
            logger.debug("stopped execution service");
        }
    }

    public boolean started() {
        return started.get();
    }

    public long queueSize() {
        return executor.queue().size();
    }

    public long largestQueueSize() {
        return executor.largestPoolSize();
    }

    public WatchRecord execute(WatchExecutionContext ctx) throws IOException {
        WatchRecord watchRecord = new WatchRecord(ctx.id(), ctx.watch(), ctx.triggerEvent());
        WatchLockService.Lock lock = watchLockService.acquire(ctx.watch().name());
        try {
            WatchExecution execution = executeInner(ctx);
            watchRecord.seal(execution);
        } finally {
            lock.release();
        }
        if (ctx.recordInHistory()) {
            historyStore.put(watchRecord);
        }
        return watchRecord;
    }

    /*
       The execution of an watch is split into two phases:
       1. the trigger part which just makes sure to store the associated watch record in the history
       2. the actual processing of the watch

       The reason this split is that we don't want to lose the fact watch was triggered. This way, even if the
       thread pool that executes the watches is completely busy, we don't lose the fact that the watch was
       triggered (it'll have its history record)
    */

    private void executeAsync(WatchExecutionContext ctx, WatchRecord watchRecord) {
        try {
            executor.execute(new WatchExecutionTask(ctx, watchRecord));
        } catch (EsRejectedExecutionException e) {
            logger.debug("failed to execute triggered watch [{}]", watchRecord.name());
            watchRecord.update(WatchRecord.State.FAILED, "failed to run triggered watch [" + watchRecord.name() + "] due to thread pool capacity");
            historyStore.update(watchRecord);
        }
    }


    private void executeWatch(Watch watch, TriggerEvent event) throws WatcherException {
        if (!started.get()) {
            throw new ElasticsearchIllegalStateException("not started");
        }
        TriggeredExecutionContext ctx = new TriggeredExecutionContext(watch, clock.now(), event);
        WatchRecord watchRecord = new WatchRecord(ctx.id(), watch, event);
        if (ctx.recordInHistory()) {
            logger.debug("saving watch record [{}] for watch [{}]", watchRecord.id(), watch.name());
            historyStore.put(watchRecord);
        }
        executeAsync(ctx, watchRecord);
    }


    WatchExecution executeInner(WatchExecutionContext ctx) throws IOException {
        Watch watch = ctx.watch();

        Input.Result inputResult = ctx.inputResult();
        if (inputResult == null) {
            inputResult = watch.input().execute(ctx);
            ctx.onInputResult(inputResult);
        }
        Condition.Result conditionResult = ctx.conditionResult();
        if (conditionResult == null) {
            conditionResult = watch.condition().execute(ctx);
            ctx.onConditionResult(conditionResult);
        }

        if (conditionResult.met()) {

            Throttler.Result throttleResult = ctx.throttleResult();
            if (throttleResult == null) {
                throttleResult = watch.throttler().throttle(ctx);
                ctx.onThrottleResult(throttleResult);
            }

            if (!throttleResult.throttle()) {
                Transform transform = watch.transform();
                if (transform != null) {
                    Transform.Result result = watch.transform().apply(ctx, inputResult.payload());
                    ctx.onTransformResult(result);
                }
                for (ActionWrapper action : watch.actions()) {
                    ActionWrapper.Result actionResult = action.execute(ctx);
                    ctx.onActionResult(actionResult);
                }
            }
        }
        return ctx.finish();

    }

    void executeRecords(Collection<WatchRecord> records) {
        assert records != null;
        int counter = 0;
        for (WatchRecord record : records) {
            Watch watch = watchStore.get(record.name());
            if (watch == null) {
                logger.warn("unable to find watch [{}] in watch store. perhaps it has been deleted. skipping...", record.name());
                continue;
            }
            TriggeredExecutionContext ctx = new TriggeredExecutionContext(watch, clock.now(), record.triggerEvent());
            executeAsync(ctx, record);
            counter++;
        }
        logger.debug("executed [{}] watches from the watch history", counter);
    }

    private void retry(final Callback<ClusterState> callback) {
        ClusterStateListener clusterStateListener = new ClusterStateListener() {

            @Override
            public void clusterChanged(final ClusterChangedEvent event) {
                // Remove listener, so that it doesn't get called on the next cluster state update:
                assert initializationRetries.decrementAndGet() == 0 : "Only one retry can run at the time";
                clusterService.remove(this);
                // We fork into another thread, because start(...) is expensive and we can't call this from the cluster update thread.
                executor.execute(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            start(event.state(), callback);
                        } catch (Exception e) {
                            callback.onFailure(e);
                        }
                    }
                });
            }
        };
        assert initializationRetries.incrementAndGet() == 1 : "Only one retry can run at the time";
        clusterService.add(clusterStateListener);
    }

    private final class WatchExecutionTask implements Runnable {

        private final WatchRecord watchRecord;

        private final WatchExecutionContext ctx;

        private WatchExecutionTask(WatchExecutionContext ctx, WatchRecord watchRecord) {
            this.watchRecord = watchRecord;
            this.ctx = ctx;
        }

        @Override
        public void run() {
            if (!started.get()) {
                logger.debug("can't initiate watch execution as execution service is not started, ignoring it...");
                return;
            }
            logger.trace("executing [{}] [{}]", ctx.watch().name(), ctx.id());
            WatchLockService.Lock lock = watchLockService.acquire(ctx.watch().name());
            try {
                watchRecord.update(WatchRecord.State.CHECKING, null);
                logger.debug("checking watch [{}]", watchRecord.name());
                WatchExecution execution = executeInner(ctx);
                watchRecord.seal(execution);
                if (ctx.recordInHistory()) {
                    historyStore.update(watchRecord);
                }
            } catch (Exception e) {
                if (started()) {
                    logger.warn("failed to execute watch [{}] [{}]", e, watchRecord.name(), ctx.id());
                    try {
                        watchRecord.update(WatchRecord.State.FAILED, e.getMessage());
                        if (ctx.recordInHistory()) {
                            historyStore.update(watchRecord);
                        }
                    } catch (Exception e2) {
                        logger.error("failed to update watch record [{}] failure [{}] for [{}] [{}]", e2, watchRecord, ctx.watch().name(), ctx.id(), e.getMessage());
                    }
                } else {
                    logger.debug("failed to execute watch [{}] after shutdown", e, watchRecord);
                }
            } finally {
                lock.release();
                logger.trace("finished [{}] [{}]", ctx.watch().name(), ctx.id());
            }
        }

    }

    private class SchedulerListener implements TriggerEngine.Listener {

        @Override
        public void triggered(String name, TriggerEvent event) {
            if (!started.get()) {
                throw new ElasticsearchIllegalStateException("not started");
            }
            Watch watch = watchStore.get(name);
            if (watch == null) {
                logger.warn("unable to find watch [{}] in the watch store, perhaps it has been deleted", name);
                return;
            }
            try {
                ExecutionService.this.executeWatch(watch, event);
            } catch (Exception e) {
                if (started()) {
                    logger.error("failed to execute watch from SchedulerListener [{}]", e, name);
                } else {
                    logger.debug("failed to execute watch from SchedulerListener [{}] after shutdown", e, name);
                }
            }
        }
    }
}
