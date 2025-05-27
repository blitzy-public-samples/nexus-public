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

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;

import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;

/**
 * @since 3.31
 */
public abstract class ReplicationIngesterSupport
    extends ComponentSupport
    implements ReplicationIngester
{
  private final BlobStoreManager blobStoreManager;

  private final ReplicationIngesterHelper replicationIngesterHelper;
  
  private final ReplicationVirtualThreadManager virtualThreadManager;

  @Inject
  public ReplicationIngesterSupport(final BlobStoreManager blobstoreManager,
                                    final ReplicationIngesterHelper replicationIngesterHelper,
                                    final ReplicationVirtualThreadManager virtualThreadManager) {
    this.blobStoreManager = checkNotNull(blobstoreManager);
    this.replicationIngesterHelper = checkNotNull(replicationIngesterHelper);
    this.virtualThreadManager = checkNotNull(virtualThreadManager);
  }

  /**
   * Extract asset attributes from properties, optimized for parallel processing.
   * 
   * @param props the properties containing asset attributes
   * @return a map of asset attributes
   */
  public Map<String, Object> extractAssetAttributesFromProperties(final Properties props) {
    return extractAttributesFromProperties(props, ASSET_ATTRIBUTES_PREFIX);
  }

  /**
   * Extract component attributes from properties, optimized for parallel processing.
   * 
   * @param props the properties containing component attributes
   * @return a map of component attributes
   */
  public Map<String, Object> extractComponentAttributesFromProperties(final Properties props) {
    return extractAttributesFromProperties(props, COMPONENT_ATTRIBUTES_PREFIX);
  }

  /**
   * Extract attributes from properties with the given prefix.
   * Uses a thread-safe map implementation to support parallel attribute extraction.
   * 
   * @param props the properties to extract from
   * @param prefix the prefix to filter by
   * @return a map of extracted attributes
   */
  private Map<String, Object> extractAttributesFromProperties(Properties props, final String prefix) {
    Map<String, Object> backingAssetAttributes = new ConcurrentHashMap<>();
    Set<String> keys = props.stringPropertyNames();
    for (String key : keys) {
      if (key.startsWith(prefix)) {
        String value = props.getProperty(key);
        key = key.substring(prefix.length());
        String[] flattenedAttributesParts = key.split("\\.");
        key = flattenedAttributesParts[0];

        unflattenAttributes(backingAssetAttributes, flattenedAttributesParts[0],
            Arrays.copyOfRange(flattenedAttributesParts, 1, flattenedAttributesParts.length),
            convertAttributeValue(key, value));
      }
    }
    return backingAssetAttributes;
  }

  @Override
  public void ingestBlob(final String blobIdString,
                         final String blobStoreId,
                         final String repositoryName,
                         final BlobEventType eventType)
      throws ReplicationIngestionException
  {
    // Process the blob ingestion asynchronously using Virtual Threads
    virtualThreadManager.executeReplicationTask(blobIdString, () -> {
      try {
        processIngestBlob(blobIdString, blobStoreId, repositoryName, eventType);
      }
      catch (ReplicationIngestionException e) {
        log.error("Failed to ingest blob {} for repository {} in blob store {}: {}",
            blobIdString, repositoryName, blobStoreId, e.getMessage(), e);
        throw e;
      }
      catch (Exception e) {
        log.error("Unexpected error ingesting blob {} for repository {} in blob store {}: {}",
            blobIdString, repositoryName, blobStoreId, e.getMessage(), e);
        throw new ReplicationIngestionException(
            String.format("Unexpected error ingesting blob %s for repository %s in blob store %s",
                blobIdString, repositoryName, blobStoreId), e);
      }
    });
  }
  
  /**
   * Process the blob ingestion synchronously. This method is called by the asynchronous wrapper.
   * 
   * @param blobIdString the blob ID string
   * @param blobStoreId the blob store ID
   * @param repositoryName the repository name
   * @param eventType the blob event type
   * @throws ReplicationIngestionException if ingestion fails
   */
  private void processIngestBlob(final String blobIdString,
                               final String blobStoreId,
                               final String repositoryName,
                               final BlobEventType eventType)
      throws ReplicationIngestionException
  {
    BlobId blobId = new BlobId(blobIdString);
    BlobStore blobStore = blobStoreManager.get(blobStoreId);
    validateBlobStore(blobStore, blobId, blobStoreId);

    Blob blob = blobStore.get(blobId);
    BlobAttributes blobAttributes = blobStore.getBlobAttributes(blobId);
    validateBlob(blob, blobAttributes, blobId);

    if (eventType.equals(BlobEventType.DELETED)) {
      log.info("Ingesting a delete for blob {} in repository {} and blob store {}.", blobIdString, repositoryName,
          blobStoreId);
      String path = blobAttributes.getHeaders().get(BLOB_NAME_HEADER);
      replicationIngesterHelper.deleteReplication(path, repositoryName);
      return;
    }

    // Extract attributes in parallel using CompletableFuture for better performance
    CompletableFuture<Map<String, Object>> assetAttributesFuture = CompletableFuture.supplyAsync(
        () -> extractAssetAttributesFromProperties(blobAttributes.getProperties()));
    
    CompletableFuture<Map<String, Object>> componentAttributesFuture = CompletableFuture.supplyAsync(
        () -> extractComponentAttributesFromProperties(blobAttributes.getProperties()));

    try {
      Map<String, Object> assetAttributes = assetAttributesFuture.get();
      Map<String, Object> componentAttributes = componentAttributesFuture.get();

      log.debug("Ingesting blob {} in repository {} and blob store {}.", blobIdString, repositoryName,
          blobStoreId);
      replicationIngesterHelper.replicate(blobStoreId, blob, assetAttributes, componentAttributes, repositoryName, blobStoreId);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ReplicationIngestionException(String
          .format("Interrupted while ingesting blob %s for repository %s in blobstore %s.", blobIdString, repositoryName,
              blobStoreId), e);
    }
    catch (ExecutionException e) {
      throw new ReplicationIngestionException(String
          .format("Error extracting attributes for blob %s for repository %s in blobstore %s.", blobIdString, repositoryName,
              blobStoreId), e.getCause());
    }
    catch (IOException e) {
      throw new ReplicationIngestionException(String
          .format("Could not ingest blob %s for repository %s in blobstore %s.", blobIdString, repositoryName,
              blobStoreId), e);
    }
  }

  /**
   * Validate that the blob store exists.
   * 
   * @param blobStore the blob store to validate
   * @param blobId the blob ID
   * @param blobStoreId the blob store ID
   * @throws ReplicationIngestionException if validation fails
   */
  private void validateBlobStore(final BlobStore blobStore, final BlobId blobId, final String blobStoreId) {
    if (blobStore == null) {
      throw new ReplicationIngestionException(
          String.format("Can't ingest blob %s, the blob store %s doesn't exist", blobId.asUniqueString(), blobStoreId));
    }
  }

  /**
   * Validate that the blob and its attributes exist.
   * 
   * @param blob the blob to validate
   * @param blobAttributes the blob attributes to validate
   * @param blobId the blob ID
   * @throws ReplicationIngestionException if validation fails
   */
  private void validateBlob(final Blob blob, final BlobAttributes blobAttributes, final BlobId blobId) {
    if (blob == null) {
      throw new ReplicationIngestionException(
          String.format("Can't ingest blob %s, the blob doesn't exist",  blobId.asUniqueString()));
    }

    if (blobAttributes == null) {
      throw new ReplicationIngestionException(
          String.format("Can't ingest blob %s, the blob doesn't have related attributes",  blobId.asUniqueString()));
    }
  }

  /**
   * Recursively unflattens a dot separated String in a Map.
   * Thread-safe implementation to support parallel attribute extraction.
   * 
   * @param backing the backing map
   * @param root the root key
   * @param children the child keys
   * @param value the value to set
   */
  protected void unflattenAttributes(Map<String, Object> backing, String root, String[] children, Object value) {
    if (children.length > 1) {
      if (backing.containsKey(root)) {
        unflattenAttributes((Map<String, Object>) backing.get(root), children[0],
            Arrays.copyOfRange(children, 1, children.length), value);
      }
      else {
        Map<String, Object> rootMap = new ConcurrentHashMap<>();
        backing.put(root, rootMap);
        unflattenAttributes(rootMap, children[0], Arrays.copyOfRange(children, 1, children.length), value);
      }
    }
    else {
      if (backing.containsKey(root)) {
        ((Map<String, Object>) backing.get(root)).put(children[0], value);
      }
      else {
        Map<String, Object> newEntry = new ConcurrentHashMap<>();
        newEntry.put(children[0], value);
        backing.put(root, newEntry);
      }
    }
  }

  /**
   * Convert an attribute value from string to its appropriate type.
   * 
   * @param key the attribute key
   * @param value the attribute value as string
   * @return the converted attribute value
   */
  protected Object convertAttributeValue(final String key, final String value) {
    if (value == null) {
      return null;
    }

    if (value.startsWith(VALUE_DATE_PREFIX)) {
      return new Date(Long.parseLong(value.substring(VALUE_DATE_PREFIX.length())));
    }
    else if (value.startsWith(VALUE_JODA_DATE_TIME_PREFIX)) {
      return new DateTime(Long.parseLong(value.substring(VALUE_JODA_DATE_TIME_PREFIX.length())));
    }
    else if (value.startsWith(VALUE_DATE_TIME_PREFIX)) {
      return new Date(Long.parseLong(value.substring(VALUE_DATE_TIME_PREFIX.length()))).toInstant().atOffset(
          ZoneOffset.UTC);
    }

    return value;
  }
}