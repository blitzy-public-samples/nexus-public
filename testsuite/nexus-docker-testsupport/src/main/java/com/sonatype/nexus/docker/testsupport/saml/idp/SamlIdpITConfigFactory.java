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
package com.sonatype.nexus.docker.testsupport.saml.idp;

import java.util.Map;

import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

/**
 * Factory for creating SAML Identity Provider configurations for integration tests.
 * 
 * <p>This class is compatible with Java 21 and leverages record patterns for efficient data handling.</p>
 *
 * @since 3.60
 */
public class SamlIdpITConfigFactory
{
  /**
   * Record representing Keycloak configuration parameters.
   * 
   * @param image Docker image name
   * @param tag Docker image tag
   * @param userName Keycloak admin username
   * @param password Keycloak admin password
   * @param portMappingPort Port to expose from the container
   */
  public record KeycloakConfig(String image, String tag, String userName, String password, String portMappingPort) {}
  
  private SamlIdpITConfigFactory() {
    // Prevent instantiation
  }

  /**
   * Creates a Docker container configuration for Keycloak.
   *
   * @param image Docker image name
   * @param tag Docker image tag
   * @param userName Keycloak admin username
   * @param password Keycloak admin password
   * @param portMappingPort Port to expose from the container
   * @return A configured {@link DockerContainerConfig} instance
   */
  public static DockerContainerConfig createKeycloakConfig(
      final String image,
      final String tag,
      final String userName,
      final String password,
      final String portMappingPort)
  {
    // Create a record instance with the parameters
    KeycloakConfig config = new KeycloakConfig(image, tag, userName, password, portMappingPort);
    
    // Use pattern matching with the record for cleaner code
    return createKeycloakConfigFromRecord(config);
  }
  
  /**
   * Creates a Docker container configuration for Keycloak using a configuration record.
   *
   * @param config The Keycloak configuration record
   * @return A configured {@link DockerContainerConfig} instance
   */
  public static DockerContainerConfig createKeycloakConfigFromRecord(final KeycloakConfig config) {
    // Use pattern matching to destructure the record
    var KeycloakConfig(image, tag, userName, password, portMappingPort) = config;
    
    return DockerContainerConfig.builder(image + ":" + tag)
        .withEnv(Map.of("KEYCLOAK_USER", userName, "KEYCLOAK_PASSWORD", password))
        .withExposedPort(portMappingPort)
        .build();
  }
}