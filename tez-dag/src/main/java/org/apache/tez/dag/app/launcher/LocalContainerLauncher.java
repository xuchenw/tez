/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.dag.app.launcher;


import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.util.AuxiliaryServiceHelper;
import org.apache.tez.common.TezCommonUtils;
import org.apache.tez.common.TezTaskUmbilicalProtocol;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.app.AppContext;
import org.apache.tez.dag.app.TaskAttemptListener;
import org.apache.tez.dag.app.TaskAttemptListenerImpTezDag;
import org.apache.tez.dag.app.TezTaskCommunicatorImpl;
import org.apache.tez.dag.app.rm.NMCommunicatorEvent;
import org.apache.tez.dag.app.rm.NMCommunicatorLaunchRequestEvent;
import org.apache.tez.dag.app.rm.NMCommunicatorStopRequestEvent;
import org.apache.tez.dag.app.rm.container.AMContainerEvent;
import org.apache.tez.dag.app.rm.container.AMContainerEventCompleted;
import org.apache.tez.dag.app.rm.container.AMContainerEventLaunchFailed;
import org.apache.tez.dag.app.rm.container.AMContainerEventLaunched;
import org.apache.tez.dag.app.rm.container.AMContainerEventType;
import org.apache.tez.dag.history.DAGHistoryEvent;
import org.apache.tez.dag.history.events.ContainerLaunchedEvent;
import org.apache.tez.dag.records.TaskAttemptTerminationCause;
import org.apache.tez.runtime.api.ExecutionContext;
import org.apache.tez.runtime.api.impl.ExecutionContextImpl;
import org.apache.tez.runtime.library.common.shuffle.ShuffleUtils;
import org.apache.tez.runtime.task.TezChild;


/**
 * Runs the container task locally in a thread.
 * Since all (sub)tasks share the same local directory, they must be executed
 * sequentially in order to avoid creating/deleting the same files/dirs.
 */
