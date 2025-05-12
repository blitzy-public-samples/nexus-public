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
package com.sonatype.nexus.docker.testsupport.framework;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.singletonList;

/**
 * Configuration object for the Docker Container.
 * 
 * <p>This class is compatible with Java 21 and leverages record patterns for efficient data handling.</p>
 * 
 * @since 3.60
 */
public class DockerContainerConfig
{
  /**
   * Immutable configuration record for Docker container settings.
   * 
   * @param image Docker image name
   * @param dockerfile Path to Dockerfile
   * @param exposedPorts List of exposed ports
   * @param env Environment variables map
   * @param pathBinds Path bindings map
   * @param workingDir Working directory
   */
  private record ConfigData(
      String image,
      Path dockerfile,
      List<Integer> exposedPorts,
      Map<String, String> env,
      Map<String, String> pathBinds,
      String workingDir)
  {
  }
  
  private ConfigData configData;

  private DockerContainerConfig(@Nullable final String image, @Nullable final Path dockerfile) {
    checkArgument(!(image == null && dockerfile == null), "Image name or Dockerfile should be presented");
    checkArgument(!(image != null && dockerfile != null), "Image name and Dockerfile should not be presented both");
    
    this.configData = new ConfigData(
        image,
        dockerfile,
        new ArrayList<>(),
        new HashMap<>(),
        new HashMap<>(),
        null);
  }

  /**
   * Returns the Docker image name.
   */
  public String getImage() {
    return configData.image();
  }

  /**
   * Returns the path to the Dockerfile.
   */
  public Path getDockerfile() {
    return configData.dockerfile();
  }

  /**
   * Returns the list of exposed ports.
   */
  public List<Integer> getExposedPorts() {
    return configData.exposedPorts();
  }

  /**
   * Returns the map of environment variables.
   */
  public Map<String, String> getEnv() {
    return configData.env();
  }

  /**
   * Returns the map of path bindings.
   */
  public Map<String, String> getPathBinds() {
    return configData.pathBinds();
  }

  /**
   * Returns the working directory.
   */
  public String getWorkingDir() {
    return configData.workingDir();
  }

  /**
   * Creates a builder with the specified Docker image.
   */
  public static Builder builder(final String image) {
    return new Builder(image);
  }

  /**
   * Creates a builder with the specified Dockerfile path.
   */
  public static Builder builder(final Path dockerfile) {
    return new Builder(dockerfile);
  }

  /**
   * Builder for {@link DockerContainerConfig}.
   * 
   * <p>Provides a fluent API for constructing container configurations.</p>
   */
  public static final class Builder
  {
    private String image;

    private Path dockerfile;

    private List<Integer> exposedPorts = new ArrayList<>();

    private Map<String, String> env = new HashMap<>();

    private Map<String, String> pathBinds = new HashMap<>();

    private String workingDir;

    private Builder(final String image) {
      this.image = checkNotNull(image);
    }

    private Builder(final Path dockerfile) {
      this.dockerfile = checkNotNull(dockerfile);
    }

    /**
     * Sets a single exposed port.
     */
    public Builder withExposedPort(final String exposedPort) {
      this.exposedPorts = singletonList(Integer.parseInt(exposedPort));
      return this;
    }

    /**
     * Sets multiple exposed ports.
     */
    public Builder withExposedPorts(final List<String> exposedPorts) {
      this.exposedPorts = exposedPorts.stream().map(Integer::parseInt).collect(Collectors.toList());
      return this;
    }

    /**
     * Sets environment variables.
     */
    public Builder withEnv(final Map<String, String> env) {
      this.env = env;
      return this;
    }

    /**
     * Sets path bindings.
     */
    public Builder withPathBinds(final Map<String, String> pathBinds) {
      this.pathBinds = pathBinds;
      return this;
    }

    /**
     * Sets the working directory.
     */
    public Builder withWorkingDir(final String workingDir) {
      this.workingDir = workingDir;
      return this;
    }

    /**
     * Builds the {@link DockerContainerConfig} instance.
     */
    public DockerContainerConfig build() {
      DockerContainerConfig dockerContainerConfig = new DockerContainerConfig(image, dockerfile);
      
      // Create a new ConfigData record with all the builder values
      dockerContainerConfig.configData = new ConfigData(
          dockerContainerConfig.configData.image(),
          dockerContainerConfig.configData.dockerfile(),
          this.exposedPorts,
          this.env,
          this.pathBinds,
          this.workingDir);
      
      return dockerContainerConfig;
    }
  }
}