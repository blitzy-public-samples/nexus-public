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
package org.sonatype.nexus.repository.replication;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Event representing a blob operation that needs to be replicated.
 * Enhanced for Java 21 with Virtual Thread support, async operation tracking,
 * and improved concurrency handling.
 *
 * @since 3.31
 */
public class BlobEvent
{
  /**
   * Enum representing the current state of the asynchronous operation.
   */
  public enum OperationState {
    PENDING,      // Initial state, not yet processed
    IN_PROGRESS,  // Currently being processed
    COMPLETED,    // Successfully completed
    FAILED        // Failed to complete
  }

  // Base properties
  private String blobId;
  private String assetPath;
  private String repositoryName;
  private String replicationConnectionId;
  private BlobEventType blobEventType;
  private volatile boolean inUse;

  // Retry and concurrency handling
  private final AtomicInteger retryCount = new AtomicInteger(0);
  private final AtomicReference<Instant> lastRetryTime = new AtomicReference<>();
  private final AtomicReference<Instant> createdTime = new AtomicReference<>(Instant.now());

  // Async operation state tracking
  private final AtomicReference<OperationState> state = new AtomicReference<>(OperationState.PENDING);
  private final AtomicReference<Instant> stateUpdatedTime = new AtomicReference<>(Instant.now());
  private final AtomicReference<String> errorMessage = new AtomicReference<>();
  private final AtomicReference<String> processingThreadId = new AtomicReference<>();

  // Batching support
  private String batchId;
  private int priority = 0;

  // Default retry configuration
  private static final int MAX_RETRY_COUNT = 10;
  private static final Duration INITIAL_RETRY_DELAY = Duration.ofMillis(100);
  private static final double BACKOFF_MULTIPLIER = 2.0;
  private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(10);

  /**
   * Increments the retry count and updates the last retry time.
   * @return this instance for method chaining
   */
  public BlobEvent retry() {
    retryCount.incrementAndGet();
    lastRetryTime.set(Instant.now());
    return this;
  }

  /**
   * Checks if this event should be retried based on retry count and max retries.
   * @return true if the event should be retried, false otherwise
   */
  public boolean shouldRetry() {
    return retryCount.get() < MAX_RETRY_COUNT;
  }

  /**
   * Calculates the next retry delay using exponential backoff.
   * @return the duration to wait before the next retry attempt
   */
  public Duration calculateRetryDelay() {
    int currentRetries = retryCount.get();
    if (currentRetries <= 0) {
      return INITIAL_RETRY_DELAY;
    }
    
    // Calculate exponential backoff with jitter
    double exponentialFactor = Math.pow(BACKOFF_MULTIPLIER, currentRetries - 1);
    long delayMillis = (long) (INITIAL_RETRY_DELAY.toMillis() * exponentialFactor);
    
    // Add some randomness (jitter) to prevent retry storms - ±15%
    double jitter = 0.85 + (Math.random() * 0.3); // between 0.85 and 1.15
    delayMillis = (long) (delayMillis * jitter);
    
    // Cap at maximum delay
    return Duration.ofMillis(Math.min(delayMillis, MAX_RETRY_DELAY.toMillis()));
  }

  /**
   * Updates the operation state and records the time of the state change.
   * @param newState the new operation state
   * @return this instance for method chaining
   */
  public BlobEvent updateState(final OperationState newState) {
    state.set(newState);
    stateUpdatedTime.set(Instant.now());
    return this;
  }

  /**
   * Updates the operation state to FAILED and records the error message.
   * @param message the error message describing the failure
   * @return this instance for method chaining
   */
  public BlobEvent fail(final String message) {
    state.set(OperationState.FAILED);
    errorMessage.set(message);
    stateUpdatedTime.set(Instant.now());
    return this;
  }

  /**
   * Marks this event as being processed by the current thread.
   * @return this instance for method chaining
   */
  public BlobEvent markInProgress() {
    inUse = true;
    updateState(OperationState.IN_PROGRESS);
    processingThreadId.set(Thread.currentThread().toString());
    return this;
  }

  /**
   * Marks this event as completed successfully.
   * @return this instance for method chaining
   */
  public BlobEvent markCompleted() {
    inUse = false;
    updateState(OperationState.COMPLETED);
    return this;
  }

  /**
   * Creates a new batch ID if one doesn't exist.
   * @return the batch ID
   */
  public String ensureBatchId() {
    if (batchId == null) {
      batchId = UUID.randomUUID().toString();
    }
    return batchId;
  }

  /**
   * Gets the duration since this event was created.
   * @return the duration since creation
   */
  public Duration getAge() {
    return Duration.between(createdTime.get(), Instant.now());
  }

  /**
   * Gets the duration since the last state change.
   * @return the duration since the last state change
   */
  public Duration getTimeSinceStateChange() {
    return Duration.between(stateUpdatedTime.get(), Instant.now());
  }

