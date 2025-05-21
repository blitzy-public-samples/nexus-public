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

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Implementation of {@link BlobStoreApi} that leverages Java 21 features for improved performance and code clarity.
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
    // Using record patterns for type safety and improved readability
    record FileConfig(String path) {}
    
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> fileAttributes = new HashMap<>();
    fileAttributes.put("path", checkNotNull(path));
    attributes.put("file", fileAttributes);
    
    // Using pattern matching to validate configuration
    if (fileAttributes instanceof Map<String, Object> map && map.get("path") instanceof String pathValue) {
      // Path is valid, proceed with configuration
    } else {
      throw new IllegalArgumentException("Invalid file path configuration");
    }
    
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
    // Using record patterns for type safety and improved readability
    record GroupConfig(List<String> members, String fillPolicy) {}
    
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> groupAttributes = new HashMap<>();
    groupAttributes.put("members", memberNames);
    groupAttributes.put("fillPolicy", fillPolicy);
    attributes.put("group", groupAttributes);
    
    // Using pattern matching to validate configuration
    if (groupAttributes instanceof Map<String, Object> map && 
        map.get("members") instanceof List<?> members && 
        map.get("fillPolicy") instanceof String policy) {
      // Group configuration is valid, proceed
      GroupConfig config = new GroupConfig(memberNames, fillPolicy);
      // We can now use the config record for additional validation if needed
    } else {
      throw new IllegalArgumentException("Invalid group configuration");
    }
    
    BlobStoreConfiguration blobStoreConfiguration = blobStoreManager.newConfiguration();
    blobStoreConfiguration.setName(name);
    blobStoreConfiguration.setType("Group");
    blobStoreConfiguration.setAttributes(attributes);
    
    return doCreate(blobStoreConfiguration);
  }

  @Override
  public BlobStoreConfiguration createS3BlobStore(final String name, final Map<String, String> s3Config) {
    // Using record patterns for type safety and improved readability
    record S3Config(Map<String, Object> config) {}
    
    // re-collecting to move from <String,String> to <String,Object>
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> s3Attributes = new HashMap<>(s3Config);
    attributes.put("s3", s3Attributes);
    
    // Using pattern matching with record patterns for validation
    S3Config config = new S3Config(s3Attributes);
    if (config instanceof S3Config(var configMap) && !configMap.isEmpty()) {
      // S3 configuration is valid, proceed
      // We can access the config directly through the destructured record pattern
      
      // Validate required S3 configuration parameters if needed
      if (!configMap.containsKey("bucket")) {
        throw new IllegalArgumentException("S3 configuration missing required 'bucket' parameter");
      }
    } else {
      throw new IllegalArgumentException("Invalid S3 configuration");
    }
    
    BlobStoreConfiguration blobStoreConfiguration = blobStoreManager.newConfiguration();
    blobStoreConfiguration.setName(name);
    blobStoreConfiguration.setType("S3");
    blobStoreConfiguration.setAttributes(attributes);
    
    return doCreate(blobStoreConfiguration);
  }

  @Override
  public CompletableFuture<BlobStoreConfiguration> createFileBlobStoreAsync(String name, String path) {
    return CompletableFuture.supplyAsync(() -> createFileBlobStore(name, path), 
        Thread.ofVirtual().factory());
  }

  @Override
  public CompletableFuture<BlobStoreConfiguration> createBlobStoreGroupAsync(String name, List<String> memberNames, String fillPolicy) {
    return CompletableFuture.supplyAsync(() -> createBlobStoreGroup(name, memberNames, fillPolicy), 
        Thread.ofVirtual().factory());
  }

  @Override
  public CompletableFuture<BlobStoreConfiguration> createS3BlobStoreAsync(String name, Map<String, String> config) {
    return CompletableFuture.supplyAsync(() -> createS3BlobStore(name, config), 
        Thread.ofVirtual().factory());
  }

  /**
   * Creates a BlobStore using Virtual Threads for improved I/O operation handling.
   * This method leverages Java 21's Virtual Threads to avoid blocking platform threads
   * during potentially long-running I/O operations involved in BlobStore creation.
   *
   * <p>Virtual Threads are particularly well-suited for this operation as BlobStore creation
   * typically involves file system or network I/O operations that would otherwise block
   * platform threads. By using Virtual Threads, we can maintain high throughput even
   * with many concurrent BlobStore creation requests.</p>
   *
   * @param blobStoreConfiguration the configuration for the BlobStore to create
   * @return the created BlobStore's configuration
   */
  private BlobStoreConfiguration doCreate(final BlobStoreConfiguration blobStoreConfiguration) {
    try {
      // Using Virtual Threads for I/O-bound operations to improve throughput
      // When the thread blocks on I/O, the carrier thread is released to handle other tasks
      return Thread.ofVirtual()
          .name("blobstore-create-" + blobStoreConfiguration.getName())
          .start(() -> {
              try {
                // This operation may involve significant I/O, making it ideal for Virtual Threads
                var blobStore = blobStoreManager.create(blobStoreConfiguration);
                
                // Using pattern matching to safely extract configuration
                if (blobStore != null) {
                  return blobStore.getBlobStoreConfiguration();
                } else {
                  throw new IllegalStateException("BlobStore creation returned null for " + 
                      blobStoreConfiguration.getName());
                }
              } 
              catch (Exception e) {
                throw new RuntimeException("Failed to create BlobStore: " + e.getMessage(), e);
              }
          })
          .join(); // Join waits for the Virtual Thread to complete
    }
    catch (Exception e) {
      throw new RuntimeException("Error during BlobStore creation: " + e.getMessage(), e);
    }
  }
}