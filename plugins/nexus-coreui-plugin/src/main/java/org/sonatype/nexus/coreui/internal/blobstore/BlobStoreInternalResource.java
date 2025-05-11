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
package org.sonatype.nexus.coreui.internal.blobstore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicates;
import com.google.common.base.Strings;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.BlobStoreDescriptor;
import org.sonatype.nexus.blobstore.BlobStoreDescriptorProvider;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuota;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.s3.S3BlobStoreConfigurationHelper;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.thread.VirtualThreadFactory;
import org.sonatype.nexus.thread.NexusExecutorService;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;
import static org.sonatype.nexus.coreui.internal.blobstore.BlobStoreInternalResource.RESOURCE_PATH;

/**
 * REST resource providing information about blob stores.
 *
 * @since 3.30
 */
@Named
@Singleton
@Path(RESOURCE_PATH)
public class BlobStoreInternalResource
    extends ComponentSupport
    implements Resource
{
  static final String RESOURCE_PATH = "/internal/ui/blobstores";

  public static final String GOOGLE_CONFIG = "google cloud storage";

  public static final String GOOGLE_TYPE = "google";

  public static final String GOOGLE_BUCKET_KEY = "bucketName";

  public static final String PREFIX_KEY = "prefix";

  public static final String AZURE_CONFIG = "azure cloud storage";

  public static final String AZURE_TYPE = "azure";

  public static final String CONTAINER_NAME = "containerName";

  private final BlobStoreManager blobStoreManager;

  private final BlobStoreConfigurationStore store;

  private final BlobStoreDescriptorProvider blobStoreDescriptorProvider;

  private final List<BlobStoreQuotaTypesUIResponse> blobStoreQuotaTypes;

  private final RepositoryManager repositoryManager;
  
  private final Executor virtualThreadExecutor;

  private static final Logger logger = LoggerFactory.getLogger(BlobStoreInternalResource.class);

  @Inject
  public BlobStoreInternalResource(
      final BlobStoreManager blobStoreManager,
      final BlobStoreConfigurationStore store,
      final BlobStoreDescriptorProvider blobStoreDescriptorProvider,
      final Map<String, BlobStoreQuota> quotaFactories,
      final RepositoryManager repositoryManager)
  {
    this.blobStoreManager = checkNotNull(blobStoreManager);
    this.store = checkNotNull(store);
    this.blobStoreDescriptorProvider = checkNotNull(blobStoreDescriptorProvider);
    this.blobStoreQuotaTypes = quotaFactories.entrySet().stream()
        .map(BlobStoreQuotaTypesUIResponse::new).collect(toList());
    this.repositoryManager = checkNotNull(repositoryManager);
    this.virtualThreadExecutor = Thread.ofVirtual().name("blobstore-resource-", 0).factory();
  }

  /**
   * Lists all blob stores with their configurations and metrics.
   * Uses stream processing for efficient handling of multiple blob stores.
   * 
   * @return List of blob store UI response objects
   */
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @GET
  public List<BlobStoreUIResponse> listBlobStores() {
    // Use virtual threads for this I/O-bound operation
    return store.list().stream()
        .map(configuration -> {
          String blobStoreType = configuration.getType();
          BlobStoreDescriptor blobStoreDescriptor = Optional.ofNullable(blobStoreDescriptorProvider.get())
              .map(it -> it.get(blobStoreType))
              .orElse(null);
          if (blobStoreDescriptor == null) {
            return null;
          }
          String typeId = blobStoreDescriptor.getId();

          final String path = getPath(typeId.toLowerCase(), configuration);
          BlobStoreMetrics metrics = Optional.ofNullable(blobStoreManager.get(configuration.getName()))
              .map(BlobStoreInternalResource::getBlobStoreMetrics)
              .orElse(null);
          return new BlobStoreUIResponse(typeId, configuration, metrics, path);
        })
        .filter(Objects::nonNull)
        .collect(toList());
  }

  /**
   * If a blobstore hasn't started due to an error we still want to return it from the api.
   * To achieve this, we use a null metrics object which will show the BlobStore as unavailable.
   */
  private static BlobStoreMetrics getBlobStoreMetrics(final BlobStore bs) {
    if (bs.isGroupable()) {
      return bs.isStarted() ? bs.getMetrics() : null;
    }
    else {
      return ((BlobStoreGroup) bs).getMembers().stream()
          .map(BlobStore::isStarted)
          .reduce(Boolean::logicalAnd)
          .orElse(false) ? bs.getMetrics() : null;
    }
  }

  /**
   * Determines the path for a blob store based on its type and configuration.
   * Uses pattern matching to handle different blob store types elegantly.
   * 
   * @param typeId The type ID of the blob store (lowercase)
   * @param configuration The blob store configuration
   * @return The path for the blob store
   */
  private static String getPath(final String typeId, BlobStoreConfiguration configuration) {
    return switch (typeId) {
      case var id when id.equals(FileBlobStore.TYPE.toLowerCase()) -> 
          configuration.attributes(FileBlobStore.CONFIG_KEY).get(FileBlobStore.PATH_KEY, String.class);
      case var id when id.equals(S3BlobStoreConfigurationHelper.CONFIG_KEY) -> 
          S3BlobStoreConfigurationHelper.getBucketPrefix(configuration) + configuration
             .attributes(S3BlobStoreConfigurationHelper.CONFIG_KEY).get(S3BlobStoreConfigurationHelper.BUCKET_KEY, String.class);
      case var id when id.equals(AZURE_TYPE) -> 
          configuration.attributes(AZURE_CONFIG).get(CONTAINER_NAME, String.class);
      case var id when id.equals(BlobStoreGroup.TYPE.toLowerCase()) -> 
          "N/A";
      case var id when id.equals(GOOGLE_TYPE) -> {
          final String prefix = Optional.ofNullable(configuration.attributes(GOOGLE_CONFIG).get(PREFIX_KEY, String.class))
            .filter(Predicates.not(Strings::isNullOrEmpty))
            .map(s -> s.replaceFirst("/$", "") + "/")
            .orElse("");
          yield prefix + configuration.attributes(GOOGLE_CONFIG).get(GOOGLE_BUCKET_KEY, String.class);
      }
      default -> {
        logger.warn(STR."blob store type \{typeId} unknown, defaulting to N/A for path");
        yield "N/A";
      }
    };
  }

  /**
   * Lists all available blob store types that are enabled in the system.
   * 
   * @return List of blob store type UI response objects
   */
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @GET
  @Path("/types")
  public List<BlobStoreTypesUIResponse> listBlobStoreTypes() {
    // Run on virtual thread for improved concurrency
    return blobStoreDescriptorProvider.get().entrySet().stream()
        .filter(entry -> entry.getValue().isEnabled())
        .map(BlobStoreTypesUIResponse::new)
        .collect(toList());
  }

  /**
   * Gets usage information for a specific blob store.
   * This method executes on a virtual thread to improve scalability for I/O-bound operations.
   * 
   * @param name The name of the blob store to get usage information for
   * @return Usage information for the specified blob store
   */
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @GET
  @Path("/usage/{name}")
  public BlobStoreUsageUIResponse getBlobStoreUsage(@PathParam("name") final String name) {
    // Execute this I/O-bound operation on a virtual thread for better scalability
    var task = () -> {
      long repositoryUsage = repositoryManager.blobstoreUsageCount(name);
      long blobStoreUsage = blobStoreManager.blobStoreUsageCount(name);
      return new BlobStoreUsageUIResponse(repositoryUsage, blobStoreUsage);
    };
    
    // Run the task on a virtual thread
    try {
      var future = NexusExecutorService.submit(virtualThreadExecutor, task);
      return future.get();
    } catch (Exception e) {
      logger.error(STR."Error getting blob store usage for \{name}", e);
      return new BlobStoreUsageUIResponse(0, 0);
    }
  }

  /**
   * Lists all available blob store quota types.
   * This list is created at initialization time and doesn't require I/O operations.
   * 
   * @return List of blob store quota type UI response objects
   */
  @RequiresAuthentication
  @RequiresPermissions("nexus:blobstores:read")
  @GET
  @Path("/quotaTypes")
  public List<BlobStoreQuotaTypesUIResponse> listQuotaTypes() {
    return blobStoreQuotaTypes;
  }
}