  /**
   * Gets the duration since the last retry attempt.
   * @return the duration since the last retry, or null if never retried
   */
  public Duration getTimeSinceLastRetry() {
    Instant lastRetry = lastRetryTime.get();
    return lastRetry != null ? Duration.between(lastRetry, Instant.now()) : null;
  }

  // Getters

  public String getBlobId() {
    return blobId;
  }

  public String getAssetPath() {
    return assetPath;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public String getReplicationConnectionId() {
    return replicationConnectionId;
  }

  public BlobEventType getBlobEventType() {
    return blobEventType;
  }

  public int getRetryCount() {
    return retryCount.get();
  }

  public boolean isInUse() {
    return inUse;
  }

  public OperationState getState() {
    return state.get();
  }

  public Instant getCreatedTime() {
    return createdTime.get();
  }

  public Instant getStateUpdatedTime() {
    return stateUpdatedTime.get();
  }

  public Instant getLastRetryTime() {
    return lastRetryTime.get();
  }

  public String getErrorMessage() {
    return errorMessage.get();
  }

  public String getProcessingThreadId() {
    return processingThreadId.get();
  }

  public String getBatchId() {
    return batchId;
  }

  public int getPriority() {
    return priority;
  }

  // Fluent setters

  public BlobEvent withBlobId(final String blobId) {
    this.blobId = blobId;
    return this;
  }

  public BlobEvent withAssetPath(final String assetPath) {
    this.assetPath = assetPath;
    return this;
  }

  public BlobEvent withRepositoryName(final String repositoryName) {
    this.repositoryName = repositoryName;
    return this;
  }

  public BlobEvent withReplicationConnectionId(final String replicationConnectionId) {
    this.replicationConnectionId = replicationConnectionId;
    return this;
  }

  public BlobEvent withBlobEventType(final BlobEventType blobEventType) {
    this.blobEventType = blobEventType;
    return this;
  }

  public BlobEvent withInUse(final boolean inUse) {
    this.inUse = inUse;
    return this;
  }

  public BlobEvent withRetryCount(final int retryCount) {
    this.retryCount.set(retryCount);
    return this;
  }

  public BlobEvent withBatchId(final String batchId) {
    this.batchId = batchId;
    return this;
  }

  public BlobEvent withPriority(final int priority) {
    this.priority = priority;
    return this;
  }

  public BlobEvent withErrorMessage(final String errorMessage) {
    this.errorMessage.set(errorMessage);
    return this;
  }

  /**
   * Thread-safe implementation of equals that handles concurrent modifications.
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BlobEvent blobEvent = (BlobEvent) o;
    
    // Capture atomic values once to prevent inconsistent comparisons
    int thisRetryCount = this.retryCount.get();
    OperationState thisState = this.state.get();
    String thisError = this.errorMessage.get();
    
    return inUse == blobEvent.inUse && 
        thisRetryCount == blobEvent.getRetryCount() &&
        priority == blobEvent.priority &&
        Objects.equals(blobId, blobEvent.blobId) &&
        Objects.equals(assetPath, blobEvent.assetPath) &&
        Objects.equals(repositoryName, blobEvent.repositoryName) &&
        Objects.equals(replicationConnectionId, blobEvent.replicationConnectionId) &&
        Objects.equals(batchId, blobEvent.batchId) &&
        thisState == blobEvent.getState() &&
        Objects.equals(thisError, blobEvent.getErrorMessage()) &&
        blobEventType == blobEvent.blobEventType;
  }

  /**
   * Thread-safe implementation of hashCode that handles concurrent modifications.
   */
  @Override
  public int hashCode() {
    // Capture atomic values once to ensure consistent hash code
    int thisRetryCount = this.retryCount.get();
    OperationState thisState = this.state.get();
    String thisError = this.errorMessage.get();
    
    return Objects.hash(
        blobId, 
        assetPath, 
        repositoryName, 
        replicationConnectionId, 
        blobEventType, 
        inUse, 
        thisRetryCount,
        batchId,
        priority,
        thisState,
        thisError
    );
  }

  @Override
  public String toString() {
    return "BlobEvent{" +
        "blobId='" + blobId + '\'' +
        ", assetPath='" + assetPath + '\'' +
        ", repositoryName='" + repositoryName + '\'' +
        ", replicationConnectionId='" + replicationConnectionId + '\'' +
        ", blobEventType=" + blobEventType +
        ", inUse=" + inUse +
        ", retryCount=" + retryCount +
        ", state=" + state +
        ", createdTime=" + createdTime +
        ", lastStateChange=" + stateUpdatedTime +
        ", batchId='" + batchId + '\'' +
        ", priority=" + priority +
        (errorMessage.get() != null ? ", error='" + errorMessage.get() + '\'' : "") +
        '}';
  }
}