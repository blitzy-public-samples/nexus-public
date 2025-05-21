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
package org.sonatype.nexus.security.role;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.internal.AuthorizingRealmImpl;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;
import org.sonatype.nexus.security.realm.MockRealm;
import org.sonatype.nexus.security.realm.RealmManager;
import org.sonatype.nexus.security.user.MockUserManager;
import org.sonatype.nexus.security.user.UserManager;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ExternalRoleMappedTest
    extends AbstractSecurityTest
{
  @Override
  protected void customizeModules(List<Module> modules) {
    super.customizeModules(modules);
    modules.add(new AbstractModule()
    {
      @Override
      protected void configure() {
        bind(UserManager.class)
            .annotatedWith(Names.named("Mock"))
            .to(MockUserManager.class)
            .in(Singleton.class);

        bind(Realm.class)
            .annotatedWith(Names.named("Mock"))
            .to(MockRealm.class)
            .in(Singleton.class);
      }
    });
  }

  @Test
  void testUserHasPermissionFromExternalRole() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);

    Map<String, String> properties = new HashMap<String, String>();
    properties.put(WildcardPrivilegeDescriptor.P_PATTERN, "permissionOne:read");

    securitySystem.getAuthorizationManager("default").addPrivilege(new Privilege(
        "randomId",
        "permissionOne",
        "permissionOne",
        WildcardPrivilegeDescriptor.TYPE,
        properties,
        false));

    securitySystem.getAuthorizationManager("default").addRole(new Role("mockrole1", "mockrole1", "mockrole1",
        "default", false, null,
        Collections.singleton("randomId")));

    // add MockRealm to config
    RealmManager realmManager = lookup(RealmManager.class);
    realmManager.setConfiguredRealmIds(ImmutableList.of("Mock", AuthorizingRealmImpl.NAME));

    // jcohen has the role mockrole1, there is also test role with the same ID, which means jcohen automaticly has
    // this test role

    PrincipalCollection jcohen = new SimplePrincipalCollection("jcohen", MockRealm.NAME);

    try {
      securitySystem.checkPermission(jcohen, "permissionOne:invalid");
      Assertions.fail("Expected AuthorizationException");
    }
    catch (AuthorizationException e) {
      // expected
    }

    securitySystem.checkPermission(jcohen, "permissionOne:read"); // throws on error, so this is all we need to do
  }

  @Test
  void testConcurrentUserPermissionChecksWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);

    Map<String, String> properties = new HashMap<String, String>();
    properties.put(WildcardPrivilegeDescriptor.P_PATTERN, "permissionOne:read");

    securitySystem.getAuthorizationManager("default").addPrivilege(new Privilege(
        "randomId",
        "permissionOne",
        "permissionOne",
        WildcardPrivilegeDescriptor.TYPE,
        properties,
        false));

    securitySystem.getAuthorizationManager("default").addRole(new Role("mockrole1", "mockrole1", "mockrole1",
        "default", false, null,
        Collections.singleton("randomId")));

    // add MockRealm to config
    RealmManager realmManager = lookup(RealmManager.class);
    realmManager.setConfiguredRealmIds(ImmutableList.of("Mock", AuthorizingRealmImpl.NAME));

    // Create multiple principal collections for testing
    PrincipalCollection jcohen = new SimplePrincipalCollection("jcohen", MockRealm.NAME);
    PrincipalCollection anotherUser = new SimplePrincipalCollection("anotherUser", MockRealm.NAME);

    // Number of concurrent checks to perform
    int concurrentChecks = 100;
    CountDownLatch latch = new CountDownLatch(concurrentChecks * 2); // For both users

    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit permission checks for jcohen
      for (int i = 0; i < concurrentChecks; i++) {
        executor.submit(() -> {
          try {
            securitySystem.checkPermission(jcohen, "permissionOne:read");
            latch.countDown();
          }
          catch (Exception e) {
            Assertions.fail("Unexpected exception: " + e.getMessage());
          }
        });
      }

      // Submit permission checks for anotherUser (should fail)
      for (int i = 0; i < concurrentChecks; i++) {
        executor.submit(() -> {
          try {
            securitySystem.checkPermission(anotherUser, "permissionOne:read");
            Assertions.fail("Expected AuthorizationException for anotherUser");
          }
          catch (AuthorizationException e) {
            // expected
            latch.countDown();
          }
          catch (Exception e) {
            Assertions.fail("Unexpected exception: " + e.getMessage());
          }
        });
      }
    }

    // Wait for all tasks to complete
    Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for permission checks");
  }

  @Test
  void testVirtualThreadRoleMapping() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);

    // Create a privilege and role
    Map<String, String> properties = new HashMap<String, String>();
    properties.put(WildcardPrivilegeDescriptor.P_PATTERN, "virtualThread:access");

    securitySystem.getAuthorizationManager("default").addPrivilege(new Privilege(
        "virtualThreadId",
        "virtualThreadAccess",
        "Virtual Thread Access",
        WildcardPrivilegeDescriptor.TYPE,
        properties,
        false));

    securitySystem.getAuthorizationManager("default").addRole(new Role(
        "virtualThreadRole", 
        "virtualThreadRole", 
        "Virtual Thread Role",
        "default", 
        false, 
        null,
        Collections.singleton("virtualThreadId")));

    // Add MockRealm to config
    RealmManager realmManager = lookup(RealmManager.class);
    realmManager.setConfiguredRealmIds(ImmutableList.of("Mock", AuthorizingRealmImpl.NAME));

    // Create a thread that will run using a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("virtual-test-thread").start(() -> {
      try {
        // Create a principal with the role
        PrincipalCollection principal = new SimplePrincipalCollection("virtualUser", MockRealm.NAME);
        
        // Check permission
        securitySystem.checkPermission(principal, "virtualThread:access");
        
        // If we get here, no exception was thrown, which means the permission check passed
        // This is expected to fail since virtualUser doesn't have the role
        Assertions.fail("Expected AuthorizationException for virtualUser");
      }
      catch (AuthorizationException e) {
        // This is expected
      }
      catch (Exception e) {
        Assertions.fail("Unexpected exception: " + e.getMessage());
      }
    });

    // Wait for the virtual thread to complete
    virtualThread.join();
  }
}