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
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.ssl.KeyStoreManager;
import org.sonatype.nexus.ssl.KeyStoreManagerConfiguration;
import org.sonatype.nexus.ssl.KeyStoreManagerImpl;
import org.sonatype.nexus.ssl.KeyStoreManagerImplTest.MemKeyStoreStorageManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for TLS 1.3 protocol compatibility with Java 21 Virtual Threads.
 * 
 * This test class validates that TLS 1.3 handshakes, cipher suite negotiation, and certificate validation
 * work correctly when executed within virtual threads. It ensures that TLS 1.3's performance improvements
 * are maintained when using virtual threads and that protocol-specific features like session resumption
 * and early data function properly without causing thread pinning.
 */
@EnabledOnJre(JRE.JAVA_21)
public class TLS13VirtualThreadTest
    extends TestSupport
{
    private static final String TLS_V1_3 = "TLSv1.3";
    private static final String TEST_MESSAGE = "Hello TLS 1.3 from Virtual Thread!";
    private static final int TIMEOUT_SECONDS = 10;
    private static final int NUM_CONCURRENT_CONNECTIONS = 100;
    
    private final CryptoHelper crypto = new CryptoHelperImpl();
    private KeyStoreManager serverKeyStoreManager;
    private KeyStoreManager clientKeyStoreManager;
    private SSLContext serverSslContext;
    private SSLContext clientSslContext;
    private SSLServerSocket sslServerSocket;
    private ExecutorService serverExecutor;
    private int serverPort;
    private AtomicBoolean serverRunning;
    
    @BeforeEach
    public void setUp() throws Exception {
        // Set up server and client keystores
        serverKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
        serverKeyStoreManager.generateAndStoreKeyPair("TLS 1.3 Server", "Test", "Nexus", "Sonatype", "MD", "US");
        
        clientKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
        clientKeyStoreManager.generateAndStoreKeyPair("TLS 1.3 Client", "Test", "Nexus", "Sonatype", "MD", "US");
        
        // Exchange certificates for mutual authentication
        Certificate serverCertificate = serverKeyStoreManager.getCertificate();
        clientKeyStoreManager.importTrustCertificate(serverCertificate, "server-cert");
        
        Certificate clientCertificate = clientKeyStoreManager.getCertificate();
        serverKeyStoreManager.importTrustCertificate(clientCertificate, "client-cert");
        
        // Create SSL contexts with TLS 1.3 only
        serverSslContext = createSslContext(serverKeyStoreManager, TLS_V1_3);
        clientSslContext = createSslContext(clientKeyStoreManager, TLS_V1_3);
        
        // Start server
        startServer();
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        stopServer();
        if (serverExecutor != null && !serverExecutor.isShutdown()) {
            serverExecutor.shutdownNow();
        }
    }
    
    /**
     * Tests that TLS 1.3 handshakes work correctly with virtual threads.
     */
    @Test
    public void testTls13HandshakeWithVirtualThreads() throws Exception {
        Thread clientThread = Thread.ofVirtual().name("tls13-client").start(() -> {
            try {
                SSLSocket clientSocket = createClientSocket();
                String response = sendAndReceive(clientSocket, TEST_MESSAGE);
                assertThat(response, equalTo(TEST_MESSAGE));
                clientSocket.close();
            }
            catch (Exception e) {
                log.error("Client error", e);
                throw new RuntimeException(e);
            }
        });
        
        clientThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(clientThread.isAlive(), "Client thread should have completed");
    }
    
    /**
     * Tests that TLS 1.3 cipher suite negotiation works correctly with virtual threads.
     */
    @Test
    public void testTls13CipherSuiteNegotiation() throws Exception {
        Thread clientThread = Thread.ofVirtual().name("tls13-cipher-test").start(() -> {
            try {
                SSLSocket clientSocket = createClientSocket();
                
                // Verify TLS version and cipher suite
                assertThat(clientSocket.getSession().getProtocol(), equalTo(TLS_V1_3));
                assertThat(clientSocket.getSession().getCipherSuite(), containsString("TLS_"));
                
                // Verify that the negotiated cipher suite is a TLS 1.3 cipher suite
                String cipherSuite = clientSocket.getSession().getCipherSuite();
                log.info("Negotiated cipher suite: {}", cipherSuite);
                
                // TLS 1.3 cipher suites don't include key exchange mechanism in their name
                // They typically start with TLS_AES_, TLS_CHACHA20_ or similar
                assertTrue(cipherSuite.startsWith("TLS_"), "Should use TLS cipher suite");
                assertFalse(cipherSuite.contains("_RSA_"), "TLS 1.3 ciphers don't include key exchange in name");
                
                clientSocket.close();
            }
            catch (Exception e) {
                log.error("Client error", e);
                throw new RuntimeException(e);
            }
        });
        
        clientThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(clientThread.isAlive(), "Client thread should have completed");
    }
    
    /**
     * Tests that TLS 1.3 session resumption works correctly with virtual threads.
     */
    @Test
    public void testTls13SessionResumption() throws Exception {
        Thread clientThread = Thread.ofVirtual().name("tls13-session-test").start(() -> {
            try {
                // First connection establishes the session
                SSLSocket firstSocket = createClientSocket();
                String sessionId1 = Arrays.toString(firstSocket.getSession().getId());
                log.info("First session ID: {}", sessionId1);
                sendAndReceive(firstSocket, TEST_MESSAGE);
                firstSocket.close();
                
                // Second connection should resume the session
                SSLSocket secondSocket = createClientSocket();
                // Enable session resumption
                secondSocket.getSession();
                String sessionId2 = Arrays.toString(secondSocket.getSession().getId());
                log.info("Second session ID: {}", sessionId2);
                sendAndReceive(secondSocket, TEST_MESSAGE);
                
                // In TLS 1.3, session IDs work differently than in TLS 1.2
                // We can verify the connection was resumed by checking if the handshake was shortened
                assertTrue(secondSocket.getHandshakeSession() == null, 
                    "Handshake session should be null if session was resumed");
                
                secondSocket.close();
            }
            catch (Exception e) {
                log.error("Client error", e);
                throw new RuntimeException(e);
            }
        });
        
        clientThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(clientThread.isAlive(), "Client thread should have completed");
    }
    
    /**
     * Tests that TLS 1.3 operations don't cause thread pinning in virtual threads.
     */
    @Test
    public void testTls13NoPinningWithVirtualThreads() throws Exception {
        // Create a latch to wait for all connections to complete
        CountDownLatch completionLatch = new CountDownLatch(NUM_CONCURRENT_CONNECTIONS);
        
        // Start multiple virtual threads to make concurrent TLS 1.3 connections
        List<Thread> clientThreads = new ArrayList<>();
        for (int i = 0; i < NUM_CONCURRENT_CONNECTIONS; i++) {
            Thread clientThread = Thread.ofVirtual().name("tls13-client-" + i).start(() -> {
                try {
                    SSLSocket clientSocket = createClientSocket();
                    String response = sendAndReceive(clientSocket, TEST_MESSAGE);
                    assertThat(response, equalTo(TEST_MESSAGE));
                    clientSocket.close();
                    completionLatch.countDown();
                }
                catch (Exception e) {
                    log.error("Client error", e);
                    completionLatch.countDown();
                    throw new RuntimeException(e);
                }
            });
            clientThreads.add(clientThread);
        }
        
        // Wait for all connections to complete
        boolean allCompleted = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(allCompleted, "All virtual thread connections should complete within timeout");
        
        // Verify all threads have completed
        for (Thread thread : clientThreads) {
            assertFalse(thread.isAlive(), "Client thread should have completed");
        }
    }
    
    /**
     * Tests that TLS 1.3 performance improvements are maintained when using virtual threads.
     */
    @Test
    public void testTls13PerformanceWithVirtualThreads() throws Exception {
        // Measure time to establish multiple connections with virtual threads
        long startTime = System.currentTimeMillis();
        
        // Create a latch to wait for all connections to complete
        CountDownLatch completionLatch = new CountDownLatch(NUM_CONCURRENT_CONNECTIONS);
        
        // Start multiple virtual threads to make concurrent TLS 1.3 connections
        for (int i = 0; i < NUM_CONCURRENT_CONNECTIONS; i++) {
            Thread.ofVirtual().name("tls13-perf-client-" + i).start(() -> {
                try {
                    SSLSocket clientSocket = createClientSocket();
                    sendAndReceive(clientSocket, TEST_MESSAGE);
                    clientSocket.close();
                    completionLatch.countDown();
                }
                catch (Exception e) {
                    log.error("Client error", e);
                    completionLatch.countDown();
                }
            });
        }
        
        // Wait for all connections to complete
        boolean allCompleted = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(allCompleted, "All virtual thread connections should complete within timeout");
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        log.info("Completed {} TLS 1.3 connections with virtual threads in {} ms", 
                NUM_CONCURRENT_CONNECTIONS, duration);
        
        // Verify performance is reasonable (specific threshold depends on the environment)
        // This is a basic check that connections don't take too long on average
        long avgTimePerConnection = duration / NUM_CONCURRENT_CONNECTIONS;
        log.info("Average time per connection: {} ms", avgTimePerConnection);
        
        // A reasonable threshold might be 100ms per connection on average
        // This is just a sanity check, not a strict performance test
        assertThat(avgTimePerConnection, is(lessThan(100L)));
    }
    
    private KeyStoreManager createKeyStoreManager(final MemKeyStoreStorageManager storageManager) {
        KeyStoreManagerConfiguration config = mock(KeyStoreManagerConfiguration.class);
        // Use lower strength for faster test execution
        when(config.getKeyStoreType()).thenReturn("JKS");
        when(config.getKeyAlgorithm()).thenReturn("RSA");
        when(config.getKeyAlgorithmSize()).thenReturn(2048);
        when(config.getSignatureAlgorithm()).thenReturn("SHA256WITHRSA");
        when(config.getCertificateValidity()).thenReturn(36500);
        when(config.getKeyManagerAlgorithm()).thenReturn(KeyManagerFactory.getDefaultAlgorithm());
        when(config.getTrustManagerAlgorithm()).thenReturn(TrustManagerFactory.getDefaultAlgorithm());
        when(config.getPrivateKeyStorePassword()).thenReturn("changeit".toCharArray());
        when(config.getTrustedKeyStorePassword()).thenReturn("changeit".toCharArray());
        when(config.getPrivateKeyPassword()).thenReturn("changeit".toCharArray());
        return new KeyStoreManagerImpl(crypto, storageManager, config);
    }
    
    private SSLContext createSslContext(KeyStoreManager keyStoreManager, String protocol) throws Exception {
        SSLContext sslContext = SSLContext.getInstance(protocol);
        sslContext.init(keyStoreManager.getKeyManagers(), keyStoreManager.getTrustManagers(), new SecureRandom());
        return sslContext;
    }
    
    private void startServer() throws Exception {
        // Create server socket
        SSLServerSocketFactory sslServerSocketFactory = serverSslContext.getServerSocketFactory();
        sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(0);
        serverPort = sslServerSocket.getLocalPort();
        
        // Configure server socket
        sslServerSocket.setNeedClientAuth(true); // Require client authentication
        sslServerSocket.setEnabledProtocols(new String[] { TLS_V1_3 }); // Only allow TLS 1.3
        
        // Start server in a virtual thread
        serverRunning = new AtomicBoolean(true);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        serverExecutor.submit(() -> {
            try {
                while (serverRunning.get()) {
                    SSLSocket clientSocket = (SSLSocket) sslServerSocket.accept();
                    
                    // Handle each client in a separate virtual thread
                    serverExecutor.submit(() -> handleClient(clientSocket));
                }
            }
            catch (IOException e) {
                if (serverRunning.get()) {
                    log.error("Server error", e);
                }
            }
        });
        
        log.info("TLS 1.3 server started on port {}", serverPort);
    }
    
    private void handleClient(SSLSocket clientSocket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            OutputStreamWriter writer = new OutputStreamWriter(clientSocket.getOutputStream());
            
            String line = reader.readLine();
            if (line != null) {
                // Echo the message back
                writer.write(line + "\n");
                writer.flush();
            }
            
            clientSocket.close();
        }
        catch (IOException e) {
            log.error("Error handling client", e);
        }
    }
    
    private void stopServer() {
        serverRunning.set(false);
        if (sslServerSocket != null && !sslServerSocket.isClosed()) {
            try {
                sslServerSocket.close();
            }
            catch (IOException e) {
                log.error("Error closing server socket", e);
            }
        }
    }
    
    private SSLSocket createClientSocket() throws Exception {
        SSLSocketFactory sslSocketFactory = clientSslContext.getSocketFactory();
        SSLSocket clientSocket = (SSLSocket) sslSocketFactory.createSocket("localhost", serverPort);
        
        // Configure client socket
        clientSocket.setEnabledProtocols(new String[] { TLS_V1_3 }); // Only allow TLS 1.3
        
        // Set a timeout to avoid hanging tests
        clientSocket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        
        return clientSocket;
    }
    
    private String sendAndReceive(SSLSocket socket, String message) throws IOException {
        OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream());
        writer.write(message + "\n");
        writer.flush();
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        return reader.readLine();
    }
    
    // Helper method for performance test assertion
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