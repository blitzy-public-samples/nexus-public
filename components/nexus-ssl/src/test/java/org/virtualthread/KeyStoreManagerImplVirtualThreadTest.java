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
package org.virtualthread;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.ssl.KeyStoreManager;
import org.sonatype.nexus.ssl.KeyStoreManagerConfiguration;
import org.sonatype.nexus.ssl.KeyStoreManagerImpl;
import org.sonatype.nexus.ssl.spi.KeyStoreStorage;
import org.sonatype.nexus.ssl.spi.KeyStoreStorageManager;
import org.sonatype.nexus.testsuite.testsupport.VirtualThreadTestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.ssl.KeyStoreManagerImpl.PRIVATE_KEY_ALIAS;

/**
 * Tests for {@link KeyStoreManagerImpl} using Java 21 Virtual Threads.
 * 
 * This test class validates that key pair generation, certificate import/export, and SSL handshakes
 * work correctly when executed concurrently using Virtual Threads, ensuring the SSL module's core
 * functionality remains reliable in the Java 21 environment.
 */
@Category(VirtualThreadTestGroup.class)
public class KeyStoreManagerImplVirtualThreadTest
    extends TestSupport
{
  private final CryptoHelper crypto = new CryptoHelperImpl();

  private KeyStoreStorageManager storageManager;

  private KeyStoreManager keyStoreManager;

  @Before
  public void setUp() throws Exception {
    storageManager = new MemKeyStoreStorageManager();
    keyStoreManager = createKeyStoreManager(storageManager);
  }

  private KeyStoreManagerConfiguration createMockConfiguration() {
    KeyStoreManagerConfiguration config = mock(KeyStoreManagerConfiguration.class);
    // use lower strength for faster test execution
    when(config.getKeyStoreType()).thenReturn("JKS");
    when(config.getKeyAlgorithm()).thenReturn("RSA");
    when(config.getKeyAlgorithmSize()).thenReturn(1024);
    when(config.getSignatureAlgorithm()).thenReturn("SHA1WITHRSA");
    when(config.getCertificateValidity()).thenReturn(org.sonatype.goodies.common.Time.days(36500));
    when(config.getKeyManagerAlgorithm()).thenReturn(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
    when(config.getTrustManagerAlgorithm()).thenReturn(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
    when(config.getPrivateKeyStorePassword()).thenReturn("pwd".toCharArray());
    when(config.getTrustedKeyStorePassword()).thenReturn("pwd".toCharArray());
    when(config.getPrivateKeyPassword()).thenReturn("pwd".toCharArray());
    return config;
  }

  private KeyStoreManager createKeyStoreManager(final KeyStoreStorageManager storageManager) {
    return new KeyStoreManagerImpl(crypto, storageManager, createMockConfiguration());
  }

  /**
   * Tests concurrent key pair generation using Virtual Threads.
   * Verifies that multiple key pairs can be generated concurrently without issues.
   */
  @Test
  public void testConcurrentKeyPairGenerationWithVirtualThreads() throws Exception {
    int threadCount = 5;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<KeyStoreManager> managers = new ArrayList<>();
    
    // Create multiple KeyStoreManagers, one for each thread
    for (int i = 0; i < threadCount; i++) {
      managers.add(createKeyStoreManager(new MemKeyStoreStorageManager()));
    }
    
    // Use Virtual Thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to generate key pairs concurrently
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          try {
            // Generate a key pair with a unique common name
            managers.get(index).generateAndStoreKeyPair(
                "Virtual Thread Test " + index,
                "dev",
                "nexus",
                "AnyTown",
                "state",
                "US");
            
            // Verify the key pair was generated correctly
            KeyManager[] keyManagers = managers.get(index).getKeyManagers();
            if (keyManagers != null && keyManagers.length == 1 && keyManagers[0] instanceof X509KeyManager) {
              X509KeyManager keyManager = (X509KeyManager) keyManagers[0];
              if (keyManager.getCertificateChain(PRIVATE_KEY_ALIAS) != null) {
                successCount.incrementAndGet();
              }
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify all key pairs were generated successfully
      assertEquals("All key pairs should be generated successfully", threadCount, successCount.get());
    }
  }

  /**
   * Tests concurrent certificate import/export operations using Virtual Threads.
   * Verifies that certificates can be imported and exported concurrently without issues.
   */
  @Test
  public void testConcurrentCertificateOperationsWithVirtualThreads() throws Exception {
    // First create a key pair to get a certificate
    keyStoreManager.generateAndStoreKeyPair("Test Certificate", "dev", "nexus", "AnyTown", "state", "US");
    X509Certificate certificate = (X509Certificate) keyStoreManager.getCertificate();
    
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Use Virtual Thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to import certificates concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a new KeyStoreManager for each thread
            KeyStoreManager manager = createKeyStoreManager(new MemKeyStoreStorageManager());
            
            // Import the certificate with a unique alias
            String alias = "cert-" + index;
            manager.importTrustCertificate(certificate, alias);
            
            // Verify the certificate was imported correctly
            Certificate importedCert = manager.getTrustedCertificate(alias);
            if (importedCert != null && importedCert.equals(certificate)) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify all certificates were imported successfully
      assertEquals("All certificates should be imported successfully", threadCount, successCount.get());
    }
  }

  /**
   * Tests concurrent PEM certificate import operations using Virtual Threads.
   * Verifies that PEM certificates can be imported concurrently without issues.
   */
  @Test
  public void testConcurrentPEMCertificateImportWithVirtualThreads() throws Exception {
    // First create a key pair to get a certificate
    keyStoreManager.generateAndStoreKeyPair("Test PEM Certificate", "dev", "nexus", "AnyTown", "state", "US");
    X509Certificate certificate = (X509Certificate) keyStoreManager.getCertificate();
    String pemCertificate = CertificateUtil.serializeCertificateInPEM(certificate);
    
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Use Virtual Thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to import PEM certificates concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a new KeyStoreManager for each thread
            KeyStoreManager manager = createKeyStoreManager(new MemKeyStoreStorageManager());
            
            // Import the PEM certificate with a unique alias
            String alias = "pem-cert-" + index;
            manager.importTrustCertificate(pemCertificate, alias);
            
            // Verify the certificate was imported correctly
            Certificate importedCert = manager.getTrustedCertificate(alias);
            if (importedCert != null) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify all PEM certificates were imported successfully
      assertEquals("All PEM certificates should be imported successfully", threadCount, successCount.get());
    }
  }

  /**
   * Tests concurrent SSL handshakes using Virtual Threads.
   * Verifies that SSL handshakes work correctly when executed concurrently using Virtual Threads.
   */
  @Test
  public void testConcurrentSSLHandshakesWithVirtualThreads() throws Exception {
    // Set up server KeyStoreManager
    KeyStoreManager serverKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
    serverKeyStoreManager.generateAndStoreKeyPair("Server Side", "dev", "nexus", "AnyTown", "state", "US");
    
    // Set up SSL context for server
    SSLContext serverSslContext = SSLContext.getInstance("TLS");
    serverSslContext.init(serverKeyStoreManager.getKeyManagers(), serverKeyStoreManager.getTrustManagers(),
        new SecureRandom());
    
    // Set up SSL server socket
    SSLServerSocketFactory sslServerSocketFactory = serverSslContext.getServerSocketFactory();
    final SSLServerSocket sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(0);
    sslServerSocket.setNeedClientAuth(true);
    
    // Start server thread
    Thread serverThread = Thread.ofVirtual().name("ssl-server").start(() -> {
      try {
        int clientCount = 0;
        while (clientCount < 5) { // Accept 5 client connections
          SSLSocket clientSocket = (SSLSocket) sslServerSocket.accept();
          Thread.ofVirtual().name("client-handler-" + clientCount).start(() -> {
            try {
              // Just complete the handshake and close
              clientSocket.getSession();
              clientSocket.close();
            }
            catch (Exception e) {
              log.error("Error handling client", e);
            }
          });
          clientCount++;
        }
      }
      catch (Exception e) {
        log.error("Server error", e);
      }
      finally {
        try {
          sslServerSocket.close();
        }
        catch (IOException e) {
          log.error("Error closing server socket", e);
        }
      }
    });
    
    // Give the server a moment to start
    Thread.sleep(500);
    
    int clientCount = 5;
    CountDownLatch latch = new CountDownLatch(clientCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Use Virtual Thread per task executor for clients
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit client tasks
      for (int i = 0; i < clientCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a client KeyStoreManager
            KeyStoreManager clientKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
            clientKeyStoreManager.generateAndStoreKeyPair("Client " + index, "dev", "nexus", "AnyTown", "state", "US");
            
            // Exchange certificates
            Certificate serverCertificate = serverKeyStoreManager.getCertificate();
            clientKeyStoreManager.importTrustCertificate(serverCertificate, "server-cert");
            
            Certificate clientCertificate = clientKeyStoreManager.getCertificate();
            serverKeyStoreManager.importTrustCertificate(clientCertificate, "client-cert-" + index);
            
            // Set up SSL context for client
            SSLContext clientSslContext = SSLContext.getInstance("TLS");
            clientSslContext.init(clientKeyStoreManager.getKeyManagers(), clientKeyStoreManager.getTrustManagers(),
                new SecureRandom());
            
            // Connect to server
            SSLSocketFactory sslSocketFactory = clientSslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket("localhost", sslServerSocket.getLocalPort());
            
            // Complete handshake
            sslSocket.startHandshake();
            
            // Verify handshake completed successfully
            if (sslSocket.getSession() != null) {
              successCount.incrementAndGet();
            }
            
            // Close socket
            sslSocket.close();
          }
          catch (Exception e) {
            log.error("Error in client virtual thread", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all clients to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify all handshakes were successful
      assertEquals("All SSL handshakes should complete successfully", clientCount, successCount.get());
    }
    
    // Wait for server thread to complete
    serverThread.join(5000);
  }

  /**
   * Tests concurrent removal of trusted certificates using Virtual Threads.
   */
  @Test
  public void testConcurrentRemoveTrustedCertificatesWithVirtualThreads() throws Exception {
    // First create certificates and import them
    int certCount = 10;
    List<String> aliases = new ArrayList<>();
    
    for (int i = 0; i < certCount; i++) {
      X509Certificate certificate = generateCertificate(10, "Cert " + i, "unit", "org", "locality", "state", "country");
      String alias = "cert-" + i;
      keyStoreManager.importTrustCertificate(certificate, alias);
      aliases.add(alias);
    }
    
    // Verify all certificates were imported
    assertEquals(certCount, keyStoreManager.getTrustedCertificates().size());
    
    CountDownLatch latch = new CountDownLatch(certCount);
    
    // Use Virtual Thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to remove certificates concurrently
      for (int i = 0; i < certCount; i++) {
        final String alias = aliases.get(i);
        executor.submit(() -> {
          try {
            keyStoreManager.removeTrustCertificate(alias);
          }
          catch (Exception e) {
            log.error("Error removing certificate", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify all certificates were removed
      assertEquals("All certificates should be removed", 0, keyStoreManager.getTrustedCertificates().size());
    }
  }

  private X509Certificate generateCertificate(int validity,
                                              String commonName,
                                              String orgUnit,
                                              String organization,
                                              String locality,
                                              String state,
                                              String country)
      throws Exception
  {
    KeyPairGenerator kpgen = KeyPairGenerator.getInstance("RSA");
    kpgen.initialize(512);
    KeyPair keyPair = kpgen.generateKeyPair();

    return CertificateUtil.generateCertificate(keyPair.getPublic(), keyPair.getPrivate(), "SHA1WITHRSA", validity,
        commonName, orgUnit, organization, locality, state, country);
  }

  /**
   * In-memory implementation of KeyStoreStorageManager for testing.
   */
  static class MemKeyStoreStorageManager
      implements KeyStoreStorageManager
  {
    class MemKeyStoreStorage
        implements KeyStoreStorage
    {
      private final String keyStoreName;

      MemKeyStoreStorage(final String keyStoreName) {
        this.keyStoreName = checkNotNull(keyStoreName);
      }

      @Override
      public boolean exists() {
        return storages.get(keyStoreName) != null;
      }

      @Override
      public boolean modified() {
        return false;
      }

      @Override
      public void load(final KeyStore keyStore, final char[] password)
          throws NoSuchAlgorithmException, CertificateException, IOException
      {
        byte[] bytes = storages.get(keyStoreName);
        checkState(bytes != null);
        keyStore.load(new ByteArrayInputStream(bytes), password);
      }

      @Override
      public void save(final KeyStore keyStore, final char[] password)
          throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException
      {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        keyStore.store(baos, password);
        storages.put(keyStoreName, baos.toByteArray());
      }
    }

    private final Map<String, byte[]> storages = new HashMap<>();

    @Override
    public KeyStoreStorage createStorage(final String keyStoreName) {
      return new MemKeyStoreStorage(keyStoreName);
    }
  }

  private static <T> T checkNotNull(T reference) {
    if (reference == null) {
      throw new NullPointerException();
    }
    return reference;
  }

  private static void checkState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }
}