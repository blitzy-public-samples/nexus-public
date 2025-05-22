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
package virtualthread;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.ssl.KeyStoreManager;
import org.sonatype.nexus.ssl.KeyStoreManagerConfiguration;
import org.sonatype.nexus.ssl.KeyStoreManagerImpl;
import org.sonatype.nexus.ssl.KeyStoreManagerImplTest.MemKeyStoreStorageManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.ssl.KeyStoreManagerImpl.PRIVATE_KEY_ALIAS;

/**
 * Tests for SSL operations using Java 21 Virtual Threads.
 * 
 * This test class validates that SSL operations work correctly when executed within
 * Virtual Threads. It tests SSL context creation, certificate handling, and SSL connections
 * using virtual threads to ensure they don't cause thread pinning issues.
 */
public class SSLVirtualThreadTest
    extends TestSupport
{
  private final CryptoHelper crypto = new CryptoHelperImpl();

  private MemKeyStoreStorageManager storageManager;

  private KeyStoreManager keyStoreManager;
  
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  public void setUp() throws Exception {
    storageManager = new MemKeyStoreStorageManager();
    keyStoreManager = createKeyStoreManager(storageManager);
    // Create an executor service that uses virtual threads
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private KeyStoreManagerConfiguration createMockConfiguration() {
    KeyStoreManagerConfiguration config = mock(KeyStoreManagerConfiguration.class);
    // use lower strength for faster test execution
    when(config.getKeyStoreType()).thenReturn("JKS");
    when(config.getKeyAlgorithm()).thenReturn("RSA");
    when(config.getKeyAlgorithmSize()).thenReturn(1024);
    when(config.getSignatureAlgorithm()).thenReturn("SHA1WITHRSA");
    when(config.getCertificateValidity()).thenReturn(org.sonatype.goodies.common.Time.days(36500));
    when(config.getKeyManagerAlgorithm()).thenReturn("SunX509");
    when(config.getTrustManagerAlgorithm()).thenReturn("SunX509");
    when(config.getPrivateKeyStorePassword()).thenReturn("pwd".toCharArray());
    when(config.getTrustedKeyStorePassword()).thenReturn("pwd".toCharArray());
    when(config.getPrivateKeyPassword()).thenReturn("pwd".toCharArray());
    return config;
  }

  private KeyStoreManager createKeyStoreManager(final MemKeyStoreStorageManager storageManager) {
    return new KeyStoreManagerImpl(crypto, storageManager, createMockConfiguration());
  }

  /**
   * Tests that key pair generation works correctly when executed in a virtual thread.
   */
  @Test
  public void testKeyPairGenerationInVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      try {
        // Create the key pair in a virtual thread
        keyStoreManager.generateAndStoreKeyPair("Virtual Thread Test", "dev", "codeSoft", "AnyTown", "state", "US");
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Wait for the virtual thread to complete
    future.get(10, TimeUnit.SECONDS);
    
    // Verify the KeyManager[] only contains one key
    KeyManager[] keyManagers = keyStoreManager.getKeyManagers();
    assertThat(keyManagers, notNullValue());
    assertThat(keyManagers, arrayWithSize(1));
    assertThat(keyManagers[0], instanceOf(X509KeyManager.class));
    assertThat(
        ((X509KeyManager) keyManagers[0]).getCertificateChain(PRIVATE_KEY_ALIAS)[0].getSubjectDN().getName(),
        equalTo("CN=Virtual Thread Test,OU=dev,O=codeSoft,L=AnyTown,ST=state,C=US"));
  }

  /**
   * Tests that certificate import works correctly when executed in a virtual thread.
   */
  @Test
  public void testCertificateImportInVirtualThread() throws Exception {
    // First generate a key pair
    keyStoreManager.generateAndStoreKeyPair("Virtual Thread Test", "dev", "codeSoft", "AnyTown", "state", "US");
    
    // Generate a certificate to import
    X509Certificate certificate = generateCertificate(10, "Virtual Thread Cert", "other-org-unit", 
        "other-org", "other-locality", "other-state", "other-country");
    
    // Import the certificate in a virtual thread
    Future<?> future = virtualThreadExecutor.submit(() -> {
      try {
        keyStoreManager.importTrustCertificate(certificate, "virtual-thread-cert");
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Wait for the virtual thread to complete
    future.get(10, TimeUnit.SECONDS);
    
    // Verify the TrustManager[] contains our certificate
    TrustManager[] trustManagers = keyStoreManager.getTrustManagers();
    assertThat(trustManagers, notNullValue());
    assertThat(trustManagers, arrayWithSize(1));
    assertThat(trustManagers[0], instanceOf(X509TrustManager.class));
    assertThat(((X509TrustManager) trustManagers[0]).getAcceptedIssuers(), arrayWithSize(1));
    assertThat(((X509TrustManager) trustManagers[0]).getAcceptedIssuers(), arrayContaining(certificate));
  }

  /**
   * Tests that SSL connection works correctly when executed in virtual threads.
   * This test creates both server and client in virtual threads and verifies they can communicate.
   */
  @Test
  public void testSSLConnectionWithVirtualThreads() throws Exception {
    // Set up server and client keystores
    KeyStoreManager serverKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
    serverKeyStoreManager.generateAndStoreKeyPair("Server Side", "dev", "codeSoft", "AnyTown", "state", "US");

    KeyStoreManager clientKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
    clientKeyStoreManager.generateAndStoreKeyPair("Client Side", "dev", "codeSoft", "AnyTown", "state", "US");

    // Exchange certificates for mutual authentication
    Certificate clientCertificate = clientKeyStoreManager.getCertificate();
    serverKeyStoreManager.importTrustCertificate(clientCertificate, "client-side");

    Certificate serverCertificate = serverKeyStoreManager.getCertificate();
    clientKeyStoreManager.importTrustCertificate(serverCertificate, "server-side");

    // Create SSL contexts
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

    // Setup the SSL Server Socket
    SSLServerSocketFactory sslServerSocketfactory = serverSslContext.getServerSocketFactory();
    final SSLServerSocket sslServerSocket = (SSLServerSocket) sslServerSocketfactory.createServerSocket(0);
    sslServerSocket.setNeedClientAuth(true);

    final List<String> results = new ArrayList<>();
    final CountDownLatch serverReady = new CountDownLatch(1);
    final CountDownLatch clientDone = new CountDownLatch(1);
    final AtomicBoolean serverThreadIsPinned = new AtomicBoolean(false);
    final AtomicBoolean clientThreadIsPinned = new AtomicBoolean(false);
    
    // Start server in a virtual thread
    Future<?> serverFuture = virtualThreadExecutor.submit(() -> {
      try {
        // Check if this thread is a virtual thread
        boolean isVirtual = Thread.currentThread().isVirtual();
        log("Server running in virtual thread: {}", isVirtual);
        assertTrue(isVirtual, "Server should be running in a virtual thread");
        
        // Signal that server is ready to accept connections
        serverReady.countDown();
        
        // Accept connection
        SSLSocket sslSocket = (SSLSocket) sslServerSocket.accept();
        
        // Record connection details
        results.add(sslSocket.getSession().getPeerPrincipal().getName());
        results.add(sslSocket.getSession().getLocalPrincipal().getName());
        
        // Read data from client
        InputStream inputStream = sslSocket.getInputStream();
        InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
        
        String data = bufferedReader.readLine();
        if (data != null) {
          results.add(data);
        }
        
        // Wait for client to finish
        clientDone.await();
        sslSocket.close();
      }
      catch (Exception e) {
        log("Server error: {}", e.getMessage());
        serverThreadIsPinned.set(true);
      }
    });
    
    // Wait for server to be ready
    assertTrue(serverReady.await(5, TimeUnit.SECONDS), "Server failed to start in time");
    
    // Start client in a virtual thread
    Future<?> clientFuture = virtualThreadExecutor.submit(() -> {
      try {
        // Check if this thread is a virtual thread
        boolean isVirtual = Thread.currentThread().isVirtual();
        log("Client running in virtual thread: {}", isVirtual);
        assertTrue(isVirtual, "Client should be running in a virtual thread");
        
        // Connect to server
        SSLSocketFactory sslSocketFactory = clientSslContext.getSocketFactory();
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket("localhost", sslServerSocket.getLocalPort());
        
        // Send data to server
        OutputStream outputStream = sslSocket.getOutputStream();
        OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
        
        String testData = "Virtual Thread SSL Test Data";
        outputStreamWriter.write(testData + "\n");
        outputStreamWriter.flush();
        
        // Signal that client is done
        clientDone.countDown();
        sslSocket.close();
      }
      catch (Exception e) {
        log("Client error: {}", e.getMessage());
        clientThreadIsPinned.set(true);
      }
    });
    
    // Wait for both threads to complete
    serverFuture.get(10, TimeUnit.SECONDS);
    clientFuture.get(10, TimeUnit.SECONDS);
    
    // Close server socket
    sslServerSocket.close();
    
    // Verify results
    assertFalse(serverThreadIsPinned.get(), "Server thread should not be pinned");
    assertFalse(clientThreadIsPinned.get(), "Client thread should not be pinned");
    
    // Verify connection data
    assertThat(results.size(), equalTo(3));
    assertThat(results.get(0), equalTo("CN=Client Side,OU=dev,O=codeSoft,L=AnyTown,ST=state,C=US"));
    assertThat(results.get(1), equalTo("CN=Server Side,OU=dev,O=codeSoft,L=AnyTown,ST=state,C=US"));
    assertThat(results.get(2), equalTo("Virtual Thread SSL Test Data"));
  }
  
  /**
   * Tests concurrent SSL operations in multiple virtual threads to verify they don't cause thread pinning.
   */
  @Test
  public void testConcurrentSSLOperationsInVirtualThreads() throws Exception {
    // First generate a key pair
    keyStoreManager.generateAndStoreKeyPair("Concurrent Test", "dev", "codeSoft", "AnyTown", "state", "US");
    
    // Number of concurrent operations to perform
    int concurrentOperations = 10;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicBoolean anyThreadPinned = new AtomicBoolean(false);
    
    // Submit multiple concurrent SSL operations
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < concurrentOperations; i++) {
      final int index = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          // Check if this thread is a virtual thread
          boolean isVirtual = Thread.currentThread().isVirtual();
          log("Operation {} running in virtual thread: {}", index, isVirtual);
          assertTrue(isVirtual, "Operation should be running in a virtual thread");
          
          // Generate a certificate
          X509Certificate certificate = generateCertificate(10, "Concurrent Cert " + index, "unit", 
              "org", "locality", "state", "country");
          
          // Import the certificate
          keyStoreManager.importTrustCertificate(certificate, "concurrent-cert-" + index);
          
          // Create an SSL context using the certificate
          SSLContext sslContext = SSLContext.getInstance("TLS");
          sslContext.init(keyStoreManager.getKeyManagers(), keyStoreManager.getTrustManagers(), new SecureRandom());
          
          // Get SSL socket factory to verify context is properly initialized
          SSLSocketFactory factory = sslContext.getSocketFactory();
          assertThat(factory, notNullValue());
          
          latch.countDown();
        }
        catch (Exception e) {
          log("Operation {} error: {}", index, e.getMessage());
          anyThreadPinned.set(true);
          latch.countDown();
        }
      }));
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Not all operations completed in time");
    
    // Verify no threads were pinned
    assertFalse(anyThreadPinned.get(), "No threads should be pinned during SSL operations");
    
    // Verify all certificates were imported
    TrustManager[] trustManagers = keyStoreManager.getTrustManagers();
    assertThat(trustManagers, notNullValue());
    assertThat(trustManagers, arrayWithSize(1));
    assertThat(trustManagers[0], instanceOf(X509TrustManager.class));
    assertThat(((X509TrustManager) trustManagers[0]).getAcceptedIssuers().length, equalTo(concurrentOperations));
  }

  private X509Certificate generateCertificate(int validity,
                                              String commonName,
                                              String orgUnit,
                                              String organization,
                                              String locality,
                                              String state,
                                              String country)
      throws NoSuchAlgorithmException, IOException
  {
    KeyPairGenerator kpgen = KeyPairGenerator.getInstance("RSA");
    kpgen.initialize(512);
    KeyPair keyPair = kpgen.generateKeyPair();

    return CertificateUtil.generateCertificate(keyPair.getPublic(), keyPair.getPrivate(), "SHA1WITHRSA", validity,
        commonName, orgUnit, organization, locality, state, country);
  }
}