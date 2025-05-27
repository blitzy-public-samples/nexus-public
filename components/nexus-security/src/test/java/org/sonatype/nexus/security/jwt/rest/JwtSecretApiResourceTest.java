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
package org.sonatype.nexus.security.jwt.rest;

import javax.ws.rs.core.Response;

import org.sonatype.nexus.security.jwt.SecretStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static javax.ws.rs.core.Response.Status.OK;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class JwtSecretApiResourceTest
{
  @Mock
  private SecretStore secretStore;

  private JwtSecretApiResourceV1 underTest;

  @BeforeEach
  public void setup() {
    underTest = new JwtSecretApiResourceV1(secretStore);
  }

  @Test
  public void resetSecret() {
    underTest.resetSecret();

    verify(secretStore).setSecret(any(String.class));
  }
  
  @Test
  public void resetSecret_returnsOkResponse() {
    Response response = underTest.resetSecret();
    
    assertThat(response.getStatus(), is(OK.getStatusCode()));
  }
  
  @Test
  public void resetSecret_doesNotUseGenerateNewSecret() {
    underTest.resetSecret();
    
    verify(secretStore, never()).generateNewSecret();
  }
  
  @Test
  public void resetSecret_responseHasNoEntity() {
    Response response = underTest.resetSecret();
    
    assertThat(response.getEntity(), is(nullValue()));
  }
}
