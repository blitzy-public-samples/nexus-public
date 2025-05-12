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
package org.sonatype.nexus.blobstore.group;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.blobstore.MemoryBlobSession;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobSession;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.api.OperationMetrics;
import org.sonatype.nexus.blobstore.api.OperationType;
import org.sonatype.nexus.blobstore.api.PaginatedResult;
import org.sonatype.nexus.blobstore.api.RawObjectAccess;
import org.sonatype.nexus.blobstore.api.UnimplementedRawObjectAccess;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService;
import org.sonatype.nexus.blobstore.group.internal.BlobStoreGroupMetrics;
import org.sonatype.nexus.blobstore.group.internal.WriteToFirstMemberFillPolicy;
import org.sonatype.nexus.blobstore.metrics.MonitoringBlobStoreMetrics;
import org.sonatype.nexus.cache.CacheHelper;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.common.stateguard.Transitions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.HashCode;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.synchronizedList;
import static java.util.Collections.unmodifiableList;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static org.sonatype.nexus.blobstore.api.OperationType.DOWNLOAD;
import static org.sonatype.nexus.blobstore.api.OperationType.UPLOAD;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.FAILED;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.NEW;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.SHUTDOWN;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STOPPED;

/**
 * A {@link BlobStore} consisting of other blob stores.
 *
 * @since 3.14
 */
