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
package org.sonatype.nexus.security.authc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link LoginEvent}.
 */
public class LoginEventTest
{
  private static final String TEST_PRINCIPAL = "test-user";
  private static final String TEST_REALM = "test-realm";

  @Test
  public void testConstructor() {
    LoginEvent event = new LoginEvent(TEST_PRINCIPAL, TEST_REALM);
    
    assertThat(event, notNullValue());
    assertThat(event.principal(), is(TEST_PRINCIPAL));
    assertThat(event.realm(), is(TEST_REALM));
  }
  
  @Test
  public void testConstructorValidation() {
    // Test null principal
    IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, 
        () -> new LoginEvent(null, TEST_REALM));
    assertThat(e1.getMessage(), containsString("Principal cannot be null"));
    
    // Test empty principal
    IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, 
        () -> new LoginEvent("", TEST_REALM));
    assertThat(e2.getMessage(), containsString("Principal cannot be null or empty"));
    
    // Test null realm
    IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class, 
        () -> new LoginEvent(TEST_PRINCIPAL, null));
    assertThat(e3.getMessage(), containsString("Realm cannot be null"));
    
    // Test empty realm
    IllegalArgumentException e4 = assertThrows(IllegalArgumentException.class, 
        () -> new LoginEvent(TEST_PRINCIPAL, ""));
    assertThat(e4.getMessage(), containsString("Realm cannot be null or empty"));
  }
  
  @Test
  public void testToString() {
    LoginEvent event = new LoginEvent(TEST_PRINCIPAL, TEST_REALM);
    String str = event.toString();
    
    assertThat(str, containsString("LoginEvent"));
    assertThat(str, containsString("principal=" + TEST_PRINCIPAL));
    assertThat(str, containsString("realm=" + TEST_REALM));
  }
  
  @Test
  public void testRecordPatternMatching() {
    LoginEvent event = new LoginEvent(TEST_PRINCIPAL, TEST_REALM);
    
    // Using Java 21 record pattern matching
    if (event instanceof LoginEvent(String principal, String realm)) {
      assertThat(principal, is(TEST_PRINCIPAL));
      assertThat(realm, is(TEST_REALM));
    } else {
      throw new AssertionError("Record pattern matching failed");
    }
  }
  
  @Test
  public void testSerialization() throws Exception {
    LoginEvent original = new LoginEvent(TEST_PRINCIPAL, TEST_REALM);
    
    // Serialize
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
      oos.writeObject(original);
    }
    
    // Deserialize
    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    LoginEvent deserialized;
    try (ObjectInputStream ois = new ObjectInputStream(bais)) {
      deserialized = (LoginEvent) ois.readObject();
    }
    
    // Verify
    assertThat(deserialized, notNullValue());
    assertThat(deserialized.principal(), equalTo(original.principal()));
    assertThat(deserialized.realm(), equalTo(original.realm()));
  }
}