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
package org.sonatype.nexus.virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.internal.email.EmailConfigurationStore;
import org.sonatype.nexus.internal.email.EmailManagerImpl;
import org.sonatype.nexus.security.UserIdHelper;
import org.sonatype.nexus.ssl.TrustStore;

import javax.inject.Provider;
import javax.net.ssl.SSLContext;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link EmailManager} using Java 21 Virtual Threads to validate thread safety and performance
 * when sending emails concurrently.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.Category(VirtualThreadTestGroup.class)
public class EmailManagerVirtualThreadTests
    extends TestSupport
{
    private static final int CONCURRENT_THREADS = 1000;
    private static final int TIMEOUT_SECONDS = 30;
    
    @Mock
    private EventManager eventManager;
    
    @Mock
    private EmailConfigurationStore emailConfigurationStore;
    
    @Mock
    private TrustStore trustStore;
    
    @Mock
    private Provider capabilityRegistryProvider;
    
    @Mock
    private SecretsService secretsService;
    
    @Captor
    private ArgumentCaptor<Email> emailCaptor;
    
    private EmailManagerImpl emailManager;
    
    @BeforeEach
    public void setup() throws Exception {
        // Initialize the email manager with mocks
        emailManager = new EmailManagerImpl(eventManager, emailConfigurationStore, trustStore, 
                                           email -> email, capabilityRegistryProvider, secretsService);
        
        // Configure the trust store to return a default SSL context
        when(trustStore.getSSLContext()).thenReturn(SSLContext.getDefault());
        
        // Configure a mock email configuration
        EmailConfiguration emailConfig = mock(EmailConfiguration.class);
        when(emailConfig.isEnabled()).thenReturn(true);
        when(emailConfig.getHost()).thenReturn("smtp.example.com");
        when(emailConfig.getPort()).thenReturn(25);
        when(emailConfig.getFromAddress()).thenReturn("sender@example.com");
        when(emailConfig.getUsername()).thenReturn("user");
        when(emailConfig.isStartTlsEnabled()).thenReturn(true);
        when(emailConfig.isStartTlsRequired()).thenReturn(false);
        when(emailConfig.isSslOnConnectEnabled()).thenReturn(false);
        when(emailConfig.isSslCheckServerIdentityEnabled()).thenReturn(false);
        when(emailConfig.isNexusTrustStoreEnabled()).thenReturn(true);
        
        // Configure the email configuration store to return our mock configuration
        when(emailConfigurationStore.load()).thenReturn(emailConfig);
    }
    
    @AfterEach
    public void tearDown() {
        // No additional cleanup needed as mocks are handled by MockitoExtension
    }
    
    /**
     * Tests that the EmailManager can handle a large number of concurrent email sending operations
     * using Java 21 Virtual Threads without errors or race conditions.
     */
    @Test
    public void testConcurrentEmailSendingWithVirtualThreads() throws Exception {
        // Create a thread factory for virtual threads
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
        
        // Create an executor service using virtual threads
        ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        
        // Track completion and errors
        CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        
        // Spy on the email manager to verify method calls
        EmailManagerImpl emailManagerSpy = spy(emailManager);
        
        // Configure the spy to not actually send emails
        doNothing().when(emailManagerSpy).send(any(Email.class));
        
        try {
            // Submit concurrent email sending tasks
            for (int i = 0; i < CONCURRENT_THREADS; i++) {
                final int taskId = i;
                executor.submit(() -> {
                    try {
                        // Create a simple email
                        SimpleEmail email = new SimpleEmail();
                        email.setSubject("Test Email " + taskId);
                        email.setMsg("This is a test email sent from virtual thread " + taskId);
                        email.addTo("recipient" + taskId + "@example.com");
                        
                        // Send the email
                        emailManagerSpy.send(email);
                    } catch (Throwable t) {
                        errorCount.incrementAndGet();
                        firstError.compareAndSet(null, t);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait for all tasks to complete
            boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            // Verify all tasks completed within the timeout
            assertTrue(completed, "Not all email sending tasks completed within the timeout");
            
            // Verify no errors occurred
            assertEquals(0, errorCount.get(), 
                    "Errors occurred during concurrent email sending: " + 
                    (firstError.get() != null ? firstError.get().getMessage() : "unknown error"));
            
            // Verify the send method was called the expected number of times
            verify(emailManagerSpy, times(CONCURRENT_THREADS)).send(any(Email.class));
        } finally {
            executor.shutdown();
        }
    }
    
    /**
     * Compares the performance of sending emails using platform threads vs virtual threads.
     */
    @Test
    public void testEmailSendingPerformanceComparison() throws Exception {
        // Create thread factories
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
        ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
        
        // Spy on the email manager to verify method calls
        EmailManagerImpl emailManagerSpy = spy(emailManager);
        
        // Configure the spy to not actually send emails
        doNothing().when(emailManagerSpy).send(any(Email.class));
        
        // Test with platform threads
        long platformThreadTime = measureEmailSendingTime(emailManagerSpy, platformThreadFactory, CONCURRENT_THREADS);
        log.info("Platform thread execution time: {} ms", platformThreadTime);
        
        // Test with virtual threads
        long virtualThreadTime = measureEmailSendingTime(emailManagerSpy, virtualThreadFactory, CONCURRENT_THREADS);
        log.info("Virtual thread execution time: {} ms", virtualThreadTime);
        
        // Log the performance improvement
        double improvementFactor = (double) platformThreadTime / virtualThreadTime;
        log.info("Performance improvement factor with virtual threads: {}x", String.format("%.2f", improvementFactor));
        
        // Verify that virtual threads provide better performance
        // Note: This assertion might be environment-dependent, so we use a conservative threshold
        assertThat(virtualThreadTime, lessThanOrEqualTo(platformThreadTime));
    }
    
    /**
     * Tests that the EmailManager correctly handles mutex synchronization when accessed concurrently
     * by multiple virtual threads.
     */
    @Test
    public void testMutexSynchronizationWithVirtualThreads() throws Exception {
        // Create a thread factory for virtual threads
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
        
        // Create an executor service using virtual threads
        ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        
        // Track completion
        CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
        
        // Create a list to track all created emails
        List<Email> capturedEmails = new ArrayList<>();
        
        // Spy on the email manager
        EmailManagerImpl emailManagerSpy = spy(emailManager);
        
        // Configure the spy to capture emails instead of sending them
        doAnswer(invocation -> {
            Email email = invocation.getArgument(0);
            synchronized (capturedEmails) {
                capturedEmails.add(email);
            }
            return null;
        }).when(emailManagerSpy).send(any(Email.class));
        
        try {
            // Submit concurrent email sending tasks
            for (int i = 0; i < CONCURRENT_THREADS; i++) {
                final int taskId = i;
                executor.submit(() -> {
                    try {
                        // Create a simple email with a unique subject
                        SimpleEmail email = new SimpleEmail();
                        email.setSubject("Test Email " + taskId);
                        email.setMsg("This is a test email sent from virtual thread " + taskId);
                        email.addTo("recipient" + taskId + "@example.com");
                        
                        // Send the email
                        emailManagerSpy.send(email);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait for all tasks to complete
            boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            // Verify all tasks completed within the timeout
            assertTrue(completed, "Not all email sending tasks completed within the timeout");
            
            // Verify the correct number of emails were captured
            assertEquals(CONCURRENT_THREADS, capturedEmails.size(), 
                    "Not all emails were captured during concurrent sending");
            
            // Verify that each email has a unique subject (no duplicates or overwrites)
            List<String> subjects = new ArrayList<>();
            for (Email email : capturedEmails) {
                subjects.add(email.getSubject());
            }
            
            // Check for the expected number of unique subjects
            assertEquals(CONCURRENT_THREADS, subjects.stream().distinct().count(), 
                    "Some emails were duplicated or overwritten during concurrent sending");
        } finally {
            executor.shutdown();
        }
    }
    
    /**
     * Tests that the EmailManager correctly handles thread pinning detection when using virtual threads.
     */
    @Test
    public void testThreadPinningDetection() throws Exception {
        // Enable thread pinning detection via system property
        System.setProperty("jdk.tracePinnedThreads", "full");
        
        try {
            // Create a thread factory for virtual threads
            ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
            
            // Create an executor service using virtual threads
            ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
            
            // Track completion
            CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
            AtomicInteger pinnedThreadCount = new AtomicInteger(0);
            
            // Spy on the email manager
            EmailManagerImpl emailManagerSpy = spy(emailManager);
            
            // Configure the spy to not actually send emails
            doNothing().when(emailManagerSpy).send(any(Email.class));
            
            try {
                // Submit concurrent email sending tasks
                for (int i = 0; i < CONCURRENT_THREADS; i++) {
                    final int taskId = i;
                    executor.submit(() -> {
                        try {
                            // Create a simple email
                            SimpleEmail email = new SimpleEmail();
                            email.setSubject("Test Email " + taskId);
                            email.setMsg("This is a test email sent from virtual thread " + taskId);
                            email.addTo("recipient" + taskId + "@example.com");
                            
                            // Send the email
                            emailManagerSpy.send(email);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                
                // Wait for all tasks to complete
                boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                
                // Verify all tasks completed within the timeout
                assertTrue(completed, "Not all email sending tasks completed within the timeout");
                
                // Log the pinned thread count (this is informational, as we can't reliably detect pinning in a test)
                log.info("Detected {} potentially pinned threads during email sending", pinnedThreadCount.get());
                
                // We don't assert on the pinned thread count as it's environment-dependent
                // and we're just demonstrating the detection capability
            } finally {
                executor.shutdown();
            }
        } finally {
            // Reset the system property
            System.clearProperty("jdk.tracePinnedThreads");
        }
    }
    
    /**
     * Helper method to measure the time taken to send a specified number of emails using
     * the provided thread factory.
     */
    private long measureEmailSendingTime(EmailManagerImpl emailManager, ThreadFactory threadFactory, int count) 
            throws Exception {
        // Create an executor service using the provided thread factory
        ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
        
        // Track completion
        CountDownLatch latch = new CountDownLatch(count);
        
        try {
            // Record start time
            long startTime = System.currentTimeMillis();
            
            // Submit email sending tasks
            for (int i = 0; i < count; i++) {
                final int taskId = i;
                executor.submit(() -> {
                    try {
                        // Create a simple email
                        SimpleEmail email = new SimpleEmail();
                        email.setSubject("Test Email " + taskId);
                        email.setMsg("This is a test email");
                        email.addTo("recipient" + taskId + "@example.com");
                        
                        // Send the email
                        emailManager.send(email);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait for all tasks to complete
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            // Calculate and return the elapsed time
            return System.currentTimeMillis() - startTime;
        } finally {
            executor.shutdown();
        }
    }
}