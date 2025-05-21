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
package org.sonatype.nexus.security.realm;

import java.util.concurrent.ExecutionException;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.user.User;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class OrderingRealmsTest
    extends AbstractSecurityTest
{
  @Test
  void testOrderedGetUser() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);
    RealmManager realmManager = lookup(RealmManager.class);
    realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmA", "MockRealmB"));

    User jcoder = securitySystem.getUser("jcoder");
    Assertions.assertNotNull(jcoder);

    // make sure jcoder is from MockUserManagerA
    Assertions.assertEquals("MockUserManagerA", jcoder.getSource());

    // now change the order
    realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmB", "MockRealmA"));

    jcoder = securitySystem.getUser("jcoder");
    Assertions.assertNotNull(jcoder);

    // make sure jcoder is from MockUserManagerA
    Assertions.assertEquals("MockUserManagerB", jcoder.getSource());
  }
  
  @Test
  void testOrderedGetUserWithVirtualThread() throws Exception {
    // Run the same test but in a virtual thread to verify realm ordering works correctly
    // with Apache Shiro 2.0.0 under virtual thread execution
    Thread.startVirtualThread(() -> {
      try {
        SecuritySystem securitySystem = this.lookup(SecuritySystem.class);
        RealmManager realmManager = lookup(RealmManager.class);
        
        // Verify we're running in a virtual thread
        Assertions.assertTrue(Thread.currentThread().isVirtual(), 
            "This test should run in a virtual thread");
        
        // Test first ordering: MockRealmA, MockRealmB
        realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmA", "MockRealmB"));

        User jcoder = securitySystem.getUser("jcoder");
        Assertions.assertNotNull(jcoder);

        // make sure jcoder is from MockUserManagerA
        Assertions.assertEquals("MockUserManagerA", jcoder.getSource());

        // now change the order
        realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmB", "MockRealmA"));

        jcoder = securitySystem.getUser("jcoder");
        Assertions.assertNotNull(jcoder);

        // make sure jcoder is from MockUserManagerB
        Assertions.assertEquals("MockUserManagerB", jcoder.getSource());
      }
      catch (Exception e) {
        throw new RuntimeException("Error in virtual thread test", e);
      }
    }).join(); // Wait for the virtual thread to complete
  }
}