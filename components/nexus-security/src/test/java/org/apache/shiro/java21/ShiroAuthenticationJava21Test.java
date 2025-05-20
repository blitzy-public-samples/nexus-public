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
package org.apache.shiro.java21;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.SimpleCredentialsMatcher;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Apache Shiro's authentication mechanisms under Java 21, verifying that realm authentication,
 * token validation, and subject creation work correctly with Java 21 features. Focuses on validating
 * that authentication operations remain secure and consistent when executed using Virtual Threads
 * and when leveraging Java 21's pattern matching features.
 */
public class ShiroAuthenticationJava21Test {

    private DefaultSecurityManager securityManager;
    private List<Realm> realms;

    @BeforeEach
    public void setUp() {
        // Clear any ThreadContext bindings from previous tests
        ThreadContext.remove();
        
        // Create and configure realms
        realms = new ArrayList<>();
        realms.add(new TestRealm("admin", "admin123", "AdminRealm"));
        realms.add(new TestRealm("user", "user123", "UserRealm"));
        realms.add(new TestRealm("guest", "guest123", "GuestRealm"));
        
        // Create and configure the security manager
        securityManager = new DefaultSecurityManager(realms);
        SecurityUtils.setSecurityManager(securityManager);
    }
    
    @AfterEach
    public void tearDown() {
        // Clean up ThreadContext to prevent memory leaks
        ThreadContext.remove();
        if (securityManager != null) {
            securityManager.destroy();
        }
        SecurityUtils.setSecurityManager(null);
    }

    /**
     * Tests basic authentication using Java 21's pattern matching for instanceof
     * to improve test assertions and token validation.
     */
    @Test
    @DisplayName("Test authentication with Java 21 pattern matching")
    public void testAuthenticationWithPatternMatching() {
        // Create authentication token
        AuthenticationToken token = new UsernamePasswordToken("admin", "admin123");
        
        // Use Java 21 pattern matching for instanceof to validate token type and extract credentials
        if (token instanceof UsernamePasswordToken upToken) {
            assertEquals("admin", upToken.getUsername());
            assertArrayEquals("admin123".toCharArray(), upToken.getPassword());
        } else {
            fail("Token should be an instance of UsernamePasswordToken");
        }
        
        // Authenticate
        Subject subject = SecurityUtils.getSubject();
        subject.login(token);
        
        // Verify authentication succeeded
        assertTrue(subject.isAuthenticated());
        assertEquals("admin", subject.getPrincipal());
        
        // Test logout
        subject.logout();
        assertFalse(subject.isAuthenticated());
    }

    /**
     * Tests authentication across different realm types using Virtual Threads.
     * Verifies that all pluggable realms remain compatible with the Java 21 runtime.
     */
    @Test
    @DisplayName("Test authentication across realms using Virtual Threads")
    public void testAuthenticationAcrossRealmsWithVirtualThreads() throws Exception {
        final int THREAD_COUNT = 10;
        final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        final AtomicInteger successCount = new AtomicInteger(0);
        
        // Create and start virtual threads for each realm test
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int index = i % 3; // Cycle through the 3 test users
            String username = switch(index) {
                case 0 -> "admin";
                case 1 -> "user";
                case 2 -> "guest";
                default -> throw new IllegalStateException("Unexpected index: " + index);
            };
            
            String password = username + "123";
            
            // Use Java 21 Virtual Thread
            Thread.ofVirtual().name("auth-test-" + i).start(() -> {
                try {
                    // Create a new subject for this thread
                    Subject threadSubject = new Subject.Builder(securityManager).buildSubject();
                    ThreadContext.bind(threadSubject);
                    
                    // Login with the appropriate credentials
                    AuthenticationToken token = new UsernamePasswordToken(username, password);
                    threadSubject.login(token);
                    
                    // Verify authentication succeeded
                    if (threadSubject.isAuthenticated() && 
                        threadSubject.getPrincipal().equals(username)) {
                        successCount.incrementAndGet();
                    }
                    
                    // Logout
                    threadSubject.logout();
                } catch (Exception e) {
                    fail("Authentication failed in virtual thread: " + e.getMessage());
                } finally {
                    // Clean up thread context
                    ThreadContext.remove();
                    latch.countDown();
                }
            });
        }
        
        // Wait for all threads to complete
        latch.await();
        
