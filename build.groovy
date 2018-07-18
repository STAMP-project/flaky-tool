#!/usr/bin/env groovy

/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
import hudson.FilePath
import hudson.util.IOUtils
import javax.xml.bind.DatatypeConverter
import hudson.tasks.test.AbstractTestResultAction
import com.jenkinsci.plugins.badge.action.BadgeAction
import com.cloudbees.groovy.cps.NonCPS

def call(body)
{
    //[...]

    // Initialize a variable that hold past failing tests. We need this because we can't get access to the failing
    // tests for a given Maven run (we can only get them globally).
    // See https://groups.google.com/d/msg/jenkinsci-users/dDDPC486JWE/9vtojUOoAwAJ
    // Note1: we save TesResult objects in this list and they are serializable.
    // Note2: This strategy is not working and cannot work because of parallel() executions, see
    //        https://issues.jenkins-ci.org/browse/JENKINS-49339. The consequence is that we can get several emails
    //        sent for the same test errors (no past failing test when the various maven build start in // and as
    //        soon as one has a failing test they'll all report is as a new failing test).
    def savedFailingTests = getFailingTests()
    echoXWiki "Past failing tests: ${savedFailingTests.collect { "${it.getClassName()}#${it.getName()}" }}"

    //[...]

    stage("Post Build") {
        // If the job made it to here it means the Maven build has either succeeded or some tests have failed.
        // If the build has succeeded, then currentBuild.result is null (since withMaven doesn't set it in this case).
        if (currentBuild.result == null) {
            currentBuild.result = 'SUCCESS'
        }

        if (currentBuild.result != 'SUCCESS') {
            def failingTests = getFailingTestsSinceLastMavenExecution(savedFailingTests)
            if (!failingTests.isEmpty()) {
                // Check for false positives & Flickers.
                echoXWiki "Checking for false positives and flickers in build results..."
                def jiraURL = [... your jira url here...]
                // Example:
                // "https://jira.xwiki.org/sr/jira.issueviews:searchrequest-xml/temp/SearchRequest.xml?".concat(
                //      "jqlQuery=%22Flickering%20Test%22%20is%20not%20empty%20and%20resolution%20=%20Unresolved")
                def containsFalsePositivesOrOnlyFlickers = checkForFalsePositivesAndFlickers(failingTests, jiraURL)

                // Also send a mail notification when the job is not successful.
                echoXWiki "Checking if email should be sent or not"
                if (!containsFalsePositivesOrOnlyFlickers) {
                    notifyByMail(currentBuild.result)
                } else {
                    echoXWiki "No email sent even if some tests failed because they contain only flickering tests!"
                    echoXWiki "Considering job as successful!"
                    currentBuild.result = 'SUCCESS'
                }
            }
        }
    }
}

def notifyByMail(String buildStatus)
{
    // Send email for example using the emailext step
}

/**
 * Check for false positives for known cases of failures not related to code + check for test flickers.
 *
 * @return true if the build has false positives or if there are only flickering tests
 */
def checkForFalsePositivesAndFlickers(def failingTests, def jiraURL)
{
    // Step 1: Check for false positives
    def containsFalsePositives = checkForFalsePositives()

    // Step 2: Check for flickers
    def containsOnlyFlickers = checkForFlickers(failingTests, jiraURL)

    return containsFalsePositives || containsOnlyFlickers
}

/**
 * Check for false positives for known cases of failures not related to code.
 *
 * @return true if false positives have been detected
 */
def checkForFalsePositives()
{
    def messages = [
        [".*A fatal error has been detected by the Java Runtime Environment.*", "JVM Crash", "A JVM crash happened!"],
        [".*Error: cannot open display: :1.0.*", "VNC not running", "VNC connection issue!"],
        [".*java.lang.NoClassDefFoundError: Could not initialize class sun.awt.X11GraphicsEnvironment.*", "VNC issue",
             "VNC connection issue!"],
        [".*hudson.plugins.git.GitException: Could not fetch from any repository.*", "Git issue",
             "Git fetching issue!"],
        [".*Error communicating with the remote browser. It may have died..*", "Browser issue",
             "Connection to Browser has died!"],
        [".*Failed to start XWiki in .* seconds.*", "XWiki Start", "Failed to start XWiki fast enough!"],
        [".*Failed to transfer file.*nexus.*Return code is:.*ReasonPhrase:Service Temporarily Unavailable.",
             "Nexus down", "Nexus is down!"],
        [".*com.jcraft.jsch.JSchException: java.net.UnknownHostException: maven.xwiki.org.*",
             "maven.xwiki.org unavailable", "maven.xwiki.org is not reachable!"],
        [".*Fatal Error: Unable to find package java.lang in classpath or bootclasspath.*", "Compilation issue",
             "Compilation issue!"],
        [".*hudson.plugins.git.GitException: Command.*", "Git issue", "Git issue!"],
        [".*Caused by: org.openqa.selenium.WebDriverException: Failed to connect to binary FirefoxBinary.*",
             "Browser issue", "Browser setup is wrong somehow!"],
        [".*java.net.SocketTimeoutException: Read timed out.*", "Unknown connection issue",
             "Unknown connection issue!"],
        [".*Can't connect to X11 window server.*", "VNC not running", "VNC connection issue!"],
        [".*The forked VM terminated without saying properly goodbye.*", "Surefire Forked VM crash",
             "Surefire Forked VM issue!"],
        [".*java.lang.RuntimeException: Unexpected exception deserializing from disk cache.*", "GWT building issue",
             "GWT building issue!"],
        [".*Unable to bind to locking port 7054.*", "Selenium bind issue with browser", "Selenium issue!"],
        [".*Error binding monitor port 8079: java.net.BindException: Cannot assign requested address.*",
             "XWiki instance already running", "XWiki stop issue!"],
        [".*Caused by: java.util.zip.ZipException: invalid block type.*", "Maven build error",
             "Maven generated some invalid Zip file"],
        [".*java.lang.ClassNotFoundException: org.jvnet.hudson.maven3.listeners.MavenProjectInfo.*", "Jenkins error",
             "Unknown Jenkins error!"],
        [".*Failed to execute goal org.codehaus.mojo:sonar-maven-plugin.*No route to host.*", "Sonar error",
             "Error connecting to Sonar!"]
    ]

    messages.each { message ->
        if (manager.logContains(message.get(0))) {
            manager.addWarningBadge(message.get(1))
            manager.createSummary("warning.gif").appendText("<h1>${message.get(2)}</h1>", false, false, false, "red")
            manager.buildUnstable()
            echoXWiki "False positive detected [${message.get(2)}] ..."
            return true
        }
    }

    return false
}

