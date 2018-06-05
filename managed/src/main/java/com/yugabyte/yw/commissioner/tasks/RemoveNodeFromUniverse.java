// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.SubTaskGroupQueue;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.commissioner.tasks.subtasks.ChangeMasterConfig;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForDataMove;
import com.yugabyte.yw.commissioner.tasks.subtasks.WaitForLoadBalance;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;

import java.util.Arrays;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import play.api.Play;
import play.libs.Json;

// Allows the removal of a node from a universe. Ensures the task waits for the right set of
// server data move primitives. And stops using the underlying instance, though YW still owns it.
public class RemoveNodeFromUniverse extends UniverseTaskBase {
  public static final Logger LOG = LoggerFactory.getLogger(RemoveNodeFromUniverse.class);

  @Override
  protected NodeTaskParams taskParams() {
    return (NodeTaskParams)taskParams;
  }

  @Override
  public void run() {
    LOG.info("Started {} task for node {} in univ uuid={}", getName(),
             taskParams().nodeName, taskParams().universeUUID);
    NodeDetails currentNode = null;
    boolean hitException = false;
    try {
      // Create the task list sequence.
      subTaskGroupQueue = new SubTaskGroupQueue(userTaskUUID);

      // Set the 'updateInProgress' flag to prevent other updates from happening.
      Universe universe = lockUniverseForUpdate(taskParams().expectedUniverseVersion);

      currentNode = universe.getNode(taskParams().nodeName);
      if (currentNode == null) {
        String msg = "No node " + taskParams().nodeName + " found in universe " + universe.name;
        LOG.error(msg);
        throw new RuntimeException(msg);
      }

      if (currentNode.state != NodeDetails.NodeState.Live) {
        String msg = "Node " + taskParams().nodeName + " is not in live state, but is in " +
                     currentNode.state + ", so cannot be removed.";
        LOG.error(msg);
        throw new RuntimeException(msg);
      }

      taskParams().azUuid = currentNode.azUuid;
      taskParams().placementUuid = currentNode.placementUuid;

      // Update Node State to being removed.
      createSetNodeStateTask(currentNode, NodeState.Removing)
          .setSubTaskGroupType(SubTaskGroupType.RemovingNode);

      if (instanceExists(taskParams())) {
        String masterAddrs = universe.getMasterAddresses();
        // Remove the master on this node from master quorum and update its state from YW DB,
        // only if it reachable.
        boolean masterReachable = isMasterAliveOnNode(currentNode, masterAddrs);
        LOG.info("Master {}, reachable = {}.", currentNode.cloudInfo.private_ip, masterReachable);
        if (currentNode.isMaster) {
          if (masterReachable) {
            createChangeConfigTask(currentNode, false, SubTaskGroupType.WaitForDataMigration);
            createStopMasterTasks(new HashSet<NodeDetails>(Arrays.asList(currentNode)))
                .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);
            createWaitForMasterLeaderTask()
                .setSubTaskGroupType(SubTaskGroupType.WaitForDataMigration);
          } else {
            createChangeConfigTask(currentNode, false, SubTaskGroupType.WaitForDataMigration, true);
          }
        }

        // Mark the tserver as blacklisted on the master leader.
        UserIntent userIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;
        createPlacementInfoTask(new HashSet<NodeDetails>(Arrays.asList(currentNode)),
                                userIntent.replicationFactor)
            .setSubTaskGroupType(SubTaskGroupType.WaitForDataMigration);

        // Wait for data move and stop the tserver process only if it is reachable.
        boolean tserverReachable = isTserverAliveOnNode(currentNode, masterAddrs);
        LOG.info("Tserver {}, reachable = {}.", currentNode.cloudInfo.private_ip, tserverReachable);
        if (tserverReachable) {
          createWaitForDataMoveTask()
              .setSubTaskGroupType(SubTaskGroupType.WaitForDataMigration);
          createTServerTaskForNode(currentNode, "stop")
              .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);
        }
      } else {
        if (currentNode.isMaster) {
          createWaitForMasterLeaderTask()
              .setSubTaskGroupType(SubTaskGroupType.WaitForDataMigration);
          createChangeConfigTask(currentNode, false, SubTaskGroupType.WaitForDataMigration, true);
        }
      }

      // Remove master status (even when it does not exists or is not reachable).
      createUpdateNodeProcessTask(taskParams().nodeName, ServerType.MASTER, false)
          .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);

      // Remove its tserver status in DB.
      createUpdateNodeProcessTask(taskParams().nodeName, ServerType.TSERVER, false)
          .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);

      // Update Node State to Removed
      createSetNodeStateTask(currentNode, NodeState.Removed)
          .setSubTaskGroupType(SubTaskGroupType.RemovingNode);

      // Mark universe task state to success
      createMarkUniverseUpdateSuccessTasks()
          .setSubTaskGroupType(SubTaskGroupType.RemovingNode);

      // Run all the tasks.
      subTaskGroupQueue.run();
    } catch (Throwable t) {
      LOG.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);
      hitException = true;
      throw t;
    } finally {
      // Reset the state, on any failure, so that the actions can be retried.
      if (currentNode != null && hitException) {
        setNodeState(taskParams().nodeName, currentNode.state);
      }

      // Mark the update of the universe as done. This will allow future edits/updates to the
      // universe to happen.
      unlockUniverseForUpdate();
    }
    LOG.info("Finished {} task.", getName());
  }
}