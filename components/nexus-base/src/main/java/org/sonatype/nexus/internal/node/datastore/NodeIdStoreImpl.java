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
package org.sonatype.nexus.internal.node.datastore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.internal.node.NodeIdEncoding;
import org.sonatype.nexus.node.datastore.NodeIdStore;
import org.sonatype.nexus.transaction.Transactional;

/**
 * Implementation of {@link NodeIdStore} using a database for storage.
 * 
 * @since 3.37
 */
@Named("mybatis")
@Singleton
public class NodeIdStoreImpl
    extends ConfigStoreSupport<NodeIdDAO>
    implements NodeIdStore
{
  @Inject
  public NodeIdStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier);
  }

  /**
   * Remove the currently persisted node id, this will not change the {@link NodeAccess}.
   */
  @Transactional
  @Override
  public void clear() {
    // Use virtual thread for this I/O-bound operation
    Thread.startVirtualThread(() -> dao().clear()).join();
  }

  /**
   * Retrieve the current node id if it exists.
   *
   * @return the node id
   */
  @Transactional
  @Override
  public Optional<String> get() {
    // Use virtual thread for this I/O-bound operation
    return Thread.startVirtualThread(() -> dao().get()).join();
  }

  /**
   * Set the current node id, this will not update the {@link NodeAccess}
   *
   * @param nodeId the node ID to set
   */
  @Transactional
  @Override
  public void set(final String nodeId) {
    // Use virtual thread for this I/O-bound operation
    Thread.startVirtualThread(() -> dao().set(nodeId)).join();
  }

  /**
   * Get the current node ID or create a new one if it doesn't exist.
   * 
   * @return the node ID
   */
  @Transactional(retryOn = DuplicateKeyException.class)
  @Override
  public String getOrCreate() {
    // Use virtual thread for this I/O-bound operation with potential database transaction
    return Thread.startVirtualThread(() -> 
        get().orElseGet(() -> {
          String newNodeId = generateNodeId();
          dao().create(newNodeId);
          return newNodeId;
        })
    ).join();
  }

  /**
   * Generate a new node ID using SHA-1 hash of a random UUID.
   * 
   * @return the generated node ID
   */
  private String generateNodeId() {
    log.debug(STR."Generating nodeId using Java \{System.getProperty(\"java.version\")} virtual threads");

    // Generate something unique
    UUID cn = UUID.randomUUID();
    
    try {
      // Use Java's MessageDigest instead of deprecated Guava Hashing
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] digest = md.digest(cn.toString().getBytes(StandardCharsets.UTF_8));
      
      // Convert to hex string
      StringBuilder hexString = new StringBuilder();
      for (byte b : digest) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      
      // Format the node ID using the same encoding as before
      return NodeIdEncoding.nodeIdForSha1(hexString.toString());
    } 
    catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-1 algorithm not available", e);
    }
  }
}