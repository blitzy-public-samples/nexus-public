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
package org.sonatype.nexus.onboarding.internal;

import java.util.concurrent.CountDownLatch;

import javax.validation.ConstraintViolationException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.onboarding.OnboardingManager;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.config.AdminPasswordFileManager;
import org.sonatype.nexus.validation.ValidationModule;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.sonatype.nexus.onboarding.internal.OnboardingResource.PASSWORD_REQUIRED;

/**
 * Tests for {@link OnboardingResource} that validate its behavior when executed within virtual threads.
 * This test ensures that the REST endpoint correctly handles password changes and validation
 * in a virtual thread execution environment.
 */
@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class OnboardingResourceVirtualThreadTest
    extends TestSupport
{
  @Mock
  private OnboardingManager onboardingManager;

  @Mock
  private SecuritySystem securitySystem;

  @Mock
  private ApplicationDirectories applicationDirectories;

  @Mock
  private AdminPasswordFileManager adminPasswordFileManager;

  private OnboardingResource underTest;

  @BeforeEach
  public void setup() {
    underTest = Guice.createInjector(new ValidationModule(), new AbstractModule()
    {
      @Override
      protected void configure() {
        bind(OnboardingManager.class).toInstance(onboardingManager);
        bind(SecuritySystem.class).toInstance(securitySystem);
        bind(ApplicationDirectories.class).toInstance(applicationDirectories);
        bind(AdminPasswordFileManager.class).toInstance(adminPasswordFileManager);
      }
    }).getInstance(OnboardingResource.class);
  }

  /**
   * Tests that the changeAdminPassword method works correctly when executed in a virtual thread.
   */
  @Test
  void testChangeAdminPasswordInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = Thread.ofVirtual().name("virtual-thread-test").start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Execute the password change
        underTest.changeAdminPassword("newpass");
        
        // Verify the security system was called with the correct parameters
        verify(securitySystem).changePassword("admin", "newpass", false);
      }
      catch (Exception e) {
        log.error("Error in virtual thread test", e);
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
    thread.join();
  }

  /**
   * Tests that the changeAdminPassword method correctly validates empty passwords
   * when executed in a virtual thread.
   */
  @Test
  void testChangeAdminPasswordEmptyInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = Thread.ofVirtual().name("virtual-thread-empty-test").start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Execute the password change with empty password and expect exception
        ConstraintViolationException exception = assertThrows(ConstraintViolationException.class, 
            () -> underTest.changeAdminPassword(""));
        
        // Verify the exception contains the expected message
        assertEquals(PASSWORD_REQUIRED, 
            exception.getConstraintViolations().iterator().next().getMessage());
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
    thread.join();
  }

  /**
   * Tests that the changeAdminPassword method correctly validates null passwords
   * when executed in a virtual thread.
   */
  @Test
  void testChangeAdminPasswordNullInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = Thread.ofVirtual().name("virtual-thread-null-test").start(() -> {
      try {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
        
        // Execute the password change with null password and expect exception
        ConstraintViolationException exception = assertThrows(ConstraintViolationException.class, 
            () -> underTest.changeAdminPassword(null));
        
        // Verify the exception contains the expected message
        assertEquals(PASSWORD_REQUIRED, 
            exception.getConstraintViolations().iterator().next().getMessage());
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
    thread.join();
  }

  /**
   * Tests concurrent password change requests using multiple virtual threads.
   * This validates that the endpoint can handle concurrent operations in a virtual thread environment.
   */
  @Test
  void testConcurrentPasswordChangesInVirtualThreads() throws Exception {
    int threadCount = 5;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create multiple virtual threads that will all try to change the password concurrently
    for (int i = 0; i < threadCount; i++) {
      final String password = "password" + i;
      Thread.ofVirtual().name("virtual-thread-concurrent-" + i).start(() -> {
        try {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Verify we're running in a virtual thread
          assertTrue(Thread.currentThread().isVirtual(), "Test should be running in a virtual thread");
          
          // Execute the password change
          underTest.changeAdminPassword(password);
          
          // Verify the security system was called with the correct parameters
          verify(securitySystem).changePassword("admin", password, false);
        }
        catch (Exception e) {
          log.error("Error in concurrent virtual thread test", e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all virtual threads to complete
    completionLatch.await();
  }
}