@Named(BlobStoreGroup.TYPE)
public class BlobStoreGroup
    extends StateGuardLifecycleSupport
    implements BlobStore
{
  private static final String RAW_OBJECTS_NOT_SUPPORTED = "Group BlobStore does not support raw objects";

  public static final String TYPE = "Group";

  public static final String CONFIG_KEY = "group";

  public static final String MEMBERS_KEY = "members";

  public static final String FILL_POLICY_KEY = "fillPolicy";

  public static final String FALLBACK_FILL_POLICY_TYPE = WriteToFirstMemberFillPolicy.TYPE;

  public static final String CACHE_NAME = "blobstore-group-blobIds";

  private final BlobStoreManager blobStoreManager;

  private final Map<String, Provider<FillPolicy>> fillPolicyProviders;

  private Provider<CacheHelper> cacheHelperProvider;

  private Time blobIdCacheTimeout;

  private AtomicReference<List<BlobStore>> members = new AtomicReference<>();

  @VisibleForTesting
  FillPolicy fillPolicy;

  private BlobStoreConfiguration blobStoreConfiguration;

  // cache of located blobs that have not been soft deleted
  private Cache<BlobId, String> locatedBlobs;
  
  // Virtual thread executor for parallel operations
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public BlobStoreGroup(
      final BlobStoreManager blobStoreManager,
      final Map<String, Provider<FillPolicy>> fillPolicyProviders,
      final Provider<CacheHelper> cacheHelperProvider,
      @Named("${nexus.blobstore.group.blobId.cache.timeToLive:-2d}") final Time blobIdCacheTimeout)
  {
    this.blobStoreManager = checkNotNull(blobStoreManager);
    this.fillPolicyProviders = checkNotNull(fillPolicyProviders);
    this.cacheHelperProvider = checkNotNull(cacheHelperProvider);
    this.blobIdCacheTimeout = checkNotNull(blobIdCacheTimeout);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public void init(final BlobStoreConfiguration configuration) {
    this.blobStoreConfiguration = configuration;
    initializeMembers();
    String fillPolicyName = BlobStoreGroupConfigurationHelper.fillPolicyName(configuration);
    if (fillPolicyProviders.containsKey(fillPolicyName)) {
      this.fillPolicy = fillPolicyProviders.get(fillPolicyName).get();
    }
    else {
      log.warn("Unable to find fill policy {} for Blob Store Group {}, using fill policy {}",
          fillPolicyName, configuration.getName(), FALLBACK_FILL_POLICY_TYPE);
      this.fillPolicy = fillPolicyProviders.get(FALLBACK_FILL_POLICY_TYPE).get();
    }
  }

  /**
   * Initialize the members list using thread-safe lazy initialization
   */
  private void initializeMembers() {
    // No initialization needed if already set
    if (members.get() != null) {
      return;
    }
    
    // Create the member list
    List<BlobStore> memberList = new ArrayList<>();
    for (String name : BlobStoreGroupConfigurationHelper.memberNames(blobStoreConfiguration)) {
      BlobStore blobStore = blobStoreManager.get(name);
      if (blobStore == null) {
        throw new BlobStoreException("Blob Store '" + name + "' not found", null);
      }
      memberList.add(blobStore);
    }
    
    // Use thread-safe list and set it atomically
    members.compareAndSet(null, synchronizedList(memberList));
  }

  @Override
  protected void doStart() throws Exception {
    // Configure cache with optimized settings for Virtual Threads
    locatedBlobs = cacheHelperProvider.get().maybeCreateCache(CACHE_NAME, getCacheConfiguration());
  }

  private MutableConfiguration<BlobId, String> getCacheConfiguration() {
    return new MutableConfiguration<BlobId, String>()
        .setStoreByValue(false)
        .setExpiryPolicyFactory(
            CreatedExpiryPolicy.factoryOf(new Duration(blobIdCacheTimeout.unit(), blobIdCacheTimeout.value())))
        .setManagementEnabled(true)
        .setStatisticsEnabled(true);
  }

  @Override
  protected void doStop() throws Exception {
    locatedBlobs = null;
  }

  @Override
  public BlobStoreConfiguration getBlobStoreConfiguration() {
    return this.blobStoreConfiguration;
  }

  @Override
  @Guarded(by = STARTED)
  public BlobSession<?> openSession() {
    return new MemoryBlobSession(this);
  }

  @Override
  @Guarded(by = STARTED)
  public Blob create(final InputStream blobData, final Map<String, String> headers) {
    return create(blobData, headers, null);
  }

  @Override
  @Guarded(by = STARTED)
  @MonitoringBlobStoreMetrics(operationType = UPLOAD)
  public Blob create(final InputStream blobData, final Map<String, String> headers, @Nullable final BlobId blobId) {
    return create(headers, target -> target.create(blobData, headers, blobId));
  }

  @Override
  @Guarded(by = STARTED)
  @MonitoringBlobStoreMetrics(operationType = UPLOAD)
  public Blob create(final Path sourceFile, final Map<String, String> headers, final long size, final HashCode sha1) {
    return create(headers, target -> target.create(sourceFile, headers, size, sha1));
  }

  private Blob create(final Map<String, String> headers, final CreateBlobFunction createBlobFunction) {
    BlobStore result = fillPolicy.chooseBlobStore(this, headers);
    if (result == null) {
      throw new BlobStoreException("Unable to find a member Blob Store of '" + this + "' for create", null);
    }
    
    // Use CompletableFuture with Virtual Threads for I/O operation
    CompletableFuture<Blob> future = CompletableFuture.supplyAsync(() -> {
      Blob blob = createBlobFunction.create(result);
      locatedBlobs.put(blob.getId(), result.getBlobStoreConfiguration().getName());
      return blob;
    }, virtualThreadExecutor);
    
    try {
      return future.join();
    } catch (Exception e) {
      if (e.getCause() instanceof BlobStoreException) {
        throw (BlobStoreException) e.getCause();
      }
      throw new BlobStoreException("Error creating blob", e);
    }
  }

  @Override
  public void createBlobAttributes(final BlobId blobId, Map<String, String> headers, final BlobMetrics metrics) {
    locate(blobId)
        .orElseThrow(() -> new BlobStoreException(
            "Unable to find a member Blob Store of '" + this + "' for create properties", null))
        .createBlobAttributes(blobId, headers, metrics);
  }

  @Override
  public BlobAttributes createBlobAttributesInstance(
      final BlobId blobId,
      final Map<String, String> headers,
      final BlobMetrics metrics)
  {
    BlobStore result = fillPolicy.chooseBlobStore(this, headers);
    if (result == null) {
      throw new BlobStoreException("Unable to find a member Blob Store of '" + this + "' for create properties", null);
    }
    return result.createBlobAttributesInstance(blobId, headers, metrics);
  }

  @Override
  @Guarded(by = STARTED)
  public Blob copy(final BlobId blobId, final Map<String, String> headers) {
    BlobStore target = locate(blobId)
        .orElseThrow(() -> new BlobStoreException("Unable to find blob", blobId));
    
    // Use CompletableFuture with Virtual Threads for I/O operation
    CompletableFuture<Blob> future = CompletableFuture.supplyAsync(() -> {
      Blob blob = target.copy(blobId, headers);
      locatedBlobs.put(blob.getId(), target.getBlobStoreConfiguration().getName());
      return blob;
    }, virtualThreadExecutor);
    
    try {
      return future.join();
    } catch (Exception e) {
      if (e.getCause() instanceof BlobStoreException) {
        throw (BlobStoreException) e.getCause();
      }
      throw new BlobStoreException("Error copying blob", e);
    }
  }

  @Nullable
  @Override
  @Guarded(by = STARTED)
  @MonitoringBlobStoreMetrics(operationType = DOWNLOAD)
  public Blob get(final BlobId blobId) {
    Optional<BlobStore> blobStoreOptional = locate(blobId);
    if (!blobStoreOptional.isPresent()) {
      return null;
    }
    
    // Use CompletableFuture with Virtual Threads for I/O operation
    CompletableFuture<Blob> future = CompletableFuture.supplyAsync(
        () -> blobStoreOptional.get().get(blobId),
        virtualThreadExecutor);
    
    try {
      return future.join();
    } catch (Exception e) {
      log.error("Error getting blob {}", blobId, e);
      return null;
    }
  }

  @Nullable
  @Override
  @Guarded(by = STARTED)
  @MonitoringBlobStoreMetrics(operationType = DOWNLOAD)
  public Blob get(final BlobId blobId, final boolean includeDeleted) {
    if (includeDeleted) {
      // check directly without using cache
      List<BlobStore> membersList = members.get();
      
      // Use CompletableFuture with Virtual Threads to search in parallel
      List<CompletableFuture<Blob>> futures = membersList.stream()
          .map(member -> CompletableFuture.supplyAsync(
              () -> member.exists(blobId) ? member.get(blobId, true) : null,
              virtualThreadExecutor))
          .collect(toList());
      
      // Return the first non-null result
      return futures.stream()
          .map(CompletableFuture::join)
          .filter(Objects::nonNull)
          .findAny()
          .orElse(null);
    }
    else {
      return get(blobId);
    }
  }

  @Override
  @Guarded(by = STARTED)
  public boolean delete(final BlobId blobId, final String reason) {
    locatedBlobs.remove(blobId);
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to find locations in parallel
    List<CompletableFuture<BlobStore>> locationFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            () -> member.exists(blobId) ? member : null,
            virtualThreadExecutor))
        .collect(toList());
    
    List<BlobStore> locations = locationFutures.stream()
        .map(CompletableFuture::join)
        .filter(Objects::nonNull)
        .collect(toList());

    if (!locations.isEmpty()) {
      // Use CompletableFuture with Virtual Threads to delete in parallel
      List<CompletableFuture<Boolean>> deleteFutures = locations.stream()
          .map(member -> CompletableFuture.supplyAsync(
              () -> member.delete(blobId, reason),
              virtualThreadExecutor))
          .collect(toList());
      
      return deleteFutures.stream()
          .map(CompletableFuture::join)
          .allMatch(Boolean::booleanValue);
    }
    else {
      return false;
    }
  }

  @Override
  @Guarded(by = STARTED)
  public boolean deleteHard(final BlobId blobId) {
    locatedBlobs.remove(blobId);
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to find locations in parallel
    List<CompletableFuture<BlobStore>> locationFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            () -> member.exists(blobId) ? member : null,
            virtualThreadExecutor))
        .collect(toList());
    
    List<BlobStore> locations = locationFutures.stream()
        .map(CompletableFuture::join)
        .filter(Objects::nonNull)
        .collect(toList());

    if (!locations.isEmpty()) {
      // Use CompletableFuture with Virtual Threads to delete in parallel
      List<CompletableFuture<Boolean>> deleteFutures = locations.stream()
          .map(member -> CompletableFuture.supplyAsync(
              () -> member.deleteHard(blobId),
              virtualThreadExecutor))
          .collect(toList());
      
      return deleteFutures.stream()
          .map(CompletableFuture::join)
          .allMatch(Boolean::booleanValue);
    }
    else {
      return false;
    }
  }

  @Override
  @Guarded(by = STARTED)
  public BlobStoreMetricsService<BlobStoreGroup> getMetricsService() {
    throw new UnsupportedOperationException("metrics service is not available at a group level");
  }

  @Override
  @Guarded(by = STARTED)
  public BlobStoreMetrics getMetrics() {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to get metrics in parallel
    List<CompletableFuture<BlobStoreMetrics>> metricsFutures = membersList.stream()
        .filter(BlobStore::isStarted)
        .map(member -> CompletableFuture.supplyAsync(
            member::getMetrics,
            virtualThreadExecutor))
        .collect(toList());
    
    List<BlobStoreMetrics> membersMetrics = metricsFutures.stream()
        .map(CompletableFuture::join)
        .collect(toList());
    
    return new BlobStoreGroupMetrics(membersMetrics);
  }

  @Override
  public Map<OperationType, OperationMetrics> getOperationMetricsByType() {
    Map<OperationType, OperationMetrics> result = new EnumMap<>(OperationType.class);
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to get metrics in parallel
    List<CompletableFuture<Map<OperationType, OperationMetrics>>> metricsFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            member::getOperationMetricsByType,
            virtualThreadExecutor))
        .collect(toList());
    
    List<Map<OperationType, OperationMetrics>> metrics = metricsFutures.stream()
        .map(CompletableFuture::join)
        .collect(toList());
    
    // Aggregate metrics
    for (Map<OperationType, OperationMetrics> metric : metrics) {
      for (Entry<OperationType, OperationMetrics> metricsEntry : metric.entrySet()) {
        OperationType type = metricsEntry.getKey();
        OperationMetrics operationMetrics = metricsEntry.getValue();
        OperationMetrics existingMetrics = result.get(type);
        if (existingMetrics != null) {
          OperationMetrics aggregatedMetrics = existingMetrics.add(operationMetrics);
          result.put(type, aggregatedMetrics);
        }
        else {
          result.put(type, operationMetrics);
        }
      }
    }
    return result;
  }

  @Override
  public Map<OperationType, OperationMetrics> getOperationMetricsDelta() {
    Map<OperationType, OperationMetrics> result = new EnumMap<>(OperationType.class);
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to get metrics in parallel
    List<CompletableFuture<Map<OperationType, OperationMetrics>>> metricsFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            member::getOperationMetricsDelta,
            virtualThreadExecutor))
        .collect(toList());
    
    List<Map<OperationType, OperationMetrics>> metrics = metricsFutures.stream()
        .map(CompletableFuture::join)
        .collect(toList());
    
    // Aggregate metrics
    for (Map<OperationType, OperationMetrics> metric : metrics) {
      for (Entry<OperationType, OperationMetrics> metricsEntry : metric.entrySet()) {
        OperationType type = metricsEntry.getKey();
        OperationMetrics operationMetrics = metricsEntry.getValue();
        OperationMetrics existingMetrics = result.get(type);
        if (existingMetrics != null) {
          OperationMetrics aggregatedMetrics = existingMetrics.add(operationMetrics);
          result.put(type, aggregatedMetrics);
        }
        else {
          result.put(type, operationMetrics);
        }
      }
    }
    return result;
  }

  @Override
  public void clearOperationMetrics() {
    // noop invoke the method on the members
  }

  @Override
  @Guarded(by = STARTED)
  public synchronized void compact(@Nullable final BlobStoreUsageChecker inUseChecker) {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to compact in parallel
    List<CompletableFuture<Void>> compactFutures = membersList.stream()
        .map(member -> CompletableFuture.runAsync(
            () -> member.compact(inUseChecker),
            virtualThreadExecutor))
        .collect(toList());
    
    // Wait for all compact operations to complete
    CompletableFuture.allOf(compactFutures.toArray(new CompletableFuture[0])).join();
  }

  @Override
  @Guarded(by = STARTED)
  public synchronized void deleteTempFiles(@Nullable final Integer daysOlderThan) {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to delete temp files in parallel
    List<CompletableFuture<Void>> deleteTempFutures = membersList.stream()
        .map(member -> CompletableFuture.runAsync(
            () -> member.deleteTempFiles(daysOlderThan),
            virtualThreadExecutor))
        .collect(toList());
    
    // Wait for all delete temp files operations to complete
    CompletableFuture.allOf(deleteTempFutures.toArray(new CompletableFuture[0])).join();
  }

  @Override
  public boolean undelete(
      @Nullable final BlobStoreUsageChecker inUseChecker,
      final BlobId blobId,
      final BlobAttributes attributes,
      final boolean isDryRun)
  {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to undelete in parallel
    List<CompletableFuture<Boolean>> undeleteFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            () -> member.undelete(inUseChecker, blobId, attributes, isDryRun),
            virtualThreadExecutor))
        .collect(toList());
    
    return undeleteFutures.stream()
        .map(CompletableFuture::join)
        .anyMatch(Boolean::booleanValue);
  }

  @Override
  public boolean isStorageAvailable() {
    return true;
  }

  @Override
  public boolean isGroupable() {
    return false;
  }

  @Override
  public boolean isWritable() {
    return false;
  }

  @Override
  public boolean isEmpty() {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to check emptiness in parallel
    List<CompletableFuture<Boolean>> emptyFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            member::isEmpty,
            virtualThreadExecutor))
        .collect(toList());
    
    return emptyFutures.stream()
        .map(CompletableFuture::join)
        .reduce(true, Boolean::logicalAnd);
  }

  /**
   * Permanently stops this blob store regardless of the current state, disallowing restarts.
   */
  @Override
  @Transitions(to = SHUTDOWN)
  public void shutdown() throws Exception {
    if (isStarted()) {
      doStop();
    }
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
  }

  @Override
  public boolean exists(final BlobId blobId) {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to check existence in parallel
    List<CompletableFuture<Boolean>> existsFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            () -> member.exists(blobId),
            virtualThreadExecutor))
        .collect(toList());
    
    return existsFutures.stream()
        .map(CompletableFuture::join)
        .anyMatch(Boolean::booleanValue);
  }

  @Override
  public boolean bytesExists(final BlobId blobId) {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to check bytes existence in parallel
    List<CompletableFuture<Boolean>> bytesExistsFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            () -> member.bytesExists(blobId),
            virtualThreadExecutor))
        .collect(toList());
    
    return bytesExistsFutures.stream()
        .map(CompletableFuture::join)
        .anyMatch(Boolean::booleanValue);
  }

  @Override
  public boolean isBlobEmpty(final BlobId blobId) {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to check if blob is empty in parallel
    List<CompletableFuture<Boolean>> isBlobEmptyFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            () -> member.isBlobEmpty(blobId),
            virtualThreadExecutor))
        .collect(toList());
    
    return isBlobEmptyFutures.stream()
        .map(CompletableFuture::join)
        .anyMatch(Boolean::booleanValue);
  }

  @Override
  @Guarded(by = {NEW, STOPPED, FAILED, SHUTDOWN})
  public void remove() {
    // no-op
  }

  @Override
  public Stream<BlobId> getBlobIdStream() {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to get blob IDs in parallel
    List<CompletableFuture<Stream<BlobId>>> blobIdStreamFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            member::getBlobIdStream,
            virtualThreadExecutor))
        .collect(toList());
    
    return blobIdStreamFutures.stream()
        .map(CompletableFuture::join)
        .flatMap(identity());
  }

  @Override
  public Stream<BlobId> getBlobIdUpdatedSinceStream(final java.time.Duration duration) {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to get updated blob IDs in parallel
    List<CompletableFuture<Stream<BlobId>>> updatedBlobIdStreamFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            () -> member.getBlobIdUpdatedSinceStream(duration),
            virtualThreadExecutor))
        .collect(toList());
    
    return updatedBlobIdStreamFutures.stream()
        .map(CompletableFuture::join)
        .flatMap(identity());
  }

  @Override
  public PaginatedResult<BlobId> getBlobIdUpdatedSinceStream(
      final String prefix,
      final OffsetDateTime fromDateTime,
      final OffsetDateTime toDateTime,
      @Nullable final String continuationToken,
      final int pageSize)
  {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<BlobId> getDirectPathBlobIdStream(final String prefix) {
    List<BlobStore> membersList = members.get();
    
    // Use CompletableFuture with Virtual Threads to get direct path blob IDs in parallel
    List<CompletableFuture<Stream<BlobId>>> directPathBlobIdStreamFutures = membersList.stream()
        .map(member -> CompletableFuture.supplyAsync(
            () -> member.getDirectPathBlobIdStream(prefix),
            virtualThreadExecutor))
        .collect(toList());
    
    return directPathBlobIdStreamFutures.stream()
        .map(CompletableFuture::join)
        .flatMap(identity());
  }

  @Nullable
  @Override
  public BlobAttributes getBlobAttributes(final BlobId blobId) {
    Optional<BlobStore> blobStoreOptional = locate(blobId);
    if (!blobStoreOptional.isPresent()) {
      return null;
    }
    
    // Use CompletableFuture with Virtual Threads for I/O operation
    CompletableFuture<BlobAttributes> future = CompletableFuture.supplyAsync(
        () -> blobStoreOptional.get().getBlobAttributes(blobId),
        virtualThreadExecutor);
    
    try {
      return future.join();
    } catch (Exception e) {
      log.error("Error getting blob attributes {}", blobId, e);
      return null;
    }
  }

  @Override
  public void setBlobAttributes(BlobId blobId, BlobAttributes blobAttributes) {
    locate(blobId).ifPresent(target -> {
      // Use CompletableFuture with Virtual Threads for I/O operation
      CompletableFuture.runAsync(
          () -> target.setBlobAttributes(blobId, blobAttributes),
          virtualThreadExecutor)
          .exceptionally(e -> {
            log.error("Error setting blob attributes {}", blobId, e);
            return null;
          });
    });
  }

  public List<BlobStore> getMembers() {
    return unmodifiableList(members.get());
  }

  @Override
  public RawObjectAccess getRawObjectAccess() {
    return new UnimplementedRawObjectAccess();
  }

  /**
   * Thread-safe implementation for locating a blob in member stores.
   */
  @VisibleForTesting
  Optional<BlobStore> locate(final BlobId blobId) {
    // Check the cache first
    String blobStoreName = locatedBlobs.get(blobId);
    if (blobStoreName != null) {
      log.trace("{} location was cached as {}", blobId, blobStoreName);
      return Optional.ofNullable(blobStoreManager.get(blobStoreName));
    }

    // Use CompletableFuture with Virtual Threads to search in parallel
    CompletableFuture<BlobStore> searchFuture = CompletableFuture.supplyAsync(
        () -> search(blobId),
        virtualThreadExecutor);
    
    try {
      BlobStore blobStore = searchFuture.join();
      if (blobStore != null && blobStore.isWritable()) {
        String memberName = blobStore.getBlobStoreConfiguration().getName();
        log.trace("Caching {} in member {}", blobId, memberName);
        locatedBlobs.put(blobId, memberName);
      }
      return Optional.ofNullable(blobStore);
    } catch (Exception e) {
      log.error("Error locating blob {}", blobId, e);
      return Optional.empty();
    }
  }

  private BlobStore search(BlobId blobId) {
    List<BlobStore> membersList = members.get();
    log.trace("Searching for {} in {}", blobId, membersList);
    
    // Sort members with writable ones first
    List<BlobStore> sortedMembers = membersList.stream()
        .sorted(Comparator.comparing(BlobStore::isWritable).reversed())
        .collect(toList());
    
    // Use ConcurrentHashMap for thread-safe result collection
    ConcurrentHashMap<BlobId, BlobStore> foundMap = new ConcurrentHashMap<>();
    
    // Use CompletableFuture with Virtual Threads to search in parallel
    List<CompletableFuture<Void>> searchFutures = sortedMembers.stream()
        .map(member -> CompletableFuture.runAsync(() -> {
          if (member.exists(blobId)) {
            foundMap.putIfAbsent(blobId, member);
          }
        }, virtualThreadExecutor))
        .collect(toList());
    
    // Wait for all search operations to complete
    CompletableFuture.allOf(searchFutures.toArray(new CompletableFuture[0])).join();
    
    return foundMap.get(blobId);
  }

  @Override
  public String toString() {
    String name = blobStoreConfiguration != null ? blobStoreConfiguration.getName() : null;
    return getClass().getSimpleName() + "{" +
        "name='" + name + "'," +
        "members='" + members.get() + '\'' +
        '}';
  }

  /**
   * Functional interface for caller delegation of BlobStore creation
   *
   * @since 3.14
   */
  @FunctionalInterface
  private interface CreateBlobFunction
  {
    Blob create(BlobStore blobStore);
  }
}