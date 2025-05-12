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
package com.sonatype.nexus.docker.testsupport.conda;

import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

/**
 * Factory for creation of Conda required objects.
 * 
 * <p>This class is compatible with Java 21 and uses string templates for efficient string construction.</p>
 *
 * @since 3.60
 */
public class CondaClientITConfigFactory
{
  private static final String IMAGE_CONDA = "docker-all.repo.sonatype.com/continuumio/miniconda3";

  private CondaClientITConfigFactory() {
    // Private constructor to prevent instantiation
  }

  /**
   * Creates a Docker container configuration for Conda with the specified image tag.
   *
   * @param imageTag the tag for the Conda Docker image
   * @return a configured {@link DockerContainerConfig} instance
   */
  public static DockerContainerConfig createCondaConfig(final String imageTag) {
    // Using Java 21 string template for image name construction
    String imageName = STR."{IMAGE_CONDA}:{imageTag}";
    return DockerContainerConfig.builder(imageName).build();
  }
}