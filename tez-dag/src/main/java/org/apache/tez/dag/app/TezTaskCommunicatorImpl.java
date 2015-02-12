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

package org.apache.tez.dag.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.authorize.PolicyProvider;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.tez.common.*;
import org.apache.tez.common.ContainerContext;
import org.apache.tez.common.security.JobTokenIdentifier;
import org.apache.tez.common.security.JobTokenSecretManager;
import org.apache.tez.common.security.TokenCache;
import org.apache.tez.dag.api.TaskCommunicator;
import org.apache.tez.dag.api.TaskCommunicatorContext;
import org.apache.tez.dag.api.TaskHeartbeatRequest;
import org.apache.tez.dag.api.TaskHeartbeatResponse;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.app.security.authorize.TezAMPolicyProvider;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.runtime.api.impl.TaskSpec;
import org.apache.tez.runtime.api.impl.TezHeartbeatRequest;
import org.apache.tez.runtime.api.impl.TezHeartbeatResponse;

@InterfaceAudience.Private
public class TezTaskCommunicatorImpl extends TaskCommunicator {

  private static final Log LOG = LogFactory.getLog(TezTaskCommunicatorImpl.class);

  private static final ContainerTask TASK_FOR_INVALID_JVM = new ContainerTask(
      null, true, null, null, false);

  private final TaskCommunicatorContext taskCommunicatorContext;

  private final ConcurrentMap<ContainerId, ContainerInfo> registeredContainers =
      new ConcurrentHashMap<ContainerId, ContainerInfo>();
  private final ConcurrentMap<TaskAttempt, ContainerId> attemptToContainerMap =
      new ConcurrentHashMap<TaskAttempt, ContainerId>();

  private final TezTaskUmbilicalProtocol taskUmbilical;
  private InetSocketAddress address;
  private Server server;

  private static final class ContainerInfo {

    ContainerInfo(ContainerId containerId) {
      this.containerId = containerId;
    }

    ContainerId containerId;
    TezHeartbeatResponse lastResponse = null;
    TaskSpec taskSpec = null;
    long lastRequestId = 0;
    Map<String, LocalResource> additionalLRs = null;
    Credentials credentials = null;
    boolean credentialsChanged = false;
    boolean taskPulled = false;

    void reset() {
      taskSpec = null;
      additionalLRs = null;
      credentials = null;
      credentialsChanged = false;
      taskPulled = false;
    }
  }



  /**
   * Construct the service.
   */
  public TezTaskCommunicatorImpl(TaskCommunicatorContext taskCommunicatorContext) {
    super(TezTaskCommunicatorImpl.class.getName());
    this.taskCommunicatorContext = taskCommunicatorContext;
    this.taskUmbilical = new TezTaskUmbilicalProtocolImpl();
  }


  @Override
  public void serviceStart() {

    startRpcServer();
  }

  @Override
  public void serviceStop() {
    stopRpcServer();
  }

  protected void startRpcServer() {
    Configuration conf = getConfig();
    if (!conf.getBoolean(TezConfiguration.TEZ_LOCAL_MODE, TezConfiguration.TEZ_LOCAL_MODE_DEFAULT)) {
      try {
        JobTokenSecretManager jobTokenSecretManager =
            new JobTokenSecretManager();
        Token<JobTokenIdentifier> sessionToken = TokenCache.getSessionToken(taskCommunicatorContext.getCredentials());
        jobTokenSecretManager.addTokenForJob(
            taskCommunicatorContext.getApplicationAttemptId().getApplicationId().toString(), sessionToken);

        server = new RPC.Builder(conf)
            .setProtocol(TezTaskUmbilicalProtocol.class)
            .setBindAddress("0.0.0.0")
            .setPort(0)
            .setInstance(taskUmbilical)
            .setNumHandlers(
                conf.getInt(TezConfiguration.TEZ_AM_TASK_LISTENER_THREAD_COUNT,
                    TezConfiguration.TEZ_AM_TASK_LISTENER_THREAD_COUNT_DEFAULT))
            .setSecretManager(jobTokenSecretManager).build();

        // Enable service authorization?
        if (conf.getBoolean(
            CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHORIZATION,
            false)) {
          refreshServiceAcls(conf, new TezAMPolicyProvider());
        }

        server.start();
        this.address = NetUtils.getConnectAddress(server);
      } catch (IOException e) {
        throw new TezUncheckedException(e);
      }
    } else {
      try {
        this.address = new InetSocketAddress(InetAddress.getLocalHost(), 0);
      } catch (UnknownHostException e) {
        throw new TezUncheckedException(e);
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Not starting TaskAttemptListener RPC in LocalMode");
      }
    }
  }

  protected void stopRpcServer() {
    if (server != null) {
      server.stop();
      server = null;
    }
  }

  private void refreshServiceAcls(Configuration configuration,
                                  PolicyProvider policyProvider) {
    this.server.refreshServiceAcl(configuration, policyProvider);
  }

