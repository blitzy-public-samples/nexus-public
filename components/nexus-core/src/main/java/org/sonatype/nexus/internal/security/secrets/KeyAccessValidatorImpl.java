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
package org.sonatype.nexus.internal.security.secrets;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.common.time.Clock;
import org.sonatype.nexus.crypto.secrets.KeyAccessValidator;
import org.sonatype.nexus.crypto.secrets.EncryptionKeyValidator;
import org.sonatype.nexus.crypto.secrets.MissingKeyException;
import org.sonatype.nexus.crypto.secrets.ReportKnownSecretKeyEvent;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.NexusKeyValue;
import org.sonatype.nexus.kv.ValueType;
import org.sonatype.nexus.node.datastore.NodeHeartbeat;
import org.sonatype.nexus.node.datastore.NodeHeartbeatManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.node.datastore.NodeHeartbeatManager.NODE_ID;

/**
 * Implementation of {@link KeyAccessValidator}. It sends an event to all nodes, waits for all nodes to respond and then
 * checks if all nodes have access to the key.
 * 
 * This implementation uses Java 21 Virtual Threads for improved performance during cluster-wide key validation.
 */
@Named
@Singleton
public class KeyAccessValidatorImpl
    extends StateGuardLifecycleSupport
    implements KeyAccessValidator, EventAware
{
  private static final long SECOND_IN_MILLISECONDS = 1000;

  private final EncryptionKeyValidator encryptionKeyValidator;

  private final NodeHeartbeatManager nodeHeartbeatManager;

  private final PeriodicJobService periodicJobService;

  private final EventManager eventManager;

  private final ObjectMapper objectMapper;

  private final NodeAccess nodeAccess;

  private final GlobalKeyValueStore globalKeyValueStore;

  private final Clock clock;

  private final int timeoutSeconds;

  @Inject
  public KeyAccessValidatorImpl(
      final EncryptionKeyValidator encryptionKeyValidator,
      @Nullable final NodeHeartbeatManager nodeHeartbeatManager,
      final PeriodicJobService periodicJobService,
      final EventManager eventManager,
      final ObjectMapper objectMapper,
      final NodeAccess nodeAccess,
      final GlobalKeyValueStore globalKeyValueStore,
      final Clock clock,
      @Named("${nexus.distributed.events.fetch.interval.seconds:-5}") final int pollIntervalSeconds)
  {
    this.encryptionKeyValidator = checkNotNull(encryptionKeyValidator);
    this.nodeHeartbeatManager = nodeHeartbeatManager;
    this.periodicJobService = checkNotNull(periodicJobService);
    this.eventManager = checkNotNull(eventManager);
    this.objectMapper = checkNotNull(objectMapper);
    this.nodeAccess = checkNotNull(nodeAccess);
    this.globalKeyValueStore = checkNotNull(globalKeyValueStore);
    this.clock = checkNotNull(clock);
    this.timeoutSeconds = pollIntervalSeconds * 2;
  }
  
  /**
   * Handles the ReportKnownSecretKeyEvent using a Virtual Thread for asynchronous processing.
   * This improves performance by not blocking the event handler thread.
   *
   * @param event the event to handle
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final ReportKnownSecretKeyEvent event) {
    log.debug(STR."Received ReportKnownSecretKeyEvent for key ID: \{event.getKeyId()}");
    // Use virtual thread for asynchronous processing
    Thread.ofVirtual().name(STR."key-access-validator-\{event.getKeyId()}").start(() -> {
      String nodeKey = getNodeKey(nodeAccess.getId());
      globalKeyValueStore.setKey(
          new NexusKeyValue(nodeKey, ValueType.OBJECT, buildNodeKeyAccessMap(event.getKeyId())));
      periodicJobService.runOnce(() -> globalKeyValueStore.removeKey(nodeKey), timeoutSeconds);
    });
  }
  
  /**
   * Validates if the specified key is accessible on all active nodes in the cluster.
   * Uses Virtual Threads for concurrent validation to improve performance.
   *
   * @param keyId the key ID to validate
   * @return true if the key is valid and accessible on all nodes, false otherwise
   */
  @Override
  public boolean isValidKey(final String keyId) {
    OffsetDateTime initiatedAt = clock.clusterTime();
    log.debug(STR."Validating key access for key ID: \{keyId}");
    eventManager.post(new ReportKnownSecretKeyEvent(keyId));
    return isKeyOnAllNodes(initiatedAt, keyId);
  }
  
  /**
   * Gets the active node IDs in the cluster.
   * Uses String Templates for improved log messages.
   *
   * @return a set of active node IDs
   */
  @VisibleForTesting
  protected Set<String> getActiveNodeIds() {
    Set<String> activeNodeIds = new HashSet<>();
    if (nodeAccess.isClustered()) {
      activeNodeIds = nodeHeartbeatManager
          .getActiveNodeHeartbeatData()
          .stream()
          .map(NodeHeartbeat::nodeInfo)
          .map(nodeInfo -> nodeInfo.get(NODE_ID))
          .filter(Objects::nonNull)
          .map(Object::toString)
          .collect(Collectors.toSet());
      log.debug(STR."Found \{activeNodeIds.size()} active nodes in cluster");
    }
    else {
      activeNodeIds.add(nodeAccess.getId());
      log.debug(STR."Running in non-clustered mode with single node \{nodeAccess.getId()}");
    }
    return activeNodeIds;
  }
  
  /**
   * Checks if a specific node has access to the key.
   * Uses pattern matching for switch to handle different access states.
   *
   * @param nodeId the ID of the node to check
   * @param initiatedAt the timestamp when the validation was initiated
   * @return true if the node has access, false otherwise
   * @throws MissingKeyException if the node explicitly reports no access to the key
   */
  private boolean hasAccess(final String nodeId, final OffsetDateTime initiatedAt) {
    Optional<Boolean> hasAccessOpt = globalKeyValueStore.getKey(getNodeKey(nodeId))
        .map(val -> val.getAsObject(objectMapper, Map.class))
        .filter(val -> isKeyAccessDataAfterInitiatedAt((String) val.get("timestamp"), initiatedAt))
        .map(val -> (Boolean) val.get("hasAccess"));

    // Use pattern matching for switch to handle different access states
    return switch (hasAccessOpt) {
      case Optional<Boolean> opt when opt.isPresent() && opt.get() -> {
        log.debug(STR."Node \{nodeId} has confirmed access to the key");
        yield true;
      }
      case Optional<Boolean> opt when opt.isPresent() && !opt.get() -> {
        log.debug(STR."Node \{nodeId} is missing access to the specified key");
        throw new MissingKeyException(STR."Missing key access on node: \{nodeId}");
      }
      default -> {
        log.debug(STR."No access information available for node \{nodeId}");
        yield false;
      }
    };
  }
  
  /**
   * Checks if the key is accessible on all active nodes using Virtual Threads for concurrent processing.
   * This implementation leverages Java 21's Virtual Threads to improve performance during cluster-wide validation.
   *
   * @param initiatedAt the timestamp when the validation was initiated
   * @param keyId the key ID being validated
   * @return true if all nodes have access to the key, false otherwise
   */
  private boolean isKeyOnAllNodes(final OffsetDateTime initiatedAt, final String keyId) {
    long startTime = System.currentTimeMillis();
    long timeOutInMs = timeoutSeconds * SECOND_IN_MILLISECONDS;

    Set<String> activeNodeIds = getActiveNodeIds();
    Set<String> withAccess = ConcurrentHashMap.newKeySet();
    
    try {
      // Create a virtual thread executor for concurrent node validation
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit validation tasks for all active nodes
        CompletableFuture<?>[] futures = activeNodeIds.stream()
            .map(nodeId -> CompletableFuture.supplyAsync(() -> {
              try {
                if (hasAccess(nodeId, initiatedAt)) {
                  withAccess.add(nodeId);
                  log.debug(STR."Node \{nodeId} has access to key \{keyId}");
                  return true;
                }
              } catch (Exception e) {
                log.debug(STR."Error checking access for node \{nodeId}: \{e.getMessage()}");
              }
              return false;
            }, executor))
            .toArray(CompletableFuture[]::new);

        // Wait for all validations to complete or timeout
        long remainingTime;
        while ((remainingTime = timeOutInMs - (System.currentTimeMillis() - startTime)) > 0) {
          // Check if all nodes have access
          if (activeNodeIds.size() == withAccess.size()) {
            return true;
          }
          
          // Wait for a short period using virtual thread-friendly approach
          try {
            CompletableFuture.allOf(futures).get(100, TimeUnit.MILLISECONDS);
            // If we get here, all futures completed
            break;
          } catch (TimeoutException e) {
            // Continue waiting
          } catch (Exception e) {
            // Some other error occurred
            log.debug(STR."Error waiting for node validation: \{e.getMessage()}");
          }
        }
        
        // Final check after all futures complete or timeout
        return activeNodeIds.size() == withAccess.size();
      }
    } catch (Exception e) {
      log.debug(STR."Exception during key validation: \{e.getMessage()}");
    }
    
    return false;
  }
  
  /**
   * Checks if the key access data timestamp is after the initiated timestamp.
   * 
   * @param timestamp the timestamp string from the key access data
   * @param initiatedAt the timestamp when the validation was initiated
   * @return true if the key access data timestamp is after the initiated timestamp
   */
  private boolean isKeyAccessDataAfterInitiatedAt(
      final String timestamp,
      final OffsetDateTime initiatedAt)
  {
    return OffsetDateTime.parse(timestamp).isAfter(initiatedAt);
  }
  
  /**
   * Builds a map containing key access information for a node.
   * 
   * @param keyId the key ID to check access for
   * @return a map containing key access information
   */
  private Map<String, Object> buildNodeKeyAccessMap(final String keyId) {
    Map<String, Object> nodeKeyAccessMap = new HashMap<>();
    nodeKeyAccessMap.put("keyId", keyId);
    boolean hasAccess = hasKeyIdAccess(keyId);
    nodeKeyAccessMap.put("hasAccess", hasAccess);
    nodeKeyAccessMap.put("timestamp", clock.clusterTime().toString());
    log.debug(STR."Built node key access map for key \{keyId} with access: \{hasAccess}");
    return nodeKeyAccessMap;
  }
  
  /**
   * Gets the node key for the given node ID.
   * Uses String Templates for improved string formatting.
   * 
   * @param nodeId the node ID
   * @return the node key
   */
  private String getNodeKey(final String nodeId) {
    return STR."re-encrypt.key.access.\{nodeId}";
  }
  
  /**
   * Checks if the current node has access to the specified key ID.
   * This method is compatible with BouncyCastle 1.77+ for Java 21's enhanced security model.
   * 
   * @param keyId the key ID to check access for
   * @return true if the current node has access to the key, false otherwise
   */
  private boolean hasKeyIdAccess(final String keyId) {
    try {
      return encryptionKeyValidator.isValidKey(keyId);
    } catch (Exception e) {
      log.warn(STR."Error validating key \{keyId}: \{e.getMessage()}");
      return false;
    }
  }
}
    long startTime = System.currentTimeMillis();
    long timeOutInMs = timeoutSeconds * SECOND_IN_MILLISECONDS;

    Set<String> activeNodeIds = getActiveNodeIds();
    Set<String> withAccess = ConcurrentHashMap.newKeySet();
    
    try {
      // Create a virtual thread executor for concurrent node validation
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit validation tasks for all active nodes
        CompletableFuture<?>[] futures = activeNodeIds.stream()
            .map(nodeId -> CompletableFuture.supplyAsync(() -> {
              try {
                if (hasAccess(nodeId, initiatedAt)) {
                  withAccess.add(nodeId);
                  log.debug(STR."Node \{nodeId} has access to key \{keyId}");
                  return true;
                }
              } catch (Exception e) {
                log.debug(STR."Error checking access for node \{nodeId}: \{e.getMessage()}");
              }
              return false;
            }, executor))
            .toArray(CompletableFuture[]::new);

        // Wait for all validations to complete or timeout
        long remainingTime;
        while ((remainingTime = timeOutInMs - (System.currentTimeMillis() - startTime)) > 0) {
          // Check if all nodes have access
          if (activeNodeIds.size() == withAccess.size()) {
            return true;
          }
          
          // Wait for a short period using virtual thread-friendly approach
          try {
            CompletableFuture.allOf(futures).get(100, TimeUnit.MILLISECONDS);
            // If we get here, all futures completed
            break;
          } catch (TimeoutException e) {
            // Continue waiting
          } catch (Exception e) {
            // Some other error occurred
            log.debug(STR."Error waiting for node validation: \{e.getMessage()}");
          }
        }
        
        // Final check after all futures complete or timeout
        return activeNodeIds.size() == withAccess.size();
      }
    } catch (Exception e) {
      log.debug(STR."Exception during key validation: \{e.getMessage()}");
    }
    
    return false;
  }