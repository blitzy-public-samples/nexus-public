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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.common.Time;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.ssl.KeyStoreManager;
import org.sonatype.nexus.ssl.KeyStoreManagerConfiguration;
import org.sonatype.nexus.ssl.KeyStoreManagerImpl;
import org.sonatype.nexus.ssl.KeyStoreManagerImplTest.MemKeyStoreStorageManager;
import org.sonatype.nexus.ssl.spi.KeyStoreStorageManager;
import org.sonatype.nexus.testsuite.testsupport.virtualthread.VirtualThreadTestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KeyStoreManagerImpl} using Java 21 Virtual Threads.
 * 
 * This test class validates that key pair generation, certificate import/export, 
 * and SSL handshakes work correctly when executed concurrently using Virtual Threads.
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
    when(config.getCertificateValidity()).thenReturn(Time.days(36500));
    when(config.getKeyManagerAlgorithm()).thenReturn(KeyManagerFactory.getDefaultAlgorithm());
    when(config.getTrustManagerAlgorithm()).thenReturn(TrustManagerFactory.getDefaultAlgorithm());
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
   * This verifies that multiple Virtual Threads can safely generate key pairs
   * without causing thread-safety issues.
   */
  @Test
  public void testConcurrentKeyPairGenerationWithVirtualThreads() throws Exception {
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create multiple Virtual Threads to generate key pairs concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a new KeyStoreManager for each thread
            KeyStoreManager threadKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
            
            // Generate a key pair with a unique common name
            threadKeyStoreManager.generateAndStoreKeyPair(
                "Virtual Thread " + index,
                "dev",
                "codeSoft",
                "AnyTown",
                "state",
                "US");
            
            // Verify the key pair was generated successfully
            assertTrue(threadKeyStoreManager.isKeyPairInitialized());
            assertNotNull(threadKeyStoreManager.getCertificate());
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(30, TimeUnit.SECONDS);
    }

    // Check if any exceptions occurred
    if (!exceptions.isEmpty()) {
      for (Exception e : exceptions) {
        log.error("Exception during concurrent key pair generation", e);
      }
      throw exceptions.get(0);
    }

    // Verify all threads successfully generated key pairs
    assertEquals("All virtual threads should have successfully generated key pairs", 
        threadCount, successCount.get());
  }

  /**
   * Tests concurrent certificate import operations using Virtual Threads.
   * This verifies that multiple Virtual Threads can safely import certificates
   * without causing thread-safety issues.
   */
  @Test
  public void testConcurrentCertificateImportWithVirtualThreads() throws Exception {
    // First generate a key pair
    keyStoreManager.generateAndStoreKeyPair("Main Key", "dev", "codeSoft", "AnyTown", "state", "US");
    
    int threadCount = 20;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();

    // Pre-generate certificates for import
    List<X509Certificate> certificates = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      certificates.add(generateCertificate(10, 
          "Cert " + i, "org-unit", "org", "locality", "state", "country"));
    }

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create multiple Virtual Threads to import certificates concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        final X509Certificate cert = certificates.get(index);
        
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Import the certificate with a unique alias
            keyStoreManager.importTrustCertificate(cert, "cert-" + index);
            
            // Verify the certificate was imported successfully
            Certificate importedCert = keyStoreManager.getTrustedCertificate("cert-" + index);
            assertNotNull(importedCert);
            assertEquals(cert, importedCert);
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(30, TimeUnit.SECONDS);
    }

    // Check if any exceptions occurred
    if (!exceptions.isEmpty()) {
      for (Exception e : exceptions) {
        log.error("Exception during concurrent certificate import", e);
      }
      throw exceptions.get(0);
    }

    // Verify all threads successfully imported certificates
    assertEquals("All virtual threads should have successfully imported certificates", 
        threadCount, successCount.get());
    
    // Verify the total number of trusted certificates
    assertEquals(threadCount, keyStoreManager.getTrustedCertificates().size());
  }

  /**
   * Tests concurrent certificate removal operations using Virtual Threads.
   * This verifies that multiple Virtual Threads can safely remove certificates
   * without causing thread-safety issues.
   */
  @Test
  public void testConcurrentCertificateRemovalWithVirtualThreads() throws Exception {
    // First generate a key pair
    keyStoreManager.generateAndStoreKeyPair("Main Key", "dev", "codeSoft", "AnyTown", "state", "US");
    
    int threadCount = 20;
    
    // Pre-generate and import certificates
    for (int i = 0; i < threadCount; i++) {
      X509Certificate cert = generateCertificate(10, 
          "Cert " + i, "org-unit", "org", "locality", "state", "country");
      keyStoreManager.importTrustCertificate(cert, "cert-" + i);
    }
    
    // Verify all certificates were imported
    assertEquals(threadCount, keyStoreManager.getTrustedCertificates().size());
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Exception> exceptions = new ArrayList<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create multiple Virtual Threads to remove certificates concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Remove the certificate
            keyStoreManager.removeTrustCertificate("cert-" + index);
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } finally {
            completionLatch.countDown();
          }
        });
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(30, TimeUnit.SECONDS);
    }

    // Check if any exceptions occurred
    if (!exceptions.isEmpty()) {
      for (Exception e : exceptions) {
        log.error("Exception during concurrent certificate removal", e);
      }
      throw exceptions.get(0);
    }

    // Verify all threads successfully removed certificates
    assertEquals("All virtual threads should have successfully removed certificates", 
        threadCount, successCount.get());
    
    // Verify all certificates were removed
    assertEquals(0, keyStoreManager.getTrustedCertificates().size());
  }

  /**
   * Tests SSL handshakes using Virtual Threads.
   * This verifies that SSL connections work properly when handled by Virtual Threads.
   */
  @Test
  public void testSSLConnectionWithVirtualThreads() throws Exception {
    // Set up server and client keystores
    KeyStoreManager serverKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
    serverKeyStoreManager.generateAndStoreKeyPair("Server Side", "dev", "codeSoft", "AnyTown", "state", "US");

    KeyStoreManager clientKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
    clientKeyStoreManager.generateAndStoreKeyPair("Client Side", "dev", "codeSoft", "AnyTown", "state", "US");

    // Exchange certificates for mutual trust
    Certificate clientCertificate = clientKeyStoreManager.getCertificate();
    serverKeyStoreManager.importTrustCertificate(clientCertificate, "client-side");

    Certificate serverCertificate = serverKeyStoreManager.getCertificate();
    clientKeyStoreManager.importTrustCertificate(serverCertificate, "server-side");

    // Set up SSL contexts
    SSLContext serverSslContext = SSLContext.getInstance("TLS");
    serverSslContext.init(serverKeyStoreManager.getKeyManagers(), serverKeyStoreManager.getTrustManagers(),
        new SecureRandom());
    serverSslContext.getServerSessionContext().setSessionTimeout(1);
    serverSslContext.getClientSessionContext().setSessionTimeout(1);

    SSLContext clientSslContext = SSLContext.getInstance("TLS");
    clientSslContext.init(clientKeyStoreManager.getKeyManagers(), clientKeyStoreManager.getTrustManagers(),
        new SecureRandom());
    clientSslContext.getServerSessionContext().setSessionTimeout(1);
    clientSslContext.getClientSessionContext().setSessionTimeout(1);

    // Set up the SSL server socket
    SSLServerSocketFactory sslServerSocketFactory = serverSslContext.getServerSocketFactory();
    final SSLServerSocket sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(0);
    sslServerSocket.setNeedClientAuth(true);

    final List<String> results = new ArrayList<>();
    final CountDownLatch serverReady = new CountDownLatch(1);
    final CountDownLatch serverDone = new CountDownLatch(1);

    // Start server in a Virtual Thread
    Thread serverThread = Thread.ofVirtual().name("ssl-server").start(() -> {
      try {
        serverReady.countDown();
        SSLSocket sslSocket = (SSLSocket) sslServerSocket.accept();

        results.add(sslSocket.getSession().getPeerPrincipal().getName());
        results.add(sslSocket.getSession().getLocalPrincipal().getName());

        InputStream inputStream = sslSocket.getInputStream();
        InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

        String data;
        while ((data = bufferedReader.readLine()) != null) {
          results.add(data);
        }
        
        serverDone.countDown();
      } catch (Exception e) {
        log.error("Server thread error", e);
      }
    });

    // Wait for server to be ready
    serverReady.await(5, TimeUnit.SECONDS);

    // Expected data and results
    String expectedXferData = "Virtual Thread SSL Test Data";
    List<String> expectedResults = new ArrayList<>();
    expectedResults.add("C=US,ST=state,L=AnyTown,O=codeSoft,OU=dev,CN=Client Side"); // client DN
    expectedResults.add("C=US,ST=state,L=AnyTown,O=codeSoft,OU=dev,CN=Server Side"); // server DN
    expectedResults.add(expectedXferData); // transfer data

    // Start client in a Virtual Thread
    Future<?> clientFuture = Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      SSLSocket sslSocket = null;
      try {
        SSLSocketFactory sslSocketFactory = clientSslContext.getSocketFactory();
        sslSocket = (SSLSocket) sslSocketFactory.createSocket("localhost", sslServerSocket.getLocalPort());

        OutputStream outputStream = sslSocket.getOutputStream();
        OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);

        outputStreamWriter.write(expectedXferData + "\n");
        outputStreamWriter.flush();
        outputStreamWriter.close();
      } catch (Exception e) {
        log.error("Client thread error", e);
      } finally {
        if (sslSocket != null) {
          try {
            sslSocket.close();
          } catch (Exception e) {
            log.error("Error closing client socket", e);
          }
        }
      }
    });

    // Wait for client and server to complete
    clientFuture.get(10, TimeUnit.SECONDS);
    serverDone.await(10, TimeUnit.SECONDS);
    sslServerSocket.close();

    // Verify results
    assertThat(results, equalTo(expectedResults));
  }

  /**
   * Tests multiple concurrent SSL connections using Virtual Threads.
   * This verifies that multiple SSL connections can be handled concurrently
   * by Virtual Threads without issues.
   */
  @Test
  public void testMultipleConcurrentSSLConnectionsWithVirtualThreads() throws Exception {
    // Set up server and client keystores
    KeyStoreManager serverKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
    serverKeyStoreManager.generateAndStoreKeyPair("Server Side", "dev", "codeSoft", "AnyTown", "state", "US");

    KeyStoreManager clientKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
    clientKeyStoreManager.generateAndStoreKeyPair("Client Side", "dev", "codeSoft", "AnyTown", "state", "US");

    // Exchange certificates for mutual trust
    Certificate clientCertificate = clientKeyStoreManager.getCertificate();
    serverKeyStoreManager.importTrustCertificate(clientCertificate, "client-side");

    Certificate serverCertificate = serverKeyStoreManager.getCertificate();
    clientKeyStoreManager.importTrustCertificate(serverCertificate, "server-side");

    // Set up SSL contexts
    SSLContext serverSslContext = SSLContext.getInstance("TLS");
    serverSslContext.init(serverKeyStoreManager.getKeyManagers(), serverKeyStoreManager.getTrustManagers(),
        new SecureRandom());

    SSLContext clientSslContext = SSLContext.getInstance("TLS");
    clientSslContext.init(clientKeyStoreManager.getKeyManagers(), clientKeyStoreManager.getTrustManagers(),
        new SecureRandom());

    // Set up the SSL server socket
    SSLServerSocketFactory sslServerSocketFactory = serverSslContext.getServerSocketFactory();
    final SSLServerSocket sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(0);
    sslServerSocket.setNeedClientAuth(true);

    final int connectionCount = 10;
    final CountDownLatch serverReady = new CountDownLatch(1);
    final CountDownLatch allConnectionsProcessed = new CountDownLatch(connectionCount);
    final AtomicInteger successfulConnections = new AtomicInteger(0);

    // Start server in a Virtual Thread
    Thread serverThread = Thread.ofVirtual().name("ssl-server").start(() -> {
      try {
        serverReady.countDown();
        
        // Accept multiple connections, each handled by a new Virtual Thread
        for (int i = 0; i < connectionCount; i++) {
          SSLSocket clientSocket = (SSLSocket) sslServerSocket.accept();
          
          // Handle each client in a separate Virtual Thread
          Thread.ofVirtual().name("client-handler-" + i).start(() -> {
            try {
              InputStream inputStream = clientSocket.getInputStream();
              InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
              BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

              // Read client data
              String clientData = bufferedReader.readLine();
              
              // Send response back to client
              OutputStreamWriter writer = new OutputStreamWriter(clientSocket.getOutputStream());
              writer.write("Received: " + clientData + "\n");
              writer.flush();
              writer.close();
              
              clientSocket.close();
              allConnectionsProcessed.countDown();
            } catch (Exception e) {
              log.error("Error handling client connection", e);
              allConnectionsProcessed.countDown();
            }
          });
        }
      } catch (Exception e) {
        log.error("Server thread error", e);
      }
    });

    // Wait for server to be ready
    serverReady.await(5, TimeUnit.SECONDS);

    // Start multiple clients in Virtual Threads
    try (ExecutorService clientExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> clientFutures = new ArrayList<>();
      
      for (int i = 0; i < connectionCount; i++) {
        final int clientId = i;
        clientFutures.add(clientExecutor.submit(() -> {
          SSLSocket sslSocket = null;
          try {
            SSLSocketFactory sslSocketFactory = clientSslContext.getSocketFactory();
            sslSocket = (SSLSocket) sslSocketFactory.createSocket("localhost", sslServerSocket.getLocalPort());

            // Send data to server
            String clientMessage = "Client " + clientId + " data";
            OutputStreamWriter writer = new OutputStreamWriter(sslSocket.getOutputStream());
            writer.write(clientMessage + "\n");
            writer.flush();

            // Read server response
            BufferedReader reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
            String response = reader.readLine();
            
            // Verify response
            assertEquals("Received: " + clientMessage, response);
            successfulConnections.incrementAndGet();
          } catch (Exception e) {
            log.error("Client " + clientId + " error", e);
          } finally {
            if (sslSocket != null) {
              try {
                sslSocket.close();
              } catch (Exception e) {
                log.error("Error closing client socket", e);
              }
            }
          }
        }));
      }
      
      // Wait for all clients to complete
      for (Future<?> future : clientFutures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }

    // Wait for server to process all connections
    allConnectionsProcessed.await(10, TimeUnit.SECONDS);
    sslServerSocket.close();

    // Verify all connections were successful
    assertEquals(connectionCount, successfulConnections.get());
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
}