  @Override
  public void registerRunningContainer(ContainerId containerId, String host, int port) {
    ContainerInfo oldInfo = registeredContainers.putIfAbsent(containerId, new ContainerInfo(containerId));
    if (oldInfo != null) {
      throw new TezUncheckedException("Multiple registrations for containerId: " + containerId);
    }
  }

  @Override
  public void registerContainerEnd(ContainerId containerId) {
    ContainerInfo containerInfo = registeredContainers.remove(containerId);
    if (containerInfo != null) {
      synchronized(containerInfo) {
        if (containerInfo.taskSpec != null && containerInfo.taskSpec.getTaskAttemptID() != null) {
          attemptToContainerMap.remove(containerInfo.taskSpec.getTaskAttemptID());
        }
      }
    }
  }

  @Override
  public void registerRunningTaskAttempt(ContainerId containerId, TaskSpec taskSpec,
                                         Map<String, LocalResource> additionalResources,
                                         Credentials credentials, boolean credentialsChanged) {

    ContainerInfo containerInfo = registeredContainers.get(containerId);
    Preconditions.checkNotNull(containerInfo,
        "Cannot register task attempt: " + taskSpec.getTaskAttemptID() + " to unknown container: " +
            containerId);
    synchronized (containerInfo) {
      if (containerInfo.taskSpec != null) {
        throw new TezUncheckedException(
            "Cannot register task: " + taskSpec.getTaskAttemptID() + " to container: " +
                containerId + " , with pre-existing assignment: " +
                containerInfo.taskSpec.getTaskAttemptID());
      }
      containerInfo.taskSpec = taskSpec;
      containerInfo.additionalLRs = additionalResources;
      containerInfo.credentials = credentials;
      containerInfo.credentialsChanged = credentialsChanged;
      containerInfo.taskPulled = false;

      ContainerId oldId = attemptToContainerMap.putIfAbsent(new TaskAttempt(taskSpec.getTaskAttemptID()), containerId);
      if (oldId != null) {
        throw new TezUncheckedException(
            "Attempting to register an already registered taskAttempt with id: " +
                taskSpec.getTaskAttemptID() + " to containerId: " + containerId +
                ". Already registered to containerId: " + oldId);
      }
    }

  }

  @Override
  public void unregisterRunningTaskAttempt(TezTaskAttemptID taskAttemptID) {
    TaskAttempt taskAttempt = new TaskAttempt(taskAttemptID);
    ContainerId containerId = attemptToContainerMap.remove(taskAttempt);
    if(containerId == null) {
      LOG.warn("Unregister task attempt: " + taskAttempt + " from unknown container");
      return;
    }
    ContainerInfo containerInfo = registeredContainers.get(containerId);
    if (containerInfo == null) {
      LOG.warn("Unregister task attempt: " + taskAttempt +
          " from non-registered container: " + containerId);
      return;
    }
    synchronized (containerInfo) {
      containerInfo.reset();
      attemptToContainerMap.remove(taskAttempt);
    }
  }

  @Override
  public InetSocketAddress getAddress() {
    return address;
  }

  public TezTaskUmbilicalProtocol getUmbilical() {
    return this.taskUmbilical;
  }

  private class TezTaskUmbilicalProtocolImpl implements TezTaskUmbilicalProtocol {

    @Override
    public ContainerTask getTask(ContainerContext containerContext) throws IOException {
      ContainerTask task = null;
      if (containerContext == null || containerContext.getContainerIdentifier() == null) {
        LOG.info("Invalid task request with an empty containerContext or containerId");
        task = TASK_FOR_INVALID_JVM;
      } else {
        ContainerId containerId = ConverterUtils.toContainerId(containerContext
            .getContainerIdentifier());
        if (LOG.isDebugEnabled()) {
          LOG.debug("Container with id: " + containerId + " asked for a task");
        }
        task = getContainerTask(containerId);
        if (task != null && !task.shouldDie()) {
          taskCommunicatorContext
              .taskStartedRemotely(task.getTaskSpec().getTaskAttemptID(), containerId);
        }
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("getTask returning task: " + task);
      }
      return task;
    }

    @Override
    public boolean canCommit(TezTaskAttemptID taskAttemptId) throws IOException {
      return taskCommunicatorContext.canCommit(taskAttemptId);
    }

    @Override
    public TezHeartbeatResponse heartbeat(TezHeartbeatRequest request) throws IOException,
        TezException {
      ContainerId containerId = ConverterUtils.toContainerId(request.getContainerIdentifier());
      long requestId = request.getRequestId();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Received heartbeat from container"
            + ", request=" + request);
      }

      ContainerInfo containerInfo = registeredContainers.get(containerId);
      if (containerInfo == null) {
        LOG.warn("Received task heartbeat from unknown container with id: " + containerId +
            ", asking it to die");
        TezHeartbeatResponse response = new TezHeartbeatResponse();
        response.setLastRequestId(requestId);
        response.setShouldDie();
        return response;
      }

