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
package org.sonatype.nexus.repository.maven.internal;

import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;
import static org.sonatype.nexus.repository.http.HttpMethods.PUT;
import static org.sonatype.nexus.repository.http.HttpStatus.BAD_REQUEST;
import static org.sonatype.nexus.repository.http.HttpStatus.NOT_FOUND;
import static org.sonatype.nexus.repository.http.HttpStatus.OK;
import static org.sonatype.nexus.repository.maven.VersionPolicy.MIXED;
import static org.sonatype.nexus.repository.maven.VersionPolicy.RELEASE;
import static org.sonatype.nexus.repository.maven.VersionPolicy.SNAPSHOT;

/**
 * Tests {@link VersionPolicyHandler}
 *
 * @since 3.0
 */
@ExtendWith(MockitoExtension.class)
class VersionPolicyHandlerTest
    extends TestSupport
{
  @Mock
  private Context context;

  @Mock
  private Repository repository;

  @Mock
  private MavenFacet mavenFacet;

  @Mock
  private Response proceeded;

  @Mock
  private Request request;

  private VersionPolicyValidator versionPolicyValidator = new VersionPolicyValidator();

  private MavenPathParser mavenPathParser = new Maven2MavenPathParser();

  private VersionPolicyHandler underTest;

  @BeforeEach
  void setup() {
    underTest = new VersionPolicyHandler(versionPolicyValidator);
  }

  /**
   * Provides test scenarios for parameterized tests.
   */
  static Stream<Arguments> testScenarios() {
    return Stream.of(
        // PUT tests
        Arguments.of(SNAPSHOT, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, PUT, OK , "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(SNAPSHOT, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", false),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(SNAPSHOT, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", false),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, PUT, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),
        Arguments.of(RELEASE, PUT, BAD_REQUEST, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", false),
        Arguments.of(MIXED, PUT, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),

        // GET should return NOT_FOUND
        Arguments.of(SNAPSHOT, GET, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(SNAPSHOT, GET, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", false),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(SNAPSHOT, GET, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", false),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, GET, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),
        Arguments.of(RELEASE, GET, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", false),
        Arguments.of(MIXED, GET, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),

        // HEAD should return NOT_FOUND
        Arguments.of(SNAPSHOT, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", false),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/maven-metadata.xml", true),
        Arguments.of(SNAPSHOT, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", false),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.sha1", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.sha1", true),
        Arguments.of(SNAPSHOT, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", false),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0/foo-1.0.0.jar.md5", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/foo-1.0.0-20161204.003314-8.jar.md5", true),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.sha1", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.md5", true),
        Arguments.of(SNAPSHOT, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true),
        Arguments.of(RELEASE, HEAD, NOT_FOUND, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", false),
        Arguments.of(MIXED, HEAD, OK, "org/sonatype/foo/1.0.0-SNAPSHOT/maven-metadata.xml.sha1", true)
    );
  }

  /**
   * Tests various scenarios for version policy handling.
   *
   * @param policy the version policy to test
   * @param httpMethod the HTTP method to test
   * @param status the expected HTTP status
   * @param path the path to test
   * @param shouldProceed whether the handler should proceed
   */
  @ParameterizedTest
  @MethodSource("testScenarios")
  void testScenario(final VersionPolicy policy, 
                    final String httpMethod, 
                    final int status, 
                    final String path, 
                    final boolean shouldProceed) throws Exception 
  {
    when(context.getRequest()).thenReturn(request);
    when(request.getAction()).thenReturn(httpMethod);
    when(context.getRepository()).thenReturn(repository);
    when(repository.facet(MavenFacet.class)).thenReturn(mavenFacet);
    when(mavenFacet.getVersionPolicy()).thenReturn(policy);
    AttributesMap attributes = new AttributesMap();
    attributes.set(MavenPath.class, mavenPathParser.parsePath(path));
    when(context.getAttributes()).thenReturn(attributes);
    if (shouldProceed) {
      when(context.proceed()).thenReturn(proceeded);
    }

    Response response = underTest.handle(context);
    if (shouldProceed) {
      assertThat(response, is(proceeded));
    }
    else {
      assertThat(response, not(proceeded));
      assertThat(response.getStatus().getCode(), is(status));
    }
  }
}