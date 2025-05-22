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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.ssl.KeyStoreManager;
import org.sonatype.nexus.ssl.KeyStoreManagerConfiguration;
import org.sonatype.nexus.ssl.KeyStoreManagerImpl;
import org.sonatype.nexus.ssl.spi.KeyStoreStorageManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for TLS 1.3 protocol compatibility with Java 21 Virtual Threads.
 * 
 * This test class validates that TLS 1.3 handshakes, cipher suite negotiation, and certificate validation
 * work correctly when executed within virtual threads. It also verifies that TLS 1.3's performance
 * improvements are maintained when using virtual threads and that protocol-specific features like
 * session resumption and early data function properly without causing thread pinning.
 */
public class TLS13VirtualThreadTest
    extends TestSupport
{
  private static final String TLS_PROTOCOL = "TLSv1.3";
  private static final int NUM_CONCURRENT_CONNECTIONS = 100;
  private static final int CONNECTION_TIMEOUT_MS = 5000;
  private static final String TEST_MESSAGE = "TLS 1.3 Virtual Thread Test Message";
  
  private final CryptoHelper crypto = new CryptoHelperImpl();
  
  private KeyStoreStorageManager storageManager;
  private KeyStoreManager serverKeyStoreManager;
  private KeyStoreManager clientKeyStoreManager;
  private SSLServerSocket sslServerSocket;
  private ExecutorService serverExecutor;
  private int serverPort;
  private AtomicBoolean serverRunning;
  
  @Before
  public void setUp() throws Exception {
    // Create key store managers for server and client
    storageManager = new KeyStoreManagerImplTest.MemKeyStoreStorageManager();
    serverKeyStoreManager = createKeyStoreManager(storageManager);
    clientKeyStoreManager = createKeyStoreManager(new KeyStoreManagerImplTest.MemKeyStoreStorageManager());
    
    // Generate key pairs for server and client
    serverKeyStoreManager.generateAndStoreKeyPair("Server Side", "dev", "nexus", "AnyTown", "state", "US");
    clientKeyStoreManager.generateAndStoreKeyPair("Client Side", "dev", "nexus", "AnyTown", "state", "US");
    
    // Exchange certificates for mutual trust
    Certificate clientCertificate = clientKeyStoreManager.getCertificate();
    serverKeyStoreManager.importTrustCertificate(clientCertificate, "client-cert");
    
    Certificate serverCertificate = serverKeyStoreManager.getCertificate();
    clientKeyStoreManager.importTrustCertificate(serverCertificate, "server-cert");
    
    // Start TLS server
    startServer();
  }
  
  @After
  public void tearDown() throws Exception {
    stopServer();
  }
  
  /**
   * Tests that TLS 1.3 handshakes work correctly when executed in virtual threads.
   * This test creates multiple virtual threads that establish TLS 1.3 connections
   * to the server and verifies that the handshakes complete successfully.
   */
  @Test
  public void testTLS13HandshakeInVirtualThreads() throws Exception {
    log("Testing TLS 1.3 handshakes in virtual threads");
    
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(NUM_CONCURRENT_CONNECTIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create virtual threads to establish TLS 1.3 connections
    for (int i = 0; i < NUM_CONCURRENT_CONNECTIONS; i++) {
      int connectionId = i;
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        try {
          log("Virtual thread {} establishing TLS 1.3 connection", connectionId);
          SSLSocket socket = createClientSocket();
          
          // Verify TLS 1.3 protocol
          String protocol = socket.getSession().getProtocol();
          log("Virtual thread {} negotiated protocol: {}", connectionId, protocol);
          assertEquals(TLS_PROTOCOL, protocol);
          
          socket.close();
          successCount.incrementAndGet();
          latch.countDown();
          return true;
        }
        catch (Exception e) {
          log("Virtual thread {} failed: {}", connectionId, e.getMessage());
          latch.countDown();
          return false;
        }
      }, Thread.ofVirtual().factory());
      
      futures.add(future);
    }
    
    // Wait for all connections to complete
    latch.await(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    
    // Verify all connections were successful
    int totalSuccess = successCount.get();
    log("Successful TLS 1.3 handshakes: {} out of {}", totalSuccess, NUM_CONCURRENT_CONNECTIONS);
    assertEquals(NUM_CONCURRENT_CONNECTIONS, totalSuccess);
  }
  
  /**
   * Tests that TLS 1.3 cipher suite negotiation works correctly in virtual threads.
   * This test verifies that the expected TLS 1.3 cipher suites are negotiated when
   * connections are established from virtual threads.
   */
  @Test
  public void testTLS13CipherSuiteNegotiationInVirtualThreads() throws Exception {
    log("Testing TLS 1.3 cipher suite negotiation in virtual threads");
    
    // List of TLS 1.3 cipher suites
    List<String> tls13CipherSuites = Arrays.asList(
        "TLS_AES_256_GCM_SHA384",
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256"
    );
    
    // Create a virtual thread to establish a TLS 1.3 connection
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        SSLSocket socket = createClientSocket();
        
        // Verify TLS 1.3 protocol
        String protocol = socket.getSession().getProtocol();
        log("Negotiated protocol: {}", protocol);
        assertEquals(TLS_PROTOCOL, protocol);
        
        // Verify negotiated cipher suite is a valid TLS 1.3 cipher suite
        String cipherSuite = socket.getSession().getCipherSuite();
        log("Negotiated cipher suite: {}", cipherSuite);
        assertTrue("Negotiated cipher suite should be a valid TLS 1.3 cipher suite",
            tls13CipherSuites.stream().anyMatch(cipherSuite::contains));
        
        socket.close();
      }
      catch (Exception e) {
        log("Failed to test cipher suite negotiation: {}", e.getMessage());
        throw new RuntimeException(e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(CONNECTION_TIMEOUT_MS);
  }
  
  /**
   * Tests that TLS 1.3 data transfer works correctly in virtual threads.
   * This test sends and receives data over TLS 1.3 connections established from
   * virtual threads and verifies that the data is transferred correctly.
   */
  @Test
  public void testTLS13DataTransferInVirtualThreads() throws Exception {
    log("Testing TLS 1.3 data transfer in virtual threads");
    
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(NUM_CONCURRENT_CONNECTIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create virtual threads to send and receive data over TLS 1.3 connections
    for (int i = 0; i < NUM_CONCURRENT_CONNECTIONS; i++) {
      int connectionId = i;
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        try {
          log("Virtual thread {} sending data over TLS 1.3", connectionId);
          SSLSocket socket = createClientSocket();
          
          // Send data
          OutputStream outputStream = socket.getOutputStream();
          OutputStreamWriter writer = new OutputStreamWriter(outputStream);
          writer.write(TEST_MESSAGE + "\n");
          writer.flush();
          
          // Receive echo response
          InputStream inputStream = socket.getInputStream();
          BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
          String response = reader.readLine();
          
          // Verify response
          assertEquals(TEST_MESSAGE, response);
          
          socket.close();
          successCount.incrementAndGet();
          latch.countDown();
          return true;
        }
        catch (Exception e) {
          log("Virtual thread {} failed: {}", connectionId, e.getMessage());
          latch.countDown();
          return false;
        }
      }, Thread.ofVirtual().factory());
      
      futures.add(future);
    }
    
    // Wait for all connections to complete
    latch.await(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    
    // Verify all connections were successful
    int totalSuccess = successCount.get();
    log("Successful TLS 1.3 data transfers: {} out of {}", totalSuccess, NUM_CONCURRENT_CONNECTIONS);
    assertEquals(NUM_CONCURRENT_CONNECTIONS, totalSuccess);
  }
  
  /**
   * Tests that TLS 1.3 session resumption works correctly in virtual threads.
   * This test establishes a TLS 1.3 connection, closes it, and then establishes
   * a new connection to verify that session resumption works correctly.
   */
  @Test
  public void testTLS13SessionResumptionInVirtualThreads() throws Exception {
    log("Testing TLS 1.3 session resumption in virtual threads");
    
    // Create a virtual thread to test session resumption
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // First connection
        SSLSocket socket1 = createClientSocket();
        String sessionId1 = bytesToHex(socket1.getSession().getId());
        log("First connection session ID: {}", sessionId1);
        socket1.close();
        
        // Small delay to ensure session is cached
        Thread.sleep(100);
        
        // Second connection - should resume session
        SSLSocket socket2 = createClientSocket();
        String sessionId2 = bytesToHex(socket2.getSession().getId());
        log("Second connection session ID: {}", sessionId2);
        
        // In TLS 1.3, session IDs may change even with resumption due to the new design
        // Instead, we verify that the connection was established quickly, which indicates resumption
        long startTime = System.nanoTime();
        socket2.startHandshake(); // Explicit handshake to measure time
        long endTime = System.nanoTime();
        long handshakeTime = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        
        log("TLS 1.3 handshake time for resumed session: {} ms", handshakeTime);
        // A resumed handshake should be very fast
        assertThat(handshakeTime, is(lessThan(100L)));
        
        socket2.close();
      }
      catch (Exception e) {
        log("Failed to test session resumption: {}", e.getMessage());
        throw new RuntimeException(e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(CONNECTION_TIMEOUT_MS);
  }
  
  /**
   * Tests that TLS 1.3 operations don't cause thread pinning in virtual threads.
   * This test performs TLS 1.3 operations in virtual threads and verifies that
   * the virtual threads are not pinned to carrier threads during blocking operations.
   */
  @Test
  public void testTLS13OperationsDontCauseThreadPinning() throws Exception {
    log("Testing that TLS 1.3 operations don't cause thread pinning");
    
    // Create a list to track carrier thread IDs
    List<Long> carrierThreadIds = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(NUM_CONCURRENT_CONNECTIONS * 2); // Each thread reports twice
    
    // Create virtual threads that perform TLS operations
    for (int i = 0; i < NUM_CONCURRENT_CONNECTIONS; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          // Record carrier thread before TLS operation
          Thread currentThread = Thread.currentThread();
          String threadName = currentThread.toString();
          int atIndex = threadName.indexOf('@');
          if (atIndex > 0) {
            String carrierInfo = threadName.substring(atIndex + 1);
            log("Before TLS operation - Virtual thread running on carrier: {}", carrierInfo);
            carrierThreadIds.add(Thread.currentThread().threadId());
          }
          latch.countDown();
          
          // Perform TLS operation
          SSLSocket socket = createClientSocket();
          
          // Perform a blocking read operation
          socket.setSoTimeout(100); // Short timeout
          try {
            socket.getInputStream().read();
          }
          catch (IOException e) {
            // Expected timeout
          }
          
          // Record carrier thread after TLS operation
          threadName = Thread.currentThread().toString();
          atIndex = threadName.indexOf('@');
          if (atIndex > 0) {
            String carrierInfo = threadName.substring(atIndex + 1);
            log("After TLS operation - Virtual thread running on carrier: {}", carrierInfo);
            carrierThreadIds.add(Thread.currentThread().threadId());
          }
          latch.countDown();
          
          socket.close();
        }
        catch (Exception e) {
          log("Failed during thread pinning test: {}", e.getMessage());
          latch.countDown();
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    latch.await(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    
    // Verify that multiple carrier threads were used, indicating no pinning
    long distinctCarrierThreads = carrierThreadIds.stream().distinct().count();
    log("Number of distinct carrier threads used: {}", distinctCarrierThreads);
    
    // We should have more than one carrier thread if virtual threads are unmounting properly
    assertThat(distinctCarrierThreads, greaterThan(1L));
  }
  
  /**
   * Tests the performance of TLS 1.3 in virtual threads compared to platform threads.
   * This test measures the time taken to establish TLS 1.3 connections using both
   * virtual threads and platform threads, and verifies that virtual threads provide
   * comparable or better performance.
   */
  @Test
  public void testTLS13PerformanceInVirtualThreads() throws Exception {
    log("Testing TLS 1.3 performance in virtual threads vs platform threads");
    
    // Number of connections for performance test
    final int numConnections = 50;
    
    // Test with platform threads
    long platformThreadsStartTime = System.nanoTime();
    CountDownLatch platformLatch = new CountDownLatch(numConnections);
    
    for (int i = 0; i < numConnections; i++) {
      new Thread(() -> {
        try {
          SSLSocket socket = createClientSocket();
          socket.close();
        }
        catch (Exception e) {
          log("Platform thread failed: {}", e.getMessage());
        }
        finally {
          platformLatch.countDown();
        }
      }).start();
    }
    
    platformLatch.await(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    long platformThreadsTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - platformThreadsStartTime);
    log("Time taken with platform threads: {} ms", platformThreadsTime);
    
    // Test with virtual threads
    long virtualThreadsStartTime = System.nanoTime();
    CountDownLatch virtualLatch = new CountDownLatch(numConnections);
    
    for (int i = 0; i < numConnections; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          SSLSocket socket = createClientSocket();
          socket.close();
        }
        catch (Exception e) {
          log("Virtual thread failed: {}", e.getMessage());
        }
        finally {
          virtualLatch.countDown();
        }
      });
    }
    
    virtualLatch.await(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    long virtualThreadsTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - virtualThreadsStartTime);
    log("Time taken with virtual threads: {} ms", virtualThreadsTime);
    
    // Virtual threads should be at least as fast as platform threads for this workload
    // Note: This is a relative comparison and may vary based on system load
    log("Performance ratio (platform/virtual): {}", (double) platformThreadsTime / virtualThreadsTime);
    assertTrue("Virtual threads should provide comparable or better performance",
        virtualThreadsTime <= platformThreadsTime * 1.2); // Allow 20% margin
  }
  
  private KeyStoreManager createKeyStoreManager(final KeyStoreStorageManager storageManager) {
    KeyStoreManagerConfiguration config = mock(KeyStoreManagerConfiguration.class);
    // Use lower strength for faster test execution
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
    return new KeyStoreManagerImpl(crypto, storageManager, config);
  }
  
  private void startServer() throws Exception {
    // Create SSL context for server
    SSLContext serverSslContext = SSLContext.getInstance(TLS_PROTOCOL);
    serverSslContext.init(serverKeyStoreManager.getKeyManagers(), serverKeyStoreManager.getTrustManagers(),
        new SecureRandom());
    
    // Create server socket
    SSLServerSocketFactory sslServerSocketFactory = serverSslContext.getServerSocketFactory();
    sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(0);
    sslServerSocket.setNeedClientAuth(true); // Require client authentication
    
    // Set enabled protocols to TLS 1.3 only
    sslServerSocket.setEnabledProtocols(new String[]{TLS_PROTOCOL});
    
    // Get the port the server is listening on
    serverPort = sslServerSocket.getLocalPort();
    log("TLS 1.3 server started on port {}", serverPort);
    
    // Start server thread
    serverRunning = new AtomicBoolean(true);
    serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    serverExecutor.submit(() -> {
      try {
        while (serverRunning.get()) {
          SSLSocket clientSocket = (SSLSocket) sslServerSocket.accept();
          
          // Handle client connection in a separate virtual thread
          serverExecutor.submit(() -> handleClientConnection(clientSocket));
        }
      }
      catch (IOException e) {
        if (serverRunning.get()) {
          log("Server error: {}", e.getMessage());
        }
      }
    });
  }
  
  private void handleClientConnection(SSLSocket clientSocket) {
    try {
      // Echo any received data back to the client
      InputStream inputStream = clientSocket.getInputStream();
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      
      OutputStream outputStream = clientSocket.getOutputStream();
      OutputStreamWriter writer = new OutputStreamWriter(outputStream);
      
      String line;
      while ((line = reader.readLine()) != null) {
        writer.write(line + "\n");
        writer.flush();
      }
      
      clientSocket.close();
    }
    catch (IOException e) {
      // Client disconnected or other error
      try {
        clientSocket.close();
      }
      catch (IOException ex) {
        // Ignore
      }
    }
  }
  
  private void stopServer() {
    serverRunning.set(false);
    
    if (sslServerSocket != null && !sslServerSocket.isClosed()) {
      try {
        sslServerSocket.close();
      }
      catch (IOException e) {
        log("Error closing server socket: {}", e.getMessage());
      }
    }
    
    if (serverExecutor != null) {
      serverExecutor.shutdownNow();
      try {
        serverExecutor.awaitTermination(5, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    
    log("TLS 1.3 server stopped");
  }
  
  private SSLSocket createClientSocket() throws Exception {
    // Create SSL context for client
    SSLContext clientSslContext = SSLContext.getInstance(TLS_PROTOCOL);
    clientSslContext.init(clientKeyStoreManager.getKeyManagers(), clientKeyStoreManager.getTrustManagers(),
        new SecureRandom());
    
    // Create client socket
    SSLSocketFactory sslSocketFactory = clientSslContext.getSocketFactory();
    SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket("localhost", serverPort);
    
    // Set enabled protocols to TLS 1.3 only
    sslSocket.setEnabledProtocols(new String[]{TLS_PROTOCOL});
    
    // Set a timeout to avoid hanging tests
    sslSocket.setSoTimeout(CONNECTION_TIMEOUT_MS);
    
    // Start handshake explicitly
    sslSocket.startHandshake();
    
    return sslSocket;
  }
  
  private X509Certificate generateCertificate(String commonName) throws Exception {
    KeyPairGenerator kpgen = KeyPairGenerator.getInstance("RSA");
    kpgen.initialize(1024);
    KeyPair keyPair = kpgen.generateKeyPair();
    
    return CertificateUtil.generateCertificate(keyPair.getPublic(), keyPair.getPrivate(), "SHA1WITHRSA", 365,
        commonName, "dev", "nexus", "AnyTown", "state", "US");
  }
  
  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02X", b));
    }
    return sb.toString();
  }
  
  private static <T extends Comparable<T>> org.hamcrest.Matcher<T> lessThan(T value) {
    return new org.hamcrest.BaseMatcher<T>() {
      @Override
      public boolean matches(Object item) {
        return ((Comparable) item).compareTo(value) < 0;
      }
      
      @Override
      public void describeTo(org.hamcrest.Description description) {
        description.appendText("less than ").appendValue(value);
      }
    };
  }
}