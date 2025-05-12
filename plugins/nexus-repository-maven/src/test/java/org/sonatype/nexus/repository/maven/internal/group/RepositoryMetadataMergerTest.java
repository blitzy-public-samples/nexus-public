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
package org.sonatype.nexus.repository.maven.internal.group;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.internal.group.RepositoryMetadataMerger.Envelope;
import org.sonatype.nexus.repository.view.Content;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Plugin;
import org.apache.maven.artifact.repository.metadata.Snapshot;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Unit tests for {@link RepositoryMetadataMerger}
 *
 * @since 3.0
 */
@ExtendWith(MockitoExtension.class)
public class RepositoryMetadataMergerTest
    extends TestSupport
{
  @Mock
  OutputStream outputStream;

  @Mock
  MavenPath mavenPath;

  @Mock
  Repository repository;

  @Mock
  Content content;

  private final RepositoryMetadataMerger merger = new RepositoryMetadataMerger();

  /**
   * Creates a Plugin object with the given name.
   *
   * @param name the name to use for the plugin
   * @return a configured Plugin object
   */
  private Plugin plugin(String name) {
    final Plugin p = new Plugin();
    p.setPrefix(name);
    p.setArtifactId(name + "-maven-plugin");
    p.setName("The " + name + " plugin");
    return p;
  }

  /**
   * Creates a group-level Metadata object with the specified plugins.
   *
   * @param pluginNames names of plugins to include in the metadata
   * @return a configured group-level Metadata object
   */
  private Metadata g(final String... pluginNames)
  {
    final Metadata m = new Metadata();
    for (String pluginName : pluginNames) {
      m.addPlugin(plugin(pluginName));
    }
    return m;
  }

  /**
   * Creates an artifact-level Metadata object with the specified attributes.
   *
   * @param groupId the group ID
   * @param artifactId the artifact ID
   * @param lastUpdated the last updated timestamp
   * @param latest the latest version
   * @param release the release version
   * @param versions the list of available versions
   * @return a configured artifact-level Metadata object
   */
  private Metadata a(final String groupId,
                     final String artifactId,
                     final String lastUpdated,
                     final String latest,
                     final String release,
                     final String... versions)
  {
    final Metadata m = new Metadata();
    m.setGroupId(groupId);
    m.setArtifactId(artifactId);
    m.setVersioning(new Versioning());
    final Versioning mv = m.getVersioning();
    if (!Strings.isNullOrEmpty(lastUpdated)) {
      mv.setLastUpdated(lastUpdated);
    }
    if (!Strings.isNullOrEmpty(latest)) {
      mv.setLatest(latest);
    }
    if (!Strings.isNullOrEmpty(release)) {
      mv.setRelease(release);
    }
    mv.getVersions().addAll(Arrays.asList(versions));
    return m;
  }

  /**
   * Creates a version-level Metadata object with the specified attributes.
   *
   * @param groupId the group ID
   * @param artifactId the artifact ID
   * @param versionPrefix the version prefix
   * @param timestamp the timestamp
   * @param buildNumber the build number
   * @return a configured version-level Metadata object
   */
  private Metadata v(final String groupId,
                     final String artifactId,
                     final String versionPrefix,
                     final String timestamp,
                     final int buildNumber)
  {
    final Metadata m = new Metadata();
    m.setGroupId(groupId);
    m.setArtifactId(artifactId);
    m.setVersion(versionPrefix + "-SNAPSHOT");
    m.setVersioning(new Versioning());
    final Versioning mv = m.getVersioning();
    mv.setLastUpdated(timestamp.replace(".", ""));
    final Snapshot snapshot = new Snapshot();
    snapshot.setTimestamp(timestamp);
    snapshot.setBuildNumber(buildNumber);
    mv.setSnapshot(snapshot);
    final SnapshotVersion pom = new SnapshotVersion();
    pom.setExtension("pom");
    pom.setVersion(versionPrefix + "-" + timestamp + "-" + buildNumber);
    pom.setUpdated(timestamp);
    mv.getSnapshotVersions().add(pom);

    final SnapshotVersion jar = new SnapshotVersion();
    jar.setExtension("jar");
    jar.setVersion(versionPrefix + "-" + timestamp + "-" + buildNumber);
    jar.setUpdated(timestamp);
    mv.getSnapshotVersions().add(jar);

    final SnapshotVersion sources = new SnapshotVersion();
    sources.setExtension("jar");
    sources.setClassifier("sources");
    sources.setVersion(versionPrefix + "-" + timestamp + "-" + buildNumber);
    sources.setUpdated(timestamp);
    mv.getSnapshotVersions().add(sources);

    return m;
  }

  /**
   * Tests that metadata with null, empty, and space-only classifiers are considered equal.
   */
  @Test
  void metadataWithNullAndEmptyAreEqual() {
    final Metadata nullClassifier = v("org.foo", "some-project", "v-", "20150324121700", 1);
    final Metadata emptyClassifier = v("org.foo", "some-project", "v-", "20150324121700", 1);
    final Metadata spaceClassifier = v("org.foo", "some-project", "v-", "20150324121700", 1);

    nullClassifier.getVersioning().getSnapshotVersions().get(0).setClassifier(null);
    emptyClassifier.getVersioning().getSnapshotVersions().get(0).setClassifier("");
    spaceClassifier.getVersioning().getSnapshotVersions().get(0).setClassifier("   ");

    assertThat(merger.metadataEquals(nullClassifier, emptyClassifier), is(true));
    assertThat(merger.metadataEquals(emptyClassifier, spaceClassifier), is(true));
    assertThat(merger.metadataEquals(spaceClassifier, nullClassifier), is(true));
  }

  /**
   * Tests merging of group-level metadata.
   */
  @Test
  void groupLevelMd() {
    final Metadata m1 = g("foo");
    final Metadata m2 = g("foo", "bar");
    final Metadata m3 = g("baz");

    final Metadata m = merger.merge(
        ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2), new Envelope("3", m3))
    );
    assertThat(m, notNullValue());
    assertThat(m.getModelVersion(), equalTo("1.1.0"));
    assertThat(m.getPlugins(), hasSize(3));
    final List<String> prefixes = Lists.newArrayList(Iterables.transform(m.getPlugins(), 
        (Plugin input) -> input.getArtifactId()));
    assertThat(prefixes, containsInAnyOrder("foo-maven-plugin", "bar-maven-plugin", "baz-maven-plugin"));
  }

  /**
   * Tests merging of artifact-level metadata.
   */
  @Test
  void artifactLevelMd() {
    final Metadata m1 = a("org.foo", "some-project", "20150324121500", "1.0.1", "1.0.1", "1.0.0", "1.0.1");
    final Metadata m2 = a("org.foo", "some-project", "20150324121700", "1.0.2", "1.0.2", "1.0.2");
    final Metadata m3 = a("org.foo", "some-project", "20150324121600", "1.1.0-SNAPSHOT", null, "1.1.0-SNAPSHOT");

    final Metadata m = merger.merge(
        ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2), new Envelope("3", m3))
    );
    assertThat(m, notNullValue());
    assertThat(m.getModelVersion(), equalTo("1.1.0"));
    assertThat(m.getGroupId(), equalTo("org.foo"));
    assertThat(m.getArtifactId(), equalTo("some-project"));
    assertThat(m.getVersioning().getLastUpdated(), equalTo("20150324121700"));
    assertThat(m.getVersioning().getSnapshot(), nullValue());
    assertThat(m.getVersioning().getRelease(), equalTo("1.0.2"));
    assertThat(m.getVersioning().getLatest(), equalTo("1.1.0-SNAPSHOT"));
    assertThat(m.getVersioning().getVersions(), contains("1.0.0", "1.0.1", "1.0.2", "1.1.0-SNAPSHOT"));
  }

  /**
   * Tests merging of version-level metadata.
   */
  @Test
  void versionLevelMd() {
    final Metadata m1 = v("org.foo", "some-project", "1.0.0", "20150324.121500", 3);
    final Metadata m2 = v("org.foo", "some-project", "1.0.0", "20150323.121500", 2);
    final Metadata m3 = v("org.foo", "some-project", "1.0.0", "20150322.121500", 1);

    final Metadata m = merger.merge(
        ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2), new Envelope("3", m3))
    );
    assertThat(m, notNullValue());
    assertThat(m.getModelVersion(), equalTo("1.1.0"));
    assertThat(m.getGroupId(), equalTo("org.foo"));
    assertThat(m.getArtifactId(), equalTo("some-project"));
    assertThat(m.getVersioning().getLastUpdated(), equalTo("20150324121500"));
    assertThat(m.getVersioning().getSnapshot(), notNullValue());
    assertThat(m.getVersioning().getSnapshot().getTimestamp(), equalTo("20150324.121500"));
    assertThat(m.getVersioning().getSnapshot().getBuildNumber(), equalTo(3));
  }

  /**
   * Tests merging of mixed-level metadata.
   */
  @Test
  void mixedLevelMd() {
    final Metadata m1 = a("org.foo", "some-project", "20150324121500", "1.0.1", "1.0.1", "1.0.0", "1.0.1");
    final Metadata m2 = g("foo", "bar");
    final Metadata m3 = v("org.foo", "some-project", "1.1.0", "20150322.121500", 3);

    final Metadata m = merger.merge(
        ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2), new Envelope("3", m3))
    );
    assertThat(m, notNullValue());
    assertThat(m.getModelVersion(), equalTo("1.1.0"));
    assertThat(m.getGroupId(), equalTo("org.foo"));
    assertThat(m.getArtifactId(), equalTo("some-project"));
    assertThat(m.getVersion(), equalTo("1.1.0-SNAPSHOT"));
    assertThat(m.getVersioning().getLastUpdated(), equalTo("20150324121500"));
    assertThat(m.getVersioning().getSnapshot(), notNullValue());
    assertThat(m.getVersioning().getSnapshot().getTimestamp(), equalTo("20150322.121500"));
    assertThat(m.getVersioning().getSnapshot().getBuildNumber(), equalTo(3));
    assertThat(m.getVersioning().getRelease(), equalTo("1.0.1"));
    assertThat(m.getVersioning().getLatest(), equalTo("1.0.1"));
    assertThat(m.getVersioning().getVersions(), contains("1.0.0", "1.0.1"));
    assertThat(m.getPlugins(), hasSize(2));
    final List<String> prefixes = Lists.newArrayList(Iterables.transform(m.getPlugins(), 
        (Plugin input) -> input.getArtifactId()));
    assertThat(prefixes, containsInAnyOrder("foo-maven-plugin", "bar-maven-plugin"));
  }

  /**
   * Tests handling of version in artifact-level metadata.
   * <p>
   * NEXUS-13085: Some maven-metadata.xml files are contrary to the present spec 
   * (http://maven.apache.org/ref/3.3.9/maven-repository-metadata/repository-metadata.html) and contain a 'version' 
   * element for non-SNAPSHOT artifacts, allowing for lax validation.
   */
  @Test
  void allowVersionInArtifactLevelMetadata() {
    Metadata m1 = a("org.foo", "some-project", "20150324121500", "1.0.0","1.0.0", "1.0.0");
    m1.setVersion("1.0.0");
    Metadata m2 = a("org.foo", "some-project", "20150324121501", "1.0.1","1.0.1", "1.0.1");
    m2.setVersion("1.0.1");

    final Metadata m = merger.merge(
        ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2))
    );
    assertThat(m.getVersion(), is(m1.getVersion())); // target version is left intact, no attempt to merge
    assertThat(m.getVersioning().getRelease(), is(m2.getVersion()));
    assertThat(m.getVersioning().getLastUpdated(), is(m2.getVersioning().getLastUpdated()));
    assertThat(m.getVersioning().getVersions(), contains("1.0.0", "1.0.1"));
  }

  /**
   * Tests handling of null snapshot timestamps.
   */
  @Test
  void handleNullSnapshotTimestamps() {
    Metadata m1 = v("org.foo", "some-project", "1.0.0", "20150322.121500", 1);
    m1.getVersioning().getSnapshot().setTimestamp(null);
    Metadata m2 = v("org.foo", "some-project", "1.0.0", "20150323.121500", 2);

    final Metadata m = merger.merge(
        ImmutableList.of(new Envelope("1", m1), new Envelope("2", m2))
    );
    assertThat(m, notNullValue());
    assertThat(m.getModelVersion(), equalTo("1.1.0"));
    assertThat(m.getGroupId(), equalTo("org.foo"));
    assertThat(m.getArtifactId(), equalTo("some-project"));
    assertThat(m.getVersioning().getLastUpdated(), equalTo("20150323121500"));
    assertThat(m.getVersioning().getSnapshot(), notNullValue());
    assertThat(m.getVersioning().getSnapshot().getTimestamp(), equalTo("20150323.121500"));
    assertThat(m.getVersioning().getSnapshot().getBuildNumber(), equalTo(2));
  }
}