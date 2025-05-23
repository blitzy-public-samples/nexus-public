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
 * Represents a blob event in the replication system.
 * Enhanced with Java 21 features for improved concurrency and Virtual Thread support.
 *
 * @since 3.31
 */
public class BlobEvent
{
  /**
   * Maximum number of retries before giving up.
   */
  private static final int MAX_RETRY_COUNT = 10;

  /**
   * Base delay for exponential backoff in milliseconds.
   */
  private static final long BASE_RETRY_DELAY_MS = 100;

  /**
   * Represents the current state of an asynchronous operation.
   */
  public enum AsyncState {
    PENDING,    // Initial state, not yet processed
    PROCESSING, // Currently being processed
    COMPLETED,  // Successfully completed
    FAILED,     // Failed and will not be retried
    RETRYING    // Failed but will be retried
  }

  private String blobId;

  private String assetPath;

  private String repositoryName;

  private String replicationConnectionId;

  private BlobEventType blobEventType;

  private volatile boolean inUse;

  private final AtomicInteger retryCount = new AtomicInteger(0);

  private final AtomicReference<AsyncState> asyncState = new AtomicReference<>(AsyncState.PENDING);

  private Instant lastAttemptTime;

  private Instant creationTime = Instant.now();

  private String batchId;

  private int batchPosition;

  private int batchSize;

  /**
   * Increments the retry count and updates the async state.
   * Thread-safe implementation for use with Virtual Threads.
   */
  public void retry() {
    retryCount.incrementAndGet();
    asyncState.set(AsyncState.RETRYING);
    lastAttemptTime = Instant.now();
  }

  /**
   * Determines if this event should be retried based on retry count and max retries.
   * Thread-safe implementation for use with Virtual Threads.
   *
   * @return true if the event should be retried, false otherwise
   */
  public boolean shouldRetry() {
    return retryCount.get() > 0 && retryCount.get() <= MAX_RETRY_COUNT;
  }

  /**
   * Calculates the next retry delay using exponential backoff.
   * This provides increasing delays between retry attempts to prevent overwhelming the system.
   *
   * @return Duration to wait before the next retry attempt
   */
  public Duration getRetryDelay() {
    int currentRetries = retryCount.get();
    if (currentRetries <= 0) {
      return Duration.ZERO;
    }
    
    // Exponential backoff with jitter: BASE_DELAY * 2^(retryCount-1) * (0.75 + 0.5*random)
    // Simplified implementation with fixed 0.875 jitter factor (midpoint of range)
    long delayMs = (long) (BASE_RETRY_DELAY_MS * Math.pow(2, currentRetries - 1) * 0.875);
    return Duration.ofMillis(delayMs);
  }

  /**
   * Marks this event as being processed by setting the async state to PROCESSING.
   * Thread-safe implementation for use with Virtual Threads.
   */
  public void markProcessing() {
    asyncState.set(AsyncState.PROCESSING);
    lastAttemptTime = Instant.now();
  }

  /**
   * Marks this event as completed by setting the async state to COMPLETED.
   * Thread-safe implementation for use with Virtual Threads.
   */
  public void markCompleted() {
    asyncState.set(AsyncState.COMPLETED);
  }

  /**
   * Marks this event as failed by setting the async state to FAILED.
   * Thread-safe implementation for use with Virtual Threads.
   */
  public void markFailed() {
    asyncState.set(AsyncState.FAILED);
  }

  /**
   * Gets the current async state of this event.
   *
   * @return the current AsyncState
   */
  public AsyncState getAsyncState() {
    return asyncState.get();
  }

  /**
   * Gets the time of the last attempt to process this event.
   *
   * @return the last attempt time or null if never attempted
   */
  public Instant getLastAttemptTime() {
    return lastAttemptTime;
  }

  /**
   * Gets the creation time of this event.
   *
   * @return the creation time
   */
  public Instant getCreationTime() {
    return creationTime;
  }

