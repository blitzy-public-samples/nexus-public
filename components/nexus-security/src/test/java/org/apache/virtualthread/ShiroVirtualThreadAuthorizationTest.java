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
package org.apache.virtualthread;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests Apache Shiro's authorization mechanisms using Java 21 virtual threads.
 * Verifies that permission checks and role-based access control work correctly
 * when executed concurrently on virtual threads.
 */
public class ShiroVirtualThreadAuthorizationTest
    extends TestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 100;
  private static final int PERMISSION_CHECK_COUNT = 10;
  
  private DefaultSecurityManager securityManager;
  private TestAuthorizingRealm realm;
  
  @Before
  public void setUp() {
    // Create and configure the security manager with our test realm
    realm = new TestAuthorizingRealm();
    securityManager = new DefaultSecurityManager(realm);
    
    // Enable authorization caching
    realm.setCachingEnabled(true);
    realm.setAuthorizationCachingEnabled(true);
    
    // Set the security manager as the default for static access
    SecurityUtils.setSecurityManager(securityManager);
  }
  
  @After
  public void tearDown() {
    // Clean up the security manager
    if (securityManager != null) {
      securityManager.destroy();
    }
    SecurityUtils.setSecurityManager(null);
  }
  
  /**
   * Tests that basic permission checks work correctly with virtual threads.
   * This verifies that a subject can check permissions when running on a virtual thread.
   */
  @Test
  public void testBasicPermissionCheckOnVirtualThread() throws Exception {
    // Login as admin user
    Subject adminSubject = loginUser("admin", "password");
    
    // Create a virtual thread to perform permission checks
    Thread virtualThread = Thread.ofVirtual()
        .name("permission-check-")
        .start(() -> {
          // Get the current subject (should be the admin)
          Subject currentSubject = SecurityUtils.getSubject();
          
          // Verify the subject is authenticated
          assertTrue("Subject should be authenticated", currentSubject.isAuthenticated());
          
          // Check permissions
          assertTrue("Admin should have admin permission", 
              currentSubject.isPermitted("admin:*"));
          assertTrue("Admin should have system permission", 
              currentSubject.isPermitted("system:config:read"));
        });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Logout
    adminSubject.logout();
  }
  
  /**
   * Tests concurrent permission checks across multiple virtual threads.
   * This verifies that multiple subjects can check permissions concurrently
   * when running on different virtual threads without interference.
   */
  @Test
  public void testConcurrentPermissionChecksOnVirtualThreads() throws Exception {
    // Login as admin user
    Subject adminSubject = loginUser("admin", "password");
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a set to track any failures
      Set<String> failures = ConcurrentHashMap.newKeySet();
      
      // Create a latch to ensure all threads start at roughly the same time
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Submit tasks to check permissions concurrently
      List<Future<?>> futures = IntStream.range(0, VIRTUAL_THREAD_COUNT)
          .mapToObj(i -> executor.submit(() -> {
            try {
              // Wait for the signal to start
              startLatch.await();
              
              // Get the current subject (should be the admin)
              Subject currentSubject = SecurityUtils.getSubject();
              
              // Perform multiple permission checks
              for (int j = 0; j < PERMISSION_CHECK_COUNT; j++) {
                if (!currentSubject.isPermitted("admin:*")) {
                  failures.add("Thread " + i + " failed admin:* permission check");
                }
                
                if (!currentSubject.isPermitted("system:config:read")) {
                  failures.add("Thread " + i + " failed system:config:read permission check");
                }
                
                // Check a collection of permissions
                Collection<Permission> permissions = List.of(
                    new WildcardPermission("repository:read:*"),
                    new WildcardPermission("repository:write:*")
                );
                
                if (!currentSubject.isPermittedAll(permissions)) {
                  failures.add("Thread " + i + " failed repository permissions check");
                }
              }
            } 
            catch (Exception e) {
              failures.add("Thread " + i + " exception: " + e.getMessage());
            }
          }))
          .collect(Collectors.toList());
      
      // Signal all threads to start
      startLatch.countDown();
      
      // Wait for all futures to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      
      // Check for any failures
      if (!failures.isEmpty()) {
        fail("Permission check failures: " + String.join(", ", failures));
      }
    }
    
    // Logout
    adminSubject.logout();
  }
  
  /**
   * Tests that role checks work correctly with virtual threads.
   * This verifies that a subject can check roles when running on a virtual thread.
   */
  @Test
  public void testRoleChecksOnVirtualThread() throws Exception {
    // Login as user with specific roles
    Subject userSubject = loginUser("user1", "password");
    
    // Create a virtual thread to perform role checks
    Thread virtualThread = Thread.ofVirtual()
        .name("role-check-")
        .start(() -> {
          // Get the current subject
          Subject currentSubject = SecurityUtils.getSubject();
          
          // Verify the subject is authenticated
          assertTrue("Subject should be authenticated", currentSubject.isAuthenticated());
          
          // Check roles
          assertTrue("User should have user role", 
              currentSubject.hasRole("user"));
          assertTrue("User should have viewer role", 
              currentSubject.hasRole("viewer"));
          
          // Check multiple roles
          assertTrue("User should have all specified roles", 
              currentSubject.hasAllRoles(List.of("user", "viewer")));
        });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Logout
    userSubject.logout();
  }
  
  /**
   * Tests that authorization caching works correctly with virtual threads.
   * This verifies that authorization information is properly cached and retrieved
   * when accessed from virtual threads.
   */
  @Test
  public void testAuthorizationCachingWithVirtualThreads() throws Exception {
    // Login as admin user
    Subject adminSubject = loginUser("admin", "password");
    
    // First access should populate the cache
    adminSubject.isPermitted("admin:*");
    
    // Track the number of authorization info lookups
    int initialLookupCount = realm.getAuthorizationInfoLookupCount();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to check permissions concurrently
      List<Future<?>> futures = IntStream.range(0, VIRTUAL_THREAD_COUNT)
          .mapToObj(i -> executor.submit(() -> {
            // Get the current subject (should be the admin)
            Subject currentSubject = SecurityUtils.getSubject();
            
            // Check permissions (should use cached authorization info)
            assertTrue(currentSubject.isPermitted("admin:*"));
          }))
          .collect(Collectors.toList());
      
      // Wait for all futures to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
    
    // Verify that the cache was used (lookup count should not have increased significantly)
    int finalLookupCount = realm.getAuthorizationInfoLookupCount();
    assertThat("Authorization cache should have been used", 
        finalLookupCount - initialLookupCount, is(0));
    
    // Logout
    adminSubject.logout();
  }
  
  /**
   * Helper method to login a user and return the authenticated Subject.
   */
  private Subject loginUser(String username, String password) {
    Subject subject = SecurityUtils.getSubject();
    UsernamePasswordToken token = new UsernamePasswordToken(username, password);
    subject.login(token);
    return subject;
  }
  
  /**
   * Custom AuthorizingRealm implementation for testing.
   * Provides authentication and authorization for test users.
   */
  private static class TestAuthorizingRealm extends AuthorizingRealm {
    private int authorizationInfoLookupCount = 0;
    
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) 
        throws AuthenticationException {
      // Simple authentication for testing
      UsernamePasswordToken upToken = (UsernamePasswordToken) token;
      String username = upToken.getUsername();
      
      // For testing, accept any username with password "password"
      if ("password".equals(new String(upToken.getPassword()))) {
        return new SimpleAuthenticationInfo(username, "password", getName());
      }
      
      return null;
    }
    
    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
      // Track the number of authorization info lookups
      authorizationInfoLookupCount++;
      
      String username = (String) getAvailablePrincipal(principals);
      SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
      
      // Assign roles and permissions based on username
      if ("admin".equals(username)) {
        // Admin user has admin role and all permissions
        info.addRole("admin");
        info.addStringPermission("admin:*");
        info.addStringPermission("system:*");
        info.addStringPermission("repository:*:*");
      } 
      else {
        // Regular users have basic roles and permissions
        info.addRole("user");
        info.addRole("viewer");
        info.addStringPermission("repository:read:*");
      }
      
      return info;
    }
    
    /**
     * @return the number of times authorization info has been looked up
     */
    public int getAuthorizationInfoLookupCount() {
      return authorizationInfoLookupCount;
    }
  }
}