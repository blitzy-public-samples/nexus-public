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
package org.sonatype.nexus.repository.content.fluent.internal;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.MissingBlobException;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.AttributeChangeSet;
import org.sonatype.nexus.repository.content.AttributeOperation;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAttributes;
import org.sonatype.nexus.repository.content.store.AssetData;
import org.sonatype.nexus.repository.content.store.WrappedContent;
import org.sonatype.nexus.repository.proxy.ProxyFacetSupport;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.BlobPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.repository.cache.CacheInfo.CACHE;
import static org.sonatype.nexus.repository.cache.CacheInfo.CACHE_TOKEN;
import static org.sonatype.nexus.repository.cache.CacheInfo.INVALIDATED;
import static org.sonatype.nexus.repository.content.AttributeOperation.OVERLAY;
import static org.sonatype.nexus.repository.view.Content.CONTENT;
import static org.sonatype.nexus.repository.view.Content.CONTENT_ETAG;
import static org.sonatype.nexus.repository.view.Content.CONTENT_LAST_MODIFIED;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FluentAsset} implementation.
 *
 * @since 3.24
 */
public class FluentAssetImpl
    implements FluentAsset, WrappedContent<Asset>
{
  private static final Logger log = LoggerFactory.getLogger(FluentAssetImpl.class);
  
  private final ContentFacetSupport facet;

  private final Asset asset;
  
  // Virtual Thread executor for I/O-bound operations
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  public FluentAssetImpl(final ContentFacetSupport facet, final Asset asset) {
    this.facet = checkNotNull(facet);
    this.asset = checkNotNull(asset);
  }

  @Override
  public Repository repository() {
    return facet.repository();
  }

  @Override
  public String path() {
    return asset.path();
  }

  @Override
  public String kind() {
    return asset.kind();
  }

  @Override
  public Optional<Component> component() {
    return asset.component();
  }

  @Override
  public Optional<AssetBlob> blob() {
    return asset.blob();
  }

  @Override
  public boolean hasBlob() {
    return asset.hasBlob();
  }

  @Override
  public Optional<OffsetDateTime> lastDownloaded() {
    return asset.lastDownloaded();
  }

  @Override
  public String blobStoreName() {
    return asset.blobStoreName();
  }

  @Override
  public long assetBlobSize() {
    return asset.assetBlobSize();
  }

  @Override
  public NestedAttributesMap attributes() {
    return asset.attributes();
  }

  @Override
  public OffsetDateTime created() {
    return asset.created();
  }

  @Override
  public void created(final OffsetDateTime lastUpdated) {
    facet.stores().assetStore.created(this, lastUpdated);
  }

  @Override
  public OffsetDateTime lastUpdated() {
    return asset.lastUpdated();
  }

  @Override
  public FluentAsset attributes(final AttributeOperation change, final String key, final Object value) {
    facet.stores().assetStore.updateAssetAttributes(asset, new AttributeChangeSet(change, key, value));
    asset.blob()
        .ifPresent(blob -> facet.blobMetadataStorage()
            .attach(facet.stores().blobStoreProvider.get(), blob.blobRef().getBlobId(), null, asset.attributes(),
                asset.blob().get().checksums()));
    return this;
  }

  @Override
  public FluentAsset attributes(final AttributeChangeSet changes) {
    facet.stores().assetStore.updateAssetAttributes(asset, changes);
    asset.blob()
        .ifPresent(blob -> facet.blobMetadataStorage()
            .attach(facet.stores().blobStoreProvider.get(), blob.blobRef().getBlobId(), null, asset.attributes(),
                blob.checksums()));
    return this;
  }

  @Override
  public FluentAsset attach(final TempBlob blob) {
    return new FluentAssetBuilderImpl(facet, facet.stores().assetStore, asset).attach(blob);
  }

  @Override
  public FluentAsset attach(final Blob blob, final Map<HashAlgorithm, HashCode> checksums) {
    return new FluentAssetBuilderImpl(facet, facet.stores().assetStore, asset)
        .attach(blob, checksums);
  }

  @Override
  public FluentAsset attachIgnoringWritePolicy(final Blob blob, final Map<HashAlgorithm, HashCode> checksums) {
    return new FluentAssetBuilderImpl(facet, facet.stores().assetStore, asset)
        .attachIgnoringWritePolicy(blob, checksums);
  }

  /**
   * Record for Content headers to enable pattern matching in Java 21
   * @since 3.41
   */
  private record ContentHeaders(Object lastModified, String etag) {
    /**
     * Creates ContentHeaders from AttributesMap
     */
    static Optional<ContentHeaders> from(AttributesMap contentAttributes) {
      if (contentAttributes.contains(CONTENT_LAST_MODIFIED) || contentAttributes.contains(CONTENT_ETAG)) {
        return Optional.of(new ContentHeaders(
            contentAttributes.get(CONTENT_LAST_MODIFIED),
            contentAttributes.get(CONTENT_ETAG, String.class)));
      }
      return Optional.empty();
    }
  }

  @Override
  public Content download() {
    AssetBlob assetBlob = asset.blob()
        .orElseThrow(() -> new IllegalStateException(STR."No blob attached to \{asset.path()}"));

    BlobRef blobRef = assetBlob.blobRef();
    
    // Use Virtual Threads for I/O-bound blob retrieval
    Blob blob = VIRTUAL_THREAD_EXECUTOR.submit(() -> 
        Optional.ofNullable(facet.stores().blobStoreProvider.get().get(blobRef.getBlobId()))
            .orElseGet(() -> facet.dependencies()
                .getMoveService()
                .flatMap(service -> Optional.ofNullable(service.getIfBeingMoved(blobRef, repository().getName())))
                .orElseThrow(() -> new MissingBlobException(blobRef))))
        .join();

    Content content = new Content(new BlobPayload(blob, assetBlob.contentType()));
    AttributesMap contentAttributes = content.getAttributes();

    // attach asset so downstream format handlers can retrieve it if necessary
    contentAttributes.set(Asset.class, this);

    // Use enhanced Optional API for cache info handling
    attributes().getOptional(CACHE)
        .map(CacheInfo::fromMap)
        .ifPresent(cacheInfo -> contentAttributes.set(CacheInfo.class, cacheInfo));

    // Use pattern matching for repository type checks
    var repositoryType = repository().getType();
    switch (repositoryType) {
      case HostedType _ -> {
        // For hosted repositories, use the blob to supply details for external caching
        BlobMetrics metrics = blob.getMetrics();
        contentAttributes.set(CONTENT_LAST_MODIFIED, metrics.getCreationTime());
        contentAttributes.set(CONTENT_ETAG, metrics.getSha1Hash());
      }
      case GroupType _ -> {
        // For group repositories, handle content headers with record patterns
        if (attributes().contains(CONTENT)) {
          // Use record patterns to extract content headers
          AttributesMap contentHeaders = attributes(CONTENT);
          ContentHeaders.from(contentHeaders).ifPresentOrElse(
              headers -> {
                // Use pattern matching to extract values
                if (headers instanceof ContentHeaders(var lastModified, var etag)) {
                  contentAttributes.set(CONTENT_LAST_MODIFIED, new DateTime(lastModified));
                  Optional.ofNullable(etag)
                      .ifPresentOrElse(
                          e -> contentAttributes.set(CONTENT_ETAG, e),
                          () -> contentAttributes.set(CONTENT_ETAG, blob.getMetrics().getSha1Hash()));
                }
              },
              () -> {
                // Fallback to blob metrics if no content headers
                BlobMetrics metrics = blob.getMetrics();
                contentAttributes.set(CONTENT_LAST_MODIFIED, metrics.getCreationTime());
                contentAttributes.set(CONTENT_ETAG, metrics.getSha1Hash());
              });
        } else {
          // No content headers, use blob metrics
          BlobMetrics metrics = blob.getMetrics();
          contentAttributes.set(CONTENT_LAST_MODIFIED, metrics.getCreationTime());
          contentAttributes.set(CONTENT_ETAG, metrics.getSha1Hash());
        }
      }
      default -> {
        // For other repository types (proxy, etc.)
        if (attributes().contains(CONTENT)) {
          // External cache details previously recorded from upstream content
          AttributesMap contentHeaders = attributes(CONTENT);
          
          Object lastModified = contentHeaders.get(CONTENT_LAST_MODIFIED);
          if (lastModified == null && (repository().getType() instanceof GroupType)) {
            lastModified = blob.getMetrics().getCreationTime();
          }
          
          contentAttributes.set(CONTENT_LAST_MODIFIED, new DateTime(lastModified));
          contentAttributes.set(CONTENT_ETAG, Optional.ofNullable(contentHeaders.get(CONTENT_ETAG))
              .orElseGet(blob.getMetrics()::getSha1Hash));
        } else {
          // Otherwise use the blob to supply details for external caching
          BlobMetrics metrics = blob.getMetrics();
          contentAttributes.set(CONTENT_LAST_MODIFIED, metrics.getCreationTime());
          contentAttributes.set(CONTENT_ETAG, metrics.getSha1Hash());
        }
      }
    }

    return content;
  }
  
  @Override
  public FluentAsset markAsDownloaded() {
    // Use Virtual Threads for I/O-bound operations
    VIRTUAL_THREAD_EXECUTOR.submit(() -> {
      facet.stores().assetStore.markAsDownloaded(asset);
      return null;
    }).join();
    return this;
  }

  @Override
  public FluentAsset markAsCached(final Payload content) {
    if (content instanceof Content) {
      AttributeChangeSet changes = new AttributeChangeSet();
      AttributesMap contentAttributes = ((Content) content).getAttributes();
      
      // Use enhanced Optional API for CacheInfo handling
      Optional.ofNullable(contentAttributes.get(CacheInfo.class))
          .ifPresent(cacheInfo -> markAsCached(changes, cacheInfo));
      
      cacheContentHeaders(changes, contentAttributes);
      attributes(changes);
    }
    return this;
  }

  @Override
  public FluentAsset markAsCached(final CacheInfo cacheInfo) {
    return markAsCached(this, cacheInfo);
  }

  private static <A extends FluentAttributes<A>> A markAsCached(final A attributes, final CacheInfo cacheInfo) {
    return attributes.withAttribute(CACHE, cacheInfo.toMap());
  }

  @Override
  public FluentAsset markAsStale() {
    return attributes(OVERLAY, CACHE, ImmutableMap.of(CACHE_TOKEN, INVALIDATED));
  }

  @Override
  public boolean isStale(final CacheController cacheController) {
    // Use enhanced Optional API for CacheInfo handling
    return Optional.ofNullable(CacheInfo.fromMap(attributes(CACHE)))
        .map(cacheInfo -> cacheController.isStale(cacheInfo))
        .orElse(false);
  }
  
  /**
   * Record cache content headers using Java 21 String Templates for more readable logging.
   * This method is used to cache external details provided by upstream content.
   *
   * @see ProxyFacetSupport#fetch
   */
  private static void cacheContentHeaders(final FluentAttributes<?> attributes, final AttributesMap contentAttributes) {
    ImmutableMap.Builder<String, String> headerBuilder = ImmutableMap.builder();
    
    // Use enhanced Optional API for content headers
    Optional.ofNullable(contentAttributes.get(CONTENT_LAST_MODIFIED))
        .ifPresent(lastModified -> {
          String value = lastModified.toString();
          headerBuilder.put(CONTENT_LAST_MODIFIED, value);
          // Use String Templates for logging if needed
          if (log.isDebugEnabled()) {
            log.debug(STR."Caching last-modified header: \{value}");
          }
        });
        
    Optional.ofNullable(contentAttributes.get(CONTENT_ETAG, String.class))
        .ifPresent(etag -> {
          headerBuilder.put(CONTENT_ETAG, etag);
          // Use String Templates for logging if needed
          if (log.isDebugEnabled()) {
            log.debug(STR."Caching etag header: \{etag}");
          }
        });
    
    Map<String, String> contentHeaders = headerBuilder.build();
    if (!contentHeaders.isEmpty()) {
      attributes.withAttribute(CONTENT, contentHeaders);
    }
    else {
      attributes.withoutAttribute(CONTENT);
    }
  }
  
  @Override
  public void blobCreated(final OffsetDateTime blobCreated) {
    // Use Virtual Threads for I/O-bound operations
    VIRTUAL_THREAD_EXECUTOR.submit(() -> {
      blob().ifPresent(assetBlob -> facet.stores().assetBlobStore.setBlobCreated(assetBlob, blobCreated));
      return null;
    }).join();
  }

  @Override
  public void blobAddedToRepository(final OffsetDateTime addedToRepository) {
    // Use Virtual Threads for I/O-bound operations
    VIRTUAL_THREAD_EXECUTOR.submit(() -> {
      blob().ifPresent(assetBlob -> facet.stores().assetBlobStore.setAddedToRepository(assetBlob, addedToRepository));
      return null;
    }).join();
  }

  @Override
  public void lastDownloaded(final OffsetDateTime lastDownloaded) {
    // Use Virtual Threads for I/O-bound operations
    VIRTUAL_THREAD_EXECUTOR.submit(() -> {
      facet.stores().assetStore.lastDownloaded(this, lastDownloaded);
      return null;
    }).join();
  }

  @Override
  public void lastUpdated(final OffsetDateTime lastUpdated) {
    // Use Virtual Threads for I/O-bound operations
    VIRTUAL_THREAD_EXECUTOR.submit(() -> {
      facet.stores().assetStore.lastUpdated(this, lastUpdated);
      return null;
    }).join();
  }

  @Override
  public void createdBy(final String createdBy) {
    // Use Virtual Threads for I/O-bound operations
    VIRTUAL_THREAD_EXECUTOR.submit(() -> {
      blob().ifPresent(assetBlob -> facet.stores().assetBlobStore.setCreatedBy(assetBlob, createdBy));
      return null;
    }).join();
    
    // Use String Templates for logging if needed
    if (log.isDebugEnabled()) {
      log.debug(STR."Asset \{asset.path()} created by \{createdBy}");
    }
  }

  @Override
  public void createdByIP(final String createdByIP) {
    // Use Virtual Threads for I/O-bound operations
    VIRTUAL_THREAD_EXECUTOR.submit(() -> {
      blob().ifPresent(assetBlob -> facet.stores().assetBlobStore.setCreatedByIP(assetBlob, createdByIP));
      return null;
    }).join();
    
    // Use String Templates for logging if needed
    if (log.isDebugEnabled()) {
      log.debug(STR."Asset \{asset.path()} created from IP \{createdByIP}");
    }
  }

  @Override
  public String toString() {
    return asset.toString();
  }