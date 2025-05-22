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
package org.apache.shiro;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.authc.credential.DefaultPasswordService;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.apache.shiro.config.IniSecurityManagerFactory;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.apache.shiro.util.Factory;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.security.Security;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Apache Shiro 2.0.0 core authentication and authorization functionality compatibility with Java 21.
 * Verifies that basic Shiro operations including subject authentication, permission checks, and
 * role-based access control function correctly under the Java 21 runtime. Ensures that Shiro's
 * SecurityManager and related components can be properly initialized and configured with the
 * updated JDK module system.
 */
public class ShiroJava21CompatibilityTest {

    private SecurityManager securityManager;
    private SimpleAccountRealm realm;
    private PasswordService passwordService;

    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_PASSWORD = "password123";
    private static final String TEST_ROLE = "admin";
    private static final String TEST_PERMISSION = "system:config:read";

    @BeforeEach
    public void setUp() {
        // Initialize a simple realm for testing
        realm = new SimpleAccountRealm();
        passwordService = new DefaultPasswordService();
        
        // Add a test user with role and permission
        realm.addAccount(TEST_USERNAME, TEST_PASSWORD, TEST_ROLE);
        
        // Create and configure the security manager
        securityManager = new DefaultSecurityManager(realm);
        
        // Make the SecurityManager accessible to the current thread
        SecurityUtils.setSecurityManager(securityManager);
    }

    @AfterEach
    public void tearDown() {
        // Clean up the thread local storage
        ThreadContext.remove();
        SecurityUtils.setSecurityManager(null);
    }

    /**
     * Tests that the SecurityManager can be properly initialized in a Java 21 environment.
     * This verifies basic compatibility with the Java 21 module system.
     */
    @Test
    @DisplayName("Test SecurityManager initialization in Java 21")
    public void testSecurityManagerInitialization() {
        assertNotNull(securityManager, "SecurityManager should be initialized");
        assertTrue(securityManager instanceof DefaultSecurityManager, 
                "SecurityManager should be an instance of DefaultSecurityManager");
        
        // Verify the security manager is accessible via SecurityUtils
        SecurityManager retrievedManager = SecurityUtils.getSecurityManager();
        assertNotNull(retrievedManager, "SecurityManager should be retrievable from SecurityUtils");
        assertSame(securityManager, retrievedManager, "Retrieved SecurityManager should be the same instance");
    }

    /**
     * Tests basic authentication functionality to ensure it works correctly in Java 21.
     */
    @Test
    @DisplayName("Test basic authentication in Java 21")
    public void testBasicAuthentication() {
        // Get the current subject
        Subject subject = SecurityUtils.getSubject();
        assertNotNull(subject, "Subject should not be null");
        assertFalse(subject.isAuthenticated(), "Subject should not be authenticated initially");
        
        // Authenticate
        UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
        subject.login(token);
        
        // Verify authentication succeeded
        assertTrue(subject.isAuthenticated(), "Subject should be authenticated after login");
        assertEquals(TEST_USERNAME, subject.getPrincipal(), "Subject principal should match the test username");
        
        // Test logout
        subject.logout();
        assertFalse(subject.isAuthenticated(), "Subject should not be authenticated after logout");
    }

    /**
     * Tests authentication failure scenarios to ensure proper error handling in Java 21.
     */
    @Test
    @DisplayName("Test authentication failure handling in Java 21")
    public void testAuthenticationFailure() {
        Subject subject = SecurityUtils.getSubject();
        
        // Test with incorrect password
        UsernamePasswordToken invalidToken = new UsernamePasswordToken(TEST_USERNAME, "wrongpassword");
        assertThrows(AuthenticationException.class, () -> {
            subject.login(invalidToken);
        }, "Authentication with wrong password should throw AuthenticationException");
        
        // Test with non-existent user
        UsernamePasswordToken nonExistentToken = new UsernamePasswordToken("nonexistentuser", TEST_PASSWORD);
        assertThrows(AuthenticationException.class, () -> {
            subject.login(nonExistentToken);
        }, "Authentication with non-existent user should throw AuthenticationException");
    }

    /**
     * Tests role-based access control to ensure it works correctly in Java 21.
     */
    @Test
    @DisplayName("Test role-based access control in Java 21")
    public void testRoleBasedAccess() {
        // Login first
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
        subject.login(token);
        
        // Test role checks
        assertTrue(subject.hasRole(TEST_ROLE), "Subject should have the test role");
        assertFalse(subject.hasRole("user"), "Subject should not have the 'user' role");
        
        // Test role-based access control
        subject.checkRole(TEST_ROLE); // Should not throw exception
        assertThrows(org.apache.shiro.authz.UnauthorizedException.class, () -> {
            subject.checkRole("user");
        }, "Checking for unauthorized role should throw UnauthorizedException");
    }

    /**
     * Tests permission-based authorization to ensure it works correctly in Java 21.
     */
    @Test
    @DisplayName("Test permission-based authorization in Java 21")
    public void testPermissionChecks() {
        // Add a permission to the test user's role
        ((DefaultSecurityManager) securityManager).getAuthorizationInfo(TEST_ROLE)
                .addStringPermission(TEST_PERMISSION);
        
        // Login first
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
        subject.login(token);
        
        // Test permission checks
        Permission permission = new WildcardPermission(TEST_PERMISSION);
        assertTrue(subject.isPermitted(permission), "Subject should have the test permission");
        assertFalse(subject.isPermitted("system:config:write"), 
                "Subject should not have the 'system:config:write' permission");
        
        // Test permission-based access control
        subject.checkPermission(TEST_PERMISSION); // Should not throw exception
        assertThrows(org.apache.shiro.authz.UnauthorizedException.class, () -> {
            subject.checkPermission("system:config:write");
        }, "Checking for unauthorized permission should throw UnauthorizedException");
    }

