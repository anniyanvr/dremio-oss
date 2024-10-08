/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
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
package com.dremio.resource;

import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.options.OptionResolver;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.coordinator.NodeStatusListener;
import com.dremio.service.coordinator.ServiceSet;
import java.util.Collection;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/** Resource information including all executors in the cluster, for software. */
@Singleton
public class ClusterResourceInformation implements GroupResourceInformation {
  private final Provider<ClusterCoordinator> clusterCoordinatorProvider;
  private volatile ServiceSet executorSet;
  private volatile long averageExecutorMemory = 0;
  private volatile int averageExecutorCores = 0;
  private volatile int executorCount = 0;

  @Inject
  public ClusterResourceInformation(Provider<ClusterCoordinator> clusterCoordinatorProvider) {
    this.clusterCoordinatorProvider = clusterCoordinatorProvider;
  }

  @Override
  public void start() throws Exception {
    this.executorSet =
        clusterCoordinatorProvider.get().getServiceSet(ClusterCoordinator.Role.EXECUTOR);
    executorSet.addNodeStatusListener(
        new NodeStatusListener() {
          @Override
          public void nodesUnregistered(Set<NodeEndpoint> unregisteredNodes) {
            refreshInfo();
          }

          @Override
          public void nodesRegistered(Set<NodeEndpoint> registeredNodes) {
            refreshInfo();
          }
        });

    refreshInfo();
  }

  private void refreshInfo() {
    Collection<NodeEndpoint> executorEndpoints = executorSet.getAvailableEndpoints();
    computeAverageMemory(executorEndpoints);
    computeAverageExecutorCores(executorEndpoints);
    executorCount = executorEndpoints.size();
  }

  private void computeAverageMemory(final Collection<NodeEndpoint> executors) {
    if (executors == null || executors.isEmpty()) {
      averageExecutorMemory = 0;
    } else {
      long totalDirectMemory = 0;
      for (final NodeEndpoint endpoint : executors) {
        totalDirectMemory += endpoint.getMaxDirectMemory();
      }

      averageExecutorMemory = totalDirectMemory / executors.size();
    }
  }

  private void computeAverageExecutorCores(final Collection<NodeEndpoint> executors) {
    if (executors == null || executors.isEmpty()) {
      averageExecutorCores = 0;
    } else {
      int totalCoresAcrossExecutors = 0;
      for (final NodeEndpoint endpoint : executors) {
        totalCoresAcrossExecutors += endpoint.getAvailableCores();
      }

      averageExecutorCores = totalCoresAcrossExecutors / executors.size();
    }
  }

  /**
   * Get the average maximum direct memory of executors in the cluster.
   *
   * @return average maximum direct memory of executors
   */
  @Override
  public long getAverageExecutorMemory() {
    return averageExecutorMemory;
  }

  /**
   * Get the number of executors.
   *
   * @return Number of registered executors.
   */
  @Override
  public int getExecutorNodeCount() {
    return executorCount;
  }

  /**
   * Get the average number of cores in executor nodes. This will be used as the default value of
   * MAX_WIDTH_PER_NODE
   *
   * @return average number of executor cores
   */
  @Override
  public long getAverageExecutorCores(final OptionResolver optionManager) {
    return GroupResourceInformation.computeCoresAvailableForExecutor(
        averageExecutorCores, optionManager);
  }

  @Override
  public void close() throws Exception {}
}
