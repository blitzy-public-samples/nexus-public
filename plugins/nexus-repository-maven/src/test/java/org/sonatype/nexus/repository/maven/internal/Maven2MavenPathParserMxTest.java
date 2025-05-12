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

import java.util.List;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.MavenPath.SignatureType;
import org.sonatype.nexus.repository.maven.MavenPathParser;

import com.google.common.collect.Lists;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * UT for {@link Maven2MavenPathParser}, that varies all the known forms of GAV elements.
 *
 * @since 3.0
 */
public class Maven2MavenPathParserMxTest
    extends TestSupport
{
  public static String[] GROUP_IDS = {"single", "multiple.group.elements"};

  public static String[] ARTIFACT_IDS = {"simple", "artifact-with-dashes"};

  public static String[][] VERSIONS = { // [baseVersion, version pairs]
                                        {"1.0", "1.0"},
                                        {"1.0-alpha-1", "1.0-alpha-1"},
                                        {"1.0-SNAPSHOT", "1.0-SNAPSHOT"},
                                        {"1.0-SNAPSHOT", "1.0-20150317.161100-10"},
                                        {"1.0.SNAPSHOT", "1.0.20150317.161100-10"},
                                        {"1.0SNAPSHOT", "1.020150317.161100-10"}
  };

  public static String[] CLASSIFIERS = {null, "single", "with-dash", "with.dot"};

  public static String[] EXTENSIONS = {
      "jar", "jar.sha1", "jar.asc", "jar.asc.md5", "tar.gz",
      "tar.anyext.sha1", "tar.anyext.md5", "tar.anyext.asc", "tar.anyext.asc.md5",
      "cpio.anyext.sha1", "cpio.anyext.md5", "cpio.anyext.asc", "cpio.anyext.asc.md5",
      "nk.os.sha1", "nk.os.md5", "nk.os.asc", "nk.os.asc.md5"
  };

  private final MavenPathParser subject = new Maven2MavenPathParser();

  /**
   * Generates test parameters for all combinations of GAV elements.
   * 
   * @return Stream of Arguments containing test parameters
   */
  static Stream<Arguments> parametersProvider() {
    final List<Arguments> result = Lists.newArrayList();
    for (String g : GROUP_IDS) {
      for (String a : ARTIFACT_IDS) {
        for (String[] v : VERSIONS) {
          for (String c : CLASSIFIERS) {
            for (String e : EXTENSIONS) {
              result.add(Arguments.of(g, a, v[0], v[1], c, e));
            }
          }
        }
      }
    }
    return result.stream();
  }

  /**
   * Constructs a Maven path string based on the provided parameters.
   * 
   * @param groupId The group ID
   * @param artifactId The artifact ID
   * @param baseVersion The base version
   * @param version The version
   * @param classifier The classifier (may be null)
   * @param extension The file extension
   * @return The constructed Maven path
   */
  private String path(String groupId, String artifactId, String baseVersion, String version, String classifier, String extension) {
    if (classifier != null) {
      return "/" + groupId.replace('.', '/') + "/" + artifactId + "/" + baseVersion + "/" + artifactId + "-" +
          version + "-" + classifier + "." + extension;
    }
    else {
      return "/" + groupId.replace('.', '/') + "/" + artifactId + "/" + baseVersion + "/" + artifactId + "-" +
          version + "." + extension;
    }
  }

  /**
   * Tests the Maven path parser with various combinations of GAV elements.
   * Updated to use JUnit Jupiter for Java 21 compatibility.
   * 
   * @param groupId The group ID to test
   * @param artifactId The artifact ID to test
   * @param baseVersion The base version to test
   * @param version The version to test
   * @param classifier The classifier to test (may be null)
   * @param extension The file extension to test
   */
  @ParameterizedTest(name = "{index}: {0}:{1}:{2}:{3}:{4}:{5}")
  @MethodSource("parametersProvider")
  public void affirmativeMxTest(String groupId, String artifactId, String baseVersion, String version, String classifier, String extension) {
    final String path = path(groupId, artifactId, baseVersion, version, classifier, extension);

    final boolean snapshot = baseVersion.endsWith("SNAPSHOT");
    final HashType hashType = path.endsWith(".sha1") ? HashType.SHA1 : (path.endsWith(".md5") ? HashType.MD5 : null);
    final SignatureType signatureType = path.contains(".asc") ? SignatureType.GPG : null;

    final MavenPath mavenPath = subject.parsePath(path);
    assertThat(mavenPath, notNullValue());
    assertThat(mavenPath.getPath(), equalTo(path.substring(1)));
    assertThat(mavenPath.getHashType(), equalTo(hashType));
    assertThat(mavenPath.getCoordinates(), notNullValue());
    assertThat(mavenPath.getCoordinates().isSnapshot(), equalTo(snapshot));
    assertThat(mavenPath.getCoordinates().getGroupId(), equalTo(groupId));
    assertThat(mavenPath.getCoordinates().getArtifactId(), equalTo(artifactId));
    assertThat(mavenPath.getCoordinates().getVersion(), equalTo(version));
    assertThat(mavenPath.getCoordinates().getBaseVersion(), equalTo(baseVersion));
    assertThat(mavenPath.getCoordinates().getClassifier(), equalTo(classifier));
    assertThat(mavenPath.getCoordinates().getExtension(), equalTo(extension));
    assertThat(mavenPath.getCoordinates().getSignatureType(), equalTo(signatureType));
  }
}