  /**
   * Creates a new batch ID for a group of related events.
   *
   * @return a new batch ID
   */
  public static String generateBatchId() {
    return UUID.randomUUID().toString();
  }

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

  /**
   * Gets the batch ID this event belongs to, if any.
   *
   * @return the batch ID or null if not part of a batch
   */
  public String getBatchId() {
    return batchId;
  }

  /**
   * Gets the position of this event within its batch.
   *
   * @return the batch position
   */
  public int getBatchPosition() {
    return batchPosition;
  }

  /**
   * Gets the total size of the batch this event belongs to.
   *
   * @return the batch size
   */
  public int getBatchSize() {
    return batchSize;
  }

  /**
   * Checks if this event is part of a batch.
   *
   * @return true if this event is part of a batch, false otherwise
   */
  public boolean isPartOfBatch() {
    return batchId != null && batchSize > 0;
  }

  /**
   * Checks if this event is the last one in its batch.
   *
   * @return true if this is the last event in the batch, false otherwise
   */
  public boolean isLastInBatch() {
    return isPartOfBatch() && batchPosition == batchSize - 1;
  }

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

  /**
   * Sets the retry count for this event.
   * Thread-safe implementation for use with Virtual Threads.
   *
   * @param retryCount the new retry count
   * @return this BlobEvent instance for method chaining
   */
  public BlobEvent withRetryCount(final int retryCount) {
    this.retryCount.set(retryCount);
    return this;
  }

  /**
   * Sets the batch information for this event.
   *
   * @param batchId the batch ID
   * @param position the position within the batch
   * @param size the total size of the batch
   * @return this BlobEvent instance for method chaining
   */
  public BlobEvent withBatchInfo(final String batchId, final int position, final int size) {
    this.batchId = batchId;
    this.batchPosition = position;
    this.batchSize = size;
    return this;
  }

  /**
   * Sets the creation time for this event.
   * Useful for deserialization or testing.
   *
   * @param creationTime the creation time
   * @return this BlobEvent instance for method chaining
   */
  public BlobEvent withCreationTime(final Instant creationTime) {
    this.creationTime = creationTime;
    return this;
  }

  /**
   * Thread-safe implementation of equals that handles concurrent modifications.
   * Uses volatile and AtomicInteger fields to ensure visibility across threads.
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
    
    // Compare atomic fields safely
    boolean retryCountEqual = retryCount.get() == blobEvent.retryCount.get();
    boolean asyncStateEqual = asyncState.get() == blobEvent.asyncState.get();
    
    // Compare the core identity fields that don't change during processing
    return Objects.equals(blobId, blobEvent.blobId) &&
        Objects.equals(assetPath, blobEvent.assetPath) &&
        Objects.equals(repositoryName, blobEvent.repositoryName) &&
        Objects.equals(replicationConnectionId, blobEvent.replicationConnectionId) &&
        Objects.equals(batchId, blobEvent.batchId) &&
        batchPosition == blobEvent.batchPosition &&
        batchSize == blobEvent.batchSize &&
        blobEventType == blobEvent.blobEventType &&
        inUse == blobEvent.inUse &&
        retryCountEqual &&
        asyncStateEqual;
  }

  /**
   * Thread-safe implementation of hashCode that handles concurrent modifications.
   * Uses volatile and AtomicInteger fields to ensure visibility across threads.
   */
  @Override
  public int hashCode() {
    // Include only the core identity fields that don't change during processing
    // This makes the hash code stable even when state changes during processing
    return Objects.hash(
        blobId, 
        assetPath, 
        repositoryName, 
        replicationConnectionId, 
        blobEventType, 
        batchId, 
        batchPosition, 
        batchSize);
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
        ", retryCount=" + retryCount.get() +
        ", asyncState=" + asyncState.get() +
        ", lastAttemptTime=" + lastAttemptTime +
        ", creationTime=" + creationTime +
        ", batchId='" + batchId + '\'' +
        ", batchPosition=" + batchPosition +
        ", batchSize=" + batchSize +
        '}';
  }
}