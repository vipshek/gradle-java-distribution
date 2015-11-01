/*
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * <http://www.apache.org/licenses/LICENSE-2.0>
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.gradle.javadist

import java.nio.file.Files

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder

import spock.lang.Specification

class JavaDistributionPluginTests extends Specification {

    @Rule
    TemporaryFolder temporaryFolder = new TemporaryFolder()

    File projectDir
    File buildFile
    List<File> pluginClasspath

    def 'produce distribution bundle and check start, stop and restart behavior' () {
        given:
        createUntarBuildFile(buildFile)

        temporaryFolder.newFolder('src', 'main', 'java', 'test')
        temporaryFolder.newFile('src/main/java/test/Test.java') << '''
        package test;
        public class Test {
            public static void main(String[] args) throws InterruptedException {
                System.out.println("Test started");
                java.lang.Thread.sleep(100000);
            }
        }
        '''.stripIndent()

        when:
        BuildResult buildResult = run('build', 'distTar', 'untar').build()

        then:
        buildResult.task(':build').outcome == TaskOutcome.SUCCESS
        buildResult.task(':distTar').outcome == TaskOutcome.SUCCESS
        buildResult.task(':untar').outcome == TaskOutcome.SUCCESS

        // check content was extracted
        new File(projectDir, 'dist/service-name-0.1').exists()

        // try all of the service commands
        exec('dist/service-name-0.1/service/bin/init.sh', 'start') ==~ /(?m)Running 'service-name'\.\.\.\s+Started \(\d+\)\n/
        sleep 5000
        readFully('dist/service-name-0.1/var/log/service-name-startup.log').equals('Test started\n')
        exec('dist/service-name-0.1/service/bin/init.sh', 'status') ==~ /(?m)Checking 'service-name'\.\.\.\s+Running \(\d+\)\n/
        sleep 5000
        exec('dist/service-name-0.1/service/bin/init.sh', 'restart') ==~
            /(?m)Stopping 'service-name'\.\.\.\s+Stopped \(\d+\)\nRunning 'service-name'\.\.\.\s+Started \(\d+\)\n/
        sleep 5000
        exec('dist/service-name-0.1/service/bin/init.sh', 'stop') ==~ /(?m)Stopping 'service-name'\.\.\.\s+Stopped \(\d+\)\n/

        // check manifest was created
        new File(projectDir, 'build/deployment/manifest.yaml').exists()
        String manifest = readFully('dist/service-name-0.1/deployment/manifest.yaml')
        manifest.contains('productName: service-name\n')
        manifest.contains('productVersion: 0.1\n')
    }

    def 'produce distribution bundle and check var/log and var/run are excluded' () {
        given:
        createUntarBuildFile(buildFile)

        temporaryFolder.newFolder('var', 'log')
        temporaryFolder.newFile('var/log/service-name.log')
        temporaryFolder.newFolder('var', 'run')
        temporaryFolder.newFile('var/run/service-name.pid')
        temporaryFolder.newFolder('var', 'conf')
        temporaryFolder.newFile('var/conf/service-name.yml')

        when:
        BuildResult buildResult = run('build', 'distTar', 'untar').build()

        then:
        buildResult.task(':build').outcome == TaskOutcome.SUCCESS
        buildResult.task(':distTar').outcome == TaskOutcome.SUCCESS
        buildResult.task(':untar').outcome == TaskOutcome.SUCCESS

        // check content was extracted
        new File(projectDir, 'dist/service-name-0.1').exists()
        !new File(projectDir, 'dist/service-name-0.1/var/log').exists()
        !new File(projectDir, 'dist/service-name-0.1/var/run').exists()
        new File(projectDir, 'dist/service-name-0.1/var/conf/service-name.yml').exists()
    }

    def 'produce distribution bundle with a non-string version object' () {
        given:
        buildFile << '''
            plugins {
                id 'com.palantir.java-distribution'
                id 'java'
            }

            class MyVersion {
                String version

                MyVersion(String version) {
                    this.version = version
                }

                String toString() {
                    return this.version
                }
            }

            version new MyVersion('0.1')

            distribution {
                serviceName 'service-name'
                mainClass 'test.Test'
            }

            sourceCompatibility = '1.7'

            // most convenient way to untar the dist is to use gradle
            task untar (type: Copy) {
                from tarTree(resources.gzip("${buildDir}/distributions/service-name-0.1.tgz"))
                into "${projectDir}/dist"
                dependsOn distTar
            }
        '''.stripIndent()

        when:
        BuildResult buildResult = run('build', 'distTar', 'untar').build()

        then:
        buildResult.task(':build').outcome == TaskOutcome.SUCCESS
        buildResult.task(':distTar').outcome == TaskOutcome.SUCCESS
        buildResult.task(':untar').outcome == TaskOutcome.SUCCESS

        // check content was extracted
        new File(projectDir, 'dist/service-name-0.1').exists()

        // check manifest was created
        new File(projectDir, 'build/deployment/manifest.yaml').exists()
        String manifest = readFully('dist/service-name-0.1/deployment/manifest.yaml')
        manifest.contains('productName: service-name\n')
        manifest.contains('productVersion: 0.1\n')
    }

    def 'produce distribution bundle with files in deployment/' () {
        given:
        createUntarBuildFile(buildFile)

        String deploymentConfiguration = 'log: service-name.log'
        temporaryFolder.newFolder('deployment')
        temporaryFolder.newFile('deployment/manifest.yaml') << 'invalid manifest'
        temporaryFolder.newFile('deployment/configuration.yaml') << deploymentConfiguration

        when:
        BuildResult buildResult = run('build', 'distTar', 'untar').build()

        then:
        buildResult.task(':build').outcome == TaskOutcome.SUCCESS
        buildResult.task(':distTar').outcome == TaskOutcome.SUCCESS
        buildResult.task(':untar').outcome == TaskOutcome.SUCCESS

        new File(projectDir, 'dist/service-name-0.1').exists()

        // clobbers deployment/manifest.yaml
        new File(projectDir, 'dist/service-name-0.1/deployment/manifest.yaml').exists()
        String manifest = readFully('dist/service-name-0.1/deployment/manifest.yaml')
        manifest.contains('productName: service-name\n')
        manifest.contains('productVersion: 0.1\n')

        // check files in deployment/ copied successfully
        new File(projectDir, 'dist/service-name-0.1/deployment/configuration.yaml').exists()
        String configuration = readFully('dist/service-name-0.1/deployment/configuration.yaml')
        configuration.equals(deploymentConfiguration)
    }

    private def createUntarBuildFile(buildFile) {
        buildFile << '''
            plugins {
                id 'com.palantir.java-distribution'
                id 'java'
            }

            version '0.1'

            distribution {
                serviceName 'service-name'
                mainClass 'test.Test'
            }

            sourceCompatibility = '1.7'

            // most convenient way to untar the dist is to use gradle
            task untar (type: Copy) {
                from tarTree(resources.gzip("${buildDir}/distributions/service-name-0.1.tgz"))
                into "${projectDir}/dist"
                dependsOn distTar
            }
        '''.stripIndent()
    }

    private String readFully(String file) {
        return new String(Files.readAllBytes(projectDir.toPath().resolve(file)))
    }

    private GradleRunner run(String... tasks) {
        GradleRunner.create()
            .withPluginClasspath(pluginClasspath)
            .withProjectDir(projectDir)
            .withArguments(tasks)
    }

    private String exec(String... tasks) {
        StringBuffer sout = new StringBuffer(), serr = new StringBuffer()
        Process proc = new ProcessBuilder().command(tasks).directory(projectDir).start()
        proc.consumeProcessOutput(sout, serr)
        proc.waitFor()
        return sout.toString()
    }

    def setup() {
        projectDir = temporaryFolder.root
        buildFile = temporaryFolder.newFile('build.gradle')

        def pluginClasspathResource = getClass().classLoader.findResource("plugin-classpath.txt")
        if (pluginClasspathResource == null) {
            throw new IllegalStateException("Did not find plugin classpath resource, run `testClasses` build task.")
        }

        pluginClasspath = pluginClasspathResource.readLines()
            .collect { it.replace('\\', '\\\\') } // escape backslashes in Windows paths
            .collect { new File(it) }
    }

}