        // Verify all authentications were successful
        assertEquals(THREAD_COUNT, successCount.get(), 
                "All virtual thread authentications should succeed");
    }

    /**
     * Tests concurrent authentication requests using Java 21's lightweight threading.
     * Verifies that Shiro can handle multiple simultaneous authentication requests
     * when executed in Virtual Threads.
     */
    @Test
    @DisplayName("Test concurrent authentication with Virtual Threads")
    public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
        final int THREAD_COUNT = 100;
        final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        
        // Launch multiple virtual threads to perform concurrent authentication
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int index = i;
            
            // Use Java 21 Virtual Thread for concurrent authentication
            Thread.ofVirtual().name("concurrent-auth-" + i).start(() -> {
                try {
                    // Create a new subject for this thread
                    Subject threadSubject = new Subject.Builder(securityManager).buildSubject();
                    ThreadContext.bind(threadSubject);
                    
                    // Determine which credentials to use based on thread index
                    String username;
                    String password;
                    
                    // Every third thread will use invalid credentials to test failure handling
                    if (index % 3 == 0) {
                        username = "admin";
                        password = "wrongpassword";
                    } else {
                        username = (index % 2 == 0) ? "user" : "guest";
                        password = username + "123";
                    }
                    
                    try {
                        // Attempt login
                        AuthenticationToken token = new UsernamePasswordToken(username, password);
                        threadSubject.login(token);
                        
                        // If we get here, authentication succeeded
                        if (threadSubject.isAuthenticated()) {
                            successCount.incrementAndGet();
                        }
                        
                        // Logout
                        threadSubject.logout();
                    } catch (AuthenticationException e) {
                        // Expected for invalid credentials
                        failureCount.incrementAndGet();
                    }
                } finally {
                    // Clean up thread context
                    ThreadContext.remove();
                    latch.countDown();
                }
            });
        }
        
        // Wait for all threads to complete
        latch.await();
        
        // Verify expected success and failure counts
        assertEquals(THREAD_COUNT / 3, failureCount.get(), 
                "One-third of authentication attempts should fail");
        assertEquals(THREAD_COUNT - failureCount.get(), successCount.get(), 
                "Remaining authentication attempts should succeed");
    }

    /**
     * Tests authentication token validation using Java 21 pattern matching for switch.
     * Demonstrates how pattern matching can improve token validation code.
     */
    @Test
    @DisplayName("Test token validation with Java 21 pattern matching for switch")
    public void testTokenValidationWithPatternMatchingSwitch() {
        // Create different types of authentication tokens for testing
        AuthenticationToken adminToken = new UsernamePasswordToken("admin", "admin123");
        AuthenticationToken userToken = new UsernamePasswordToken("user", "user123");
        AuthenticationToken guestToken = new UsernamePasswordToken("guest", "guest123");
        
        // Validate each token using pattern matching in switch
        for (AuthenticationToken token : List.of(adminToken, userToken, guestToken)) {
            String expectedRole = switch (token) {
                case UsernamePasswordToken upToken when "admin".equals(upToken.getUsername()) -> "administrator";
                case UsernamePasswordToken upToken when "user".equals(upToken.getUsername()) -> "regular user";
                case UsernamePasswordToken upToken when "guest".equals(upToken.getUsername()) -> "guest user";
                default -> "unknown";
            };
            
            // Verify the expected role was determined correctly
            assertNotEquals("unknown", expectedRole, "Token should have a recognized role");
            
            // Authenticate with the token
            Subject subject = new Subject.Builder(securityManager).buildSubject();
            subject.login(token);
            
            // Verify authentication succeeded
            assertTrue(subject.isAuthenticated());
            
            // Clean up
            subject.logout();
        }
    }

    /**
     * A simple test realm implementation for authentication testing.
     */
    private static class TestRealm extends AuthenticatingRealm {
        private final String expectedUsername;
        private final String expectedPassword;
        private final String realmName;
        
        public TestRealm(String username, String password, String name) {
            this.expectedUsername = username;
            this.expectedPassword = password;
            this.realmName = name;
            setCredentialsMatcher(new SimpleCredentialsMatcher());
        }
        
        @Override
        public String getName() {
            return realmName;
        }
        
        @Override
        protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) 
                throws AuthenticationException {
            // Use Java 21 pattern matching to extract username
            if (token instanceof UsernamePasswordToken upToken) {
                String username = upToken.getUsername();
                
                // Only authenticate if username matches this realm's expected username
                if (expectedUsername.equals(username)) {
                    return new SimpleAuthenticationInfo(username, expectedPassword, getName());
                }
            }
            return null;
        }
    }
}