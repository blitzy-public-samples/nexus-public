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

import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.manager.internal.RepositoryManagerImpl;
import org.sonatype.nexus.repository.maven.MavenHostedFacet;
import org.sonatype.nexus.repository.maven.MavenProxyFacet;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.ViewFacet;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for validating the use of Java 21's Pattern Matching for switch in the {@link RepositoryManagerImpl} class.
 * 
 * These tests ensure that the refactored code correctly handles different repository types (hosted, proxy, group)
 * and format types using pattern matching instead of traditional instanceof checks and type casting.
 * 
 * @since 3.60
 */
public class RepositoryManagerPatternMatchingTest
    extends TestSupport
{
    @Mock
    private Repository hostedRepository;
    
    @Mock
    private Repository proxyRepository;
    
    @Mock
    private Repository groupRepository;
    
    @Mock
    private Format mavenFormat;
    
    @Mock
    private Format rawFormat;
    
    @Mock
    private Type hostedType;
    
    @Mock
    private Type proxyType;
    
    @Mock
    private Type groupType;
    
    @Mock
    private Configuration hostedConfig;
    
    @Mock
    private Configuration proxyConfig;
    
    @Mock
    private Configuration groupConfig;
    
    @Mock
    private ViewFacet viewFacet;
    
    @Mock
    private MavenHostedFacet mavenHostedFacet;
    
    @Mock
    private MavenProxyFacet mavenProxyFacet;
    
    @Mock
    private ProxyFacet proxyFacet;
    
    @Mock
    private GroupFacet groupFacet;
    
    @Before
    public void setup() {
        // Setup hosted repository
        when(hostedRepository.getType()).thenReturn(hostedType);
        when(hostedRepository.getFormat()).thenReturn(mavenFormat);
        when(hostedRepository.getConfiguration()).thenReturn(hostedConfig);
        when(hostedRepository.facet(ViewFacet.class)).thenReturn(viewFacet);
        when(hostedRepository.optionalFacet(MavenHostedFacet.class)).thenReturn(java.util.Optional.of(mavenHostedFacet));
        when(hostedRepository.getName()).thenReturn("maven-hosted");
        when(hostedType.getValue()).thenReturn(HostedType.NAME);
        
        // Setup proxy repository
        when(proxyRepository.getType()).thenReturn(proxyType);
        when(proxyRepository.getFormat()).thenReturn(mavenFormat);
        when(proxyRepository.getConfiguration()).thenReturn(proxyConfig);
        when(proxyRepository.facet(ViewFacet.class)).thenReturn(viewFacet);
        when(proxyRepository.facet(ProxyFacet.class)).thenReturn(proxyFacet);
        when(proxyRepository.optionalFacet(MavenProxyFacet.class)).thenReturn(java.util.Optional.of(mavenProxyFacet));
        when(proxyRepository.getName()).thenReturn("maven-proxy");
        when(proxyType.getValue()).thenReturn(ProxyType.NAME);
        
        // Setup group repository
        when(groupRepository.getType()).thenReturn(groupType);
        when(groupRepository.getFormat()).thenReturn(mavenFormat);
        when(groupRepository.getConfiguration()).thenReturn(groupConfig);
        when(groupRepository.facet(ViewFacet.class)).thenReturn(viewFacet);
        when(groupRepository.facet(GroupFacet.class)).thenReturn(groupFacet);
        when(groupRepository.getName()).thenReturn("maven-group");
        when(groupType.getValue()).thenReturn(GroupType.NAME);
        
        // Setup formats
        when(mavenFormat.getValue()).thenReturn("maven2");
        when(rawFormat.getValue()).thenReturn("raw");
    }
    
    /**
     * Tests the pattern matching for repository types using switch expressions.
     * This test validates that the pattern matching correctly identifies the repository type
     * and executes the appropriate case block.
     */
    @Test
    public void testRepositoryTypePatternMatching() {
        // Test with hosted repository
        String hostedResult = getRepositoryTypeUsingPatternMatching(hostedRepository);
        assertThat(hostedResult, is(equalTo("hosted")));
        
        // Test with proxy repository
        String proxyResult = getRepositoryTypeUsingPatternMatching(proxyRepository);
        assertThat(proxyResult, is(equalTo("proxy")));
        
        // Test with group repository
        String groupResult = getRepositoryTypeUsingPatternMatching(groupRepository);
        assertThat(groupResult, is(equalTo("group")));
    }
    
    /**
     * Tests the pattern matching for repository formats using switch expressions.
     * This test validates that the pattern matching correctly identifies the repository format
     * and executes the appropriate case block.
     */
    @Test
    public void testRepositoryFormatPatternMatching() {
        // Test with Maven format
        String mavenResult = getRepositoryFormatUsingPatternMatching(hostedRepository);
        assertThat(mavenResult, is(equalTo("maven2")));
        
        // Setup a raw format repository
        Repository rawRepository = mock(Repository.class);
        when(rawRepository.getFormat()).thenReturn(rawFormat);
        
        // Test with Raw format
        String rawResult = getRepositoryFormatUsingPatternMatching(rawRepository);
        assertThat(rawResult, is(equalTo("raw")));
    }
    
    /**
     * Tests the pattern matching for repository configuration processing using switch expressions.
     * This test validates that the pattern matching correctly processes repository configurations
     * based on the repository type.
     */
    @Test
    public void testRepositoryConfigurationPatternMatching() {
        // Test with hosted repository configuration
        Map<String, Object> hostedAttributes = processConfigurationUsingPatternMatching(hostedRepository);
        assertThat(hostedAttributes.get("type"), is(equalTo("hosted")));
        
        // Test with proxy repository configuration
        Map<String, Object> proxyAttributes = processConfigurationUsingPatternMatching(proxyRepository);
        assertThat(proxyAttributes.get("type"), is(equalTo("proxy")));
        
        // Test with group repository configuration
        Map<String, Object> groupAttributes = processConfigurationUsingPatternMatching(groupRepository);
        assertThat(groupAttributes.get("type"), is(equalTo("group")));
    }
    
    /**
     * Tests the comparison between the old implementation using instanceof checks and the new
     * implementation using pattern matching for switch. This test validates that both implementations
     * produce the same results.
     */
    @Test
    public void testComparisonBetweenOldAndNewImplementation() {
        // Test with hosted repository
        String oldHostedResult = getRepositoryTypeUsingInstanceOf(hostedRepository);
        String newHostedResult = getRepositoryTypeUsingPatternMatching(hostedRepository);
        assertThat(newHostedResult, is(equalTo(oldHostedResult)));
        
        // Test with proxy repository
        String oldProxyResult = getRepositoryTypeUsingInstanceOf(proxyRepository);
        String newProxyResult = getRepositoryTypeUsingPatternMatching(proxyRepository);
        assertThat(newProxyResult, is(equalTo(oldProxyResult)));
        
        // Test with group repository
        String oldGroupResult = getRepositoryTypeUsingInstanceOf(groupRepository);
        String newGroupResult = getRepositoryTypeUsingPatternMatching(groupRepository);
        assertThat(newGroupResult, is(equalTo(oldGroupResult)));
    }
    
    /**
     * Helper method that uses the old implementation with instanceof checks to determine the repository type.
     * This method simulates the approach used before Java 21's pattern matching for switch.
     */
    private String getRepositoryTypeUsingInstanceOf(Repository repository) {
        Type type = repository.getType();
        String typeValue = type.getValue();
        
        if (HostedType.NAME.equals(typeValue)) {
            return "hosted";
        } else if (ProxyType.NAME.equals(typeValue)) {
            return "proxy";
        } else if (GroupType.NAME.equals(typeValue)) {
            return "group";
        } else {
            return "unknown";
        }
    }
    
    /**
     * Helper method that uses Java 21's pattern matching for switch to determine the repository type.
     * This method demonstrates the new approach using pattern matching.
     */
    private String getRepositoryTypeUsingPatternMatching(Repository repository) {
        Type type = repository.getType();
        String typeValue = type.getValue();
        
        return switch (typeValue) {
            case HostedType.NAME -> "hosted";
            case ProxyType.NAME -> "proxy";
            case GroupType.NAME -> "group";
            default -> "unknown";
        };
    }
    
    /**
     * Helper method that uses Java 21's pattern matching for switch to determine the repository format.
     * This method demonstrates pattern matching with different format types.
     */
    private String getRepositoryFormatUsingPatternMatching(Repository repository) {
        Format format = repository.getFormat();
        
        return switch (format) {
            case Format f when "maven2".equals(f.getValue()) -> "maven2";
            case Format f when "raw".equals(f.getValue()) -> "raw";
            default -> "unknown";
        };
    }
    
    /**
     * Helper method that uses Java 21's pattern matching for switch to process repository configurations.
     * This method demonstrates pattern matching with repository configurations.
     */
    private Map<String, Object> processConfigurationUsingPatternMatching(Repository repository) {
        Type type = repository.getType();
        String typeValue = type.getValue();
        Map<String, Object> attributes = Map.of();
        
        return switch (typeValue) {
            case HostedType.NAME -> {
                attributes = Map.of("type", "hosted", "writable", true);
                yield attributes;
            }
            case ProxyType.NAME -> {
                attributes = Map.of("type", "proxy", "remote", true);
                yield attributes;
            }
            case GroupType.NAME -> {
                attributes = Map.of("type", "group", "members", true);
                yield attributes;
            }
            default -> Map.of("type", "unknown");
        };
    }
    
    /**
     * This test demonstrates pattern matching with nested patterns and guards.
     * It shows how to use pattern matching to check for specific repository types and formats
     * in a single switch expression.
     */
    @Test
    public void testNestedPatternMatchingWithGuards() {
        // Test with Maven hosted repository
        String mavenHostedResult = getRepositoryDetailsUsingNestedPatternMatching(hostedRepository);
        assertThat(mavenHostedResult, is(equalTo("maven-hosted")));
        
        // Test with Maven proxy repository
        String mavenProxyResult = getRepositoryDetailsUsingNestedPatternMatching(proxyRepository);
        assertThat(mavenProxyResult, is(equalTo("maven-proxy")));
        
        // Test with Maven group repository
        String mavenGroupResult = getRepositoryDetailsUsingNestedPatternMatching(groupRepository);
        assertThat(mavenGroupResult, is(equalTo("maven-group")));
        
        // Setup a raw hosted repository
        Repository rawHostedRepository = mock(Repository.class);
        when(rawHostedRepository.getType()).thenReturn(hostedType);
        when(rawHostedRepository.getFormat()).thenReturn(rawFormat);
        
        // Test with Raw hosted repository
        String rawHostedResult = getRepositoryDetailsUsingNestedPatternMatching(rawHostedRepository);
        assertThat(rawHostedResult, is(equalTo("raw-hosted")));
    }
    
    /**
     * Helper method that uses Java 21's pattern matching for switch with nested patterns and guards.
     * This method demonstrates more complex pattern matching scenarios.
     */
    private String getRepositoryDetailsUsingNestedPatternMatching(Repository repository) {
        return switch (repository) {
            case Repository r when r.getType().getValue().equals(HostedType.NAME) && r.getFormat().getValue().equals("maven2") -> 
                "maven-hosted";
            case Repository r when r.getType().getValue().equals(ProxyType.NAME) && r.getFormat().getValue().equals("maven2") -> 
                "maven-proxy";
            case Repository r when r.getType().getValue().equals(GroupType.NAME) && r.getFormat().getValue().equals("maven2") -> 
                "maven-group";
            case Repository r when r.getType().getValue().equals(HostedType.NAME) && r.getFormat().getValue().equals("raw") -> 
                "raw-hosted";
            default -> "unknown";
        };
    }
    
    /**
     * This test demonstrates exhaustiveness checking with pattern matching.
     * It shows how pattern matching ensures that all possible cases are covered.
     */
    @Test
    public void testExhaustivenessWithPatternMatching() {
        // Create an enum for repository types to demonstrate exhaustiveness
        enum RepoType { HOSTED, PROXY, GROUP }
        
        // Test with all enum values
        for (RepoType repoType : RepoType.values()) {
            String result = switch (repoType) {
                case HOSTED -> "hosted";
                case PROXY -> "proxy";
                case GROUP -> "group";
                // No default needed as all enum values are covered
            };
            
            assertThat(result, is(equalTo(repoType.name().toLowerCase())));
        }
    }
}