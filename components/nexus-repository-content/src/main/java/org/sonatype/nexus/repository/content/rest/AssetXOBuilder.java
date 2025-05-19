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
package org.sonatype.nexus.repository.content.rest;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.rest.api.AssetXO;
import org.sonatype.nexus.repository.rest.api.AssetXODescriptor;
import org.sonatype.nexus.repository.rest.api.RepositoryItemIDXO;

import static org.sonatype.nexus.repository.content.store.InternalIds.internalAssetId;
import static org.sonatype.nexus.repository.content.store.InternalIds.toExternalId;

/**
 * Builds asset transfer objects for REST APIs.
 *
 * @since 3.26
 */
public class AssetXOBuilder
{
  public static AssetXO fromAsset(
      final Asset asset,
      final Repository repository,
      final Map<String, AssetXODescriptor> assetDescriptors)
  {
    String externalId = toExternalId(internalAssetId(asset)).getValue();
    String format = repository.getFormat().getValue();
    
    // Use pattern matching to extract AssetBlob properties
    Map<String, String> checksum;
    String contentType;
    String uploader;
    String uploaderIp;
    long fileSize;
    Date lastModified;
    
    // Using pattern matching with Optional<AssetBlob>
    if (asset.blob() instanceof Optional<AssetBlob>(var blob) && blob != null) {
      // Using record pattern to extract properties from AssetBlob
      checksum = blob.checksums();
      contentType = blob.contentType();
      uploader = blob.createdBy().orElse(null);
      uploaderIp = blob.createdByIp().orElse(null);
      fileSize = blob.blobSize();
      
      // Optimized timestamp conversion using Java 21 features
      lastModified = Date.from(blob.blobCreated().toInstant());
    } else {
      checksum = Collections.emptyMap();
      contentType = null;
      uploader = null;
      uploaderIp = null;
      fileSize = 0L;
      lastModified = Date.from(asset.created().toInstant());
    }
    
    Date blobCreated = Date.from(asset.created().toInstant());

    return AssetXO.builder()
        .path(asset.path())
        .downloadUrl(repository.getUrl() + asset.path())
        .id(new RepositoryItemIDXO(repository.getName(), externalId).getValue())
        .repository(repository.getName())
        .checksum(checksum)
        .format(format)
        .contentType(contentType)
        .lastModified(lastModified)
        .lastDownloaded(getLastDownloaded(asset))
        .attributes(getExpandedAttributes(asset, format, assetDescriptors))
        .uploader(uploader)
        .uploaderIp(uploaderIp)
        .fileSize(fileSize)
        .blobCreated(blobCreated)
        .build();
  }

  @Nullable
  private static Date getLastDownloaded(final Asset asset) {
    // Using pattern matching with Optional for more concise code
    return switch (asset.lastDownloaded()) {
      case Optional<OffsetDateTime>(var lastDownloaded) when lastDownloaded != null -> 
          Date.from(lastDownloaded.toInstant());
      default -> null;
    };
  }

  private static Map<String, Object> getExpandedAttributes(
      final Asset asset,
      final String format,
      @Nullable final Map<String, AssetXODescriptor> assetDescriptors)
  {
    // Using pattern matching with Optional for more concise code
    Set<String> exposedAttributeKeys = switch (assetDescriptors) {
      case null -> Collections.emptySet();
      case var descriptors -> {
        AssetXODescriptor descriptor = descriptors.get(format);
        yield descriptor != null ? descriptor.listExposedAttributeKeys() : Collections.emptySet();
      }
    };

    Map<String, Object> exposedAttributes = asset.attributes(format).backing().entrySet()
      .stream()
      .filter(entry -> exposedAttributeKeys.contains(entry.getKey()))
      .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

    return Collections.singletonMap(format, exposedAttributes);
  }
}