public class LocalContainerLauncher extends AbstractService implements
  ContainerLauncher {

  private static final Log LOG = LogFactory.getLog(LocalContainerLauncher.class);
  private final AppContext context;
  private final TezTaskUmbilicalProtocol taskUmbilicalProtocol;
  private final AtomicBoolean serviceStopped = new AtomicBoolean(false);
  private final String workingDirectory;
  private final Map<String, String> localEnv = new HashMap<String, String>();
  private final ExecutionContext executionContext;

  private final ConcurrentHashMap<ContainerId, ListenableFuture<TezChild.ContainerExecutionResult>>
      runningContainers =
      new ConcurrentHashMap<ContainerId, ListenableFuture<TezChild.ContainerExecutionResult>>();

  private final ExecutorService callbackExecutor = Executors.newFixedThreadPool(1,
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("CallbackExecutor").build());

  private BlockingQueue<NMCommunicatorEvent> eventQueue =
      new LinkedBlockingQueue<NMCommunicatorEvent>();
  private Thread eventHandlingThread;


  private ListeningExecutorService taskExecutorService;



  public LocalContainerLauncher(AppContext context,
                                TaskAttemptListener taskAttemptListener,
                                String workingDirectory) throws UnknownHostException {
    super(LocalContainerLauncher.class.getName());
    this.context = context;
    TaskAttemptListenerImpTezDag taListener = (TaskAttemptListenerImpTezDag)taskAttemptListener;
    TezTaskCommunicatorImpl taskComm = (TezTaskCommunicatorImpl) taListener.getTaskCommunicator();
    this.taskUmbilicalProtocol = taskComm.getUmbilical();
    this.workingDirectory = workingDirectory;
    AuxiliaryServiceHelper.setServiceDataIntoEnv(
        ShuffleUtils.SHUFFLE_HANDLER_SERVICE_ID, ByteBuffer.allocate(4).putInt(0), localEnv);
    executionContext = new ExecutionContextImpl(InetAddress.getLocalHost().getHostName());
    // User cannot be set here since it isn't available till a DAG is running.
  }

  @Override
  public synchronized void serviceInit(Configuration conf) {
    int numExecutors = conf.getInt(TezConfiguration.TEZ_AM_INLINE_TASK_EXECUTION_MAX_TASKS,
        TezConfiguration.TEZ_AM_INLINE_TASK_EXECUTION_MAX_TASKS_DEFAULT);
    Preconditions.checkState(numExecutors >=1, "Must have at least 1 executor");
    ExecutorService rawExecutor = Executors.newFixedThreadPool(numExecutors,
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("LocalTaskExecutionThread [%d]")
            .build());
    this.taskExecutorService = MoreExecutors.listeningDecorator(rawExecutor);
  }

  @Override
  public void serviceStart() throws Exception {
    eventHandlingThread =
        new Thread(new TezSubTaskRunner(), "LocalContainerLauncher-SubTaskRunner");
    eventHandlingThread.start();
  }

  @Override
  public void serviceStop() throws Exception {
    if (!serviceStopped.compareAndSet(false, true)) {
      LOG.info("Service Already stopped. Ignoring additional stop");
    }
    if (eventHandlingThread != null) {
      eventHandlingThread.interrupt();
      eventHandlingThread.join(2000l);
    }
    if (taskExecutorService != null) {
      taskExecutorService.shutdownNow();
    }
    callbackExecutor.shutdownNow();
  }

  // Thread to monitor the queue of incoming NMCommunicator events
  private class TezSubTaskRunner implements Runnable {
    @Override
    public void run() {
      while (!Thread.currentThread().isInterrupted() && !serviceStopped.get()) {
        NMCommunicatorEvent event;
        try {
          event = eventQueue.take();
          switch (event.getType()) {
            case CONTAINER_LAUNCH_REQUEST:
              launch((NMCommunicatorLaunchRequestEvent) event);
              break;
            case CONTAINER_STOP_REQUEST:
              stop((NMCommunicatorStopRequestEvent)event);
              break;
          }
        } catch (InterruptedException e) {
          if (!serviceStopped.get()) {
            LOG.error("TezSubTaskRunner interrupted ", e);
          }
          return;
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  void sendContainerLaunchFailedMsg(ContainerId containerId, String message) {
    context.getEventHandler().handle(new AMContainerEventLaunchFailed(containerId, message));
  }

  private void handleLaunchFailed(Throwable t, ContainerId containerId) {
    String message;
    if (t instanceof RejectedExecutionException) {
      message = "Failed to queue container launch for container Id: " + containerId;
    } else {
      message = "Failed to launch container for container Id: " + containerId;
    }
    LOG.error(message, t);
    sendContainerLaunchFailedMsg(containerId, message);
  }

  //launch tasks
  private void launch(NMCommunicatorLaunchRequestEvent event) {

    String tokenIdentifier = context.getApplicationID().toString();
    try {
      TezChild tezChild;
      try {
        tezChild =
            createTezChild(context.getAMConf(), event.getContainerId(), tokenIdentifier,
                context.getApplicationAttemptId().getAttemptId(), context.getLocalDirs(),
                taskUmbilicalProtocol,
                TezCommonUtils.parseCredentialsBytes(event.getContainerLaunchContext().getTokens().array()));
      } catch (InterruptedException e) {
        handleLaunchFailed(e, event.getContainerId());
        return;
      } catch (TezException e) {
        handleLaunchFailed(e, event.getContainerId());
        return;
      } catch (IOException e) {
        handleLaunchFailed(e, event.getContainerId());
        return;
      }
      ListenableFuture<TezChild.ContainerExecutionResult> runningTaskFuture =
          taskExecutorService.submit(createSubTask(tezChild, event.getContainerId()));
      runningContainers.put(event.getContainerId(), runningTaskFuture);
      Futures.addCallback(runningTaskFuture,
          new RunningTaskCallback(context, event.getContainerId(), tezChild), callbackExecutor);
    } catch (RejectedExecutionException e) {
      handleLaunchFailed(e, event.getContainerId());
    }
  }

  private void stop(NMCommunicatorStopRequestEvent event) {
    ListenableFuture<TezChild.ContainerExecutionResult> future =
        runningContainers.get(event.getContainerId());
    if (future == null) {
      LOG.info("Ignoring stop request for containerId: " + event.getContainerId());
    } else {
      LOG.info("Interrupting running/queued container with id: " + event.getContainerId());
      future.cancel(true);
      // This will work only if the running task respects Interrupts - which at the moment is
      // not the case for parts of the Runtime.
    }
    // Send this event to maintain regular control flow. This isn't of much use though.
    context.getEventHandler().handle(
        new AMContainerEvent(event.getContainerId(), AMContainerEventType.C_NM_STOP_SENT));
  }

  private class RunningTaskCallback
      implements FutureCallback<TezChild.ContainerExecutionResult> {

    private final AppContext appContext;
    private final ContainerId containerId;
    private final TezChild tezChild;

    RunningTaskCallback(AppContext appContext, ContainerId containerId, TezChild tezChild) {
      this.appContext = appContext;
      this.containerId = containerId;
      this.tezChild = tezChild;
    }

    @Override
    public void onSuccess(TezChild.ContainerExecutionResult result) {
      runningContainers.remove(containerId);
      if (result.getExitStatus() == TezChild.ContainerExecutionResult.ExitStatus.SUCCESS ||
          result.getExitStatus() ==
              TezChild.ContainerExecutionResult.ExitStatus.ASKED_TO_DIE) {
        LOG.info("Container: " + containerId + " completed successfully");
        appContext.getEventHandler().handle(
            new AMContainerEventCompleted(containerId, result.getExitStatus().getExitCode(),
                null, TaskAttemptTerminationCause.CONTAINER_EXITED));
      } else {
        LOG.info("Container: " + containerId + " completed but with errors");
        appContext.getEventHandler().handle(
            new AMContainerEventCompleted(containerId, result.getExitStatus().getExitCode(),
                result.getErrorMessage() == null ?
                    (result.getThrowable() == null ? null : result.getThrowable().getMessage()) :
                    result.getErrorMessage(), TaskAttemptTerminationCause.APPLICATION_ERROR));
      }
    }

    @Override
    public void onFailure(Throwable t) {
      runningContainers.remove(containerId);
      tezChild.shutdown();
      // Ignore CancellationException since that is triggered by the LocalContainerLauncher itself
      if (!(t instanceof CancellationException)) {
        LOG.info("Container: " + containerId + ": Execution Failed: ", t);
        // Inform of failure with exit code 1.
        appContext.getEventHandler()
            .handle(new AMContainerEventCompleted(containerId,
                TezChild.ContainerExecutionResult.ExitStatus.EXECUTION_FAILURE.getExitCode(),
                t.getMessage(), TaskAttemptTerminationCause.APPLICATION_ERROR));
      } else {
        LOG.info("Ignoring CancellationException - triggered by LocalContainerLauncher");
        appContext.getEventHandler()
            .handle(new AMContainerEventCompleted(containerId,
                TezChild.ContainerExecutionResult.ExitStatus.SUCCESS.getExitCode(),
                "CancellationException", TaskAttemptTerminationCause.CONTAINER_EXITED));
      }
    }
  }


  //create a SubTask
  private synchronized Callable<TezChild.ContainerExecutionResult> createSubTask(
      final TezChild tezChild, final ContainerId containerId) {

    return new Callable<TezChild.ContainerExecutionResult>() {
      @Override
      public TezChild.ContainerExecutionResult call() throws InterruptedException, TezException,
          IOException {
        // Inform about the launch request now that the container has been allocated a thread to execute in.
        context.getEventHandler().handle(new AMContainerEventLaunched(containerId));
        ContainerLaunchedEvent lEvt =
            new ContainerLaunchedEvent(containerId, context.getClock().getTime(),
                context.getApplicationAttemptId());

        context.getHistoryHandler().handle(new DAGHistoryEvent(context.getCurrentDAGID(), lEvt));
        return tezChild.run();
      }
    };
  }

  private TezChild createTezChild(Configuration defaultConf, ContainerId containerId,
                                  String tokenIdentifier, int attemptNumber, String[] localDirs,
                                  TezTaskUmbilicalProtocol tezTaskUmbilicalProtocol,
                                  Credentials credentials) throws
      InterruptedException, TezException, IOException {
    Map<String, String> containerEnv = new HashMap<String, String>();
    containerEnv.putAll(localEnv);
    containerEnv.put(Environment.USER.name(), context.getUser());

    // TODO TEZ-1482. Control the memory available based on number of executors
    TezChild tezChild =
        TezChild.newTezChild(defaultConf, null, 0, containerId.toString(), tokenIdentifier,
            attemptNumber, localDirs, workingDirectory, containerEnv, "", executionContext, credentials,
            Runtime.getRuntime().maxMemory(), context.getUser());
    tezChild.setUmbilical(tezTaskUmbilicalProtocol);
    return tezChild;
  }



  @Override
  public void handle(NMCommunicatorEvent event) {
    try {
      eventQueue.put(event);
    } catch (InterruptedException e) {
      throw new TezUncheckedException(e);
    }
  }

}
