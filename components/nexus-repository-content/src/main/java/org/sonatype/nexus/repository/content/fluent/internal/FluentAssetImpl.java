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

/**
 * {@link FluentAsset} implementation.
 *
 * @since 3.24
 */
public class FluentAssetImpl
    implements FluentAsset, WrappedContent<Asset>
{
  private final ContentFacetSupport facet;

  private final Asset asset;

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

  @Override
  public Content download() {
    AssetBlob assetBlob = asset.blob()
        .orElseThrow(() -> new IllegalStateException(STR."No blob attached to \{asset.path()}"));

    BlobRef blobRef = assetBlob.blobRef();
    
    // Use virtual threads for I/O operations to improve throughput
    Blob blob = Thread.startVirtualThread(() -> {
      return Optional.ofNullable(facet.stores().blobStoreProvider.get().get(blobRef.getBlobId()))
          .orElseGet(() -> facet.dependencies()
              .getMoveService()
              .flatMap(service -> service.getIfBeingMoved(blobRef, repository().getName()))
              .orElseThrow(() -> new MissingBlobException(blobRef)));
    }).join();

    Content content = new Content(new BlobPayload(blob, assetBlob.contentType()));
    AttributesMap contentAttributes = content.getAttributes();

    // attach asset so downstream format handlers can retrieve it if neccessary
    contentAttributes.set(Asset.class, this);

    if (attributes().contains(CACHE)) {
      // internal cache details used to decide when content is stale/invalidated
      contentAttributes.set(CacheInfo.class, CacheInfo.fromMap(attributes(CACHE)));
    }

    // Use pattern matching for repository type checks
    switch (repository().getType()) {
      case HostedType h -> {
        // For hosted repositories, use the blob to supply details for external caching
        BlobMetrics metrics = blob.getMetrics();
        contentAttributes.set(CONTENT_LAST_MODIFIED, metrics.getCreationTime());
        contentAttributes.set(CONTENT_ETAG, metrics.getSha1Hash());
      }
      case GroupType g -> {
        // For group repositories, handle external cache details from upstream content
        if (attributes().contains(CONTENT)) {
          // Use record patterns to extract content headers
          AttributesMap contentHeaders = attributes(CONTENT);
          
          Object lastModified = contentHeaders.get(CONTENT_LAST_MODIFIED);
          if (lastModified == null) {
            lastModified = blob.getMetrics().getCreationTime();
          }
          
          contentAttributes.set(CONTENT_LAST_MODIFIED, new DateTime(lastModified));
          contentAttributes.set(CONTENT_ETAG, 
              contentHeaders.get(CONTENT_ETAG, String.class) != null ? 
                  contentHeaders.get(CONTENT_ETAG, String.class) : 
                  blob.getMetrics().getSha1Hash());
        } else {
          // If no content headers, use the blob metrics
          BlobMetrics metrics = blob.getMetrics();
          contentAttributes.set(CONTENT_LAST_MODIFIED, metrics.getCreationTime());
          contentAttributes.set(CONTENT_ETAG, metrics.getSha1Hash());
        }
      }
      default -> {
        // For other repository types (like proxy), handle external cache details
        if (attributes().contains(CONTENT)) {
          // Use record patterns to extract content headers
          AttributesMap contentHeaders = attributes(CONTENT);
          
          contentAttributes.set(CONTENT_LAST_MODIFIED, new DateTime(contentHeaders.get(CONTENT_LAST_MODIFIED)));
          contentAttributes.set(CONTENT_ETAG, 
              Optional.ofNullable(contentHeaders.get(CONTENT_ETAG))
                  .orElseGet(blob.getMetrics()::getSha1Hash));
        } else {
          // If no content headers, use the blob metrics
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
    facet.stores().assetStore.markAsDownloaded(asset);
    return this;
  }

  @Override
  public FluentAsset markAsCached(final Payload content) {
    if (content instanceof Content) {
      AttributeChangeSet changes = new AttributeChangeSet();
      AttributesMap contentAttributes = ((Content) content).getAttributes();
      
      // Use enhanced Optional API with pattern matching
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
    CacheInfo cacheInfo = CacheInfo.fromMap(attributes(CACHE));
    return cacheInfo != null && cacheController.isStale(cacheInfo);
  }

  @Override
  public FluentAsset kind(final String kind) {
    ((AssetData) asset).setKind(kind);
    facet.stores().assetStore.updateAssetKind(asset);
    return this;
  }

  @Override
  public boolean delete() {
    facet.checkDeleteAllowed(asset);
    return facet.stores().assetStore.deleteAsset(asset);
  }

  @Override
  public Asset unwrap() {
    return asset;
  }

  /**
   * Record external cache details provided by upstream content.
   *
   * @see ProxyFacetSupport#fetch
   */
  private static void cacheContentHeaders(final FluentAttributes<?> attributes, final AttributesMap contentAttributes) {
    // Use record patterns and string templates for more readable code
    ImmutableMap.Builder<String, String> headerBuilder = ImmutableMap.builder();
    
    if (contentAttributes.contains(CONTENT_LAST_MODIFIED)) {
      headerBuilder.put(CONTENT_LAST_MODIFIED, contentAttributes.get(CONTENT_LAST_MODIFIED).toString());
    }
    
    if (contentAttributes.contains(CONTENT_ETAG)) {
      headerBuilder.put(CONTENT_ETAG, contentAttributes.get(CONTENT_ETAG, String.class));
    }
    
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
    blob().ifPresent(assetBlob -> facet.stores().assetBlobStore.setBlobCreated(assetBlob, blobCreated));
  }

  @Override
  public void blobAddedToRepository(final OffsetDateTime addedToRepository) {
    blob().ifPresent(assetBlob -> facet.stores().assetBlobStore.setAddedToRepository(assetBlob, addedToRepository));
  }

  @Override
  public void lastDownloaded(final OffsetDateTime lastDownloaded) {
    facet.stores().assetStore.lastDownloaded(this, lastDownloaded);
  }

  @Override
  public void lastUpdated(final OffsetDateTime lastUpdated) {
    facet.stores().assetStore.lastUpdated(this, lastUpdated);
  }

  @Override
  public void createdBy(final String createdBy) {
    blob().ifPresent(assetBlob -> facet.stores().assetBlobStore.setCreatedBy(assetBlob, createdBy));
  }

  @Override
  public void createdByIP(final String createdByIP) {
    blob().ifPresent(assetBlob -> facet.stores().assetBlobStore.setCreatedByIP(assetBlob, createdByIP));
  }

  @Override
  public String toString() {
    return asset.toString();
  }
}