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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import java.security.cert.CertificateException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Provider;

import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.ssl.KeystoreException;
import org.sonatype.nexus.ssl.TrustStore;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit 5 extension for managing trust certificates during tests.
 * <p>
 * Usage example:
 * <pre>
 * {@code
 * @RegisterExtension
 * public TrustStoreExtension trustStore = new TrustStoreExtension(trustStoreProvider);
 * }
 * </pre>
 *
 * @since 3.60
 */
public class TrustStoreExtension
    implements AfterEachCallback
{
  private static final Logger log = LoggerFactory.getLogger(TrustStoreExtension.class);

  private final Provider<TrustStore> trustStoreProvider;

  private final Set<String> managedAliases = new HashSet<>();

  /**
   * Creates a new TrustStoreExtension with the given trust store provider.
   *
   * @param trustStoreProvider the provider for the trust store to manage
   */
  public TrustStoreExtension(final Provider<TrustStore> trustStoreProvider) {
    this.trustStoreProvider = trustStoreProvider;
  }

  @Override
  public void afterEach(final ExtensionContext context) {
    cleanupManagedAliases();
  }

  /**
   * Cleans up all managed certificate aliases using virtual threads for I/O operations when running on Java 21+.
   */
  private void cleanupManagedAliases() {
    if (managedAliases.isEmpty()) {
      return;
    }

    try {
      // Use virtual threads for I/O operations when available (Java 21+)
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        managedAliases.forEach(fingerprint -> {
          executor.submit(() -> {
            try {
              trustStoreProvider.get().removeTrustCertificate(fingerprint);
            }
            catch (Exception e) { // NOSONAR
              log.info("Unable to clean up alias {}", fingerprint, e);
            }
            return null;
          });
        });
      }
      finally {
        executor.close();
      }
    }
    catch (Exception e) {
      // Fall back to sequential cleanup if virtual threads are not available
      managedAliases.forEach(fingerprint -> {
        try {
          trustStoreProvider.get().removeTrustCertificate(fingerprint);
        }
        catch (Exception ex) { // NOSONAR
          log.info("Unable to clean up alias {}", fingerprint, ex);
        }
      });
    }
    
    managedAliases.clear();
  }

  /**
   * Add a certificate to the trust store.
   * 
   * @param pem The PEM-formatted certificate to add
   */
  public void addCertificate(final String pem) {
    try {
      String fingerprint = CertificateUtil.calculateFingerprint(CertificateUtil.decodePEMFormattedCertificate(pem));
      trustStoreProvider.get().importTrustCertificate(pem, fingerprint);
      managedAliases.add(fingerprint);
    }
    catch (CertificateException | KeystoreException e) {
      throw new RuntimeException("Failed to add certificate", e);
    }
  }

  /**
   * Add a certificate alias to automatically cleanup upon test completion.
   * 
   * @param fingerprint The certificate fingerprint to manage
   */
  public void manageAlias(final String fingerprint) {
    managedAliases.add(fingerprint);
  }

  /**
   * Remove a certificate alias from automatic cleanup.
   * 
   * @param fingerprint The certificate fingerprint to unmanage
   */
  public void unmanageAlias(final String fingerprint) {
    managedAliases.remove(fingerprint);
  }
}