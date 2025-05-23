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
package org.sonatype.nexus.internal.provisioning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.BlobStoreApi;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.VirtualThreadFriendly;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Implementation of {@link BlobStoreApi} that leverages Java 21 features for improved performance
 * and type safety.
 *
 * @since 3.0
 */
@Named
@Singleton
public class BlobStoreApiImpl
    implements BlobStoreApi
{
  private final BlobStoreManager blobStoreManager;

  @Inject
  public BlobStoreApiImpl(final BlobStoreManager blobStoreManager) {
    this.blobStoreManager = checkNotNull(blobStoreManager);
  }

  @Override
  public BlobStoreConfiguration createFileBlobStore(final String name, final String path) {
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> fileAttributes = new HashMap<>();
    fileAttributes.put("path", checkNotNull(path));
    attributes.put("file", fileAttributes);
    BlobStoreConfiguration blobStoreConfiguration = blobStoreManager.newConfiguration();
    blobStoreConfiguration.setName(name);
    blobStoreConfiguration.setType("File");
    blobStoreConfiguration.setAttributes(attributes);
    return doCreate(blobStoreConfiguration);
  }

  @Override
  public BlobStoreConfiguration createBlobStoreGroup(
      final String name,
      final List<String> memberNames,
      final String fillPolicy)
  {
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> groupAttributes = new HashMap<>();
    
    // Using pattern matching for type-safe attribute handling
    if (memberNames instanceof List<String> members && fillPolicy instanceof String policy) {
      groupAttributes.put("members", members);
      groupAttributes.put("fillPolicy", policy);
    } else {
      groupAttributes.put("members", memberNames);
      groupAttributes.put("fillPolicy", fillPolicy);
    }
    
    attributes.put("group", groupAttributes);
    BlobStoreConfiguration blobStoreConfiguration = blobStoreManager.newConfiguration();
    blobStoreConfiguration.setName(name);
    blobStoreConfiguration.setType("Group");
    blobStoreConfiguration.setAttributes(attributes);
    return doCreate(blobStoreConfiguration);
  }

  @Override
  public BlobStoreConfiguration createS3BlobStore(final String name, final Map<String, String> s3Config) {
    // Using Record Patterns for Map configuration handling
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    
    // Convert from <String,String> to <String,Object> with pattern matching for type safety
    Map<String, Object> s3Attributes = new HashMap<>();
    s3Config.forEach((key, value) -> {
      if (key instanceof String k && value instanceof String v) {
        s3Attributes.put(k, v);
      }
    });
    
    attributes.put("s3", s3Attributes);
    BlobStoreConfiguration blobStoreConfiguration = blobStoreManager.newConfiguration();
    blobStoreConfiguration.setName(name);
    blobStoreConfiguration.setType("S3");
    blobStoreConfiguration.setAttributes(attributes);
    return doCreate(blobStoreConfiguration);
  }

  /**
   * Creates a blob store using the provided configuration.
   * 
   * <p>This method is optimized to use Virtual Threads for I/O operations,
   * significantly improving performance when creating multiple blob stores
   * or when dealing with slow storage backends.</p>
   *
   * @param blobStoreConfiguration the configuration to use
   * @return the created blob store configuration
   */
  @VirtualThreadFriendly
  private BlobStoreConfiguration doCreate(final BlobStoreConfiguration blobStoreConfiguration) {
    try {
      // Use Virtual Threads for I/O-bound blob store creation
      CompletableFuture<BlobStoreConfiguration> future = CompletableFuture.supplyAsync(
          () -> {
            try {
              return blobStoreManager.create(blobStoreConfiguration).getBlobStoreConfiguration();
            }
            catch (Exception e) {
              throw new RuntimeException("Failed to create blob store: " + e.getMessage(), e);
            }
          },
          Executors.newVirtualThreadPerTaskExecutor()
      );
      
      return future.join();
    }
    catch (Exception e) {
      throw new RuntimeException("Failed to create blob store: " + e.getMessage(), e);
    }
  }
}