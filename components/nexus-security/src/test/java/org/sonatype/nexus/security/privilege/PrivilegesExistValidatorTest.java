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
package org.sonatype.nexus.security.privilege;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.validation.ConstraintValidatorContext;

import org.sonatype.nexus.security.SecuritySystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@ExtendWith(MockitoExtension.class)
class PrivilegesExistValidatorTest
{
  private PrivilegesExistValidator underTest;

  @Mock
  private SecuritySystem securitySystem;

  @BeforeEach
  void setup() {
    Mockito.when(securitySystem.listPrivileges()).thenReturn(Collections.emptySet());
    underTest = new PrivilegesExistValidator(securitySystem);
  }

  @Test
  void isValid_ignoresJavaElExpression() {
    ConstraintValidatorContext context = Mockito.mock(ConstraintValidatorContext.class);
    Mockito.when(context.buildConstraintViolationWithTemplate(Mockito.any()))
        .thenReturn(Mockito.mock(ConstraintValidatorContext.ConstraintViolationBuilder.class));
    assertThat(underTest.isValid(Collections
            .singleton("dx27e${\"gggggggggggggggggggggggggggggggggggggggggggz\".toString().replace(\"g\", \"q\")}yv5rm"),
        context), is(false));
    //note the missing $
    Mockito.verify(context).buildConstraintViolationWithTemplate(
        "Invalid privilege id: dx27e{\"gggggggggggggggggggggggggggggggggggggggggggz\".toString().replace(\"g\", \"q\")}yv5rm. Only letters, digits, underscores(_), hyphens(-), dots(.), and asterisks(*) are allowed and may not start with underscore or dot.");
  }

  @Test
  void isValid_allows_wildcards() {
    Set<Privilege> validPrivileges = new HashSet<>();
    Privilege privilege = Mockito.mock(Privilege.class);
    Mockito.when(privilege.getId()).thenReturn("nx-repository-admin-maven2-maven-public-*");
    validPrivileges.add(privilege);
    Mockito.when(securitySystem.listPrivileges()).thenReturn(validPrivileges);
    ConstraintValidatorContext context = Mockito.mock(ConstraintValidatorContext.class);
    Mockito.when(context.buildConstraintViolationWithTemplate(Mockito.any()))
        .thenReturn(Mockito.mock(ConstraintValidatorContext.ConstraintViolationBuilder.class));
    assertThat(underTest.isValid(Collections
            .singleton("nx-repository-admin-maven2-maven-public-*"),
        context), is(true));
  }
  
  @Test
  void testConcurrentValidationWithVirtualThreads() throws Exception {
    // Setup valid privileges
    Set<Privilege> validPrivileges = new HashSet<>();
    Privilege privilege = Mockito.mock(Privilege.class);
    Mockito.when(privilege.getId()).thenReturn("nx-repository-admin-maven2-maven-public-*");
    validPrivileges.add(privilege);
    Mockito.when(securitySystem.listPrivileges()).thenReturn(validPrivileges);
    
    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger validCount = new AtomicInteger(0);
    AtomicInteger invalidCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent validation tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            ConstraintValidatorContext context = Mockito.mock(ConstraintValidatorContext.class);
            Mockito.when(context.buildConstraintViolationWithTemplate(Mockito.any()))
                .thenReturn(Mockito.mock(ConstraintValidatorContext.ConstraintViolationBuilder.class));
            
            // Alternate between valid and invalid privilege IDs
            String privilegeId = (index % 2 == 0) 
                ? "nx-repository-admin-maven2-maven-public-*" 
                : "invalid_privilege_id_" + index;
            
            boolean isValid = underTest.isValid(Collections.singleton(privilegeId), context);
            
            if (isValid) {
              validCount.incrementAndGet();
            } else {
              invalidCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(10, TimeUnit.SECONDS);
      
      // Verify results
      assertThat(validCount.get(), is(taskCount / 2));
      assertThat(invalidCount.get(), is(taskCount / 2));
    } finally {
      executor.shutdown();
    }
  }
}