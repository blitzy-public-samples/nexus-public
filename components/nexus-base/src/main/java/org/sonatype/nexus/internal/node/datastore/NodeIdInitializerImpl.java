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

import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertPathValidatorException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.internal.node.KeyStoreManagerImpl;
import org.sonatype.nexus.internal.node.NodeIdEncoding;
import org.sonatype.nexus.internal.node.NodeIdInitializer;
import org.sonatype.nexus.node.datastore.NodeIdStore;
import org.sonatype.nexus.ssl.KeyStoreManager;
import org.sonatype.nexus.ssl.KeystoreException;

import static java.lang.StringTemplate.STR;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Migrates the legacy on disk node identifier to the database where it may be used to identify a single node, or a
 * cluster.
 * 
 * Updated for Java 21 compatibility with improved certificate handling and String Templates for logging.
 */
@Named
@Singleton
public class NodeIdInitializerImpl
    extends ComponentSupport
    implements NodeIdInitializer
{
  private final NodeIdStore nodeIdStore;

  private final Provider<KeyStoreManager> keyStoreProvider;

  @Inject
  public NodeIdInitializerImpl(
      @Named(KeyStoreManagerImpl.NAME) final Provider<KeyStoreManager> keyStoreProvider,
      final NodeIdStore nodeIdStore)
  {
    this.nodeIdStore = checkNotNull(nodeIdStore);
    this.keyStoreProvider = checkNotNull(keyStoreProvider);
  }

  public void initialize() {
    if (!nodeIdStore.get().isPresent()) {
      migrateNodeId();
    }
  }

  private void migrateNodeId() {
    log.info(STR."No node-id found. Attempting to migrate from KeyStore");
    KeyStoreManager keyStoreManager = keyStoreProvider.get();

    if (!keyStoreManager.isKeyPairInitialized()) {
      generateNodeId();
      return;
    }

    // Migrating an existing key
    log.info(STR."Migrating node-id to database");

    try {
      Certificate certificate = keyStoreManager.getCertificate();
      log.trace(STR."Certificate:\n\{certificate}");

      String id = NodeIdEncoding.nodeIdForCertificate(certificate);

      nodeIdStore.set(id);
    }
    catch (KeystoreException e) {
      log.warn(STR."KeystoreException while migrating node-id: \{e.getMessage()}");
      generateNodeId();
    }
    catch (CertificateEncodingException e) {
      log.warn(STR."CertificateEncodingException while migrating node-id: \{e.getMessage()}");
      generateNodeId();
    }
    catch (CertPathValidatorException e) {
      // Java 21 may throw this exception when certificate algorithm constraints are violated
      log.warn(STR."Certificate validation failed due to algorithm constraints: \{e.getMessage()}");
      generateNodeId();
    }
    catch (Exception e) {
      // Catch any other certificate-related exceptions that might be thrown in Java 21
      log.warn(STR."Unexpected exception while processing certificate: \{e.getMessage()}");
      generateNodeId();
    }
  }

  private void generateNodeId() {
    log.info(STR."Unable to get node-id from KeyStore - Generating a new node-id");
    nodeIdStore.getOrCreate();
  }
}