      synchronized (containerInfo) {
        if (containerInfo.lastRequestId == requestId) {
          LOG.warn("Old sequenceId received: " + requestId
              + ", Re-sending last response to client");
          return containerInfo.lastResponse;
        }
      }

      TaskHeartbeatResponse tResponse = null;


      TezTaskAttemptID taskAttemptID = request.getCurrentTaskAttemptID();
      if (taskAttemptID != null) {
        synchronized (containerInfo) {
          ContainerId containerIdFromMap = attemptToContainerMap.get(new TaskAttempt(taskAttemptID));
          if (containerIdFromMap == null || !containerIdFromMap.equals(containerId)) {
            throw new TezException("Attempt " + taskAttemptID
                + " is not recognized for heartbeat");
          }

          if (containerInfo.lastRequestId + 1 != requestId) {
            throw new TezException("Container " + containerId
                + " has invalid request id. Expected: "
                + containerInfo.lastRequestId + 1
                + " and actual: " + requestId);
          }
        }
        TaskHeartbeatRequest tRequest = new TaskHeartbeatRequest(request.getContainerIdentifier(),
            request.getCurrentTaskAttemptID(), request.getEvents(), request.getStartIndex(),
            request.getMaxEvents());
        tResponse = taskCommunicatorContext.heartbeat(tRequest);
      }
      TezHeartbeatResponse response;
      if (tResponse == null) {
        response = new TezHeartbeatResponse();
      } else {
        response = new TezHeartbeatResponse(tResponse.getEvents());
      }
      response.setLastRequestId(requestId);
      containerInfo.lastRequestId = requestId;
      containerInfo.lastResponse = response;
      return response;
    }


    // TODO Remove this method once we move to the Protobuf RPC engine
    @Override
    public long getProtocolVersion(String protocol, long clientVersion) throws IOException {
      return versionID;
    }

    // TODO Remove this method once we move to the Protobuf RPC engine
    @Override
    public ProtocolSignature getProtocolSignature(String protocol, long clientVersion,
                                                  int clientMethodsHash) throws IOException {
      return ProtocolSignature.getProtocolSignature(this, protocol,
          clientVersion, clientMethodsHash);
    }
  }

  private ContainerTask getContainerTask(ContainerId containerId) throws IOException {
    ContainerInfo containerInfo = registeredContainers.get(containerId);
    ContainerTask task = null;
    if (containerInfo == null) {
      if (taskCommunicatorContext.isKnownContainer(containerId)) {
        LOG.info("Container with id: " + containerId
            + " is valid, but no longer registered, and will be killed");
      } else {
        LOG.info("Container with id: " + containerId
            + " is invalid and will be killed");
      }
      task = TASK_FOR_INVALID_JVM;
    } else {
      synchronized (containerInfo) {
        if (containerInfo.taskSpec != null) {
          if (!containerInfo.taskPulled) {
            containerInfo.taskPulled = true;
            task = constructContainerTask(containerInfo);
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Task " + containerInfo.taskSpec.getTaskAttemptID() +
                  " already sent to container: " + containerId);
            }
            task = null;
          }
        } else {
          task = null;
          if (LOG.isDebugEnabled()) {
            LOG.debug("No task assigned yet for running container: " + containerId);
          }
        }
      }
    }
    return task;
  }

  private ContainerTask constructContainerTask(ContainerInfo containerInfo) throws IOException {
    return new ContainerTask(containerInfo.taskSpec, false,
        convertLocalResourceMap(containerInfo.additionalLRs), containerInfo.credentials,
        containerInfo.credentialsChanged);
  }

  private Map<String, TezLocalResource> convertLocalResourceMap(Map<String, LocalResource> ylrs)
      throws IOException {
    Map<String, TezLocalResource> tlrs = Maps.newHashMap();
    if (ylrs != null) {
      for (Map.Entry<String, LocalResource> ylrEntry : ylrs.entrySet()) {
        TezLocalResource tlr;
        try {
          tlr = TezConverterUtils.convertYarnLocalResourceToTez(ylrEntry.getValue());
        } catch (URISyntaxException e) {
          throw new IOException(e);
        }
        tlrs.put(ylrEntry.getKey(), tlr);
      }
    }
    return tlrs;
  }


  // Holder for Task information, which eventually will likely be VertexImplm taskIndex, attemptIndex
  private static class TaskAttempt {
    // TODO TEZ-2003 Change this to work with VertexName, int id, int version
    // TODO TEZ-2003 Avoid constructing this unit all over the place
    private TezTaskAttemptID taskAttemptId;

    TaskAttempt(TezTaskAttemptID taskAttemptId) {
      this.taskAttemptId = taskAttemptId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TaskAttempt)) {
        return false;
      }

      TaskAttempt that = (TaskAttempt) o;

      if (!taskAttemptId.equals(that.taskAttemptId)) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      return taskAttemptId.hashCode();
    }

    @Override
    public String toString() {
      return "TaskAttempt{" + "taskAttemptId=" + taskAttemptId + '}';
    }
  }
}