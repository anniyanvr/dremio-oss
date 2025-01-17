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
package com.dremio.service.jobcounts;

import com.dremio.service.grpc.GrpcChannelBuilderFactory;
import com.dremio.service.grpc.GrpcServerBuilderFactory;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utilities related to RPC. */
public final class JobCountsRpcUtils {
  private static final Logger logger = LoggerFactory.getLogger(JobCountsRpcUtils.class);

  private static final String IN_PROCESS_SERVICE_NAME = "local_job_counts_service";
  private static final String JOB_COUNTS_HOSTNAME =
      System.getProperty("services.jobcounts.hostname");
  private static final int JOB_COUNTS_PORT = Integer.getInteger("services.jobcounts.port", 9000);

  /**
   * Create a new in-process server builder. Append the fabricPort to handle the case where multiple
   * SabotNode are started within the same jvm (for eg. mongo tests).
   *
   * @return server builder
   */
  public static ServerBuilder<?> newInProcessServerBuilder(
      GrpcServerBuilderFactory grpcFactory, int fabricPort) {
    return grpcFactory.newInProcessServerBuilder(IN_PROCESS_SERVICE_NAME + ":" + fabricPort);
  }

  public static String getJobCountsHostname() {
    return JOB_COUNTS_HOSTNAME;
  }

  public static int getJobCountsPort() {
    return JOB_COUNTS_PORT;
  }

  /**
   * Create a new channel builder.
   *
   * @return channel builder
   */
  public static ManagedChannelBuilder<?> newLocalChannelBuilder(
      GrpcChannelBuilderFactory grpcFactory, int fabricPort) {
    return grpcFactory.newInProcessChannelBuilder(IN_PROCESS_SERVICE_NAME + ":" + fabricPort);
  }

  // prevent instantiation
  private JobCountsRpcUtils() {}
}
