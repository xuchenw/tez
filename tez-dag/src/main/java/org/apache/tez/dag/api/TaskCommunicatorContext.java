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

package org.apache.tez.dag.api;

import java.io.IOException;

import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.tez.dag.records.TezTaskAttemptID;


// Do not make calls into this from within a held lock.

// TODO TEZ-2003 Move this into the tez-api module
public interface TaskCommunicatorContext {

  // TODO TEZ-2003 Add signalling back into this to indicate errors - e.g. Container unregsitered, task no longer running, etc.

  // TODO TEZ-2003 Maybe add book-keeping as a helper library, instead of each impl tracking container to task etc.

  ApplicationAttemptId getApplicationAttemptId();
  Credentials getCredentials();

  // TODO TEZ-2003 Move to vertex, taskIndex, version
  boolean canCommit(TezTaskAttemptID taskAttemptId) throws IOException;

  TaskHeartbeatResponse heartbeat(TaskHeartbeatRequest request) throws IOException, TezException;

  boolean isKnownContainer(ContainerId containerId);

  // TODO TEZ-2003 Move to vertex, taskIndex, version
  void taskStartedRemotely(TezTaskAttemptID taskAttemptID, ContainerId containerId);

  // TODO TEZ-2003 Add an API to register task failure - for example, a communication failure.
  // This will have to take into consideration the TA_FAILED event

  // TODO Eventually Add methods to report availability stats to the scheduler.
}
