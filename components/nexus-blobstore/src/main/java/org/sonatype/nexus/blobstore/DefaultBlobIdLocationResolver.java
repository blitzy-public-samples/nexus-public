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
package org.sonatype.nexus.blobstore;

import java.time.OffsetDateTime;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.common.time.UTC;

import static java.util.UUID.randomUUID;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.DIRECT_PATH_BLOB_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.TEMPORARY_BLOB_HEADER;
import static org.sonatype.nexus.common.app.FeatureFlags.DATE_BASED_BLOBSTORE_LAYOUT_ENABLED_NAMED;

/**
 * Default {@link BlobIdLocationResolver}.
 * 
 * This implementation is optimized for Java 21 Virtual Threads, ensuring efficient
 * location resolution without blocking operations.
 *
 * @since 3.8
 */
@Named
public class DefaultBlobIdLocationResolver
    implements BlobIdLocationResolver
{
  /**
   * Prefix indicating a temporary blob.
   */
  public static final String TEMPORARY_BLOB_ID_PREFIX = "tmp$";

  /**
   * Prefix indicating a direct-path blob.
   *
   * @see org.sonatype.nexus.blobstore.api.BlobStore#DIRECT_PATH_BLOB_HEADER
   */
  public static final String DIRECT_PATH_BLOB_ID_PREFIX = "path$";

  protected final LocationStrategy volumeChapterLocationStrategy;

  protected final LocationStrategy temporaryLocationStrategy;

  protected final LocationStrategy directLocationStrategy;

  protected final LocationStrategy dateBasedLocationStrategy;

  private final boolean dateBasedLayoutEnabled;

  @Inject
  public DefaultBlobIdLocationResolver(
      @Named(DATE_BASED_BLOBSTORE_LAYOUT_ENABLED_NAMED) final boolean dateBasedLayoutEnabled)
  {
    this.dateBasedLayoutEnabled = dateBasedLayoutEnabled;
    this.volumeChapterLocationStrategy = new VolumeChapterLocationStrategy();
    this.temporaryLocationStrategy = new TemporaryLocationStrategy();
    this.directLocationStrategy = new DirectPathLocationStrategy();
    this.dateBasedLocationStrategy = new DateBasedLocationStrategy();
  }

  /**
   * Gets the location for the given blob ID using pattern matching to select the appropriate strategy.
   * This implementation is optimized for Virtual Threads, avoiding blocking operations.
   */
  @Override
  public String getLocation(final BlobId id) {
    String uniqueString = id.asUniqueString();
    
    // Using pattern matching for switch to efficiently select the appropriate strategy
    return switch (uniqueString) {
      case String s when s.startsWith(TEMPORARY_BLOB_ID_PREFIX) -> 
          temporaryLocationStrategy.location(id);
      case String s when s.startsWith(DIRECT_PATH_BLOB_ID_PREFIX) -> 
          directLocationStrategy.location(id);
      default -> getBlobIdLocation(id);
    };
  }

  /**
   * Determines the location strategy based on whether the blob has a creation reference.
   * Optimized for Virtual Thread execution with pattern matching.
   */
  private String getBlobIdLocation(final BlobId blobId) {
    // Using pattern matching to select the appropriate strategy based on creation reference
    return switch (blobId) {
      case BlobId b when b.getBlobCreatedRef() != null -> 
          dateBasedLocationStrategy.location(blobId);
      default -> 
          volumeChapterLocationStrategy.location(blobId);
    };
  }

  @Override
  public String getTemporaryLocation(final BlobId id) {
    return temporaryLocationStrategy.location(id);
  }

  /**
   * Creates a BlobId from headers, optimized for Virtual Thread environments.
   * Timestamp creation is efficient and non-blocking when used with Virtual Threads.
   */
  @Override
  public BlobId fromHeaders(final Map<String, String> headers) {
    // Efficient timestamp creation for Virtual Thread context
    OffsetDateTime blobCreatedRef = dateBasedLayoutEnabled ? UTC.now() : null;
    
    // Using pattern matching for switch to create the appropriate BlobId
    return switch (headers) {
      case Map<String, String> h when h.containsKey(TEMPORARY_BLOB_HEADER) -> 
          new BlobId(TEMPORARY_BLOB_ID_PREFIX + randomUUID(), blobCreatedRef);
      case Map<String, String> h when h.containsKey(DIRECT_PATH_BLOB_HEADER) -> 
          new BlobId(DIRECT_PATH_BLOB_ID_PREFIX + h.get(BLOB_NAME_HEADER), blobCreatedRef);
      default -> 
          new BlobId(randomUUID().toString(), blobCreatedRef);
    };
  }
}