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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.common.time.UTC;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.AttributeChangeSet;
import org.sonatype.nexus.repository.content.AttributeOperation;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAssetBlobAttach;
import org.sonatype.nexus.repository.content.fluent.FluentAssetBuilder;
import org.sonatype.nexus.repository.content.store.AssetBlobData;
import org.sonatype.nexus.repository.content.store.AssetData;
import org.sonatype.nexus.repository.content.store.AssetStore;
import org.sonatype.nexus.repository.proxy.ProxyFacetSupport;
import org.sonatype.nexus.repository.view.payloads.AttachableBlob;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.lang.StringTemplate.STR;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_IP_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.common.time.DateHelper.toOffsetDateTime;

/**
 * {@link FluentAssetBuilder} implementation.
 *
 * @since 3.24
 */
public class FluentAssetBuilderImpl
    implements FluentAssetBuilder, FluentAssetBlobAttach
{
  private final ContentFacetSupport facet;

  private final AssetStore<?> assetStore;

  private final AssetData assetData;

  private Supplier<Blob> blobSupplier;

  private Map<HashAlgorithm, HashCode> checksums;

  private Blob blob;

  private Map<String, Object> attributes;

  public FluentAssetBuilderImpl(final ContentFacetSupport facet, final AssetStore<?> assetStore, final String path) {
    this.facet = checkNotNull(facet);
    this.assetStore = checkNotNull(assetStore);
    assetData = new AssetData();
    assetData.setRepositoryId(facet.contentRepositoryId());
    assetData.setPath(checkNotNull(path));
    assetData.setKind(EMPTY);
  }

  public FluentAssetBuilderImpl(final ContentFacetSupport facet, final AssetStore<?> assetStore, final Asset asset) {
    this.facet = checkNotNull(facet);
    this.assetStore = checkNotNull(assetStore);
    this.assetData = (AssetData) checkNotNull(asset);
  }

  @Override
  public FluentAssetBuilder kind(final String kind) {
    assetData.setKind(checkNotNull(kind));
    return this;
  }

  @Override
  public FluentAssetBuilder component(final Component component) {
    assetData.setComponent(checkNotNull(component));
    return this;
  }

  @Override
  public FluentAssetBuilder blob(final TempBlob tempBlob) {
    // Using a lambda to create the blob supplier
    blobSupplier = () -> makePermanent(tempBlob);
    checksums = tempBlob.getHashes();
    return this;
  }

  @Override
  public FluentAssetBuilder blob(final Blob blob, final Map<HashAlgorithm, HashCode> checksums) {
    // Using a lambda to create the blob supplier
    blobSupplier = () -> blob;
    this.checksums = checksums;
    return this;
  }

  @Override
  public FluentAssetBuilder attributes(final String key, final Object value) {
    checkNotNull(key);
    checkNotNull(value);
    // Use computeIfAbsent from Java collections API for more concise initialization
    attributes = attributes == null ? new HashMap<>() : attributes;
    attributes.put(key, value);
    return this;
  }

  @Override
  public FluentAsset save() {
    // Apply attributes if present
    if (attributes != null) {
      assetData.attributes().backing().putAll(attributes);
    }
    
    // Get blob from supplier if available
    if (blobSupplier != null) {
      // Use pattern matching to get the asset for permission check
      Optional<Asset> existingAsset = findAsset();
      facet.checkAttachAllowed(existingAsset.orElse(assetData));
      blob = blobSupplier.get();
    }
    
    // Save the asset and return a fluent wrapper
    Asset asset = assetStore.save(this::findAsset, this::createAsset, this::updateAsset, this::postTransaction);
    return new FluentAssetImpl(facet, asset);
  }

  @Override
  public Optional<FluentAsset> find() {
    return findAsset().map(theAsset -> new FluentAssetImpl(facet, theAsset));
  }

  private Optional<Asset> findAsset() {
    return assetStore.readPath(facet.contentRepositoryId(), assetData.path());
  }

  private Asset createAsset() {
    // Set asset blob if available
    if (blob != null) {
      assetData.setAssetBlob(getOrCreateAssetBlob(blob, checksums));
    }

    // Set timestamps
    OffsetDateTime now = UTC.now();
    assetData.setLastUpdated(now);

    // Set download time if applicable
    if (ProxyFacetSupport.isDownloading()) {
      assetData.setLastDownloaded(now);
      // Using string template for logging if needed
      // System.out.println(STR."Creating asset \{assetData.path()} with download time \{now}");
    }

    // Create the asset in the store
    assetStore.createAsset(assetData);

    return assetData;
  }

  private Asset updateAsset(Asset asset) {
    updateAssetBlob(asset);
    updateAssetAttributes(asset);
    return asset;
  }

  private Asset updateAssetBlob(Asset asset) {
    if (blob != null) {
      // Use pattern matching for instanceof to simplify casting
      if (asset instanceof AssetData assetData) {
        assetData.setAssetBlob(getOrCreateAssetBlob(blob, checksums));
        facet.stores().assetStore.updateAssetBlobLink(asset);
        
        // Using string template for logging if needed
        // System.out.println(STR."Updated asset blob for \{assetData.path()}");
      }
    }
    return asset;
  }

  private Asset updateAssetAttributes(Asset asset) {
    if (attributes != null && !attributes.isEmpty()) {
      // Create a change set and use enhanced forEach with method reference pattern
      AttributeChangeSet changeSet = new AttributeChangeSet();
      attributes.forEach((key, value) -> changeSet.attributes(AttributeOperation.OVERLAY, key, value));
      
      // Use string template for more readable logging (if needed)
      // System.out.println(STR."Updating asset attributes for \{asset.path()} with \{attributes.size()} attributes");
      
      facet.stores()
          .assetStore
          .updateAssetAttributes(asset, changeSet);
    }
    return asset;
  }

  private void postTransaction(Asset asset) {
    if (attributes != null && !attributes.isEmpty()) {
      // Use pattern matching with ifPresent to make the code more readable
      asset.blob().ifPresent(assetBlob -> {
        // Use string template for logging if needed
        // System.out.println(STR."Attaching metadata for asset \{asset.path()} with blob ID \{assetBlob.blobRef().getBlobId()}");
        
        facet.blobMetadataStorage()
            .attach(
                facet.stores().blobStoreProvider.get(), 
                assetBlob.blobRef().getBlobId(), 
                null, 
                asset.attributes(), 
                assetBlob.checksums()
            );
      });
    }
  }

  private Blob makePermanent(final TempBlob tempBlob) {
    if (tempBlob instanceof AttachableBlob attachableBlob && !attachableBlob.isAttached()) {
      attachableBlob.markAttached();
      return tempBlob.getBlob();
    }

    Blob blob = tempBlob.getBlob();
    ImmutableMap.Builder<String, String> headerBuilder = ImmutableMap.builder();

    Map<String, String> tempHeaders = blob.getHeaders();
    headerBuilder.put(REPO_NAME_HEADER, tempHeaders.get(REPO_NAME_HEADER));
    headerBuilder.put(BLOB_NAME_HEADER, assetData.path());
    headerBuilder.put(CREATED_BY_HEADER, tempHeaders.get(CREATED_BY_HEADER));
    headerBuilder.put(CREATED_BY_IP_HEADER, tempHeaders.get(CREATED_BY_IP_HEADER));
    headerBuilder.put(CONTENT_TYPE_HEADER, facet.checkContentType(assetData, blob));

    Blob permanentBlob = facet.stores().blobStoreProvider.get().makeBlobPermanent(blob.getId(), headerBuilder.build());
    
    if (assetData.component() instanceof Component component) {
      facet.blobMetadataStorage().attach(
          facet.stores().blobStoreProvider.get(), 
          permanentBlob.getId(), 
          component.attributes(), 
          assetData.attributes(),
          assetData.blob().map(AssetBlob::checksums).orElse(null));
    } else {
      facet.blobMetadataStorage().attach(
          facet.stores().blobStoreProvider.get(), 
          permanentBlob.getId(), 
          null, 
          assetData.attributes(),
          assetData.blob().map(AssetBlob::checksums).orElse(null));
    }
    
    return permanentBlob;
  }

  private AssetBlob getOrCreateAssetBlob(final Blob blob, final Map<HashAlgorithm, HashCode> checksums) {
    BlobRef blobRef = blobRef(blob);
    return facet.stores().assetBlobStore.readAssetBlob(blobRef)
        .orElseGet(() -> createAssetBlob(blobRef, blob, checksums));
  }

  private AssetBlobData createAssetBlob(final BlobRef blobRef,
                                        final Blob blob,
                                        final Map<HashAlgorithm, HashCode> checksums)
  {
    // Extract metrics and headers using pattern matching for better readability
    BlobMetrics metrics = blob.getMetrics();
    Map<String, String> headers = blob.getHeaders();

    // Create and configure the asset blob
    AssetBlobData assetBlob = new AssetBlobData();
    assetBlob.setBlobRef(blobRef);
    assetBlob.setBlobSize(metrics.getContentSize());
    assetBlob.setContentType(headers.get(CONTENT_TYPE_HEADER));

    // Use enhanced stream operations to convert checksums
    assetBlob.setChecksums(checksums.entrySet().stream().collect(
        toImmutableMap(
            e -> e.getKey().name(),
            e -> e.getValue().toString())));

    // Set creation metadata
    assetBlob.setBlobCreated(toOffsetDateTime(metrics.getCreationTime()));
    assetBlob.setCreatedBy(headers.get(CREATED_BY_HEADER));
    assetBlob.setCreatedByIp(headers.get(CREATED_BY_IP_HEADER));

    // Store the asset blob
    facet.stores().assetBlobStore.createAssetBlob(assetBlob);

    return assetBlob;
  }

  private BlobRef blobRef(final Blob blob) {
    BlobId blobId = blob.getId();
    return new BlobRef(
        facet.nodeName(),
        facet.stores().blobStoreName,
        blobId.asUniqueString(),
        blobId.getBlobCreatedRef());
  }

  @Override
  public FluentAsset attach(final TempBlob tempBlob) {
    facet.checkAttachAllowed(assetData);
    return attachBlob(makePermanent(tempBlob), tempBlob.getHashes());
  }

  @Override
  public FluentAsset attach(final Blob blob, final Map<HashAlgorithm, HashCode> checksums) {
    facet.checkAttachAllowed(assetData);
    return attachBlob(blob, checksums);
  }

  @Override
  public FluentAsset attachIgnoringWritePolicy(final Blob blob, final Map<HashAlgorithm, HashCode> checksums) {
    return attachBlob(blob, checksums);
  }

  private FluentAsset attachBlob(final Blob blob, final Map<HashAlgorithm, HashCode> checksums) {
    // Store blob and checksums
    this.blob = blob;
    this.checksums = checksums;
    
    // Update the asset blob and return a fluent wrapper
    updateAssetBlob(assetData);
    
    // Using string template for logging if needed
    // System.out.println(STR."Attached blob to asset \{assetData.path()} with \{checksums.size()} checksums");
    
    return new FluentAssetImpl(facet, assetData);
  }
}