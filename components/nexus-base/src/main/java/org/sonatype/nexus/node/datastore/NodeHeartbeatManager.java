/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.node.datastore;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.sonatype.nexus.systemchecks.NodeSystemCheckResult;

/**
 * Create the job which will collect information about nodes and periodically write it to DB.
 */
public interface NodeHeartbeatManager
{
  String VERSION_ATTRIBUTE = "version";

  String CLUSTERED_ATTRIBUTE = "clustered";

  String NODE_ID = "nodeId";

  String CACHE_KEY = "nodes";

  /**
   * Determines if the current deployment is on sync with their database/deployment type
   *
   * @return {@code true} in case of a valid deployment, otherwise {@code false}.
   */
  boolean isValidNodeDeployment();

  /**
   * Get the {@link NodeSystemCheckResult} for the active nodes
   * 
   * This method runs on the calling thread and may block during I/O operations.
   * For non-blocking operation, use {@link #getSystemChecksAsync()}
   */
  Stream<NodeSystemCheckResult> getSystemChecks();

  /**
   * Get the {@link NodeSystemCheckResult} for the active nodes asynchronously using Java 21 Virtual Threads
   * 
   * This method returns immediately and performs I/O operations on a Virtual Thread,
   * providing improved concurrency and resource utilization for I/O-bound operations.
   *
   * @return A CompletableFuture that will complete with the stream of system check results
   * @since 3.77.0
   */
  CompletableFuture<Stream<NodeSystemCheckResult>> getSystemChecksAsync();

  /**
   * Determines if the current node is in a clustered mode
   */
  boolean isCurrentNodeClustered();

  /**
   * Triggers a write of the latest heartbeat information
   * 
   * This method runs on the calling thread and may block during database I/O operations.
   * For non-blocking operation, use {@link #writeHeartbeatAsync()}
   */
  void writeHeartbeat();

  /**
   * Triggers an asynchronous write of the latest heartbeat information using Java 21 Virtual Threads
   * 
   * This method returns immediately and performs database I/O operations on a Virtual Thread,
   * providing improved concurrency and resource utilization for I/O-bound operations.
   *
   * @return A CompletableFuture that will complete when the heartbeat has been written
   * @since 3.77.0
   */
  CompletableFuture<Void> writeHeartbeatAsync();

  /**
   * Collects and transforms system info from heartbeat table
   */
  Map<String, Map<String, Object>> getSystemInformationForNodes();

  /**
   * Collects nodeInfo from the heartbeat table
   */
  Collection<NodeHeartbeat> getActiveNodeHeartbeatData();
}