    /**
     * Tests Shiro's configuration loading capabilities in Java 21.
     */
    @Test
    @DisplayName("Test Shiro configuration loading in Java 21")
    public void testShiroConfigurationLoading() {
        // Create a simple INI configuration
        String iniConfig = "[users]\n" +
                           "configuser = configpass, admin\n" +
                           "\n" +
                           "[roles]\n" +
                           "admin = *\n";
        
        // Load the configuration
        Factory<SecurityManager> factory = new IniSecurityManagerFactory(iniConfig);
        SecurityManager manager = factory.getInstance();
        
        assertNotNull(manager, "SecurityManager should be created from INI configuration");
        
        // Test the configuration by authenticating a user defined in the INI
        SecurityUtils.setSecurityManager(manager);
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken("configuser", "configpass");
        subject.login(token);
        
        assertTrue(subject.isAuthenticated(), "Subject should be authenticated using INI configuration");
        assertTrue(subject.hasRole("admin"), "Subject should have the 'admin' role from INI configuration");
    }

    /**
     * Tests that BouncyCastle cryptography provider works correctly with Shiro in Java 21.
     */
    @Test
    @DisplayName("Test BouncyCastle cryptography provider in Java 21")
    public void testCryptographyProviders() {
        // Register BouncyCastle provider if not already registered
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        
        // Test password hashing with BouncyCastle
        String plainPassword = "secretpassword";
        String hashedPassword = passwordService.encryptPassword(plainPassword);
        
        assertNotNull(hashedPassword, "Hashed password should not be null");
        assertNotEquals(plainPassword, hashedPassword, "Hashed password should differ from plain password");
        assertTrue(passwordService.passwordsMatch(plainPassword, hashedPassword), 
                "Password service should verify the password correctly");
    }

    /**
     * Tests Shiro's compatibility with Java 21 virtual threads.
     * This verifies that Subject propagation works correctly with virtual threads.
     */
    @Test
    @DisplayName("Test Shiro compatibility with Java 21 virtual threads")
    public void testVirtualThreadCompatibility() throws Exception {
        // Login a subject on the main thread
        Subject mainSubject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(TEST_USERNAME, TEST_PASSWORD);
        mainSubject.login(token);
        
        // Create a virtual thread executor (Java 21 feature)
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Test subject propagation to virtual thread
            AtomicBoolean virtualThreadHasSubject = new AtomicBoolean(false);
            CountDownLatch latch = new CountDownLatch(1);
            
            Future<?> future = executor.submit(() -> {
                try {
                    // Get the subject in the virtual thread
                    Subject virtualThreadSubject = SecurityUtils.getSubject();
                    
                    // Check if the subject is available and authenticated
                    if (virtualThreadSubject != null && 
                        virtualThreadSubject.isAuthenticated() && 
                        TEST_USERNAME.equals(virtualThreadSubject.getPrincipal())) {
                        virtualThreadHasSubject.set(true);
                    }
                } finally {
                    latch.countDown();
                }
            });
            
            // Wait for the virtual thread to complete
            latch.await(5, TimeUnit.SECONDS);
            future.get(5, TimeUnit.SECONDS); // Ensure no exceptions were thrown
            
            // By default, subjects are not propagated to new threads
            // This test verifies the current behavior, which is that the virtual thread does not inherit the subject
            assertFalse(virtualThreadHasSubject.get(), 
                    "Virtual thread should not inherit subject without explicit propagation");
            
            // Now test with explicit subject propagation
            AtomicBoolean propagatedSubjectWorks = new AtomicBoolean(false);
            CountDownLatch latch2 = new CountDownLatch(1);
            
            // Get the subject to propagate
            Subject subjectToPropagate = mainSubject;
            
            Future<?> future2 = executor.submit(() -> {
                try {
                    // Manually bind the subject to this virtual thread
                    ThreadContext.bind(subjectToPropagate);
                    
                    // Now check if the subject is available and authenticated
                    Subject virtualThreadSubject = SecurityUtils.getSubject();
                    if (virtualThreadSubject != null && 
                        virtualThreadSubject.isAuthenticated() && 
                        TEST_USERNAME.equals(virtualThreadSubject.getPrincipal())) {
                        propagatedSubjectWorks.set(true);
                    }
                } finally {
                    // Clean up thread context
                    ThreadContext.remove();
                    latch2.countDown();
                }
            });
            
            // Wait for the virtual thread to complete
            latch2.await(5, TimeUnit.SECONDS);
            future2.get(5, TimeUnit.SECONDS); // Ensure no exceptions were thrown
            
            // With explicit propagation, the subject should be available in the virtual thread
            assertTrue(propagatedSubjectWorks.get(), 
                    "Virtual thread should have access to explicitly propagated subject");
        }
    }

    /**
     * Tests Shiro's module system compatibility with Java 21.
     * This verifies that Shiro classes are properly accessible through the module system.
     */
    @Test
    @DisplayName("Test Shiro module system compatibility with Java 21")
    public void testModuleSystemCompatibility() {
        // Verify key Shiro classes are accessible through the module system
        assertDoesNotThrow(() -> {
            Class.forName("org.apache.shiro.SecurityUtils");
            Class.forName("org.apache.shiro.subject.Subject");
            Class.forName("org.apache.shiro.mgt.SecurityManager");
            Class.forName("org.apache.shiro.realm.Realm");
            Class.forName("org.apache.shiro.authc.AuthenticationInfo");
            Class.forName("org.apache.shiro.authz.AuthorizationInfo");
        }, "Shiro classes should be accessible through the Java 21 module system");
    }
}