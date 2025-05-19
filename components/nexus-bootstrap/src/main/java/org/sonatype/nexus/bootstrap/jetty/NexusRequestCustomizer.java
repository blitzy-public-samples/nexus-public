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
package org.sonatype.nexus.bootstrap.jetty;

import java.util.Optional;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConfiguration.Customizer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Customizes Jetty requests for Nexus-specific handling, particularly for Docker API requests.
 */
public class NexusRequestCustomizer
    implements Customizer
{
  private static final Logger log = LoggerFactory.getLogger(NexusRequestCustomizer.class);

  private static final String REPOSITORY = "repository";

  private static final String DOCKER_V1_REQUEST_PREFIX = "/v1";

  private static final String DOCKER_V2_REQUEST_PREFIX = "/v2";

  private static final String DOCKER_TOKEN_REQUEST_SUFFIX = "/v2/token";

  private final int jettyPort;

  private final int jettySslPort;

  private final String repositoryRequestPathPrefix;

  private final java.util.regex.Pattern dockerBehindReverseProxyTokenRequestPattern;

  /**
   * Constructor for NexusRequestCustomizer.
   *
   * @param contextPath The context path for Nexus
   * @param jettyPort The Jetty HTTP port
   * @param jettySslPort The Jetty HTTPS port
   */
  public NexusRequestCustomizer(final String contextPath, final int jettyPort, final int jettySslPort) {
    this.jettyPort = jettyPort;
    this.jettySslPort = jettySslPort;
    String nexusContextPath = checkNotNull(contextPath) + (contextPath.endsWith("/") ? "" : "/");
    this.repositoryRequestPathPrefix = nexusContextPath + REPOSITORY;
    this.dockerBehindReverseProxyTokenRequestPattern = initDockerBehindReverseProxyTokenRequestPattern();
  }

  @Override
  public void customize(final Connector connector, final HttpConfiguration channelConfig, final Request request) {
    HttpURI uri = request.getHttpURI();
    String path = uri.getPath();

    if (path == null) {
      log.debug(STR."Invalid URL for request: \{request}");
      return;
    }

    switch (path) {
      case String p when p.startsWith(DOCKER_V1_REQUEST_PREFIX) || p.startsWith(DOCKER_V2_REQUEST_PREFIX) -> 
          customizeDockerSubdomainRequest(connector, request, path, uri);
      case String p when p.endsWith(DOCKER_TOKEN_REQUEST_SUFFIX) -> 
          customizeDockerBehindReverseProxyTokenRequest(request, uri, path);
      default -> {}
    }
  }

  /**
   * Customizes Docker subdomain requests by rewriting the URI path.
   *
   * @param connector The Jetty connector
   * @param request The HTTP request
   * @param path The request path
   * @param uri The HTTP URI
   */
  private void customizeDockerSubdomainRequest(
      final Connector connector,
      final Request request,
      final String path,
      final HttpURI uri)
  {
    String version = path.substring(0, 3);
    log.debug(STR."Found \{version} for \{uri}");

    if (isJettyPort(connector)) {
      String repositoryName = DockerSubdomainRepositoryMapping.get(request.getHeader("Host"));
      if (repositoryName != null) {
        String dockerLocation = extractDockerLocation(request);
        log.debug(STR."For \{repositoryName} dockerLocation \{dockerLocation}");

        request.setAttribute("dockerLocation", dockerLocation);
        String newPath = path.replaceFirst(version, repositoryRequestPathPrefix + '/' + repositoryName + version);
        setNewRequestURI(request, uri, path, newPath);
      }
    }
  }

  /**
   * Extracts the Docker location from the request, preferring proxy forwarded headers.
   *
   * @param request The HTTP request
   * @return The Docker location
   */
  private static String extractDockerLocation(final Request request) {
    HttpURI uri = request.getHttpURI();

    // Prefer proxy forwarded headers, fall back to what we know about the request
    String scheme = Optional.ofNullable(request.getHeader("X-Forwarded-Proto"))
        .orElseGet(uri::getScheme);
    String host = Optional.ofNullable(request.getHeader("X-Forwarded-Host"))
        .orElseGet(uri::getHost);
    int port = Optional.ofNullable(request.getHeader("X-Forwarded-Port"))
        .map(Integer::valueOf)
        .orElseGet(uri::getPort);

    if (port < 1 || port == 80 && "http".equals(scheme) || port == 443 && "https".equals(scheme)) {
      // Omit canonical ports or missing port
      return STR."\{scheme}://\{host}\{uri.getPath()}";
    }

    return STR."\{scheme}://\{host}:\{port}\{uri.getPath()}";
  }

  /**
   * Checks if the connector is using one of the configured Jetty ports.
   *
   * @param connector The Jetty connector
   * @return true if the connector is using a configured Jetty port
   */
  private boolean isJettyPort(final Connector connector) {
    if (connector instanceof ServerConnector serverConnector) {
      int localPort = serverConnector.getLocalPort();
      return localPort == jettyPort || localPort == jettySslPort;
    }
    return false;
  }

  /**
   * Initializes the pattern for Docker behind reverse proxy token requests.
   *
   * @return The compiled pattern
   */
  private java.util.regex.Pattern initDockerBehindReverseProxyTokenRequestPattern() {
    // e.g. /nexus-context/repository/docker-repo/nexus-context/repository/docker-repo/v2/token
    return java.util.regex.Pattern.compile(
        STR."^\{repositoryRequestPathPrefix}/([^/]+)\{repositoryRequestPathPrefix}/([^/]+)/v2/token$");
  }

  /**
   * Customizes Docker behind reverse proxy token requests by rewriting the URI path.
   *
   * @param request The HTTP request
   * @param uri The HTTP URI
   * @param path The request path
   */
  private void customizeDockerBehindReverseProxyTokenRequest(
      final Request request,
      final HttpURI uri,
      final String path)
  {
    java.util.regex.Matcher matcher = dockerBehindReverseProxyTokenRequestPattern.matcher(path);
    if (matcher.matches() && matcher.group(1).equals(matcher.group(2))) {
      // e.g. /nexus-context/repository/docker-repo/nexus-context/repository/docker-repo/v2/token
      // -> /nexus-context/repository/docker-repo/v2/token
      String newPath = path.substring(path.lastIndexOf(repositoryRequestPathPrefix));
      setNewRequestURI(request, uri, path, newPath);
    }
  }

  /**
   * Sets a new request URI by replacing the path in the original URI.
   *
   * @param request The HTTP request
   * @param uri The HTTP URI
   * @param path The original path
   * @param newPath The new path
   */
  private void setNewRequestURI(final Request request, final HttpURI uri, final String path, final String newPath) {
    request.setHttpURI(HttpURI.build(uri).path(newPath).asImmutable());
    request.setMetaData(request.getMetaData());
  }
}