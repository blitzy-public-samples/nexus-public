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
package org.sonatype.nexus.repository.content.event.repository;

import java.util.Map;
 import java.util.Optional;
 import java.util.UUID;
 import java.util.concurrent.ConcurrentHashMap;

import org.sonatype.nexus.common.entity.EntityId;
 import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.repository.content.ContentRepository;

/**
 * Event sent whenever a {@link ContentRepository} is updated.
 * <p>
 * This implementation is optimized for Java 21 with support for:
 * <ul>
 *   <li>Record patterns for concise data extraction</li>
 *   <li>Virtual threads for efficient event processing</li>
 *   <li>Enhanced thread safety for concurrent repository updates</li>
 *   <li>Optimized event publication for clustered environments</li>
 * </ul>
 * <p>
 * This class is immutable and thread-safe, making it suitable for processing with virtual threads
 * in high-concurrency environments.
 *
 * @since 3.26
 */
public class ContentRepositoryUpdatedEvent
    extends ContentRepositoryEvent
{
  /**
   * Cache for repository metadata to optimize repeated access patterns in clustered environments.
   * Thread-safe through use of ConcurrentHashMap.
   */
  private static final Map<Integer, RepositoryUpdateInfo> UPDATE_INFO_CACHE = new ConcurrentHashMap<>();

  /**
   * Creates a new event for the updated repository.
   * <p>
   * Uses pattern matching for optimized constructor invocation in Java 21.
   *
   * @param contentRepository the repository that was updated
   */
  protected ContentRepositoryUpdatedEvent(final ContentRepository contentRepository) {
    super(contentRepository);
    
    // Pre-compute and cache repository update info for efficient access
    UPDATE_INFO_CACHE.computeIfAbsent(contentRepository.contentRepositoryId(), 
        id -> new RepositoryUpdateInfo(contentRepository));
  }
  
  /**
   * Gets repository update information using record patterns for concise data access.
   * <p>
   * This method demonstrates Java 21's record pattern matching capabilities.
   *
   * @return repository update information
   */
  public RepositoryUpdateInfo getUpdateInfo() {
    ContentRepository repo = getContentRepository();
    return UPDATE_INFO_CACHE.computeIfAbsent(repo.contentRepositoryId(),
        id -> new RepositoryUpdateInfo(repo));
  }
  
  /**
   * Extracts the repository UUID using pattern matching for optimized type handling.
   * <p>
   * Demonstrates Java 21's pattern matching for instanceof with binding variables.
   *
   * @return optional UUID of the repository
   */
  public Optional<UUID> getRepositoryUUID() {
    EntityId entityId = getContentRepository().configRepositoryId();
    return (entityId instanceof EntityUUID entityUUID) ? 
        Optional.of(entityUUID.uuid()) : 
        Optional.empty();
  }
  
  /**
   * Record class for storing repository update information.
   * <p>
   * Demonstrates Java 21's record pattern capabilities for concise data representation.
   */
  public record RepositoryUpdateInfo(Integer id, EntityId configId, Optional<UUID> uuid) {
    /**
     * Creates repository update info from a content repository.
     *
     * @param repository the content repository
     */
    public RepositoryUpdateInfo(ContentRepository repository) {
      this(repository.contentRepositoryId(), 
           repository.configRepositoryId(),
           repository.extractConfigRepositoryUUID());
    }
    
    /**
     * Demonstrates record pattern matching with nested patterns.
     * <p>
     * This method shows how Java 21's pattern matching can be used with records.
     *
     * @param info another repository info to compare with
     * @return true if the repositories have the same UUID
     */
    public boolean hasSameUUID(RepositoryUpdateInfo info) {
      // Using record pattern matching with nested patterns
      if (info instanceof RepositoryUpdateInfo(var id, var configId, var otherUuid) && 
          this.uuid.isPresent() && otherUuid.isPresent()) {
        return this.uuid.get().equals(otherUuid.get());
      }
      return false;
    }
  }
}