/**
 * Check for test flickers, and modify test result descriptions for tests that are identified as flicker. A test is
 * a flicker if there's a JIRA issue having the "Flickering Test" custom field containing the FQN of the test in the
 * format {@code <java package name>#<test name>}.
 *
 * @return true if the failing tests only contain flickering tests
 */
def checkForFlickers(def failingTests, def jiraURL)
{
    def knownFlickers = getKnownFlickeringTests(jiraURL)
    echoXWiki "Known flickering tests: ${knownFlickers}"

    // For each failed test, check if it's in the known flicker list.
    def containsAtLeastOneFlicker = false
    boolean containsOnlyFlickers = true
    failingTests.each() { testResult ->
        // Format of a Test Result id is "junit/<package name>/<test class name>/<test method name>"
        // Example: "junit/org.xwiki.test.ui.repository/RepositoryTest/validateAllFeatures"
        // => testName = "org.xwiki.test.ui.repository.RepositoryTest#validateAllFeatures"
        def parts = testResult.getId().split('/')
        def testName = "${parts[1]}.${parts[2]}#${parts[3]}".toString()
        echoXWiki "Analyzing test [${testName}] for flicker..."
        if (knownFlickers.contains(testName)) {
            // Add the information that the test is a flicker to the test's description. Only display this
            // once (a Jenkinsfile can contain several builds and this this code can be called several times
            // for the same tests).
            def flickeringText = 'This is a flickering test'
            if (testResult.getDescription() == null || !testResult.getDescription().contains(flickeringText)) {
                testResult.setDescription(
                    "<h1 style='color:red'>${flickeringText}</h1>${testResult.getDescription() ?: ''}")
            }
            echo "   It's a flicker"
            containsAtLeastOneFlicker = true
        } else {
            echo "   Not a flicker"
            // This is a real failing test, thus we'll need to send the notification email...
            containsOnlyFlickers = false
        }
    }

    if (containsAtLeastOneFlicker) {
        // Only add the badge if none already exist
        def badgeText = 'Contains some flickering tests'
        def badgeFound = isBadgeFound(
            currentBuild.getRawBuild().getActions(BadgeAction.class), badgeText)
        if (!badgeFound) {
            manager.addWarningBadge(badgeText)
            manager.createSummary("warning.gif").appendText("<h1>${badgeText}</h1>", false, false, false, "red")
        }
    }

    return containsOnlyFlickers
}

@NonCPS
def isBadgeFound(def badgeActionItems, def badgeText)
{
    badgeActionItems.each() {
        if (it.getText().contains(badgeText)) {
            return true
        }
    }
    return false
}

/**
 * @return all known flickering tests from JIRA in the format
 *         {@code org.xwiki.test.ui.repository.RepositoryTest#validateAllFeatures}
 */
@NonCPS
def getKnownFlickeringTests(def jiraURL)
{
    def knownFlickers = []
    def root = new XmlSlurper().parseText(jiraURL.toURL().text)
    // Note: slurper nodes are not seralizable, hence the @NonCPS annotation above.
    def packageName = ''
    root.channel.item.customfields.customfield.each() { customfield ->
        if (customfield.customfieldname == 'Flickering Test') {
            customfield.customfieldvalues.customfieldvalue.text().split(',').each() {
                // Check if a package is specified and if not use the previously found package name
                // This is an optimization to make it shorter to specify several tests in the same test class.
                // e.g.: "org.xwiki.test.ui.extension.ExtensionTest#testUpgrade,testUninstall"
                def fullName
                int pos = it.indexOf('#')
                if (pos > -1) {
                    packageName = it.substring(0, pos)
                    fullName = it
                } else {
                    fullName = "${packageName}.${it}".toString()
                }
                knownFlickers.add(fullName)
            }
        }
    }

    return knownFlickers
}

def getFailingTests()
{
    def failingTests
    AbstractTestResultAction testResultAction = currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
    if (testResultAction != null) {
        failingTests = testResultAction.getResult().getFailedTests()
    } else {
        // No tests were run in this build, nothing left to do.
        failingTests = []
    }
    return failingTests
}

def getFailingTestsSinceLastMavenExecution(savedFailingTests)
{
    def failingTestsSinceLastExecution = []
    def fullFailingTests = getFailingTests()
    def savedFailingTestsAsStringList = savedFailingTests.collect { "${it.getClassName()}#${it.getName()}" }
    echo "All failing tests at this point: ${fullFailingTests.collect { "${it.getClassName()}#${it.getName()}" }}"
    fullFailingTests.each() {
        def fullTestName = "${it.getClassName()}#${it.getName()}"
        if (!savedFailingTestsAsStringList.contains(fullTestName)) {
            failingTestsSinceLastExecution.add(it)
        }
    }
    return failingTestsSinceLastExecution
}

